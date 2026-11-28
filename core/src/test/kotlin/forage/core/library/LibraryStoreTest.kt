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
        playlists = mapOf(
            "Woodwork" to listOf(PlaylistEntry(id("c"), at(5)), PlaylistEntry(id("a"), at(1))),
            "Watch later" to listOf(PlaylistEntry(id("d"), at(7))),
        ),
        subscriptions = listOf(Subscription(id("UCwren"), "Workshop Wren")),
    )

    @Test
    fun `import fills history, playlists and subscriptions, and repeating it changes nothing`() {
        val summary = store.importTakeout(takeout)
        assertEquals(ImportSummary(3, mapOf("Woodwork" to 2, "Watch later" to 1), 1), summary)
        store.importTakeout(takeout)
        assertEquals(4L to 3L, store.counts())
        assertEquals(mapOf("Woodwork" to 2L), store.playlists())
        // YouTube's Watch later lands in the queue, with the time it was saved there.
        assertEquals(listOf(SavedVideo(id("d"), at(7))), store.savedForLater())
        // In the order the export lists them, which is the playlist's own.
        assertEquals(listOf(id("c") to at(5), id("a") to at(1)), store.playlistItems("Woodwork"))
        assertEquals(listOf(Subscription(id("UCwren"), "Workshop Wren")), store.subscriptions())
        assertEquals(setOf(id("a"), id("b"), id("c"), id("d")), store.knownVideoIds())
    }

    @Test
    fun `events carry the details any import supplied`() {
        store.importTakeout(takeout)
        val history = store.events()
        assertEquals(3, history.size)
        // The second watch of "a" had no title, but the library remembers the first one's.
        assertEquals(setOf("Dovetails"), history.filter { it.video == id("a") }.map { it.title }.toSet())
        assertNull(history.single { it.video == id("b") }.watchedAt)

        val saved = store.events(playlist = "Woodwork").associateBy { it.video }
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

    @Test
    fun `playlists can be made, filled, renamed and deleted`() {
        store.importTakeout(takeout)
        assertEquals(true, store.createPlaylist("Joinery"))
        assertEquals(false, store.createPlaylist("Joinery"))
        assertEquals(0L, store.playlists()["Joinery"])
        assertEquals(2, store.addToPlaylist("Joinery", listOf(id("a"), id("e"))))
        assertEquals(0, store.addToPlaylist("Joinery", listOf(id("a"))))
        assertEquals(false, store.renamePlaylist("Joinery", "Woodwork"))
        assertEquals(true, store.renamePlaylist("Joinery", "Carving"))
        assertEquals(listOf(id("a"), id("e")), store.events("Carving").map { it.video })
        store.removeFromPlaylist("Carving", listOf(id("a")))
        assertEquals(mapOf("Carving" to 1L, "Woodwork" to 2L), store.playlists())
        store.deletePlaylist("Carving")
        assertEquals(mapOf("Woodwork" to 2L), store.playlists())
        // The videos stay in the library.
        assertEquals("Dovetails", store.video(id("a"))?.title)
    }

    @Test
    fun `the history records opened videos, and forgets them on request`() {
        store.importTakeout(takeout)
        store.recordWatch(id("e"))
        assertEquals(setOf(id("a"), id("b"), id("e")), store.events().map { it.video }.toSet())
        store.forget(listOf(id("a")))
        assertEquals(setOf(id("b"), id("e")), store.events().map { it.video }.toSet())
        store.clearHistory()
        assertEquals(emptyList(), store.events())
        // Playlists are not history.
        assertEquals(2, store.events("Woodwork").size)
    }
}

class WatchLaterTest {
    private val id = PlatformId.youtube("x")
    private var now = Instant.parse("2026-11-17T10:00:00Z")
    private val store = LibraryStore(memoryDatabase()) { now }

    @Test
    fun `watched videos leave the queue but stay known`() {
        store.saveForLater(id)
        store.markWatched(id)
        assertEquals(emptyList(), store.watchLater())
        assertEquals(setOf(id), store.knownVideoIds())
        assertEquals(1, store.events().size)
    }

    @Test
    fun `the queue knows when each video was saved and keeps details for it`() {
        store.remember(id, "Title", durationSeconds = 754, uploadedAt = Instant.parse("2026-10-01T00:00:00Z"))
        store.saveForLater(id)
        assertEquals(listOf(SavedVideo(id, now)), store.savedForLater())
        assertEquals(754L, store.video(id)!!.durationSeconds)
        // Later calls without the details keep them.
        store.remember(id, "Title")
        assertEquals(754L, store.video(id)!!.durationSeconds)
    }
}

class SubscriptionFeedTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private var now = Instant.parse("2026-11-20T08:00:00Z")
    private val store = LibraryStore(memoryDatabase()) { now }
    private fun video(v: String, daysAgo: Long, title: String = "Video $v") =
        forage.core.source.VideoStub(id(v), title, id("UCwren"), "Workshop Wren", 600, false, now.minusSeconds(daysAgo * 86_400))

    @Test
    fun `a channel's first check fills its uploads without calling them new, later ones are`() {
        store.follow(id("UCwren"), "Workshop Wren")
        store.storeFeed(id("UCwren"), forage.core.source.ChannelFeed(listOf(video("a", 1), video("b", 3)), "Workshop Wren", "https://img/wren.jpg"))
        assertEquals(listOf("a", "b"), store.uploads().map { it.video.id.id })
        assertEquals(listOf(false, false), store.uploads().map { it.isNew })
        assertEquals("https://img/wren.jpg", store.following().single().avatarUrl)

        now = now.plusSeconds(86_400)
        store.storeFeed(id("UCwren"), forage.core.source.ChannelFeed(listOf(video("c", 0), video("a", 2, "Video a, fixed"))))
        assertEquals(listOf("c", "a", "b"), store.uploads().map { it.video.id.id })
        assertEquals(listOf(true, false, false), store.uploads().map { it.isNew })
        assertEquals("Video a, fixed", store.uploads()[1].video.title)
        // The picture stays when a later look has none.
        assertEquals("https://img/wren.jpg", store.following().single().avatarUrl)

        store.markUploadsSeen()
        assertEquals(listOf(false, false, false), store.uploads().map { it.isNew })

        store.markGone(id("UCwren"), "This channel was removed")
        assertEquals("This channel was removed", store.following().single().gone)
        store.storeFeed(id("UCwren"), forage.core.source.ChannelFeed(emptyList()))
        assertEquals(null, store.following().single().gone)

        // Only the newest are kept; unfollowing forgets them.
        store.storeFeed(id("UCwren"), forage.core.source.ChannelFeed(emptyList()), keep = 2)
        assertEquals(listOf("c", "a"), store.uploads().map { it.video.id.id })
        store.unfollow(id("UCwren"))
        assertEquals(emptyList(), store.uploads())
    }
}
