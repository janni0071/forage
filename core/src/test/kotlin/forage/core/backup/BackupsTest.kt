package forage.core.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import forage.core.library.PlaybackPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupsTest {
    private fun fresh(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        ForageDatabase.Schema.create(it)
        it.execute(null, "PRAGMA user_version = ${ForageDatabase.Schema.version}", 0)
    }

    private fun scratch() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    private fun filled(driver: JdbcSqliteDriver): LibraryStore = LibraryStore(ForageDatabase(driver)).apply {
        recordWatch(PlatformId.youtube("aaaaaaaaaaa"))
        saveForLater(PlatformId.youtube("bbbbbbbbbbb"))
        follow(PlatformId("youtube", "UCwren"), "Workshop Wren")
        savePlaybackPosition(PlatformId.youtube("aaaaaaaaaaa"), PlaybackPosition(120, 600))
    }

    @Test
    fun `a library comes back exactly as it was`() {
        val source = fresh()
        filled(source)
        val bytes = ByteArrayOutputStream().also { Backups.write(source, it, "laptop") }.toByteArray()
        assertTrue(Backups.isBackup(bytes) && !Backups.isSealed(bytes))

        val target = fresh()
        val targetDb = ForageDatabase(target)
        // Something already there goes: a restore replaces the library.
        LibraryStore(targetDb).saveForLater(PlatformId.youtube("zzzzzzzzzzz"))
        val contents = Backups.read(ByteArrayInputStream(bytes))
        assertEquals("laptop", contents.about.device)
        Backups.restore(contents, target, targetDb, scratch())

        val library = LibraryStore(targetDb)
        assertEquals(listOf(PlatformId.youtube("bbbbbbbbbbb")), library.watchLater())
        assertEquals(mapOf(PlatformId.youtube("aaaaaaaaaaa") to PlaybackPosition(120, 600)), library.playbackPositions())
        assertEquals(listOf("Workshop Wren"), library.following().map { it.name })
        assertEquals(1L, library.counts().second)
    }

    @Test
    fun `blobs and fractions keep their bytes and digits`() {
        val source = fresh()
        source.execute(null, "INSERT INTO embedding (video_id, model, text_hash, vector) VALUES ('youtube:x', 'm', 'h', ?)", 1) { bindBytes(0, byteArrayOf(0, 1, -1, 127)) }
        val bytes = ByteArrayOutputStream().also { Backups.write(source, it, null) }.toByteArray()
        val target = fresh()
        Backups.restore(Backups.read(ByteArrayInputStream(bytes)), target, ForageDatabase(target), scratch())
        val vector = target.executeQuery(null, "SELECT vector FROM embedding", { c -> c.next(); QueryResult.Value(c.getBytes(0)) }, 0).value
        assertTrue(byteArrayOf(0, 1, -1, 127).contentEquals(vector))
    }

    @Test
    fun `a sealed backup opens with its PIN only`() {
        val source = fresh()
        filled(source)
        val bytes = ByteArrayOutputStream().also { Backups.write(source, it, null, "4711".toCharArray()) }.toByteArray()
        assertTrue(Backups.isSealed(bytes))
        assertFailsWith<WrongPinException> { Backups.read(ByteArrayInputStream(bytes)) }
        assertFailsWith<WrongPinException> { Backups.read(ByteArrayInputStream(bytes), "1234".toCharArray()) }
        val contents = Backups.read(ByteArrayInputStream(bytes), "4711".toCharArray())
        assertTrue(contents.about.private)
        assertEquals(1, contents.rowCount("watch_later"))
    }

    @Test
    fun `a backup from an older Forage is brought up to date on the way in`() {
        // The library as version 15 had it: no playback positions yet.
        val old = fresh()
        old.execute(null, "DROP TABLE playback_position", 0)
        old.execute(null, "PRAGMA user_version = 15", 0)
        LibraryStore(ForageDatabase(old)).saveForLater(PlatformId.youtube("ccccccccccc"))
        val bytes = ByteArrayOutputStream().also { Backups.write(old, it, null) }.toByteArray()
        assertEquals(15L, Backups.read(ByteArrayInputStream(bytes)).about.schemaVersion)

        val target = fresh()
        val db = ForageDatabase(target)
        Backups.restore(Backups.read(ByteArrayInputStream(bytes)), target, db, scratch())
        val library = LibraryStore(db)
        assertEquals(listOf(PlatformId.youtube("ccccccccccc")), library.watchLater())
        assertEquals(emptyMap(), library.playbackPositions())
    }

    @Test
    fun `anything else isn't taken for a backup`() {
        assertFailsWith<NotABackupException> { Backups.read(ByteArrayInputStream("SQLite format 3".toByteArray())) }
    }

    @Test
    fun `likes come back even where the database checks that their profile exists`() {
        val source = fresh()
        val profiles = forage.core.profile.ProfileStore(ForageDatabase(source))
        profiles.like(profiles.profile(), PlatformId.youtube("ddddddddddd"))
        val bytes = ByteArrayOutputStream().also { Backups.write(source, it, null) }.toByteArray()
        val contents = Backups.read(ByteArrayInputStream(bytes))
        assertEquals(1, contents.rowCount("profile_video", "state", "liked"))

        val target = fresh()
        target.execute(null, "PRAGMA foreign_keys = ON", 0)
        val db = ForageDatabase(target)
        Backups.restore(contents, target, db, scratch().also { it.execute(null, "PRAGMA foreign_keys = ON", 0) })
        val restored = forage.core.profile.ProfileStore(db)
        assertEquals(listOf(PlatformId.youtube("ddddddddddd")), restored.entries(restored.profile()).map { it.video })
    }
}
