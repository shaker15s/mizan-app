package app.mizan.domain.receipt

import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.Digests
import app.mizan.domain.model.Fingerprints
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
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

/**
 * The fields of a receipt, as a reader that does not care what JSON library
 * is underneath.
 *
 * The device has to rebuild exactly the bytes the authority signed, from
 * bytes that travelled over a network. That is only possible if the wire
 * carries every field of [ReceiptClaims.canonical], which is why the list
 * below is the contract and not a convenience.
 */
interface ReceiptFieldSource {
    fun text(name: String): String?
    fun list(name: String): List<String>
    fun number(name: String): Long?
}

object ReceiptWire {

    /** Every field the signature covers. A missing one means no verification. */
    val SIGNED_FIELDS: List<String> = listOf(
        "actorId",
        "approvalLevel",
        "approverIds",
        "catalogVersion",
        "erpModel",
        "erpRecordId",
        "executionId",
        "inputHash",
        "issuedAtMillis",
        "policyHash",
        "policyVersionId",
        "proposalFingerprint",
        "receiptId",
        "tenantId",
        "tool",
        "toolVersion",
        "traceId",
        "verificationHash",
        "verifiedFields",
    )

    /**
     * Rebuilds the claims from the fields a device received.
     *
     * Returns null when anything the signature covers is absent: a partial
     * receipt is not a receipt, and guessing a default would produce a body
     * that cannot verify and an error that blames the signature.
     */
    fun claimsOf(source: ReceiptFieldSource): ReceiptClaims? {
        val level = source.text("approvalLevel")
            ?.let { name -> ApprovalLevel.entries.firstOrNull { it.name == name } }
            ?: return null
        val issuedAt = source.number("issuedAtMillis") ?: return null
        // Every text field is read before any of them is used: a receipt with
        // one missing claim is refused whole, not quietly rebuilt around a
        // blank that the signature would then disagree with.
        val texts = TEXT_FIELDS.associateWith { source.text(it)?.takeIf { value -> value.isNotEmpty() } }
        if (texts.any { it.value == null }) return null
        fun value(name: String): String = texts.getValue(name).orEmpty()
        return ReceiptClaims(
            receiptId = value("receiptId"),
            executionId = value("executionId"),
            tenantId = value("tenantId"),
            actorId = value("actorId"),
            approverIds = source.list("approverIds"),
            proposalFingerprint = value("proposalFingerprint"),
            tool = value("tool"),
            toolVersion = value("toolVersion"),
            catalogVersion = value("catalogVersion"),
            policyVersionId = value("policyVersionId"),
            policyHash = value("policyHash"),
            approvalLevel = level,
            inputHash = value("inputHash"),
            erpModel = value("erpModel"),
            erpRecordId = value("erpRecordId"),
            verificationHash = value("verificationHash"),
            verifiedFields = source.list("verifiedFields"),
            issuedAtMillis = issuedAt,
            traceId = value("traceId"),
        )
    }

    /** The signed fields whose value is a single string. */
    private val TEXT_FIELDS: List<String> = listOf(
        "actorId",
        "catalogVersion",
        "erpModel",
        "erpRecordId",
        "executionId",
        "inputHash",
        "policyHash",
        "policyVersionId",
        "proposalFingerprint",
        "receiptId",
        "tenantId",
        "tool",
        "toolVersion",
        "traceId",
        "verificationHash",
    )

    /** The whole receipt, ready for [ReceiptInspector]. Null if unusable. */
    fun signedReceiptOf(
        source: ReceiptFieldSource,
        algorithm: String?,
        keyId: String?,
        signature: String?,
    ): SignedReceipt? {
        val claims = claimsOf(source) ?: return null
        return SignedReceipt(
            claims = claims,
            algorithm = algorithm.orEmpty(),
            keyId = keyId.orEmpty(),
            signature = signature.orEmpty(),
        )
    }
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

    /**
     * The receipt is well formed and its MAC is correct, and the device still
     * cannot call it a proof: verifying an HMAC requires the signing secret,
     * and a phone that holds the authority's signing secret can forge any
     * receipt it likes. This verdict exists so that a deployment using HMAC
     * says so out loud instead of showing a tick.
     */
    NOT_DEVICE_VERIFIABLE,
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
sealed interface ReceiptKey {
    val keyId: String
    val activeFrom: Instant
    val retiredAt: Instant?
    val algorithm: SignatureAlgorithm

