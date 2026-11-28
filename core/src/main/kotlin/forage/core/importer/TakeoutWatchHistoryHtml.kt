package forage.core.importer

import forage.core.YouTubeUrls
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.InputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale

/**
 * Parser for `history/watch-history.html`, the format Takeout exports unless the user switches
 * the history to JSON. Each entry is a `div.outer-cell` whose first content cell reads
 * "Watched <video link> / <channel link> / <local date>".
 *
 * The whole document is loaded into memory; a decade of history is around 100 MB of HTML,
 * for which the JSON export is the better choice.
 */
object TakeoutWatchHistoryHtml {
    fun parse(input: InputStream): ImportResult {
        val events = ArrayList<WatchEvent>()
        val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
        fun skip(reason: SkipReason) = skipped.merge(reason, 1, Int::plus)

        for (cell in Jsoup.parse(input, "UTF-8", "").select("div.outer-cell")) {
            val content = cell.selectFirst("div.content-cell") ?: continue
            if (cell.select("div.content-cell").any { ADS_MARKER in it.text() }) {
                skip(SkipReason.AD)
                continue
            }
            val links = content.select("a[href]")
            val videoLink = links.firstOrNull()
            if (videoLink == null) {
                skip(SkipReason.NO_URL)
                continue
            }
            val url = videoLink.attr("href")
            val ref = YouTubeUrls.video(url)
            if (ref == null) {
                skip(SkipReason.NOT_A_VIDEO)
                continue
            }
            val channelLink = links.getOrNull(1)
            events += WatchEvent(
                video = ref.id,
                // When the title is no longer known, the link text is the URL itself.
                title = videoLink.text().trim().takeUnless { it.isEmpty() || it == url || it.startsWith("https://") },
                channel = channelLink?.attr("href")?.let(YouTubeUrls::channel),
                channelName = channelLink?.text()?.trim()?.takeIf { it.isNotEmpty() },
                watchedAt = lastTextLine(content)?.let(::parseDate),
                isShort = ref.isShort,
            )
        }
        return ImportResult(events, skipped)
    }

    private fun lastTextLine(content: Element): String? =
        content.childNodes().filterIsInstance<TextNode>().map { it.text().trim() }.lastOrNull { it.isNotEmpty() }

    /**
     * The date is written in the account's language and time zone ("Nov 15, 2026, 7:33:29 PM CET").
     * Formats we don't know yield null: the entry is kept, only without a time.
     */
    internal fun parseDate(text: String): Instant? {
        // Takeout puts narrow no-break spaces inside the date.
        val normalized = text.replace(Regex("[\\u00a0\\u202f\\s]+"), " ").trim()
        for (format in DATE_FORMATS) {
            runCatching { return ZonedDateTime.parse(normalized, format).toInstant() }
        }
        return null
    }

    private const val ADS_MARKER = "From Google Ads"

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss z", Locale.GERMAN),
    )
}
