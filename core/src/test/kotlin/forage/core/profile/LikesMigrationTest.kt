package forage.core.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Version 10 turns the profile states into likes and gives playlists their own table. */
class LikesMigrationTest {
    @Test
    fun `old profile states become likes, and imported playlists keep their videos`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ForageDatabase.Schema.create(driver)
        // Back to how version 9 stored things.
        driver.execute(null, "DROP TABLE playlist", 0)
        driver.execute(null, "INSERT INTO profile (id, name, created_at) VALUES (1, 'Default', 0)", 0)
        for ((video, state) in listOf("a" to "included", "b" to "excluded", "c" to "negative")) {
            driver.execute(null, "INSERT INTO profile_video VALUES (1, 'youtube:$video', '$state', 1, 'manual', 0)", 0)
        }
        driver.execute(null, "INSERT INTO playlist_item VALUES ('Woodwork', 'youtube:a', 100), ('Woodwork', 'youtube:d', 50)", 0)

        ForageDatabase.Schema.migrate(driver, 9, 10)

        val db = ForageDatabase(driver)
        val profiles = ProfileStore(db)
        val entries = profiles.entries(profiles.profile()).associate { it.video.id to it.state }
        assertEquals(mapOf("a" to VideoState.LIKED, "c" to VideoState.NOT_INTERESTED), entries)
        val library = LibraryStore(db)
        assertEquals(mapOf("Woodwork" to 2L), library.playlists())
        assertEquals(setOf(PlatformId.youtube("a"), PlatformId.youtube("d")), library.events("Woodwork").map { it.video }.toSet())
    }

    @Test
    fun `an imported Watch later playlist moves into the watch-later queue`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ForageDatabase.Schema.create(driver)
        driver.execute(null, "INSERT INTO playlist VALUES ('Später ansehen', 0), ('Woodwork', 0)", 0)
        driver.execute(null, "INSERT INTO playlist_item VALUES ('Später ansehen', 'youtube:a', 100), ('Später ansehen', 'youtube:b', NULL), ('Woodwork', 'youtube:c', 5)", 0)
        driver.execute(null, "INSERT INTO watch_later VALUES ('youtube:a', 50)", 0)

        ForageDatabase.Schema.migrate(driver, 10, 11)

        val library = LibraryStore(ForageDatabase(driver))
        assertEquals(mapOf("Woodwork" to 1L), library.playlists())
        val saved = library.savedForLater().associate { it.id.id to it.savedAt.epochSecond }
        // Already queued stays as it was; one without a date gets the time of the move.
        assertEquals(50L, saved["a"])
        assertEquals(setOf("a", "b"), saved.keys)
    }
}
