package forage.source.pornhub

import forage.core.PlatformId
import forage.core.source.CatalogueMatch
import forage.core.source.VideoCatalogue
import forage.core.source.VideoStub
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.util.PriorityQueue
import java.util.zip.ZipInputStream
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The site's embed dump as a local catalogue (SPEC, "Sensitive platforms"): about five million
 * videos with titles, tags, categories, performers and thumbnails, which the site publishes for
 * other sites to import.
 *
 * The index is a SQLite file holding only this public catalogue, nothing about the user. Besides
 * the video rows, each tag keeps the list of videos carrying it (delta-encoded), so matching a
 * profile's tags reads a few dozen lists instead of every video.
 */
class PornhubCatalogue private constructor(private val connection: Connection) : VideoCatalogue, AutoCloseable {
    override val platform = PornhubSource.PLATFORM
    override val size: Int
    /** When the dump was imported, as epoch seconds. */
    val importedAt: Long
    /** False for an index an older Forage built, whose layout or reading this version doesn't trust: import again. */
    val current: Boolean
    /** Tags per video and a 0–255 quality from views and rating, indexed by row id. */
    private val tagCounts: ByteArray
    private val quality: ByteArray

    init {
        fun meta(key: String) = connection.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { if (it.next()) it.getBytes(1) else null }
        }
        size = meta("count")?.decodeToString()?.toInt() ?: 0
        importedAt = meta("imported_at")?.decodeToString()?.toLong() ?: 0
        current = meta("version")?.decodeToString() == SCHEMA_VERSION
        tagCounts = meta("tag_counts") ?: ByteArray(size + 1)
        quality = meta("quality") ?: ByteArray(size + 1)
    }

    override fun matching(tagCounts: Map<String, Double>, limit: Int, exclude: Set<PlatformId>): List<CatalogueMatch> = synchronized(connection) {
        if (size == 0 || limit <= 0) return emptyList()
        // Weigh each profile tag by how rare it is in the catalogue; keep the most telling.
        val tags = tagCounts.entries.mapNotNull { (raw, count) ->
            val tag = tag(normalize(raw)) ?: return@mapNotNull null
            if (tag.df == 0) null else Triple(tag, count * ln(size.toDouble() / tag.df), raw)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX_QUERY_TAGS)
        if (tags.isEmpty()) return emptyList()

        val scores = FloatArray(size + 1)
        val touched = IntArray(size + 1)
        var touchedCount = 0
        for ((tag, weight) in tags) {
            decodePostings(postings(tag.id)) { row ->
                if (scores[row] == 0f) touched[touchedCount++] = row
                scores[row] += weight.toFloat()
            }
        }
        val skip = exclude.filter { it.platform == platform }.mapNotNullTo(HashSet()) { rowId(it.id) }

        // Videos with many tags would otherwise match everything a little; a good video edges out a poor one.
        fun final(row: Int): Float {
            val n = (this.tagCounts[row].toInt() and 0xff).coerceAtLeast(MIN_TAGS_FOR_NORMALIZING)
            val q = (quality[row].toInt() and 0xff) / 255f
            return scores[row] / sqrt(n.toFloat()) * (1 - QUALITY_SHARE + QUALITY_SHARE * q)
        }
        val best = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        for (i in 0 until touchedCount) {
            val row = touched[i]
            if (row in skip) continue
            val score = final(row)
            if (best.size < limit) best.add(row to score)
            else if (score > best.peek().second) { best.poll(); best.add(row to score) }
        }
        val ranked = generateSequence { best.poll() }.toList().reversed()
        val rows = rows(ranked.map { it.first })
        val names = tagNames(rows.values.flatMap { it.tagIds }.toSet())
        val queryIds = tags.associate { it.first.id to it.first.name }
        return ranked.mapNotNull { (row, _) ->
            val r = rows[row] ?: return@mapNotNull null
            CatalogueMatch(
                VideoStub(
                    id = PlatformId(platform, r.key),
                    title = r.title,
                    channel = null,
                    channelName = r.performers.take(2).joinToString(", ").ifEmpty { null },
                    durationSeconds = r.duration,
                    isShort = false,
                    // The dump has no upload date; the thumbnail's address holds the day the video was processed.
                    uploadedAt = r.thumb?.let(PornhubSource::uploadDateFromImage),
                    tags = r.tagIds.mapNotNull { names[it] },
                ),
                sharedTags = tags.map { it.first.id }.filter { it in r.tagIds }.mapNotNull { queryIds[it] },
            )
        }
    }

    override fun thumbnailUrl(id: PlatformId): String? = synchronized(connection) {
        if (id.platform != platform) return null
        connection.prepareStatement("SELECT thumb FROM video WHERE key = ?").use { st ->
            st.setString(1, id.id)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    override fun close() = synchronized(connection) { connection.close() }

    private data class Tag(val id: Int, val name: String, val df: Int)

    private class Row(
        val key: String, val title: String, val duration: Long?, val performers: List<String>, val tagIds: Set<Int>, val thumb: String?,
    )

    private fun tag(name: String): Tag? = connection.prepareStatement("SELECT id, df FROM tag WHERE name = ?").use { st ->
        st.setString(1, name)
        st.executeQuery().use { if (it.next()) Tag(it.getInt(1), name, it.getInt(2)) else null }
    }

    private fun postings(tagId: Int): ByteArray = connection.prepareStatement("SELECT postings FROM tag WHERE id = ?").use { st ->
        st.setInt(1, tagId)
        st.executeQuery().use { if (it.next()) it.getBytes(1) ?: ByteArray(0) else ByteArray(0) }
    }

    private fun rowId(key: String): Int? = connection.prepareStatement("SELECT id FROM video WHERE key = ?").use { st ->
        st.setString(1, key)
        st.executeQuery().use { if (it.next()) it.getInt(1) else null }
    }

    private fun rows(ids: List<Int>): Map<Int, Row> {
        if (ids.isEmpty()) return emptyMap()
        val sql = "SELECT id, key, title, duration, performers, tags, thumb FROM video WHERE id IN (${ids.joinToString(",")})"
        return connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildMap {
                    while (rs.next()) {
                        val tagIds = HashSet<Int>()
                        decodeList(rs.getBytes(6) ?: ByteArray(0)) { tagIds += it }
                        put(
                            rs.getInt(1),
                            Row(
                                rs.getString(2), rs.getString(3), rs.getLong(4).takeIf { it > 0 },
                                rs.getString(5).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }, tagIds,
                                rs.getString(7),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun tagNames(ids: Set<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        return connection.createStatement().use { st ->
            st.executeQuery("SELECT id, display FROM tag WHERE id IN (${ids.joinToString(",")})").use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getString(2)) }
            }
        }
    }

    /** What an import did. */
    data class ImportSummary(
        val videos: Int,
        val skippedUnreadable: Int,
        val skippedFewViews: Int,
        val tags: Int,
        /** How most lines were laid out, without content, e.g. "13 fields, counts in fields 8–11". */
        val layout: String = "",
    )

    /** The file isn't laid out the way Forage expects; [layout] describes it without its content. */
    class LayoutException(message: String, val layout: String) : Exception(message)

    companion object {
        /** Tags on fewer videos than this can't find anything worth suggesting and aren't kept. */
        private const val MIN_TAG_VIDEOS = 3
        private const val MAX_QUERY_TAGS = 25
        private const val MIN_TAGS_FOR_NORMALIZING = 4
        private const val QUALITY_SHARE = 0.2f
        /** Videos seen by fewer people than this are left out: mostly uploads nobody found worth watching. */
        const val MIN_VIEWS = 1000L
        /** 2: counts found by content (version 1 misread lines with an extra field). */
        private const val SCHEMA_VERSION = "2"
        private val EMBED_KEY = Regex("""/embed/([A-Za-z0-9]+)""")

        fun open(file: File): PornhubCatalogue {
            require(file.isFile) { "No catalogue at ${file.path}" }
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
            connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
            return PornhubCatalogue(connection)
        }

        /** Same spelling for the catalogue's tags and the profile's: lower case, hyphens and underscores as spaces. */
        fun normalize(tag: String): String =
            tag.lowercase().replace('-', ' ').replace('_', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * Imports the dump [zip] (as downloaded, no need to unpack it) into the index file [target].
         * Writes to a temporary file first, so a stopped or failed import leaves any earlier
         * catalogue in place. [progress] gets the share of the zip read so far; [cancelled] is
         * checked as it goes.
         */
        fun import(
            zip: File,
            target: File,
            progress: (Double) -> Unit = {},
            cancelled: () -> Boolean = { false },
            now: () -> Long = { System.currentTimeMillis() / 1000 },
            /** Called just before the new file replaces the old one, to close anything reading it. */
            beforeReplace: () -> Unit = {},
        ): ImportSummary {
            target.absoluteFile.parentFile.mkdirs()
            val temp = File(target.absoluteFile.parentFile, "${target.name}.importing")
            temp.delete()
            val total = zip.length().coerceAtLeast(1)
            var read = 0L
            val counting = object : FilterInputStream(zip.inputStream().buffered(1 shl 20)) {
                override fun read(): Int = super.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            }
            val summary = try {
                ZipInputStream(counting).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null && (entry.isDirectory || !entry.name.endsWith(".csv", ignoreCase = true))) entry = zin.nextEntry
                    entry ?: throw LayoutException("The zip holds no .csv file", "no .csv entry")
                    DriverManager.getConnection("jdbc:sqlite:${temp.path}").use { db ->
                        write(BufferedReader(InputStreamReader(zin, Charsets.UTF_8), 1 shl 20), db, now()) { rows ->
                            if (rows % 20_000 == 0L) {
                                if (cancelled()) throw InterruptedException("Import stopped")
                                progress(read.toDouble() / total)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
            beforeReplace()
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Couldn't move the new catalogue into place" }
            }
            progress(1.0)
            return summary
        }

        /**
         * One dump line: embed | thumb | thumbs | title | tags | categories | performers | duration |
         * views | likes | dislikes | big thumbs, and possibly more fields after those. [numbersAt] is
         * where the duration was found, for the import report.
         */
        private class Parsed(
            val key: String, val title: String, val thumb: String?, val tags: List<String>, val performers: List<String>,
            val duration: Long, val views: Long, val likes: Long, val dislikes: Long, val fields: Int, val numbersAt: Int,
        )

        private const val FIELDS = 12

        private fun parse(line: String): Parsed? {
            val f = line.split('|')
            if (f.size < FIELDS) return null
            val key = EMBED_KEY.find(f[0])?.groupValues?.get(1) ?: return null
            // The four counts (duration, views, likes, dislikes) are the first run of four whole numbers
            // after the title, tags, categories and performers; the rest is placed around them. Positions
            // counted from either end are off whenever a title holds a '|' or the dump adds a field.
            fun number(i: Int) = f[i].trim().let { it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }
            val at = (FIRST_NUMBERS_AT..f.size - 4).firstOrNull { i -> (i until i + 4).all(::number) } ?: return null
            // A title containing '|' spreads over several fields; everything between thumbs and tags is the title.
            val title = f.subList(3, at - 3).joinToString("|").trim()
            if (title.isEmpty()) return null
            fun list(s: String) = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            return Parsed(
                key = key,
                title = title,
                thumb = f[1].trim().takeIf { it.startsWith("https://") },
                tags = list(f[at - 3]) + list(f[at - 2]),
                performers = list(f[at - 1]),
                duration = f[at].trim().toLong(),
                views = f[at + 1].trim().toLong(),
                likes = f[at + 2].trim().toLong(),
                dislikes = f[at + 3].trim().toLong(),
                fields = f.size,
                numbersAt = at,
            )
        }

        /** Where the counts can start: after embed, thumb, thumbs, title, tags, categories and performers. */
        private const val FIRST_NUMBERS_AT = 7

        /** The layout of a line with letters as x and digits as 9, for reports. */
        private fun describe(line: String): String {
            val fields = line.split('|')
            return "${fields.size} fields separated by '|': " + fields.take(14).joinToString(" | ") { field ->
                val url = Regex("(https?://[^/]+)").find(field)?.value
                (url ?: "") + " " + field.replace(Regex("[^\\W\\d_]"), "x").replace(Regex("\\d"), "9").take(30)
            }
        }

        private fun write(reader: BufferedReader, db: Connection, importedAt: Long, onRow: (Long) -> Unit): ImportSummary {
            db.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = OFF")
                st.execute("PRAGMA synchronous = OFF")
                st.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB)")
                st.execute(
                    "CREATE TABLE video (id INTEGER PRIMARY KEY, key TEXT NOT NULL, title TEXT NOT NULL, duration INTEGER, " +
                        "views INTEGER, rating REAL, thumb TEXT, performers TEXT, tags BLOB)",
                )
                st.execute("CREATE TABLE tag (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, display TEXT NOT NULL, df INTEGER NOT NULL, postings BLOB)")
            }
            db.autoCommit = false
            val tagIds = HashMap<String, Int>()
            val tagDisplay = ArrayList<String>()
            val postings = ArrayList<VarintBuffer>()
            val tagCounts = GrowableBytes()
            val quality = GrowableBytes()
            tagCounts.add(0)
            quality.add(0)
            var row = 0
            var lines = 0L
            var unreadable = 0
            var fewViews = 0
            var firstLine: String? = null
            val layouts = HashMap<Pair<Int, Int>, Int>()

            val insert = db.prepareStatement("INSERT INTO video (id, key, title, duration, views, rating, thumb, performers, tags) VALUES (?,?,?,?,?,?,?,?,?)")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                lines++
                if (firstLine == null) firstLine = line
                onRow(lines)
                val p = parse(line)
                if (p == null) {
                    unreadable++
                    // Stop early when the file isn't what this reader expects, instead of importing nothing for minutes.
                    if (lines == 2000L && unreadable > 400) {
                        throw LayoutException("The dump isn't laid out the way Forage expects", describe(firstLine))
                    }
                    continue
                }
                layouts.merge(p.fields to p.numbersAt, 1, Int::plus)
                if (p.views < MIN_VIEWS) {
                    fewViews++
                    continue
                }
                row++
                val ids = p.tags.map { t ->
                    val name = normalize(t)
                    tagIds.getOrPut(name) {
                        tagDisplay += t.lowercase()
                        postings += VarintBuffer()
                        tagDisplay.size - 1
                    }
                }.distinct()
                ids.forEach { postings[it].addDelta(row) }
                val rating = if (p.likes + p.dislikes > 0) p.likes.toDouble() / (p.likes + p.dislikes) else 0.5
                tagCounts.add(ids.size.coerceAtMost(255))
                val q = 0.6 * (log10(p.views.toDouble() + 1) / 7).coerceIn(0.0, 1.0) + 0.4 * rating
                quality.add((q * 255).toInt().coerceIn(0, 255))
                insert.setInt(1, row)
                insert.setString(2, p.key)
                insert.setString(3, p.title)
                insert.setLong(4, p.duration)
                insert.setLong(5, p.views)
                insert.setDouble(6, rating)
                insert.setString(7, p.thumb)
                insert.setString(8, p.performers.joinToString(";"))
                insert.setBytes(9, VarintBuffer().also { b -> ids.forEach { b.add(it) } }.toByteArray())
                insert.addBatch()
                if (row % 10_000 == 0) insert.executeBatch()
            }
            insert.executeBatch()
            insert.close()
            if (row == 0) {
                throw LayoutException("Forage found no videos it could read in the dump", firstLine?.let(::describe) ?: "empty file")
            }

            var keptTags = 0
            db.prepareStatement("INSERT INTO tag (id, name, display, df, postings) VALUES (?,?,?,?,?)").use { st ->
                for ((name, id) in tagIds) {
                    val list = postings[id]
                    if (list.count < MIN_TAG_VIDEOS) continue
                    keptTags++
                    st.setInt(1, id)
                    st.setString(2, name)
                    st.setString(3, tagDisplay[id])
                    st.setInt(4, list.count)
                    st.setBytes(5, list.toByteArray())
                    st.addBatch()
                    if (keptTags % 5_000 == 0) st.executeBatch()
                }
                st.executeBatch()
            }
            db.prepareStatement("INSERT INTO meta (key, value) VALUES (?, ?)").use { st ->
                for ((k, v) in listOf(
                    "version" to SCHEMA_VERSION.toByteArray(),
                    "count" to row.toString().toByteArray(),
                    "imported_at" to importedAt.toString().toByteArray(),
                    "tag_counts" to tagCounts.toByteArray(),
                    "quality" to quality.toByteArray(),
                )) {
                    st.setString(1, k)
                    st.setBytes(2, v)
                    st.addBatch()
                }
                st.executeBatch()
            }
            db.createStatement().use { it.execute("CREATE INDEX video_key ON video (key)") }
            db.commit()
            val (fields, at) = layouts.maxBy { it.value }.key
            val layout = "$fields fields, counts in fields ${at + 1}–${at + 4}" + if (layouts.size > 1) " (most lines)" else ""
            return ImportSummary(row, unreadable, fewViews, keptTags, layout)
        }

        private fun decodePostings(bytes: ByteArray, each: (Int) -> Unit) {
            var last = 0
            decodeList(bytes) { delta -> last += delta; each(last) }
        }

        private inline fun decodeList(bytes: ByteArray, each: (Int) -> Unit) {
            var i = 0
            while (i < bytes.size) {
                var value = 0
                var shift = 0
                while (true) {
                    val b = bytes[i++].toInt()
                    value = value or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                each(value)
            }
        }
    }

    /** Unsigned varints in a growing byte array; with [addDelta], each value stored as the gap to the previous one. */
    private class VarintBuffer {
        private var bytes = ByteArray(8)
        private var size = 0
        private var last = 0
        var count = 0
            private set

        fun addDelta(value: Int) {
            add(value - last)
            last = value
        }

        fun add(value: Int) {
            var v = value
            while (true) {
                if (size + 5 > bytes.size) bytes = bytes.copyOf(bytes.size * 2)
                if (v and 0x7f.inv() == 0) {
                    bytes[size++] = v.toByte()
                    break
                }
                bytes[size++] = ((v and 0x7f) or 0x80).toByte()
                v = v ushr 7
            }
            count++
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private class GrowableBytes {
        private var bytes = ByteArray(1 shl 16)
        private var size = 0

        fun add(value: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = value.toByte()
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
