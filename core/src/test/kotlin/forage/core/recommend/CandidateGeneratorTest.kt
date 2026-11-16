package forage.core.recommend

import forage.core.PlatformId
import forage.core.metadata.MetadataCache
import forage.core.metadata.details
import forage.core.metadata.memoryDatabase
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CandidateGeneratorTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private val cache = MetadataCache(memoryDatabase()).apply {
        put(details("a", related = listOf("rel1", "rel2")))
        put(details("b", related = listOf("rel2", "seen")))
    }
    private val clusters = listOf(
        Cluster(
            0,
            listOf(ProfileVideo(id("a"), "A", null, floatArrayOf(1f)), ProfileVideo(id("b"), "B", null, floatArrayOf(1f))),
            floatArrayOf(1f),
            listOf("sourdough", "bread"),
        )
    )

    /** Fails the first [failures] searches, then returns one hit. */
    private class SearchOnly(private var failures: Int) : ExtractorSource {
        val queries = ArrayList<String>()
        override val platform = "youtube"
        override val capabilities = setOf(Capability.SEARCH)
        override fun video(id: PlatformId): VideoDetails = error("not used")
        override fun search(query: String): List<VideoStub> {
            queries += query
            if (failures-- > 0) throw SourceException.Blocked("bot check")
            return listOf(VideoStub(PlatformId.youtube("hit"), "Hit", null, null, null, false))
        }
        override fun watchUrl(id: PlatformId) = ""
    }

    @Test
    fun `collects related videos once, remembers every source and drops excluded ones`() {
        val found = CandidateGenerator(cache).generate(clusters, exclude = setOf(id("seen")))
        assertEquals(setOf("rel1", "rel2"), found.map { it.stub.id.id }.toSet())
        val rel2 = found.single { it.stub.id.id == "rel2" }
        assertEquals(setOf("A", "B"), rel2.sources.map { (it as CandidateSource.RelatedTo).video.title }.toSet())
    }

    @Test
    fun `searches each cluster by its keywords and retries a failed search`() {
        val source = SearchOnly(failures = 1)
        val sleeps = ArrayList<Duration>()
        val generator = CandidateGenerator(cache, source, sleep = { sleeps += it })
        val hit = generator.generate(clusters, emptySet()).single { it.stub.id.id == "hit" }
        assertIs<CandidateSource.Search>(hit.sources.single())
        assertEquals(listOf("sourdough bread", "sourdough bread"), source.queries)
        assertEquals(1, sleeps.size)
        assertEquals(emptyList(), generator.searchFailures)
    }

    @Test
    fun `a search that keeps failing is reported and skipped`() {
        val generator = CandidateGenerator(cache, SearchOnly(failures = 99), sleep = {})
        assertEquals(2, generator.generate(clusters, setOf(id("seen"))).size)
        assertEquals(1, generator.searchFailures.size)
    }
}
