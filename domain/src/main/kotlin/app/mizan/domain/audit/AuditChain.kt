package app.mizan.domain.audit

import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.Digests
import app.mizan.domain.model.TenantId

/**
 * Local integrity check. A matching chain means the rows on this device
 * still hash together. It does not mean a server, a witness, or a court
 * has stored the same history.
 */
enum class IntegrityClass {
    LOCAL_ONLY,
    SERVER_AUTHORED,
    EXTERNAL_DURABLE,
}

data class AuditEvent(
    val chainIndex: Long,
    val timestampMillis: Long,
    val traceId: String,
    val tenantId: String,
    val actorId: String,
    val action: String,
    val stateBefore: String,
    val stateAfter: String,
    val details: String,
    val previousHash: String,
    val currentHash: String,
    val integrityClass: IntegrityClass = IntegrityClass.LOCAL_ONLY,
    /**
     * The authority's signature over [currentHash].
     *
     * A hash chain proves the rows are internally consistent; anyone who can
     * rewrite the table can rewrite the whole chain consistently. A seal is
     * what makes that impossible without the key, and its absence is reported
     * as an absence rather than treated as a pass.
     */
    val seal: app.mizan.domain.receipt.DetachedSignature? = null,
)

data class ChainReport(
    val intact: Boolean,
    val records: Int,
    val brokenIndex: Long?,
    val messageCode: String,
    /** How many rows carried a seal, and how many seals verified. */
    val sealedRecords: Int = 0,
    val verifiedSeals: Int = 0,
) {
    /** True when every row is sealed and every seal verifies. */
    val fullySealed: Boolean get() = records > 0 && sealedRecords == records && verifiedSeals == records
}

object AuditHasher {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun payload(event: AuditEvent, previousHash: String): String {
        val canonical = CanonicalValue.Obj(
            listOf(
                "action" to CanonicalValue.Str(event.action),
                "actorId" to CanonicalValue.Str(event.actorId),
                "chainIndex" to CanonicalValue.Num(event.chainIndex.toString()),
                "details" to CanonicalValue.Str(event.details),
                "previousHash" to CanonicalValue.Str(previousHash),
                "stateAfter" to CanonicalValue.Str(event.stateAfter),
                "stateBefore" to CanonicalValue.Str(event.stateBefore),
                "tenantId" to CanonicalValue.Str(event.tenantId),
                "timestampMillis" to CanonicalValue.Num(event.timestampMillis.toString()),
                "traceId" to CanonicalValue.Str(event.traceId),
            ),
        )
        return CanonicalJson.write(canonical)
    }

    fun hash(event: AuditEvent, previousHash: String): String = Digests.sha256(payload(event, previousHash))
}

class ChainVerifier(
    /**
     * When set, every row must carry a seal this verifier can check. A chain
     * of unsigned rows is not "intact": it is a chain nobody stood behind, and
     * a deployment that configured a key must be told so.
     */
    private val sealer: AuditSealer? = null,
) {
    fun verify(events: List<AuditEvent>): ChainReport {
        if (events.isEmpty()) {
            return ChainReport(true, 0, null, "CHAIN_EMPTY")
        }
        val ordered = events.sortedBy { it.chainIndex }
        var expectedPrev = AuditHasher.GENESIS
        var sealed = 0
        var verifiedSeals = 0
        for (event in ordered) {
            if (event.previousHash != expectedPrev) {
                return ChainReport(false, ordered.size, event.chainIndex, "CHAIN_LINK_MISMATCH")
            }
            val recomputed = AuditHasher.hash(event, event.previousHash)
            if (recomputed != event.currentHash) {
                return ChainReport(false, ordered.size, event.chainIndex, "CHAIN_PAYLOAD_MISMATCH")
            }
            if (event.seal != null) {
                sealed++
                if (sealer == null) {
                    // Nobody here can check it. That is not a failure of the
                    // row, and it is not a verification either.
                } else if (!sealer.verify(event)) {
                    return ChainReport(
                        intact = false,
                        records = ordered.size,
                        brokenIndex = event.chainIndex,
                        messageCode = "CHAIN_SEAL_MISMATCH",
                        sealedRecords = sealed,
                        verifiedSeals = verifiedSeals,
                    )
                } else {
                    verifiedSeals++
                }
            } else if (sealer != null) {
                return ChainReport(
                    intact = false,
                    records = ordered.size,
                    brokenIndex = event.chainIndex,
                    messageCode = "CHAIN_NOT_SEALED",
                    sealedRecords = sealed,
                    verifiedSeals = verifiedSeals,
                )
            }
            expectedPrev = event.currentHash
        }
        return ChainReport(
            intact = true,
            records = ordered.size,
            brokenIndex = null,
            messageCode = if (sealer != null) "CHAIN_SEALED_OK" else "CHAIN_INTACT_LOCAL",
            sealedRecords = sealed,
            verifiedSeals = verifiedSeals,
        )
    }

    /**
     * Proves the verifier on a copy. Does not write.
     */
    fun demonstrateOnCopy(events: List<AuditEvent>): ChainReport {
        if (events.isEmpty()) return verify(events)
        val ordered = events.sortedBy { it.chainIndex }.toMutableList()
        val index = minOf(1, ordered.lastIndex)
        val target = ordered[index]
        ordered[index] = target.copy(details = target.details + " ")
        return verify(ordered)
    }
}

data class AuditAppend(
    val traceId: String,
    val tenantId: TenantId,
    val actorId: String,
    val action: String,
    val stateBefore: String,
    val stateAfter: String,
    val details: String,
    val timestampMillis: Long,
)

/**
 * The authority's seal on an audit row.
 *
 * The chain hash proves the rows hash together. Anyone who can rewrite one row
 * can rewrite the next one's `previousHash` and produce a chain that verifies
 * perfectly -- a hash chain is an internal-consistency check, not a defence
 * against the party that owns the table. Sealing each row with a key the table
 * owner does not hold is what turns "these rows are consistent" into "these
 * rows were written by the authority".
 */
class AuditSealer(private val signer: app.mizan.domain.receipt.ReceiptSigner) {

    /** The seal an event will carry, or null when the authority has no key. */
    fun seal(event: AuditEvent): app.mizan.domain.receipt.DetachedSignature = signer.sealDigest(event.currentHash)

    /** True when this event's seal matches its own hash. */
    fun verify(event: AuditEvent): Boolean {
        val seal = event.seal ?: return false
        return signer.verifyDigest(event.currentHash, seal)
    }

    /** The row as it should be stored: hashed, then sealed. */
    fun sealed(event: AuditEvent): AuditEvent = event.copy(seal = seal(event))

    val keyId: String get() = signer.activeKeyId

    companion object {
        /** A sealer over an Ed25519 authority key, or null when there is none. */
        fun of(key: app.mizan.domain.receipt.AuthorityKeyPair?): AuditSealer? =
            key?.let { AuditSealer(app.mizan.domain.receipt.ReceiptSigner(listOf(it))) }
    }
}
