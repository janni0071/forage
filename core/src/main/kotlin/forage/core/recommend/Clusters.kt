package forage.core.recommend

import forage.core.PlatformId

object Clusters {
    /**
     * Groups the profile into topics. [texts] supplies what keywords are drawn from
     * (title and tags) for each profile video.
     */
    fun of(profile: Profile, texts: Map<PlatformId, String>, k: Int? = null, seed: Long = 1): List<Cluster> {
        val vectors = profile.videos.map { it.vector }
        val groups = Clustering.kMeans(vectors, k ?: Clustering.defaultK(vectors.size), seed)
            .map { members -> members.map { profile.videos[it] } }
        val keywords = Keywords.top(groups.map { g -> g.map { texts[it.id] ?: it.title } })
        return groups.mapIndexed { i, videos ->
            Cluster(i, videos, Clustering.mean(videos.map { it.vector }), keywords[i])
        }
    }
}
