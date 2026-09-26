package app.mizan.domain.security

import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Fingerprints
import app.mizan.domain.model.TenantId
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Device-bound authorisation.
 *
 * A prompt that returns "the screen was unlocked" is a claim by the app. A
 * signature over a server-issued challenge by a key the server has never seen
 * the private half of is evidence about a device. The sensitive approval path
 * uses the second.
 *
 * The signed message includes the proposal fingerprint and a single-use nonce,
 * so a signature collected for one proposal cannot approve another and cannot
 * approve the same one twice.
 */
enum class DeviceKeyAlgorithm(val wire: String, val jcaName: String) {
    ED25519("Ed25519", "Ed25519"),
    ECDSA_P256("ES256", "SHA256withECDSA"),
    ;

    companion object {
        fun fromWire(wire: String): DeviceKeyAlgorithm? = entries.firstOrNull { it.wire == wire }
    }
}

data class DevicePublicKey(
    val deviceId: String,
    val tenantId: TenantId,
    val actorId: ActorId,
    val algorithm: DeviceKeyAlgorithm,
    val publicKeyBase64: String,
    val label: String,
    val enrolledAtMillis: Long,
    val revokedAtMillis: Long? = null,
    val lastSeenAtMillis: Long? = null,
) {
    val revoked: Boolean get() = revokedAtMillis != null
}

interface DeviceRepository {
    fun find(deviceId: String): DevicePublicKey?
    fun save(device: DevicePublicKey)
    fun byTenant(tenantId: TenantId): List<DevicePublicKey>
}

class InMemoryDeviceRepository(initial: List<DevicePublicKey> = emptyList()) : DeviceRepository {
    private val devices = LinkedHashMap<String, DevicePublicKey>()

    init {
        initial.forEach { devices[it.deviceId] = it }
    }

    override fun find(deviceId: String): DevicePublicKey? = devices[deviceId]

    override fun save(device: DevicePublicKey) {
        devices[device.deviceId] = device
    }

    override fun byTenant(tenantId: TenantId): List<DevicePublicKey> =
        devices.values.filter { it.tenantId == tenantId }
}

data class ApprovalChallenge(
    val challengeId: String,
    val nonce: String,
    val deviceId: String,
    val tenantId: TenantId,
    val actorId: ActorId,
    val executionId: ExecutionId,
    val proposalFingerprint: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val consumedAtMillis: Long? = null,
) {
    val expiresInMillis: Long get() = expiresAtMillis - issuedAtMillis
}

enum class ChallengeVerdict {
    VALID,
    UNKNOWN_CHALLENGE,
    UNKNOWN_DEVICE,
    REVOKED_DEVICE,
    WRONG_TENANT,
    WRONG_ACTOR,
    EXPIRED,
    REPLAYED,
    SIGNATURE_INVALID,
    MALFORMED,
}

data class ChallengeVerification(
    val verdict: ChallengeVerdict,
    val reasonCode: String,
    val errorCode: String?,
) {
    val valid: Boolean get() = verdict == ChallengeVerdict.VALID
}

interface ChallengeRepository {
    fun find(challengeId: String): ApprovalChallenge?
    fun save(challenge: ApprovalChallenge)
}

class InMemoryChallengeRepository : ChallengeRepository {
    private val challenges = LinkedHashMap<String, ApprovalChallenge>()

    override fun find(challengeId: String): ApprovalChallenge? = challenges[challengeId]

    override fun save(challenge: ApprovalChallenge) {
        challenges[challenge.challengeId] = challenge
    }
}

