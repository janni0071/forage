package forage.core.recommend

import forage.core.embedding.EmbeddingStore
import forage.core.embedding.EmbeddingText
import forage.core.language.LanguageDetector
import forage.core.language.LanguageFilter
import forage.core.library.LibraryStore
import forage.core.metadata.MetadataCache
import forage.core.profile.ProfileRef
import forage.core.profile.ProfileStore
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.source.ExtractorSource
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Everything one Discover visit produced, for the UI to show. */
data class DiscoverResult(
    val clusters: List<Cluster>,
    val picks: List<Pick>,
    val candidates: Int,
    /** Candidates dropped for being in a language the profile doesn't contain. */
    val foreignLanguage: Int,
    /** The profile's languages with the number of videos in each. */
    val profileLanguages: Map<String, Int>,
    /** Profile and not-for-me videos left out because their metadata hasn't been fetched yet. */
    val withoutMetadata: Int,
    val searchFailures: List<String>,
)

class EmptyProfileException(message: String) : Exception(message)

/**
 * One Discover visit (SPEC, "How recommendations work"): build the profile from the database,
 * group it into topics, collect and score candidates, and store the picks so feedback can refer
 * to them. Nothing here runs unless the user asks for it.
 */
class Discover(
    private val cache: MetadataCache,
    private val library: LibraryStore,
    private val profiles: ProfileStore,
    private val embeddings: EmbeddingStore,
    /** Null skips the language filter. */
    private val detector: LanguageDetector? = null,
    /** Used for the per-topic searches; null means related videos only. */
    private val source: ExtractorSource? = null,
    private val config: RankingConfig = RankingConfig(),
    private val seed: Long = 1,
    private val now: () -> Instant = Instant::now,
) {
    fun run(profile: ProfileRef): DiscoverResult {
        val entries = profiles.entries(profile)
        val included = entries.filter { it.state == VideoState.INCLUDED }
        val rejected = entries.filter { it.state == VideoState.NEGATIVE }
        val known = included.mapNotNull { e -> cache.video(e.video)?.metadata?.let { e to it } }
        val knownRejected = rejected.mapNotNull { cache.video(it.video)?.metadata }
        if (known.isEmpty()) {
            throw EmptyProfileException(
                if (included.isEmpty()) "The profile \"${profile.name}\" has no videos yet"
                else "None of the profile's ${included.size} videos have metadata yet; fetch it first"
            )
        }

        val vectors = embeddings.embed(known.associate { (_, v) -> v.id to EmbeddingText.of(v) })
        val negatives = embeddings.embed(knownRejected.associate { it.id to EmbeddingText.of(it) }).values.toList()
        val firstSeen = library.firstSeen()
        val taste = Profile(
            known.map { (entry, v) ->
                val age = v.uploadedAt?.let { up -> firstSeen[v.id]?.let { Duration.between(up, it).toDays().coerceAtLeast(0) } }
                ProfileVideo(v.id, v.title, v.channel, vectors.getValue(v.id), entry.weight, age)
            },
            negatives,
        )
        val clusters = Clusters.of(
            taste,
            known.associate { (_, v) -> v.id to EmbeddingText.keywordsSource(v) },
            channelNames = known.mapNotNull { it.second.channelName },
        )

        // Never suggest what is watched, saved, or already decided on in this profile.
        val exclude = library.knownVideoIds() + entries.map { it.video }
        val generator = CandidateGenerator(cache, source, Random(seed))
        val stubs = generator.generate(clusters, exclude)

        val languages = detector?.let { d -> LanguageFilter(d, known.map { (_, v) -> "${v.title}\n${v.description.take(300)}" }) }
        val kept = if (languages == null) stubs else stubs.filter { languages.foreignLanguage(it.stub.title) == null }

        // Candidates are mostly known from list results only; use the full text for those that happen to be cached.
        val candidateVectors = embeddings.embed(
            kept.associate { c -> c.stub.id to (cache.video(c.stub.id)?.metadata?.let(EmbeddingText::of) ?: EmbeddingText.of(c.stub)) }
        )
        val candidates = kept.map { c ->
            val full = cache.video(c.stub.id)?.metadata
            Candidate(
                id = c.stub.id,
                title = c.stub.title,
                channel = c.stub.channel,
                channelName = c.stub.channelName,
                isShort = c.stub.isShort || full?.isShort == true,
                uploadedAt = full?.uploadedAt ?: c.stub.uploadedAt,
                vector = candidateVectors.getValue(c.stub.id),
                sources = c.sources,
            )
        }
        val picks = Ranker(config).rank(taste, clusters, candidates, library.subscriptions().map { it.channel }.toSet(), now())

        profiles.savePicks(
            profile,
            picks.mapIndexed { i, p ->
                StoredPick(
                    i + 1, p.candidate.id, p.candidate.title, p.candidate.channel, p.candidate.channelName,
                    p.score, p.reason, p.candidate.sources.distinct().take(2).joinToString("; ", transform = ::describe), p.exploration,
                )
            },
        )
        return DiscoverResult(
            clusters = clusters,
            picks = picks,
            candidates = candidates.size,
            foreignLanguage = stubs.size - kept.size,
            profileLanguages = languages?.profileLanguages.orEmpty(),
            withoutMetadata = (included.size - known.size) + (rejected.size - knownRejected.size),
            searchFailures = generator.searchFailures,
        )
    }

    private fun describe(source: CandidateSource): String = when (source) {
        is CandidateSource.RelatedTo -> "related to \"${source.video.title}\""
        is CandidateSource.Search -> "search \"${source.query}\""
    }
}
