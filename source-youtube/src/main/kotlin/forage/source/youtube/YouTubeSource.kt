package forage.source.youtube

import forage.core.PlatformId
import forage.core.Platforms
import forage.core.YouTubeUrls
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoMetadata
import forage.core.source.VideoStub
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import forage.core.source.ChannelFeed
import forage.core.source.AudioTrack
import forage.core.source.Playback
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** YouTube through NewPipeExtractor: no account, no API key. */
class YouTubeSource : ExtractorSource {
    init {
        NewPipe.init(UrlConnectionDownloader(), Localization("en", "US"))
    }

    override val platform = Platforms.YOUTUBE
    override val displayName = "YouTube"
    override val capabilities = setOf(Capability.RELATED_VIDEOS, Capability.SEARCH, Capability.CHANNEL_FEED)

    override fun video(id: PlatformId): VideoDetails = guarded("video $id") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        val metadata = VideoMetadata(
            id = id,
            title = info.name,
            description = plainText(info.description),
            tags = info.tags.orEmpty(),
            durationSeconds = info.duration.takeIf { it > 0 },
            channel = info.uploaderUrl?.let(YouTubeUrls::channel),
            channelName = info.uploaderName?.takeIf { it.isNotBlank() },
            language = info.languageInfo?.toLanguageTag(),
            thumbnailUrl = info.thumbnails.maxByOrNull { it.width }?.url,
            uploadedAt = info.uploadDate?.offsetDateTime()?.toInstant(),
            isShort = info.isShortFormContent,
            extra = buildMap {
                info.category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
                if (info.viewCount >= 0) put("viewCount", info.viewCount.toString())
            },
        )
        VideoDetails(metadata, stubs(info.relatedItems))
    }

    override fun search(query: String): List<VideoStub> = guarded("search '$query'") {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        stubs(SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems)
    }

    /**
     * The channel page for its name and picture, then its Videos tab: the latest uploads with
     * their lengths, and without Shorts, which have a tab of their own.
     */
    override fun channelFeed(channel: PlatformId, name: String?): ChannelFeed = guarded("channel ${name ?: channel.id}") {
        require(channel.platform == platform) { "Not a YouTube channel: $channel" }
        val info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/${channel.id}")
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val videos = videosTab?.let { stubs(ChannelTabInfo.getInfo(ServiceList.YouTube, it).relatedItems) }.orEmpty()
        ChannelFeed(
            videos = videos.map { it.copy(channel = it.channel ?: channel, channelName = it.channelName ?: info.name) },
            name = info.name?.takeIf { it.isNotBlank() },
            avatarUrl = smallest(info.avatars),
        )
    }

    /** A channel link in any of YouTube's shapes (/channel/, /@handle, /c/, /user/), resolved by its page. */
    override fun channelFromLink(text: String): Pair<PlatformId, String?>? {
        val t = text.trim()
        if (!CHANNEL_LINK.containsMatchIn(t)) return null
        YouTubeUrls.channel(t)?.let { direct -> if (direct.id.startsWith("UC")) return direct to null }
        val url = if (t.startsWith("http")) t else "https://$t"
        return guarded("channel link") {
            // The link handler resolves a handle (@name) to its channel through YouTube's API; the
            // channel page itself doesn't always come back readable for a handle.
            val handler = ServiceList.YouTube.channelLHFactory.fromUrl(url)
            val extractor = ServiceList.YouTube.getChannelExtractor(handler)
            extractor.fetchPage()
            val id = extractor.id?.takeIf { it.startsWith("UC") } ?: YouTubeUrls.channel(extractor.url ?: "")?.id
                ?: throw SourceException("YouTube didn't say which channel that is")
            PlatformId.youtube(id) to extractor.name?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The best video stream up to 1080p (H.264 first, which every phone decodes in hardware) and
     * the best audio stream, both plain HTTP files; a stream with both where there's no separate one.
     */
    override fun playback(id: PlatformId): Playback = guarded("streams of $id") {
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        fun plain(s: org.schabi.newpipe.extractor.stream.Stream) =
            s.isUrl && s.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP
        val videos = info.videoOnlyStreams.orEmpty().filter { plain(it) && it.height in 1..1080 }
        val video = videos.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 }.maxByOrNull { it.height }
            ?: videos.maxByOrNull { it.height }
        val tracks = audioTracks(
            info.audioStreams.orEmpty().filter(::plain).map {
                AudioCandidate(
                    it.content, it.audioTrackId, it.audioTrackName, it.audioLocale,
                    original = it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL,
                    secondary = it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.DESCRIPTIVE,
                    m4a = it.format == org.schabi.newpipe.extractor.MediaFormat.M4A, bitrate = it.averageBitrate,
                )
            },
        )
        if (video != null && tracks.isNotEmpty()) {
            return@guarded Playback(
                video.content, tracks.first().url, quality = "${video.height}p", headers = streamHeaders(video.content),
                audioTracks = tracks.takeIf { it.size > 1 }.orEmpty(),
            )
        }
        val muxed = info.videoStreams.orEmpty().filter(::plain).maxByOrNull { it.height }
            ?: throw SourceException("YouTube offered no stream Forage can play for $id")
        Playback(muxed.content, quality = "${muxed.height}p", headers = streamHeaders(muxed.content))
    }

    /**
     * One stream per audio track (the best of each: M4A first, then the higher bitrate), the
     * original first: YouTube adds dubbed tracks, often made by machine, and the extractor lists
     * them in no particular order.
     */
    internal fun audioTracks(candidates: List<AudioCandidate>): List<AudioTrack> {
        val best = candidates.groupBy { it.trackId }.values.map { group ->
            group.sortedWith(compareBy<AudioCandidate>({ !it.m4a }, { -it.bitrate })).first()
        }
        return best.sortedWith(compareBy<AudioCandidate>({ !it.original }, { it.secondary })).mapIndexed { i, it ->
            val name = it.name?.takeIf { n -> n.isNotBlank() }
                ?: it.locale?.getDisplayName(java.util.Locale.ENGLISH)?.takeIf { n -> n.isNotBlank() }
                ?: "Track ${i + 1}"
            AudioTrack(it.url, if (it.original && "original" !in name.lowercase()) "$name (original)" else name, it.original)
        }
    }

    internal data class AudioCandidate(
        val url: String, val trackId: String?, val name: String?, val locale: java.util.Locale?,
        val original: Boolean, val secondary: Boolean, val m4a: Boolean, val bitrate: Int,
    )

    /**
     * YouTube serves a stream only to the kind of client it was given to: the same user agent for
     * the streams the extractor got as the visionOS app, the site's origin for those it got as the web.
     */
    private fun streamHeaders(url: String): Map<String, String> = when {
        org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
            mapOf("User-Agent" to org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent(null))
        org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl(url) ->
            mapOf("User-Agent" to WEB_AGENT, "Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
        else -> mapOf("User-Agent" to WEB_AGENT)
    }

    /** The channel page, for its picture alone. */
    override fun channelPicture(channel: PlatformId): String? = guarded("channel ${channel.id}") {
        if (channel.platform != platform) return@guarded null
        smallest(ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/${channel.id}").avatars)
    }

    /** A small picture is plenty for a round badge. */
    private fun smallest(images: List<org.schabi.newpipe.extractor.Image>): String? =
        images.filter { it.width in 1..240 }.maxByOrNull { it.width }?.url ?: images.firstOrNull()?.url

    /** A link in any of YouTube's shapes, a bare 11-character ID, or `youtube:<id>`. */
    override fun parseLink(text: String): PlatformId? {
        val t = text.trim()
        if (BARE_ID.matches(t)) return PlatformId.youtube(t)
        if (t.startsWith("$platform:")) return runCatching { PlatformId.parse(t) }.getOrNull()
        return YouTubeUrls.video(t)?.id
    }

    override fun watchUrl(id: PlatformId): String {
        require(id.platform == platform) { "Not a YouTube id: $id" }
        return "https://www.youtube.com/watch?v=${id.id}"
    }

    /** YouTube descriptions arrive as HTML with links; the profile wants the text only. */
    private fun plainText(description: Description?): String {
        val content = description?.content().orEmpty()
        if (description?.type() != Description.Type.HTML) return content
        val doc = Jsoup.parse(content)
        doc.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return doc.wholeText().trim()
    }

    /** Every YouTube video has the 320×180 image; most also have a 1280×720 one. No metadata fetch needed. */
    override fun thumbnailUrl(id: PlatformId, large: Boolean, fresh: Boolean): String? =
        if (id.platform != platform) null
        else "https://i.ytimg.com/vi/${id.id}/${if (large) "hq720" else "mqdefault"}.jpg"

    private fun stubs(items: List<InfoItem>?): List<VideoStub> =
        items.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val ref = YouTubeUrls.video(item.url) ?: return@mapNotNull null
            VideoStub(
                id = ref.id,
                title = item.name,
                channel = item.uploaderUrl?.let(YouTubeUrls::channel),
                channelName = item.uploaderName?.takeIf { it.isNotBlank() },
                durationSeconds = item.duration.takeIf { it > 0 },
                isShort = item.isShortFormContent || ref.isShort,
                uploadedAt = item.uploadDate?.offsetDateTime()?.toInstant(),
            )
        }

    private inline fun <T> guarded(what: String, block: () -> T): T = try {
        block()
    } catch (e: ReCaptchaException) {
        throw SourceException.Blocked("YouTube answered $what with a bot check", e)
    } catch (e: SignInConfirmNotBotException) {
        throw SourceException.Blocked("YouTube wants a sign-in to confirm $what is not from a bot", e)
    } catch (e: ContentNotAvailableException) {
        throw SourceException.NotAvailable("Not available: $what (${e.message})", e)
    } catch (e: Exception) {
        // A bot-check or proxy page where the API's JSON should be: the extractor reports it as a parsing error.
        if (e.message?.contains("Got HTML document") == true) {
            throw SourceException.Blocked("YouTube answered $what with a web page instead of data, which usually means this network is being bot-checked", e)
        }
        throw SourceException("Failed to load $what: ${e.message ?: e.toString()}", e)
    }

    private companion object {
        val BARE_ID = Regex("[A-Za-z0-9_-]{11}")
        val CHANNEL_LINK = Regex("""youtube\.com/(channel/|@|c/|user/)""", RegexOption.IGNORE_CASE)
        const val WEB_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
