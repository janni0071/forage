package forage.source.pornhub

import forage.core.sql.SqlFile

/** A blob read in pieces: Android's SQLite can't return a row of more than about 2 MB at once. */
internal fun SqlFile.blob(table: String, column: String, where: String, arg: Any?): ByteArray? {
    var length = -1L
    query("SELECT length($column) FROM $table WHERE $where", listOf(arg)) { length = if (it.string(0) == null) -1 else it.long(0) }
    if (length < 0) return null
    val out = java.io.ByteArrayOutputStream(length.toInt())
    var offset = 0L
    while (offset < length) {
        val size = minOf(BLOB_CHUNK, length - offset)
        query("SELECT substr($column, ${offset + 1}, $size) FROM $table WHERE $where", listOf(arg)) { row -> row.bytes(0)?.let(out::write) }
        offset += size
    }
    return out.toByteArray()
}

private const val BLOB_CHUNK = 1L shl 20
