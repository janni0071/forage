package forage.core.importer

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import forage.core.PlatformId
import java.time.Instant

/**
 * NewPipe's exports: the database export (Settings, Backup and restore, Export database: a zip
 * holding `newpipe.db`, a Room database) and the subscriptions export (JSON, read by
 * [AppExports.readJson]). Only YouTube (service 0) is taken; NewPipe's other services aren't Forage's.
 * Dates are epoch milliseconds, playback progress milliseconds, lengths seconds.
 */
object NewPipeImport {
    private const val YOUTUBE_SERVICE = 0L

    /** Whether the database attached as [schema] is NewPipe's. */
    fun isNewPipe(driver: SqlDriver, schema: String): Boolean =
        query(driver, "SELECT count(*) FROM $schema.sqlite_master WHERE type = 'table' AND name IN ('streams', 'stream_history', 'subscriptions')") { it.getLong(0) }
            .firstOrNull() == 3L

    /** Reads the NewPipe database attached to [driver] as [schema]. */
    fun read(driver: SqlDriver, schema: String): TakeoutData {
        val videos = LinkedHashMap<PlatformId, KnownVideo>()
        fun video(c: SqlCursor, from: Int): KnownVideo? {
            val id = YouTubeLinks.video(c.getString(from)) ?: return null
            val known = KnownVideo(
                id,
                title = c.getString(from + 1)?.takeIf { it.isNotBlank() },
                channelName = c.getString(from + 2)?.takeIf { it.isNotBlank() },
                channel = YouTubeLinks.channel(c.getString(from + 3)),
                durationSeconds = c.getLong(from + 4)?.takeIf { it > 0 },
                uploadedAt = c.getLong(from + 5)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            )
            videos[id] = known
            return known
        }
        val stream = "s.url, s.title, s.uploader, s.uploader_url, s.duration, s.upload_date"

        val events = query(
            driver,
            "SELECT $stream, h.access_date FROM $schema.stream_history h JOIN $schema.streams s ON s.uid = h.stream_id " +
                "WHERE s.service_id = $YOUTUBE_SERVICE ORDER BY h.access_date",
        ) { c ->
            val v = video(c, 0) ?: return@query null
            WatchEvent(v.id, v.title, v.channel, v.channelName, c.getLong(6)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli))
        }.filterNotNull()

        val playlists = LinkedHashMap<String, MutableList<PlaylistEntry>>()
        // Empty playlists too, in NewPipe's order.
        query(driver, "SELECT name FROM $schema.playlists ORDER BY display_index, uid") { it.getString(0) }
            .filterNotNull().filter { it.isNotBlank() }.forEach { playlists.getOrPut(it) { ArrayList() } }
        query(
            driver,
            "SELECT p.name, $stream FROM $schema.playlist_stream_join j JOIN $schema.playlists p ON p.uid = j.playlist_id " +
                "JOIN $schema.streams s ON s.uid = j.stream_id WHERE s.service_id = $YOUTUBE_SERVICE ORDER BY p.uid, j.join_index",
        ) { c ->
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@query null
            val v = video(c, 1) ?: return@query null
            playlists.getOrPut(name) { ArrayList() } += PlaylistEntry(v.id, null, v.title, v.channel, v.channelName, v.durationSeconds)
        }

        val subscriptions = query(driver, "SELECT url, name FROM $schema.subscriptions WHERE service_id = $YOUTUBE_SERVICE") { c ->
            YouTubeLinks.channel(c.getString(0))?.let { Subscription(it, c.getString(1)?.takeIf { n -> n.isNotBlank() }) }
        }.filterNotNull()

        val positions = query(
            driver,
            "SELECT s.url, st.progress_time FROM $schema.stream_state st JOIN $schema.streams s ON s.uid = st.stream_id " +
                "WHERE s.service_id = $YOUTUBE_SERVICE",
        ) { c -> YouTubeLinks.video(c.getString(0))?.let { it to (c.getLong(1) ?: 0L) / 1000 } }
            .filterNotNull().toMap()

        return TakeoutData(ImportResult(events, emptyMap()), playlists, subscriptions, videos.values.toList(), positions, "NewPipe")
    }

    private fun <T> query(driver: SqlDriver, sql: String, row: (SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = ArrayList<T>()
            while (c.next().value) out += row(c)
            QueryResult.Value(out)
        }, 0).value
}
