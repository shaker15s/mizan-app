package app.mizan.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mizan.R
import app.mizan.domain.agent.MissingField
import app.mizan.domain.attention.AttentionKind
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.Role
import app.mizan.domain.model.ToolName

@Composable
fun phaseLabel(phase: ExecutionPhase): String = stringResource(phaseId(phase))

@StringRes
fun phaseId(phase: ExecutionPhase): Int = when (phase) {
    ExecutionPhase.PROPOSED -> R.string.phase_PROPOSED
    ExecutionPhase.VALIDATED -> R.string.phase_VALIDATED
    ExecutionPhase.RISK_EVALUATED -> R.string.phase_RISK_EVALUATED
    ExecutionPhase.AWAITING_APPROVAL -> R.string.phase_AWAITING_APPROVAL
    ExecutionPhase.AUTHORIZED -> R.string.phase_AUTHORIZED
    ExecutionPhase.LEASE_ACQUIRED -> R.string.phase_LEASE_ACQUIRED
    ExecutionPhase.EXECUTING -> R.string.phase_EXECUTING
    ExecutionPhase.ERP_ACCEPTED -> R.string.phase_ERP_ACCEPTED
    ExecutionPhase.VERIFICATION_PENDING -> R.string.phase_VERIFICATION_PENDING
    ExecutionPhase.VERIFIED -> R.string.phase_VERIFIED
    ExecutionPhase.REJECTED -> R.string.phase_REJECTED
    ExecutionPhase.CANCELLED -> R.string.phase_CANCELLED
    ExecutionPhase.TIMEOUT -> R.string.phase_TIMEOUT
    ExecutionPhase.ERP_FAILURE -> R.string.phase_ERP_FAILURE
    ExecutionPhase.AMBIGUOUS -> R.string.phase_AMBIGUOUS
    ExecutionPhase.RECONCILIATION_REQUIRED -> R.string.phase_RECONCILIATION_REQUIRED
    ExecutionPhase.LINKED_UNVERIFIED -> R.string.phase_LINKED_UNVERIFIED
    ExecutionPhase.CLOSED_UNVERIFIED -> R.string.phase_CLOSED_UNVERIFIED
}

@Composable
fun toolLabel(tool: ToolName): String = stringResource(
    when (tool) {
        ToolName.STOCK_AVAILABILITY -> R.string.tool_stock
        ToolName.CUSTOMER_SEARCH -> R.string.tool_customer
        ToolName.CREATE_DRAFT_ORDER -> R.string.tool_draft
        ToolName.CANCEL_ORDER -> R.string.tool_cancel
        ToolName.CREATE_INVOICE -> R.string.tool_invoice
        ToolName.REGISTER_PAYMENT -> R.string.tool_payment
        ToolName.SALES_SUMMARY -> R.string.tool_summary
        ToolName.UNKNOWN -> R.string.tool_unknown
    },
)

@Composable
fun reasonLabel(code: String): String {
    val id = REASONS[code] ?: return code
    return stringResource(id)
}

@Composable
fun missingLabel(field: MissingField): String = stringResource(
    when (field) {
        MissingField.CUSTOMER -> R.string.missing_CUSTOMER
        MissingField.AMOUNT -> R.string.missing_AMOUNT
        MissingField.CURRENCY -> R.string.missing_CURRENCY
        MissingField.ITEMS -> R.string.missing_ITEMS
        MissingField.ORDER_ID -> R.string.missing_ORDER_ID
        MissingField.INVOICE_ID -> R.string.missing_INVOICE_ID
        MissingField.REASON -> R.string.missing_REASON
        MissingField.SKU -> R.string.missing_SKU
        MissingField.QUERY -> R.string.missing_QUERY
    },
)

@Composable
fun attentionLabel(kind: AttentionKind): String = stringResource(
    when (kind) {
        AttentionKind.APPROVAL -> R.string.attention_approval
        AttentionKind.RECONCILIATION -> R.string.attention_reconciliation
        AttentionKind.FAILURE -> R.string.attention_failure
        AttentionKind.ERP_UNAVAILABLE -> R.string.attention_erp
        AttentionKind.SYNC_STALE -> R.string.attention_sync
        AttentionKind.BACKEND_UNKNOWN -> R.string.attention_backend
    },
)