    val active: Boolean get() = retiredAt == null
}

/** A key that can sign, and is itself part of what a verifier checks. */
data class SigningKey(
    override val keyId: String,
    val secret: ByteArray,
    override val activeFrom: Instant,
    override val retiredAt: Instant? = null,
) : ReceiptKey {

    init {
        require(secret.size >= 32) { "a signing secret must be at least 256 bits" }
    }

    override val algorithm: SignatureAlgorithm get() = SignatureAlgorithm.HMAC_SHA256

    // A secret must not leak through a log line, a stack trace or a debugger.
    override fun toString(): String = "SigningKey(keyId=$keyId, secret=redacted, active=$active)"

    override fun equals(other: Any?): Boolean =
        other is SigningKey && other.keyId == keyId && other.secret.contentEquals(secret)

    override fun hashCode(): Int = keyId.hashCode() * 31 + secret.contentHashCode()
}

/**
 * An authority key the device verifies with and never holds.
 *
 * The difference between this and [SigningKey] is the difference between a
 * proof and a promise: the device can verify an Ed25519 receipt with a public
 * key, and cannot forge one with it. A receipt signed by HMAC can only be
 * checked by someone holding the same secret that mints receipts, which on a
 * phone means the phone can mint them.
 */
data class AuthorityKeyPair(
    override val keyId: String,
    val privateKey: PrivateKey,
    val publicKeyBase64: String,
    override val activeFrom: Instant = Instant.EPOCH,
    override val retiredAt: Instant? = null,
) : ReceiptKey {

    override val algorithm: SignatureAlgorithm get() = SignatureAlgorithm.ED25519

    /** The half that gets pinned in the app's build. */
    fun publicKey(): AuthorityPublicKey = AuthorityPublicKey(
        keyId = keyId,
        algorithm = algorithm.wire,
        publicKeyBase64 = publicKeyBase64,
        retired = !active,
    )

    override fun toString(): String = "AuthorityKeyPair(keyId=$keyId, private=redacted, active=$active)"

    companion object {
        /** A fresh Ed25519 authority key. */
        fun generate(keyId: String, activeFrom: Instant = Instant.EPOCH): AuthorityKeyPair {
            // Ed25519 has no key size to choose; asking for one throws.
            val generator = java.security.KeyPairGenerator.getInstance(SignatureAlgorithm.ED25519.jcaName)
            val pair = generator.generateKeyPair()
            return AuthorityKeyPair(
                keyId = keyId,
                privateKey = pair.private,
                publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
                activeFrom = activeFrom,
            )
        }
    }
}

/** What a device is allowed to know about an authority key: the public half. */
data class AuthorityPublicKey(
    val keyId: String,
    val algorithm: String,
    val publicKeyBase64: String,
    val retired: Boolean = false,
)

enum class SignatureAlgorithm(val wire: String, val jcaName: String) {
    HMAC_SHA256("HMAC-SHA256", "HmacSHA256"),
    ED25519("Ed25519", "Ed25519"),
    ;

    /** The JCA name of the MAC this algorithm uses. Only defined for HMAC. */
    val macName: String get() = jcaName

    val asymmetric: Boolean get() = this == ED25519

