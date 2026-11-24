package forage.core.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * SQLite values as JSON and back, for backups and sync: text and numbers as themselves, blobs as
 * `{"blob": base64}`, NULL as null. Read with `typeof(column), column` pairs, so each value keeps
 * its type.
 */
internal object SqlJson {
    fun value(cursor: SqlCursor, typeAt: Int): JsonElement = when (cursor.getString(typeAt)) {
        "integer" -> JsonPrimitive(cursor.getLong(typeAt + 1))
        "real" -> JsonPrimitive(cursor.getDouble(typeAt + 1))
        "text" -> JsonPrimitive(cursor.getString(typeAt + 1))
        "blob" -> buildJsonObject { put("blob", Base64.getEncoder().encodeToString(cursor.getBytes(typeAt + 1))) }
        else -> JsonNull
    }

    fun bind(statement: SqlPreparedStatement, at: Int, value: JsonElement?) = with(statement) {
        when (value) {
            null, is JsonNull -> bindString(at, null)
            is JsonObject -> bindBytes(at, Base64.getDecoder().decode(value["blob"]!!.jsonPrimitive.content))
            is JsonPrimitive -> when {
                value.isString -> bindString(at, value.content)
                value.content.any { it == '.' || it == 'e' || it == 'E' } -> bindDouble(at, value.content.toDouble())
                else -> bindLong(at, value.content.toLong())
            }
            else -> bindString(at, null)
        }
    }

    /** `typeof("a"), "a", typeof("b"), "b"`, for reading [columns] with [value]. */
    fun select(columns: List<String>, prefix: String = ""): String = columns.joinToString(", ") { "typeof($prefix\"$it\"), $prefix\"$it\"" }

    fun strings(driver: SqlDriver, sql: String, vararg args: String): List<String> = driver.executeQuery(null, sql, { cursor ->
        val result = ArrayList<String>()
        while (cursor.next().value) cursor.getString(0)?.let(result::add)
        QueryResult.Value(result)
    }, args.size) { args.forEachIndexed { i, a -> bindString(i, a) } }.value

    fun columns(driver: SqlDriver, table: String): List<String> = strings(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")
}
