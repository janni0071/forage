package forage.core.importer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppExportsTest {
    private val dir: File = Files.createTempDirectory("exports").toFile()
    private val scratch = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) }
    private fun yt(id: String) = PlatformId.youtube(id)
    private val v1 = "aaaaaaaaaa1"
    private val v2 = "bbbbbbbbbb2"
    private val v3 = "ccccccccc_3"
    private val ch = "UC" + "x".repeat(22)

    @AfterTest
    fun cleanUp() { dir.deleteRecursively() }

    private fun read(bytes: ByteArray, name: String = "export") = AppExports.read(name, { bytes.inputStream() }, scratch, dir)

    @Test
    fun `NewPipe's database export gives history, playlists, subscriptions and positions, YouTube only`() {
        // A database laid out as NewPipe's Room entities are.
        val db = File(dir, "src.db")
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE streams (uid INTEGER PRIMARY KEY, service_id INTEGER, url TEXT, title TEXT, stream_type TEXT, duration INTEGER, uploader TEXT, uploader_url TEXT, thumbnail_url TEXT, view_count INTEGER, textual_upload_date TEXT, upload_date INTEGER, is_upload_date_approximation INTEGER)")
                s.executeUpdate("CREATE TABLE stream_history (stream_id INTEGER, access_date INTEGER, repeat_count INTEGER, PRIMARY KEY (stream_id, access_date))")
                s.executeUpdate("CREATE TABLE stream_state (stream_id INTEGER PRIMARY KEY, progress_time INTEGER)")
                s.executeUpdate("CREATE TABLE playlists (uid INTEGER PRIMARY KEY, name TEXT, is_thumbnail_permanent INTEGER, thumbnail_stream_id INTEGER, display_index INTEGER)")
                s.executeUpdate("CREATE TABLE playlist_stream_join (playlist_id INTEGER, stream_id INTEGER, join_index INTEGER, PRIMARY KEY (playlist_id, join_index))")
                s.executeUpdate("CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY, service_id INTEGER, url TEXT, name TEXT, avatar_url TEXT, subscriber_count INTEGER, description TEXT, notification_mode INTEGER)")
                s.executeUpdate("INSERT INTO streams VALUES (1, 0, 'https://www.youtube.com/watch?v=$v1', 'Dovetails by hand', 'VIDEO_STREAM', 600, 'Workshop Wren', 'https://www.youtube.com/channel/$ch', NULL, NULL, NULL, 1700000000000, 0)")
                s.executeUpdate("INSERT INTO streams VALUES (2, 0, 'https://www.youtube.com/watch?v=$v2', 'Sharpening chisels', 'VIDEO_STREAM', 300, 'Workshop Wren', 'https://www.youtube.com/channel/$ch', NULL, NULL, NULL, NULL, 0)")
                s.executeUpdate("INSERT INTO streams VALUES (3, 1, 'https://soundcloud.com/someone/track', 'A track', 'AUDIO_STREAM', 200, 'Someone', NULL, NULL, NULL, NULL, NULL, 0)")
                s.executeUpdate("INSERT INTO stream_history VALUES (1, 1710000000000, 2), (3, 1710000500000, 1)")
                s.executeUpdate("INSERT INTO stream_state VALUES (1, 125000)")
                s.executeUpdate("INSERT INTO playlists VALUES (1, 'Woodworking', 0, -1, 0), (2, 'Empty one', 0, -1, 1)")
                s.executeUpdate("INSERT INTO playlist_stream_join VALUES (1, 2, 0), (1, 1, 1), (1, 3, 2)")
                s.executeUpdate("INSERT INTO subscriptions VALUES (1, 0, 'https://www.youtube.com/channel/$ch', 'Workshop Wren', NULL, NULL, NULL, 0), (2, 1, 'https://soundcloud.com/someone', 'Someone', NULL, NULL, NULL, 0)")
            }
        }
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("newpipe.db")); z.write(db.readBytes()); z.closeEntry()
                z.putNextEntry(ZipEntry("preferences.json")); z.write("{}".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val data = read(zip, "NewPipeData-20261127.zip")
        assertEquals("NewPipe", data.source)
        assertEquals(listOf(yt(v1)), data.history.events.map { it.video })
        assertEquals(Instant.ofEpochMilli(1710000000000), data.history.events.single().watchedAt)
        assertEquals(yt(ch), data.history.events.single().channel)
        assertEquals(listOf("Woodworking", "Empty one"), data.playlists.keys.toList())
        assertEquals(listOf(yt(v2), yt(v1)), data.playlists.getValue("Woodworking").map { it.video })
        assertEquals("Sharpening chisels", data.playlists.getValue("Woodworking").first().title)
        assertEquals(listOf(Subscription(yt(ch), "Workshop Wren")), data.subscriptions)
        assertEquals(mapOf(yt(v1) to 125L), data.positions)
        assertEquals(600L, data.videos.first { it.id == yt(v1) }.durationSeconds)
        assertEquals(Instant.ofEpochMilli(1700000000000), data.videos.first { it.id == yt(v1) }.uploadedAt)
        // The copy taken to read it is gone.
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("newpipe") })
    }

    @Test
    fun `FreeTube's history, playlists and subscriptions exports, and its own data files`() {
        val history = """
            {"videoId":"$v1","title":"Dovetails by hand","author":"Workshop Wren","authorId":"$ch","published":1700000000000,"description":"","viewCount":10,"lengthSeconds":600,"watchProgress":125,"timeWatched":1710000000000,"isLive":false,"type":"video","_id":"h1"}
            {"videoId":"$v2","title":"A short","author":"Workshop Wren","authorId":"$ch","published":1700000000000,"lengthSeconds":40,"watchProgress":0,"timeWatched":1710000100000,"isLive":false,"type":"short","_id":"h2"}
        """.trimIndent() + "\n"
        val h = read(history.toByteArray(), "freetube-watch-history-2026-11-27.db")
        assertEquals("FreeTube", h.source)
        assertEquals(listOf(yt(v1), yt(v2)), h.history.events.map { it.video })
        assertTrue(h.history.events[1].isShort)
        assertEquals(mapOf(yt(v1) to 125L), h.positions)

        val playlists = """
            {"playlistName":"Favorites","protected":true,"description":"","videos":[{"videoId":"$v1","title":"Dovetails by hand","author":"Workshop Wren","authorId":"$ch","lengthSeconds":"600","timeAdded":1711000000000,"playlistItemId":"x","type":"video"}],"_id":"favorites","createdAt":1,"lastUpdatedAt":2}
            {"playlistName":"Watch Later","videos":[{"videoId":"$v3","title":"Later","lengthSeconds":100,"timeAdded":1712000000000}],"_id":"watchLater"}
        """.trimIndent() + "\n"
        val p = read(playlists.toByteArray())
        assertEquals(setOf("Favorites", "Watch later"), p.playlists.keys)
        assertEquals(600L, p.playlists.getValue("Favorites").single().durationSeconds)
        assertEquals(Instant.ofEpochMilli(1711000000000), p.playlists.getValue("Favorites").single().addedAt)

        val profiles = """
            {"name":"All Channels","bgColor":"#000000","textColor":"#FFFFFF","subscriptions":[{"id":"$ch","name":"Workshop Wren","thumbnail":"x"}],"_id":"allChannels"}
            {"name":"Wood","bgColor":"#000000","textColor":"#FFFFFF","subscriptions":[{"id":"$ch","name":"Workshop Wren","thumbnail":"x"}],"_id":"wood"}
        """.trimIndent() + "\n"
        assertEquals(listOf(Subscription(yt(ch), "Workshop Wren")), read(profiles.toByteArray()).subscriptions)

        // FreeTube's own history.db (NeDB): a later line replaces an earlier one; $$ lines are bookkeeping.
        val nedb = """
            {"videoId":"$v1","title":"Old title","author":"W","authorId":"$ch","lengthSeconds":600,"watchProgress":10,"timeWatched":1700000000000,"isLive":false,"type":"video","published":1,"_id":"h1"}
            {"${'$'}${'$'}indexCreated":{"fieldName":"videoId","unique":true}}
            {"videoId":"$v2","title":"Gone","author":"W","authorId":"$ch","lengthSeconds":40,"watchProgress":0,"timeWatched":1700000000000,"isLive":false,"type":"video","published":1,"_id":"h2"}
            {"${'$'}${'$'}deleted":true,"_id":"h2"}
            {"videoId":"$v1","title":"New title","author":"W","authorId":"$ch","lengthSeconds":600,"watchProgress":200,"timeWatched":1710000000000,"isLive":false,"type":"video","published":1,"_id":"h1"}
        """.trimIndent() + "\n"
        val n = read(nedb.toByteArray(), "history.db")
        assertEquals(listOf("New title"), n.history.events.map { it.title })
        assertEquals(mapOf(yt(v1) to 200L), n.positions)
    }

    @Test
    fun `LibreTube's backup, and NewPipe's and Piped's subscriptions`() {
        val backup = """
            {"format":"Piped","version":1,
             "watchHistory":[{"videoId":"$v1","title":"Dovetails by hand","uploadDate":"2023-11-14","uploader":"Workshop Wren","uploaderUrl":"/channel/$ch","uploaderAvatar":null,"thumbnailUrl":"x","duration":600,"isShort":false}],
             "watchPositions":[{"videoId":"$v1","position":125000}],
             "searchHistory":[],"customInstances":[],"playlistBookmarks":[],"preferences":[],"groups":[],
             "subscriptions":[{"channelId":"$ch","name":"Workshop Wren","avatar":null,"verified":false}],
             "localPlaylists":[{"playlist":{"id":1,"name":"Woodworking","thumbnailUrl":"","description":""},"videos":[{"id":1,"playlistId":1,"videoId":"$v2","title":"Sharpening chisels","uploadDate":"2023-01-01","uploader":"Workshop Wren","uploaderUrl":"/channel/$ch","thumbnailUrl":"x","duration":300}]}],
             "playlists":[{"name":"From Piped","type":"playlist","visibility":"private","videos":["https://www.youtube.com/watch?v=$v3"]}]}
        """.trimIndent()
        val b = read(backup.toByteArray(), "libretube-backup.json")
        assertEquals("LibreTube", b.source)
        assertEquals(listOf(yt(v1)), b.history.events.map { it.video })
        assertEquals(null, b.history.events.single().watchedAt)
        assertEquals(mapOf(yt(v1) to 125L), b.positions)
        assertEquals(listOf(Subscription(yt(ch), "Workshop Wren")), b.subscriptions)
        assertEquals(listOf(yt(v2)), b.playlists.getValue("Woodworking").map { it.video })
        assertEquals(listOf(yt(v3)), b.playlists.getValue("From Piped").map { it.video })
        assertEquals(Instant.parse("2023-11-14T00:00:00Z"), b.videos.single().uploadedAt)

        val newPipe = """{"app_version":"0.27.6","app_version_int":1004,"subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/$ch","name":"Workshop Wren"},{"service_id":1,"url":"https://soundcloud.com/x","name":"X"}]}"""
        val s = read(newPipe.toByteArray(), "newpipe_subscriptions.json")
        assertEquals("NewPipe", s.source)
        assertEquals(listOf(Subscription(yt(ch), "Workshop Wren")), s.subscriptions)
    }

    @Test
    fun `anything else is refused with what to pick instead`() {
        assertFailsWith<NotAnExportException> { read("just some notes".toByteArray()) }
        assertFailsWith<NotAnExportException> { read("""{"hello":"world"}""".toByteArray()) }
    }
}
