package forage.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.embedding.Embedder
import forage.core.embedding.EmbeddingStore
import forage.core.importer.TakeoutData
import forage.core.importer.TakeoutFolder
import forage.core.language.LanguageDetector
import forage.core.library.LibraryStore
import forage.core.library.WatchHistory
import forage.core.library.WatchedVideo
import forage.core.metadata.FetchOutcome
import forage.core.metadata.MetadataCache
import forage.core.metadata.MetadataFetcher
import forage.core.metadata.SearchCache
import forage.core.profile.ProfileRef
import forage.core.profile.ProfileStore
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.recommend.Cluster
import forage.core.recommend.Discover
import forage.core.recommend.EmptyProfileException
import forage.core.recommend.RankingConfig
import forage.core.recommend.TopicPreference
import forage.core.recommend.TopicStore
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoStub
import forage.core.source.Capability
import forage.core.source.Sources
import forage.source.pornhub.DumpDownload
import forage.source.pornhub.PornhubCatalogue
import forage.source.pornhub.PornhubSource
import forage.source.youtube.YouTubeSource
import forage.core.vault.VaultDamagedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

enum class Screen { LIBRARY, TOPICS, DISCOVER, LATER }

enum class ProfileDialog { NEW, DELETE, PRIVATE }

/** The lists the library shows: the profile's likes, the history, each playlist, and what was turned down. */
sealed interface LibrarySource {
    val label: String

    data object Liked : LibrarySource { override val label = "Liked" }
    data object History : LibrarySource { override val label = "History" }
    data class Playlist(val name: String, val size: Long) : LibrarySource { override val label = name }
    data object NotInterested : LibrarySource { override val label = "Not interested" }
}

/** Narrows the history or a playlist by what the profile thinks of each video. */
enum class StateFilter(val label: String) {
    ALL("All"), LIKED("Liked"), NEITHER("Not liked"), NOT_INTERESTED("Not interested");

    fun matches(state: VideoState?) = when (this) {
        ALL -> true
        LIKED -> state == VideoState.LIKED
        NEITHER -> state == null
        NOT_INTERESTED -> state == VideoState.NOT_INTERESTED
    }
}

enum class HistorySort(val label: String) { RECENT("Recent"), MOST_WATCHED("Most watched") }

/** The library's dialogs for playlists and the history. */
sealed interface LibraryDialog {
    data class AddToPlaylist(val videos: List<PlatformId>) : LibraryDialog
    data object NewPlaylist : LibraryDialog
    data class RenamePlaylist(val name: String) : LibraryDialog
    data class DeletePlaylist(val name: String) : LibraryDialog
    data object ClearHistory : LibraryDialog
}

data class LibraryRow(
    val id: PlatformId,
    val title: String?,
    val channelName: String?,
    val watchCount: Int,
    val lastSeen: Instant?,
    val durationSeconds: Long?,
    val isShort: Boolean,
    val state: VideoState?,
    val hasMetadata: Boolean,
)

enum class Feedback { LIKE, NOT_INTERESTED, SAVED }

/** A video in the watch-later queue, with what the queue shows about it. */
data class LaterItem(
    val id: PlatformId,
    val title: String?,
    val channelName: String?,
    val durationSeconds: Long?,
    val uploadedAt: Instant?,
    val savedAt: Instant,
)

enum class LaterSort(val label: String, val shortLabel: String) {
    RECENT("Recently saved", "Recent"), OLDEST("Oldest first", "Oldest"), SHORTEST("Shortest first", "Shortest"),
}

data class PickView(val pick: StoredPick, val feedback: Set<Feedback>)

/** A long-running task. [total] is null when progress can't be counted. */
data class Job(val label: String, val done: Int = 0, val total: Int? = null, val detail: String = "")

/**
 * Everything the desktop UI shows and does, kept free of Compose UI code so it can be tested
 * and driven headlessly. State lives in snapshot state; work runs on [io].
 */
