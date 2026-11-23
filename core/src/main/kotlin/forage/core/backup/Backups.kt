package forage.core.backup

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import forage.core.db.ForageDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A whole library in one file, to keep somewhere safe and to restore on any device: every table's
 * rows, with the schema they were in, as gzipped JSON; for the private vault, encrypted with a
 * PIN as well. Not a copy of the database file: the desktop and the phone keep their vaults in
 * different files (sealed in memory, SQLCipher), and a backup restores on either. A backup from an
 * older Forage is brought up to date on the way in, with the same migrations as the database.
 */
object Backups {
    /** What a backup holds besides its rows: which format, which schema version, when, from where. */
    data class About(val schemaVersion: Long, val createdAt: Instant, val device: String?, val private: Boolean)

    /** A backup, read: what it is, and its schema and rows. */
    class Contents internal constructor(val about: About, internal val schema: List<String>, internal val tables: Map<String, Table>) {
        /** Rows per table, for showing what's in it. */
        fun rowCount(table: String): Int = tables[table]?.rows?.size ?: 0

        /** Rows of [table] whose [column] is [value] ("liked" in profile_video's state, say). */
        fun rowCount(table: String, column: String, value: String): Int {
            val t = tables[table] ?: return 0
            val at = t.columns.indexOf(column).takeIf { it >= 0 } ?: return 0
            return t.rows.count { (it[at] as? JsonPrimitive)?.content == value }
        }
    }

    internal class Table(val columns: List<String>, val rows: List<List<JsonElement>>)

