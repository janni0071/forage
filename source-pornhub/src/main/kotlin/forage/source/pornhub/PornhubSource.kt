package forage.source.pornhub

import forage.core.PlatformId
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoMetadata
import forage.core.source.VideoStub
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * The private platform plugin (SPEC, "Sensitive platforms"): the site's public webmasters API,
 * which answers in JSON without an account or key. Only the private vault offers it.
 *
 * The API has search and per-video lookups but no related videos, so candidates come from
 * searches (see the candidate generator). Thumbnail links are signed and expire after about a
 * day, so they are remembered for a few hours only and looked up again after that.
 */
class PornhubSource(
    private val http: Http = Http.default(),
    /** Gap between two requests, whichever thread sends them. */
    private val pause: Duration = Duration.ofMillis(400),
    private val clock: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : ExtractorSource {
    override val platform = PLATFORM
    override val displayName = "Pornhub"
    override val capabilities = setOf(Capability.SEARCH, Capability.DESCRIPTIVE_TAGS)

    /** A GET that returns the status and body; replaceable for tests. */
    fun interface Http {
        fun get(uri: URI): Response

        data class Response(val status: Int, val body: String)

        companion object {
            /** `HttpURLConnection`, which the desktop JVM and Android both have. */
            fun default(): Http = Http { uri ->
                val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 20_000
                    connection.setRequestProperty("Accept", "application/json")
                    val status = connection.responseCode
                    val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    Response(status, body)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    /** A video's preview images, best first: the thumbnail, then the alternate one and the preview frames. */
    private data class Thumb(val urls: List<String>, val at: Instant, val frames: List<String> = emptyList())

    private val thumbs = ConcurrentHashMap<String, Thumb>()
    private val gate = Any()
    private var lastRequest: Instant? = null

    override fun video(id: PlatformId): VideoDetails {
        require(id.platform == platform) { "Not a $displayName id: $id" }
        val json = request("video $id", "video_by_id", "id" to id.id)
        val video = json["video"] as? JsonObject
            ?: throw apiError(json, "video $id", notFoundIsGone = true)
        val parsed = parse(video) ?: throw SourceException("The answer for video $id had no video link")
        return VideoDetails(parsed.copy(id = id), related = emptyList())
    }

    override fun search(query: String): List<VideoStub> {
        val json = request("search '$query'", "search", "search" to query)
        val videos = json["videos"] as? JsonArray ?: run {
            // No results come back as an error object.
            if (isNotFound(json)) return emptyList()
            throw apiError(json, "search '$query'", notFoundIsGone = false)
        }
        return videos.mapNotNull { (it as? JsonObject)?.let(::parse) }.map {
            VideoStub(it.id, it.title, channel = null, it.channelName, it.durationSeconds, isShort = false, it.uploadedAt, it.tags)
        }
    }

    override fun watchUrl(id: PlatformId): String {
        require(id.platform == platform) { "Not a $displayName id: $id" }
        return "$SITE/view_video.php?viewkey=${URLEncoder.encode(id.id, Charsets.UTF_8)}"
    }

    override fun thumbnailUrl(id: PlatformId, large: Boolean, fresh: Boolean): String? = thumbnailUrls(id, large, fresh).firstOrNull()

    /**
     * The images seen most recently in a search or lookup, or a fresh lookup when they are old or
     * were never seen (results reused from the search cache carry none). There is only one size.
     */
    override fun thumbnailUrls(id: PlatformId, large: Boolean, fresh: Boolean): List<String> {
        if (id.platform != platform || large) return emptyList()
        if (!fresh) thumbs[id.id]?.takeIf { it.at > clock() - THUMB_MAX_AGE }?.let { return it.urls }
        // A long list asks for many at once, and the site may ask Forage to slow down: back off and try again.
        var wait = Duration.ofSeconds(3)
        repeat(LOOKUP_ATTEMPTS) { attempt ->
            try {
                video(id)
                return thumbs[id.id]?.urls.orEmpty()
            } catch (e: SourceException.Blocked) {
                if (attempt < LOOKUP_ATTEMPTS - 1) sleep(wait)
                wait = wait.multipliedBy(2)
            } catch (e: SourceException) {
                return emptyList()
            }
        }
        return emptyList()
    }

    override fun parseLink(text: String): PlatformId? = Companion.parseLink(text)

    /** The frames from the last search or lookup if recent, otherwise from a lookup. */
    override fun previewFrames(id: PlatformId): List<String> {
        if (id.platform != platform) return emptyList()
        thumbs[id.id]?.takeIf { it.at > clock() - THUMB_MAX_AGE && it.frames.isNotEmpty() }?.let { return it.frames }
        return runCatching { video(id) }.map { thumbs[id.id]?.frames.orEmpty() }.getOrDefault(emptyList())
    }

    private fun parse(video: JsonObject): VideoMetadata? {
        val key = video.string("url")?.let(::viewkey) ?: return null
        val tags = video.names("tags", "tag_name")
        val categories = video.names("categories", "category")
        val performers = video.names("pornstars", "pornstar_name")
        val frames = (video["thumbs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("src") }
            .filter { it.startsWith("https://") }
        val images = (listOfNotNull(video.string("default_thumb"), video.string("thumb")) + frames)
            .filter { it.startsWith("https://") }.distinct()
        val thumb = images.firstOrNull()
        if (images.isNotEmpty()) thumbs[key] = Thumb(images, clock(), frames)
        return VideoMetadata(
            id = PlatformId(platform, key),
            title = unescape(video.string("title").orEmpty()).ifBlank { "(no title)" },
            description = "",
            // Categories describe a video as much as its tags do.
            tags = (categories + tags).distinctBy { it.lowercase() },
            durationSeconds = video.string("duration")?.let(::parseDuration),
            // The API names no uploader; the performers are the closest thing to a creator.
            channel = null,
            channelName = performers.take(MAX_NAMED_PERFORMERS).joinToString(", ").ifEmpty { null },
            language = null,
            thumbnailUrl = thumb,
            uploadedAt = video.string("publish_date")?.let(::parseDate) ?: thumb?.let(::uploadDateFromImage),
            isShort = false,
            extra = buildMap {
                if (performers.isNotEmpty()) put("performers", performers.joinToString(", "))
                if (categories.isNotEmpty()) put("categories", categories.joinToString(", "))
                video.string("views")?.let { put("viewCount", it) }
                video.string("rating")?.let { put("rating", it) }
            },
        )
    }

    private fun request(what: String, endpoint: String, vararg params: Pair<String, String>): JsonObject {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val uri = URI("$SITE/webmasters/$endpoint?$query")
        waitForTurn()
        val response = try {
            http.get(uri)
        } catch (e: java.io.IOException) {
            throw SourceException("Couldn't reach $displayName for $what: ${e.message ?: e}", e)
        }
        when (response.status) {
            200 -> {}
            403, 451 -> throw SourceException.Blocked(
                "$displayName refused $what (HTTP ${response.status}). The site blocks some regions over age-verification laws; " +
                    "if it opens in your browser, try again later",
            )
            429 -> throw SourceException.Blocked("$displayName asked Forage to slow down ($what)")
            else -> throw SourceException("$displayName answered $what with HTTP ${response.status}")
        }
        val body = response.body.trimStart()
        if (!body.startsWith("{")) {
            throw SourceException.Blocked(
                "$displayName answered $what with a web page instead of data. Where the site requires age verification, " +
                    "it shows that page to programs like Forage too",
            )
        }
        return try {
            Json.parseToJsonElement(body) as JsonObject
        } catch (e: Exception) {
            throw SourceException("$displayName sent an answer to $what that Forage can't read", e)
        }
    }

    private fun waitForTurn() = synchronized(gate) {
        lastRequest?.let { last ->
            val wait = Duration.between(clock(), last + pause)
            if (!wait.isNegative && !wait.isZero) sleep(wait)
        }
        lastRequest = clock()
    }

    private fun isNotFound(json: JsonObject): Boolean {
        val message = json.string("message")?.lowercase() ?: return false
        return NOT_FOUND.any { it in message }
    }

    private fun apiError(json: JsonObject, what: String, notFoundIsGone: Boolean): SourceException {
        val message = json.string("message") ?: "no data"
        return if (notFoundIsGone && isNotFound(json)) SourceException.NotAvailable("Not available: $what ($message)")
        else SourceException("$displayName couldn't answer $what: $message")
    }

    companion object {
        const val PLATFORM = "pornhub"
        private const val SITE = "https://www.pornhub.com"
        private val THUMB_MAX_AGE = Duration.ofHours(6)
        private const val MAX_NAMED_PERFORMERS = 2
        private const val LOOKUP_ATTEMPTS = 3
        private val VIEWKEY = Regex("[A-Za-z0-9]{6,40}")
        private val NOT_FOUND = listOf("no video", "not found", "no such", "removed", "deleted", "inactive")
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** `view_video.php?viewkey=…` and `/embed/…` links on the site's domains, or `pornhub:<viewkey>`. */
        fun parseLink(text: String): PlatformId? {
            val t = text.trim()
            if (t.startsWith("$PLATFORM:")) return t.removePrefix("$PLATFORM:").takeIf(VIEWKEY::matches)?.let { PlatformId(PLATFORM, it) }
            val uri = runCatching { URI(t) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!(host == "pornhub.com" || host.endsWith(".pornhub.com") || host == "pornhub.org" || host.endsWith(".pornhub.org"))) return null
            return viewkey(t)?.let { PlatformId(PLATFORM, it) }
        }

        private fun viewkey(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val path = uri.path.orEmpty()
            val raw = when {
                path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
                else -> uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("viewkey=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            }
            return raw?.takeIf(VIEWKEY::matches)
        }

        /** "10:30" or "1:02:03". */
        internal fun parseDuration(text: String): Long? {
            val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
            if (parts.isEmpty() || parts.size > 3) return null
            return parts.fold(0L) { total, part -> total * 60 + part }.takeIf { it > 0 }
        }

        private val FRAME_NUMBER = Regex("""\)(\d{1,2})\.jpg$""")

        /**
         * The video's numbered frames worked out from one of its image addresses, which differ only in
         * the number before `.jpg` (`…)(mh=…)12.jpg`); empty when the address isn't numbered.
         */
        fun framesFromImage(url: String, count: Int = 16): List<String> {
            val path = url.substringBefore('?')
            val match = FRAME_NUMBER.find(path) ?: return emptyList()
            val number = match.groups[1]!!.range
            return (1..count).map { url.replaceRange(number, it.toString()) }
        }

        private val IMAGE_DATE = Regex("""/videos/(\d{4})(\d{2})/(\d{2})/""")

        /**
         * The day a video was processed, from its image address (`…/videos/202510/05/…`): the upload
         * date, give or take a day, for sources that don't state one (the embed dump).
         */
        fun uploadDateFromImage(url: String): Instant? {
            val (year, month, day) = IMAGE_DATE.find(url)?.destructured ?: return null
            return runCatching { java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt()).atStartOfDay(ZoneOffset.UTC).toInstant() }
                .getOrNull()?.takeIf { it.atZone(ZoneOffset.UTC).year >= 2005 }
        }

        /** "2025-11-03 08:24:34"; the API doesn't say which time zone, so it's read as UTC. */
        internal fun parseDate(text: String): Instant? =
            runCatching { LocalDateTime.parse(text.trim(), DATE).toInstant(ZoneOffset.UTC) }.getOrNull()

        private val ENTITIES = mapOf("&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&#039;" to "'", "&lt;" to "<", "&gt;" to ">")

        private fun unescape(text: String): String = ENTITIES.entries.fold(text) { s, (from, to) -> s.replace(from, to) }.trim()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.names(list: String, field: String): List<String> =
            (this[list] as? JsonArray).orEmpty().mapNotNull { item: JsonElement ->
                (item as? JsonObject)?.string(field)?.let(::unescape)?.takeIf { it.isNotBlank() }
            }
    }
}
