package app.mizan.service

import app.mizan.domain.execution.JournalStage
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.service.authority.ReferenceDeployment
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.store.ServiceStores
import app.mizan.service.store.sql.DatabaseConfig
import app.mizan.service.store.sql.PostgresRecordLog
import app.mizan.service.store.sql.SqlDatabase
import app.mizan.service.store.sql.SqlLogProvider
import app.mizan.service.store.sql.SqlSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PostgreSQL deployment, tested as far as a machine with no PostgreSQL can
 * honestly test it.
 *
 * What is proven here is the adapter: the schema it creates, the statements it
 * emits, the order it reads a stream back in, and the atomicity of a
 * compaction. What is *not* proven is PostgreSQL itself -- the driver, the
 * server, the network, the failover. Those need a database, and this test file
 * says so instead of pretending otherwise.
 *
 * The fake below is not a mock: it understands the three statements the
 * adapter emits and executes their semantics on an in-memory table, which is
 * what lets a whole service run on the SQL path in the last test.
 */
class PostgresStoreTest {

    /**
     * A tiny PostgreSQL: the three statements the adapter uses, and a
     * transaction that really rolls back.
     */
    private class FakePostgres : SqlDatabase {

        data class Row(val seq: Long, val stream: String, val payload: String)

        private val rows = ArrayList<Row>()
        private var sequence = 0L
        private var committed = ArrayList<Row>()

        /** Every statement the adapter ran, for the tests that inspect them. */
        val statements = ArrayList<String>()
        var rollbacks = 0
        var commits = 0
        var failNextInsert = false

        override fun <T> transaction(block: (SqlSession) -> T): T {
            val snapshot = ArrayList(rows)
            val snapshotSequence = sequence
            try {
                val result = block(Session())
                commits++
                committed = ArrayList(rows)
                return result
            } catch (error: Throwable) {
                rows.clear()
                rows.addAll(snapshot)
                sequence = snapshotSequence
                rollbacks++
                throw error
            }
        }

        fun payloads(stream: String): List<String> = rows.filter { it.stream == stream }.map { it.payload }

        fun streamCount(stream: String): Int = rows.count { it.stream == stream }

        private inner class Session : SqlSession {

            override fun update(sql: String, parameters: List<Any?>): Int {
                statements += sql
                val normalised = sql.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                if (normalised.endsWith("RETURNING seq")) {
                    if (failNextInsert) {
                        failNextInsert = false
                        error("the database refused the insert")
                    }
                    rows += Row(++sequence, parameters[0] as String, parameters[1] as String)
                    return 1
                }
                if (normalised.startsWith("DELETE FROM")) {
                    val stream = parameters[0] as String
                    val removed = rows.count { it.stream == stream }
                    rows.removeAll { it.stream == stream }
                    return removed
                }
                // DDL and the schema-version insert are accepted and recorded.
                return 0
            }

            override fun select(sql: String, parameters: List<Any?>): List<Map<String, Any?>> {
                statements += sql
                val normalised = sql.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                if (normalised.endsWith("RETURNING seq")) {
                    if (failNextInsert) {
                        failNextInsert = false
                        error("the database refused the insert")
                    }
                    val row = Row(++sequence, parameters[0] as String, parameters[1] as String)
                    rows += row
                    return listOf(mapOf("seq" to row.seq))
                }
                if (normalised.startsWith("SELECT payload FROM")) {
                    val stream = parameters[0] as String
                    return rows.filter { it.stream == stream }
                        .sortedBy { it.seq }
                        .map { mapOf("payload" to it.payload) }
                }
                return emptyList()
            }
        }
    }

    private fun sqlLog(database: SqlDatabase) = PostgresRecordLog(database, stream = "execution-journal")

    // ------------------------------------------------------------------ schema

    @Test
    fun theSchemaIsCreatedWhenTheProviderIsBuilt() {
        val database = FakePostgres()
        SqlLogProvider(database)
        val ddl = database.statements.filter { it.startsWith("CREATE") }
        assertEquals(3, ddl.size)
        assertTrue("the records table is created", ddl[0].contains("CREATE TABLE IF NOT EXISTS mizan_records"))
        assertTrue("the position is assigned by the database", ddl[0].contains("BIGSERIAL PRIMARY KEY"))
        assertTrue("the stream index is created", ddl[1].contains("ON mizan_records (stream, seq)"))
        assertTrue("the schema version is recorded", ddl[2].contains("CREATE TABLE IF NOT EXISTS mizan_schema"))
        assertTrue(
            "the version insert is idempotent",
            database.statements.any { it.startsWith("INSERT INTO mizan_schema") && it.contains("WHERE NOT EXISTS") },
        )
    }

