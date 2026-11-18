package forage.core.source

import forage.core.PlatformId

/**
 * Several platforms behind one [ExtractorSource] (SPEC, "Future: other platforms"): each call goes
 * to the source of the video's platform. Searches have no video to go by, so callers that search
 * pick a member with [forPlatform].
 */
class Sources(val members: List<ExtractorSource>) : ExtractorSource {
    constructor(vararg members: ExtractorSource) : this(members.toList())

    init {
        require(members.isNotEmpty()) { "No sources" }
        require(members.map { it.platform }.toSet().size == members.size) { "Two sources for one platform" }
    }

    private val byPlatform = members.associateBy { it.platform }

    override val platform: String = members.singleOrNull()?.platform ?: "mixed"
    override val displayName: String = members.joinToString(" and ") { it.displayName }

    /** What any member can do; check the member for a particular video's platform. */
    override val capabilities: Set<Capability> = members.flatMap { it.capabilities }.toSet()

    override fun forPlatform(platform: String): ExtractorSource? = byPlatform[platform]

    private fun of(id: PlatformId): ExtractorSource =
        byPlatform[id.platform] ?: throw SourceException("Forage can't read $id here: no source for ${id.platform}")

    override fun video(id: PlatformId): VideoDetails = of(id).video(id)

    override fun search(query: String): List<VideoStub> =
        members.singleOrNull()?.search(query) ?: error("Search a member: use forPlatform")

    override fun watchUrl(id: PlatformId): String = of(id).watchUrl(id)

    override fun thumbnailUrl(id: PlatformId, large: Boolean, fresh: Boolean): String? =
        byPlatform[id.platform]?.thumbnailUrl(id, large, fresh)

    override fun parseLink(text: String): PlatformId? = members.firstNotNullOfOrNull { it.parseLink(text) }
}
