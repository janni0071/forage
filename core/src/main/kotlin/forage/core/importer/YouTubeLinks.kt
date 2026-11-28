package forage.core.importer

import forage.core.PlatformId

/** YouTube video and channel ids from the links apps keep in their exports. */
internal object YouTubeLinks {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    /** `https://www.youtube.com/watch?v=ID`, `youtu.be/ID`, `/shorts/ID`, or a bare id. */
    fun video(link: String?): PlatformId? {
        val text = link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (VIDEO_ID.matches(text)) return PlatformId.youtube(text)
        val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
            ?: Regex("(?:youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
        return id?.let(PlatformId::youtube)
    }

    /** `https://www.youtube.com/channel/UC…`, `/channel/UC…`, or a bare channel id. */
    fun channel(link: String?): PlatformId? {
        val text = link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (CHANNEL_ID.matches(text)) return PlatformId.youtube(text)
        return Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(text)?.groupValues?.get(1)?.let(PlatformId::youtube)
    }
}