    /** Writes the library behind [driver] to [out]: gzipped JSON, sealed with [pin] where there is one. */
    fun write(driver: SqlDriver, out: OutputStream, device: String?, pin: CharArray? = null) {
        val json = buildJsonObject {
            put("format", FORMAT)
            put("app", "Forage")
            put("schemaVersion", longQuery(driver, "PRAGMA user_version"))
            put("createdAt", Instant.now().toString())
            device?.let { put("device", it) }
            put("private", pin != null)
            put("schema", JsonArray(schema(driver).map(::JsonPrimitive)))
            put("tables", buildJsonObject { for (name in tableNames(driver)) put(name, dump(driver, name)) })
        }
        val packed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(json.toString().toByteArray()) } }.toByteArray()
        out.write(if (pin != null) seal(packed, pin) else packed)
    }

    /** True when [head] (a file's first bytes) starts a backup that needs its PIN. */
    fun isSealed(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** True when [head] starts a backup at all (sealed, or gzipped JSON). */
    fun isBackup(head: ByteArray): Boolean = isSealed(head) || (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte())

    /**
     * Reads a backup. Throws [WrongPinException] for a sealed one opened with the wrong PIN (or
     * none), [NotABackupException] for anything else.
     */
    fun read(input: InputStream, pin: CharArray? = null): Contents {
        val bytes = input.readBytes()
        val packed = when {
            isSealed(bytes) -> open(bytes, pin ?: throw WrongPinException())
            isBackup(bytes) -> bytes
            else -> throw NotABackupException()
        }
        val json = try {
            Json.parseToJsonElement(GZIPInputStream(ByteArrayInputStream(packed)).readBytes().decodeToString()).jsonObject
        } catch (e: Exception) {
            throw NotABackupException(e)
        }
        if (json["app"]?.jsonPrimitive?.content != "Forage") throw NotABackupException()
        val format = json["format"]?.jsonPrimitive?.long ?: throw NotABackupException()
        if (format > FORMAT) throw TooNewException("This backup is from a newer Forage; update Forage to restore it")
        val about = About(
            schemaVersion = json["schemaVersion"]!!.jsonPrimitive.long,
            createdAt = Instant.parse(json["createdAt"]!!.jsonPrimitive.content),
            device = json["device"]?.jsonPrimitive?.content,
            private = json["private"]?.jsonPrimitive?.content == "true",
        )
        val schema = json["schema"]!!.jsonArray.map { it.jsonPrimitive.content }
        val tables = json["tables"]!!.jsonObject.mapValues { (_, table) ->
            val t = table.jsonObject
            Table(t["columns"]!!.jsonArray.map { it.jsonPrimitive.content }, t["rows"]!!.jsonArray.map { it.jsonArray.toList() })
        }
        return Contents(about, schema, tables)
    }

    /**
     * Replaces everything in the library behind [live] with [contents]. The backup is first put
     * together in [scratch] (an empty in-memory database) in its own schema and brought up to the
     * current one; then every table of [live] is emptied and filled in one transaction, so a
     * failure leaves the library as it was.
     */
    fun restore(contents: Contents, live: SqlDriver, transacter: Transacter, scratch: SqlDriver) {
        val current = ForageDatabase.Schema.version
        if (contents.about.schemaVersion > current) throw TooNewException("This backup is from a newer Forage; update Forage to restore it")
        try {
            for (statement in contents.schema) scratch.execute(null, statement, 0)
            val referenced = contents.schema.flatMap { REFERENCES.findAll(it).map { m -> m.groupValues[1] } }.toSet()
            for ((name, table) in contents.tables.entries.sortedBy { it.key !in referenced }) load(scratch, name, table)
            if (contents.about.schemaVersion < current) ForageDatabase.Schema.migrate(scratch, contents.about.schemaVersion, current)
            val upToDate = tableNames(scratch).associateWith { dump(scratch, it).let(::table) }
            transacter.transaction {
                for (name in tableNames(live)) {
                    live.execute(null, "DELETE FROM \"$name\"", 0)
                    upToDate[name]?.let { load(live, name, it) }
                }
            }
        } finally {
            scratch.close()
        }
    }

    // The database, read and written without knowing its tables: names and columns from SQLite itself.

    /** The tables, those others refer to (`REFERENCES profile`) first, so their rows are there when the others' arrive. */
    private fun tableNames(driver: SqlDriver): List<String> {
        val names = strings(driver, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        val referenced = schema(driver).flatMap { REFERENCES.findAll(it).map { m -> m.groupValues[1] } }.toSet()
        return names.sortedBy { it !in referenced }
    }

    /** The statements that make the schema: tables first, then indexes. */
    private fun schema(driver: SqlDriver): List<String> = strings(
        driver,
        "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
            "ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name",
    )

    private fun columns(driver: SqlDriver, table: String): List<String> = strings(driver, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")

    /** A table's rows, each value with its type: text and numbers as themselves, blobs as `{"blob": base64}`. */
    private fun dump(driver: SqlDriver, table: String): JsonObject {
        val columns = columns(driver, table)
        val select = columns.joinToString(", ") { "typeof(\"$it\"), \"$it\"" }
        val rows = driver.executeQuery(null, "SELECT $select FROM \"$table\"", { cursor ->
            val rows = ArrayList<JsonElement>()
            while (cursor.next().value) rows.add(buildJsonArray { for (i in columns.indices) add(value(cursor, i * 2)) })
            QueryResult.Value(rows)
        }, 0).value
        return buildJsonObject {
            put("columns", JsonArray(columns.map(::JsonPrimitive)))
            put("rows", JsonArray(rows))
        }
    }

    private fun table(json: JsonObject) = Table(
        json["columns"]!!.jsonArray.map { it.jsonPrimitive.content },
        json["rows"]!!.jsonArray.map { it.jsonArray.toList() },
    )

    private fun value(cursor: SqlCursor, typeAt: Int): JsonElement = when (cursor.getString(typeAt)) {
        "integer" -> JsonPrimitive(cursor.getLong(typeAt + 1))
        "real" -> JsonPrimitive(cursor.getDouble(typeAt + 1))
        "text" -> JsonPrimitive(cursor.getString(typeAt + 1))
        "blob" -> buildJsonObject { put("blob", Base64.getEncoder().encodeToString(cursor.getBytes(typeAt + 1))) }
        else -> JsonNull
    }

    /** Puts [table]'s rows into [name], for the columns both have (a column the backup lacks keeps its default). */
    private fun load(driver: SqlDriver, name: String, table: Table) {
        val present = columns(driver, name).toSet()
        val keep = table.columns.indices.filter { table.columns[it] in present }
        if (keep.isEmpty()) return
        val sql = "INSERT INTO \"$name\" (${keep.joinToString(", ") { "\"${table.columns[it]}\"" }}) VALUES (${keep.joinToString(", ") { "?" }})"
        for (row in table.rows) {
            driver.execute(null, sql, keep.size) {
                keep.forEachIndexed { at, column ->
                    when (val v = row[column]) {
                        is JsonNull -> bindString(at, null)
                        is JsonObject -> bindBytes(at, Base64.getDecoder().decode(v["blob"]!!.jsonPrimitive.content))
                        is JsonPrimitive -> when {
                            v.isString -> bindString(at, v.content)
                            v.content.any { it == '.' || it == 'e' || it == 'E' } -> bindDouble(at, v.content.toDouble())
                            else -> bindLong(at, v.content.toLong())
                        }
                        else -> bindString(at, null)
                    }
                }
            }
        }
    }

    private fun strings(driver: SqlDriver, sql: String): List<String> = driver.executeQuery(null, sql, { cursor ->
        val result = ArrayList<String>()
        while (cursor.next().value) cursor.getString(0)?.let(result::add)
        QueryResult.Value(result)
    }, 0).value

    private fun longQuery(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    // Sealing: AES-GCM with a key from the PIN (PBKDF2, as the vaults), the salt and nonce in the file.

    private fun seal(packed: ByteArray, pin: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(TAG_BITS, nonce)) }
        cipher.updateAAD(MAGIC)
        val sealed = cipher.doFinal(packed)
        return ByteBuffer.allocate(MAGIC.size + SALT_BYTES + NONCE_BYTES + sealed.size).put(MAGIC).put(salt).put(nonce).put(sealed).array()
    }

    private fun open(bytes: ByteArray, pin: CharArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size, bytes.size - MAGIC.size)
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(pin, salt), GCMParameterSpec(TAG_BITS, nonce)) }
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw WrongPinException()
        }
    }

    private fun key(pin: CharArray, salt: ByteArray): SecretKeySpec {
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin, salt, ITERATIONS, 256)).encoded
        return SecretKeySpec(bits, "AES")
    }

    private val REFERENCES = Regex("""REFERENCES\s+"?(\w+)"?""", RegexOption.IGNORE_CASE)
    private const val FORMAT = 1
    private val MAGIC = "FORAGEBK1".toByteArray()
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 600_000
}

class NotABackupException(cause: Throwable? = null) : Exception("That file isn't a Forage backup", cause)
class WrongPinException : Exception("That PIN or passphrase doesn't open this backup")
class TooNewException(message: String) : Exception(message)
