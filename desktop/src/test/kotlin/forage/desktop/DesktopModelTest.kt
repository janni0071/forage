package forage.desktop

import forage.app.*
import forage.app.ui.*

import forage.core.PlatformId
import forage.core.importer.ImportResult
import forage.core.importer.PlaylistEntry
import forage.core.importer.TakeoutData
import forage.core.importer.WatchEvent
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopModelTest {
    private fun id(s: String) = PlatformId.youtube(s)

    /** Answers every video as gone, noting what the status bar said while it was asked. */
    private val backgroundDuringFetch = ArrayList<String?>()
    private val offline = object : ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<Capability>()
        override fun video(id: PlatformId): VideoDetails {
            backgroundDuringFetch += model.background
            throw SourceException.NotAvailable("offline")
        }
        override fun search(query: String): List<VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
        override fun parseLink(text: String) =
            text.removePrefix("https://www.youtube.com/watch?v=").takeIf { it.length == 11 && "/" !in it }?.let(PlatformId::youtube)
    }

    private val dir = Files.createTempDirectory("forage-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Unconfined dispatchers run every action to completion before it returns.
    private val model: DesktopModel = DesktopModel(
        dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    ) { offline }.apply {
        library.importTakeout(
            TakeoutData(
                ImportResult(
                    listOf(
                        WatchEvent(id("a"), "Hand-cut dovetails", null, "Workshop Wren", Instant.parse("2026-11-01T10:00:00Z")),
                        WatchEvent(id("b"), "Sourdough basics", null, "Brotzeit", Instant.parse("2026-11-02T10:00:00Z")),
                    ),
                    emptyMap(),
                ),
                mapOf("Watch later" to listOf(PlaylistEntry(id("c"), null))),
                emptyList(),
            )
        )
        runBlocking { reload() }
    }

    @Test
    fun `search results can be liked, and unliked again`() {
        val searchable = object : ExtractorSource by offline {
            override val capabilities = setOf(Capability.SEARCH)
            override fun forPlatform(platform: String) = takeIf { platform == "youtube" }
            override fun search(query: String) = listOf(
                VideoStub(id("sssssssss01"), "Found $query", null, "Someone", 600, false),
                VideoStub(id("a"), "Already watched", null, null, null, false),
            )
        }
        val m = DesktopModel(Files.createTempDirectory("forage-search").toFile(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { searchable }
        try {
            assertEquals(listOf("youtube" to "youtube"), m.searchPlatforms)
            m.searchVideos("  dovetails ")
            assertEquals(listOf("Found dovetails", "Already watched"), m.searchResults.map { it.title })
            assertEquals(emptySet(), m.searchLiked)
            m.likeFromSearch(m.searchResults.first())
            assertEquals(setOf(id("sssssssss01")), m.searchLiked)
            assertEquals(listOf(id("sssssssss01")), m.profiles.entries(m.profile, VideoState.LIKED).map { it.video })
            assertEquals("Found dovetails", m.library.video(id("sssssssss01"))?.title)
            m.likeFromSearch(m.searchResults.first())
            assertEquals(emptySet(), m.searchLiked)
            assertEquals(emptyList(), m.profiles.entries(m.profile))
            m.closeAddDialog()
            assertEquals(emptyList(), m.searchResults)
        } finally {
            m.close()
        }
    }

    @Test
    fun `several links can be added at once, and anything else is refused`() {
        assertEquals("That doesn't look like a link to a video on youtube", model.addByLink("https://example.com/x"))
        val refused = model.addByLink("https://www.youtube.com/watch?v=aaaaaaaaaa1\nnot-a-link")
        assertEquals("1 of these aren't video links Forage knows, e.g. not-a-link", refused)
        assertNull(model.addByLink(" https://www.youtube.com/watch?v=aaaaaaaaaa1\n\nhttps://www.youtube.com/watch?v=bbbbbbbbbb2 "))
        val liked = model.profiles.entries(model.profile, VideoState.LIKED).map { it.video.id }
        assertEquals(setOf("aaaaaaaaaa1", "bbbbbbbbbb2"), liked.toSet())
        assertEquals("Liked 2 videos; 2 still need details (Fetch details)", model.message)
        assertEquals(LibrarySource.Liked, model.currentSource)
    }

    @Test
    fun `lists the history and filters it by text and state`() {
        assertEquals(listOf("Liked", "History", "Watch later", "Not interested"), model.sources.map { it.label })
        assertEquals(LibrarySource.Liked, model.currentSource)
        assertEquals(emptyList(), model.rows)
        model.selectSource(LibrarySource.History)
        // Most recently watched first.
        assertEquals(listOf("b", "a"), model.rows.map { it.id.id })
        model.sortHistory(HistorySort.MOST_WATCHED)
        assertEquals(setOf("a", "b"), model.rows.map { it.id.id }.toSet())
        model.query = "wren"
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
        model.query = ""
        model.stateFilter = StateFilter.LIKED
        assertEquals(emptyList(), model.visibleRows)
        // The filter is for mixed lists only.
        model.selectSource(LibrarySource.Liked)
        model.mark(listOf(id("a")), VideoState.LIKED)
        model.stateFilter = StateFilter.NOT_INTERESTED
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
    }

    @Test
    fun `bulk actions like videos, fetch their details and clear the selection`() {
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        assertEquals(setOf(id("a"), id("b")), model.selection)
        model.markSelected(VideoState.LIKED)
        assertEquals(emptySet(), model.selection)
        assertEquals(2, model.profileSize)
        assertTrue(model.rows.all { it.state == VideoState.LIKED })
        assertEquals("Liked 2 videos", model.message)
        // A like counts at once; the details follow in the background.
        assertEquals(listOf<String?>("Fetching details of 2 videos", "Fetching details of 2 videos"), backgroundDuringFetch)

        model.toggle(id("b"))
        model.markSelected(VideoState.NOT_INTERESTED)
        assertEquals(VideoState.NOT_INTERESTED, model.rows.single { it.id == id("b") }.state)
        model.selectSource(LibrarySource.Liked)
        assertEquals(listOf(id("a")), model.rows.map { it.id })
        model.selectSource(LibrarySource.NotInterested)
        assertEquals(listOf(id("b")), model.rows.map { it.id })
        assertEquals(1, model.notInterestedSize)
    }

    @Test
    fun `playlists are made, filled, renamed, emptied and deleted`() {
        assertNull(model.createPlaylist("  Bread  "))
        assertEquals(LibrarySource.Playlist("Bread", 0), model.currentSource)
        assertTrue("already" in model.createPlaylist("Bread")!!)
        model.openAddToPlaylist(listOf(id("b"), id("a")))
        assertEquals(LibraryDialog.AddToPlaylist(listOf(id("b"), id("a"))), model.libraryDialog)
        assertNull(model.addToPlaylist("Bread", listOf(id("b"), id("a"))))
        assertNull(model.libraryDialog)
        assertEquals("Added 2 videos to \"Bread\"", model.message)
        assertEquals(LibrarySource.Playlist("Bread", 2), model.currentSource)
        assertEquals(setOf(id("a"), id("b")), model.rows.map { it.id }.toSet())

        assertTrue("already" in model.renamePlaylist("Bread", "Watch later")!!)
        assertNull(model.renamePlaylist("Bread", "Baking"))
        assertEquals(LibrarySource.Playlist("Baking", 2), model.currentSource)
        model.removeFromPlaylist(listOf(id("a")))
        assertEquals(listOf(id("b")), model.rows.map { it.id })
        model.deletePlaylist("Baking")
        assertEquals(LibrarySource.Liked, model.currentSource)
        assertEquals(listOf("Liked", "History", "Watch later", "Not interested"), model.sources.map { it.label })
        assertEquals("Sourdough basics", model.library.video(id("b"))?.title)
    }

    @Test
    fun `the history forgets videos, clears and pauses`() {
        model.selectSource(LibrarySource.History)
        model.forget(listOf(id("a")))
        assertEquals(listOf(id("b")), model.rows.map { it.id })
        model.clearHistory()
        assertEquals(emptyList(), model.rows)
        // The playlist is untouched.
        assertEquals(1L, model.library.playlists()["Watch later"])
        assertTrue(model.recordHistory)
        model.changeRecordHistory(false)
        assertEquals(false, Settings(dir.resolve("settings.properties")).recordHistory)
    }

    @Test
    fun `playlists show untitled videos until their details are fetched`() {
        model.selectSource(model.sources.single { it is LibrarySource.Playlist })
        val row = model.rows.single()
        assertEquals(id("c"), row.id)
        assertEquals(null, row.title)
    }

    @Test
    fun `feedback buttons toggle and show in the pick list`() {
        val pick = StoredPick(1, id("p"), "A pick", null, "Channel", 0.5f, "similar to X", "related to X", false)
        model.profiles.savePicks(model.profile, listOf(pick))
        runBlocking { model.reload() }
        val view = model.picks.single()
        model.feedback(view, Feedback.SAVED)
        model.feedback(model.picks.single(), Feedback.LIKE)
        assertEquals(setOf(Feedback.SAVED, Feedback.LIKE), model.picks.single().feedback)
        assertEquals(1, model.profileSize)
        assertEquals(listOf(id("p")), model.later.map { it.id })
        assertEquals("A pick", model.later.single().title)

        model.feedback(model.picks.single(), Feedback.LIKE)
        model.removeFromLater(id("p"))
        assertEquals(emptySet(), model.picks.single().feedback)
    }

    @Test
    fun `feedback fetches the video's details in the background and says so`() {
        val pick = StoredPick(1, id("p"), "A pick", null, "Channel", 0.5f, "similar to X", "related to X", false)
        model.profiles.savePicks(model.profile, listOf(pick))
        runBlocking { model.reload() }
        model.feedback(model.picks.single(), Feedback.NOT_INTERESTED)
        assertEquals(listOf<String?>("Fetching the video's details"), backgroundDuringFetch)
        assertNull(model.background)
        // Saving needs no details, so nothing is fetched.
        model.feedback(model.picks.single(), Feedback.SAVED)
        assertEquals(1, backgroundDuringFetch.size)
    }

    @Test
    fun `the queue sorts, keeps the pick's details and marks videos watched`() {
        fun pick(n: Int, v: String, seconds: Long?) =
            StoredPick(n, id(v), "Video $v", null, "Channel", 0.5f, "similar to X", "related to X", false, durationSeconds = seconds)
        model.profiles.savePicks(model.profile, listOf(pick(1, "long", 3600), pick(2, "short", 60), pick(3, "unknown", null)))
        runBlocking { model.reload() }
        model.picks.forEach { model.feedback(it, Feedback.SAVED) }
        model.laterSort = LaterSort.SHORTEST
        assertEquals(listOf("short", "long", "unknown"), model.sortedLater.map { it.id.id })
        assertEquals(3600L, model.later.single { it.id == id("long") }.durationSeconds)

        model.markWatched(model.later.single { it.id == id("short") })
        assertEquals(setOf(id("long"), id("unknown")), model.later.map { it.id }.toSet())
        assertTrue(id("short") in model.library.knownVideoIds())
    }

    @Test
    fun `switching profiles shows each profile's own decisions and remembers the choice`() {
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        model.markSelected(VideoState.LIKED)
        assertEquals("Default", model.profile.name)
        assertTrue("already" in model.createProfile("Default")!!)
        assertTrue(model.createProfile("  ")!!.isNotEmpty())

        assertEquals(null, model.createProfile("Evening"))
        assertEquals("Evening", model.profile.name)
        assertEquals(0, model.profileSize)
        assertTrue(model.rows.all { it.state == null })
        assertEquals(listOf("Default" to 2, "Evening" to 0), model.profileList.map { it.first.name to it.second })
        assertEquals("Evening", model.settings.lastProfile)

        model.switchProfile(model.profileList.first().first)
        assertEquals(2, model.profileSize)
    }

    @Test
    fun `deleting a profile switches to another and keeps the library`() {
        model.selectSource(LibrarySource.History)
        model.createProfile("Evening")
        model.deleteProfile()
        assertEquals("Default", model.profile.name)
        assertEquals(listOf("Default"), model.profileList.map { it.first.name })
        assertEquals(2, model.rows.size)
        // The last profile can't be deleted.
        model.deleteProfile()
        assertEquals(listOf("Default"), model.profileList.map { it.first.name })
    }

    @Test
    fun `a background task failing with an Error shows a message instead of crashing`() {
        val broken = object : ExtractorSource by offline {
            override fun video(id: PlatformId): VideoDetails = throw AssertionError("native library missing")
        }
        val other = DesktopModel(dir.resolve("other"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { broken }
        other.profiles.savePicks(other.profile, listOf(StoredPick(1, id("p"), "A pick", null, null, 0.5f, "r", "f", false)))
        runBlocking { other.reload() }
        other.feedback(other.picks.single(), Feedback.LIKE)
        assertTrue("native library missing" in other.message.orEmpty(), other.message)
        assertEquals(setOf(Feedback.LIKE), other.picks.single().feedback)
    }

    @Test
    fun `renaming a topic changes its label and can be undone`() {
        val topic = forage.core.recommend.Cluster(
            0, listOf(forage.core.recommend.ProfileVideo(id("a"), "A", null, floatArrayOf(1f))), floatArrayOf(1f), listOf("cube", "cubing"),
        )
        model.topics = listOf(topic)

        model.renamingTopic = 0
        model.renameTopic(topic, "  Speedcubing  ")
        assertEquals(null, model.renamingTopic)
        assertEquals("Speedcubing", model.topics.single().label)
        model.renameTopic(model.topics.single(), "")
        assertEquals("cube, cubing", model.topics.single().label)
    }

    @Test
    fun `closing folds every change into the database file`() {
        val folder = dir.resolve("closing")
        val plain = DesktopModel(folder, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { offline }
        plain.library.remember(id("z"), "Written just before closing")
        plain.close()
        val wal = folder.resolve("forage.db-wal")
        assertTrue(!wal.exists() || wal.length() == 0L, "pending changes left in ${wal.name}")
        // A fresh open of the file alone sees the change.
        assertEquals("Written just before closing", forage.core.library.LibraryStore(DesktopStorage.openDatabase(folder.resolve("forage.db"))).video(id("z"))?.title)
    }

    @Test
    fun `the interface size is remembered, and automatic when unset`() {
        assertEquals(null, model.uiScale)
        model.changeUiScale(1.5f)
        assertEquals(1.5f, model.effectiveUiScale)
        assertEquals(1.5f, Settings(dir.resolve("settings.properties")).uiScale)
        model.changeUiScale(null)
        assertEquals(null, Settings(dir.resolve("settings.properties")).uiScale)
    }

    @Test
    fun `discover on an empty profile explains what to do`() {
        model.discover()
        assertTrue("Nothing liked yet" in model.message!!, model.message)
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        model.markSelected(VideoState.LIKED)
        model.discover()
        assertTrue("have details yet" in model.message!!, model.message)
        assertEquals(null, model.job)
    }
}
