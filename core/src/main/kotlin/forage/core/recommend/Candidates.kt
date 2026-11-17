package forage.core.recommend

import forage.core.PlatformId
import forage.core.metadata.MetadataCache
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoStub
import java.time.Duration
import kotlin.random.Random
import java.util.concurrent.Executors
import forage.core.metadata.SearchCache

/** Where a candidate came from; shown as part of the reason for a pick. */
sealed interface CandidateSource {
    data class RelatedTo(val video: ProfileVideo) : CandidateSource
    data class Search(val query: String, val cluster: Int) : CandidateSource
}

data class CandidateStub(val stub: VideoStub, val sources: List<CandidateSource>)

/**
 * Collects videos worth scoring (SPEC, Candidates): the related videos of a sample of profile
 * videos from every cluster, plus one search per cluster built from its keywords.
 * Channel-collaboration candidates are not implemented yet.
 */
class CandidateGenerator(
    private val cache: MetadataCache,
    /** Used for searches. Null, or a source without [Capability.SEARCH], means related videos only. */
    private val source: ExtractorSource? = null,
    private val random: Random = Random(1),
    /** Recent results of the same search are reused instead of asking again. */
    private val searchCache: SearchCache? = null,
    /** A search is tried this often before it is skipped. */
    private val searchAttempts: Int = 3,
    /** Searches running at the same time. A few at once is still polite and much faster than one by one. */
    private val parallelSearches: Int = 3,
    /** Wait before each further search on the same worker, and the base of the backoff after a failure. */
    private val pause: Duration = Duration.ofSeconds(1),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    /** What went wrong with searches, for the caller to report. Candidate generation itself never fails on them. */
    val searchFailures: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    fun generate(
        clusters: List<Cluster>,
        /** Videos never to suggest: watched, excluded, already saved, or in the profile. */
        exclude: Set<PlatformId>,
        /** Profile videos sampled per topic, so a big topic can't supply most candidates. */
        samplePerCluster: Int = 8,
        /** The varied cluster holds many unrelated interests, so it gets a larger sample. */
        sampleVaried: Int = 24,
        keywordsPerSearch: Int = 3,
    ): List<CandidateStub> {
        val found = LinkedHashMap<PlatformId, CandidateStub>()
        fun add(stub: VideoStub, from: CandidateSource) {
            if (stub.id in exclude) return
            found.merge(stub.id, CandidateStub(stub, listOf(from))) { old, new -> old.copy(sources = old.sources + new.sources) }
        }

        for (cluster in clusters) {
            for (video in cluster.videos.shuffled(random).take(if (cluster.isVaried) sampleVaried else samplePerCluster)) {
                cache.video(video.id)?.related?.forEach { add(it, CandidateSource.RelatedTo(video)) }
            }
        }

        if (source != null && Capability.SEARCH in source.capabilities) {
            // The varied cluster has no common subject to search for.
            val searches = clusters.filter { !it.isVaried }
                .map { it to it.keywords.take(keywordsPerSearch).joinToString(" ") }
                .filter { it.second.isNotBlank() }
            // Results are merged in topic order afterwards, so the outcome doesn't depend on which search finished first.
            search(searches.map { it.second }).forEachIndexed { i, results ->
                val (cluster, query) = searches[i]
                results.forEach { add(it, CandidateSource.Search(query, cluster.index)) }
            }
        }
        return found.values.toList()
    }

    private fun search(queries: List<String>): List<List<VideoStub>> {
        val source = source!!
        val results = arrayOfNulls<List<VideoStub>>(queries.size)
        val pending = ArrayList<Int>()
        queries.forEachIndexed { i, q ->
            val cached = searchCache?.get(source.platform, q)
            if (cached != null) results[i] = cached else pending += i
        }
        if (pending.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(parallelSearches.coerceIn(1, pending.size))
            try {
                pending.mapIndexed { n, i ->
                    pool.submit {
                        // Stagger the first wave so the searches don't all start in the same instant.
                        if (n in 1 until parallelSearches) sleep(pause.multipliedBy(n.toLong()).dividedBy(parallelSearches.toLong()))
                        results[i] = searchWithRetries(source, queries[i], first = n < parallelSearches)
                    }
                }.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        return results.map { it.orEmpty() }
    }

    private fun searchWithRetries(source: ExtractorSource, query: String, first: Boolean): List<VideoStub>? {
        var failure: SourceException? = null
        for (attempt in 1..searchAttempts) {
            if (attempt > 1 || !first) sleep(pause.multipliedBy(if (attempt > 1) 5L * (attempt - 1) else 1L))
            try {
                return source.search(query).also { searchCache?.put(source.platform, query, it) }
            } catch (e: SourceException) {
                failure = e
            }
        }
        searchFailures += "'$query': ${failure?.message}"
        return null
    }
}
