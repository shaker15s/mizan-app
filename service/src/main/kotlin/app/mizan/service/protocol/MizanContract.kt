package app.mizan.service.protocol

/**
 * The wire contract between the Android client and the MIZAN service.
 *
 * The client side lives in `:integration` (`MizanApiClient`, `SessionApi`).
 * These constants are the shared vocabulary. Changing a value here means
 * changing both sides in the same commit, and the client treats an unknown
 * `status` as a refusal rather than a success.
 */
object MizanContract {

    const val PATH_SESSIONS = "/v1/sessions"
    const val PATH_EXECUTIONS = "/v1/executions"
    const val PATH_HEALTH = "/v1/health"
    const val PATH_AUDIT = "/v1/audit"
    const val PATH_ERP = "/v1/erp"
    const val PATH_JOURNAL = "/v1/journal"
    const val PATH_CAPABILITIES = "/v1/capabilities"
    const val PATH_TOOLS = "/v1/tools"
    const val PATH_POLICY = "/v1/policy"
    const val PATH_RECONCILIATION = "/v1/reconciliation"
    const val PATH_RECEIPTS = "/v1/receipts"
    const val PATH_APPROVALS = "/v1/approvals"
    const val PATH_DEVICES = "/v1/devices"

    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_TRACE_ID = "X-Trace-Id"
    const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"

    /**
     * Test-only. When the client sends this header the service answers
     * `ambiguous` on purpose so the reconciliation path can be exercised
     * without breaking a real ERP. A deployment may disable it.
     */
    const val HEADER_SIMULATE = "X-Mizan-Simulate"
    const val SIMULATE_AMBIGUOUS = "ambiguous"

    /** Response `status` values. The client fails closed on anything else. */
    object Status {
        const val VERIFIED = "verified"
        const val ACCEPTED = "accepted"
        const val AMBIGUOUS = "ambiguous"
        const val REJECTED = "rejected"
        const val FAILED = "failed"
    }

    /** Field names of the execution request the client sends. */
    object RequestField {
        const val EXECUTION_ID = "executionId"
        const val PROPOSAL_ID = "proposalId"
        const val TENANT_ID = "tenantId"
        const val TRACE_ID = "traceId"
        const val TOOL = "tool"
        const val TOOL_VERSION = "toolVersion"
        const val ARGUMENTS = "arguments"
        const val APPROVER_ID = "approverId"
        const val IDEMPOTENCY_KEY = "idempotencyKey"

        /** The approval object the request claims, and the proposal it binds to. */
        const val APPROVAL_ID = "approvalId"
        const val PROPOSAL_FINGERPRINT = "proposalFingerprint"

        /** Device-bound authorisation: a server challenge and its signature. */
        const val DEVICE_CHALLENGE_ID = "deviceChallengeId"
        const val DEVICE_SIGNATURE = "deviceSignature"

        /** Opaque reference to the proof of presence kept in the journal. */
        const val PROOF_REFERENCE = "proofReference"
    }

    /** Canonical argument keys produced by the client's `ToolArgs.canonical()`. */
    object ArgumentField {
        const val SKU = "sku"
        const val QUERY = "query"
        const val PERIOD = "period"
        const val CUSTOMER_NAME = "customerName"
        const val ITEMS_SUMMARY = "itemsSummary"
        const val AMOUNT_MINOR = "amountMinor"
        const val CURRENCY = "currency"
        const val ORDER_ID = "orderId"
        const val REASON = "reason"
        const val INVOICE_ID = "invoiceId"
    }

    object ErpModel {
        const val SALE_ORDER = "sale.order"
        const val INVOICE = "account.move"
        const val PAYMENT = "account.payment"
        const val STOCK = "stock.quant"
        const val CUSTOMER = "res.partner"
        const val ANALYTICS = "sale.report"
    }
}
