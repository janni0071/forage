package forage.app

import forage.core.PlatformId
import forage.core.source.ExtractorSource
import forage.core.source.VideoCatalogue
import forage.core.sql.SqlFile
import java.io.File
import java.io.InputStream

/**
 * The private vault's own site, in the version of Forage that has one ([Edition.vaultSite]: Full
 * has it, Standard has none, and none of its code). Everything the shared app does that is
 * particular to the site goes through here: its source, its performers, its catalogue (the site's
 * public list of all its videos), its preview frames and what its player errors mean.
 */
interface VaultSite {
    /** Its platform id, as in `pornhub:…`. */
    val platform: String

    /** Its name, for what's said about it. */
    val name: String

    fun newSource(): ExtractorSource

    /** A performer's id from their name, so they can be followed one by one. */
    fun performerId(name: String): PlatformId

    /** Whether [channel] is one of the site's performers (rather than an uploader). */
    fun isPerformer(channel: PlatformId): Boolean

    /** A video of the site's from a shared link; null when the link isn't one. */
    fun parseLink(text: String): PlatformId?

    /** Frames from across a video, worked out from its catalogue thumbnail; empty where that can't be done. */
    fun framesFromImage(url: String): List<String>

    /** What a player's last lines mean for the site's videos, when it's a known case; null otherwise. */
    fun explainPlayerFailure(lines: List<String>): String? = null

    // The catalogue

    /** The downloaded dump's file name, as the site offers it. */
    val dumpFileName: String

    /** Videos seen by fewer people than this are left out of the catalogue. */
    val catalogueMinViews: Long

    fun openCatalogue(file: File, opener: SqlFile.Opener): SiteCatalogue

    /** Imports the dump [open] gives ([size] bytes) into [target]; throws [CatalogueLayoutException] for a file not laid out as expected. */
    fun importCatalogue(
        open: () -> InputStream, size: Long, target: File, progress: (Double) -> Unit, cancelled: () -> Boolean,
        beforeReplace: () -> Unit, opener: SqlFile.Opener,
    ): CatalogueImport

    /** Downloads the dump into [target], continuing a download that broke off; throws with a readable message. */
    fun downloadDump(target: File, progress: (Long, Long?) -> Unit, cancelled: () -> Boolean)
}

/** A site's catalogue, open. */
interface SiteCatalogue : VideoCatalogue, AutoCloseable {
    /** Made by this version of Forage (an older one's is left alone until imported again). */
    val current: Boolean

    /** When it was imported, in epoch seconds. */
    val importedAt: Long
}

/** What a catalogue import did. */
data class CatalogueImport(val videos: Int, val skippedUnreadable: Int, val skippedFewViews: Int, val layout: String)

/** The dump isn't laid out the way Forage expects; [layout] describes it without its content. */
class CatalogueLayoutException(message: String, val layout: String) : Exception(message)