class DeviceBindingService(
    private val devices: DeviceRepository,
    private val challenges: ChallengeRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val challengeTtlMillis: Long = 120_000,
) {

    fun enroll(
        deviceId: String,
        tenantId: TenantId,
        actorId: ActorId,
        algorithm: DeviceKeyAlgorithm,
        publicKeyBase64: String,
        label: String,
    ): DevicePublicKey {
        // A key that does not parse must never be stored: it would fail every
        // verification later and look like a forgery.
        parsePublicKey(algorithm, publicKeyBase64)
        val device = DevicePublicKey(
            deviceId = deviceId,
            tenantId = tenantId,
            actorId = actorId,
            algorithm = algorithm,
            publicKeyBase64 = publicKeyBase64,
            label = label,
            enrolledAtMillis = clock(),
        )
        devices.save(device)
        return device
    }

    /** Every device a tenant has enrolled, revoked ones included. */
    fun byTenant(tenantId: TenantId): List<DevicePublicKey> = devices.byTenant(tenantId)

    fun revoke(deviceId: String, atMillis: Long = clock()): DevicePublicKey? {
        val device = devices.find(deviceId) ?: return null
        val revoked = device.copy(revokedAtMillis = atMillis)
        devices.save(revoked)
        return revoked
    }

    fun issueChallenge(
        challengeId: String,
        nonce: String,
        deviceId: String,
        executionId: ExecutionId,
        tenantId: TenantId,
        actorId: ActorId,
        proposalFingerprint: String,
    ): ApprovalChallenge? {
        val device = devices.find(deviceId) ?: return null
        if (device.revoked) return null
        if (device.tenantId != tenantId || device.actorId != actorId) return null
        val issued = clock()
        val challenge = ApprovalChallenge(
            challengeId = challengeId,
            nonce = nonce,
            deviceId = deviceId,
            tenantId = tenantId,
            actorId = actorId,
            executionId = executionId,
            proposalFingerprint = proposalFingerprint,
            issuedAtMillis = issued,
            expiresAtMillis = issued + challengeTtlMillis,
        )
        challenges.save(challenge)
        return challenge
    }

    /** The bytes a device signs. Published so both sides cannot drift apart. */
    fun message(challenge: ApprovalChallenge): ByteArray =
        Fingerprints.approvalChallenge(
            tenantId = challenge.tenantId,
            actorId = challenge.actorId,
            executionId = challenge.executionId,
            proposalFingerprint = challenge.proposalFingerprint,
            nonce = challenge.nonce,
        ).toByteArray(Charsets.UTF_8)

    fun verify(
        challengeId: String,
        signatureBase64: String,
        proposalFingerprint: String,
    ): ChallengeVerification {
        val challenge = challenges.find(challengeId)
            ?: return failure(ChallengeVerdict.UNKNOWN_CHALLENGE, "CHALLENGE_UNKNOWN", "SECURITY_CHALLENGE_REPLAYED")
        val device = devices.find(challenge.deviceId)
            ?: return failure(ChallengeVerdict.UNKNOWN_DEVICE, "DEVICE_UNKNOWN", "SECURITY_DEVICE_NOT_ENROLLED")
        val now = clock()
        return when {
            challenge.consumedAtMillis != null ->
                failure(ChallengeVerdict.REPLAYED, "CHALLENGE_REPLAYED", "SECURITY_CHALLENGE_REPLAYED")
            device.revoked ->
                failure(ChallengeVerdict.REVOKED_DEVICE, "DEVICE_REVOKED", "SECURITY_DEVICE_REVOKED")
            now > challenge.expiresAtMillis ->
                failure(ChallengeVerdict.EXPIRED, "CHALLENGE_EXPIRED", "SECURITY_CHALLENGE_EXPIRED")
            challenge.proposalFingerprint != proposalFingerprint ->
                failure(ChallengeVerdict.WRONG_TENANT, "PROPOSAL_CHANGED", "APPROVAL_INVALIDATED")
            else -> {
                val verified = verifySignature(device, challenge, signatureBase64)
                if (!verified.valid) {
                    return failure(verified.verdict, verified.reasonCode, verified.errorCode)
                }
                challenges.save(challenge.copy(consumedAtMillis = now))
                devices.save(device.copy(lastSeenAtMillis = now))
                ChallengeVerification(ChallengeVerdict.VALID, "CHALLENGE_VERIFIED", null)
            }
        }
    }

    private fun verifySignature(
        device: DevicePublicKey,
        challenge: ApprovalChallenge,
        signatureBase64: String,
    ): ChallengeVerification {
        val publicKey = try {
            parsePublicKey(device.algorithm, device.publicKeyBase64)
        } catch (error: Exception) {
            return ChallengeVerification(ChallengeVerdict.MALFORMED, "DEVICE_KEY_MALFORMED", "SECURITY_SIGNATURE_INVALID")
        }
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (error: IllegalArgumentException) {
            return ChallengeVerification(
                ChallengeVerdict.MALFORMED,
                "SIGNATURE_NOT_BASE64",
                "SECURITY_SIGNATURE_INVALID",
            )
        }
        val verifier = Signature.getInstance(device.algorithm.jcaName)
        verifier.initVerify(publicKey)
        verifier.update(message(challenge))
        val valid = try {
            verifier.verify(signatureBytes)
        } catch (error: Exception) {
            false
        }
        return if (valid) {
            ChallengeVerification(ChallengeVerdict.VALID, "SIGNATURE_VERIFIED", null)
        } else {
            ChallengeVerification(
                ChallengeVerdict.SIGNATURE_INVALID,
                "SIGNATURE_INVALID",
                "SECURITY_SIGNATURE_INVALID",
            )
        }
    }

    private fun failure(verdict: ChallengeVerdict, reason: String, errorCode: String?) =
        ChallengeVerification(verdict, reason, errorCode)

    companion object {
        fun parsePublicKey(algorithm: DeviceKeyAlgorithm, base64: String): PublicKey {
            val bytes = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance(algorithm.jcaName).generatePublic(X509EncodedKeySpec(bytes))
        }
    }
}

/**
 * The device half of the handshake. Android generates the private key in the
 * Keystore and never exports it; this object exists so the JVM side of the
 * same protocol can be tested for real, with real keys and real signatures.
 */
object DeviceKeyMaterial {
    fun generate(algorithm: DeviceKeyAlgorithm = DeviceKeyAlgorithm.ED25519): KeyPair =
        KeyPairGenerator.getInstance(algorithm.jcaName).apply { initialize(256) }.generateKeyPair()

    fun encode(publicKey: PublicKey): String = Base64.getEncoder().encodeToString(publicKey.encoded)

    fun sign(privateKey: java.security.PrivateKey, message: ByteArray, algorithm: DeviceKeyAlgorithm): String {
        val signer = Signature.getInstance(algorithm.jcaName)
        signer.initSign(privateKey)
        signer.update(message)
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}
