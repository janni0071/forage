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

    /** The platform's name as people know it, for messages. */
    val displayName: String get() = platform

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
     * to the small one. [fresh] asks for a new address when the last one was refused, for
     * platforms whose image links expire.
     */
    fun thumbnailUrl(id: PlatformId, large: Boolean = false, fresh: Boolean = false): String? = null

    /**
     * Every preview image the platform offers for the video, best first: callers try the next
     * when one won't load. Most platforms have just the one from [thumbnailUrl].
     */
    fun thumbnailUrls(id: PlatformId, large: Boolean = false, fresh: Boolean = false): List<String> =
        listOfNotNull(thumbnailUrl(id, large, fresh))

    /** The video a pasted link (or bare ID) points to, if it is one of this platform's. */
    fun parseLink(text: String): PlatformId? = null

    /**
     * The source that handles [platform]: this one, or for a [Sources] the matching member.
     * Lets callers that work per platform (searches) accept either.
     */
    fun forPlatform(platform: String): ExtractorSource? = takeIf { it.platform == platform }
}

enum class Capability {
    RELATED_VIDEOS,
    SEARCH,

    /**
     * The platform's tags describe what a video is about better than its title does, so topics
     * are grouped by shared tags and named after them.
     */
    DESCRIPTIVE_TAGS,
}

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