@Composable
fun healthLabel(status: HealthStatus): String = stringResource(
    when (status) {
        HealthStatus.HEALTHY -> R.string.health_HEALTHY
        HealthStatus.DEGRADED -> R.string.health_DEGRADED
        HealthStatus.UNAVAILABLE -> R.string.health_UNAVAILABLE
        HealthStatus.UNKNOWN -> R.string.health_UNKNOWN
    },
)

@Composable
fun roleLabel(role: Role): String = stringResource(
    when (role) {
        Role.SALES_REP -> R.string.role_SALES_REP
        Role.SALES_MANAGER -> R.string.role_SALES_MANAGER
        Role.FINANCE_APPROVER -> R.string.role_FINANCE_APPROVER
        Role.AUDITOR -> R.string.role_AUDITOR
        Role.OPERATOR -> R.string.role_OPERATOR
    },
)

@Composable
fun chainLabel(code: String): String {
    val id = when (code) {
        "CHAIN_EMPTY" -> R.string.chain_CHAIN_EMPTY
        "CHAIN_INTACT_LOCAL" -> R.string.chain_CHAIN_INTACT_LOCAL
        "CHAIN_LINK_MISMATCH" -> R.string.chain_CHAIN_LINK_MISMATCH
        "CHAIN_PAYLOAD_MISMATCH" -> R.string.chain_CHAIN_PAYLOAD_MISMATCH
        else -> return code
    }
    return stringResource(id)
}

private val REASONS = mapOf(
    "SAFE_READ" to R.string.reason_SAFE_READ,
    "AUDITOR_READONLY" to R.string.reason_AUDITOR_READONLY,
    "DESTRUCTIVE_REQUIRES_MANAGER" to R.string.reason_DESTRUCTIVE_REQUIRES_MANAGER,
    "THRESHOLD_L1" to R.string.reason_THRESHOLD_L1,
    "THRESHOLD_L2" to R.string.reason_THRESHOLD_L2,
    "THRESHOLD_L3" to R.string.reason_THRESHOLD_L3,
    "THRESHOLD_L4" to R.string.reason_THRESHOLD_L4,
    "CURRENCY_LADDER_MISSING" to R.string.reason_CURRENCY_LADDER_MISSING,
    "CREDIT_LIMIT" to R.string.reason_CREDIT_LIMIT,
    "CUSTOMER_BLOCKED" to R.string.reason_CUSTOMER_BLOCKED,
    "INJECTION_BLOCKED" to R.string.reason_INJECTION_BLOCKED,
    "TOOL_NOT_SUPPORTED" to R.string.reason_TOOL_NOT_SUPPORTED,
    "CONFIRM_NO_AMOUNT" to R.string.reason_CONFIRM_NO_AMOUNT,
    "API_NOT_CONFIGURED" to R.string.reason_API_NOT_CONFIGURED,
    "SESSION_MISSING" to R.string.reason_SESSION_MISSING,
    "SESSION_EXPIRED" to R.string.reason_SESSION_EXPIRED,
    "FORBIDDEN" to R.string.reason_FORBIDDEN,
    "SOD_SAME_ACTOR" to R.string.reason_SOD_SAME_ACTOR,
    "REAUTH" to R.string.reason_REAUTH,
    "IDEMPOTENCY" to R.string.reason_IDEMPOTENCY,
    "SOD_SAME_ACTOR" to R.string.reason_SOD_SAME_ACTOR,
    "SOD_NEED_SECOND_APPROVER" to R.string.reason_SOD_NEED_SECOND_APPROVER,
    "SOD_ROLE_INSUFFICIENT" to R.string.reason_SOD_ROLE_INSUFFICIENT,
    "SOD_TENANT_MISMATCH" to R.string.reason_SOD_TENANT_MISMATCH,
    "ILLEGAL_TRANSITION" to R.string.reason_ILLEGAL_TRANSITION,
    "API_URL_NOT_HTTPS" to R.string.reason_API_URL_NOT_HTTPS,
    "SIGN_IN_HTTP" to R.string.reason_SIGN_IN_HTTP,
    "SIGN_IN_NETWORK" to R.string.reason_SIGN_IN_NETWORK,
    "SIGN_IN_BODY" to R.string.reason_SIGN_IN_BODY,
)