    @Test
    fun theSchemaStatementsAreParameterisedOnlyWhenTheTableIsConfigurable() {
        val statements = PostgresRecordLog.schemaStatements("mizan_records_eu")
        assertTrue(statements[0].contains("mizan_records_eu"))
        assertTrue(statements[1].contains("mizan_records_eu_stream_seq_idx"))
        assertTrue(statements[1].contains("ON mizan_records_eu (stream, seq)"))
        // The version insert names no table that came from configuration.
        assertTrue(statements[3].startsWith("INSERT INTO mizan_schema"))
    }

    @Test
    fun aTableNameThatIsNotAnIdentifierIsRefused() {
        // The table name is the one part of the SQL that cannot be a
        // parameter, so it is validated rather than interpolated.
        assertThrows(IllegalArgumentException::class.java) {
            PostgresRecordLog.schemaStatements("records; DROP TABLE journals")
        }
        assertThrows(IllegalArgumentException::class.java) { PostgresRecordLog.schemaStatements("Records") }
    }

    // ----------------------------------------------------------------- the log

    @Test
    fun everyStatementNamesOnlyColumnsTheSchemaHas() {
        val columns = PostgresRecordLog.schemaColumns()
        assertTrue("the schema defines the stream", "stream" in columns)
        assertTrue("the schema defines the payload", "payload" in columns)
        assertTrue("the schema defines the position", "seq" in columns)

        val insert = PostgresRecordLog.SQL_INSERT.substringAfter('(').substringBefore(')')
            .split(',').map { it.trim().lowercase() }.toSet()
        val selected = PostgresRecordLog.SQL_SELECT
            .substringAfter("SELECT ").substringBefore(" FROM")
            .split(',').map { it.trim().lowercase() }.toSet()
        assertEquals("every inserted column exists", emptySet<String>(), insert - columns)
        assertEquals("every selected column exists", emptySet<String>(), selected - columns)
        assertTrue("the read is ordered by the position", PostgresRecordLog.SQL_SELECT.contains("ORDER BY seq"))
    }

    @Test
    fun aRecordIsInsertedAndComesBackInOrder() {
        val database = FakePostgres()
        val log = sqlLog(database)
        log.append("""{"executionId":"EXE-1"}""")
        log.append("""{"executionId":"EXE-2"}""")
        log.append("""{"executionId":"EXE-3"}""")

        assertEquals(
            listOf("""{"executionId":"EXE-1"}""", """{"executionId":"EXE-2"}""", """{"executionId":"EXE-3"}"""),
            log.records(),
        )
        assertEquals(3, database.streamCount("execution-journal"))
        assertTrue(
            "the append is one insert",
            database.statements.count { it.startsWith("INSERT INTO mizan_records") } == 3,
        )
    }

    @Test
    fun aSecondStreamDoesNotSeeTheFirst() {
        val database = FakePostgres()
        PostgresRecordLog(database, "sessions").append("""{"token":"one"}""")
        PostgresRecordLog(database, "receipts").append("""{"receiptId":"RCT-1"}""")
        assertEquals(1, PostgresRecordLog(database, "sessions").records().size)
        assertEquals(listOf("""{"receiptId":"RCT-1"}"""), PostgresRecordLog(database, "receipts").records())
    }

    @Test
    fun aCompactionReplacesTheStreamInOneTransaction() {
        val database = FakePostgres()
        val log = sqlLog(database)
        log.append("""{"revision":1}""")
        log.append("""{"revision":2}""")
        log.append("""{"revision":3}""")
        val commitsBefore = database.commits

        log.compact(listOf("""{"revision":3}"""))
        // Measured before the read below, because a read is a transaction too.
        assertEquals("the compaction is one transaction", commitsBefore + 1, database.commits)

        assertEquals(listOf("""{"revision":3}"""), log.records())
        assertEquals("the stream is not duplicated", 1, database.streamCount("execution-journal"))
        assertEquals(0, database.rollbacks)
    }

