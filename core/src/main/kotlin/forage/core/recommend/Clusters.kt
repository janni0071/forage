package forage.core.recommend

import forage.core.PlatformId

object Clusters {
    /** Groups smaller than this aren't topics; their videos go into the "varied" cluster. */
    const val MIN_TOPIC_SIZE = 3

    /**
     * Groups the profile into topics, plus at most one [Cluster.isVaried] cluster holding the
     * one-off interests. [keywordTexts] supplies what keywords are drawn from for each video
     * (title and tags); [channelNames] are kept out of the keywords.
     */
    fun of(
        profile: Profile,
        keywordTexts: Map<PlatformId, String> = emptyMap(),
        channelNames: Collection<String> = emptyList(),
        threshold: Float = Clustering.DEFAULT_THRESHOLD,
    ): List<Cluster> {
        val groups = Clustering.agglomerate(profile.videos.map { it.vector }, threshold)
            .map { members -> members.map { profile.videos[it] } }
        val topics = groups.filter { it.size >= MIN_TOPIC_SIZE }
        val varied = groups.filter { it.size < MIN_TOPIC_SIZE }.flatten()

        val ignore = channelNames.flatMap(Keywords::tokens).toSet()
        val keywords = Keywords.top(topics.map { g -> g.map { keywordTexts[it.id] ?: it.title } }, ignore = ignore)
        val clusters = topics.mapIndexed { i, videos ->
            Cluster(i, videos, Clustering.mean(videos.map { it.vector }), keywords[i])
        }
        return if (varied.isEmpty()) clusters
        else clusters + Cluster(clusters.size, varied, Clustering.mean(varied.map { it.vector }), emptyList(), isVaried = true)
    }
}
