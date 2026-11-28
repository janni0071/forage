package forage.core.importer

import forage.core.YouTubeUrls
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream
import java.time.Instant
import java.util.EnumMap

/**
 * Parser for Google Takeout's `YouTube and YouTube Music/history/watch-history.json`.
 *
 * The file is one large JSON array, newest entry first. It is read as a stream because
 * long-lived accounts produce files of 50 MB and more.
 */
object TakeoutWatchHistory {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): ImportResult {
        val events = ArrayList<WatchEvent>()
        val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
        fun skip(reason: SkipReason) = skipped.merge(reason, 1, Int::plus)

        for (entry in json.decodeToSequence<Entry>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
            if (entry.details.any { it.name == ADS_MARKER }) {
                skip(SkipReason.AD)
                continue
            }
            val url = entry.titleUrl
            if (url == null) {
                skip(SkipReason.NO_URL)
                continue
            }
            val ref = YouTubeUrls.video(url)
            if (ref == null) {
                skip(SkipReason.NOT_A_VIDEO)
                continue
            }
            val channel = entry.subtitles.firstOrNull()
            events += WatchEvent(
                video = ref.id,
                title = entry.title?.let { cleanTitle(it, url) },
                channel = channel?.url?.let(YouTubeUrls::channel),
                channelName = channel?.name,
                watchedAt = entry.time?.let { runCatching { Instant.parse(it) }.getOrNull() },
                isShort = ref.isShort,
            )
        }
        return ImportResult(events, skipped)
    }

    /**
     * Takeout wraps the title in a phrase in the account's language ("Watched X", "X angesehen").
     * Strip the wrappers we know; when the video's title is gone, Takeout puts the URL there instead.
     */
    internal fun cleanTitle(raw: String, url: String): String? {
        var title = raw.trim()
        for ((prefix, suffix) in TITLE_WRAPPERS) {
            if (title.length > prefix.length + suffix.length && title.startsWith(prefix) && title.endsWith(suffix)) {
                title = title.substring(prefix.length, title.length - suffix.length).trim()
                break
            }
        }
        return title.takeUnless { it.isEmpty() || it == url || it.startsWith("https://") }
    }

    private const val ADS_MARKER = "From Google Ads"

    // prefix to suffix, per Takeout language. Unknown languages keep the raw title until metadata is fetched.
    private val TITLE_WRAPPERS = listOf(
        "Watched " to "",              // en
        "" to " angesehen",            // de
        "Vous avez regardé " to "",    // fr
        "Has visto " to "",            // es
        "Hai guardato " to "",         // it
        "Je hebt " to " bekeken",      // nl
    )

    @Serializable
    private data class Entry(
        val title: String? = null,
        val titleUrl: String? = null,
        val subtitles: List<Link> = emptyList(),
        val time: String? = null,
        val details: List<Detail> = emptyList(),
    )

    @Serializable
    private data class Link(val name: String? = null, val url: String? = null)

    @Serializable
    private data class Detail(val name: String? = null)
}
