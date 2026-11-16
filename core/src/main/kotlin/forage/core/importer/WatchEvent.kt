package forage.core.importer

import forage.core.PlatformId
import java.time.Instant

/** One "this video was watched at this time" record from any import source. */
data class WatchEvent(
    val video: PlatformId,
    val title: String?,
    val channel: PlatformId?,
    val channelName: String?,
    val watchedAt: Instant?,
    val isShort: Boolean = false,
)

data class ImportResult(
    val events: List<WatchEvent>,
    /** Why entries were left out, keyed by reason. */
    val skipped: Map<SkipReason, Int>,
)

enum class SkipReason {
    /** Ad shown as a video ("From Google Ads"). */
    AD,

    /** Removed or private video: the entry has no URL left. */
    NO_URL,

    /** Community posts, YouTube Music-only pages and anything else that isn't a video link. */
    NOT_A_VIDEO,
}
