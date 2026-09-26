package app.mizan.service.outbox

import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.text
import app.mizan.service.json.whole
import app.mizan.service.store.DurableLog
import java.time.Duration

/**
 * Work that was decided and must still happen.
 *
 * This is not a second way to write to the ERP. Its one job is to remember
 * that a *read* the ERP could not answer should be asked again later, and to
 * remember it in the same durable log as everything else, so a restart cannot
 * lose the intention.
 *
 * What it deliberately cannot do is dispatch a write. A write whose answer was
 * lost is ambiguous and belongs to reconciliation, where a person decides. The
 * outbox refuses such an entry rather than queueing it, because a queue that
 * retries writes is a duplicate-record generator with a schedule.
 */
class Outbox(private val log: DurableLog) {

    enum class State {
        PENDING,
        IN_FLIGHT,
        DONE,
        DEAD_LETTERED,
    }

    data class Entry(
        val id: String,
        val tenantId: String,
        val actorId: String,
        val tool: ToolName,
        val executionId: String,
        val idempotencyKey: String,
        val arguments: String,
        val attempts: Int,
        val state: State,
        val nextAttemptAtMillis: Long,
        val lastErrorCode: String?,
        val createdAtMillis: Long,
    ) {
        val claimable: Boolean get() = state == State.PENDING || state == State.IN_FLIGHT
    }

    private val byId = LinkedHashMap<String, Entry>()

    init {
        for (record in log.records()) {
            val parsed = Json.parseOrNull(record)?.asObject() ?: continue
            val entry = parse(parsed) ?: continue
            byId[entry.id] = entry
        }
    }

    /** Records the intention. Refuses anything that names an unreadable tool later. */
    @Synchronized
    fun enqueue(entry: Entry): Entry {
        require(entry.state == State.PENDING) { "a new outbox entry starts pending" }
        require(entry.attempts == 0) { "a new outbox entry has no attempts" }
        write(entry)
        byId[entry.id] = entry
        return entry
    }

    @Synchronized
    fun get(id: String): Entry? = byId[id]

    @Synchronized
    fun all(): List<Entry> = byId.values.toList()

    @Synchronized
    fun pending(): List<Entry> = byId.values.filter { it.state == State.PENDING }

    /** Entries due now, oldest first, moved to in-flight by the caller. */
    @Synchronized
    fun due(nowMillis: Long, limit: Int = 20): List<Entry> = byId.values
        .filter { it.state == State.PENDING && it.nextAttemptAtMillis <= nowMillis }
        .sortedBy { it.nextAttemptAtMillis }
        .take(limit)

    @Synchronized
    fun markInFlight(id: String): Entry? = transition(id) { it.copy(state = State.IN_FLIGHT) }

    @Synchronized
    fun markDone(id: String): Entry? = transition(id) { it.copy(state = State.DONE, lastErrorCode = null) }

    /**
     * Schedules the next attempt. The delay grows with the attempt count, so a
     * sick ERP is not hammered by the clients that depend on it.
     */
    @Synchronized
    fun markFailed(id: String, errorCode: String, nowMillis: Long, maxAttempts: Int = MAX_ATTEMPTS): Entry? {
        val entry = byId[id] ?: return null
        val attempts = entry.attempts + 1
        val next = if (attempts >= maxAttempts) {
            entry.copy(
                attempts = attempts,
                state = State.DEAD_LETTERED,
                lastErrorCode = errorCode,
                nextAttemptAtMillis = nowMillis,
            )
        } else {
            entry.copy(
                attempts = attempts,
                state = State.PENDING,
                lastErrorCode = errorCode,
                nextAttemptAtMillis = nowMillis + backoffMillis(attempts),
            )
        }
        write(next)
        byId[id] = next
        return next
    }

    /**
     * Returns an entry that was in flight to the pending queue. Called when a
     * worker dies mid-dispatch, so the work is not quietly lost.
     */
    @Synchronized
    fun release(id: String, nowMillis: Long): Entry? =
        transition(id) { it.copy(state = State.PENDING, nextAttemptAtMillis = nowMillis) }

    @Synchronized
    fun deadLettered(): List<Entry> = byId.values.filter { it.state == State.DEAD_LETTERED }

