package forage.core

/**
 * Platform-qualified identifier, e.g. `youtube:dQw4w9WgXcQ`.
 *
 * Videos, channels and playlists are never stored as bare platform IDs (see SPEC, "Future: other platforms").
 */
data class PlatformId(val platform: String, val id: String) {
    init {
        require(platform.isNotEmpty() && ':' !in platform) { "Invalid platform: '$platform'" }
        require(id.isNotEmpty()) { "Empty id for platform '$platform'" }
    }

    override fun toString(): String = "$platform:$id"

    companion object {
        fun parse(value: String): PlatformId {
            val sep = value.indexOf(':')
            require(sep > 0) { "Not a platform-qualified id: '$value'" }
            return PlatformId(value.substring(0, sep), value.substring(sep + 1))
        }

        fun youtube(id: String) = PlatformId(Platforms.YOUTUBE, id)
    }
}

object Platforms {
    const val YOUTUBE = "youtube"
}
