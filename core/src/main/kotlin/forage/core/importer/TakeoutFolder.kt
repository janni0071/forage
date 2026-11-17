package forage.core.importer

import java.io.File

/** Everything Forage reads from an extracted Takeout archive. */
data class TakeoutData(
    val history: ImportResult,
    /** Playlist name to its videos. */
    val playlists: Map<String, List<PlaylistEntry>>,
    val subscriptions: List<Subscription>,
)

object TakeoutFolder {
    /**
     * Reads an extracted Takeout archive. [dir] may be the archive root or any folder above the
     * YouTube files; they are found by file name, since the folder names are localized.
     */
    fun read(dir: File): TakeoutData {
        require(dir.isDirectory) { "Not a folder: $dir" }
        val files = dir.walkTopDown().maxDepth(5).filter { it.isFile }.toList()

        val history = files.firstOrNull { it.name == "watch-history.json" }?.let { it.inputStream().buffered().use(TakeoutWatchHistory::parse) }
            ?: files.firstOrNull { it.name == "watch-history.html" }?.let { it.inputStream().buffered().use(TakeoutWatchHistoryHtml::parse) }
            ?: ImportResult(emptyList(), emptyMap())
        val playlists = files.filter { it.name.endsWith(PLAYLIST_SUFFIX) }
            .associate { unescape(it.name.removeSuffix(PLAYLIST_SUFFIX)) to TakeoutCsv.playlist(it.readText()) }
        val subscriptions = files.firstOrNull { it.name == "subscriptions.csv" }
            ?.let { TakeoutCsv.subscriptions(it.readText()) }.orEmpty()
        return TakeoutData(history, playlists, subscriptions)
    }

    /** Some unzip tools write non-ASCII characters in file names as `#U00f2`; turn those back into the characters. */
    internal fun unescape(name: String): String =
        ESCAPED.replace(name) { String(Character.toChars(it.groupValues[1].toInt(16))) }

    private val ESCAPED = Regex("#U([0-9a-fA-F]{4,6})")
    private const val PLAYLIST_SUFFIX = "-videos.csv"
}
