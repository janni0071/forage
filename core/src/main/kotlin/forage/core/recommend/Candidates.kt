package forage.core.recommend

import forage.core.PlatformId
import forage.core.metadata.MetadataCache
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoStub
import java.time.Duration
import kotlin.random.Random

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
    /** A search is tried this often before it is skipped. */
    private val searchAttempts: Int = 3,
    private val pause: Duration = Duration.ofSeconds(5),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    /** What went wrong with searches, for the caller to report. Candidate generation itself never fails on them. */
    val searchFailures = ArrayList<String>()

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

        var searched = false
        if (source != null && Capability.SEARCH in source.capabilities) {
            for (cluster in clusters) {
                val query = cluster.keywords.take(keywordsPerSearch).joinToString(" ")
                // The varied cluster has no common subject to search for.
                if (cluster.isVaried || query.isBlank()) continue
                var failure: SourceException? = null
                for (attempt in 1..searchAttempts) {
                    // Pause before every search but the very first, and longer after each failure.
                    if (searched) sleep(pause.multipliedBy(attempt.toLong()))
                    searched = true
                    try {
                        source.search(query).forEach { add(it, CandidateSource.Search(query, cluster.index)) }
                        failure = null
                        break
                    } catch (e: SourceException) {
                        failure = e
                    }
                }
                failure?.let { searchFailures += "'$query': ${it.message}" }
            }
        }
        return found.values.toList()
    }
}
