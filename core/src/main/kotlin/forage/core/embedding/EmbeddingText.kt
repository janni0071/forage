package forage.core.embedding

import forage.core.source.VideoMetadata
import forage.core.source.VideoStub

/**
 * The text a video is embedded from: title, channel, tags, notes and the start of the description
 * (SPEC, "How recommendations work"). The most telling parts come first, because the model only
 * reads the first hundred or so tokens.
 */
object EmbeddingText {
    /** Bump when the recipe changes, so stored vectors are recognised as stale. */
    const val VERSION = 1

    private const val DESCRIPTION_CHARS = 600
    private val URL = Regex("""https?://\S+""")
    private val SPACES = Regex("""[ \t]+""")

    fun of(video: VideoMetadata, notes: String? = null): String = buildList {
        add(video.title)
        video.channelName?.let(::add)
        if (video.tags.isNotEmpty()) add(video.tags.joinToString(", "))
        notes?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        cleanDescription(video.description).takeIf { it.isNotEmpty() }?.let(::add)
    }.joinToString("\n")

    /** For videos known only from a list result: title and channel, and tags where the platform lists them. */
    fun of(stub: VideoStub): String = buildList {
        add(stub.title)
        stub.channelName?.let(::add)
        if (stub.tags.isNotEmpty()) add(stub.tags.joinToString(", "))
    }.joinToString("\n")

    /** What cluster keywords are drawn from. */
    fun keywordsSource(video: VideoMetadata): String = (listOf(video.title) + video.tags).joinToString(" ")

    /** Descriptions are full of links, sponsor codes and chapter lists; keep the leading prose. */
    private fun cleanDescription(description: String): String =
        description.lineSequence()
            .map { URL.replace(it, "").replace(SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(DESCRIPTION_CHARS)
}
