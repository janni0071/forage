package forage.core.metadata

import forage.core.PlatformId
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MetadataFetcherTest {
    /** Answers each video from a scripted queue; the last answer repeats. */
    private class FakeSource(private val script: Map<String, List<() -> VideoDetails>>) : ExtractorSource {
        val requests = ArrayList<String>()
        override val platform = "youtube"
        override val capabilities = setOf(Capability.RELATED_VIDEOS)
        override fun video(id: PlatformId): VideoDetails {
            val answers = script.getValue(id.id)
            val n = requests.count { it == id.id }
            requests += id.id
            return answers[minOf(n, answers.lastIndex)]()
        }
        override fun search(query: String): List<VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = "https://example.invalid/${id.id}"
    }

    private fun ok(id: String) = { details(id) }
    private val blocked = { throw SourceException.Blocked("bot check") }
    private val gone = { throw SourceException.NotAvailable("removed") }

    private val sleeps = ArrayList<Duration>()
    private val cache = MetadataCache(memoryDatabase())
    private val policy = FetchPolicy(
        pause = Duration.ofSeconds(1), attempts = 3, firstBackoff = Duration.ofSeconds(10), stopAfterFailedVideos = 2,
    )
    private fun fetcher(source: ExtractorSource) = MetadataFetcher(source, cache, policy) { sleeps += it }
    private fun ids(vararg ids: String) = ids.map(PlatformId::youtube)

    @Test
    fun `fetches once, then serves from the cache`() {
        val source = FakeSource(mapOf("a" to listOf(ok("a"))))
        val fetcher = fetcher(source)
        assertIs<FetchOutcome.Fetched>(fetcher.fetch(PlatformId.youtube("a")))
        assertEquals(FetchOutcome.Cached(details("a")), fetcher.fetch(PlatformId.youtube("a")))
        assertEquals(listOf("a"), source.requests)
    }

    @Test
    fun `pauses between videos and backs off after a block`() {
        val source = FakeSource(mapOf("a" to listOf(ok("a")), "b" to listOf(blocked, blocked, ok("b"))))
        val summary = fetcher(source).fetchAll(ids("a", "b"))
        assertEquals(FetchSummary(cached = 0, fetched = 2, unavailable = 0, failed = 0, notAttempted = 0), summary)
        assertEquals(listOf(1L, 10L, 20L), sleeps.map { it.seconds })
    }

    @Test
    fun `remembers videos that are gone and never asks again`() {
        val source = FakeSource(mapOf("a" to listOf(gone)))
        val fetcher = fetcher(source)
        assertEquals(FetchOutcome.Unavailable("removed"), fetcher.fetch(PlatformId.youtube("a")))
        assertEquals(FetchOutcome.Unavailable("removed"), fetcher.fetch(PlatformId.youtube("a")))
        assertEquals(listOf("a"), source.requests)
    }

    @Test
    fun `stops a bulk run when videos keep failing, and resumes from the cache`() {
        val script = mutableMapOf("a" to listOf(ok("a")), "b" to listOf(blocked), "c" to listOf(blocked), "d" to listOf(ok("d")))
        val source = FakeSource(script)
        val first = fetcher(source).fetchAll(ids("a", "b", "c", "d"))
        assertEquals(FetchSummary(cached = 0, fetched = 1, unavailable = 0, failed = 2, notAttempted = 1), first)
        assertEquals(listOf("a") + List(3) { "b" } + List(3) { "c" }, source.requests)

        script["b"] = listOf(ok("b")); script["c"] = listOf(ok("c"))
        val second = fetcher(FakeSource(script)).fetchAll(ids("a", "b", "c", "d"))
        assertEquals(FetchSummary(cached = 1, fetched = 3, unavailable = 0, failed = 0, notAttempted = 0), second)
    }
}
