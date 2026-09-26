package app.mizan.service.store

import java.nio.file.Path

/**
 * The durability seam.
 *
 * Every durable store in the service (the journal, the idempotency index, the
 * sessions, the receipts, the devices, the approvals, the reconciliation cases
 * and the outbox) is an append-only log of JSON records that is replayed into
 * an index at start-up. That shape does not depend on where the bytes live,
 * so this interface is what the stores are written against and what a
 * PostgreSQL deployment replaces.
 *
 * The contract is short and the service depends on all of it:
 *
 *  - [append] returns only after the record is durable. A record that came
 *    back from [append] survives a power loss;
 *  - [records] returns the records in the order they were appended;
 *  - [compact] replaces the whole log with the given state atomically. A
 *    reader either sees the old log or the new one, never half of each, and a
 *    crash during a compaction leaves the old log intact.
 */
interface RecordLog : AutoCloseable {

    fun append(record: String)

    fun records(): List<String>

    fun compact(state: List<String>)

    override fun close() {}
}

/** Opens one log per stream name. A stream is a logical file, not a table. */
interface LogProvider : AutoCloseable {

    fun open(stream: String): RecordLog

    override fun close() {}
}

/**
 * The default provider: one append-only file per stream, fsynced on append.
 *
 * It is what makes the durability claim testable on a machine with no
 * database. The file names are the stream names plus `.log`, which is what
 * every existing store directory already contains.
 */
class FileLogProvider(private val directory: Path) : LogProvider {

    private val opened = LinkedHashMap<String, DurableLog>()

    @Synchronized
    override fun open(stream: String): RecordLog {
        val existing = opened[stream]
        if (existing != null) return existing
        val created = DurableLog(directory.resolve("$stream.log"))
        opened[stream] = created
        return created
    }

    @Synchronized
    override fun close() {
        opened.values.forEach { it.close() }
        opened.clear()
    }
}