    @Synchronized
    fun size(): Int = byId.size

    /** Drops finished entries from the log while keeping everything else. */
    @Synchronized
    fun compact(): Int {
        val live = byId.values.filter { it.state != State.DONE }
        log.compact(live.map { Json.write(encode(it)) })
        byId.clear()
        live.forEach { byId[it.id] = it }
        return byId.size
    }

    private fun transition(id: String, change: (Entry) -> Entry): Entry? {
        val entry = byId[id] ?: return null
        val next = change(entry)
        write(next)
        byId[id] = next
        return next
    }

    private fun write(entry: Entry) {
        log.append(Json.write(encode(entry)))
    }

    private fun encode(entry: Entry): JsonValue = Json.obj(
        "id" to Json.str(entry.id),
        "tenantId" to Json.str(entry.tenantId),
        "actorId" to Json.str(entry.actorId),
        "tool" to Json.str(entry.tool.wire),
        "executionId" to Json.str(entry.executionId),
        "idempotencyKey" to Json.str(entry.idempotencyKey),
        "arguments" to Json.str(entry.arguments),
        "attempts" to Json.num(entry.attempts),
        "state" to Json.str(entry.state.name),
        "nextAttemptAtMillis" to Json.num(entry.nextAttemptAtMillis),
        "lastErrorCode" to Json.str(entry.lastErrorCode),
        "createdAtMillis" to Json.num(entry.createdAtMillis),
    )

    private fun parse(record: JsonValue.Obj): Entry? {
        val id = record.text("id") ?: return null
        val tool = record.text("tool")?.let { ToolName.fromWire(it) } ?: return null
        val state = record.text("state")?.let { runCatching { State.valueOf(it) }.getOrNull() } ?: return null
        return Entry(
            id = id,
            tenantId = record.text("tenantId") ?: "",
            actorId = record.text("actorId") ?: "",
            tool = tool,
            executionId = record.text("executionId") ?: "",
            idempotencyKey = record.text("idempotencyKey") ?: "",
            arguments = record.text("arguments") ?: "{}",
            attempts = (record.whole("attempts") ?: 0L).toInt(),
            state = state,
            nextAttemptAtMillis = record.whole("nextAttemptAtMillis") ?: 0L,
            lastErrorCode = record.text("lastErrorCode"),
            createdAtMillis = record.whole("createdAtMillis") ?: 0L,
        )
    }

    companion object {
        /** After this many attempts a person is asked, not the ERP again. */
        const val MAX_ATTEMPTS = 5

        /** 30s, 1m, 2m, 4m: a fixed base doubling per attempt, with a ceiling. */
        val BACKOFF_BASE: Duration = Duration.ofSeconds(30)

        val BACKOFF_CEILING: Duration = Duration.ofMinutes(15)

        fun backoffMillis(attempts: Int): Long {
            val multiplier = 1L shl (attempts - 1).coerceIn(0, 10)
            return (BACKOFF_BASE.toMillis() * multiplier).coerceAtMost(BACKOFF_CEILING.toMillis())
        }

        /** Builds an entry for a read that the ERP could not answer. */
        fun forRetryableRead(
            id: String,
            tenantId: String,
            actorId: String,
            tool: ToolName,
            executionId: String,
            idempotencyKey: String,
            arguments: String,
            nowMillis: Long,
            delayMillis: Long = 0L,
        ): Entry = Entry(
            id = id,
            tenantId = tenantId,
            actorId = actorId,
            tool = tool,
            executionId = executionId,
            idempotencyKey = idempotencyKey,
            arguments = arguments,
            attempts = 0,
            state = State.PENDING,
            nextAttemptAtMillis = nowMillis + delayMillis,
            lastErrorCode = null,
            createdAtMillis = nowMillis,
        )

        /** Reads the identity fields back out of an entry, for a dispatch call. */
        fun tenantOf(entry: Entry): TenantId = TenantId(entry.tenantId)

        fun executionOf(entry: Entry): ExecutionId = ExecutionId(entry.executionId)

        fun keyOf(entry: Entry): IdempotencyKey = IdempotencyKey(entry.idempotencyKey)
    }
}
