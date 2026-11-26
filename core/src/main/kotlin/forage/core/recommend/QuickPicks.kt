package forage.core.recommend

import forage.core.PlatformId
import forage.core.library.LibraryStore
import forage.core.metadata.MetadataCache
import forage.core.profile.ProfileRef
import forage.core.profile.ProfileStore
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.source.ExtractorSource
import forage.core.source.VideoStub
import java.time.Duration
import java.time.Instant
import kotlin.math.ln

/**
 * Picks that need no recommendation model, for while it isn't there yet (a new user's first
 * minutes, while it downloads): videos related to what was liked and watched lately, new uploads
 * of those followed, and the latest of the channels watched most; each scored by how many of those
 * point to it and how much its channel is liked and watched. Rougher than Discover's, which
 * compares what each video is about with the taste the likes make up, but there at once.
 */
class QuickPicks(
    private val cache: MetadataCache,
    private val library: LibraryStore,
    private val profiles: ProfileStore,
    /** For the latest uploads of channels watched a lot but not followed; null leaves them out. */
    private val source: ExtractorSource? = null,
    private val showShorts: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    private class Scored(val stub: VideoStub, var score: Double, var reason: String, var reasonScore: Double)

    fun run(profile: ProfileRef, limit: Int = 40, channelFeeds: Int = 6): List<StoredPick> {
        val entries = profiles.entries(profile)
        val exclude = library.knownVideoIds() + entries.map { it.video }
        val liked = entries.filter { it.state == VideoState.LIKED }.sortedByDescending { it.changedAt }
        val history = library.events().filter { it.watchedAt != null }.sortedByDescending { it.watchedAt }
        val found = LinkedHashMap<PlatformId, Scored>()

        fun add(stub: VideoStub, points: Double, reason: String) {
            if (stub.id in exclude || (stub.isShort && !showShorts)) return
            val s = found.getOrPut(stub.id) { Scored(stub, 0.0, reason, 0.0) }
            s.score += points
            // The strongest of its reasons is the one shown.
            if (points > s.reasonScore) {
                s.reason = reason
                s.reasonScore = points
            }
        }

        // How much each channel is liked (counts three times) and watched.
        val affinity = HashMap<PlatformId, Double>()
        val channelNames = HashMap<PlatformId, String>()
        for (e in liked) cache.video(e.video)?.metadata?.let { v ->
            v.channel?.let { c -> affinity.merge(c, 3.0, Double::plus); v.channelName?.let { channelNames[c] = it } }
        }
        for (e in history.take(500)) e.channel?.let { c -> affinity.merge(c, 1.0, Double::plus); e.channelName?.let { channelNames[c] = it } }

        // Related to the latest likes, and (less) to what was watched lately.
        for (e in liked.take(30)) {
            val video = cache.video(e.video) ?: continue
            val title = video.metadata.title
            video.related.forEach { add(it, 1.0, "Related to \"$title\", which you liked") }
        }
        for (e in history.take(40)) {
            val video = cache.video(e.video) ?: continue
            video.related.forEach { add(it, 0.4, "Related to \"${video.metadata.title}\", which you watched") }
        }

        // New from those followed: the newer, the more.
        for (u in library.uploads(200)) {
            val age = Duration.between(u.video.uploadedAt ?: u.foundAt, now()).toDays().coerceAtLeast(0)
            add(u.video, 0.9 / (1 + age / 7.0), "New from ${u.video.channelName ?: "a channel you follow"}")
        }

        // The latest of channels watched most but not followed, when there's too little so far.
        val source = source
        if (source != null && found.size < limit * 2) {
            val followed = library.following().map { it.channel }.toSet()
            val top = affinity.entries.filter { it.key !in followed }.sortedByDescending { it.value }.take(channelFeeds)
            for ((channel, _) in top) {
                val feed = runCatching { source.channelFeed(channel, channelNames[channel]) }.getOrNull() ?: continue
                val name = feed.name ?: channelNames[channel] ?: "a channel you watch"
                feed.videos.take(15).forEach { add(it, 0.6, "Latest from $name, which you watch a lot") }
            }
        }

        // Liked and watched channels lift their videos a little.
        for (s in found.values) s.stub.channel?.let { c -> affinity[c]?.let { s.score += 0.15 * ln(1 + it) } }

        // At most three per channel, so a few channels don't fill the batch.
        val perChannel = HashMap<PlatformId?, Int>()
        val picks = found.values.sortedByDescending { it.score }.filter { s ->
            val n = perChannel.merge(s.stub.channel, 1, Int::plus)!!
            s.stub.channel == null || n <= 3
        }.take(limit)

        val stored = picks.mapIndexed { i, s ->
            StoredPick(
                i + 1, s.stub.id, s.stub.title, s.stub.channel, s.stub.channelName, s.score.toFloat(), s.reason, FOUND_VIA, false,
                s.stub.durationSeconds, s.stub.uploadedAt,
            )
        }
        if (stored.isNotEmpty()) profiles.savePicks(profile, stored)
        return stored
    }

    companion object {
        /** What a quick pick's "found via" says, so a batch of them can be told from Discover's. */
        const val FOUND_VIA = "quick picks"
    }
}
