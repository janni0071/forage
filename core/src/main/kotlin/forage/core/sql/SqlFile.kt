package forage.core.sql

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * The little of SQLite the catalogue needs, so it runs on the desktop's JDBC driver and on
 * Android's own SQLite alike. Not thread-safe; the catalogue serializes its use.
 */
interface SqlFile : AutoCloseable {
    /** Runs a statement that returns nothing (PRAGMAs included). */
    fun execute(sql: String)

    /** Runs a query; [each] sees every row. Arguments may be Int, Long, String or null. */
    fun query(sql: String, args: List<Any?> = emptyList(), each: (Row) -> Unit)

    /** Runs [block] in one transaction, committed when it returns. */
    fun <T> transaction(block: () -> T): T

    /** A statement to run many times with different arguments, such as bulk inserts. */
    fun prepare(sql: String): Statement

    interface Row {
        fun int(column: Int): Int
        fun long(column: Int): Long
        fun string(column: Int): String?
        fun bytes(column: Int): ByteArray?
    }

    interface Statement : AutoCloseable {
        /** Runs the statement with [args]: Int, Long, Double, String, ByteArray or null. */
        fun run(vararg args: Any?)
    }

    /** Opens a SQLite file on this platform. */
    fun interface Opener {
        fun open(file: File, writable: Boolean): SqlFile
    }
}



/** SQLite through JDBC (sqlite-jdbc), on the desktop. */
class JdbcSqlFile private constructor(private val connection: Connection) : SqlFile {
    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun query(sql: String, args: List<Any?>, each: (SqlFile.Row) -> Unit) {
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            st.executeQuery().use { rs ->
                val row = object : SqlFile.Row {
                    override fun int(column: Int) = rs.getInt(column + 1)
                    override fun long(column: Int) = rs.getLong(column + 1)
                    override fun string(column: Int): String? = rs.getString(column + 1)
                    override fun bytes(column: Int): ByteArray? = rs.getBytes(column + 1)
                }
                while (rs.next()) each(row)
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun prepare(sql: String): SqlFile.Statement {
        val st = connection.prepareStatement(sql)
        return object : SqlFile.Statement {
            private var pending = 0

            override fun run(vararg args: Any?) {
                args.forEachIndexed { i, a -> bind(st, i + 1, a) }
                st.addBatch()
                if (++pending == BATCH) flush()
            }

            private fun flush() {
                if (pending > 0) st.executeBatch()
                pending = 0
            }

            override fun close() {
                flush()
                st.close()
            }
        }
    }

    override fun close() = connection.close()

    private fun bind(st: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> st.setObject(index, null)
            is Int -> st.setInt(index, value)
            is Long -> st.setLong(index, value)
            is Double -> st.setDouble(index, value)
            is String -> st.setString(index, value)
            is ByteArray -> st.setBytes(index, value)
            else -> error("Can't bind ${value::class.simpleName}")
        }
    }

    companion object : SqlFile.Opener {
        private const val BATCH = 10_000

        override fun open(file: File, writable: Boolean): SqlFile {
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
            if (!writable) connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
            return JdbcSqlFile(connection)
        }
    }
}
