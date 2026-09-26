package app.mizan.service.authority

import app.mizan.domain.approval.ApprovalPolicy
import app.mizan.domain.approval.ApprovalVerdict
import app.mizan.domain.approval.ProposalDiff
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalEvent
import app.mizan.domain.execution.JournalStage
import app.mizan.domain.execution.JournalStateMachine
import app.mizan.domain.execution.JournalTransition
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.Digests
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.PolicyRequest
import app.mizan.domain.policy.SeparationOfDuties
import app.mizan.domain.policy.SodCode
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.domain.receipt.ReceiptClaims
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.VerificationFingerprint
import app.mizan.domain.risk.RiskEvaluator
import app.mizan.domain.risk.RiskInput
import app.mizan.domain.security.DeviceBindingService
import app.mizan.domain.tool.ToolCatalog
import app.mizan.domain.tool.ToolDefinition
import app.mizan.service.erp.ErpConnector
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.erp.InMemoryErpConnector
import app.mizan.service.json.text
import app.mizan.service.json.wholeOrNumericText
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.ledger.LedgerEntry
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.protocol.executionIdOf
import app.mizan.service.protocol.messageCodeOf
import app.mizan.service.protocol.statusOf
import app.mizan.service.security.LimitSurface
import app.mizan.service.security.RateLimiter
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.UserDirectory
import app.mizan.service.store.ServiceStores
import java.time.Instant
import java.util.UUID

/**
 * The authority. The device proposes; this decides.
 *
 * Everything the phone evaluated is re-evaluated here: the tenant, the tool
 * contract and its version, the policy band, separation of duties, the device
 * proof, and the write itself. Nothing in a request body is trusted because
 * the phone already checked it.
 *
 * Three rules are structural rather than aspirational:
 *
 *  - a write is reported as verified only after a separate read-back whose
 *    fields are compared with what the request said would be true;
 *  - an uncertain write is [ExecutionOutcome.Ambiguous], never a failure and
 *    never a retry, and it opens a reconciliation case;
 *  - every decision is written to a journal whose stage moves through
 *    [JournalStateMachine], so a process death leaves a readable record rather
 *    than a lost request.
 */