class AppModel(
    val dataDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val storage: Storage,
    /** Shared with the private vault's model, so the models load only once. */
    private val models: Models,
    /** What the desktop and Android apps do differently. */
    val platform: Platform,
    /** The private vault also offers the sensitive platform (SPEC, "Sensitive platforms"). */
    private val privateSourceFactory: () -> ExtractorSource = { Sources(YouTubeSource(), PornhubSource()) },
    /** Replaceable for tests; created on first use otherwise, since it starts network clients. Last, so tests can pass it as a trailing lambda. */
    private val sourceFactory: () -> ExtractorSource = { YouTubeSource() },
) {
    val db: ForageDatabase = storage.db
    val cache = MetadataCache(db)
    val library = LibraryStore(db)
    val profiles = ProfileStore(db)
    val isPrivate = storage.isPrivate
    val settings = storage.settings

    /** The profile everything on screen belongs to. Profiles share the vault's library but nothing else. */
    var profile by mutableStateOf(
        settings.lastProfile?.let(profiles::find) ?: profiles.profiles().firstOrNull()
            ?: profiles.profile(if (storage.isPrivate) "Private" else ProfileStore.DEFAULT)
    )
        private set

    /** Every profile in this vault with the number of videos in it, for the switcher. */
    var profileList by mutableStateOf<List<Pair<ProfileRef, Int>>>(emptyList())
        private set

    var settingsOpen by mutableStateOf(false)

    /** Takes effect at the next start: the window is created with or without the system frame. */
    var systemTitleBar by mutableStateOf(settings.systemTitleBar)
        private set

    fun useSystemTitleBar(on: Boolean) {
        settings.systemTitleBar = on
        systemTitleBar = on
    }

    /** Where videos open, on platforms that offer a choice; the first usable one until the user picks. */
    var player by mutableStateOf(
        settings.player ?: platform.players.firstOrNull { it != Player.BROWSER && platform.playerProblem(it) == null } ?: Player.BROWSER
    )
        private set

    fun choosePlayer(value: Player) {
        settings.player = value
        player = value
    }

    /** Opens the folder the vault lives in, in the system's file manager. */
    fun openDataFolder() {
        if (!platform.openFolder(dataDir.absoluteFile)) message = "Couldn't open the folder: ${dataDir.absolutePath}"
    }

    var profileMenuOpen by mutableStateOf(false)
    var profileDialog by mutableStateOf<ProfileDialog?>(null)

    private val source by lazy(sourceFactory)
    val thumbnails = Thumbnails(storage.thumbnailsDir, decodeImage = platform::decodeImage) { id, large, fresh ->
        // The catalogue's links are stable; the platform is asked only when they're refused.
        val fromCatalogue = if (!large && !fresh && isPrivate && id.platform == PornhubSource.PLATFORM) openCatalogue()?.thumbnailUrl(id) else null
        fromCatalogue?.let { listOf(it) } ?: source.thumbnailUrls(id, large, fresh)
    }

    /**
     * Frames from across the video for a preview on hover; empty where there are none. Worked out
     * from the catalogue's thumbnail where possible, which needs no request; [fromPlatform] asks
     * the platform instead, for when those don't load. Blocks.
     */
    fun previewFrames(id: PlatformId, fromPlatform: Boolean = false): List<String> {
        if (!fromPlatform && isPrivate && id.platform == PornhubSource.PLATFORM) {
            openCatalogue()?.thumbnailUrl(id)?.let(PornhubSource::framesFromImage)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching { source.previewFrames(id) }.getOrDefault(emptyList())
    }

    /** Platforms whose topics are grouped by their tags. */
    private val tagPlatforms by lazy {
        ((source as? Sources)?.members ?: listOf(source))
            .filter { Capability.DESCRIPTIVE_TAGS in it.capabilities }.mapTo(HashSet()) { it.platform }
    }
    private val searchCache = SearchCache(db)
    private val topicStore = TopicStore(db)
    private fun embedder(update: (String) -> Unit = {}): Embedder = models.embedder(update)

    private fun detector(): LanguageDetector? = models.detector()

    /** Everything this model starts runs here, so locking a private vault can stop all of it. */
    private val ownScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[CoroutineJob]))

    /** The private vault's encrypted copy is written a few seconds after each change. */
    private val saver: CoroutineJob? = storage.encrypted?.let { encrypted ->
        ownScope.launch {
            while (true) {
                delay(5_000)
                withContext(io) { encrypted.saveIfChanged() }
            }
        }
    }

    /**
     * Loads the models in the background so the first Discover doesn't wait for them. Only when
     * the model is already downloaded: a first-time download stays tied to an explicit Discover.
     */
    fun warmUp() {
        if (!models.downloaded) return
        launchQuiet {
            inBackground("Loading the recommendation models") {
                embedder()
                // One detection loads the language models, which is the slow part.
                detector()?.scores("warming up the language models")
            }
        }
    }

    var screen by mutableStateOf(Screen.LIBRARY)

    var showThumbnails by mutableStateOf(settings.showThumbnails)
        private set

    var libraryGrid by mutableStateOf(settings.libraryGrid)
        private set

    fun showLibraryAsGrid(grid: Boolean) {
        settings.libraryGrid = grid
        libraryGrid = grid
    }

    var theme by mutableStateOf(settings.theme)
        private set

    /** The interface size the user chose; null means automatic. */
    var uiScale by mutableStateOf(settings.uiScale)
        private set

    fun changeUiScale(value: Float?) {
        settings.uiScale = value
        uiScale = value
    }

    /** The size actually used: the user's choice, or the automatic one. */
    val effectiveUiScale: Float get() = uiScale ?: autoUiScale

    private val autoUiScale by lazy { platform.automaticUiScale() }

    fun changeTheme(value: Theme) {
        settings.theme = value
        theme = value
    }

    fun setThumbnails(on: Boolean) {
        settings.showThumbnails = on
        showThumbnails = on
    }

    // Library
    var sources by mutableStateOf<List<LibrarySource>>(listOf(LibrarySource.Liked, LibrarySource.History))
        private set
    var currentSource by mutableStateOf<LibrarySource>(LibrarySource.Liked)
        private set
    var query by mutableStateOf("")
    var stateFilter by mutableStateOf(StateFilter.ALL)
    var historySort by mutableStateOf(HistorySort.RECENT)
        private set
    var libraryDialog by mutableStateOf<LibraryDialog?>(null)

    /** Whether videos opened from Forage go into the history; the "Pause history" switch. */
    var recordHistory by mutableStateOf(settings.recordHistory)
        private set

    fun changeRecordHistory(on: Boolean) {
        settings.recordHistory = on
        recordHistory = on
        message = if (on) "History is on again" else "History paused: videos you open aren't recorded"
    }

    fun sortHistory(sort: HistorySort) {
        historySort = sort
        launchQuiet { loadRows() }
    }
    var rows by mutableStateOf<List<LibraryRow>>(emptyList())
        private set
    var selection by mutableStateOf<Set<PlatformId>>(emptySet())
        private set
    /** How many videos the profile likes. */
    var profileSize by mutableStateOf(0)
        private set
    var notInterestedSize by mutableStateOf(0)
        private set
    /** True while the vault has no videos at all, so the library can say how to start. */
    var libraryEmpty by mutableStateOf(false)
        private set
    var addDialogOpen by mutableStateOf(false)

    // Discover
    var picks by mutableStateOf<List<PickView>>(emptyList())
        private set
    var topics by mutableStateOf<List<Cluster>>(emptyList())
        internal set
    var discoverNote by mutableStateOf<String?>(null)
        private set
    /** True once [topics] reflects the profile as it is now. */
    var topicsCurrent by mutableStateOf(false)
        private set

    // Watch later
    var later by mutableStateOf<List<LaterItem>>(emptyList())
        private set
    var laterSort by mutableStateOf(LaterSort.RECENT)

    /** The queue in the chosen order; videos of unknown length go last when sorting by length. */
    val sortedLater: List<LaterItem>
        get() = when (laterSort) {
            LaterSort.RECENT -> later.sortedByDescending { it.savedAt }
            LaterSort.OLDEST -> later.sortedBy { it.savedAt }
            LaterSort.SHORTEST -> later.sortedBy { it.durationSeconds ?: Long.MAX_VALUE }
        }

    // Status
    var job by mutableStateOf<Job?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    /** Small tasks running without a progress bar, e.g. fetching details after feedback. */
    var background by mutableStateOf<String?>(null)
        private set
    private var backgroundCount = 0

    val visibleRows: List<LibraryRow>
        get() {
            val q = query.trim()
            // The state filter only applies where states are mixed: the history and playlists.
            val filter = if (currentSource is LibrarySource.History || currentSource is LibrarySource.Playlist) stateFilter else StateFilter.ALL
            return rows.filter { row ->
                filter.matches(row.state) && (q.isEmpty() ||
                    row.title?.contains(q, ignoreCase = true) == true || row.channelName?.contains(q, ignoreCase = true) == true)
            }
        }

    /** Loads everything shown on screen. */
    suspend fun reload() = withContext(io) {
        loadSources()
        loadProfiles()
        loadRows()
        loadPicks()
        loadLater()
        if (isPrivate) describeCatalogue()
    }

    private fun loadSources() {
        val playlists = library.playlists().map { (name, size) -> LibrarySource.Playlist(name, size) }
        sources = listOf(LibrarySource.Liked, LibrarySource.History) + playlists + LibrarySource.NotInterested
        if (currentSource !in sources) {
            // A playlist whose size changed is still the same playlist.
            val name = (currentSource as? LibrarySource.Playlist)?.name
            currentSource = playlists.firstOrNull { it.name == name } ?: LibrarySource.Liked
        }
    }

    fun selectSource(source: LibrarySource) {
        currentSource = source
        selection = emptySet()
        launchQuiet { loadRows() }
    }

    fun toggle(id: PlatformId) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectVisible() { selection = selection + visibleRows.map { it.id } }

    fun clearSelection() { selection = emptySet() }

    /** Sets the profile state of the selected videos; null clears it. */
    fun markSelected(state: VideoState?) = mark(selection.toList(), state)

    /** Likes, rejects or (null) clears [ids]. Clears the selection afterwards. */
    fun mark(ids: List<PlatformId>, state: VideoState?) {
        if (ids.isEmpty()) return
        topicsCurrent = false
        launchQuiet {
            ids.forEach { library.remember(it) }
            profiles.mark(profile, ids, state)
            val count = if (ids.size == 1) "1 video" else "${ids.size} videos"
            message = when (state) {
                VideoState.LIKED -> "Liked $count"
                VideoState.NOT_INTERESTED -> "Marked $count not interested"
                null -> if (ids.size == 1) "Removed your choice for 1 video" else "Removed your choice for ${ids.size} videos"
            }
            selection = emptySet()
            loadRows()
            loadProfiles()
            if (state != null) fetchMissingDetails(ids)
        }
    }

    /**
     * A like counts right away, and its video's details follow in the background, since
     * recommendations need them. Big selections are left to "Fetch details", which shows progress.
     */
    private suspend fun fetchMissingDetails(ids: List<PlatformId>) {
        val missing = ids.filter { cache.video(it) == null }
        if (missing.isEmpty()) return
        if (missing.size > BACKGROUND_FETCH_LIMIT) {
            message = "${message.orEmpty()}. ${missing.size} still need details for recommendations: use \"Fetch details\""
            return
        }
        inBackground(if (missing.size == 1) "Fetching the video's details" else "Fetching details of ${missing.size} videos") {
            val summary = MetadataFetcher(source, cache).fetchAll(missing) { _, _ -> }
            loadRows()
            if (summary.failed > 0) message = "Couldn't fetch details of ${summary.failed} videos; \"Fetch details\" tries again"
        }
    }

    // Playlists and the history

    fun openAddToPlaylist(ids: List<PlatformId>) {
        if (ids.isNotEmpty()) libraryDialog = LibraryDialog.AddToPlaylist(ids)
    }

    /** Adds [ids] to a playlist, making it if it's new. Returns why it couldn't, or null. */
    fun addToPlaylist(name: String, ids: List<PlatformId>): String? {
        val clean = playlistName(name) ?: return "Give the playlist a name"
        libraryDialog = null
        launchQuiet {
            val added = library.addToPlaylist(clean, ids)
            selection = emptySet()
            loadSources()
            loadRows()
            message = when {
                added == 0 -> "Already in \"$clean\""
                added == 1 -> "Added 1 video to \"$clean\""
                else -> "Added $added videos to \"$clean\""
            }
        }
        return null
    }

    /** Makes an empty playlist and shows it. Returns why it couldn't, or null. */
    fun createPlaylist(name: String): String? {
        val clean = playlistName(name) ?: return "Give the playlist a name"
        if (!library.createPlaylist(clean)) return "There's already a playlist called \"$clean\""
        libraryDialog = null
        loadSources()
        selectSource(sources.first { it is LibrarySource.Playlist && it.name == clean })
        message = "Made \"$clean\". Add videos to it from the history, your likes or Discover."
        return null
    }

    fun renamePlaylist(old: String, new: String): String? {
        val clean = playlistName(new) ?: return "Give the playlist a name"
        if (!library.renamePlaylist(old, clean)) return "There's already a playlist called \"$clean\""
        libraryDialog = null
        val wasOpen = (currentSource as? LibrarySource.Playlist)?.name == old
        loadSources()
        if (wasOpen) currentSource = sources.first { it is LibrarySource.Playlist && it.name == clean }
        message = "Renamed to \"$clean\""
        return null
    }

    fun deletePlaylist(name: String) {
        libraryDialog = null
        launchQuiet {
            library.deletePlaylist(name)
            loadSources()
            loadRows()
            message = "Deleted \"$name\". Its videos are still in your library."
        }
    }

    /** Takes [ids] out of the playlist on screen. */
    fun removeFromPlaylist(ids: List<PlatformId>) {
        val playlist = (currentSource as? LibrarySource.Playlist)?.name ?: return
        launchQuiet {
            library.removeFromPlaylist(playlist, ids)
            selection = emptySet()
            loadSources()
            loadRows()
            message = if (ids.size == 1) "Removed 1 video from \"$playlist\"" else "Removed ${ids.size} videos from \"$playlist\""
        }
    }

    /** Takes [ids] out of the history. */
    fun forget(ids: List<PlatformId>) = launchQuiet {
        library.forget(ids)
        selection = emptySet()
        loadRows()
        message = if (ids.size == 1) "Removed 1 video from the history" else "Removed ${ids.size} videos from the history"
    }

    fun clearHistory() {
        libraryDialog = null
        launchQuiet {
            library.clearHistory()
            loadRows()
            message = "Cleared the history. Your likes, playlists and watch later are unchanged."
        }
    }

    private fun playlistName(name: String) = name.trim().take(MAX_PLAYLIST_NAME).takeIf { it.isNotEmpty() }

    /** Fetches metadata for the selection, or for every undecided-but-relevant profile video when nothing is selected. */
    fun fetchDetails() {
        val ids = selection.toList().ifEmpty {
            profiles.entries(profile).map { it.video }.filter { cache.video(it) == null }
        }
        if (ids.isEmpty()) {
            message = "Every video you liked or turned down already has its details"
            return
        }
        runJob("Fetching details from ${source.displayName}", ids.size) { update ->
            val job = currentCoroutineContext()[CoroutineJob]
            val summary = MetadataFetcher(source, cache).fetchAll(ids) { id, outcome ->
                if (job?.isActive == false) throw CancellationException("Stopped")
                val text = when (outcome) {
                    is FetchOutcome.Cached -> "cached"
                    is FetchOutcome.Fetched -> outcome.details.metadata.title
                    is FetchOutcome.Unavailable -> "gone: $id"
                    is FetchOutcome.Failed -> "failed: $id"
                }
                update(text)
            }
            selection = emptySet()
            loadRows()
            buildString {
                append("Fetched ${summary.fetched}")
                if (summary.cached > 0) append(", ${summary.cached} already had details")
                if (summary.unavailable > 0) append(", ${summary.unavailable} no longer available")
                if (summary.failed > 0) append(", ${summary.failed} failed")
                if (summary.stoppedEarly) append(". ${source.displayName} kept refusing; try again later to continue")
            }
        }
    }

    /** Imports a Takeout export: the extracted folder, or the zip as Google offers it. */
    fun importTakeout(file: File) = importTakeout(file.name) {
        if (file.isDirectory) TakeoutFolder.read(file) else file.inputStream().use(TakeoutFolder::readZip)
    }

    /** Imports a Takeout export that [read] reads, wherever it comes from (a phone's file picker, say). */
    fun importTakeout(name: String, read: () -> TakeoutData) = runJob("Importing $name") { _ ->
        val summary = library.importTakeout(read())
        reload()
        "Imported ${summary.watchEvents} watch events, ${summary.playlists.size} playlists and " +
            "${summary.subscriptions} subscriptions. Nothing was liked; that's up to you."
    }

    // Profiles

    fun switchProfile(to: ProfileRef) {
        profileMenuOpen = false
        if (to == profile) return
        profile = to
        settings.lastProfile = to.name
        // Everything shown belongs to the old profile until reloaded.
        topics = emptyList()
        topicsCurrent = false
        discoverNote = null
        picks = emptyList()
        selection = emptySet()
        launchQuiet {
            loadRows()
            loadPicks()
            loadProfiles()
        }
        message = "Switched to \"${to.name}\""
    }

    /** Creates a profile and switches to it. Returns why it couldn't, or null. */
    fun createProfile(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "Give the profile a name"
        if (clean.length > 40) return "Keep it under 40 characters"
        if (profiles.find(clean) != null) return "There's already a profile called \"$clean\""
        val created = profiles.profile(clean)
        profileDialog = null
        switchProfile(created)
        message = "Created \"$clean\". Like videos to give it a taste."
        return null
    }

    /** Deletes the open profile and switches to another. The last profile can't be deleted. */
    fun deleteProfile() {
        val doomed = profile
        val next = profiles.profiles().firstOrNull { it != doomed } ?: return
        profileDialog = null
        switchProfile(next)
        launchQuiet {
            profiles.delete(doomed)
            loadProfiles()
        }
        message = "Deleted \"${doomed.name}\". The library and watch later queue are unchanged."
    }

    private fun loadProfiles() {
        profileList = profiles.profiles().map { it to profiles.entries(it, VideoState.LIKED).size }
    }

    /** The platforms links can be added from in this vault, for the add dialog. */
    val linkPlatforms: String get() = source.displayName

    /**
     * Adds videos by their links (SPEC, Manual add), one or several separated by spaces or lines:
     * fetches their details and likes them. Returns why the text wasn't accepted, or null.
     */
    fun addByLink(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "Paste a link first"
        val ids = words.map { source.parseLink(it) }
        val unknown = words.filterIndexed { i, _ -> ids[i] == null }
        if (unknown.isNotEmpty()) {
            return if (words.size == 1) "That doesn't look like a link to a video on $linkPlatforms"
            else "${unknown.size} of these aren't video links Forage knows, e.g. ${unknown.first().take(60)}"
        }
        val found = ids.filterNotNull().distinct()
        closeAddDialog()
        runJob(if (found.size == 1) "Adding a video" else "Adding ${found.size} videos", found.size) { update ->
            val titles = ArrayList<String>()
            val reached = HashSet<PlatformId>()
            var missing = 0
            MetadataFetcher(source, cache).fetchAll(found) { id, outcome ->
                reached += id
                val details = when (outcome) {
                    is FetchOutcome.Fetched -> outcome.details
                    is FetchOutcome.Cached -> outcome.details
                    else -> null
                }
                val v = details?.metadata
                if (v == null) missing++ else titles += v.title
                library.remember(id, v?.title, v?.channel, v?.channelName, v?.isShort ?: false, v?.durationSeconds, v?.uploadedAt)
                update(v?.title ?: "no details yet: $id")
            }
            // Videos the fetch never reached (it stops after repeated failures) still go in, without details.
            found.filter { it !in reached }.forEach { library.remember(it, null, null, null, false, null, null); missing++ }
            profiles.mark(profile, found, VideoState.LIKED)
            topicsCurrent = false
            // Show them where they now are.
            currentSource = LibrarySource.Liked
            loadRows()
            loadProfiles()
            when {
                found.size == 1 && titles.size == 1 -> "Liked \"${titles.single()}\""
                found.size == 1 -> "Liked the video; its details couldn't be fetched yet (Fetch details)"
                missing == 0 -> "Liked ${found.size} videos"
                else -> "Liked ${found.size} videos; $missing still need details (Fetch details)"
            }
        }
        return null
    }

    // The private platform's catalogue (SPEC, "Sensitive platforms"): its embed dump, imported into an
    // index file of its own. It holds only the site's public catalogue, so it isn't encrypted.

    private val catalogueFile = File(dataDir, "catalogues/${PornhubSource.PLATFORM}.db")
    private var catalogue: PornhubCatalogue? = null
    private val catalogueLock = Any()

    /** Whether this vault can use a catalogue: the private one, which offers the platform. */
    val offersCatalogue: Boolean get() = isPrivate && searchPlatforms.any { it.first == PornhubSource.PLATFORM }

    /** What the catalogue holds, e.g. "4.6 million videos, imported 18 Nov 2026"; null when there is none. */
    var catalogueInfo by mutableStateOf<String?>(null)
        private set

    /** The catalogue if there is a usable one; one an older Forage built is left alone until it's imported again. */
    private fun openCatalogue(): PornhubCatalogue? = openAnyCatalogue()?.takeIf { it.current }

    private fun openAnyCatalogue(): PornhubCatalogue? = synchronized(catalogueLock) {
        if (!isPrivate) return null
        if (catalogue == null && catalogueFile.isFile) catalogue = runCatching { PornhubCatalogue.open(catalogueFile, platform.sqlFiles) }.getOrNull()
        catalogue
    }

    private fun closeCatalogue() = synchronized(catalogueLock) {
        catalogue?.close()
        catalogue = null
    }

    private fun describeCatalogue() {
        val c = openAnyCatalogue()
        catalogueInfo = if (c != null && !c.current) "Imported by an older Forage: import the dump again to use it" else c?.let {
            val count = if (it.size >= 1_000_000) "%.1f million".format(it.size / 1e6) else "%,d".format(it.size)
            val date = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                .format(java.time.Instant.ofEpochSecond(it.importedAt).atZone(java.time.ZoneId.systemDefault()))
            "$count videos, imported $date"
        }
    }

    /** Asks for the dump (or a catalogue made on another device) and imports it. */
    fun chooseCatalogueFile() = platform.chooseFile(
        "Choose the downloaded embed dump (pornhub.com-db.zip), or a catalogue made on another device (pornhub.db)",
        listOf("zip", "db"),
        ::importCatalogue,
    )

    /**
     * Imports the site's embed dump (the downloaded zip) as the catalogue, replacing any earlier
     * one when done; or takes over a catalogue another device already made (its pornhub.db), for
     * phones without the memory to import the dump themselves.
     */
    fun importCatalogue(file: PickedFile) {
        settingsOpen = false
        runJob("Importing the catalogue") { update ->
            val head = file.open().use { it.readNBytes(16) }
            if (head.decodeToString(0, minOf(head.size, 15)) == "SQLite format 3") {
                update("Copying ${file.name}")
                val incoming = File(catalogueFile.parentFile.apply { mkdirs() }, "${catalogueFile.name}.copying")
                file.open().use { input -> incoming.outputStream().use { input.copyTo(it, 1 shl 20) } }
                val usable = runCatching { PornhubCatalogue.open(incoming, platform.sqlFiles).use { it.current && it.size > 0 } }.getOrDefault(false)
                if (!usable) {
                    incoming.delete()
                    return@runJob "${file.name} isn't a catalogue this version of Forage can use"
                }
                closeCatalogue()
                catalogueFile.delete()
                check(incoming.renameTo(catalogueFile)) { "Couldn't move the catalogue into place" }
                describeCatalogue()
                return@runJob "Took over the catalogue from ${file.name}: ${catalogueInfo.orEmpty()}"
            }
            val job = currentCoroutineContext()[CoroutineJob]
            var shown = -1
            val summary = try {
                PornhubCatalogue.import(file.open, file.size, catalogueFile, progress = { share ->
                    val percent = (share * 100).toInt()
                    if (percent != shown) {
                        shown = percent
                        update("$percent % of ${file.name} read")
                    }
                }, cancelled = { job?.isActive == false }, beforeReplace = ::closeCatalogue, opener = platform.sqlFiles)
            } catch (e: PornhubCatalogue.LayoutException) {
                return@runJob "${e.message}. Its layout: ${e.layout}"
            } catch (e: OutOfMemoryError) {
                return@runJob OUT_OF_MEMORY
            }
            describeCatalogue()
            "Imported %,d videos into the catalogue (%,d left out with under %,d views, %,d lines unreadable; layout: %s). The zip can go now."
                .format(summary.videos, summary.skippedFewViews, PornhubCatalogue.MIN_VIEWS, summary.skippedUnreadable, summary.layout)
        }
    }

    /**
     * Downloads the dump from the site, imports it and deletes the zip. A download that breaks off
     * or is stopped continues where it stopped the next time.
     */
    fun downloadCatalogue() {
        settingsOpen = false
        runJob("Downloading the catalogue") { update ->
            val job = currentCoroutineContext()[CoroutineJob]
            val zip = File(catalogueFile.parentFile, "pornhub.com-db.zip")
            var shown = -1L
            try {
                DumpDownload.download(zip, progress = { done, total ->
                    val mb = done / 10_000_000
                    if (mb != shown) {
                        shown = mb
                        update("%.1f of %s GB".format(done / 1e9, total?.let { "%.1f".format(it / 1e9) } ?: "?"))
                    }
                }, cancelled = { job?.isActive == false })
            } catch (e: SourceException) {
                return@runJob e.message ?: "The download failed"
            }
            var percent = -1
            val summary = try {
                PornhubCatalogue.import(zip, catalogueFile, progress = { share ->
                    val p = (share * 100).toInt()
                    if (p != percent) {
                        percent = p
                        update("Importing: $p %")
                    }
                }, cancelled = { job?.isActive == false }, beforeReplace = ::closeCatalogue, opener = platform.sqlFiles)
            } catch (e: PornhubCatalogue.LayoutException) {
                return@runJob "${e.message}. Its layout: ${e.layout}"
            } catch (e: OutOfMemoryError) {
                return@runJob OUT_OF_MEMORY
            }
            zip.delete()
            describeCatalogue()
            "Downloaded and imported %,d videos into the catalogue.".format(summary.videos)
        }
    }

    fun removeCatalogue() = launchQuiet {
        closeCatalogue()
        catalogueFile.delete()
        catalogueInfo = null
        message = "Removed the catalogue; Discover uses searches only again"
    }

    // Search (SPEC, Manual add): find videos to add without leaving Forage for their links.

    /** The platforms this vault can search, as (platform, name). */
    val searchPlatforms: List<Pair<String, String>> by lazy {
        ((source as? Sources)?.members ?: listOf(source))
            .filter { Capability.SEARCH in it.capabilities }.map { it.platform to it.displayName }
    }

    /** The platform searched; the first one until the user picks another. */
    var searchPlatform by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<VideoStub>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchProblem by mutableStateOf<String?>(null)
        private set
    /** Videos from the results the profile likes, from just now or before. */
    var searchLiked by mutableStateOf<Set<PlatformId>>(emptySet())
        private set
    private var searchJob: CoroutineJob? = null

    fun searchVideos(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val platform = searchPlatform ?: searchPlatforms.firstOrNull()?.first ?: return
        val target = source.forPlatform(platform) ?: return
        searchJob?.cancel()
        searching = true
        searchProblem = null
        searchJob = ownScope.launch {
            try {
                val (results, included) = withContext(io) {
                    target.search(q) to profiles.entries(profile, VideoState.LIKED).mapTo(HashSet()) { it.video }
                }
                searchResults = results.filter { settings.showShorts || !it.isShort }
                searchLiked = included
                if (results.isEmpty()) searchProblem = "Nothing found for \"$q\""
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                searchProblem = e.message ?: "The search failed"
            } catch (e: Throwable) {
                searchProblem = "The search failed: ${e.message ?: e}"
            } finally {
                searching = false
            }
        }
    }

    /** Likes a search result, or takes the like back; its details follow in the background. */
    fun likeFromSearch(stub: VideoStub) {
        if (stub.id in searchLiked) {
            searchLiked = searchLiked - stub.id
            launchQuiet {
                profiles.mark(profile, listOf(stub.id), null)
                topicsCurrent = false
                loadRows()
                loadProfiles()
            }
            return
        }
        searchLiked = searchLiked + stub.id
        launchQuiet {
            library.remember(stub.id, stub.title, stub.channel, stub.channelName, stub.isShort, stub.durationSeconds, stub.uploadedAt)
            profiles.mark(profile, listOf(stub.id), VideoState.LIKED)
            topicsCurrent = false
            loadRows()
            loadProfiles()
            message = "Liked \"${stub.title}\""
            fetchMissingDetails(listOf(stub.id))
        }
    }

    /** Clears the last search when the dialog closes, so it doesn't greet the next visit. */
    fun closeAddDialog() {
        addDialogOpen = false
        searchJob?.cancel()
        searchResults = emptyList()
        searchProblem = null
        searching = false
    }

    // Topics

    private fun discoverer(embedder: Embedder, withSearch: Boolean = false) = Discover(
        cache, library, profiles, EmbeddingStore(db, embedder), detector(),
        source.takeIf { withSearch }, RankingConfig(showShorts = settings.showShorts), searchCache, topicStore,
        tagPlatforms = tagPlatforms,
        catalogues = openCatalogue()?.let { mapOf(it.platform to it) }.orEmpty(),
    )

    /** Groups the profile into topics without running a whole Discover. */
    fun loadTopics() {
        val liked = profiles.entries(profile, VideoState.LIKED).map { it.video }
        if (liked.none { cache.video(it) != null }) {
            message = "Like a few videos to see the topics they fall into"
            return
        }
        runJob("Grouping your likes into topics") { update ->
            update("Loading the models")
            val embedder = embedder(update)
            update("Grouping")
            topics = discoverer(embedder).topics(profile)
            topicsCurrent = true
            "Your likes fall into ${topics.count { !it.isVaried }} topics"
        }
    }

    /** The topic whose name is being edited, by index. */
    var renamingTopic by mutableStateOf<Int?>(null)

    /** Names a topic, or with null or a blank name gives it back its automatic label. */
    fun renameTopic(topic: Cluster, name: String?) {
        renamingTopic = null
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }?.take(TopicStore.MAX_NAME)
        if (clean == topic.customName) return
        launchQuiet {
            topicStore.rename(profile, topic, clean)
            topics = topics.map { if (it.index == topic.index) it.copy(customName = clean) else it }
            message = if (clean == null) "Back to the automatic name: \"${topic.autoLabel}\"" else "Renamed to \"$clean\""
        }
    }

    /** Pins, mutes or (null) frees a topic; takes effect in the next batch. */
    fun setTopicPreference(topic: Cluster, preference: TopicPreference?) = launchQuiet {
        topicStore.set(profile, topic, preference)
        topics = topics.map { if (it.index == topic.index) it.copy(preference = preference) else it }
        message = when (preference) {
            TopicPreference.PINNED -> "Pinned \"${topic.label}\": it gets a few picks in every batch"
            TopicPreference.MUTED -> "Muted \"${topic.label}\": no more picks from it"
            null -> "\"${topic.label}\" is back to normal"
        }
    }

    // Discover

    fun discover() {
        // Check before loading models: that takes seconds, and a download on first use.
        val liked = profiles.entries(profile, VideoState.LIKED).map { it.video }
        if (liked.none { cache.video(it) != null }) {
            message = if (liked.isEmpty()) "Nothing liked yet. Like a few videos in the library or from a search first."
            else "None of the ${liked.size} videos you like have details yet. Use \"Fetch details\" in the library."
            return
        }
        runDiscover()
    }

    private fun runDiscover() = runJob("Finding recommendations") { update ->
        update("Loading the models")
        val embedder = embedder(update)
        update(if (settings.searchYouTube) "Collecting candidates and searching ${source.displayName}" else "Collecting and ranking candidates")
        try {
            val result = discoverer(embedder, settings.searchYouTube).run(profile)
            val seconds = result.timings.values.sum() / 1000.0
            topics = result.clusters
            topicsCurrent = true
            discoverNote = buildList {
                add("${result.picks.size} picks from ${result.candidates} candidates in %.0f s".format(seconds))
                if (result.foreignLanguage > 0) add("${result.foreignLanguage} dropped for their language")
                if (result.withoutMetadata > 0) add("${result.withoutMetadata} liked videos still need details")
                if (result.fromCatalogue > 0) add("${result.fromCatalogue} from the catalogue")
                if (result.searchFailures.isNotEmpty()) add("${result.searchFailures.size} searches failed")
            }.joinToString(" · ")
            loadPicks()
            "Found ${result.picks.size} recommendations"
        } catch (e: EmptyProfileException) {
            "${e.message}. Like a few videos, then fetch their details."
        }
    }

    fun feedback(view: PickView, kind: Feedback) = launchQuiet {
        val id = view.pick.video
        library.remember(
            id, view.pick.title, view.pick.channel, view.pick.channelName,
            durationSeconds = view.pick.durationSeconds, uploadedAt = view.pick.uploadedAt,
        )
        val active = kind in view.feedback
        when (kind) {
            Feedback.LIKE -> if (active) profiles.mark(profile, listOf(id), null) else profiles.like(profile, id)
            Feedback.NOT_INTERESTED -> if (active) profiles.mark(profile, listOf(id), null) else profiles.notInterested(profile, id)
            Feedback.SAVED -> if (active) library.removeFromWatchLater(id) else library.saveForLater(id)
        }
        if (kind != Feedback.SAVED) topicsCurrent = false
        loadPicks()
        loadLater()
        loadProfiles()
        if (!active && kind != Feedback.SAVED) fetchMissingDetails(listOf(id))
    }

    // Watch later

    fun removeFromLater(id: PlatformId) = launchQuiet {
        library.removeFromWatchLater(id)
        loadLater()
        loadPicks()
    }

    /** Off the queue and into the watch history, so it isn't recommended again. */
    fun markWatched(item: LaterItem) = launchQuiet {
        library.markWatched(item.id)
        loadLater()
        loadPicks()
        message = "Marked \"${item.title ?: item.id}\" as watched"
    }

    /** Hands the video to the player, and notes it in the history unless that's paused. */
    fun open(id: PlatformId) {
        try {
            platform.openVideo(id, source.watchUrl(id), player, storage.playerLog) { problem -> message = problem }
        } catch (e: Exception) {
            message = e.message
            return
        }
        if (recordHistory) launchQuiet {
            library.recordWatch(id)
            if (currentSource == LibrarySource.History) loadRows()
        }
    }

    // Loading

    private fun loadRows() {
        val entries = profiles.entries(profile)
        val states = entries.associate { it.video to it.state }
        libraryEmpty = library.counts().first == 0L && states.isEmpty()
        profileSize = states.values.count { it == VideoState.LIKED }
        notInterestedSize = states.values.count { it == VideoState.NOT_INTERESTED }
        val videos: List<WatchedVideo> = when (val s = currentSource) {
            LibrarySource.History -> WatchHistory.select(library.events()).let { watched ->
                if (historySort == HistorySort.RECENT) watched.sortedByDescending { it.lastWatched ?: Instant.MIN } else watched
            }
            is LibrarySource.Playlist -> WatchHistory.select(library.events(s.name))
            LibrarySource.Liked, LibrarySource.NotInterested -> {
                val wanted = if (s == LibrarySource.Liked) VideoState.LIKED else VideoState.NOT_INTERESTED
                val watched = WatchHistory.select(library.events()).associateBy { it.video }
                // Most recent decision first; entries come oldest first.
                entries.filter { it.state == wanted }.asReversed().map { e ->
                    watched[e.video] ?: WatchedVideo(e.video, null, null, null, 0, null, null, false)
                }
            }
        }
        val meta = cache.allMetadata()
        rows = videos.map { v ->
            val m = meta[v.video]
            val known = if (m == null && (v.title == null || v.channelName == null)) library.video(v.video) else null
            LibraryRow(
                id = v.video,
                title = m?.title ?: v.title ?: known?.title,
                channelName = m?.channelName ?: v.channelName ?: known?.channelName ?: m?.extra?.get("performers"),
                watchCount = v.watchCount,
                lastSeen = v.lastWatched,
                durationSeconds = m?.durationSeconds,
                isShort = m?.isShort == true || v.isShort,
                state = states[v.video],
                hasMetadata = m != null,
            )
        }
        // Keep the selection to what still exists in this list.
        val ids = rows.mapTo(HashSet()) { it.id }
        selection = selection.filterTo(HashSet()) { it in ids }
    }

    private fun loadPicks() {
        val states = profiles.entries(profile).associate { it.video to it.state }
        val saved = library.watchLater().toSet()
        picks = profiles.picks(profile).map { p ->
            PickView(p, buildSet {
                if (states[p.video] == VideoState.LIKED) add(Feedback.LIKE)
                if (states[p.video] == VideoState.NOT_INTERESTED) add(Feedback.NOT_INTERESTED)
                if (p.video in saved) add(Feedback.SAVED)
            })
        }
    }

    private fun loadLater() {
        val meta = cache.allMetadata()
        later = library.savedForLater().map { saved ->
            val m = meta[saved.id]
            val known = library.video(saved.id)
            LaterItem(
                saved.id,
                m?.title ?: known?.title,
                m?.channelName ?: known?.channelName,
                m?.durationSeconds ?: known?.durationSeconds,
                m?.uploadedAt ?: known?.uploadedAt,
                saved.savedAt,
            )
        }
    }

    // Running work

    private fun launchQuiet(block: suspend () -> Unit) {
        ownScope.launch {
            try {
                withContext(io) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Libraries with native code fail with Errors too; one failed task must not take the app down.
                message = "Something went wrong: ${e.message ?: e}"
            }
        }
    }

    private suspend fun inBackground(label: String, block: suspend () -> Unit) {
        synchronized(this) { backgroundCount++ }
        background = if (backgroundCount == 1) label else "$backgroundCount tasks running in the background"
        try {
            block()
        } finally {
            val left = synchronized(this) { --backgroundCount }
            background = when (left) {
                0 -> null
                1 -> "1 task running in the background"
                else -> "$left tasks running in the background"
            }
        }
    }

    /** Runs one long task at a time, reporting progress in [job] and its outcome in [message]. */
    private fun runJob(label: String, total: Int? = null, block: suspend (update: (String) -> Unit) -> String) {
        if (job != null) {
            message = "Wait for \"${job!!.label}\" to finish first"
            return
        }
        job = Job(label, total = total)
        var done = 0
        val update: (String) -> Unit = { detail ->
            if (total != null) done++
            job = Job(label, done, total, detail)
        }
        ownScope.launch {
            message = try {
                withContext(io) { block(update) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "$label failed: ${e.message ?: e}"
            } finally {
                job = null
            }
        }
    }

    /** Closes the models and the database, folding pending changes into the database file. */
    fun close() {
        models.close()
        runCatching { storage.close() }
    }

    /** Stops this model's work and, for the private vault, saves and drops the decrypted copy. */
    suspend fun shutdown() {
        saver?.cancel()
        ownScope.coroutineContext[CoroutineJob]?.cancel()
        withContext(io + NonCancellable) {
            storage.encrypted?.close()
            closeCatalogue()
        }
    }

    // The private vault, opened from the normal vault's model.

    /** Whether "Private profiles…" is offered: in the normal vault, on platforms that have a private vault. */
    val offersPrivateVault get() = !storage.isPrivate && platform.hasPrivateVault

    /** The open private vault, shown instead of this one while it's set. */
    var privateModel by mutableStateOf<AppModel?>(null)
        private set
    var privateError by mutableStateOf<String?>(null)
    var privateBusy by mutableStateOf(false)
        private set

    /** Set on a private vault's model: locks it and returns to the normal vault. */
    var onLock: (() -> Unit)? = null
        private set

    /**
     * Unlocks the private vault for [pin], or with [create] makes one (opening it instead if that
     * PIN has one already). The PIN is wiped from memory afterwards.
     */
    fun unlockPrivate(pin: CharArray, create: Boolean): CoroutineJob? {
        if (!offersPrivateVault || privateBusy) return null
        privateError = null
        privateBusy = true
        return scope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val storage = try {
                        platform.openPrivateVault(dataDir, pin, create, settings)
                    } finally {
                        pin.fill('\u0000')
                    }
                    storage?.let {
                        // One thread for everything: the private vault's database is a single connection.
                        AppModel(
                            dataDir, scope, Dispatchers.IO.limitedParallelism(1), it, models, platform,
                            privateSourceFactory = privateSourceFactory, sourceFactory = privateSourceFactory,
                        )
                    }
                }
                if (opened == null) {
                    privateError = "Nothing unlocks with that PIN"
                } else {
                    opened.onLock = { lockPrivate() }
                    opened.reload()
                    privateModel = opened
                    profileDialog = null
                    profileMenuOpen = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultDamagedException) {
                privateError = e.message
            } catch (e: Throwable) {
                privateError = "Couldn't open the private vault: ${e.message ?: e}"
            } finally {
                privateBusy = false
            }
        }
    }

    /** Locks the private vault: it disappears at once, then finishes saving in the background. */
    fun lockPrivate(): CoroutineJob? {
        val open = privateModel ?: return null
        privateModel = null
        return scope.launch { open.shutdown() }
    }

    private companion object {
        /** Likes beyond this many videos without details wait for "Fetch details". */
        const val BACKGROUND_FETCH_LIMIT = 30
        const val MAX_PLAYLIST_NAME = 60
        const val OUT_OF_MEMORY = "Not enough memory on this device to import the dump. Import it on a computer instead, " +
            "then copy catalogues/pornhub.db from its Forage folder here and choose it in Settings."
    }
}
