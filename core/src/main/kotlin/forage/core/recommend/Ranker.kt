package forage.core.recommend

import forage.core.PlatformId
import forage.core.embedding.Vectors
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/** A candidate with everything ranking needs. */
data class Candidate(
    val id: PlatformId,
    val title: String,
    val channel: PlatformId?,
    val channelName: String?,
    val isShort: Boolean,
    val uploadedAt: Instant?,
    val vector: FloatArray,
    val sources: List<CandidateSource>,
)

data class RankingConfig(
    val slots: Int = 20,
    /** Slots kept for looser matches, so the list isn't only more of the same. */
    val explorationSlots: Int = 3,
    /** Similarity is the weighted mean over this many nearest profile videos. */
    val nearest: Int = 3,
    /**
     * Weight of the "not for me" penalty. It applies only as far as a candidate is closer to a
     * negative example than to the profile, so rejecting a beginner tutorial doesn't sink the
     * whole subject the user otherwise likes.
     */
    val negativePenalty: Float = 1f,
    /** Added when the channel is neither followed nor already in the profile. */
    val newChannelBonus: Float = 0.05f,
    /** Freshness bonus every video uploaded today gets; it fades over about three months. */
    val freshnessBonus: Float = 0.03f,
    /**
     * Extra freshness bonus where the user follows things as they come out: scaled by the share of
     * the nearest profile videos that were saved within a month of their upload. Release-driven
     * interests (trailers, news) get recent uploads; timeless ones (documentaries) are left alone.
     */
    val followedFreshnessBonus: Float = 0.12f,
    /** MMR trade-off: 1 ranks purely by score, lower values favour variety. */
    val mmrLambda: Float = 0.7f,
    /**
     * Subtracted from candidates found only by keyword search. Search returns whatever is popular
     * for the words; related videos carry the platform's knowledge of what viewers watch together.
     */
    val searchOnlyPenalty: Float = 0.05f,
    /** Titles differ enough that MMR alone lets one channel through repeatedly. */
    val maxPerChannel: Int = 2,
    val showShorts: Boolean = false,
)

data class Pick(
    val candidate: Candidate,
    val score: Float,
    val similarity: Float,
    /** Nearest profile videos with their similarity, best first. */
    val nearest: List<Pair<ProfileVideo, Float>>,
    val cluster: Cluster?,
    val newChannel: Boolean,
    val exploration: Boolean,
    val reason: String,
)

/** Scores candidates against a profile and picks a varied, explainable batch (SPEC, Ranking). */
class Ranker(private val config: RankingConfig = RankingConfig()) {

    fun rank(
        profile: Profile,
        clusters: List<Cluster>,
        candidates: List<Candidate>,
        /** Channels the user subscribes to. */
        followedChannels: Set<PlatformId> = emptySet(),
        now: Instant = Instant.now(),
    ): List<Pick> {
        val knownChannels = followedChannels + profile.videos.mapNotNull { it.channel }
        val scored = candidates
            .filter { config.showShorts || !it.isShort }
            .map { score(it, profile, clusters, knownChannels, now) }

        val main = mmr(scored.sortedByDescending { it.score }, emptyList(), config.slots - config.explorationSlots)

        // Exploration: unfamiliar channels from the middle of the similarity range, as different
        // from the main picks as possible.
        val similarities = scored.map { it.similarity }.sorted()
        val band = if (similarities.isEmpty()) 0f..0f else percentile(similarities, 0.40)..percentile(similarities, 0.75)
        val taken = main.map { it.candidate.id }.toSet()
        val pool = scored.filter { it.candidate.id !in taken && it.newChannel && it.similarity in band }
        val explore = mmr(pool.sortedByDescending { it.score }, main, config.explorationSlots, lambda = 0.3f)
            .map { it.copy(exploration = true, reason = "exploration: a looser match" + (it.cluster?.takeUnless { c -> c.isVaried }?.let { c -> " near your ${c.label} topic" } ?: "")) }

        // Top up from the main ranking when there weren't enough exploration candidates.
        val all = main + explore
        val rest = scored.filter { p -> all.none { it.candidate.id == p.candidate.id } }.sortedByDescending { it.score }
        return all + mmr(rest, all, config.slots - all.size)
    }

