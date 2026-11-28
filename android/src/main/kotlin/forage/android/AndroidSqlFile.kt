package forage.android

import android.database.sqlite.SQLiteDatabase
import forage.core.sql.SqlFile
import java.io.File

/** SQLite through Android's own database classes, for the catalogue (an ordinary, unencrypted file). */
class AndroidSqlFile private constructor(private val db: SQLiteDatabase) : SqlFile {
    override fun execute(sql: String) {
        // PRAGMAs answer with a row, which execSQL refuses.
        if (sql.trimStart().startsWith("PRAGMA", ignoreCase = true)) db.rawQuery(sql, null).use { it.moveToFirst() }
        else db.execSQL(sql)
    }

    override fun query(sql: String, args: List<Any?>, each: (SqlFile.Row) -> Unit) {
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val row = object : SqlFile.Row {
                override fun int(column: Int) = cursor.getInt(column)
                override fun long(column: Int) = cursor.getLong(column)
                override fun string(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
                override fun bytes(column: Int): ByteArray? = if (cursor.isNull(column)) null else cursor.getBlob(column)
            }
            while (cursor.moveToNext()) each(row)
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun prepare(sql: String): SqlFile.Statement {
        val statement = db.compileStatement(sql)
        return object : SqlFile.Statement {
            override fun run(vararg args: Any?) {
                statement.clearBindings()
                args.forEachIndexed { i, value ->
                    val index = i + 1
                    when (value) {
                        null -> statement.bindNull(index)
                        is Int -> statement.bindLong(index, value.toLong())
                        is Long -> statement.bindLong(index, value)
                        is Double -> statement.bindDouble(index, value)
                        is String -> statement.bindString(index, value)
                        is ByteArray -> statement.bindBlob(index, value)
                        else -> error("Can't bind ${value::class.simpleName}")
                    }
                }
                statement.execute()
            }

            override fun close() = statement.close()
        }
    }

    override fun close() = db.close()

    companion object : SqlFile.Opener {
        override fun open(file: File, writable: Boolean): SqlFile = AndroidSqlFile(
            SQLiteDatabase.openDatabase(
                file.path, null,
                if (writable) SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READONLY,
            ),
        )
    }
}
