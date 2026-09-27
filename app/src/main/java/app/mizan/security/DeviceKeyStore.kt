package app.mizan.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The device key: an Ed25519 or P-256 private key that never leaves the
 * Android keystore.
 *
 * This is what makes an approval an act by a person on a device rather than a
 * session cookie saying yes. The service issues a challenge bound to one
 * approval's fingerprint, the device signs those exact bytes, and the service
 * verifies the signature against the public key it enrolled. The private key
 * is generated inside the keystore and marked non-exportable, so a stolen
 * application sandbox -- a rooted device, a backup, a debugger -- yields no key
 * material and therefore cannot answer for this person.
 *
 * Three decisions are deliberate:
 *
 *  - **Ed25519 first, P-256 as the fallback.** Ed25519 exists in the Android
 *    keystore from API 33; P-256 is available far earlier and is what the
 *    service also accepts. A device that cannot generate one is asked for the
 *    other, and the algorithm it used travels to the service with the enrolled
 *    key, so verification never has to guess.
 *  - **No user authentication is required here.** Biometric confirmation is a
 *    separate gate (`BiometricGate`) that runs before this one. Binding the
 *    key to a biometric prompt would mean a key that signs whenever a finger
 *    is present, which is not the same statement as "this person approved this
 *    proposal", and it makes a background retry impossible.
 *  - **Nothing is exported.** [publicKeyBase64] is the only material that ever
 *    leaves, and [sign] returns a signature over bytes the caller supplies.
 *
 * The identity of the device is a random id stored beside the key. It is not
 * a hardware id: an app that reports an IMEI is reporting something it should
 * not have, and a per-install id is enough to name the key.
 */
class DeviceKeyStore(context: Context) {

    private val appContext = context.applicationContext

    /** Which algorithm this device actually generated. */
    enum class Algorithm(val wire: String, val keyType: String, val curve: String?) {
        /** Preferred: modern, compact, and accepted by the service. */
        ED25519("Ed25519", "Ed25519", null),

        /** The fallback for a device whose keystore has no Ed25519. */
        P256("P-256", "EC", "secp256r1"),
    }

    private val preferences get() = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The device id the service knows, generated once per install. */
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: newDeviceId().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** The algorithm this install enrolled with, or null before enrolment. */
    val algorithm: Algorithm?
        get() = preferences.getString(KEY_ALGORITHM, null)?.let { stored ->
            Algorithm.entries.firstOrNull { it.wire == stored }
        }

    private var cachedAlgorithm: Algorithm? = null

    /**
     * Generates the key if it is not there yet, and returns what it is.
     *
     * Idempotent: calling it twice does not rotate the key, because rotating a
     * key silently would invalidate every receipt and every enrolled device
     * row the tenant holds.
     */
    fun ensureKey(): Result<Algorithm> = runCatching {
        cachedAlgorithm?.let { return@runCatching it }
        algorithm?.let { existing ->
            if (loadPrivateKey(existing) != null) {
                cachedAlgorithm = existing
                return@runCatching existing
            }
        }
        val generated = generate(Algorithm.ED25519).recoverCatching { generate(Algorithm.P256).getOrThrow() }.getOrThrow()
        preferences.edit().putString(KEY_ALGORITHM, generated.wire).apply()
        cachedAlgorithm = generated
        generated
    }

    /**
     * The public half, base64-encoded, for enrolment.
     *
     * Null when there is no key: enrolment must not be attempted with an empty
     * string, because an empty key enrols a device that can never answer.
     */
    fun publicKeyBase64(): String? {
        val current = ensureKey().getOrNull() ?: return null
        val entry = keystore()?.getEntry(alias(current), null) as? KeyStore.PrivateKeyEntry ?: return null
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Signs exactly the bytes the service named, returning base64, or null
     * when this device cannot sign.
     */
    fun sign(messageToSign: String): String? {
        val current = ensureKey().getOrNull() ?: return null
        val key = loadPrivateKey(current) ?: return null
        return runCatching {
            val signature = Signature.getInstance(signatureAlgorithm(current))
            signature.initSign(key)
            signature.update(messageToSign.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Forgets the identity, so the next enrolment is a new device. */
    fun reset() {
        val current = algorithm
        if (current != null) runCatching { keystore()?.deleteEntry(alias(current)) }
        preferences.edit().remove(KEY_DEVICE_ID).remove(KEY_ALGORITHM).apply()
        cachedAlgorithm = null
    }

    // ------------------------------------------------------------- internals

    private fun generate(algorithm: Algorithm): Result<Algorithm> = runCatching {
        val generator = KeyPairGenerator.getInstance(algorithm.keyType, PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias(algorithm),
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        // Ed25519 takes no parameters: asking for a curve is a platform error,
        // not a stricter key. P-256 names its curve because the keystore has
        // more than one.
        if (algorithm.curve != null) builder.setAlgorithmParameterSpec(ECGenParameterSpec(algorithm.curve))
        generator.initialize(builder.build())
        generator.generateKeyPair()
        algorithm
    }

    private fun loadPrivateKey(algorithm: Algorithm): PrivateKey? = runCatching {
        val entry = keystore()?.getEntry(alias(algorithm), null) as? KeyStore.PrivateKeyEntry
        entry?.privateKey
    }.getOrNull()

    private fun keystore(): KeyStore? = runCatching {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }.getOrNull()

    private fun alias(algorithm: Algorithm) = "$PREFIX${algorithm.name.lowercase()}"

    private fun signatureAlgorithm(algorithm: Algorithm) = when (algorithm) {
        Algorithm.ED25519 -> "Ed25519"
        Algorithm.P256 -> "SHA256withECDSA"
    }

    private fun newDeviceId(): String =
        "DEV-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val PREFIX = "mizan_device_"
        private const val FILE = "mizan_device_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ALGORITHM = "algorithm"
    }
}
