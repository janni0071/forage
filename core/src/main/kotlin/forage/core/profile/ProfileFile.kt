package forage.core.profile

import forage.core.PlatformId
import forage.core.YouTubeUrls
import forage.core.library.WatchedVideo

/**
 * The M0 profile: a plain text file you edit by hand.
 *
 * One video per line, either as `platform:id` or as a pasted video URL. Everything after ` #`
 * is a comment, as are lines starting with `#`. Blank lines are ignored.
 */
object ProfileFile {
    data class Parsed(
        /** Videos in file order, without repeats. */
        val videos: List<PlatformId>,
        val duplicates: List<PlatformId>,
        /** Lines that are neither an ID nor a recognised video URL, with their 1-based line number. */
        val invalid: List<Pair<Int, String>>,
    )

    fun parse(text: String): Parsed {
        val videos = LinkedHashSet<PlatformId>()
        val duplicates = ArrayList<PlatformId>()
        val invalid = ArrayList<Pair<Int, String>>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = stripComment(raw)
            if (line.isEmpty()) return@forEachIndexed
            val id = parseEntry(line)
            when {
                id == null -> invalid += (index + 1) to line
                !videos.add(id) -> duplicates += id
            }
        }
        return Parsed(videos.toList(), duplicates, invalid)
    }

    /** A line for [video] with title, channel and watch count as a trailing comment. */
    fun line(video: WatchedVideo): String {
        val label = listOfNotNull(video.title, video.channelName).joinToString(" — ").ifEmpty { "(no title)" }
        val count = if (video.watchCount > 1) " (${video.watchCount}×)" else ""
        // Line breaks inside a title would split the entry.
        return "${video.video}  # ${label.replace(Regex("\\s+"), " ")}$count"
    }

    private fun stripComment(raw: String): String {
        val line = raw.trim()
        if (line.startsWith("#")) return ""
        // Require whitespace before '#': URLs may contain a fragment.
        val cut = Regex("\\s#").find(line)?.range?.first ?: line.length
        return line.substring(0, cut).trim()
    }

    private fun parseEntry(entry: String): PlatformId? {
        if ("://" in entry) return YouTubeUrls.video(entry)?.id
        if (entry.any(Char::isWhitespace)) return null
        return runCatching { PlatformId.parse(entry) }.getOrNull()
    }
}
