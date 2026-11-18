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
import kotlin.test.assertTrue
import forage.core.metadata.SearchCache

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
        override val capabilities = setOf(Capability.RELATED_VIDEOS, Capability.SEARCH)
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
        assertEquals(listOf(Duration.ofSeconds(5)), sleeps)
        assertEquals(emptyList(), generator.searchFailures)
    }

    @Test
    fun `searches run once a day and later batches reuse the cached results`() {
        val searchCache = SearchCache(memoryDatabase())
        val source = SearchOnly(failures = 0)
        repeat(2) { CandidateGenerator(cache, source, searchCache = searchCache, sleep = {}).generate(clusters, emptySet()) }
        assertEquals(listOf("sourdough bread"), source.queries)
        val hit = CandidateGenerator(cache, source, searchCache = searchCache, sleep = {}).generate(clusters, emptySet())
            .single { it.stub.id.id == "hit" }
        assertEquals("Hit", hit.stub.title)
    }

    @Test
    fun `many searches run in parallel and merge in topic order`() {
        val topics = (0 until 6).map { i -> clusters[0].copy(index = i, keywords = listOf("topic$i")) }
        val source = object : ExtractorSource by SearchOnly(0) {
            // Delegation would hand back the wrapped source.
            override fun forPlatform(platform: String) = this
            override fun search(query: String): List<VideoStub> {
                Thread.sleep(if (query == "topic0") 300 else 10)
                return listOf(VideoStub(PlatformId.youtube("v-$query"), query, null, null, null, false))
            }
        }
        val started = System.nanoTime()
        val found = CandidateGenerator(cache, source, sleep = {}).generate(topics, setOf(id("rel1"), id("rel2"), id("seen")))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 600, "searches should overlap")
        assertEquals((0 until 6).map { "topic$it" }, found.map { it.stub.title })
    }

    @Test
    fun `a search that keeps failing is reported and skipped`() {
        val generator = CandidateGenerator(cache, SearchOnly(failures = 99), sleep = {})
        assertEquals(2, generator.generate(clusters, setOf(id("seen"))).size)
        assertEquals(1, generator.searchFailures.size)
    }

    /** A platform with search only, like the private plugin's. */
    private class OtherPlatform : ExtractorSource {
        val queries = java.util.Collections.synchronizedList(ArrayList<String>())
        override val platform = "other"
        override val capabilities = setOf(Capability.SEARCH)
        override fun video(id: PlatformId): VideoDetails = error("not used")
        override fun search(query: String): List<VideoStub> {
            queries += query
            return listOf(VideoStub(PlatformId("other", "hit-${query.hashCode()}"), "Hit", null, null, null, false))
        }
        override fun watchUrl(id: PlatformId) = ""
    }

    @Test
    fun `a platform without related videos is searched by keywords and by its videos' tags`() {
        val o = { s: String -> PlatformId("other", s) }
        val cache = MetadataCache(memoryDatabase()).apply {
            put(details("x").let { it.copy(metadata = it.metadata.copy(id = o("x"), tags = listOf("t1", "t2", "t3", "t4")), related = emptyList()) })
            put(details("y").let { it.copy(metadata = it.metadata.copy(id = o("y"), tags = listOf("t5")), related = emptyList()) })
        }
        val topic = Cluster(
            0, listOf(ProfileVideo(o("x"), "X", null, floatArrayOf(1f)), ProfileVideo(o("y"), "Y", null, floatArrayOf(1f))),
            floatArrayOf(1f), listOf("k1", "k2", "k3", "k4", "k5", "k6", "k7"),
        )
        val varied = topic.copy(index = 1, keywords = emptyList(), isVaried = true)
        val other = OtherPlatform()
        val youtube = SearchOnly(0)
        val found = CandidateGenerator(cache, forage.core.source.Sources(youtube, other), sleep = {})
            .generate(listOf(topic, varied), emptySet())
        // Two keyword searches for the topic, and tag searches for its videos; the varied group repeats them, so they run once.
        assertEquals(setOf("k1 k2 k3", "k4 k5 k6", "t1 t2 t3", "t5"), other.queries.toSet())
        assertEquals(4, other.queries.size)
        assertEquals(emptyList(), youtube.queries)
        assertTrue(found.all { it.stub.id.platform == "other" })
    }
}
