package forage.core.source

import forage.core.PlatformId

/**
 * A platform's whole catalogue on this computer, searchable by tags (SPEC, "Sensitive
 * platforms"): candidates come from matching the profile's tags against every video instead of
 * from a handful of searches.
 */
interface VideoCatalogue {
    val platform: String

    /** Videos in the catalogue. */
    val size: Int

    /**
     * The videos whose tags best match [tagCounts] (tag → how much of the profile or topic
     * carries it), best first, leaving out [exclude]. The catalogue weighs each tag by how rare it
     * is across all its videos, so tags on half the catalogue count for little.
     */
    fun matching(tagCounts: Map<String, Double>, limit: Int, exclude: Set<PlatformId> = emptySet()): List<CatalogueMatch>

    /** The video's preview image, if the catalogue has one. */
    fun thumbnailUrl(id: PlatformId): String? = null
}

/** A catalogue video and the profile tags it shares, most telling first. */
data class CatalogueMatch(val stub: VideoStub, val sharedTags: List<String>)
