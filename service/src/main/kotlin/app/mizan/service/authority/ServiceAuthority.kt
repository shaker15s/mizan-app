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

    private val machine = JournalStateMachine()
    private val sod = SeparationOfDuties()

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
        when (val prior = priorFor(request.tenantId, request.idempotencyKey, canonicalArguments)) {
            is Prior.Conflict -> return refuse(request, prior.code, prior.httpStatus)
            is Prior.Replay -> {
                audit.append(
                    tenantId = request.tenantId,
                    traceId = request.traceId,
                    actorId = user.actorId,
                    action = "EXECUTION_REPLAYED",
                    stateBefore = "REQUEST_RECEIVED",
                    stateAfter = prior.entry.status.uppercase(),
                    details = "key=${request.idempotencyKey?.take(12)} executionId=${prior.entry.executionId}",
                )
                return replay(prior.entry)
            }
            Prior.None -> Unit
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
        val authorized = advance(journal, JournalEvent.APPROVAL_REQUESTED)
            .let { advance(it, JournalEvent.APPROVED) }
            .let { advance(it, JournalEvent.AUTHORIZED) }
            .let { withProof(it, request.proofReference) }

        val outcome = execute(request, tool, definition, simulateAmbiguous, authorized)
        remember(request, canonicalArguments, outcome, authorized)
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
        val now = now()
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

    private fun advance(
        journal: ExecutionJournal,
        event: JournalEvent,
        at: Instant = now(),
    ): ExecutionJournal {
        return when (val stepped = journal.advance(event, at, machine)) {
            is app.mizan.domain.execution.JournalAdvance.Moved -> {
                stores?.journals?.save(stepped.journal)
                stepped.journal
            }
            is app.mizan.domain.execution.JournalAdvance.Illegal -> {
                // An illegal transition is a bug in the caller, and it is
                // recorded rather than swallowed: the journal keeps the last
                // legal stage and the error code names the attempt.
                val flagged = journal.copy(
                    errorCode = "ILLEGAL_TRANSITION_${stepped.transition.event}",
                    updatedAt = at,
                    revision = journal.revision + 1,
                )
                stores?.journals?.save(flagged)
                flagged
            }
        }
    }

    // ------------------------------------------------------------- idempotency

    private sealed interface Prior {
        data object None : Prior

        data class Replay(val entry: PriorEntry) : Prior

        data class Conflict(val code: String, val httpStatus: Int) : Prior
    }

    private data class PriorEntry(
        val canonicalArguments: String,
        val executionId: String,
        val status: String,
        val stage: JournalStage,
        val erpRecordId: String?,
        val erpModel: String?,
        val messageCode: String,
        val candidateRecordIds: List<String>,
        val summary: String?,
    )

    private fun priorFor(tenantId: String, key: String?, canonicalArguments: String): Prior {
        if (key.isNullOrBlank()) return Prior.None

        val durable = stores?.idempotency?.find(tenantId, key)
        if (durable != null) {
            if (durable.canonicalArguments != canonicalArguments) {
                return Prior.Conflict("IDEMPOTENCY_KEY_REUSE", 409)
            }
            return when (durable.stage) {
                JournalStage.VERIFIED,
                JournalStage.ACCEPTED,
                JournalStage.VERIFYING,
                -> Prior.Replay(
                    PriorEntry(
                        canonicalArguments = durable.canonicalArguments,
                        executionId = durable.executionId,
                        status = durable.status,
                        stage = durable.stage,
                        erpRecordId = durable.erpRecordId,
                        erpModel = durable.erpModel,
                        messageCode = durable.messageCode,
                        candidateRecordIds = durable.candidateRecordIds,
                        summary = durable.summary,
                    ),
                )

                JournalStage.REJECTED, JournalStage.CANCELLED, JournalStage.EXPIRED, JournalStage.FAILED ->
                    if (durable.stage == JournalStage.FAILED) {
                        Prior.Conflict("EXECUTION_ALREADY_FAILED", 409)
                    } else {
                        Prior.None
                    }

                JournalStage.AMBIGUOUS, JournalStage.RECONCILIATION_REQUIRED ->
                    Prior.Conflict("EXECUTION_ALREADY_AMBIGUOUS", 409)

                else -> Prior.Conflict("EXECUTION_IN_PROGRESS", 409)
            }
        }

        val existing = ledger.find(tenantId, key) ?: return Prior.None
        if (existing.canonicalArguments != canonicalArguments) return Prior.Conflict("IDEMPOTENCY_KEY_REUSE", 409)
        return Prior.Replay(
            PriorEntry(
                canonicalArguments = existing.canonicalArguments,
                executionId = existing.executionId,
                status = existing.status,
                stage = stageFor(existing.status),
                erpRecordId = existing.erpRecordId,
                erpModel = existing.erpModel,
                messageCode = existing.messageCode,
                candidateRecordIds = existing.candidateRecordIds,
                summary = existing.summary,
            ),
        )
    }

    private fun stageFor(status: String): JournalStage = when (status) {
        MizanContract.Status.VERIFIED -> JournalStage.VERIFIED
        MizanContract.Status.ACCEPTED -> JournalStage.ACCEPTED
        MizanContract.Status.AMBIGUOUS -> JournalStage.AMBIGUOUS
        MizanContract.Status.REJECTED -> JournalStage.REJECTED
        else -> JournalStage.FAILED
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

    private fun execute(
        request: ExecutionRequest,
        tool: ToolName,
        definition: ToolDefinition?,
        simulateAmbiguous: Boolean,
        journal: ExecutionJournal,
    ): ExecutionOutcome = when (tool) {
        ToolName.STOCK_AVAILABILITY -> {
            val sku = request.arguments.text(MizanContract.ArgumentField.SKU)
                ?: return refuse(request, "MISSING_SKU", 422)
            readThrough(
                request = request,
                journal = journal,
                call = { connector.checkStock(request.tenantId, sku) },
                describe = {
                    ReadSummary(
                        recordId = it.sku,
                        model = MizanContract.ErpModel.STOCK,
                        summary = "available=${it.availableQty} reserved=${it.reservedQty}",
                    )
                },
            )
        }

        ToolName.CUSTOMER_SEARCH -> {
            val query = request.arguments.text(MizanContract.ArgumentField.QUERY)
                ?: return refuse(request, "MISSING_QUERY", 422)
            readThrough(
                request = request,
                journal = journal,
                call = { connector.findCustomer(request.tenantId, query) },
                describe = { matches ->
                    ReadSummary(
                        recordId = matches.firstOrNull()?.recordId ?: "none",
                        model = MizanContract.ErpModel.CUSTOMER,
                        summary = "matches=${matches.size}",
                    )
                },
            )
        }

        ToolName.SALES_SUMMARY -> {
            val period = request.arguments.text(MizanContract.ArgumentField.PERIOD) ?: "current"
            readThrough(
                request = request,
                journal = journal,
                call = { connector.salesSummary(request.tenantId, period) },
                describe = { ReadSummary(period, MizanContract.ErpModel.ANALYTICS, it) },
            )
        }

        ToolName.CREATE_DRAFT_ORDER -> {
            val name = request.arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
                ?: return refuse(request, "MISSING_CUSTOMER", 422)
            val amount = moneyOf(request) ?: return refuse(request, "MISSING_AMOUNT", 422)
            val items = request.arguments.text(MizanContract.ArgumentField.ITEMS_SUMMARY)
                ?: return refuse(request, "MISSING_ITEMS", 422)
            val dispatching = advance(journal, JournalEvent.DISPATCH_STARTED)
            val write = connector.createDraftOrder(request.tenantId, name, amount, items)
            finish(request, write, dispatching, simulateAmbiguous, expectedFields = mapOf(
                "amountMinor" to amount.minorUnits.toString(),
                "currency" to amount.currency,
            ))
        }

        ToolName.CANCEL_ORDER -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return refuse(request, "MISSING_ORDER_ID", 422)
            val reason = request.arguments.text(MizanContract.ArgumentField.REASON)
                ?: return refuse(request, "MISSING_REASON", 422)
            val dispatching = advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.cancelOrder(request.tenantId, orderId, reason),
                dispatching,
                simulateAmbiguous,
                expectedFields = emptyMap(),
            )
        }

        ToolName.CREATE_INVOICE -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return refuse(request, "MISSING_ORDER_ID", 422)
            val dispatching = advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.createInvoice(request.tenantId, orderId),
                dispatching,
                simulateAmbiguous,
                expectedFields = emptyMap(),
            )
        }

        ToolName.REGISTER_PAYMENT -> {
            val invoiceId = request.arguments.text(MizanContract.ArgumentField.INVOICE_ID)
                ?: return refuse(request, "MISSING_INVOICE_ID", 422)
            val amount = moneyOf(request) ?: return refuse(request, "MISSING_AMOUNT", 422)
            val dispatching = advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.registerPayment(request.tenantId, invoiceId, amount),
                dispatching,
                simulateAmbiguous,
                expectedFields = mapOf(
                    "amountMinor" to amount.minorUnits.toString(),
                    "currency" to amount.currency,
                ),
            )
        }

        ToolName.UNKNOWN -> refuse(request, "TOOL_UNKNOWN", 422)
    }

    /**
     * The write result, then the separate read that decides whether it may be
     * called verified. A write that cannot be read back is accepted; one whose
     * answer was lost is ambiguous and opens a case.
     */
    private fun finish(
        request: ExecutionRequest,
        result: ErpResult<app.mizan.service.erp.ErpRecord>,
        journal: ExecutionJournal,
        simulateAmbiguous: Boolean,
        expectedFields: Map<String, String>,
    ): ExecutionOutcome {
        val write = when (result) {
            is ErpResult.Ok -> result.value
            is ErpResult.Refused -> return failed(request, writeFailureCode(result.reasonCode), journal)
            is ErpResult.NotSupported -> return failed(request, "TOOL_NOT_SUPPORTED_BY_ERP", journal)
            is ErpResult.Unavailable -> return failed(request, result.reasonCode, journal)
            is ErpResult.Malformed -> return ambiguous(request, journal, result.reasonCode, emptyList())
            is ErpResult.Unknown -> return ambiguous(request, journal, result.reasonCode, emptyList())
        }

        val accepted = advance(journal, JournalEvent.DISPATCH_ACCEPTED)
        if (simulateAmbiguous) {
            // The write is already applied. That is the dangerous case this
            // status exists for: the ERP may hold the record and the caller
            // does not know. It opens reconciliation; it does not retry.
            return ambiguous(
                request,
                accepted,
                "SERVICE_AMBIGUOUS",
                connector.candidates(request.tenantId, write.model, 5),
                recordId = write.recordId,
                model = write.model,
            )
        }
        val verifying = advance(accepted, JournalEvent.VERIFICATION_STARTED)
        return when (val read = connector.readBack(request.tenantId, write.model, write.recordId)) {
            is ErpResult.Ok -> {
                val comparison = VerificationFingerprint.compare(expectedFields, read.value.fields)
                when {
                    // The record exists and the fields the request promised
                    // are present and equal: this is the only verified path.
                    comparison.mismatched.isEmpty() && comparison.missing.isEmpty() -> verified(
                        request = request,
                        journal = verifying,
                        write = write,
                        read = read.value,
                        verifiedFields = comparison.matched.ifEmpty { read.value.fields.keys.sorted() },
                    )
                    // A record came back that disagrees with what was asked
                    // for. That is not success, and it is not nothing either.
                    else -> ambiguous(
                        request,
                        verifying,
                        "VERIFICATION_FIELD_MISMATCH",
                        listOf(write.recordId),
                        recordId = write.recordId,
                        model = write.model,
                    )
                }
            }
            is ErpResult.Refused -> failedWithRecord(request, verifying, write, "ACCEPTED_NOT_VERIFIED")
            else -> accepted(
                request,
                "ACCEPTED_NOT_VERIFIED",
                write.recordId,
                write.model,
                "the write was not read back",
            )
        }
    }

    private fun verified(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        write: app.mizan.service.erp.ErpRecord,
        read: app.mizan.service.erp.ErpRecord,
        verifiedFields: List<String>,
    ): ExecutionOutcome {
        val verifiedJournal = advance(journal, JournalEvent.VERIFIED)
        val stored = verifiedJournal.copy(
            erpModel = write.model,
            erpRecordId = write.recordId,
        )
        stores?.journals?.save(stored)

        val receipt = signer?.let { signing ->
            val claims = ReceiptClaims(
                receiptId = "RCT-" + request.executionId.removePrefix("EXE-"),
                executionId = request.executionId,
                tenantId = request.tenantId,
                actorId = stored.actorId.value,
                approverIds = listOfNotNull(request.approverId),
                proposalFingerprint = stored.proposalFingerprint,
                tool = stored.tool.wire,
                toolVersion = stored.toolVersion,
                catalogVersion = stored.catalogVersion,
                policyVersionId = stored.policyVersionId,
                policyHash = stored.policyHash,
                approvalLevel = stored.approvalLevel,
                inputHash = stored.canonicalInputHash,
                erpModel = write.model,
                erpRecordId = write.recordId,
                verificationHash = VerificationFingerprint.of(write.model, write.recordId, read.fields),
                verifiedFields = verifiedFields,
                issuedAtMillis = clock(),
                traceId = request.traceId,
            )
            signing.sign(claims)
        }
        if (receipt != null) stores?.receipts?.save(receipt)

        return ExecutionOutcome.Verified(
            executionId = request.executionId,
            erpRecordId = write.recordId,
            erpModel = write.model,
            summary = read.summary.ifBlank { write.fields.entries.joinToString(" ") { "${it.key}=${it.value}" } },
            receiptId = receipt?.claims?.receiptId,
            receiptSignature = receipt?.signature,
            receiptKeyId = receipt?.keyId,
            verifiedFields = verifiedFields,
        )
    }

    private fun ambiguous(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        reasonCode: String,
        candidates: List<String>,
        recordId: String? = null,
        model: String? = null,
    ): ExecutionOutcome {
        // REQUIRE_RECONCILIATION is legal from DISPATCHING, ACCEPTED and
        // VERIFYING alike, so an uncertain write reaches the same stage
        // whichever earlier step discovered it.
        val moved = advance(journal, JournalEvent.REQUIRE_RECONCILIATION)
        val stored = moved.copy(
            candidateIds = candidates,
            erpModel = model ?: moved.erpModel,
            erpRecordId = recordId ?: moved.erpRecordId,
            errorCode = reasonCode,
        )
        stores?.journals?.save(stored)
        stores?.reconciliations?.upsert(
            ReconciliationCase(
                id = "REC-" + request.executionId.removePrefix("EXE-"),
                executionId = ExecutionId(request.executionId),
                traceId = app.mizan.domain.model.TraceId(request.traceId),
                tenantId = TenantId(request.tenantId),
                tool = ToolName.fromWire(request.toolWire) ?: ToolName.UNKNOWN,
                intent = request.toolWire,
                idempotencyKey = stored.idempotencyKey,
                candidateRecordIds = candidates,
                status = ReconciliationStatus.OPEN,
                notes = reasonCode,
                openedAt = now(),
            ),
        )
        return ExecutionOutcome.Ambiguous(
            executionId = request.executionId,
            candidateRecordIds = candidates,
            reasonCode = reasonCode,
            possibleRecordId = recordId,
            possibleModel = model,
        )
    }

    private fun failed(request: ExecutionRequest, code: String, journal: ExecutionJournal): ExecutionOutcome {
        stores?.journals?.save(advance(journal, JournalEvent.FAILED).copy(errorCode = code))
        return ExecutionOutcome.Failed(request.executionId, code)
    }

    private fun failedWithRecord(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        write: app.mizan.service.erp.ErpRecord,
        messageCode: String,
    ): ExecutionOutcome {
        val stored = journal.copy(erpModel = write.model, erpRecordId = write.recordId)
        stores?.journals?.save(stored)
        return accepted(request, messageCode, write.recordId, write.model, "the record was written and not read back")
    }

    /** What a successful read produced, in the shape the journal stores. */
    private data class ReadSummary(val recordId: String, val model: String, val summary: String)

    /**
     * A read is still a call to the ERP, so it is still dispatched as far as
     * the journal is concerned: a person reading the history must be able to
     * see that the device asked and what came back. What a read never does is
     * claim verification -- it is accepted, which is exactly what it is.
     */
    private fun <T> readThrough(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        call: () -> ErpResult<T>,
        describe: (T) -> ReadSummary,
    ): ExecutionOutcome {
        val dispatching = advance(journal, JournalEvent.DISPATCH_STARTED)
        val result = call()
        return when (result) {
            is ErpResult.Ok -> {
                val summary = describe(result.value)
                val accepted = advance(dispatching, JournalEvent.DISPATCH_ACCEPTED)
                stores?.journals?.save(
                    accepted.copy(erpModel = summary.model, erpRecordId = summary.recordId),
                )
                accepted(request, "READ_RESULT", summary.recordId, summary.model, summary.summary)
            }
            is ErpResult.Refused -> {
                stores?.journals?.save(advance(dispatching, JournalEvent.FAILED).copy(errorCode = result.reasonCode))
                accepted(request, result.reasonCode)
            }
            is ErpResult.NotSupported -> failed(request, "TOOL_NOT_SUPPORTED_BY_ERP", dispatching)
            is ErpResult.Unavailable -> failed(request, result.reasonCode, dispatching)
            is ErpResult.Malformed -> failed(request, result.reasonCode, dispatching)
            is ErpResult.Unknown -> failed(request, "ERP_UNKNOWN_ANSWER", dispatching)
        }
    }

    private fun writeFailureCode(reasonCode: String): String = when (reasonCode) {
        "ORDER_NOT_FOUND" -> "ORDER_NOT_FOUND"
        "ORDER_NOT_FOUND_OR_CANCELLED" -> "ORDER_NOT_FOUND_OR_CANCELLED"
        "PAYMENT_REFUSED" -> "PAYMENT_REFUSED"
        else -> reasonCode
    }

    // ---------------------------------------------------------- bookkeeping

    private fun remember(
        request: ExecutionRequest,
        canonicalArguments: String,
        outcome: ExecutionOutcome,
        journal: ExecutionJournal?,
    ) {
        val key = request.idempotencyKey ?: return
        val candidates = if (outcome is ExecutionOutcome.Ambiguous) outcome.candidateRecordIds else emptyList()
        val recordId = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpRecordId
            is ExecutionOutcome.Accepted -> outcome.erpRecordId
            is ExecutionOutcome.Ambiguous -> outcome.possibleRecordId
            else -> null
        }
        val model = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpModel
            is ExecutionOutcome.Accepted -> outcome.erpModel
            is ExecutionOutcome.Ambiguous -> outcome.possibleModel
            else -> null
        }
        val summary = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.summary
            is ExecutionOutcome.Accepted -> outcome.summary
            else -> null
        }
        ledger.record(
            LedgerEntry(
                tenantId = request.tenantId,
                key = key,
                canonicalArguments = canonicalArguments,
                executionId = outcome.executionIdOf(),
                traceId = request.traceId,
                status = outcome.statusOf(),
                erpRecordId = recordId,
                erpModel = model,
                messageCode = outcome.messageCodeOf(),
                candidateRecordIds = candidates,
                summary = summary,
            ),
        )
        stores?.idempotency?.record(
            app.mizan.service.store.IdempotencyStore.Entry(
                tenantId = request.tenantId,
                key = key,
                canonicalArguments = canonicalArguments,
                executionId = outcome.executionIdOf(),
                status = outcome.statusOf(),
                stage = stageFor(outcome.statusOf()),
                erpRecordId = recordId,
                erpModel = model,
                messageCode = outcome.messageCodeOf(),
                candidateRecordIds = candidates,
                summary = summary,
                recordedAtMillis = clock(),
            ),
        )
        stores?.idempotency?.maybeCompact()
        journal?.let { stores?.journals?.save(it) }
    }

    private fun replay(entry: PriorEntry): ExecutionOutcome = when (entry.status) {
        MizanContract.Status.VERIFIED -> ExecutionOutcome.Verified(
            executionId = entry.executionId,
            erpRecordId = entry.erpRecordId ?: "",
            erpModel = entry.erpModel ?: "",
            summary = entry.summary ?: "",
        )
        MizanContract.Status.ACCEPTED -> ExecutionOutcome.Accepted(
            executionId = entry.executionId,
            messageCode = entry.messageCode,
            erpRecordId = entry.erpRecordId,
            erpModel = entry.erpModel,
            summary = entry.summary,
        )
        MizanContract.Status.AMBIGUOUS -> ExecutionOutcome.Ambiguous(
            executionId = entry.executionId,
            candidateRecordIds = entry.candidateRecordIds,
            reasonCode = entry.messageCode,
            possibleRecordId = entry.erpRecordId,
            possibleModel = entry.erpModel,
        )
        MizanContract.Status.REJECTED -> ExecutionOutcome.Rejected(
            executionId = entry.executionId,
            messageCode = entry.messageCode,
            httpStatus = 422,
        )
        else -> ExecutionOutcome.Failed(entry.executionId, entry.messageCode)
    }

    /**
     * The amount the ladder is judged against: the request's own amount, or
     * for an invoice raised from an order, the amount of that order, read from
     * the ERP. Judging an invoice by its request body alone would call every
     * invoice amountless, and therefore low risk, however large the order
     * behind it.
     */
    private fun policyAmount(tool: ToolName, request: ExecutionRequest): Money? {
        moneyOf(request)?.let { return it }
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
                moneyOf(request) == null -> "MISSING_AMOUNT"
                else -> null
            }
            ToolName.SALES_SUMMARY -> null
            ToolName.UNKNOWN -> "TOOL_UNKNOWN"
        } ?: return null
        return refuse(request, code, 422)
    }

    private fun moneyOf(request: ExecutionRequest): Money? {
        val minor = request.arguments.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR) ?: return null
        val currency = request.arguments.text(MizanContract.ArgumentField.CURRENCY) ?: return null
        return runCatching { Money(minor, currency) }.getOrNull()
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
        val outcome = ExecutionOutcome.Rejected(request.executionId, messageCode, httpStatus, retryAfterSeconds)
        // A refusal is remembered too: a repeat of the same key with the same
        // arguments must not be re-evaluated into a different answer.
        remember(request, request.canonicalArguments(), outcome, null)
        return outcome
    }

    private fun accepted(
        request: ExecutionRequest,
        messageCode: String,
        recordId: String? = null,
        model: String? = null,
        summary: String? = null,
    ): ExecutionOutcome = ExecutionOutcome.Accepted(
        executionId = request.executionId,
        messageCode = messageCode,
        erpRecordId = recordId,
        erpModel = model,
        summary = summary,
    )

    private fun now(): Instant = Instant.ofEpochMilli(clock())

    private fun withProof(journal: ExecutionJournal, proof: String?): ExecutionJournal {
        if (proof.isNullOrBlank()) return journal
        val stamped = journal.copy(proofReference = proof)
        stores?.journals?.save(stamped)
        return stamped
    }

    companion object {
        /** Accounts the reference service ships with. Demo only, never production. */
        fun demoUsers(): List<ServiceUser> = listOf(
            ServiceUser.of(
                email = "rep@mizan.test",
                password = "rep-demo-password",
                actorId = "USR-REP",
                displayName = "Amr Kamel",
                role = Role.SALES_REP,
                tenantId = "sim-alamal",
                tenantLabel = "Al-Amal Trading",
            ),
            ServiceUser.of(
                email = "manager@mizan.test",
                password = "manager-demo-password",
                actorId = "USR-MGR",
                displayName = "Tarek Fouad",
                role = Role.SALES_MANAGER,
                tenantId = "sim-alamal",
                tenantLabel = "Al-Amal Trading",
            ),
            ServiceUser.of(
                email = "finance@mizan.test",
                password = "finance-demo-password",
                actorId = "USR-FIN",
                displayName = "Noha Adel",
                role = Role.FINANCE_APPROVER,
                tenantId = "sim-alamal",
                tenantLabel = "Al-Amal Trading",
            ),
            ServiceUser.of(
                email = "auditor@mizan.test",
                password = "auditor-demo-password",
                actorId = "USR-AUD",
                displayName = "Hisham Sayed",
                role = Role.AUDITOR,
                tenantId = "sim-alamal",
                tenantLabel = "Al-Amal Trading",
            ),
        )

        /** What the reference ERP adapter claims to support. */
        fun referenceCapabilities(): ConnectorCapabilities = ConnectorCapabilities(
            connectorId = "reference-in-memory",
            supportsDraftOrders = true,
            supportsOrderCancel = true,
            supportsInvoiceCreation = true,
            supportsPayment = true,
            supportsVerification = true,
            supportsBatchRead = false,
            supportsJson2 = false,
            supportsLegacyRpc = false,
        )

        /** A reference authority over the in-memory adapter, for tests and demos. */
        fun reference(
            ledger: ExecutionLedger,
            audit: AuditLedger,
            directory: UserDirectory,
            policy: PolicyEvaluator,
            clock: () -> Long = { System.currentTimeMillis() },
        ): ServiceAuthority = ServiceAuthority(
            connector = InMemoryErpConnector(InMemoryErp(), clock),
            ledger = ledger,
            audit = audit,
            directory = directory,
            capabilities = referenceCapabilities(),
            policy = policy,
            clock = clock,
        )

        /** The diff a person should see when a proposal moved under an approval. */
        fun diffOf(before: app.mizan.domain.model.Proposal, after: app.mizan.domain.model.Proposal) =
            ProposalDiff.between(before, after)
    }
}
