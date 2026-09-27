package app.mizan.integration.api

import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.ToolArgs
import app.mizan.domain.receipt.AuthorityPublicKey
import app.mizan.domain.receipt.ReceiptExpectation
import app.mizan.domain.receipt.ReceiptFieldSource
import app.mizan.domain.receipt.ReceiptInspection
import app.mizan.domain.receipt.ReceiptInspector
import app.mizan.domain.receipt.ReceiptWire
import app.mizan.domain.receipt.SignedReceipt
import app.mizan.integration.http.Redactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * What the service answered, in the two shapes a client can act on: a value it
 * may render, or a code it must show.
 *
 * There is no third shape. A client that invents a value for a refusal is the
 * bug this type exists to prevent.
 */
sealed interface RemoteResult<out T> {

    data class Ok<T>(val value: T) : RemoteResult<T>

    /** [code] is a message key from the service, never a sentence. */
    data class Refused(val code: String, val httpStatus: Int) : RemoteResult<Nothing>
}

/** An approval as the service describes it. */
data class RemoteApproval(
    val id: String,
    val state: ApprovalState,
    val requiredLevel: ApprovalLevel,
    val proposalFingerprint: String,
    val policyVersionId: String,
    val initiatorId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val approverIds: List<String>,
)

/**
 * A receipt as the device received it, with the verdict it is entitled to.
 *
 * The service sends a `verified` boolean and an `authorityVerified` boolean.
 * Neither is evidence: the first is about the read-back, and the second is the
 * service's opinion of its own signature. [inspect] is what the device knows
 * for itself.
 */
data class RemoteReceipt(
    val signed: SignedReceipt,
    val authorityVerified: Boolean,
) {
    /**
     * Checks the signature against the keys this build pinned, and the claims
     * against what the device asked about. A receipt from the service is not
     * trusted merely because it arrived.
     */
    fun inspect(
        pinnedKeys: List<AuthorityPublicKey>,
        expected: ReceiptExpectation? = null,
    ): ReceiptInspection = ReceiptInspector.inspect(signed, pinnedKeys, expected)

    companion object {
        /** Rebuilds a receipt from the fields the service published. Null if partial. */
        fun parse(body: String): RemoteReceipt? {
            val document = JsonText.parse(body) as? JsonText.Obj ?: return null
            val signed = ReceiptWire.signedReceiptOf(
                source = Source(document),
                algorithm = document.text("algorithm"),
                keyId = document.text("keyId"),
                signature = document.text("signature"),
            ) ?: return null
            return RemoteReceipt(signed = signed, authorityVerified = document.flag("authorityVerified") == true)
        }

        private class Source(private val document: JsonText.Obj) : ReceiptFieldSource {
            override fun text(name: String): String? = document.text(name)
            override fun list(name: String): List<String> = document.strings(name)
            override fun number(name: String): Long? = document.whole(name)
        }
    }
}

/** A challenge the device signs over, bound to one fingerprint. */
data class RemoteChallenge(
    val challengeId: String,
    val messageToSign: String,
    val expiresAtMillis: Long,
)

/**
 * The client for everything that is not a governed write.
 *
 * The execution client sends the write; this one is what the approval screen,
 * the device enrolment, the journal, the receipts and the reconciliation
 * screen call. It is deliberately separate for the same reason the service
 * keeps the routes separate: reading state and answering an approval are not
 * the same act as moving money, and a client that mixes them will eventually
 * confuse the two.
 *
 * Every call returns [RemoteResult], and a refusal is a code with the HTTP
 * status that carried it.
 */
class GovernanceApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = MizanApiClient.defaultClient(),
) {

    // ------------------------------------------------------------- approvals

    fun createApproval(
        tool: ToolArgs,
        tenantId: String,
        executionId: String,
        proposalId: String,
        proposalRevision: Int = 1,
    ): RemoteResult<RemoteApproval> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(
                listOf(
                    "tool" to CanonicalValue.Str(tool.tool.wire),
                    "toolVersion" to CanonicalValue.Str(tool.tool.version),
                    "tenantId" to CanonicalValue.Str(tenantId),
                    "executionId" to CanonicalValue.Str(executionId),
                    "proposalId" to CanonicalValue.Str(proposalId),
                    "proposalRevision" to CanonicalValue.Num(proposalRevision.toString()),
                    "arguments" to tool.canonical(),
                ),
            ),
        )
        return send(approvalCreateRequest(body)) { approvalDocument(it)?.let(::approvalOf) }
    }

    fun approvals(state: ApprovalState? = null): RemoteResult<List<RemoteApproval>> {
        val query = state?.let { "?state=${it.name}" }.orEmpty()
        return send(request("GET", "/v1/approvals$query")) { body ->
            val document = JsonText.parse(body) as? JsonText.Obj
                ?: return@send emptyList<RemoteApproval>()
            document.array("approvals").mapNotNull { item -> approvalOf(item) }
        }
    }

    fun grantApproval(
        approvalId: String,
        challengeId: String? = null,
        signature: String? = null,
    ): RemoteResult<RemoteApproval> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(
                listOfNotNull(
                    challengeId?.let { "deviceChallengeId" to CanonicalValue.Str(it) },
                    signature?.let { "deviceSignature" to CanonicalValue.Str(it) },
                ),
            ),
        )
        return send(request("POST", "/v1/approvals/$approvalId/grant", body)) {
            approvalDocument(it)?.let(::approvalOf)
        }
    }

    fun refuseApproval(approvalId: String, reasonCode: String): RemoteResult<RemoteApproval> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(listOf("reasonCode" to CanonicalValue.Str(reasonCode))),
        )
        return send(request("POST", "/v1/approvals/$approvalId/refuse", body)) {
            approvalDocument(it)?.let(::approvalOf)
        }
    }

    // --------------------------------------------------------------- devices

    fun enrolDevice(
        deviceId: String,
        algorithm: String,
        publicKeyBase64: String,
        label: String,
    ): RemoteResult<String> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(
                listOf(
                    "deviceId" to CanonicalValue.Str(deviceId),
                    "algorithm" to CanonicalValue.Str(algorithm),
                    "publicKey" to CanonicalValue.Str(publicKeyBase64),
                    "label" to CanonicalValue.Str(label),
                ),
            ),
        )
        return send(request("POST", "/v1/devices", body)) { answer ->
            (JsonText.parse(answer) as? JsonText.Obj)?.text("deviceId")
        }
    }

    /**
     * Asks for a challenge over one fingerprint.
     *
     * The fingerprint is the approval's, not the request's: signing the thing
     * you are approving is the point, and signing "yes" is not a proof.
     */
    fun challenge(
        deviceId: String,
        executionId: String,
        proposalFingerprint: String,
    ): RemoteResult<RemoteChallenge> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(
                listOf(
                    "deviceId" to CanonicalValue.Str(deviceId),
                    "executionId" to CanonicalValue.Str(executionId),
                    "proposalFingerprint" to CanonicalValue.Str(proposalFingerprint),
                ),
            ),
        )
        return send(request("POST", "/v1/devices/challenge", body)) { answer ->
            // A challenge that cannot be read is not a challenge. Returning
            // null here becomes a refusal, which is the honest answer.
            val obj = JsonText.parse(answer) as? JsonText.Obj ?: return@send null
            val challengeId = obj.text("challengeId") ?: return@send null
            val message = obj.text("messageToSign") ?: return@send null
            RemoteChallenge(
                challengeId = challengeId,
                messageToSign = message,
                expiresAtMillis = obj.whole("expiresAtMillis") ?: 0L,
            )
        }
    }

    // -------------------------------------------------------------- receipts

    /**
     * The receipt of one execution, as the service signed it.
     *
     * The client does not trust the body: it verifies the signature against the
     * key ring it was configured with, and shows the verdict. A receipt that
     * cannot be verified is not evidence.
     */
    fun receipt(receiptId: String): RemoteResult<RemoteReceipt> =
        send(request("GET", "/v1/receipts/$receiptId"), RemoteReceipt::parse)

    /**
     * Fetches a receipt and checks it the way a device must: against the keys
     * this build pinned, and against what the device asked about.
     *
     * Returns the verdict as a value, including the honest ones -- an unknown
     * key, a shared secret the device would have to hold to check, a receipt
     * about a different proposal. A caller that only wants good news is a
     * caller that will show a tick it cannot justify.
     */
    fun verifyReceipt(
        receiptId: String,
        pinnedKeys: List<AuthorityPublicKey>,
        expected: ReceiptExpectation? = null,
    ): RemoteResult<ReceiptInspection> = when (val fetched = receipt(receiptId)) {
        is RemoteResult.Refused -> fetched
        is RemoteResult.Ok -> RemoteResult.Ok(fetched.value.inspect(pinnedKeys, expected))
    }

    /**
     * The keys the deployment says it signs with.
     *
     * A client build pins its keys and does not fetch them: a key a phone
     * downloads from the same service an attacker would have compromised is a
     * key the attacker supplies. This exists so an operator can compare what is
     * deployed against what is pinned, and so the reference deployment can be
     * exercised end to end.
     */
    fun publishedReceiptKeys(): RemoteResult<List<AuthorityPublicKey>> =
        send(request("GET", "/v1/capabilities")) { body ->
            val document = JsonText.parse(body) as? JsonText.Obj
                ?: return@send emptyList<AuthorityPublicKey>()
            document.array("receiptKeys").mapNotNull { item ->
                val key = item as? JsonText.Obj ?: return@mapNotNull null
                val keyId = key.text("keyId") ?: return@mapNotNull null
                val algorithm = key.text("algorithm") ?: return@mapNotNull null
                val publicKey = key.text("publicKey") ?: return@mapNotNull null
                AuthorityPublicKey(
                    keyId = keyId,
                    algorithm = algorithm,
                    publicKeyBase64 = publicKey,
                    retired = key.flag("retired") == true,
                )
            }
        }

    // -------------------------------------------------------- reconciliation

    fun reconciliationCases(): RemoteResult<String> =
        send(request("GET", "/v1/reconciliation"), { body -> body })

    fun resolveReconciliation(caseId: String, resolution: String, note: String?): RemoteResult<String> {
        val body = CanonicalJson.write(
            CanonicalValue.Obj(
                listOfNotNull(
                    "resolution" to CanonicalValue.Str(resolution),
                    note?.takeIf { it.isNotBlank() }?.let { "note" to CanonicalValue.Str(it) },
                ),
            ),
        )
        return send(request("POST", "/v1/reconciliation/$caseId/resolve", body), { body -> body })
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * The exact request the device sends, visible so the contract test can
     * assert the path, the method and the session header without a network.
     */
    internal fun approvalCreateRequest(body: String): Request = request("POST", "/v1/approvals", body)

    internal fun request(method: String, path: String, body: String? = null): Request {
        val token = tokenProvider()
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
            builder.method(method, body.toRequestBody(JSON))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private fun <T> send(request: Request, parse: (String) -> T?): RemoteResult<T> {
        if (!baseUrl.startsWith("https://")) return RemoteResult.Refused("API_URL_NOT_HTTPS", 0)
        if (tokenProvider().isNullOrBlank()) return RemoteResult.Refused("SESSION_MISSING", 0)
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in 200..299) {
                    RemoteResult.Refused(messageCode(text) ?: "SERVICE_HTTP_${response.code}", response.code)
                } else {
                    val parsed = parse(text)
                    if (parsed == null) {
                        // A 200 the client cannot read is a refusal, never an
                        // empty value that the UI would render as nothing.
                        RemoteResult.Refused("SERVICE_BODY_UNREADABLE", response.code)
                    } else {
                        RemoteResult.Ok(parsed)
                    }
                }
            }
        } catch (io: IOException) {
            // The message is redacted because a failing URL can carry a query
            // string, and a query string can carry a secret someone put there.
            io.message?.let { Redactor.redact(it) }
            RemoteResult.Refused("SERVICE_UNREACHABLE", 0)
        }
    }

    private fun approvalOf(document: JsonText): RemoteApproval? {
        val obj = document as? JsonText.Obj ?: return null
        val id = obj.text("approvalId") ?: return null
        return RemoteApproval(
            id = id,
            state = ApprovalState.entries.firstOrNull { it.name == obj.text("state") } ?: return null,
            requiredLevel = ApprovalLevel.entries.firstOrNull { it.name == obj.text("requiredLevel") }
                ?: return null,
            proposalFingerprint = obj.text("proposalFingerprint") ?: return null,
            policyVersionId = obj.text("policyVersionId") ?: return null,
            initiatorId = obj.text("initiatorId") ?: return null,
            createdAtMillis = obj.whole("createdAtMillis") ?: return null,
            expiresAtMillis = obj.whole("expiresAtMillis") ?: return null,
            approverIds = obj.strings("approverIds"),
        )
    }

    /**
     * A grant that still needs a second approver comes back wrapped, because
     * the answer is a partial one and a screen that showed "granted" would be
     * lying about who has answered so far.
     */
    private fun approvalDocument(body: String): JsonText? {
        val document = JsonText.parse(body) ?: return null
        if (document is JsonText.Obj) document.obj("approval")?.let { return it }
        return document
    }

    private fun messageCode(body: String): String? =
        (JsonText.parse(body) as? JsonText.Obj)?.text("messageCode")

    private companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
