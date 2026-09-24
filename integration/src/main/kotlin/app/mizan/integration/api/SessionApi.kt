package app.mizan.integration.api

import app.mizan.domain.error.AppError
import app.mizan.integration.http.Redactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class RemoteSession(
    val token: String,
    val actorId: String,
    val displayName: String,
    val role: String,
    val tenantId: String,
    val tenantLabel: String,
    val expiresAtEpochMillis: Long?,
) {
    override fun toString(): String = "RemoteSession(actorId=$actorId,tenantId=$tenantId)"
}

sealed interface SignInResult {
    data class Success(val session: RemoteSession) : SignInResult
    data class Failed(val error: AppError) : SignInResult
}

/**
 * Password is sent once and is not retained by this class.
 */
class SessionApi(
    private val baseUrl: String,
    private val http: OkHttpClient = MizanApiClient.defaultClient(),
) {
    fun signIn(email: String, password: String): SignInResult {
        if (!baseUrl.startsWith("https://")) {
            return SignInResult.Failed(AppError.Configuration("API_URL_NOT_HTTPS", "url"))
        }
        val body = """{"email":${json(email)}}"""
        // Password is appended without logging the body.
        val payload = body.dropLast(1) + ""","password":${json(password)}}"""
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/sessions")
            .post(payload.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401) {
                    return SignInResult.Failed(AppError.Authentication("SESSION_EXPIRED", "401"))
                }
                if (response.code !in 200..299) {
                    return SignInResult.Failed(
                        AppError.Network("SIGN_IN_HTTP", Redactor.redact("status ${response.code}"), false),
                    )
                }
                val token = field(text, "token")
                val actorId = field(text, "actorId")
                val name = field(text, "displayName")
                val role = field(text, "role")
                val tenantId = field(text, "tenantId")
                val tenantLabel = field(text, "tenantLabel")
                val expires = longField(text, "expiresAtEpochMillis")
                if (token.isNullOrBlank() || actorId.isNullOrBlank() || tenantId.isNullOrBlank()) {
                    SignInResult.Failed(AppError.Serialization("SIGN_IN_BODY", "incomplete"))
                } else {
                    SignInResult.Success(
                        RemoteSession(
                            token = token,
                            actorId = actorId,
                            displayName = name ?: actorId,
                            role = role ?: "OPERATOR",
                            tenantId = tenantId,
                            tenantLabel = tenantLabel ?: tenantId,
                            expiresAtEpochMillis = expires,
                        ),
                    )
                }
            }
        } catch (io: IOException) {
            SignInResult.Failed(AppError.Network("SIGN_IN_NETWORK", Redactor.redact(io.message ?: "io"), true))
        }
    }

    private fun longField(json: String, name: String): Long? {
        val pattern = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun field(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"\\]*)"""").find(json)?.groupValues?.get(1)

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
