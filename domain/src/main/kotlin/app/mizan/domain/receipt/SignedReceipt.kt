package app.mizan.domain.receipt

import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.Digests
import app.mizan.domain.model.Fingerprints
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A receipt is a claim about what happened, signed by the authority that made
 * it.
 *
 * Before this existed, the only proof in the product was a hash chain on the
 * phone: a check that the local rows had not changed. That is a real check and
 * it is not a proof of anything an ERP did. A signed receipt names the
 * authority's claims -- which tool, which arguments, which approvers, which
 * policy, which ERP record, which read-back -- and a MAC that anyone holding
 * the key can verify and nobody without it can forge.
 */
data class ReceiptClaims(
    val receiptId: String,
    val executionId: String,
    val tenantId: String,
    val actorId: String,
    val approverIds: List<String>,
    val proposalFingerprint: String,
    val tool: String,
    val toolVersion: String,
    val catalogVersion: String,
    val policyVersionId: String,
    val policyHash: String,
    val approvalLevel: ApprovalLevel,
    val inputHash: String,
    val erpModel: String,
    val erpRecordId: String,
    val verificationHash: String,
    val verifiedFields: List<String>,
    val issuedAtMillis: Long,
    val traceId: String,
) {
    fun canonical(): CanonicalValue = CanonicalValue.Obj(
        listOf(
            "actorId" to CanonicalValue.Str(actorId),
            "approvalLevel" to CanonicalValue.Str(approvalLevel.name),
            "approverIds" to CanonicalValue.Arr(approverIds.sorted().map { CanonicalValue.Str(it) }),
            "catalogVersion" to CanonicalValue.Str(catalogVersion),
            "erpModel" to CanonicalValue.Str(erpModel),
            "erpRecordId" to CanonicalValue.Str(erpRecordId),
            "executionId" to CanonicalValue.Str(executionId),
            "inputHash" to CanonicalValue.Str(inputHash),
            "issuedAtMillis" to CanonicalValue.Num(issuedAtMillis.toString()),
            "policyHash" to CanonicalValue.Str(policyHash),
            "policyVersionId" to CanonicalValue.Str(policyVersionId),
            "proposalFingerprint" to CanonicalValue.Str(proposalFingerprint),
            "receiptId" to CanonicalValue.Str(receiptId),
            "tenantId" to CanonicalValue.Str(tenantId),
            "tool" to CanonicalValue.Str(tool),
            "toolVersion" to CanonicalValue.Str(toolVersion),
            "traceId" to CanonicalValue.Str(traceId),
            "verificationHash" to CanonicalValue.Str(verificationHash),
            "verifiedFields" to CanonicalValue.Arr(verifiedFields.sorted().map { CanonicalValue.Str(it) }),
        ),
    )

    fun body(): String = CanonicalJson.write(canonical())

    fun digest(): String = Digests.sha256(body())
}

data class SignedReceipt(
    val claims: ReceiptClaims,
    val algorithm: String,
    val keyId: String,
    val signature: String,
) {
    val body: String get() = claims.body()
}

enum class ReceiptVerdict {
    VALID,
    UNSIGNED,
    UNKNOWN_KEY,
    RETIRED_KEY,
    SIGNATURE_MISMATCH,
    MALFORMED,
}

data class ReceiptVerification(
    val verdict: ReceiptVerdict,
    val reasonCode: String,
) {
    val valid: Boolean get() = verdict == ReceiptVerdict.VALID
}

/**
 * A key that signs receipts. Retired keys stay in the ring so older receipts
 * remain verifiable; only the active key signs new ones.
 */
data class SigningKey(
    val keyId: String,
    val secret: ByteArray,
    val activeFrom: Instant,
    val retiredAt: Instant? = null,
) {
    init {
        require(secret.size >= 32) { "a signing secret must be at least 256 bits" }
    }

    val active: Boolean get() = retiredAt == null

    // A secret must not leak through a log line, a stack trace or a debugger.
    override fun toString(): String = "SigningKey(keyId=$keyId, secret=redacted, active=$active)"

    override fun equals(other: Any?): Boolean =
        other is SigningKey && other.keyId == keyId && other.secret.contentEquals(secret)

    override fun hashCode(): Int = keyId.hashCode() * 31 + secret.contentHashCode()
}

enum class SignatureAlgorithm(val wire: String, val macName: String) {
    HMAC_SHA256("HMAC-SHA256", "HmacSHA256"),
    ;

    companion object {
        fun fromWire(wire: String): SignatureAlgorithm? = entries.firstOrNull { it.wire == wire }
    }
}

