package forage.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.embedding.EmbeddingStore
import forage.core.importer.TakeoutFolder
import forage.core.importer.WatchEvent
import forage.core.language.LanguageDetector
import forage.core.library.LibraryStore
import forage.core.library.WatchHistory
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
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import forage.language.lingua.LinguaDetector
import forage.source.youtube.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Properties

enum class Screen { LIBRARY, TOPICS, DISCOVER, LATER }

enum class ProfileDialog { NEW, DELETE, PRIVATE }

sealed interface LibrarySource {
    val label: String

    data object History : LibrarySource { override val label = "Watch history" }
    data object InProfile : LibrarySource { override val label = "Decided in this profile" }
    data class Playlist(val name: String, val size: Long) : LibrarySource { override val label = "Playlist: $name" }
}

enum class StateFilter(val label: String) {
    ALL("All"), UNDECIDED("Undecided"), INCLUDED("In profile"), EXCLUDED("Excluded"), NEGATIVE("Not for me");

    fun matches(state: VideoState?) = when (this) {
        ALL -> true
        UNDECIDED -> state == null
        INCLUDED -> state == VideoState.INCLUDED
        EXCLUDED -> state == VideoState.EXCLUDED
        NEGATIVE -> state == VideoState.NEGATIVE
    }
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

enum class Feedback { MORE, NOT_FOR_ME, SAVED }

/** A video in the watch-later queue, with what the queue shows about it. */
data class LaterItem(
    val id: PlatformId,
    val title: String?,
    val channelName: String?,
    val durationSeconds: Long?,
    val uploadedAt: Instant?,
    val savedAt: Instant,
)

enum class LaterSort(val label: String) { RECENT("Recently saved"), OLDEST("Oldest first"), SHORTEST("Shortest first") }

data class PickView(val pick: StoredPick, val feedback: Set<Feedback>)

/** A long-running task. [total] is null when progress can't be counted. */
data class Job(val label: String, val done: Int = 0, val total: Int? = null, val detail: String = "")

/**
 * Everything the desktop UI shows and does, kept free of Compose UI code so it can be tested
 * and driven headlessly. State lives in snapshot state; work runs on [io].
 */
class DesktopModel(
    val dataDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Replaceable for tests; created on first use otherwise, since it starts network clients. */
    sourceFactory: () -> ExtractorSource = { YouTubeSource() },
) {
    val db: ForageDatabase = openDatabase(File(dataDir, "forage.db"))
    val cache = MetadataCache(db)
    val library = LibraryStore(db)
    val profiles = ProfileStore(db)
    val settings = Settings(File(dataDir, "settings.properties"))

    /** The profile everything on screen belongs to. Profiles share the vault's library but nothing else. */
    var profile by mutableStateOf(
        settings.lastProfile?.let(profiles::find) ?: profiles.profiles().firstOrNull() ?: profiles.profile()
    )
        private set

    /** Every profile in this vault with the number of videos in it, for the switcher. */
    var profileList by mutableStateOf<List<Pair<ProfileRef, Int>>>(emptyList())
        private set

    var settingsOpen by mutableStateOf(false)

    var player by mutableStateOf(settings.player)
        private set

    fun choosePlayer(value: Player) {
        settings.player = value
        player = value
    }

    /** Opens the folder the vault lives in, in the system's file manager. */
    fun openDataFolder() {
        try {
            java.awt.Desktop.getDesktop().open(dataDir.absoluteFile)
        } catch (e: Exception) {
            message = "Couldn't open the folder: ${dataDir.absolutePath}"
        }
    }

    var profileMenuOpen by mutableStateOf(false)
    var profileDialog by mutableStateOf<ProfileDialog?>(null)

    private val source by lazy(sourceFactory)
    val thumbnails = Thumbnails(File(dataDir, "thumbnails")) { id, large -> source.thumbnailUrl(id, large) }
    private val searchCache = SearchCache(db)
    private val topicStore = TopicStore(db)
    private val modelLock = Any()
    private var embedder: OnnxEmbedder? = null
    private var detector: LanguageDetector? = null

    /** The embedding model, loaded (and downloaded on first use) when first needed. */
    private fun embedder(update: (String) -> Unit = {}): OnnxEmbedder = synchronized(modelLock) {
        embedder ?: run {
            val spec = ModelSpec.MULTILINGUAL_MINILM
            val files = ModelFiles.ensure(spec, File(dataDir, "models/minilm").toPath()) { update("Downloading $it (about 470 MB)") }
            OnnxEmbedder(spec, files).also { embedder = it }
        }
    }

    private fun detector(): LanguageDetector = synchronized(modelLock) {
        detector ?: LinguaDetector().also { detector = it }
    }

    /**
     * Loads the models in the background so the first Discover doesn't wait for them. Only when
     * the model is already downloaded: a first-time download stays tied to an explicit Discover.
     */
    fun warmUp() {
        if (!File(dataDir, "models/minilm/model.onnx").isFile) return
        launchQuiet {
            inBackground("Loading the recommendation models") {
                embedder()
                // One detection loads the language models, which is the slow part.
                detector().scores("warming up the language models")
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

    fun changeTheme(value: Theme) {
        settings.theme = value
        theme = value
    }

    fun setThumbnails(on: Boolean) {
        settings.showThumbnails = on
        showThumbnails = on
    }

    // Library
    var sources by mutableStateOf<List<LibrarySource>>(listOf(LibrarySource.History))
        private set
    var currentSource by mutableStateOf<LibrarySource>(LibrarySource.History)
        private set
    var query by mutableStateOf("")
    var stateFilter by mutableStateOf(StateFilter.ALL)
    var rows by mutableStateOf<List<LibraryRow>>(emptyList())
        private set
    var selection by mutableStateOf<Set<PlatformId>>(emptySet())
        private set
    var profileSize by mutableStateOf(0)
        private set

    // Discover
    var picks by mutableStateOf<List<PickView>>(emptyList())
        private set
    var topics by mutableStateOf<List<Cluster>>(emptyList())
        private set
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
            return rows.filter { row ->
                stateFilter.matches(row.state) && (q.isEmpty() ||
                    row.title?.contains(q, ignoreCase = true) == true || row.channelName?.contains(q, ignoreCase = true) == true)
            }
        }

    /** Loads everything shown on screen. */
    suspend fun reload() = withContext(io) {
        val playlists = library.playlists().map { (name, size) -> LibrarySource.Playlist(name, size) }
        // Watch later first: it's the biggest list of videos chosen on purpose.
            .sortedByDescending { it.size }
        sources = listOf(LibrarySource.History) + playlists + LibrarySource.InProfile
        if (currentSource !in sources) currentSource = sources.first()
        loadProfiles()
        loadRows()
        loadPicks()
        loadLater()
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

    /** Sets the profile state of [ids]; null clears it. Clears the selection afterwards. */
    fun mark(ids: List<PlatformId>, state: VideoState?) {
        if (ids.isEmpty()) return
        topicsCurrent = false
        launchQuiet {
            ids.forEach { library.remember(it) }
            profiles.mark(profile, ids, state)
            message = (if (ids.size == 1) "1 video " else "${ids.size} videos ") + when (state) {
                VideoState.INCLUDED -> "added to the profile"
                VideoState.EXCLUDED -> "excluded"
                VideoState.NEGATIVE -> "marked not for me"
                null -> "cleared"
            } + if (state == VideoState.INCLUDED || state == VideoState.NEGATIVE) needsFetchHint(ids) else ""
            selection = emptySet()
            loadRows()
        }
    }

    private fun needsFetchHint(ids: List<PlatformId>): String {
        val missing = ids.count { cache.video(it) == null }
        return if (missing == 0) "" else "; $missing need details before they count (Fetch details)"
    }

    /** Fetches metadata for the selection, or for every undecided-but-relevant profile video when nothing is selected. */
    fun fetchDetails() {
        val ids = selection.toList().ifEmpty {
            profiles.entries(profile).filter { it.state != VideoState.EXCLUDED }.map { it.video }.filter { cache.video(it) == null }
        }
        if (ids.isEmpty()) {
            message = "Every profile video already has its details"
            return
        }
        runJob("Fetching details from YouTube", ids.size) { update ->
            val summary = MetadataFetcher(source, cache).fetchAll(ids) { id, outcome ->
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
                if (summary.unavailable > 0) append(", ${summary.unavailable} no longer on YouTube")
                if (summary.failed > 0) append(", ${summary.failed} failed")
                if (summary.stoppedEarly) append(". YouTube kept refusing; try again later to continue")
            }
        }
    }

    fun importTakeout(dir: File) = runJob("Importing ${dir.name}") { _ ->
        val summary = library.importTakeout(TakeoutFolder.read(dir))
        reload()
        "Imported ${summary.watchEvents} watch events, ${summary.playlists.size} playlists and " +
            "${summary.subscriptions} subscriptions. Nothing was added to the profile."
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
        message = "Created \"$clean\". Add videos to it in the library."
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
        profileList = profiles.profiles().map { it to profiles.entries(it, VideoState.INCLUDED).size }
    }

    // Topics

    private fun discoverer(embedder: OnnxEmbedder, withSearch: Boolean = false) = Discover(
        cache, library, profiles, EmbeddingStore(db, embedder), detector(),
        source.takeIf { withSearch }, RankingConfig(showShorts = settings.showShorts), searchCache, topicStore,
    )

    /** Groups the profile into topics without running a whole Discover. */
    fun loadTopics() {
        val included = profiles.entries(profile, VideoState.INCLUDED).map { it.video }
        if (included.none { cache.video(it) != null }) {
            message = "Add videos to the profile and fetch their details to see its topics"
            return
        }
        runJob("Grouping your profile into topics") { update ->
            update("Loading the models")
            val embedder = embedder(update)
            update("Grouping")
            topics = discoverer(embedder).topics(profile)
            topicsCurrent = true
            "Your profile has ${topics.count { !it.isVaried }} topics"
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
        val included = profiles.entries(profile, VideoState.INCLUDED).map { it.video }
        if (included.none { cache.video(it) != null }) {
            message = if (included.isEmpty()) "The profile has no videos yet. Add some in the library first."
            else "None of the ${included.size} profile videos have details yet. Use \"Fetch profile details\" in the library."
            return
        }
        runDiscover()
    }

    private fun runDiscover() = runJob("Finding recommendations") { update ->
        update("Loading the models")
        val embedder = embedder(update)
        update(if (settings.searchYouTube) "Collecting candidates and searching YouTube" else "Collecting and ranking candidates")
        try {
            val result = discoverer(embedder, settings.searchYouTube).run(profile)
            val seconds = result.timings.values.sum() / 1000.0
            topics = result.clusters
            topicsCurrent = true
            discoverNote = buildList {
                add("${result.picks.size} picks from ${result.candidates} candidates in %.0f s".format(seconds))
                if (result.foreignLanguage > 0) add("${result.foreignLanguage} dropped for their language")
                if (result.withoutMetadata > 0) add("${result.withoutMetadata} profile videos still need details")
                if (result.searchFailures.isNotEmpty()) add("${result.searchFailures.size} searches failed")
            }.joinToString(" · ")
            loadPicks()
            "Found ${result.picks.size} recommendations"
        } catch (e: EmptyProfileException) {
            "${e.message}. Add videos in the library, then fetch their details."
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
            Feedback.MORE -> if (active) profiles.mark(profile, listOf(id), null) else profiles.moreLikeThis(profile, id)
            Feedback.NOT_FOR_ME -> if (active) profiles.mark(profile, listOf(id), null) else profiles.notForMe(profile, id)
            Feedback.SAVED -> if (active) library.removeFromWatchLater(id) else library.saveForLater(id)
        }
        loadPicks()
        loadLater()
        // Profile feedback only counts once the video has metadata; fetch it in the background.
        if (!active && kind != Feedback.SAVED && cache.video(id) == null) {
            inBackground("Fetching details of \"${view.pick.title}\" so your feedback counts") {
                val outcome = MetadataFetcher(source, cache).fetch(id)
                if (outcome is FetchOutcome.Failed) {
                    message = "Couldn't fetch details of \"${view.pick.title}\"; it will count after the next \"Fetch profile details\""
                }
            }
        }
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

    fun open(id: PlatformId) {
        val player = player
        val log = File(dataDir, "logs/player.log")
        val process = try {
            Player.open(player, id, log)
        } catch (e: Exception) {
            message = e.message
            return
        } ?: return
        // A player that can't play the link usually quits within seconds; say why instead of doing nothing.
        scope.launch(io) {
            if (process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() != 0) {
                // The last line is usually a generic "Exiting"; the line naming the error says more.
                val lines = runCatching { log.readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
                val last = lines.lastOrNull { "ERROR" in it } ?: lines.lastOrNull()
                message = "${player.label} couldn't play the video" + (last?.let { ": $it" } ?: "") + " (full output: ${log.path})"
            }
        }
    }

    // Loading

    private fun loadRows() {
        val states = profiles.entries(profile).associate { it.video to it.state }
        profileSize = states.values.count { it == VideoState.INCLUDED }
        val events: List<WatchEvent> = when (val s = currentSource) {
            LibrarySource.History -> library.events()
            is LibrarySource.Playlist -> library.events(s.name)
            LibrarySource.InProfile -> {
                val seen = library.firstSeen()
                states.keys.map { WatchEvent(it, null, null, null, seen[it]) }
            }
        }
        val meta = cache.allMetadata()
        rows = WatchHistory.select(events).map { v ->
            val m = meta[v.video]
            val known = if (m == null && (v.title == null || v.channelName == null)) library.video(v.video) else null
            LibraryRow(
                id = v.video,
                title = m?.title ?: v.title ?: known?.title,
                channelName = m?.channelName ?: v.channelName ?: known?.channelName,
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
                if (states[p.video] == VideoState.INCLUDED) add(Feedback.MORE)
                if (states[p.video] == VideoState.NEGATIVE) add(Feedback.NOT_FOR_ME)
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
        scope.launch {
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
        scope.launch {
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

    fun close() {
        synchronized(modelLock) { embedder?.close() }
    }

    companion object {
        fun openDatabase(file: File): ForageDatabase {
            file.absoluteFile.parentFile.mkdirs()
            // Background fetches write while the UI reads; WAL lets them do so without blocking each other.
            val properties = Properties().apply {
                setProperty("journal_mode", "WAL")
                setProperty("busy_timeout", "10000")
            }
            return ForageDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.path}", properties, ForageDatabase.Schema))
        }
    }
}
