package forage.core.profile

import forage.core.PlatformId
import forage.core.library.LibraryStore
import forage.core.metadata.memoryDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileStoreTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private val db = memoryDatabase()
    private val store = ProfileStore(db)
    private val main = store.profile()

    @Test
    fun `profiles are created on first use and found again`() {
        assertEquals("Default", main.name)
        assertEquals(main, store.profile())
        val evening = store.profile("Evening")
        assertEquals(listOf(main, evening), store.profiles())
    }

    @Test
    fun `marking sets, changes and clears a video's state`() {
        store.mark(main, listOf(id("a"), id("b")), VideoState.LIKED)
        store.mark(main, listOf(id("b")), VideoState.NOT_INTERESTED)
        assertEquals(listOf(id("a")), store.entries(main, VideoState.LIKED).map { it.video })
        assertEquals(listOf(id("b")), store.entries(main, VideoState.NOT_INTERESTED).map { it.video })
        store.mark(main, listOf(id("a")), null)
        assertEquals(listOf(id("b")), store.entries(main).map { it.video })
    }

    @Test
    fun `profiles never see each other's decisions`() {
        val evening = store.profile("Evening")
        store.mark(main, listOf(id("a")), VideoState.LIKED)
        store.notInterested(evening, id("a"))
        assertEquals(VideoState.LIKED, store.entries(main).single().state)
        assertEquals(VideoState.NOT_INTERESTED, store.entries(evening).single().state)
    }

    @Test
    fun `importing a history never puts anything into a profile`() {
        LibraryStore(db).remember(id("a"), "Watched video")
        assertEquals(emptyList(), store.entries(main))
    }

    @Test
    fun `feedback maps to the states the spec describes`() {
        store.like(main, id("a"))
        store.notInterested(main, id("b"))
        val entries = store.entries(main).associateBy { it.video }
        assertEquals(ProfileEntry(id("a"), VideoState.LIKED, 1f, "discover"), entries.getValue(id("a")).copy(changedAt = null))
        assertEquals(VideoState.NOT_INTERESTED, entries.getValue(id("b")).state)
    }

    @Test
    fun `a new batch of picks replaces the previous one`() {
        fun pick(n: Int, v: String) = StoredPick(
            n, id(v), "Title $v", null, "Channel", 0.5f, "similar to X", "related to X", n == 2,
            durationSeconds = 754, uploadedAt = java.time.Instant.parse("2026-10-01T00:00:00Z"),
        )
        store.savePicks(main, listOf(pick(1, "a"), pick(2, "b")))
        store.savePicks(main, listOf(pick(1, "c")))
        assertEquals(listOf(pick(1, "c")), store.picks(main))
    }
}

class ProfileDeleteTest {
    private fun id(s: String) = PlatformId.youtube(s)

    @Test
    fun `deleting a profile removes only its own data`() {
        val db = memoryDatabase()
        val store = ProfileStore(db)
        val main = store.profile()
        val evening = store.profile("Evening")
        store.mark(main, listOf(id("a")), VideoState.LIKED)
        store.mark(evening, listOf(id("b")), VideoState.LIKED)
        store.savePicks(evening, listOf(StoredPick(1, id("p"), "P", null, null, 0.5f, "r", "f", false)))
        LibraryStore(db).saveForLater(id("q"))

        store.delete(evening)
        assertEquals(listOf(main), store.profiles())
        assertEquals(null, store.find("Evening"))
        assertEquals(listOf(id("a")), store.entries(main).map { it.video })
        assertEquals(emptyList(), store.picks(evening))
        assertEquals(listOf(id("q")), LibraryStore(db).watchLater())
        // The name can be used again, for a fresh profile.
        assertEquals(emptyList(), store.entries(store.profile("Evening")))
    }
}