class ServiceAuthority(
    private val connector: ErpConnector,
    private val ledger: ExecutionLedger,
    private val audit: AuditLedger,
    private val directory: UserDirectory,
    private val capabilities: ConnectorCapabilities,
    private val policy: PolicyEvaluator,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** When present, the journal, the idempotency index and receipts are durable. */
    private val stores: ServiceStores? = null,
    /** When present, a verified write gets a signed receipt. */
    private val signer: ReceiptSigner? = null,
    private val approvalPolicy: ApprovalPolicy = ApprovalPolicy(),
    private val deviceBinding: DeviceBindingService? = null,
    /** When true, a tool that needs a fresh proof refuses without a valid signature. */
    private val requireDeviceProof: Boolean = false,
    private val versionedPolicy: VersionedPolicy = VersionedPolicy.unversioned,
    private val rateLimiter: RateLimiter? = null,
) {

    private val sod = SeparationOfDuties()

    /** The only writer of the journal. */
    private val book = JournalBook(stores, clock)

    /** What a key already did, and the recording of what it just did. */
    private val idempotency = IdempotencyView(ledger, stores, clock)

    /** The only code that touches the ERP. */
    private val pipeline = ExecutionPipeline(connector, stores, signer, clock, book)

    // ------------------------------------------------------------- entry point

    @Synchronized
    fun decide(
        request: ExecutionRequest,
        user: ServiceUser,
        simulateAmbiguous: Boolean,
    ): ExecutionOutcome {
        val tool = ToolName.fromWire(request.toolWire)
        if (tool == null || tool == ToolName.UNKNOWN) return refuse(request, "TOOL_UNKNOWN", 422)
        if (user.tenantId != request.tenantId) return refuse(request, "TENANT_MISMATCH", 403)
        if (!capabilities.supports(tool)) return refuse(request, "TOOL_NOT_SUPPORTED_BY_ERP", 422)
        if (request.toolVersion.isNotBlank() && request.toolVersion != tool.version) {
            return refuse(request, "TOOL_VERSION_MISMATCH", 422)
        }
        val definition = ToolCatalog.find(tool)
        if (definition != null && ToolCatalog.unavailableCapabilities(tool, connector.capabilities().capabilities).isNotEmpty()) {
            return refuse(request, "TOOL_NOT_SUPPORTED_BY_ERP", 422)
        }

        rateLimiter?.let { limiter ->
            val decision = limiter.consume(LimitSurface.EXECUTION, user.tenantId, user.actorId)
            if (!decision.allowed) {
                return refuse(request, decision.reasonCode, 429, retryAfterSeconds = decision.retryAfterSeconds)
            }
        }

        val canonicalArguments = request.canonicalArguments()
        when (val prior = idempotency.priorFor(request.tenantId, request.idempotencyKey, canonicalArguments)) {
            is IdempotencyView.Prior.Conflict -> return refuse(request, prior.code, prior.httpStatus)
            is IdempotencyView.Prior.Replay -> {
                audit.append(
                    tenantId = request.tenantId,
                    traceId = request.traceId,
                    actorId = user.actorId,
                    action = "EXECUTION_REPLAYED",
                    stateBefore = "REQUEST_RECEIVED",
                    stateAfter = prior.entry.status.uppercase(),
                    details = "key=${request.idempotencyKey?.take(12)} executionId=${prior.entry.executionId}",
                )
                return idempotency.replay(prior.entry)
            }
            IdempotencyView.Prior.None -> Unit
        }

        // Required arguments are checked before policy: a request that is
        // simply missing its amount answers MISSING_AMOUNT, not the refusal
        // code of the approval ladder it happened to fall into.
        requiredArgumentRefusal(tool, request)?.let { return it }

        val actor = Actor(ActorId(user.actorId), user.displayName, user.role, TenantId(user.tenantId))
        val amount = policyAmount(tool, request)
        val customerName = request.arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
        val customer = customerName?.let { connector.customerState(request.tenantId, it) }
        val blocked = customer?.status.equals("blocked", ignoreCase = true)
        val exceeds = exceedsCredit(customer?.creditMinor, customer?.balanceMinor, amount)
        val actorLimit = versionedPolicy.ruleFor(tool, amount?.currency)?.actorLimitMinor

        val decision = policy.evaluate(
            PolicyRequest(
                actor = actor,
                tool = tool,
                amount = amount,
                destructive = tool.destructive,
                customerBlocked = blocked,
                exceedsCredit = exceeds,
                actorLimitMinor = actorLimit,
            ),
        )
        if (!decision.allowed) return refuse(request, decision.reasonCode, 422)

        val approvals = ArrayList<ApprovalRecord>()
        val approverId = request.approverId
        if (!approverId.isNullOrBlank()) {
            val approver = directory.find(request.tenantId, approverId)
                ?: return refuse(request, "APPROVER_UNKNOWN", 422)
            approvals += ApprovalRecord(
                approver = Actor(
                    id = ActorId(approver.actorId),
                    displayName = approver.displayName,
                    role = approver.role,
                    tenantId = TenantId(approver.tenantId),
                ),
                atEpochMillis = clock(),
            )
        }
        val sodCode = sod.check(
            initiatorId = ActorId(user.actorId),
            initiatorTenant = TenantId(user.tenantId),
            approval = decision.approval,
            approvals = approvals,
        )
        if (sodCode != SodCode.SATISFIED && sodCode != SodCode.NOT_REQUIRED) {
            return refuse(request, "SOD_${sodCode.name}", 422)
        }

        // The approval object, when one is named, must still be valid for this
        // exact proposal revision under this exact policy.
        approvalValidationFailure(request, decision.policyVersionId, decision.policyHash, canonicalArguments)
            ?.let { return it }

        // A tool that moves money needs proof that the person was present and
        // that the device is one this tenant enrolled.
        if (definition?.requiresFreshProof == true && requireDeviceProof) {
            val failure = deviceProofFailure(request)
            if (failure != null) return failure
        }

        val risk = RiskEvaluator().assess(
            RiskInput(
                tool = tool,
                destructive = tool.destructive,
                amountTier = decision.riskTier,
                ambiguous = false,
                injectionSuspected = false,
                customerNamed = customerName != null,
                externalUncertain = false,
                sensitiveData = tool == ToolName.REGISTER_PAYMENT,
            ),
        )

        val journal = openJournal(
            request = request,
            tool = tool,
            definition = definition,
            riskTier = decision.riskTier,
            approval = decision.approval,
            canonicalArguments = canonicalArguments,
            actorId = ActorId(user.actorId),
        )
        // PROPOSED -> WAITING_APPROVAL -> APPROVED -> AUTHORIZED. The same
        // sequence for a read and a write: a read is still an authorised
        // request, it simply has nothing to verify afterwards.
        val authorized = book.advance(journal, JournalEvent.APPROVAL_REQUESTED)
            .let { book.advance(it, JournalEvent.APPROVED) }
            .let { book.advance(it, JournalEvent.AUTHORIZED) }
            .let { book.withProof(it, request.proofReference) }

        val outcome = pipeline.execute(request, tool, definition, simulateAmbiguous, authorized)
        idempotency.remember(request, canonicalArguments, outcome, authorized)
        audit.append(
            tenantId = request.tenantId,
            traceId = request.traceId,
            actorId = user.actorId,
            action = "EXECUTION_${outcome.statusOf().uppercase()}",
            stateBefore = "AUTHORIZED",
            stateAfter = outcome.statusOf().uppercase(),
            details = details(request, decision.ruleId, decision.approval, risk.tier, risk.factors.size),
        )
        return outcome
    }

    // -------------------------------------------------------------- the journal

    private fun openJournal(
        request: ExecutionRequest,
        tool: ToolName,
        definition: ToolDefinition?,
        riskTier: RiskTier,
        approval: ApprovalLevel,
        canonicalArguments: String,
        actorId: ActorId,
    ): ExecutionJournal {
        val now = book.now()
        val inputHash = Digests.sha256(canonicalArguments)
        val journal = ExecutionJournal(
            executionId = ExecutionId(request.executionId),
            tenantId = TenantId(request.tenantId),
            actorId = actorId,
            proposalId = request.proposalId?.let { app.mizan.domain.model.ProposalId(it) },
            // The device's own fingerprint when it supplied one; otherwise the
            // hash of the exact arguments, which is what an approval binds to.
            proposalFingerprint = request.proposalFingerprint ?: inputHash,
            tool = tool,
            toolVersion = tool.version,
            schemaVersion = definition?.schemaVersion ?: "${tool.version}.s1",
            catalogVersion = ToolCatalog.VERSION,
            canonicalInputHash = inputHash,
            idempotencyKey = IdempotencyKey(request.idempotencyKey ?: inputHash),
            policyVersionId = versionedPolicy.version.id,
            policyHash = versionedPolicy.snapshot.rulesHash,
            approvalId = request.approvalId,
            approvalFingerprint = request.proposalFingerprint,
            proofReference = request.proofReference,
            stage = JournalStage.PROPOSED,
            riskTier = riskTier,
            approvalLevel = approval,
            dispatch = DispatchState.NOT_SENT,
            dispatchStartedAt = null,
            dispatchFinishedAt = null,
            responseReceivedAt = null,
            verificationStartedAt = null,
            verificationFinishedAt = null,
            erpModel = null,
            erpRecordId = null,
            candidateIds = emptyList(),
            errorCode = null,
            traceId = request.traceId,
            revision = 0L,
            createdAt = now,
            updatedAt = now,
            leaseExpiresAt = now.plusSeconds(60),
        )
        stores?.journals?.save(journal)
        return journal
    }




    // ----------------------------------------------------------- approval gate

    private fun approvalValidationFailure(
        request: ExecutionRequest,
        policyVersionId: String,
        policyHash: String,
        canonicalArguments: String,
    ): ExecutionOutcome? {
        val approvalId = request.approvalId ?: return null
        val store = stores?.approvals
        if (store == null) {
            // Without a durable approval store this deployment cannot check an
            // approval object, and a check it cannot perform must not pass.
            return refuse(request, "APPROVAL_STORE_UNAVAILABLE", 503)
        }
        val approval = store.get(approvalId) ?: return refuse(request, "APPROVAL_UNKNOWN", 422)
        val validation = approvalPolicy.validate(
            request = approval,
            currentFingerprint = request.proposalFingerprint ?: canonicalArguments,
            currentRevision = approval.proposalRevision,
            currentPolicyVersionId = policyVersionId,
            currentPolicyHash = policyHash,
            nowMillis = clock(),
        )
        if (validation.valid) return null
        return when (validation.verdict) {
            ApprovalVerdict.EXPIRED -> refuse(request, "APPROVAL_EXPIRED", 409)
            ApprovalVerdict.POLICY_CHANGED -> refuse(request, "POLICY_VERSION_CHANGED", 409)
            ApprovalVerdict.INVALIDATED_BY_CHANGE -> refuse(request, "APPROVAL_INVALIDATED", 409)
            ApprovalVerdict.ALREADY_CONSUMED -> refuse(request, "EXECUTION_ALREADY_RESOLVED", 409)
            ApprovalVerdict.REJECTED -> refuse(request, "APPROVAL_REJECTED", 422)
            else -> refuse(request, validation.errorCode ?: "APPROVAL_REQUIRED", 409)
        }
    }

    private fun deviceProofFailure(request: ExecutionRequest): ExecutionOutcome? {
        val binding = deviceBinding ?: return null
        val challengeId = request.deviceChallengeId ?: return refuse(request, "AUTH_PROOF_MISSING", 401)
        val signature = request.deviceSignature ?: return refuse(request, "AUTH_PROOF_MISSING", 401)
        val fingerprint = request.proposalFingerprint
            ?: return refuse(request, "AUTH_PROOF_MISSING", 401)
        val verification = binding.verify(challengeId, signature, fingerprint)
        if (verification.valid) return null
        val status = if (verification.errorCode?.startsWith("AUTH") == true) 401 else 403
        return refuse(request, verification.errorCode ?: "SECURITY_SIGNATURE_INVALID", status)
    }

    // ------------------------------------------------------------- the write





    /**
     * The amount the ladder is judged against: the request's own amount, or
     * for an invoice raised from an order, the amount of that order, read from
     * the ERP. Judging an invoice by its request body alone would call every
     * invoice amountless, and therefore low risk, however large the order
     * behind it.
     */
    private fun policyAmount(tool: ToolName, request: ExecutionRequest): Money? {
        request.moneyOrNull()?.let { return it }
        if (tool == ToolName.CREATE_INVOICE) {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID) ?: return null
            return (connector.orderAmount(request.tenantId, orderId) as? ErpResult.Ok)?.value
        }
        return null
    }

    private fun requiredArgumentRefusal(tool: ToolName, request: ExecutionRequest): ExecutionOutcome? {
        val args = request.arguments
        val code = when (tool) {
            ToolName.STOCK_AVAILABILITY -> if (args.text(MizanContract.ArgumentField.SKU) == null) "MISSING_SKU" else null
            ToolName.CUSTOMER_SEARCH -> if (args.text(MizanContract.ArgumentField.QUERY) == null) "MISSING_QUERY" else null
            ToolName.CREATE_DRAFT_ORDER -> when {
                args.text(MizanContract.ArgumentField.CUSTOMER_NAME) == null -> "MISSING_CUSTOMER"
                args.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR) == null -> "MISSING_AMOUNT"
                args.text(MizanContract.ArgumentField.ITEMS_SUMMARY) == null -> "MISSING_ITEMS"
                else -> null
            }
            ToolName.CREATE_INVOICE ->
                if (args.text(MizanContract.ArgumentField.ORDER_ID) == null) "MISSING_ORDER_ID" else null
            ToolName.CANCEL_ORDER -> when {
                args.text(MizanContract.ArgumentField.ORDER_ID) == null -> "MISSING_ORDER_ID"
                args.text(MizanContract.ArgumentField.REASON) == null -> "MISSING_REASON"
                else -> null
            }
            ToolName.REGISTER_PAYMENT -> when {
                args.text(MizanContract.ArgumentField.INVOICE_ID) == null -> "MISSING_INVOICE_ID"
                request.moneyOrNull() == null -> "MISSING_AMOUNT"
                else -> null
            }
            ToolName.SALES_SUMMARY -> null
            ToolName.UNKNOWN -> "TOOL_UNKNOWN"
        } ?: return null
        return refuse(request, code, 422)
    }

    private fun exceedsCredit(creditMinor: Long?, balanceMinor: Long?, amount: Money?): Boolean {
        if (creditMinor == null || balanceMinor == null || amount == null) return false
        return balanceMinor + amount.minorUnits > creditMinor
    }

    private fun details(
        request: ExecutionRequest,
        ruleId: String,
        approval: ApprovalLevel,
        riskTier: RiskTier,
        factorCount: Int,
    ): String =
        "tool=${request.toolWire} rule=$ruleId approval=$approval risk=$riskTier factors=$factorCount"

    private fun refuse(
        request: ExecutionRequest,
        messageCode: String,
        httpStatus: Int,
        retryAfterSeconds: Long = 0L,
    ): ExecutionOutcome {
        val outcome = Outcomes.rejected(request, messageCode, httpStatus, retryAfterSeconds)
        // A refusal is remembered too: a repeat of the same key with the same
        // arguments must not be re-evaluated into a different answer.
        idempotency.remember(request, request.canonicalArguments(), outcome, null)
        return outcome
    }

    /** A read the ERP answered, before the pipeline existed to build it. */
    private fun accepted(
        request: ExecutionRequest,
        messageCode: String,
        recordId: String? = null,
        model: String? = null,
        summary: String? = null,
    ): ExecutionOutcome = Outcomes.accepted(request, messageCode, recordId, model, summary)



}
