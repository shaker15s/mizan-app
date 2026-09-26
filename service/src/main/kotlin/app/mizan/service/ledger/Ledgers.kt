package app.mizan.service.ledger

import app.mizan.domain.audit.AuditEvent
import app.mizan.domain.audit.AuditHasher
import app.mizan.domain.audit.ChainReport
import app.mizan.domain.audit.ChainVerifier
import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.model.Digests

/**
 * Per-tenant hash chain kept by the service.
 *
 * These rows are authored by the authority, not by the device, which is why
 * they are [IntegrityClass.SERVER_AUTHORED]. The chain proves the service's
 * own history is internally consistent. It is not an external witness.
 */
class AuditLedger(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private val chains = LinkedHashMap<String, MutableList<AuditEvent>>()
    private val verifier = ChainVerifier()

    @Synchronized
    fun append(
        tenantId: String,
        traceId: String,
        actorId: String,
        action: String,
        stateBefore: String,
        stateAfter: String,
        details: String,
    ): AuditEvent {
        val rows = chains.getOrPut(tenantId) { ArrayList() }
        val previous = rows.lastOrNull()?.currentHash ?: AuditHasher.GENESIS
        val shell = AuditEvent(
            chainIndex = (rows.lastOrNull()?.chainIndex ?: 0L) + 1L,
            timestampMillis = clock(),
            traceId = traceId,
            tenantId = tenantId,
            actorId = actorId,
            action = action,
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            details = details,
            previousHash = previous,
            currentHash = "",
            integrityClass = IntegrityClass.SERVER_AUTHORED,
        )
        val event = shell.copy(currentHash = AuditHasher.hash(shell, previous))
        rows.add(event)
        return event
    }

    @Synchronized
    fun events(tenantId: String): List<AuditEvent> = ArrayList(chains[tenantId] ?: emptyList())

    @Synchronized
    fun verify(tenantId: String): ChainReport = verifier.verify(events(tenantId))

    @Synchronized
    fun tenants(): List<String> = chains.keys.toList()

    @Synchronized
    fun size(tenantId: String): Int = chains[tenantId]?.size ?: 0
}

/** The remembered result of an idempotency key, scoped by tenant. */
data class LedgerEntry(
    val tenantId: String,
    val key: String,
    val canonicalArguments: String,
    val executionId: String,
    val traceId: String,
    val status: String,
    val erpRecordId: String?,
    val erpModel: String?,
    val messageCode: String,
    val candidateRecordIds: List<String>,
    val summary: String?,
) {
    fun fingerprint(): String = Digests.sha256("$tenantId|$key|$canonicalArguments").take(16)
}

/**
 * Remembers what a key already did, so a repeat never creates a second ERP
 * record. A key used again with different arguments is refused: that is a
 * client bug or an attack, and it must not be silently treated as a retry.
 */
class ExecutionLedger {

    private val entries = LinkedHashMap<String, LedgerEntry>()

    @Synchronized
    fun find(tenantId: String, key: String): LedgerEntry? = entries[index(tenantId, key)]

    @Synchronized
    fun record(entry: LedgerEntry): LedgerEntry {
        entries[index(entry.tenantId, entry.key)] = entry
        return entry
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun all(): List<LedgerEntry> = entries.values.toList()

    private fun index(tenantId: String, key: String) = "$tenantId::$key"
}
