package forage.core.importer

import app.cash.sqldelight.db.SqlDriver
import forage.core.PlatformId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/** A file that isn't an export Forage knows, with what to pick instead. */
class NotAnExportException(message: String) : Exception(message)

/**
 * Reads whatever export it's given, telling them apart by their contents, not their names:
 * - a zip: NewPipe's database export (it holds `newpipe.db`), else Google Takeout;
 * - JSON lines (FreeTube's `.db` exports): its watch history, playlists or subscription profiles,
 *   one object per line; FreeTube's own data files (NeDB) too, where a later line replaces an
 *   earlier one with the same `_id` and `$$` lines are bookkeeping;
 * - one JSON object: LibreTube's backup (Piped's format: history, positions, subscriptions,
 *   playlists), or a NewPipe or Piped subscriptions export;
 * - a JSON array: Takeout's `watch-history.json` on its own.
 */
object AppExports {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [open] opens the file afresh each time (it's read up to twice); [scratch] gives a database to
     * read NewPipe's through, and [tempDir] a place for it while it's read (deleted after).
     */
    fun read(name: String, open: () -> InputStream, scratch: () -> SqlDriver, tempDir: File): TakeoutData {
        val head = open().use { it.readNBytes(16) }
        if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val newPipe = open().use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.any { it.name.substringAfterLast('/') == "newpipe.db" }
                }
            }
            return if (newPipe) readNewPipeZip(open, scratch, tempDir) else open().use(TakeoutFolder::readZip)
        }
        if (head.decodeToString(0, minOf(head.size, 15)) == "SQLite format 3") {
            return readNewPipeDatabase(name, open, scratch, tempDir)
        }
        val text = open().use { it.readBytes() }.decodeToString().removePrefix("﻿")
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("[") -> TakeoutData(TakeoutWatchHistory.parse(text.byteInputStream()), emptyMap(), emptyList())
            trimmed.startsWith("{") -> {
                val single = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                if (single != null && !looksLikeFreeTube(single)) readJson(single) else readFreeTube(text.lineSequence())
            }
            else -> throw NotAnExportException(UNKNOWN)
        }
    }

    const val UNKNOWN = "That isn't an export Forage knows. Pick a Takeout zip from Google, NewPipe's database export (a zip), " +
        "FreeTube's history, playlists or subscriptions export (.db), or LibreTube's backup (.json)."

    // NewPipe

    private fun readNewPipeZip(open: () -> InputStream, scratch: () -> SqlDriver, tempDir: File): TakeoutData {
        val file = File.createTempFile("newpipe", ".db", tempDir.apply { mkdirs() })
        try {
            open().use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.substringAfterLast('/') == "newpipe.db") {
                            file.outputStream().use { zip.copyTo(it) }
                            break
                        }
                    }
                }
            }
            return attached(file, scratch) { driver, schema ->
                if (!NewPipeImport.isNewPipe(driver, schema)) throw NotAnExportException(UNKNOWN)
                NewPipeImport.read(driver, schema)
            }
        } finally {
            file.delete()
        }
    }

    private fun readNewPipeDatabase(name: String, open: () -> InputStream, scratch: () -> SqlDriver, tempDir: File): TakeoutData {
        val file = File.createTempFile("import", ".db", tempDir.apply { mkdirs() })
        try {
            open().use { input -> file.outputStream().use { input.copyTo(it) } }
            return attached(file, scratch) { driver, schema ->
                if (!NewPipeImport.isNewPipe(driver, schema)) throw NotAnExportException("$name isn't NewPipe's database. $UNKNOWN")
                NewPipeImport.read(driver, schema)
            }
        } finally {
            file.delete()
        }
    }

    /** [file] attached to a scratch database as `export`, for [block] to read. */
    private fun <T> attached(file: File, scratch: () -> SqlDriver, block: (SqlDriver, String) -> T): T {
        val driver = scratch()
        try {
            // SQLCipher (Android) wants an empty key for a plain database; SQLite (the desktop) knows no KEY.
            val attachedWithKey = runCatching {
                driver.execute(null, "ATTACH DATABASE ? AS export KEY ''", 1) { bindString(0, file.path) }
            }.isSuccess
            if (!attachedWithKey) driver.execute(null, "ATTACH DATABASE ? AS export", 1) { bindString(0, file.path) }
            return block(driver, "export")
        } finally {
            runCatching { driver.execute(null, "DETACH DATABASE export", 0) }
            driver.close()
        }
    }

    // FreeTube

    private fun looksLikeFreeTube(o: JsonObject) =
        ("videoId" in o && "timeWatched" in o) || ("playlistName" in o && "videos" in o) ||
            ("subscriptions" in o && "_id" in o && "name" in o) || ("channelId" in o && "profile" in o)

    fun readFreeTube(lines: Sequence<String>): TakeoutData {
        // NeDB keeps changes by appending: a later line with the same _id replaces an earlier one.
        val records = LinkedHashMap<String, JsonObject>()
        var anonymous = 0
        for (line in lines) {
            val o = line.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: continue
            if (o.keys.any { it.startsWith("$$") }) {
                if (o.bool("\$\$deleted") == true) o.str("_id")?.let { records.remove(it) }
                continue
            }
            val key = o.str("_id") ?: o.str("videoId")?.let { "video:$it" } ?: "line:${anonymous++}"
            records[key] = o
        }
        val events = ArrayList<WatchEvent>()
        val videos = ArrayList<KnownVideo>()
        val positions = HashMap<PlatformId, Long>()
        val playlists = LinkedHashMap<String, List<PlaylistEntry>>()
        val subscriptions = LinkedHashMap<PlatformId, Subscription>()
        for (o in records.values) {
            when {
                "videoId" in o && "timeWatched" in o -> {
                    val id = YouTubeLinks.video(o.str("videoId")) ?: continue
                    val channel = YouTubeLinks.channel(o.str("authorId"))
                    val length = o.long("lengthSeconds")?.takeIf { it > 0 }
                    events += WatchEvent(id, o.str("title"), channel, o.str("author"), o.long("timeWatched")?.takeIf { it > 0 }?.let(Instant::ofEpochMilli), o.str("type") == "short")
                    videos += KnownVideo(id, o.str("title"), channel, o.str("author"), length, o.long("published")?.takeIf { it > 0 }?.let(Instant::ofEpochMilli), o.str("type") == "short")
                    o.long("watchProgress")?.takeIf { it > 0 }?.let { positions[id] = it }
                }
                "playlistName" in o && "videos" in o -> {
                    val name = o.str("playlistName")?.takeIf { it.isNotBlank() } ?: continue
                    val entries = (o["videos"] as? JsonArray).orEmpty().mapNotNull { v ->
                        val item = v as? JsonObject ?: return@mapNotNull null
                        val id = YouTubeLinks.video(item.str("videoId")) ?: return@mapNotNull null
                        PlaylistEntry(
                            id, item.long("timeAdded")?.takeIf { it > 0 }?.let(Instant::ofEpochMilli), item.str("title"),
                            YouTubeLinks.channel(item.str("authorId")), item.str("author"), item.long("lengthSeconds")?.takeIf { it > 0 },
                        )
                    }
                    // FreeTube's Watch later is the queue, as YouTube's is.
                    val key = if (o.str("_id") == "watchLater" || name.equals("watch later", ignoreCase = true)) WATCH_LATER else name
                    playlists[key] = entries
                }
                "subscriptions" in o -> (o["subscriptions"] as? JsonArray).orEmpty().forEach { s ->
                    val item = s as? JsonObject ?: return@forEach
                    val channel = YouTubeLinks.channel(item.str("id") ?: item.str("channelId") ?: item.str("url")) ?: return@forEach
                    subscriptions.putIfAbsent(channel, Subscription(channel, item.str("name")))
                }
                // FreeTube's old subscriptions format: one channel per line.
                "channelId" in o -> YouTubeLinks.channel(o.str("channelId"))?.let { subscriptions.putIfAbsent(it, Subscription(it, o.str("channelName"))) }
            }
        }
        if (events.isEmpty() && playlists.isEmpty() && subscriptions.isEmpty()) throw NotAnExportException(UNKNOWN)
        return TakeoutData(ImportResult(events, emptyMap()), playlists, subscriptions.values.toList(), videos, positions, "FreeTube")
    }

    // LibreTube, Piped, NewPipe's subscriptions

    fun readJson(o: JsonObject): TakeoutData {
        val events = ArrayList<WatchEvent>()
        val videos = ArrayList<KnownVideo>()
        (o["watchHistory"] as? JsonArray).orEmpty().forEach { e ->
            val item = e as? JsonObject ?: return@forEach
            val id = YouTubeLinks.video(item.str("videoId")) ?: return@forEach
            val channel = YouTubeLinks.channel(item.str("uploaderUrl"))
            // LibreTube keeps no time of watching.
            events += WatchEvent(id, item.str("title"), channel, item.str("uploader"), null, item.bool("isShort") == true)
            videos += KnownVideo(id, item.str("title"), channel, item.str("uploader"), item.long("duration")?.takeIf { it > 0 }, date(item.str("uploadDate")), item.bool("isShort") == true)
        }
        val positions = (o["watchPositions"] as? JsonArray).orEmpty().mapNotNull { p ->
            val item = p as? JsonObject ?: return@mapNotNull null
            val id = YouTubeLinks.video(item.str("videoId")) ?: return@mapNotNull null
            item.long("position")?.takeIf { it > 0 }?.let { id to it / 1000 }
        }.toMap()
        val subscriptions = LinkedHashMap<PlatformId, Subscription>()
        for (key in listOf("subscriptions", "localSubscriptions")) {
            (o[key] as? JsonArray).orEmpty().forEach { s ->
                val item = s as? JsonObject ?: return@forEach
                // NewPipe's and Piped's exports name the service; only YouTube's are taken.
                if ((item.long("service_id") ?: 0L) != 0L) return@forEach
                val channel = YouTubeLinks.channel(item.str("channelId") ?: item.str("url")) ?: return@forEach
                subscriptions.putIfAbsent(channel, Subscription(channel, item.str("name")))
            }
        }
        val playlists = LinkedHashMap<String, List<PlaylistEntry>>()
        (o["localPlaylists"] as? JsonArray).orEmpty().forEach { p ->
            val item = p as? JsonObject ?: return@forEach
            val name = (item["playlist"] as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            playlists[name] = (item["videos"] as? JsonArray).orEmpty().mapNotNull { v ->
                val video = v as? JsonObject ?: return@mapNotNull null
                val id = YouTubeLinks.video(video.str("videoId")) ?: return@mapNotNull null
                PlaylistEntry(id, null, video.str("title"), YouTubeLinks.channel(video.str("uploaderUrl")), video.str("uploader"), video.long("duration")?.takeIf { it > 0 })
            }
        }
        // Piped's playlists: links only.
        (o["playlists"] as? JsonArray).orEmpty().forEach { p ->
            val item = p as? JsonObject ?: return@forEach
            val name = item.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            playlists.putIfAbsent(name, (item["videos"] as? JsonArray).orEmpty().mapNotNull { YouTubeLinks.video((it as? JsonPrimitive)?.contentOrNull) }.map { PlaylistEntry(it, null) })
        }
        if (events.isEmpty() && playlists.isEmpty() && subscriptions.isEmpty()) throw NotAnExportException(UNKNOWN)
        val source = when {
            "app_version" in o -> "NewPipe"
            "watchHistory" in o || "localPlaylists" in o || "watchPositions" in o -> "LibreTube"
            else -> "Piped"
        }
        return TakeoutData(ImportResult(events, emptyMap()), playlists, subscriptions.values.toList(), videos, positions, source)
    }

    /** The name Forage's import takes as the queue (YouTube's Watch later). */
    private val WATCH_LATER = TakeoutFolder.WATCH_LATER.first()

    private fun date(text: String?): Instant? = text?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
        runCatching { LocalDate.parse(t.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
            ?: t.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
