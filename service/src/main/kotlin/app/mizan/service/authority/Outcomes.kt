package app.mizan.service.authority

import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest

/**
 * The answers the service builds outside the journal, in one place.
 *
 * Keeping the constructors together is not cosmetic: a refusal that forgot to
 * carry its HTTP status, or an accepted read that quietly became a verified
 * one, would be a contract change made by accident deep inside a call path.
 */
internal object Outcomes {

    /**
     * A read that the ERP answered. Accepted is not verified, and there is no
     * path from here to verified: the field comparison is what decides that.
     */
    fun accepted(
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

    /** Nothing was written and the caller may ask again. */
    fun failed(request: ExecutionRequest, messageCode: String): ExecutionOutcome =
        ExecutionOutcome.Failed(request.executionId, messageCode)

    /**
     * Refused before anything was dispatched. The authority's own `refuse`
     * wraps this to also remember the answer under the request's key.
     */
    fun rejected(
        request: ExecutionRequest,
        messageCode: String,
        httpStatus: Int,
        retryAfterSeconds: Long = 0L,
    ): ExecutionOutcome = ExecutionOutcome.Rejected(
        executionId = request.executionId,
        messageCode = messageCode,
        httpStatus = httpStatus,
        retryAfterSeconds = retryAfterSeconds,
    )
}
