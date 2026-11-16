package forage.core.recommend

import forage.core.PlatformId
import forage.core.metadata.MetadataCache
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoStub
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
) {
    /** What went wrong with searches, for the caller to report. Candidate generation itself never fails on them. */
    val searchFailures = ArrayList<String>()

    fun generate(
        clusters: List<Cluster>,
        /** Videos never to suggest: watched, excluded, already saved, or in the profile. */
        exclude: Set<PlatformId>,
        /** Profile videos sampled per cluster, so a big cluster can't supply most candidates. */
        samplePerCluster: Int = 8,
        keywordsPerSearch: Int = 3,
    ): List<CandidateStub> {
        val found = LinkedHashMap<PlatformId, CandidateStub>()
        fun add(stub: VideoStub, from: CandidateSource) {
            if (stub.id in exclude) return
            found.merge(stub.id, CandidateStub(stub, listOf(from))) { old, new -> old.copy(sources = old.sources + new.sources) }
        }

        for (cluster in clusters) {
            for (video in cluster.videos.shuffled(random).take(samplePerCluster)) {
                cache.video(video.id)?.related?.forEach { add(it, CandidateSource.RelatedTo(video)) }
            }
        }

        if (source != null && Capability.SEARCH in source.capabilities) {
            for (cluster in clusters) {
                val query = cluster.keywords.take(keywordsPerSearch).joinToString(" ")
                if (query.isBlank()) continue
                try {
                    source.search(query).forEach { add(it, CandidateSource.Search(query, cluster.index)) }
                } catch (e: SourceException) {
                    searchFailures += "'$query': ${e.message}"
                }
            }
        }
        return found.values.toList()
    }
}
