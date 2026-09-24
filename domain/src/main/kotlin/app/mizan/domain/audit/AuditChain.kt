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
)

data class ChainReport(
    val intact: Boolean,
    val records: Int,
    val brokenIndex: Long?,
    val messageCode: String,
)

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

class ChainVerifier {
    fun verify(events: List<AuditEvent>): ChainReport {
        if (events.isEmpty()) {
            return ChainReport(true, 0, null, "CHAIN_EMPTY")
        }
        val ordered = events.sortedBy { it.chainIndex }
        var expectedPrev = AuditHasher.GENESIS
        for (event in ordered) {
            if (event.previousHash != expectedPrev) {
                return ChainReport(false, ordered.size, event.chainIndex, "CHAIN_LINK_MISMATCH")
            }
            val recomputed = AuditHasher.hash(event, event.previousHash)
            if (recomputed != event.currentHash) {
                return ChainReport(false, ordered.size, event.chainIndex, "CHAIN_PAYLOAD_MISMATCH")
            }
            expectedPrev = event.currentHash
        }
        return ChainReport(true, ordered.size, null, "CHAIN_INTACT_LOCAL")
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
