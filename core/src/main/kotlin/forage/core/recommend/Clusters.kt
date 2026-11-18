package forage.core.recommend

import forage.core.PlatformId
import forage.core.embedding.Vectors
import kotlin.math.ln
import kotlin.math.sqrt

object Clusters {
    /** Groups smaller than this aren't topics; their videos go into the "varied" cluster. */
    const val MIN_TOPIC_SIZE = 3

    /**
     * How much shared tags count when grouping videos that have [tagTerms]; the text embedding
     * gets the rest. Tag-rich sites share generic tags across almost every video, which makes
     * the embeddings of their videos alike; tags weighted by rarity tell the topics apart.
     */
    const val TAG_WEIGHT = 0.75f

    /**
     * Groups the profile into topics, plus at most one [Cluster.isVaried] cluster holding the
     * one-off interests. [keywordTexts] supplies what keywords are drawn from for each video
     * (title and tags); [channelNames] are kept out of the keywords.
     *
     * Videos in [tagTerms] (platforms with descriptive tags) are grouped by their tags as well as
     * their embedding, and their topics are named after whole tags rather than words.
     */
    fun of(
        profile: Profile,
        keywordTexts: Map<PlatformId, String> = emptyMap(),
        channelNames: Collection<String> = emptyList(),
        threshold: Float = Clustering.DEFAULT_THRESHOLD,
        tagTerms: Map<PlatformId, List<String>> = emptyMap(),
    ): List<Cluster> {
        val groups = Clustering.agglomerate(groupingVectors(profile.videos, tagTerms), threshold)
            .map { members -> members.map { profile.videos[it] } }
        val topics = groups.filter { it.size >= MIN_TOPIC_SIZE }
        val varied = groups.filter { it.size < MIN_TOPIC_SIZE }.flatten()

        val ignore = channelNames.flatMap(Keywords::tokens).toSet() + channelNames.map { it.lowercase() } + commonTags(tagTerms)
        val keywords = Keywords.topTerms(
            topics.map { g -> g.map { tagTerms[it.id] ?: Keywords.tokens(keywordTexts[it.id] ?: it.title) } },
            ignore = ignore,
        )
        val clusters = topics.mapIndexed { i, videos ->
            Cluster(i, videos, Clustering.mean(videos.map { it.vector }), keywords[i])
        }
        return if (varied.isEmpty()) clusters
        else clusters + Cluster(clusters.size, varied, Clustering.mean(varied.map { it.vector }), emptyList(), isVaried = true)
    }

    /** Tags on more than half the tagged videos describe the site, not a topic, so they never name one. */
    private fun commonTags(tagTerms: Map<PlatformId, List<String>>): Set<String> {
        if (tagTerms.size < 4) return emptySet()
        val df = tagTerms.values.flatMap { it.distinct() }.groupingBy { it }.eachCount()
        return df.filterValues { it * 2 > tagTerms.size }.keys
    }

    /**
     * The embedding, extended by a TF-IDF vector of the video's tags across the profile, so that the
     * dot product of two vectors mixes embedding and tag similarity by [TAG_WEIGHT]. Tags on every
     * video weigh nothing and tags on one video can't group anything, so both are left out.
     * Videos without usable tags keep their plain embedding.
     */
    internal fun groupingVectors(videos: List<ProfileVideo>, tagTerms: Map<PlatformId, List<String>>): List<FloatArray> {
        if (tagTerms.isEmpty()) return videos.map { it.vector }
        val terms = videos.map { tagTerms[it.id].orEmpty().distinct() }
        val df = HashMap<String, Int>()
        terms.forEach { t -> t.forEach { df.merge(it, 1, Int::plus) } }
        val withTags = terms.count { it.isNotEmpty() }
        val vocabulary = df.filter { (_, n) -> n in 2 until withTags }.keys.sorted()
        val index = vocabulary.withIndex().associate { (i, term) -> term to i }
        val dims = videos.firstOrNull()?.vector?.size ?: 0
        val embeddingPart = sqrt(1 - TAG_WEIGHT)
        val tagPart = sqrt(TAG_WEIGHT)

        return videos.mapIndexed { v, video ->
            val tags = FloatArray(vocabulary.size)
            for (term in terms[v]) {
                val i = index[term] ?: continue
                tags[i] = ln(withTags.toFloat() / df.getValue(term))
            }
            val out = FloatArray(dims + vocabulary.size)
            if (tags.all { it == 0f }) {
                video.vector.copyInto(out)
            } else {
                val unit = Vectors.normalize(tags)
                for (d in 0 until dims) out[d] = video.vector[d] * embeddingPart
                for (i in unit.indices) out[dims + i] = unit[i] * tagPart
            }
            out
        }
    }
}
