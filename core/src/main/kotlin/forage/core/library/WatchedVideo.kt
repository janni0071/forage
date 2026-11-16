package forage.core.library

import forage.core.PlatformId
import forage.core.importer.WatchEvent
import java.time.Instant

/** A video from the watch history with all of its watch events folded together. */
data class WatchedVideo(
    val video: PlatformId,
    val title: String?,
    val channel: PlatformId?,
    val channelName: String?,
    val watchCount: Int,
    val firstWatched: Instant?,
    val lastWatched: Instant?,
    val isShort: Boolean,
)

/** Bulk-selection criteria: by channel, watch count, date range or title (SPEC, Library). */
data class HistoryFilter(
    /** Case-insensitive substrings of the channel name, or full `platform:id` channel IDs. Any match counts. */
    val channels: List<String> = emptyList(),
    /** Case-insensitive substring of the title. */
    val title: String? = null,
    /** Only watch events at or after this instant are considered. */
    val since: Instant? = null,
    /** Only watch events before this instant are considered. */
    val until: Instant? = null,
    /** Minimum number of watches, counted inside the date range. */
    val minWatches: Int = 1,
)

object WatchHistory {
    /**
     * Folds watch events into one entry per video, keeps those matching [filter] and orders them
     * most-watched first, then most recently watched.
     */
    fun select(events: List<WatchEvent>, filter: HistoryFilter = HistoryFilter()): List<WatchedVideo> {
        val dated = filter.since != null || filter.until != null
        return events.asSequence()
            .filter { e ->
                val t = e.watchedAt
                if (t == null) !dated
                else (filter.since == null || t >= filter.since) && (filter.until == null || t < filter.until)
            }
            .groupBy { it.video }
            .map { (video, watches) -> fold(video, watches) }
            .filter { it.watchCount >= filter.minWatches && matchesChannel(it, filter.channels) && matchesTitle(it, filter.title) }
            .sortedWith(
                compareByDescending<WatchedVideo> { it.watchCount }
                    .thenByDescending { it.lastWatched ?: Instant.MIN }
            )
    }

    private fun fold(video: PlatformId, watches: List<WatchEvent>): WatchedVideo {
        val times = watches.mapNotNull { it.watchedAt }
        return WatchedVideo(
            video = video,
            // Older entries sometimes lack a title or channel that a later watch has, so take the first one present.
            title = watches.firstNotNullOfOrNull { it.title },
            channel = watches.firstNotNullOfOrNull { it.channel },
            channelName = watches.firstNotNullOfOrNull { it.channelName },
            watchCount = watches.size,
            firstWatched = times.minOrNull(),
            lastWatched = times.maxOrNull(),
            isShort = watches.any { it.isShort },
        )
    }

    private fun matchesChannel(v: WatchedVideo, wanted: List<String>): Boolean =
        wanted.isEmpty() || wanted.any { w ->
            v.channel?.toString() == w || v.channelName?.contains(w, ignoreCase = true) == true
        }

    private fun matchesTitle(v: WatchedVideo, wanted: String?): Boolean =
        wanted == null || v.title?.contains(wanted, ignoreCase = true) == true
}
