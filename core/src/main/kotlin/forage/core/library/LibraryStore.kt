package forage.core.library

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.importer.Subscription
import forage.core.importer.TakeoutData
import forage.core.importer.TakeoutFolder
import forage.core.importer.WatchEvent
import java.time.Instant

data class ImportSummary(val watchEvents: Int, val playlists: Map<String, Int>, val subscriptions: Int)

/** What the library knows about a video without any metadata fetch. */
data class LibraryVideo(
    val id: PlatformId,
    val title: String?,
    val channel: PlatformId?,
    val channelName: String?,
    val isShort: Boolean,
    val durationSeconds: Long? = null,
    val uploadedAt: Instant? = null,
)

/** Where a video appears in the library: the history, playlists and the watch-later queue. */
data class VideoInLibrary(
    val watchCount: Int = 0,
    val lastWatched: Instant? = null,
    val playlists: List<String> = emptyList(),
    val savedForLater: Boolean = false,
)

/** A video in the watch-later queue and when it was saved. */
data class SavedVideo(val id: PlatformId, val savedAt: Instant)

/**
 * The vault's library: watch history, imported playlists, subscriptions and the watch-later queue.
 * Shared by every profile in the vault. Importing never touches a profile (SPEC: every import
 * lands videos as watched, not in profile).
 */
class LibraryStore(db: ForageDatabase, private val clock: () -> Instant = Instant::now) {
    private val queries = db.libraryQueries

    /** Adds a Takeout export. Importing the same or an overlapping export again adds nothing twice. */
    fun importTakeout(data: TakeoutData): ImportSummary {
        val now = clock().epochSecond
        queries.transaction {
            for (e in data.history.events) {
                remember(e.video, e.title, e.channel, e.channelName, e.isShort)
                queries.insertWatchEvent(e.video.toString(), e.watchedAt?.epochSecond ?: 0)
            }
            for ((playlist, entries) in data.playlists) {
                // YouTube's Watch later is the queue, not a playlist of its own.
                if (playlist in TakeoutFolder.WATCH_LATER) {
                    for (entry in entries) {
                        remember(entry.video)
                        queries.insertWatchLater(entry.video.toString(), entry.addedAt?.epochSecond ?: now)
                    }
                    continue
                }
                queries.insertPlaylist(playlist, entries.mapNotNull { it.addedAt?.epochSecond }.minOrNull() ?: now)
                for (entry in entries) {
                    remember(entry.video)
                    queries.upsertPlaylistItem(playlist, entry.video.toString(), entry.addedAt?.epochSecond)
                }
            }
            for (s in data.subscriptions) {
                queries.insertSubscription(s.channel.toString(), now)
                queries.updateSubscription(s.name, s.channel.toString())
            }
        }
        return ImportSummary(data.history.events.size, data.playlists.mapValues { it.value.size }, data.subscriptions.size)
    }

    /** Makes a video known, keeping whatever details were known before where the new ones are missing. */
    fun remember(
        id: PlatformId,
        title: String? = null,
        channel: PlatformId? = null,
        channelName: String? = null,
        isShort: Boolean = false,
        durationSeconds: Long? = null,
        uploadedAt: Instant? = null,
    ) {
        queries.insertLibraryVideo(id.toString(), clock().epochSecond)
        queries.updateLibraryVideo(title, channel?.toString(), channelName, isShort, durationSeconds, uploadedAt?.epochSecond, id.toString())
    }

    fun video(id: PlatformId): LibraryVideo? = queries.selectLibraryVideo(id.toString()).executeAsOneOrNull()
        ?.let {
            LibraryVideo(
                PlatformId.parse(it.id), it.title, it.channel_id?.let(PlatformId::parse), it.channel_name, it.is_short,
                it.duration_seconds, it.uploaded_at?.let(Instant::ofEpochSecond),
            )
        }

    /**
     * The watch history as events, or with [playlist] the videos of that playlist, where "watched
     * at" stands in for "added at" so the same date filters work on both.
     */
    fun events(playlist: String? = null): List<WatchEvent> {
        val known = queries.selectLibraryVideos().executeAsList().associateBy { it.id }
        fun event(videoId: String, at: Long?): WatchEvent {
            val v = known[videoId]
            return WatchEvent(
                video = PlatformId.parse(videoId),
                title = v?.title,
                channel = v?.channel_id?.let(PlatformId::parse),
                channelName = v?.channel_name,
                watchedAt = at?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
                isShort = v?.is_short ?: false,
            )
        }
        return if (playlist == null) queries.selectWatchEvents().executeAsList().map { event(it.video_id, it.watched_at) }
        else queries.selectPlaylistItems(playlist).executeAsList().map { event(it.video_id, it.added_at) }
    }

    /** A playlist's videos with when each was added, where known, in the order they were added. */
    fun playlistItems(name: String): List<Pair<PlatformId, Instant?>> =
        queries.selectPlaylistItemsInOrder(name).executeAsList()
            .map { PlatformId.parse(it.video_id) to it.added_at?.let(Instant::ofEpochSecond) }

    /** Playlist names with their number of videos, the newest playlist first. */
    fun playlists(): Map<String, Long> = queries.selectPlaylists().executeAsList().associate { it.name to it.videos }

    /** Makes an empty playlist; false when one by that name exists already. */
    fun createPlaylist(name: String): Boolean {
        if (name in playlists()) return false
        queries.insertPlaylist(name, clock().epochSecond)
        return true
    }

