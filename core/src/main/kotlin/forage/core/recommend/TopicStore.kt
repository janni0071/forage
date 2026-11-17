package forage.core.recommend

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.profile.ProfileRef
import kotlinx.serialization.json.Json
import java.time.Instant

enum class TopicPreference {
    /** Always gets a few picks in every batch. */
    PINNED,

    /** Gets no picks and no candidates; its videos stay in the profile. */
    MUTED,
}

/**
 * Remembers which topics the user pinned or muted (SPEC, Taste profile). Topics have no fixed
 * identity, since they are recomputed from the profile on every visit, so a preference is stored
 * with the topic's videos and applied to whichever new topic shares most of them.
 */
class TopicStore(db: ForageDatabase, private val clock: () -> Instant = Instant::now) {
    private val queries = db.topicQueries

    private data class Stored(val id: Long, val preference: TopicPreference, val members: Set<PlatformId>)

    private fun stored(profile: ProfileRef) = queries.selectTopicPreferences(profile.id).executeAsList().map {
        Stored(it.id, TopicPreference.valueOf(it.preference.uppercase()), Json.decodeFromString<List<String>>(it.members).map(PlatformId::parse).toSet())
    }

    /**
     * Returns [clusters] with their remembered preferences. A matched preference follows its topic:
     * its stored videos are updated to the topic's current ones, so it keeps up as the profile changes.
     */
    fun apply(profile: ProfileRef, clusters: List<Cluster>): List<Cluster> {
        val matches = match(stored(profile), clusters)
        val now = clock().epochSecond
        queries.transaction {
            for ((cluster, s) in matches) {
                queries.updateTopicPreference(cluster.label, encode(cluster), now, s.id)
            }
        }
        val byIndex = matches.associate { (c, s) -> c.index to s.preference }
        return clusters.map { c -> byIndex[c.index]?.let { c.copy(preference = it) } ?: c }
    }

    /** Sets or (with null) clears the preference for [cluster]. The varied cluster can't have one. */
    fun set(profile: ProfileRef, cluster: Cluster, preference: TopicPreference?) {
        require(!cluster.isVaried) { "The varied interests group isn't a topic" }
        queries.transaction {
            match(stored(profile), listOf(cluster)).forEach { (_, s) -> queries.deleteTopicPreference(s.id) }
            if (preference != null) {
                queries.insertTopicPreference(profile.id, preference.name.lowercase(), cluster.label, encode(cluster), clock().epochSecond)
            }
        }
    }

    /** Pairs each topic with the stored preference that shares most of its videos, if enough do; each side used once. */
    private fun match(stored: List<Stored>, clusters: List<Cluster>): List<Pair<Cluster, Stored>> {
        val candidates = clusters.filter { !it.isVaried }.flatMap { c ->
            val members = c.videos.map { it.id }.toSet()
            stored.map { s -> Triple(c, s, overlap(members, s.members)) }
        }.filter { it.third >= MIN_OVERLAP }.sortedByDescending { it.third }
        val usedClusters = HashSet<Int>()
        val usedStored = HashSet<Long>()
        return candidates.mapNotNull { (c, s, _) ->
            if (c.index in usedClusters || s.id in usedStored) null
            else (c to s).also { usedClusters += c.index; usedStored += s.id }
        }
    }

    private fun overlap(a: Set<PlatformId>, b: Set<PlatformId>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    private fun encode(cluster: Cluster) = Json.encodeToString(cluster.videos.map { it.id.toString() })

    companion object {
        /** Share of videos (of both together) a topic must still have in common to count as the same. */
        const val MIN_OVERLAP = 0.4
    }
}
