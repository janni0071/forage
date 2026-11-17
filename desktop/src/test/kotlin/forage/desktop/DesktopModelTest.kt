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
import kotlin.test.assertTrue

class DesktopModelTest {
    private fun id(s: String) = PlatformId.youtube(s)

    /** Never reached in these tests: everything here works offline. */
    private val offline = object : ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<Capability>()
        override fun video(id: PlatformId): VideoDetails = throw SourceException.NotAvailable("offline")
        override fun search(query: String): List<VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
    }

    private val dir = Files.createTempDirectory("forage-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Unconfined dispatchers run every action to completion before it returns.
    private val model = DesktopModel(
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
