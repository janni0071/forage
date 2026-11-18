package forage.desktop

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
    fun `several links can be added at once, and anything else is refused`() {
        assertEquals("That doesn't look like a link to a video on youtube", model.addByLink("https://example.com/x"))
        val refused = model.addByLink("https://www.youtube.com/watch?v=aaaaaaaaaa1\nnot-a-link")
        assertEquals("1 of these aren't video links Forage knows, e.g. not-a-link", refused)
        assertNull(model.addByLink(" https://www.youtube.com/watch?v=aaaaaaaaaa1\n\nhttps://www.youtube.com/watch?v=bbbbbbbbbb2 "))
        val included = model.profiles.entries(model.profile, VideoState.INCLUDED).map { it.video.id }
        assertEquals(setOf("aaaaaaaaaa1", "bbbbbbbbbb2"), included.toSet())
        assertEquals("Added 2 videos; 2 still need details (Fetch profile details)", model.message)
    }

    @Test
    fun `lists the history and filters it by text and state`() {
        assertEquals(setOf("a", "b"), model.rows.map { it.id.id }.toSet())
        assertEquals(listOf("Watch history", "Playlist: Watch later", "Decided in this profile"), model.sources.map { it.label })
        model.query = "wren"
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
        model.query = ""
        model.stateFilter = StateFilter.INCLUDED
        assertEquals(emptyList(), model.visibleRows)
    }

    @Test
    fun `bulk actions change the profile and clear the selection`() {
        model.selectVisible()
        assertEquals(setOf(id("a"), id("b")), model.selection)
        model.markSelected(VideoState.INCLUDED)
        assertEquals(emptySet(), model.selection)
        assertEquals(2, model.profileSize)
        assertTrue(model.rows.all { it.state == VideoState.INCLUDED })
        assertTrue("need details" in model.message!!)

        model.toggle(id("b"))
        model.markSelected(VideoState.NEGATIVE)
        assertEquals(VideoState.NEGATIVE, model.rows.single { it.id == id("b") }.state)
        model.selectSource(model.sources.single { it is LibrarySource.InProfile })
        assertEquals(setOf(id("a"), id("b")), model.rows.map { it.id }.toSet())
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
        model.feedback(model.picks.single(), Feedback.MORE)
        assertEquals(setOf(Feedback.SAVED, Feedback.MORE), model.picks.single().feedback)
        assertEquals(listOf(id("p")), model.later.map { it.id })
        assertEquals("A pick", model.later.single().title)

        model.feedback(model.picks.single(), Feedback.MORE)
        model.removeFromLater(id("p"))
        assertEquals(emptySet(), model.picks.single().feedback)
    }

    @Test
    fun `feedback fetches the video's details in the background and says so`() {
        val pick = StoredPick(1, id("p"), "A pick", null, "Channel", 0.5f, "similar to X", "related to X", false)
        model.profiles.savePicks(model.profile, listOf(pick))
        runBlocking { model.reload() }
        model.feedback(model.picks.single(), Feedback.NOT_FOR_ME)
        assertEquals(listOf<String?>("Fetching details of \"A pick\" so your feedback counts"), backgroundDuringFetch)
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
        model.selectVisible()
        model.markSelected(VideoState.INCLUDED)
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
        other.feedback(other.picks.single(), Feedback.MORE)
        assertTrue("native library missing" in other.message.orEmpty(), other.message)
        assertEquals(setOf(Feedback.MORE), other.picks.single().feedback)
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
        assertEquals("Written just before closing", forage.core.library.LibraryStore(DesktopModel.openDatabase(folder.resolve("forage.db"))).video(id("z"))?.title)
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
        assertTrue("no videos yet" in model.message!!, model.message)
        model.selectVisible()
        model.markSelected(VideoState.INCLUDED)
        model.discover()
        assertTrue("have details yet" in model.message!!, model.message)
        assertEquals(null, model.job)
    }
}
