package forage.core

import java.net.URI
import java.net.URLDecoder

/** Pulls IDs out of the YouTube URL shapes that show up in exports and pasted links. */
object YouTubeUrls {
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val PATH_PREFIXES = listOf("/shorts/", "/embed/", "/live/", "/v/")

    data class VideoRef(val id: PlatformId, val isShort: Boolean)

    fun video(url: String): VideoRef? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()

        val (raw, isShort) = when (host) {
            "youtu.be" -> path.removePrefix("/").substringBefore('/') to false
            "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> {
                val prefix = PATH_PREFIXES.firstOrNull { path.startsWith(it) }
                when {
                    prefix != null -> path.removePrefix(prefix).substringBefore('/') to (prefix == "/shorts/")
                    path == "/watch" -> (queryParam(uri, "v") ?: return null) to false
                    else -> return null
                }
            }
            else -> return null
        }
        if (!VIDEO_ID.matches(raw)) return null
        return VideoRef(PlatformId.youtube(raw), isShort)
    }

    /** Channel ID from a `/channel/UC…` URL. Handle and legacy `/user/` URLs need a network lookup, so they return null. */
    fun channel(url: String): PlatformId? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val id = uri.path.orEmpty().substringAfter("/channel/", "").substringBefore('/')
        return if (id.startsWith("UC") && id.length == 24) PlatformId.youtube(id) else null
    }

    private fun queryParam(uri: URI, name: String): String? =
        uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
}