    /** Renames a playlist; false when [new] is taken. */
    fun renamePlaylist(old: String, new: String): Boolean {
        if (old == new) return true
        if (new in playlists()) return false
        queries.transaction {
            queries.renamePlaylist(new, old)
            queries.renamePlaylistItems(new, old)
        }
        return true
    }

    /** Deletes a playlist. Its videos stay in the library and keep their likes. */
    fun deletePlaylist(name: String) = queries.transaction {
        queries.deletePlaylistItems(name)
        queries.deletePlaylist(name)
    }

    /** Adds videos to a playlist, creating it if needed. Returns how many weren't in it yet. */
    fun addToPlaylist(name: String, videos: Collection<PlatformId>): Int {
        val now = clock().epochSecond
        var added = 0
        queries.transaction {
            queries.insertPlaylist(name, now)
            val present = queries.selectPlaylistItems(name).executeAsList().mapTo(HashSet()) { it.video_id }
            for (v in videos) {
                remember(v)
                if (v.toString() !in present) added++
                queries.insertPlaylistItem(name, v.toString(), now)
            }
        }
        return added
    }

    fun removeFromPlaylist(name: String, videos: Collection<PlatformId>) = queries.transaction {
        videos.forEach { queries.deletePlaylistItem(name, it.toString()) }
    }

    /** Records that a video was opened from Forage, for the history. */
    fun recordWatch(id: PlatformId) {
        queries.transaction {
            remember(id)
            queries.insertWatchEvent(id.toString(), clock().epochSecond)
        }
    }

    /** Takes videos out of the history. They may be recommended again afterwards. */
    fun forget(videos: Collection<PlatformId>) = queries.transaction {
        videos.forEach { queries.deleteWatchEventsOfVideo(it.toString()) }
    }

    /** Empties the history, imported entries included. */
    fun clearHistory() = queries.deleteWatchEvents()

    /** When each video was first watched or saved, where the import said so. */
    fun firstSeen(): Map<PlatformId, Instant> {
        val result = HashMap<PlatformId, Instant>()
        fun seen(id: String, at: Long?) {
            if (at != null && at > 0) result.merge(PlatformId.parse(id), Instant.ofEpochSecond(at)) { a, b -> minOf(a, b) }
        }
        queries.selectWatchEvents().executeAsList().forEach { seen(it.video_id, it.watched_at) }
        playlists().keys.forEach { p -> queries.selectPlaylistItems(p).executeAsList().forEach { seen(it.video_id, it.added_at) } }
        return result
    }

    /** Everything watched or saved: never to be recommended, whatever the profile. */
    fun knownVideoIds(): Set<PlatformId> = queries.selectKnownVideoIds().executeAsList().map(PlatformId::parse).toSet()

    /** Follows a channel, as a subscription would; following it again only updates the name. */
    fun follow(channel: PlatformId, name: String?) = queries.transaction {
        queries.insertSubscription(channel.toString(), clock().epochSecond)
        queries.updateSubscription(name, channel.toString())
    }

    fun unfollow(channel: PlatformId) = queries.deleteSubscription(channel.toString())

    fun follows(channel: PlatformId): Boolean = queries.selectSubscription(channel.toString()).executeAsOneOrNull() != null

    /** Everything the library holds about one video, for its page. */
    fun about(id: PlatformId): VideoInLibrary {
        val watches = queries.selectWatchEventsOfVideo(id.toString()).executeAsList()
        return VideoInLibrary(
            watchCount = watches.size,
            lastWatched = watches.filter { it > 0 }.maxOrNull()?.let(Instant::ofEpochSecond),
            playlists = queries.selectPlaylistsOfVideo(id.toString()).executeAsList(),
            savedForLater = queries.isSavedForLater(id.toString()).executeAsOne() > 0,
        )
    }

    fun subscriptions(): List<Subscription> =
        queries.selectSubscriptions().executeAsList().map { Subscription(PlatformId.parse(it.channel_id), it.name) }

    fun saveForLater(id: PlatformId) = queries.insertWatchLater(id.toString(), clock().epochSecond)

    fun removeFromWatchLater(id: PlatformId) = queries.deleteWatchLater(id.toString())

    /** The watch-later queue, most recently saved first. */
    fun watchLater(): List<PlatformId> = savedForLater().map { it.id }

    /** The queue with the time each video was saved, most recent first. */
    fun savedForLater(): List<SavedVideo> =
        queries.selectWatchLater().executeAsList().map { SavedVideo(PlatformId.parse(it.video_id), Instant.ofEpochSecond(it.added_at)) }

    /**
     * Takes a video off the queue as watched. It joins the watch history, so it stays out of
     * recommendations; watching never changes a profile.
     */
    fun markWatched(id: PlatformId) {
        queries.transaction {
            queries.insertWatchEvent(id.toString(), clock().epochSecond)
            queries.deleteWatchLater(id.toString())
        }
    }

    /** Every video the library knows, the most recently added first. */
    fun allVideos(): List<LibraryVideo> = queries.selectLibraryVideosNewestFirst().executeAsList().map {
        LibraryVideo(
            PlatformId.parse(it.id), it.title, it.channel_id?.let(PlatformId::parse), it.channel_name, it.is_short,
            it.duration_seconds, it.uploaded_at?.let(Instant::ofEpochSecond),
        )
    }

    /** How many different videos the history holds. */
    fun watchedVideoCount(): Long = queries.countWatchedVideos().executeAsOne()

    fun counts(): Pair<Long, Long> = queries.countLibraryVideos().executeAsOne() to queries.countWatchEvents().executeAsOne()
}
