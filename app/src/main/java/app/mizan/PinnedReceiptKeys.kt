package app.mizan

import app.mizan.domain.receipt.AuthorityPublicKey

/**
 * The authority keys this build verifies receipts with.
 *
 * They are compiled in, not fetched. A phone that downloads the key it checks
 * signatures against is checking them against whatever the server chose to
 * send, which is a signature check that a compromised server passes by
 * definition. The service publishes its public keys so an operator can compare
 * them with what is pinned here, and so the reference deployment can be
 * exercised end to end -- and never as the source of truth.
 *
 * An empty list is a legitimate state: it means this build pinned nothing, and
 * the app then reports that it did not check the receipt instead of showing a
 * verification it never performed.
 */
object PinnedReceiptKeys {

    private val pinned: List<AuthorityPublicKey> by lazy {
        val keyId = BuildConfig.RECEIPT_KEY_ID.trim()
        val publicKey = BuildConfig.RECEIPT_PUBLIC_KEY.trim()
        if (keyId.isEmpty() || publicKey.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AuthorityPublicKey(
                    keyId = keyId,
                    algorithm = "Ed25519",
                    publicKeyBase64 = publicKey,
                ),
            )
        }
    }

    fun of(): List<AuthorityPublicKey> = pinned

    /** True when a key is pinned, so a screen can say what it can and cannot do. */
    val configured: Boolean get() = pinned.isNotEmpty()
}
