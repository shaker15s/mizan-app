package app.mizan.integration.api

import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.error.AppError
import app.mizan.domain.error.DispatchState
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.VerificationKind
import app.mizan.integration.http.Redactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client of the MIZAN service. It does not talk to an ERP.
 * A missing base URL is a configuration error, not a local success.
 */
class MizanApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = defaultClient(),
) {
    fun execute(proposal: Proposal, approverId: String): AuthorityOutcome {
        if (!baseUrl.startsWith("https://")) {
            return AuthorityOutcome.Refused(
                AppError.Configuration("API_URL_NOT_HTTPS", "service url rejected"),
            )
        }
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            return AuthorityOutcome.Refused(
                AppError.Authentication("SESSION_MISSING", "no session token"),
            )
        }
        val body = CanonicalJson.write(payload(proposal, approverId))
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/executions")
            .header("Authorization", "Bearer $token")
            .header("X-Trace-Id", proposal.traceId.value)
            .header("Idempotency-Key", proposal.idempotencyKey.value)
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                map(response.code, text, proposal.executionId)
            }
        } catch (io: IOException) {
            AuthorityOutcome.Uncertain(
                proposal.executionId,
                "DISPATCH_UNCERTAIN",
                emptyList(),
            ).also { io.message?.let { Redactor.redact(it) } }
        }
    }

    fun map(status: Int, body: String, executionId: ExecutionId): AuthorityOutcome {
        if (status == 401) return refused(AppError.Authentication("SESSION_EXPIRED", "401"))
        if (status == 403) return refused(AppError.Authorization("FORBIDDEN", "403"))
        if (status == 409) return refused(AppError.Conflict("IDEMPOTENCY_CONFLICT", "409"))
        if (status == 422) return refused(AppError.Validation("REJECTED_BY_SERVICE", "422"))
        if (status == 408 || status == 504 || status in 500..599) {
            return AuthorityOutcome.Uncertain(executionId, "SERVICE_HTTP_$status", emptyList())
        }
        if (status !in 200..299) {
            return refused(AppError.Network("SERVICE_HTTP_$status", "status $status", retryable = false))
        }
        val statusField = field(body, "status") ?: return refused(
            AppError.Serialization("SERVICE_BODY", "missing status"),
        )
        val remoteId = field(body, "executionId")?.let(::ExecutionId) ?: executionId
        return when (statusField) {
            "verified" -> {
                val record = field(body, "erpRecordId")
                val model = field(body, "erpModel")
                if (record.isNullOrBlank() || model.isNullOrBlank()) {
                    refused(AppError.Serialization("VERIFIED_WITHOUT_RECORD", "incomplete verified body"))
                } else {
                    AuthorityOutcome.Verified(remoteId, record, model, VerificationKind.READ_BACK)
                }
            }
            "accepted" -> AuthorityOutcome.AcceptedUnverified(remoteId, "ACCEPTED_NOT_VERIFIED")
            "ambiguous" -> AuthorityOutcome.Uncertain(
                remoteId,
                "SERVICE_AMBIGUOUS",
                field(body, "candidates")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            )
            "rejected" -> refused(AppError.Policy(field(body, "messageCode") ?: "REJECTED_BY_SERVICE", "rejected"))
            "failed" -> refused(AppError.Erp(field(body, "messageCode") ?: "ERP_FAILED", "failed"))
            else -> refused(AppError.Serialization("UNKNOWN_STATUS", statusField))
        }
    }

    private fun refused(error: AppError) = AuthorityOutcome.Refused(error)

    private fun payload(proposal: Proposal, approverId: String): CanonicalValue.Obj = CanonicalValue.Obj(
        listOf(
            "approverId" to CanonicalValue.Str(approverId),
            "arguments" to proposal.args.canonical(),
            "executionId" to CanonicalValue.Str(proposal.executionId.value),
            "idempotencyKey" to CanonicalValue.Str(proposal.idempotencyKey.value),
            "proposalId" to CanonicalValue.Str(proposal.id.value),
            "tenantId" to CanonicalValue.Str(proposal.tenantId.value),
            "tool" to CanonicalValue.Str(proposal.args.tool.wire),
            "toolVersion" to CanonicalValue.Str(proposal.args.tool.version),
            "traceId" to CanonicalValue.Str(proposal.traceId.value),
        ),
    )

    /**
     * Minimal extractor for the flat contract. Nested objects are not trusted
     * as authority. Unknown shapes fail closed in [map].
     */
    private fun field(json: String, name: String): String? {
        val match = Regex(""""$name"\s*:\s*"([^"\\]*)"""").find(json) ?: return null
        return match.groupValues[1]
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

fun timeoutOutcome(executionId: ExecutionId, dispatch: DispatchState): AuthorityOutcome {
    return if (dispatch == DispatchState.NOT_SENT) {
        AuthorityOutcome.Refused(AppError.Timeout("TIMEOUT_BEFORE_SEND", "not sent", dispatch))
    } else {
        AuthorityOutcome.Uncertain(executionId, "TIMEOUT_AFTER_SEND", emptyList())
    }
}
