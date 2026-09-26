package app.mizan.service.protocol

import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text

/** One execution request as the client sends it. */
data class ExecutionRequest(
    val executionId: String,
    val proposalId: String?,
    val tenantId: String,
    val traceId: String,
    val toolWire: String,
    val toolVersion: String,
    val arguments: JsonValue.Obj,
    val approverId: String?,
    val idempotencyKey: String?,
    /** The approval object this request claims, when the client has one. */
    val approvalId: String? = null,
    /** The fingerprint the client believes it is executing. */
    val proposalFingerprint: String? = null,
    /** Server-issued challenge id and the device's signature over it. */
    val deviceChallengeId: String? = null,
    val deviceSignature: String? = null,
    /** Opaque reference to the proof of presence, kept in the journal. */
    val proofReference: String? = null,
) {
    companion object {
        /**
         * Returns null when a required field is missing or of the wrong type.
         * A request that cannot be understood is refused, not guessed at.
         */
        fun parse(
            body: JsonValue.Obj,
            fallbackExecutionId: String,
            traceHeader: String?,
            idempotencyHeader: String?,
        ): ExecutionRequest? {
            val tenantId = body.text(MizanContract.RequestField.TENANT_ID) ?: return null
            val tool = body.text(MizanContract.RequestField.TOOL) ?: return null
            val arguments = body.field(MizanContract.RequestField.ARGUMENTS)?.asObject() ?: return null
            if (tenantId.isBlank() || tool.isBlank()) return null
            return ExecutionRequest(
                executionId = body.text(MizanContract.RequestField.EXECUTION_ID)
                    ?.takeIf { it.isNotBlank() } ?: fallbackExecutionId,
                proposalId = body.text(MizanContract.RequestField.PROPOSAL_ID),
                tenantId = tenantId,
                traceId = traceHeader?.takeIf { it.isNotBlank() }
                    ?: body.text(MizanContract.RequestField.TRACE_ID)
                    ?: fallbackExecutionId,
                toolWire = tool,
                toolVersion = body.text(MizanContract.RequestField.TOOL_VERSION) ?: "unknown",
                arguments = arguments,
                approverId = body.text(MizanContract.RequestField.APPROVER_ID),
                idempotencyKey = idempotencyHeader?.takeIf { it.isNotBlank() }
                    ?: body.text(MizanContract.RequestField.IDEMPOTENCY_KEY),
                approvalId = body.text(MizanContract.RequestField.APPROVAL_ID),
                proposalFingerprint = body.text(MizanContract.RequestField.PROPOSAL_FINGERPRINT),
                deviceChallengeId = body.text(MizanContract.RequestField.DEVICE_CHALLENGE_ID),
                deviceSignature = body.text(MizanContract.RequestField.DEVICE_SIGNATURE),
                proofReference = body.text(MizanContract.RequestField.PROOF_REFERENCE),
            )
        }
    }

    /** Stable serialisation used to detect key reuse with different arguments. */
    fun canonicalArguments(): String = Json.write(arguments)
}

/** What the service decides. The HTTP layer turns this into a response body. */
sealed interface ExecutionOutcome {

    /** Read back from the ERP after the write. The only success that is proven. */
    data class Verified(
        val executionId: String,
        val erpRecordId: String,
        val erpModel: String,
        val summary: String,
        /** Present when the authority signed a receipt for this write. */
        val receiptId: String? = null,
        val receiptSignature: String? = null,
        val receiptKeyId: String? = null,
        /**
         * HMAC-SHA256 or Ed25519. A device must be able to tell the two apart:
         * the first proves a shared secret signed this, the second proves the
         * authority did, and only the second is a proof the phone can check.
         */
        val receiptAlgorithm: String? = null,
        /** The fields the read-back actually matched. Not a bare tick. */
        val verifiedFields: List<String> = emptyList(),
    ) : ExecutionOutcome

    /** Applied but not read back. Honest, and not a success claim. */
    data class Accepted(
        val executionId: String,
        val messageCode: String,
        val erpRecordId: String? = null,
        val erpModel: String? = null,
        val summary: String? = null,
    ) : ExecutionOutcome

    /** The write may have happened. A person must reconcile it. */
    data class Ambiguous(
        val reasonCode: String? = null,
        val possibleRecordId: String? = null,
        val possibleModel: String? = null,
        val executionId: String,
        val candidateRecordIds: List<String>,
    ) : ExecutionOutcome

    /** Policy, separation of duties, or contract refused it. Nothing was written. */
    data class Rejected(
        val executionId: String,
        val messageCode: String,
        val httpStatus: Int,
        /** Sent as `Retry-After` when the refusal is a rate limit. */
        val retryAfterSeconds: Long = 0L,
    ) : ExecutionOutcome

    /** The ERP refused the write. Nothing was written. */
    data class Failed(
        val executionId: String,
        val messageCode: String,
    ) : ExecutionOutcome
}

internal fun ExecutionOutcome.executionIdOf(): String = when (this) {
    is ExecutionOutcome.Verified -> executionId
    is ExecutionOutcome.Accepted -> executionId
    is ExecutionOutcome.Ambiguous -> executionId
    is ExecutionOutcome.Rejected -> executionId
    is ExecutionOutcome.Failed -> executionId
}

internal fun ExecutionOutcome.statusOf(): String = when (this) {
    is ExecutionOutcome.Verified -> MizanContract.Status.VERIFIED
    is ExecutionOutcome.Accepted -> MizanContract.Status.ACCEPTED
    is ExecutionOutcome.Ambiguous -> MizanContract.Status.AMBIGUOUS
    is ExecutionOutcome.Rejected -> MizanContract.Status.REJECTED
    is ExecutionOutcome.Failed -> MizanContract.Status.FAILED
}

internal fun ExecutionOutcome.messageCodeOf(): String = when (this) {
    is ExecutionOutcome.Verified -> "VERIFIED_BY_READ_BACK"
    is ExecutionOutcome.Accepted -> messageCode
    is ExecutionOutcome.Ambiguous -> reasonCode ?: "SERVICE_AMBIGUOUS"
    is ExecutionOutcome.Rejected -> messageCode
    is ExecutionOutcome.Failed -> messageCode
}