class ReceiptSigner(
    private val keys: List<SigningKey>,
    private val algorithm: SignatureAlgorithm = SignatureAlgorithm.HMAC_SHA256,
) {
    init {
        require(keys.isNotEmpty()) { "a signer needs at least one key" }
    }

    val activeKeyId: String get() = activeKey().keyId

    private fun activeKey(): SigningKey =
        keys.filter { it.active }.maxByOrNull { it.activeFrom } ?: keys.maxByOrNull { it.activeFrom }!!

    fun sign(claims: ReceiptClaims): SignedReceipt {
        val key = activeKey()
        val mac = mac(key)
        mac.update(claims.body().toByteArray(Charsets.UTF_8))
        return SignedReceipt(
            claims = claims,
            algorithm = algorithm.wire,
            keyId = key.keyId,
            signature = encoder.encodeToString(mac.doFinal()),
        )
    }

    /**
     * Rotation is additive: the new key signs, the old key still verifies.
     * A receipt whose key is retired verifies but is reported as retired, so a
     * reader can tell the difference between "forged" and "old".
     */
    fun rotate(newKey: SigningKey, at: Instant): ReceiptSigner = ReceiptSigner(
        keys = keys.map { if (it.active) it.copy(retiredAt = at) else it } + newKey,
        algorithm = algorithm,
    )

    fun verify(signed: SignedReceipt): ReceiptVerification {
        if (signed.signature.isBlank() || signed.keyId.isBlank()) {
            return ReceiptVerification(ReceiptVerdict.UNSIGNED, "RECEIPT_UNSIGNED")
        }
        if (SignatureAlgorithm.fromWire(signed.algorithm) != algorithm) {
            return ReceiptVerification(ReceiptVerdict.MALFORMED, "RECEIPT_ALGORITHM_UNSUPPORTED")
        }
        val key = keys.firstOrNull { it.keyId == signed.keyId }
            ?: return ReceiptVerification(ReceiptVerdict.UNKNOWN_KEY, "RECEIPT_KEY_UNKNOWN")
        val expected = try {
            val mac = mac(key)
            mac.update(signed.claims.body().toByteArray(Charsets.UTF_8))
            encoder.encodeToString(mac.doFinal())
        } catch (error: IllegalArgumentException) {
            return ReceiptVerification(ReceiptVerdict.MALFORMED, "RECEIPT_MALFORMED")
        }
        val decoded = try {
            decoder.decode(signed.signature)
        } catch (error: IllegalArgumentException) {
            return ReceiptVerification(ReceiptVerdict.MALFORMED, "RECEIPT_MALFORMED")
        }
        // Constant time: a comparison that leaks timing is a forgery oracle.
        if (!MessageDigest.isEqual(decoded, decoder.decode(expected))) {
            return ReceiptVerification(ReceiptVerdict.SIGNATURE_MISMATCH, "RECEIPT_SIGNATURE_MISMATCH")
        }
        return if (key.active) {
            ReceiptVerification(ReceiptVerdict.VALID, "RECEIPT_VALID")
        } else {
            ReceiptVerification(ReceiptVerdict.RETIRED_KEY, "RECEIPT_KEY_RETIRED")
        }
    }

    private fun mac(key: SigningKey): Mac {
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key.secret, algorithm.macName))
        return mac
    }

    companion object {
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getUrlDecoder()

        /** A deterministic key for tests and the reference deployment. */
        fun demoKey(secret: String, keyId: String = "k-1"): SigningKey = SigningKey(
            keyId = keyId,
            secret = Digests.sha256("mizan-demo-signing-key:$secret").toByteArray(Charsets.UTF_8),
            activeFrom = Instant.EPOCH,
        )
    }
}

/**
 * What the read-back actually proved. Stored next to the write so a reader can
 * see the fields, not just a tick.
 */
object VerificationFingerprint {
    fun of(model: String, recordId: String, fields: Map<String, String>): String = Fingerprints.receipt(
        listOf(
            "erpModel" to CanonicalValue.Str(model),
            "erpRecordId" to CanonicalValue.Str(recordId),
            "fields" to CanonicalValue.Obj(
                fields.entries.sortedBy { it.key }.map { it.key to CanonicalValue.Str(it.value) },
            ),
        ),
    )

    /**
     * Compares the read-back against what the request said would be true.
     * A write that comes back with a different amount is not a verified write,
     * it is a mismatch that opens reconciliation.
     */
    fun compare(expected: Map<String, String>, actual: Map<String, String>): FieldComparison {
        val matched = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((field, value) in expected) {
            val found = actual[field]
            when {
                found == null -> missing += field
                found == value -> matched += field
                else -> mismatched += field
            }
        }
        return FieldComparison(matched.sorted(), mismatched.sorted(), missing.sorted())
    }
}

data class FieldComparison(
    val matched: List<String>,
    val mismatched: List<String>,
    val missing: List<String>,
) {
    val agrees: Boolean get() = mismatched.isEmpty() && missing.isEmpty() && matched.isNotEmpty()
}