    companion object {
        fun fromWire(wire: String): SignatureAlgorithm? = entries.firstOrNull { it.wire == wire }
    }
}

class ReceiptSigner(
    private val keys: List<ReceiptKey>,
    private val algorithm: SignatureAlgorithm = SignatureAlgorithm.HMAC_SHA256,
) {
    init {
        require(keys.isNotEmpty()) { "a signer needs at least one key" }
    }

    val activeKeyId: String get() = activeKey().keyId

    private fun activeKey(): ReceiptKey =
        keys.filter { it.active }.maxByOrNull { it.activeFrom } ?: keys.maxByOrNull { it.activeFrom }!!

    /** The public halves a device may pin. Never includes a secret. */
    fun publicKeys(): List<AuthorityPublicKey> = keys.mapNotNull {
        (it as? AuthorityKeyPair)?.publicKey()
    }

    fun sign(claims: ReceiptClaims): SignedReceipt {
        val key = activeKey()
        val body = claims.body().toByteArray(Charsets.UTF_8)
        val signature = when (key) {
            is SigningKey -> {
                val mac = mac(key)
                mac.update(body)
                encoder.encodeToString(mac.doFinal())
            }
            is AuthorityKeyPair -> {
                val signer = Signature.getInstance(SignatureAlgorithm.ED25519.jcaName)
                signer.initSign(key.privateKey)
                signer.update(body)
                encoder.encodeToString(signer.sign())
            }
        }
        return SignedReceipt(
            claims = claims,
            algorithm = key.algorithm.wire,
            keyId = key.keyId,
            signature = signature,
        )
    }

    /**
     * Rotation is additive: the new key signs, the old key still verifies.
     * A receipt whose key is retired verifies but is reported as retired, so a
     * reader can tell the difference between "forged" and "old".
     */
    fun rotate(newKey: ReceiptKey, at: Instant): ReceiptSigner = ReceiptSigner(
        keys = keys.map { if (it.active) it.copyRetired(at) else it } + newKey,
        algorithm = algorithm,
    )

    fun verify(signed: SignedReceipt): ReceiptVerification {
        if (signed.signature.isBlank() || signed.keyId.isBlank()) {
            return ReceiptVerification(ReceiptVerdict.UNSIGNED, "RECEIPT_UNSIGNED")
        }
        val key = keys.firstOrNull { it.keyId == signed.keyId }
            ?: return ReceiptVerification(ReceiptVerdict.UNKNOWN_KEY, "RECEIPT_KEY_UNKNOWN")
        if (SignatureAlgorithm.fromWire(signed.algorithm) != key.algorithm) {
            return ReceiptVerification(ReceiptVerdict.MALFORMED, "RECEIPT_ALGORITHM_UNSUPPORTED")
        }
        val valid = try {
            when (key) {
                is SigningKey -> macMatches(key, signed)
                is AuthorityKeyPair -> signatureMatches(key, signed)
            }
        } catch (error: IllegalArgumentException) {
            return ReceiptVerification(ReceiptVerdict.MALFORMED, "RECEIPT_MALFORMED")
        }
        if (!valid) {
            return ReceiptVerification(ReceiptVerdict.SIGNATURE_MISMATCH, "RECEIPT_SIGNATURE_MISMATCH")
        }
        return if (key.active) {
            ReceiptVerification(ReceiptVerdict.VALID, "RECEIPT_VALID")
        } else {
            ReceiptVerification(ReceiptVerdict.RETIRED_KEY, "RECEIPT_KEY_RETIRED")
        }
    }

    private fun macMatches(key: SigningKey, signed: SignedReceipt): Boolean {
        val mac = mac(key)
        mac.update(signed.claims.body().toByteArray(Charsets.UTF_8))
        val expected = encoder.encodeToString(mac.doFinal())
        val decoded = decoder.decode(signed.signature)
        // Constant time: a comparison that leaks timing is a forgery oracle.
        return MessageDigest.isEqual(decoded, decoder.decode(expected))
    }

    private fun signatureMatches(key: AuthorityKeyPair, signed: SignedReceipt): Boolean {
        val verifier = Signature.getInstance(SignatureAlgorithm.ED25519.jcaName)
        verifier.initVerify(parseAuthorityPublicKey(key.publicKeyBase64))
        verifier.update(signed.claims.body().toByteArray(Charsets.UTF_8))
        return verifier.verify(decoder.decode(signed.signature))
    }

    private fun mac(key: SigningKey): Mac {
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key.secret, algorithm.macName))
        return mac
    }

    companion object {
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getUrlDecoder()

        /** Parses the pinned half of an authority key. Throws on a malformed key. */
        fun parseAuthorityPublicKey(base64: String): PublicKey =
            java.security.KeyFactory.getInstance(SignatureAlgorithm.ED25519.jcaName)
                .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

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

/** Retires a key at a moment, without caring which kind of key it is. */
internal fun ReceiptKey.copyRetired(at: Instant): ReceiptKey = when (this) {
    is SigningKey -> copy(retiredAt = at)
    is AuthorityKeyPair -> copy(retiredAt = at)
}

/**
 * What the device may conclude from a receipt, having only public keys.
 *
 * The device is the last line of a chain that starts in the ERP and passes
 * through the service, the journal and the read-back. A receipt is the only
 * part of that chain it can check for itself, so what it can and cannot
 * conclude has to be a value, not a colour.
 */
enum class ReceiptTrust {
    /** Signed by a pinned key, and consistent with what the device expected. */
    VERIFIED,

    /**
     * The device has the receipt id and has not checked the signature yet.
     * It is not a verdict, and a screen that renders it as one is lying.
     */
    NOT_CHECKED,

    /** Signed by a pinned key that has since been rotated out. */
    RETIRED_KEY,

    /** The key is not one this device pinned. */
    UNKNOWN_KEY,

    /** The signature does not match the claims. Someone rewrote something. */
    SIGNATURE_MISMATCH,

    /** The signature is not readable as this algorithm's output. */
    MALFORMED,

    /** No signature at all. */
    UNSIGNED,

    /**
     * The receipt is signed with a symmetric key. The device cannot verify it
     * without holding the power to forge it, so it does not claim to have.
     */
    UNVERIFIABLE_SHARED_SECRET,

    /** The signature is fine; the claims are not about what the device asked about. */
    CLAIM_MISMATCH,
}

data class ReceiptInspection(
    val trust: ReceiptTrust,
    val reasonCode: String,
    /** True only for [ReceiptTrust.VERIFIED]. Retired is honest, not proven. */
    val proven: Boolean = trust == ReceiptTrust.VERIFIED,
)

/**
 * What the device expected the receipt to be about.
 *
 * A valid signature over the wrong execution is a proof of the wrong thing,
 * which is why this is an argument and not an afterthought.
 */
data class ReceiptExpectation(
    val tenantId: String,
    val executionId: String? = null,
    val proposalFingerprint: String? = null,
    val erpRecordId: String? = null,
)

/**
 * The device's half of the receipt contract.
 *
 * [ReceiptSigner] is what the authority uses; this is what a phone uses. It
 * holds no secret, and it refuses to call an HMAC-signed receipt a proof --
 * a shared secret on a device is a device that can mint its own receipts.
 */
object ReceiptInspector {

    fun inspect(
        signed: SignedReceipt,
        pinnedKeys: List<AuthorityPublicKey>,
        expected: ReceiptExpectation? = null,
    ): ReceiptInspection {
        if (signed.signature.isBlank() || signed.keyId.isBlank()) {
            return ReceiptInspection(ReceiptTrust.UNSIGNED, "RECEIPT_UNSIGNED")
        }
        val algorithm = SignatureAlgorithm.fromWire(signed.algorithm)
            ?: return ReceiptInspection(ReceiptTrust.MALFORMED, "RECEIPT_ALGORITHM_UNSUPPORTED")
        if (!algorithm.asymmetric) {
            return ReceiptInspection(
                ReceiptTrust.UNVERIFIABLE_SHARED_SECRET,
                "RECEIPT_HMAC_NOT_DEVICE_VERIFIABLE",
            )
        }
        val pinned = pinnedKeys.firstOrNull { it.keyId == signed.keyId }
            ?: return ReceiptInspection(ReceiptTrust.UNKNOWN_KEY, "RECEIPT_KEY_UNKNOWN")
        if (pinned.algorithm != algorithm.wire) {
            return ReceiptInspection(ReceiptTrust.MALFORMED, "RECEIPT_ALGORITHM_UNSUPPORTED")
        }
        val signatureOk = try {
            val verifier = Signature.getInstance(algorithm.jcaName)
            verifier.initVerify(ReceiptSigner.parseAuthorityPublicKey(pinned.publicKeyBase64))
            verifier.update(signed.claims.body().toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.getUrlDecoder().decode(signed.signature))
        } catch (error: Exception) {
            return ReceiptInspection(ReceiptTrust.MALFORMED, "RECEIPT_MALFORMED")
        }
        if (!signatureOk) {
            return ReceiptInspection(ReceiptTrust.SIGNATURE_MISMATCH, "RECEIPT_SIGNATURE_MISMATCH")
        }
        mismatchOf(signed.claims, expected)?.let { field ->
            return ReceiptInspection(ReceiptTrust.CLAIM_MISMATCH, "RECEIPT_CLAIM_$field")
        }
        return if (pinned.retired) {
            ReceiptInspection(ReceiptTrust.RETIRED_KEY, "RECEIPT_KEY_RETIRED")
        } else {
            ReceiptInspection(ReceiptTrust.VERIFIED, "RECEIPT_VALID")
        }
    }

    /** The first field the receipt disagrees with, or null when it agrees. */
    private fun mismatchOf(claims: ReceiptClaims, expected: ReceiptExpectation?): String? {
        if (expected == null) return null
        if (claims.tenantId != expected.tenantId) return "TENANT_MISMATCH"
        if (expected.executionId != null && claims.executionId != expected.executionId) {
            return "EXECUTION_MISMATCH"
        }
        if (expected.proposalFingerprint != null &&
            claims.proposalFingerprint != expected.proposalFingerprint
        ) {
            return "FINGERPRINT_MISMATCH"
        }
        if (expected.erpRecordId != null && claims.erpRecordId != expected.erpRecordId) {
            return "RECORD_MISMATCH"
        }
        return null
    }
}
