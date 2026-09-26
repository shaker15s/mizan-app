package app.mizan.service.store.sql

import app.mizan.service.store.LogProvider
import app.mizan.service.store.RecordLog

/**
 * The append-only record log, stored in PostgreSQL.
 *
 * The whole durable state of the service is a handful of append-only JSON
 * streams. In PostgreSQL each of those streams is rows of one table, ordered
 * by a sequence the database assigns, which gives the service the three
 * properties the file log gave it -- durability, order, atomic compaction --
 * plus the one the file log cannot give it: several service processes can
 * read and write the same streams.
 *
 * The table is deliberately narrow. It is a log, not a schema: the service
 * writes JSON records and replays them, so a deployment upgrades the service
 * without a migration, and a person can still read the state with `psql`.
 * Any table that needs a column and an index of its own (reporting, an audit
 * export) is a *view* over this log, not a second source of truth.
 */
class PostgresRecordLog(
    private val database: SqlDatabase,
    private val stream: String,
    private val table: String = DEFAULT_TABLE,
) : RecordLog {

    override fun append(record: String) {
        database.transaction { session ->
            session.select(insertSql(), listOf(stream, record))
            Unit
        }
    }

    override fun records(): List<String> = database.transaction { session ->
        session.select(selectSql(), listOf(stream)).mapNotNull { row -> row["payload"] as? String }
    }

    /**
     * Replaces the stream in one transaction, so a reader between the delete
     * and the insert cannot exist: either the old log is visible or the new
     * one is. A crash mid-compaction rolls the whole thing back.
     */
    override fun compact(state: List<String>) {
        database.transaction { session ->
            session.update(deleteSql(), listOf(stream))
            state.forEach { record -> session.select(insertSql(), listOf(stream, record)) }
            Unit
        }
    }

    private fun insertSql(): String = SQL_INSERT.format(table)

    private fun selectSql(): String = SQL_SELECT.format(table)

    private fun deleteSql(): String = SQL_DELETE.format(table)

    companion object {

        const val DEFAULT_TABLE = "mizan_records"

        /** Insert one record and let the database assign its position. */
        const val SQL_INSERT = "INSERT INTO %s (stream, payload) VALUES (?, ?) RETURNING seq"

        /** Read a stream in the order the database assigned. */
        const val SQL_SELECT = "SELECT payload FROM %s WHERE stream = ? ORDER BY seq"

        /** Drop a stream before it is rewritten by a compaction. */
        const val SQL_DELETE = "DELETE FROM %s WHERE stream = ?"

        /** The schema. Created idempotently at start-up. */
        val SQL_SCHEMA: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS %s (" +
                "seq BIGSERIAL PRIMARY KEY, " +
                "stream TEXT NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "written_at TIMESTAMPTZ NOT NULL DEFAULT now())",
            "CREATE INDEX IF NOT EXISTS %s_stream_seq_idx ON %s (stream, seq)",
            "CREATE TABLE IF NOT EXISTS mizan_schema (" +
                "version INTEGER NOT NULL, " +
                "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())",
            "INSERT INTO mizan_schema (version) SELECT 1 WHERE NOT EXISTS " +
                "(SELECT 1 FROM mizan_schema WHERE version = 1)",
        )

        private val IDENTIFIER = Regex("[a-z_][a-z0-9_]*")

        fun schemaStatements(table: String = DEFAULT_TABLE): List<String> {
            require(IDENTIFIER.matches(table)) { "table name must be a plain lower-case identifier: $table" }
            return SQL_SCHEMA.map { statement ->
                when (statement.count { it == '%' }) {
                    1 -> statement.format(table)
                    2 -> statement.format(table, table)
                    else -> statement
                }
            }
        }

        /** The columns the schema defines, for the drift test that guards the SQL. */
        fun schemaColumns(table: String = DEFAULT_TABLE): Set<String> {
            val create = schemaStatements(table).first()
            val body = create.substringAfter('(').substringBeforeLast(')')
            return body.split(',')
                .mapNotNull { part -> part.trim().split(' ').firstOrNull()?.lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}

/**
 * A provider over PostgreSQL.
 *
 * The schema is applied when the provider is built, not when the first record
 * is written: a deployment that cannot create its table must fail at start-up,
 * loudly, rather than on the first customer's approval.
 */
class SqlLogProvider(
    private val database: SqlDatabase,
    private val table: String = PostgresRecordLog.DEFAULT_TABLE,
) : LogProvider {

    init {
        database.transaction { session ->
            PostgresRecordLog.schemaStatements(table).forEach { statement -> session.update(statement) }
        }
    }

    override fun open(stream: String): RecordLog = PostgresRecordLog(database, stream, table)
}

/** Where the durable state lives, when it lives in a database. */
data class DatabaseConfig(
    val url: String,
    val user: String? = null,
    val password: String? = null,
    val table: String = PostgresRecordLog.DEFAULT_TABLE,
    val driverClass: String? = "org.postgresql.Driver",
)
