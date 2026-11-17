package forage.core.source

import forage.core.PlatformId
import java.time.Instant

/**
 * One implementation per platform (SPEC, "Future: other platforms"). Core, profile and ranking
 * code talk to platforms only through this interface.
 *
 * Calls block and do network I/O. They throw [SourceException] when the platform can't be reached
 * or refuses to answer; a video that is simply gone is reported as [SourceException.NotAvailable].
 */
interface ExtractorSource {
    /** Platform prefix of the IDs this source handles, e.g. `youtube`. */
    val platform: String

    /** What this source can do. Callers check before calling and skip what's missing. */
    val capabilities: Set<Capability>

    /** Metadata for one video, plus its related videos when the platform offers them in the same request. */
    fun video(id: PlatformId): VideoDetails

    /** Requires [Capability.SEARCH]. */
    fun search(query: String): List<VideoStub>

    /** Link that opens the video in a browser or external player. */
    fun watchUrl(id: PlatformId): String

    /**
     * A preview image for the video, if the platform has a stable address for one. [large] asks
     * for one sharp enough for a big card; it may not exist for every video, so callers fall back
     * to the small one.
     */
    fun thumbnailUrl(id: PlatformId, large: Boolean = false): String? = null
}

enum class Capability { RELATED_VIDEOS, SEARCH }

/** Platform-neutral video model. Platform-specific odds and ends go into [extra]. */
data class VideoMetadata(
    val id: PlatformId,
    val title: String,
    val description: String,
    val tags: List<String>,
    val durationSeconds: Long?,
    val channel: PlatformId?,
    val channelName: String?,
    /** BCP 47 language tag when the platform reports one. */
    val language: String?,
    val thumbnailUrl: String?,
    val uploadedAt: Instant?,
    val isShort: Boolean,
    val extra: Map<String, String> = emptyMap(),
)

/** The little that list results (related videos, search hits) carry about a video. */
data class VideoStub(
    val id: PlatformId,
    val title: String,
    val channel: PlatformId?,
    val channelName: String?,
    val durationSeconds: Long?,
    val isShort: Boolean,
    /** Often only approximate: list results say "3 months ago". */
    val uploadedAt: Instant? = null,
)

data class VideoDetails(val metadata: VideoMetadata, val related: List<VideoStub>)

open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The video was removed, is private, or is otherwise permanently unavailable. */
    class NotAvailable(message: String, cause: Throwable? = null) : SourceException(message, cause)

    /** The platform is refusing this client (captcha, bot check, rate limit, IP block). */
    class Blocked(message: String, cause: Throwable? = null) : SourceException(message, cause)
}
