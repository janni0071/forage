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
 * videos from every cluster, plus searches built from each cluster's keywords.
 * Channel-collaboration candidates are not implemented yet.
 */
class CandidateGenerator(
    private val cache: MetadataCache,
    /**
     * Used for searches, per platform through [ExtractorSource.forPlatform]. Null, or platforms
     * without [Capability.SEARCH], means related videos only.
     */
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
    /** On platforms without related videos: profile videos per topic searched for by their tags. */
    private val tagSearchesPerCluster: Int = 3,
    private val tagSearchesVaried: Int = 6,
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

        // Muted topics supply nothing; pinned ones twice the usual sample, so they have enough to fill their slots.
        val active = clusters.filter { it.preference != TopicPreference.MUTED }
        for (cluster in active) {
            val sample = when {
                cluster.isVaried -> sampleVaried
                cluster.preference == TopicPreference.PINNED -> samplePerCluster * 2
                else -> samplePerCluster
            }
            for (video in cluster.videos.shuffled(random).take(sample)) {
                cache.video(video.id)?.related?.forEach { add(it, CandidateSource.RelatedTo(video)) }
            }
        }

        if (source != null) {
            val searches = plannedSearches(active, keywordsPerSearch)
            // Results are merged in plan order afterwards, so the outcome doesn't depend on which search finished first.
            search(searches.map { it.source to it.query }).forEachIndexed { i, results ->
                results.forEach { add(it, CandidateSource.Search(searches[i].query, searches[i].cluster)) }
            }
        }
        return found.values.toList()
    }

    private data class PlannedSearch(val source: ExtractorSource, val query: String, val cluster: Int)

    /**
     * One search per topic from its keywords, on the platform most of the topic's videos come from.
     * A platform without related videos gets more: a second keyword search per topic, and searches
     * for a sample of profile videos by their own tags, which stand in for their related videos.
     */
    private fun plannedSearches(clusters: List<Cluster>, keywordsPerSearch: Int): List<PlannedSearch> {
        val planned = LinkedHashMap<Pair<String, String>, PlannedSearch>()
        fun plan(source: ExtractorSource, words: List<String>, cluster: Int) {
            val query = words.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (query.isNotEmpty()) planned.putIfAbsent(source.platform to query, PlannedSearch(source, query, cluster))
        }
        // How many profile videos carry each tag, per platform, to find each video's telling tags.
        val tagsOf = HashMap<PlatformId, List<String>>()
        val tagCounts = HashMap<String, HashMap<String, Int>>()
        for (video in clusters.flatMap { it.videos }.distinctBy { it.id }) {
            val tags = cache.video(video.id)?.metadata?.tags.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            tagsOf[video.id] = tags
            val counts = tagCounts.getOrPut(video.id.platform) { HashMap() }
            tags.forEach { counts.merge(it, 1, Int::plus) }
        }
        for (cluster in clusters) {
            val platform = cluster.videos.groupingBy { it.id.platform }.eachCount().maxByOrNull { it.value }?.key ?: continue
            val source = source?.forPlatform(platform)?.takeIf { Capability.SEARCH in it.capabilities } ?: continue
            val withoutRelated = Capability.RELATED_VIDEOS !in source.capabilities
            // Keywords that are whole tags make long queries; two at a time still find enough.
            val perQuery = if (Capability.DESCRIPTIVE_TAGS in source.capabilities) 2 else keywordsPerSearch
            // The varied cluster has no common subject to search for.
            if (!cluster.isVaried) {
                cluster.keywords.chunked(perQuery).take(if (withoutRelated) 2 else 1)
                    .forEach { plan(source, it, cluster.index) }
            }
            if (withoutRelated) {
                val sample = if (cluster.isVaried) tagSearchesVaried else tagSearchesPerCluster
                val counts = tagCounts[platform].orEmpty()
                val tagged = tagsOf.count { (id, tags) -> id.platform == platform && tags.isNotEmpty() }
                cluster.videos.filter { it.id.platform == platform }.shuffled(random).take(sample).forEach { video ->
                    plan(source, tellingTags(tagsOf[video.id].orEmpty(), counts, tagged).take(2), cluster.index)
                }
            }
        }
        return planned.values.toList()
    }

    /**
     * A video's tags that say most about the user's taste: ones it shares with a few other profile
     * videos, rarest first, then its own unique ones. Tags on most of the profile come last, since
     * a search for them finds the site's generic fare.
     */
    private fun tellingTags(tags: List<String>, counts: Map<String, Int>, tagged: Int): List<String> {
        fun rank(tag: String): Pair<Int, Int> {
            val n = counts[tag] ?: 1
            return when {
                n * 2 > tagged && tagged >= 4 -> 2 to n
                n >= 2 -> 0 to n
                else -> 1 to n
            }
        }
        return tags.withIndex().sortedWith(compareBy<IndexedValue<String>>({ rank(it.value).first }, { rank(it.value).second }, { it.index }))
            .map { it.value }
    }

    private fun search(queries: List<Pair<ExtractorSource, String>>): List<List<VideoStub>> {
        val results = arrayOfNulls<List<VideoStub>>(queries.size)
        val pending = ArrayList<Int>()
        queries.forEachIndexed { i, (source, q) ->
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
                        results[i] = searchWithRetries(queries[i].first, queries[i].second, first = n < parallelSearches)
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
