package forage.core.recommend

import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.embedding.EmbeddingStore
import forage.core.embedding.Vectors
import forage.core.library.LibraryStore
import forage.core.metadata.MetadataCache
import forage.core.metadata.details
import forage.core.metadata.memoryDatabase
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverTest {
    /** Puts every text on one of two axes, by whether it mentions "rel1". */
    private val embedder = object : Embedder {
        override val modelId = "fake"
        override val dimensions = 2
        override fun embed(texts: List<String>) =
            texts.map { Vectors.normalize(if ("rel1" in it) floatArrayOf(1f, 0.2f) else floatArrayOf(0.2f, 1f)) }
    }
    private fun id(s: String) = PlatformId.youtube(s)
    private val db = memoryDatabase()
    private val cache = MetadataCache(db)
    private val library = LibraryStore(db)
    private val profiles = ProfileStore(db)
    private val main = profiles.profile()
    private val discover = Discover(cache, library, profiles, EmbeddingStore(db, embedder), config = RankingConfig(explorationSlots = 0))

    @Test
    fun `recommends related videos of the profile and stores the batch`() {
        cache.put(details("a", related = listOf("rel1", "rel2", "seen", "rejected")))
        profiles.mark(main, listOf(id("a"), id("unfetched")), VideoState.LIKED)
        profiles.notInterested(main, id("rejected"))
        library.saveForLater(id("seen"))

        val result = discover.run(main)
        // rel2 is flagged as a Short in the fixture, so only rel1 remains.
        assertEquals(listOf("rel1"), result.picks.map { it.candidate.id.id })
        assertEquals(2, result.withoutMetadata)
        val stored = profiles.picks(main).single()
        assertEquals(id("rel1"), stored.video)
        assertEquals(1, stored.position)
        assertTrue(stored.foundVia.startsWith("related to"))
        assertFalse(stored.exploration)
    }

    @Test
    fun `an empty or unfetched profile is reported, not recommended from`() {
        assertTrue("no videos" in assertFailsWith<EmptyProfileException> { discover.run(main) }.message!!)
        profiles.mark(main, listOf(id("unfetched")), VideoState.LIKED)
        assertTrue("metadata" in assertFailsWith<EmptyProfileException> { discover.run(main) }.message!!)
    }

    @Test
    fun `profiles in one vault get separate recommendations`() {
        cache.put(details("a", related = listOf("rel1")))
        cache.put(details("b", related = listOf("rel3")))
        val other = profiles.profile("Evening")
        profiles.mark(main, listOf(id("a")), VideoState.LIKED)
        profiles.mark(other, listOf(id("b")), VideoState.LIKED)
        assertEquals(listOf("rel1"), discover.run(main).picks.map { it.candidate.id.id })
        assertEquals(listOf("rel3"), discover.run(other).picks.map { it.candidate.id.id })
        assertEquals(listOf(id("rel1")), profiles.picks(main).map { it.video })
    }
}
