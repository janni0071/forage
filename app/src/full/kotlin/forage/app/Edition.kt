package forage.app

import forage.core.PlatformId
import forage.core.source.CatalogueMatch
import forage.core.source.ExtractorSource
import forage.core.sql.SqlFile
import forage.source.pornhub.DumpDownload
import forage.source.pornhub.PornhubCatalogue
import forage.source.pornhub.PornhubSource
import java.io.File
import java.io.InputStream

/** Forage Full: Standard, and Pornhub in the private vault. */
object Edition {
    const val NAME = "Full"

    /** The release files of this version: the APK, or the Linux package. */
    fun releaseFile(version: String, mobile: Boolean) = if (mobile) "forage-full-$version.apk" else "forage-full_${version}_amd64.deb"

    val vaultSite: VaultSite? = PornhubSite
}

/** Pornhub, the private vault's site in Full. */
private object PornhubSite : VaultSite {
    override val platform = PornhubSource.PLATFORM
    override val name = "Pornhub"

    override fun newSource(): ExtractorSource = PornhubSource()
    override fun performerId(name: String): PlatformId = PornhubSource.performerId(name)
    override fun isPerformer(channel: PlatformId) = channel.platform == platform && channel.id.startsWith("pornstar/")
    override fun parseLink(text: String): PlatformId? = PornhubSource.parseLink(text)
    override fun framesFromImage(url: String): List<String> = PornhubSource.framesFromImage(url)

    override fun explainPlayerFailure(lines: List<String>): String? {
        val text = lines.joinToString("\n")
        return when {
            "premium" in text.lowercase() ->
                "This video is for Pornhub Premium members only; Forage can't play it."
            "[PornHub]" in text && "HTTP Error 410" in text ->
                "Pornhub turns yt-dlp away (\"410 Gone\") unless it can pass for a browser, which takes the curl_cffi add-on. " +
                    "With yt-dlp from pipx: pipx inject yt-dlp curl_cffi (from pip: pip install curl_cffi). " +
                    "If the video still won't play, it may be for Premium members only."
            else -> null
        }
    }

    override val dumpFileName = "pornhub.com-db.zip"
    override val catalogueMinViews = PornhubCatalogue.MIN_VIEWS

    override fun openCatalogue(file: File, opener: SqlFile.Opener): SiteCatalogue = Catalogue(PornhubCatalogue.open(file, opener))

    override fun importCatalogue(
        open: () -> InputStream, size: Long, target: File, progress: (Double) -> Unit, cancelled: () -> Boolean,
        beforeReplace: () -> Unit, opener: SqlFile.Opener,
    ): CatalogueImport = try {
        PornhubCatalogue.import(open, size, target, progress = progress, cancelled = cancelled, beforeReplace = beforeReplace, opener = opener)
            .let { CatalogueImport(it.videos, it.skippedUnreadable, it.skippedFewViews, it.layout) }
    } catch (e: PornhubCatalogue.LayoutException) {
        throw CatalogueLayoutException(e.message ?: "The dump isn't laid out as expected", e.layout)
    }

    override fun downloadDump(target: File, progress: (Long, Long?) -> Unit, cancelled: () -> Boolean) {
        DumpDownload.download(target, progress = progress, cancelled = cancelled)
    }

    private class Catalogue(private val c: PornhubCatalogue) : SiteCatalogue {
        override val platform get() = c.platform
        override val size get() = c.size
        override val current get() = c.current
        override val importedAt get() = c.importedAt
        override fun matching(tagCounts: Map<String, Double>, limit: Int, exclude: Set<PlatformId>): List<CatalogueMatch> = c.matching(tagCounts, limit, exclude)
        override fun thumbnailUrl(id: PlatformId): String? = c.thumbnailUrl(id)
        override fun close() = c.close()
    }
}