    private fun score(c: Candidate, profile: Profile, clusters: List<Cluster>, knownChannels: Set<PlatformId>, now: Instant): Pick {
        val nearest = profile.videos
            .map { it to Vectors.dot(c.vector, it.vector) * it.weight }
            .sortedByDescending { it.second }
            .take(config.nearest)
        val similarity = if (nearest.isEmpty()) 0f else nearest.map { it.second }.average().toFloat()
        val negative = profile.negatives.maxOfOrNull { Vectors.dot(c.vector, it) }
            ?.let { (it - similarity).coerceAtLeast(0f) } ?: 0f
        // A candidate without a channel id can't be called new with any confidence.
        val newChannel = c.channel != null && c.channel !in knownChannels
        val followsReleases = if (nearest.isEmpty()) 0f else nearest.count { it.first.addedFresh }.toFloat() / nearest.size
        val ageDays = c.uploadedAt?.let { Duration.between(it, now).toDays().coerceAtLeast(0) }
        val freshness = ageDays?.let {
            (config.freshnessBonus + config.followedFreshnessBonus * followsReleases) * exp(-it / 90.0).toFloat()
        } ?: 0f
        val recent = ageDays != null && ageDays <= ProfileVideo.FRESH_DAYS && followsReleases >= 0.5f
        val searchOnly = c.sources.isNotEmpty() && c.sources.all { it is CandidateSource.Search }
        val score = similarity - config.negativePenalty * negative +
            (if (newChannel) config.newChannelBonus else 0f) + freshness -
            (if (searchOnly) config.searchOnlyPenalty else 0f)
        // The candidate belongs with its nearest profile video; centroids mean little for the varied cluster.
        val cluster = nearest.firstOrNull()?.first?.let { video -> clusters.firstOrNull { video in it.videos } }
        return Pick(c, score, similarity, nearest, cluster, newChannel, exploration = false, reason = reason(nearest, cluster, newChannel) + if (recent) "; recent upload" else "")
    }

    /** Maximal marginal relevance: repeatedly take the pick that scores well and differs from those already taken. */
    private fun mmr(ranked: List<Pick>, alreadyChosen: List<Pick>, count: Int, lambda: Float = config.mmrLambda): List<Pick> {
        val chosen = ArrayList<Pick>()
        val remaining = ranked.toMutableList()
        val perChannel = HashMap<Any, Int>()
        alreadyChosen.forEach { perChannel.merge(channelKey(it), 1, Int::plus) }
        while (chosen.size < count && remaining.isNotEmpty()) {
            val context = alreadyChosen + chosen
            val best = remaining
                .filter { (perChannel[channelKey(it)] ?: 0) < config.maxPerChannel }
                .maxByOrNull { p ->
                    val redundancy = context.maxOfOrNull { Vectors.dot(p.candidate.vector, it.candidate.vector) } ?: 0f
                    lambda * p.score - (1 - lambda) * redundancy
                } ?: break
            chosen += best
            remaining -= best
            perChannel.merge(channelKey(best), 1, Int::plus)
        }
        return chosen
    }

    /** Videos without any channel information each count as their own channel. */
    private fun channelKey(pick: Pick): Any = pick.candidate.channel ?: pick.candidate.channelName ?: pick.candidate.id

    private fun reason(nearest: List<Pair<ProfileVideo, Float>>, cluster: Cluster?, newChannel: Boolean): String {
        val best = nearest.firstOrNull()
        // "Similar to" counts the profile videos that are nearly as close as the closest one.
        val close = if (best == null) emptyList() else nearest.filter { it.second >= best.second - 0.1f && it.second >= 0.3f }
        val similar = when (close.size) {
            0 -> null
            1 -> "similar to \"${close[0].first.title}\""
            else -> "similar to ${close.size} videos in your profile, e.g. \"${close[0].first.title}\""
        }
        val near = cluster?.takeUnless { it.isVaried }?.let { "near your ${it.label} topic" }
        return when {
            newChannel -> listOfNotNull(listOfNotNull("new channel", near).joinToString(" "), similar).joinToString("; ")
            similar != null -> similar
            else -> near ?: "loosely related to your profile"
        }
    }

    private fun percentile(sorted: List<Float>, p: Double): Float = sorted[((sorted.size - 1) * p).toInt()]
}
