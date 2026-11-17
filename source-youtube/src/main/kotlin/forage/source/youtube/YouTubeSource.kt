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
        NewPipe.init(JdkDownloader(), Localization("en", "US"))
    }

    override val platform = Platforms.YOUTUBE
    override val capabilities = setOf(Capability.RELATED_VIDEOS, Capability.SEARCH)

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
    override fun thumbnailUrl(id: PlatformId, large: Boolean): String? =
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
}
