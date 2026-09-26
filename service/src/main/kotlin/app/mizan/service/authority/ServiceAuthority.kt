package app.mizan.service.authority

import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.Money
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.PolicyRequest
import app.mizan.domain.policy.SeparationOfDuties
import app.mizan.domain.policy.SodCode
import app.mizan.domain.risk.RiskEvaluator
import app.mizan.domain.risk.RiskInput
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.json.text
import app.mizan.service.json.wholeOrNumericText
import app.mizan.service.json.whole
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.ledger.LedgerEntry
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.protocol.executionIdOf
import app.mizan.service.protocol.messageCodeOf
import app.mizan.service.protocol.statusOf
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.UserDirectory

/**
 * The authority. The device proposes; this decides.
 *
 * Everything the phone evaluated is re-evaluated here: the tenant, the tool
 * version, policy, separation of duties, and the write itself. Nothing in a
 * request body is trusted because the phone already checked it.
 *
 * A write is only reported as verified after a separate read-back from the
 * ERP adapter. Otherwise the answer is accepted, ambiguous, or failed.
 */
class ServiceAuthority(
    private val erp: InMemoryErp,
    private val ledger: ExecutionLedger,
    private val audit: AuditLedger,
    private val directory: UserDirectory,
    private val capabilities: ConnectorCapabilities,
    private val policy: PolicyEvaluator,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

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

        val key = request.idempotencyKey
        if (!key.isNullOrBlank()) {
            val existing = ledger.find(request.tenantId, key)
            if (existing != null) {
                if (existing.canonicalArguments != request.canonicalArguments()) {
                    return refuse(request, "IDEMPOTENCY_KEY_REUSE", 409)
                }
                audit.append(
                    tenantId = request.tenantId,
                    traceId = request.traceId,
                    actorId = user.actorId,
                    action = "EXECUTION_REPLAYED",
                    stateBefore = "REQUEST_RECEIVED",
                    stateAfter = existing.status.uppercase(),
                    details = "key=${key.take(16)} executionId=${existing.executionId}",
                )
                return replay(existing)
            }
        }

        // Required arguments are checked before policy. A request that is
        // simply missing its amount must answer MISSING_AMOUNT, not the
        // refusal code of the approval ladder it happened to fall into.
        requiredArgumentRefusal(tool, request)?.let { return it }

        val actor = Actor(ActorId(user.actorId), user.displayName, user.role, TenantId(user.tenantId))
        val amount = policyAmount(tool, request)
        val customerName = request.arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
        val customer = customerName?.let { erp.customerState(it) }
        val blocked = customer?.status.equals("blocked", ignoreCase = true)
        val exceeds = exceedsCredit(customer?.creditMinor, customer?.balanceMinor, customer?.currency, amount)

        val decision = policy.evaluate(
            PolicyRequest(
                actor = actor,
                tool = tool,
                amount = amount,
                destructive = tool.destructive,
                customerBlocked = blocked,
                exceedsCredit = exceeds,
            ),
        )
        if (!decision.allowed) return refuse(request, decision.reasonCode, 422)

        val approvals = ArrayList<ApprovalRecord>()
        val approverId = request.approverId
        if (!approverId.isNullOrBlank()) {
            val approver = directory.find(request.tenantId, approverId)
                ?: return refuse(request, "APPROVER_UNKNOWN", 422)
            approvals.add(
                ApprovalRecord(
                    approver = Actor(
                        id = ActorId(approver.actorId),
                        displayName = approver.displayName,
                        role = approver.role,
                        tenantId = TenantId(approver.tenantId),
                    ),
                    atEpochMillis = clock(),
                ),
            )
        }
        val sod = SeparationOfDuties().check(
            initiatorId = ActorId(user.actorId),
            initiatorTenant = TenantId(user.tenantId),
            approval = decision.approval,
            approvals = approvals,
        )
        if (sod != SodCode.SATISFIED && sod != SodCode.NOT_REQUIRED) {
            return refuse(request, "SOD_${sod.name}", 422)
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

        val outcome = execute(request, tool, simulateAmbiguous)
        remember(request, key, outcome)
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

    private fun execute(
        request: ExecutionRequest,
        tool: ToolName,
        simulateAmbiguous: Boolean,
    ): ExecutionOutcome = when (tool) {
        ToolName.STOCK_AVAILABILITY -> {
            val sku = request.arguments.text(MizanContract.ArgumentField.SKU)
                ?: return refuse(request, "MISSING_SKU", 422)
            val read = erp.stockAvailability(request.tenantId, sku)
                ?: return accepted(request, "STOCK_NOT_FOUND")
            accepted(request, "READ_RESULT", read.recordId, read.model, read.summary)
        }

        ToolName.CUSTOMER_SEARCH -> {
            val query = request.arguments.text(MizanContract.ArgumentField.QUERY)
                ?: return refuse(request, "MISSING_QUERY", 422)
            val read = erp.customerSearch(request.tenantId, query)
            accepted(request, "READ_RESULT", read.recordId, read.model, read.summary)
        }

        ToolName.SALES_SUMMARY -> {
            val period = request.arguments.text(MizanContract.ArgumentField.PERIOD) ?: "current"
            val read = erp.salesSummary(request.tenantId, period)
            accepted(request, "READ_RESULT", read.recordId, read.model, read.summary)
        }

        ToolName.CREATE_DRAFT_ORDER -> {
            val name = request.arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
                ?: return refuse(request, "MISSING_CUSTOMER", 422)
            val amount = moneyOf(request) ?: return refuse(request, "MISSING_AMOUNT", 422)
            val items = request.arguments.text(MizanContract.ArgumentField.ITEMS_SUMMARY)
                ?: return refuse(request, "MISSING_ITEMS", 422)
            val write = erp.createDraftOrder(request.tenantId, name, amount, items)
            finish(request, write.model, write.recordId, simulateAmbiguous)
        }

        ToolName.CANCEL_ORDER -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return refuse(request, "MISSING_ORDER_ID", 422)
            val reason = request.arguments.text(MizanContract.ArgumentField.REASON)
                ?: return refuse(request, "MISSING_REASON", 422)
            val write = erp.cancelOrder(request.tenantId, orderId, reason)
                ?: return ExecutionOutcome.Failed(request.executionId, "ORDER_NOT_FOUND")
            finish(request, write.model, write.recordId, simulateAmbiguous)
        }

        ToolName.CREATE_INVOICE -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return refuse(request, "MISSING_ORDER_ID", 422)
            val write = erp.createInvoice(request.tenantId, orderId)
                ?: return ExecutionOutcome.Failed(request.executionId, "ORDER_NOT_FOUND_OR_CANCELLED")
            finish(request, write.model, write.recordId, simulateAmbiguous)
        }

        ToolName.REGISTER_PAYMENT -> {
            val invoiceId = request.arguments.text(MizanContract.ArgumentField.INVOICE_ID)
                ?: return refuse(request, "MISSING_INVOICE_ID", 422)
            val amount = moneyOf(request) ?: return refuse(request, "MISSING_AMOUNT", 422)
            val write = erp.registerPayment(request.tenantId, invoiceId, amount)
                ?: return ExecutionOutcome.Failed(request.executionId, "INVOICE_NOT_FOUND_OR_CURRENCY_MISMATCH")
            finish(request, write.model, write.recordId, simulateAmbiguous)
        }

        ToolName.UNKNOWN -> refuse(request, "TOOL_UNKNOWN", 422)
    }

    /**
     * Applies the read-back rule. A write that cannot be read back is
     * accepted, never verified.
     */
    private fun finish(
        request: ExecutionRequest,
        model: String,
        recordId: String,
        simulateAmbiguous: Boolean,
    ): ExecutionOutcome {
        val read = erp.readBack(request.tenantId, model, recordId)
        if (simulateAmbiguous) {
            // The write is already applied. That is the dangerous case this
            // status exists for: the ERP may hold the record and the caller
            // does not know. It opens reconciliation, it does not retry.
            return ExecutionOutcome.Ambiguous(
                executionId = request.executionId,
                candidateRecordIds = erp.candidates(request.tenantId, model),
            )
        }
        return if (read == null) {
            accepted(request, "ACCEPTED_NOT_VERIFIED", recordId, model)
        } else {
            ExecutionOutcome.Verified(
                executionId = request.executionId,
                erpRecordId = recordId,
                erpModel = model,
                summary = read.summary,
            )
        }
    }

    private fun remember(request: ExecutionRequest, key: String?, outcome: ExecutionOutcome) {
        if (key.isNullOrBlank()) return
        val candidates = if (outcome is ExecutionOutcome.Ambiguous) outcome.candidateRecordIds else emptyList()
        val recordId = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpRecordId
            is ExecutionOutcome.Accepted -> outcome.erpRecordId
            else -> null
        }
        val model = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpModel
            is ExecutionOutcome.Accepted -> outcome.erpModel
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
                canonicalArguments = request.canonicalArguments(),
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
    }

    private fun replay(entry: LedgerEntry): ExecutionOutcome = when (entry.status) {
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
        )
        MizanContract.Status.REJECTED -> ExecutionOutcome.Rejected(
            executionId = entry.executionId,
            messageCode = entry.messageCode,
            httpStatus = 422,
        )
        else -> ExecutionOutcome.Failed(entry.executionId, entry.messageCode)
    }

    /**
     * The amount the ladder is judged against. It is the amount in the request
     * when there is one; for an invoice raised from an order it is the amount
     * of that order, read from the ERP. Judging an invoice by its request body
     * alone would call every invoice amountless, and therefore low risk, no
     * matter how large the order behind it is.
     */
    private fun policyAmount(tool: ToolName, request: ExecutionRequest): Money? {
        moneyOf(request)?.let { return it }
        if (tool == ToolName.CREATE_INVOICE) {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID) ?: return null
            return erp.orderAmount(request.tenantId, orderId)
        }
        return null
    }

    /** The first thing wrong with the arguments, or null when they are usable. */
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
        val minor = request.arguments.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR)
            ?: return null
        val currency = request.arguments.text(MizanContract.ArgumentField.CURRENCY) ?: return null
        return runCatching { Money(minor, currency) }.getOrNull()
    }

    private fun exceedsCredit(
        creditMinor: Long?,
        balanceMinor: Long?,
        currency: String?,
        amount: Money?,
    ): Boolean {
        if (creditMinor == null || balanceMinor == null || currency == null || amount == null) return false
        if (currency != amount.currency) return false
        return balanceMinor + amount.minorUnits > creditMinor
    }

    private fun details(
        request: ExecutionRequest,
        ruleId: String,
        approval: ApprovalLevel,
        riskTier: RiskTier,
        factorCount: Int,
    ): String = "tool=${request.toolWire} rule=$ruleId approval=$approval risk=$riskTier factors=$factorCount"

    private fun refuse(request: ExecutionRequest, messageCode: String, httpStatus: Int): ExecutionOutcome {
        val outcome = ExecutionOutcome.Rejected(request.executionId, messageCode, httpStatus)
        remember(request, request.idempotencyKey, outcome)
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
    }
}