    @Test
    fun aCompactionThatFailsLeavesTheStreamAsItWas() {
        val database = FakePostgres()
        val log = sqlLog(database)
        log.append("""{"revision":1}""")
        log.append("""{"revision":2}""")

        database.failNextInsert = true
        val failed = runCatching { log.compact(listOf("""{"revision":2}""")) }
        assertTrue("the failure is reported, not swallowed", failed.isFailure)
        assertEquals("the old log is still there", listOf("""{"revision":1}""", """{"revision":2}"""), log.records())
        assertEquals(1, database.rollbacks)
    }

    @Test
    fun compactionKeepsOtherStreamsUntouched() {
        val database = FakePostgres()
        PostgresRecordLog(database, "execution-journal").append("""{"executionId":"EXE-1"}""")
        PostgresRecordLog(database, "outbox").append("""{"id":"OBX-1"}""")
        PostgresRecordLog(database, "execution-journal").compact(listOf("""{"executionId":"EXE-2"}"""))
        assertEquals(listOf("""{"id":"OBX-1"}"""), PostgresRecordLog(database, "outbox").records())
    }

    // ------------------------------------------------------- the service on SQL

    @Test
    fun theWholeServiceCanRunOnSqlAndAnotherProcessCanSeeItsWork() {
        val database = FakePostgres()
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        val request = ExecutionRequest(
            executionId = "EXE-SQL-1",
            traceId = "TRC-SQL-1",
            tenantId = "sim-alamal",
            toolWire = "stock.availability",
            toolVersion = "1.0.0",
            proposalId = null,
            arguments = Json.parseOrNull("""{"sku":"SKU-DESK-01"}""") as JsonValue.Obj,
            approverId = null,
            idempotencyKey = "sql-key-1",
        )

        val first = MizanService(
            ServiceConfig(
                logProvider = SqlLogProvider(database),
                signingSecret = "sql-key",
                versionedPolicy = VersionedPolicy.demoV12,
            ),
        )
        try {
            val outcome = first.authority.decide(request, user, false)
            assertTrue("the read is answered through the SQL log, was $outcome", outcome is ExecutionOutcome.Accepted)
        } finally {
            first.stop()
        }

        // A second process, a second service, the same database: this is what
        // a PostgreSQL deployment buys and what the file log cannot give.
        val second = MizanService(
            ServiceConfig(
                logProvider = SqlLogProvider(database),
                signingSecret = "sql-key",
                versionedPolicy = VersionedPolicy.demoV12,
            ),
        )
        try {
            val journal = second.stores!!.journals.get("EXE-SQL-1")
            assertNotNull("the other process cannot see the journal", journal)
            assertEquals(JournalStage.ACCEPTED, journal!!.stage)
            assertEquals("stock.quant", journal.erpModel)
            assertEquals("SKU-DESK-01", journal.erpRecordId)

            // The key replays the same answer without asking the ERP again.
            val replayed = second.authority.decide(request.copy(executionId = "EXE-SQL-2"), user, false)
            assertTrue("a durable key must replay on the other process, was $replayed", replayed is ExecutionOutcome.Accepted)
            assertEquals(0, second.erp.snapshot("sim-alamal").orders.size)
        } finally {
            second.stop()
        }
    }

    @Test
    fun aSqlDeploymentStillRefusesToKeepTwoCopiesOfItsState() {
        val directory = java.nio.file.Files.createTempDirectory("mizan-sql-conflict")
        directory.toFile().deleteOnExit()
        assertThrows(IllegalArgumentException::class.java) {
            MizanService(
                ServiceConfig(
                    storeDirectory = directory,
                    database = DatabaseConfig(url = "jdbc:postgresql://localhost/mizan"),
                ),
            )
        }
    }

    @Test
    fun theFileBackedDeploymentStoresTheSameStreamsTheSqlOneWould() {
        // Both deployments open the same stream names, so a migration from a
        // directory to a database is a read of one and a write of the other,
        // not a translation between two shapes of state.
        val directory = java.nio.file.Files.createTempDirectory("mizan-sql-streams")
        directory.toFile().deleteOnExit()
        val stores = ServiceStores(directory)
        try {
            val names = directory.toFile().listFiles()?.map { it.name }?.sorted().orEmpty()
            assertEquals(
                listOf(
                    "approvals.log",
                    "audit.log",
                    "challenges.log",
                    "devices.log",
                    "execution-journal.log",
                    "idempotency.log",
                    "outbox.log",
                    "receipts.log",
                    "reconciliation.log",
                    "sessions.log",
                ),
                names,
            )
        } finally {
            stores.close()
        }
    }
}
