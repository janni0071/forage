package forage.core.library

import forage.core.PlatformId
import forage.core.importer.ImportResult
import forage.core.importer.PlaylistEntry
import forage.core.importer.Subscription
import forage.core.importer.TakeoutData
import forage.core.importer.WatchEvent
import forage.core.metadata.memoryDatabase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryStoreTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private fun at(day: Int) = Instant.parse("2026-10-%02dT12:00:00Z".format(day))
    private val store = LibraryStore(memoryDatabase())
    private val takeout = TakeoutData(
        history = ImportResult(
            listOf(
                WatchEvent(id("a"), "Dovetails", id("UCwren"), "Workshop Wren", at(20)),
                WatchEvent(id("a"), null, null, null, at(10)),
                WatchEvent(id("b"), "Sourdough", null, "Brotzeit", null),
            ),
            emptyMap(),
        ),
        playlists = mapOf("Watch later" to listOf(PlaylistEntry(id("c"), at(5)), PlaylistEntry(id("a"), at(1)))),
        subscriptions = listOf(Subscription(id("UCwren"), "Workshop Wren")),
    )

    @Test
    fun `import fills history, playlists and subscriptions, and repeating it changes nothing`() {
        val summary = store.importTakeout(takeout)
        assertEquals(ImportSummary(3, mapOf("Watch later" to 2), 1), summary)
        store.importTakeout(takeout)
        assertEquals(3L to 3L, store.counts())
        assertEquals(mapOf("Watch later" to 2L), store.playlists())
        assertEquals(listOf(Subscription(id("UCwren"), "Workshop Wren")), store.subscriptions())
        assertEquals(setOf(id("a"), id("b"), id("c")), store.knownVideoIds())
    }

    @Test
    fun `events carry the details any import supplied`() {
        store.importTakeout(takeout)
        val history = store.events()
        assertEquals(3, history.size)
        // The second watch of "a" had no title, but the library remembers the first one's.
        assertEquals(setOf("Dovetails"), history.filter { it.video == id("a") }.map { it.title }.toSet())
        assertNull(history.single { it.video == id("b") }.watchedAt)

        val saved = store.events(playlist = "Watch later").associateBy { it.video }
        assertEquals(at(1), saved.getValue(id("a")).watchedAt)
        assertNull(saved.getValue(id("c")).title)
    }

    @Test
    fun `first seen is the earliest watch or save`() {
        store.importTakeout(takeout)
        assertEquals(mapOf(id("a") to at(1), id("c") to at(5)), store.firstSeen())
    }

    @Test
    fun `later details never erase earlier ones`() {
        store.remember(id("x"), "Title", id("UCx"), "Channel")
        store.remember(id("x"))
        assertEquals(LibraryVideo(id("x"), "Title", id("UCx"), "Channel", false), store.video(id("x")))
    }

    @Test
    fun `watch later is a queue that counts as known`() {
        store.saveForLater(id("x"))
        store.saveForLater(id("x"))
        assertEquals(listOf(id("x")), store.watchLater())
        assertEquals(setOf(id("x")), store.knownVideoIds())
        store.removeFromWatchLater(id("x"))
        assertEquals(emptyList(), store.watchLater())
    }
}
