package app.mizan.service.store.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The database boundary, kept as small as it can honestly be.
 *
 * The service needs three things from a database: run a statement, read rows,
 * and wrap several statements in one atomic unit. Everything above this file
 * is written against that, which is why the PostgreSQL adapter can be
 * exercised by a fake in tests and why no store in the service imports
 * `java.sql` itself.
 */
interface SqlSession {

    /** Runs a statement and returns the number of affected rows. */
    fun update(sql: String, parameters: List<Any?> = emptyList()): Int

    /** Runs a query and returns its rows, each as a column-name to value map. */
    fun select(sql: String, parameters: List<Any?> = emptyList()): List<Map<String, Any?>>
}

interface SqlDatabase {

    /**
     * Runs [block] in one transaction: it commits when the block returns and
     * rolls back when it throws. A caller never sees a half-applied unit.
     */
    fun <T> transaction(block: (SqlSession) -> T): T
}

/**
 * The JDBC implementation.
 *
 * The driver is loaded by name and its absence is not an error here: this
 * class must compile and be inspected on a machine with no PostgreSQL driver
 * on the classpath, and the failure belongs at connect time with a clear
 * message rather than at class-loading time.
 */
class JdbcDatabase(
    private val url: String,
    private val user: String? = null,
    private val password: String? = null,
    private val driverClass: String? = "org.postgresql.Driver",
) : SqlDatabase {

    init {
        if (driverClass != null) {
            runCatching { Class.forName(driverClass) }
        }
    }

    override fun <T> transaction(block: (SqlSession) -> T): T {
        val connection = connect()
        connection.use {
            connection.autoCommit = false
            try {
                val result = block(JdbcSession(connection))
                connection.commit()
                return result
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    private fun connect(): Connection {
        val connection = if (user == null) {
            DriverManager.getConnection(url)
        } else {
            DriverManager.getConnection(url, user, password)
        }
        return connection
    }
}

private class JdbcSession(private val connection: Connection) : SqlSession {

    override fun update(sql: String, parameters: List<Any?>): Int =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, parameters)
            statement.executeUpdate()
        }

    override fun select(sql: String, parameters: List<Any?>): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, parameters)
            statement.executeQuery().use { rows -> read(rows) }
        }

    private fun read(rows: ResultSet): List<Map<String, Any?>> {
        val columns = rows.metaData.columnCount
        val names = (1..columns).map { rows.metaData.getColumnLabel(it).lowercase() }
        val result = ArrayList<Map<String, Any?>>()
        while (rows.next()) {
            val row = LinkedHashMap<String, Any?>()
            for (index in 1..columns) {
                row[names[index - 1]] = rows.getObject(index)
            }
            result += row
        }
        return result
    }

    private fun bind(statement: PreparedStatement, parameters: List<Any?>) {
        parameters.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setNull(position, Types.VARCHAR)
                is String -> statement.setString(position, value)
                is Long -> statement.setLong(position, value)
                is Int -> statement.setInt(position, value)
                is Boolean -> statement.setBoolean(position, value)
                else -> statement.setObject(position, value)
            }
        }
    }
}
