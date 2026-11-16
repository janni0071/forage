package forage.core.importer

import forage.core.PlatformId
import java.time.Instant
import java.time.OffsetDateTime

data class PlaylistEntry(val video: PlatformId, val addedAt: Instant?)

data class Subscription(val channel: PlatformId, val name: String?)

/** Parsers for Takeout's CSV files. Columns are looked up by header name, since Google reorders and adds them. */
object TakeoutCsv {
    /** `playlists/<name>-videos.csv`: video IDs with the time they were added. Titles are not part of the export. */
    fun playlist(text: String): List<PlaylistEntry> = Csv.read(text).mapNotNull { row ->
        val id = row["Video ID"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val added = row["Playlist Video Creation Timestamp"]
            ?.let { runCatching { OffsetDateTime.parse(it.trim()).toInstant() }.getOrNull() }
        PlaylistEntry(PlatformId.youtube(id), added)
    }

    /** `subscriptions/subscriptions.csv`. */
    fun subscriptions(text: String): List<Subscription> = Csv.read(text).mapNotNull { row ->
        val id = row["Channel Id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Subscription(PlatformId.youtube(id), row["Channel Title"]?.trim()?.takeIf { it.isNotEmpty() })
    }
}
