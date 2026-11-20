package forage.desktop

import forage.app.*
import forage.app.ui.*

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import forage.app.ui.App
import forage.app.ui.ForageTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders each screen against a real vault into PNG files, without a display.
 * Usage: Screenshots <data-dir> <output-dir>
 */
fun main(args: Array<String>) {
    val (dataDir, outDir) = args.map(::File)
    outDir.mkdirs()
    val model = DesktopModel(dataDir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
    runBlocking { model.reload() }

    fun shot(name: String, dark: Boolean = false, setup: DesktopModel.() -> Unit) {
        model.setup()
        // Load the images up front; the headless renderer doesn't wait for them.
        runBlocking { preload(model) }
        val scene = ImageComposeScene(width = 2560, height = 1640, density = Density(2f)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalExtras provides forage.desktop.ui.DesktopExtras) {
                ForageTheme(dark = dark) { App(model.privateModel ?: model, onImport = {}) }
            }
        }
        // Frames over about a second, so layout settles and menus and dialogs finish fading in.
        repeat(12) { scene.render(it * 100_000_000L) }
        val png = scene.render(1_200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(outDir, "$name.png").writeBytes(png)
        scene.close()
        println("wrote $name.png")
    }

    shot("1-library-watch-later") {
        openLibrary(forage.app.LibraryView.LIST)
        selectSource(LibrarySource.Liked)
        stateFilter = StateFilter.LIKED
        listOf(1, 3, 4).mapNotNull { visibleRows.getOrNull(it)?.id }.forEach(::toggle)
    }
    shot("2-discover") {
        clearSelection()
        message = null
        loadTopics()
        message = null
        go(Screen.DISCOVER)
        // Show the feedback states: pick 2 saved, pick 3 liked.
        if (picks.size >= 3 && picks[1].feedback.isEmpty()) {
            feedback(picks[1], Feedback.SAVED)
            feedback(picks[2], Feedback.LIKE)
        }
    }
    shot("4-later") {
        // A fuller queue than the two saved so far, to show the layout.
        picks.drop(3).take(5).forEach { feedback(it, Feedback.SAVED) }
        openLibrary(forage.app.LibraryView.LATER)
    }
    shot("7-topics") {
        openLibrary(forage.app.LibraryView.TASTE)
        loadTopics()
        topics.getOrNull(0)?.let { renameTopic(it, "Speedcubing") }
        renamingTopic = topics.getOrNull(1)?.index
        topics.getOrNull(0)?.let { setTopicPreference(it, forage.core.recommend.TopicPreference.PINNED) }
        topics.getOrNull(4)?.let { setTopicPreference(it, forage.core.recommend.TopicPreference.MUTED) }
    }
    shot("5-discover-dark", dark = true) {
        renamingTopic = null
        message = null
        go(Screen.DISCOVER)
    }
    shot("6-library-dark", dark = true) {
        openLibrary(forage.app.LibraryView.LIST)
        selectSource(LibrarySource.Liked)
        stateFilter = StateFilter.LIKED
        listOf(1, 3).mapNotNull { visibleRows.getOrNull(it)?.id }.forEach(::toggle)
    }
    shot("8-library-grid") {
        openLibrary(forage.app.LibraryView.LIST)
        stateFilter = StateFilter.ALL
        showLibraryAsGrid(true)
        selectSource(LibrarySource.Liked)
        visibleRows.drop(2).take(2).forEach { toggle(it.id) }
        runBlocking { visibleRows.take(24).forEach { thumbnails.load(it.id) } }
    }
    shot("9-profile-menu") {
        message = null
        go(Screen.DISCOVER)
        if (profileList.none { it.first.name == "Learning" }) profiles.profile("Learning")
        runBlocking { reload() }
        profileMenuOpen = true
    }
    shot("10-new-profile") {
        message = null
        profileMenuOpen = false
        profileDialog = ProfileDialog.NEW
    }
    shot("11-settings") {
        profileDialog = null
        settingsOpen = true
    }
    shot("12-settings-dark", dark = true) { settingsOpen = true }
    shot("13-private-dialog") {
        settingsOpen = false
        message = null
        profileDialog = ProfileDialog.PRIVATE
    }
    shot("14-private-vault") {
        profileDialog = null
        runBlocking { unlockPrivate("screenshot vault passphrase".toCharArray(), create = true)?.join() }
        privateModel?.go(Screen.LIBRARY)
    }
    shot("16-private-settings") {
        privateModel?.settingsOpen = true
    }
    shot("3-library-history") {
        privateModel?.settingsOpen = false
        runBlocking { lockPrivate()?.join() }
        settingsOpen = false
        showLibraryAsGrid(false)
        clearSelection()
        openLibrary(forage.app.LibraryView.LIST)
        stateFilter = StateFilter.ALL
        selectSource(LibrarySource.History)
    }
    shot("15-add-search") {
        go(Screen.HOME)
        searchText = "hand cut dovetail joint"
        submitSearch()
        runBlocking { searchResults.take(5).forEach { thumbnails.load(it.id) } }
    }
}

/** Loads the images a screen shows up front; the headless renderer doesn't wait for them. */
internal suspend fun preload(model: forage.app.AppModel) {
    val page = model.videoPage
    if (page != null) {
        model.thumbnails.load(page.id, large = true)
        page.details?.related?.take(8)?.forEach { model.thumbnails.load(it.id) }
        return
    }
    if (model.searchShown) return model.searchResults.take(8).forEach { model.thumbnails.load(it.id) }
    when (model.screen) {
        Screen.HOME -> (model.later.take(6).map { it.id } + model.picks.take(6).map { it.pick.video } + model.recentlyWatched.take(6).map { it.id })
            .forEach { model.thumbnails.load(it) }
        Screen.DISCOVER -> model.picks.take(9).forEach { model.thumbnails.load(it.pick.video, large = true) }
        Screen.SUBSCRIPTIONS -> {}
        Screen.LIBRARY -> when (model.libraryView) {
            forage.app.LibraryView.HUB -> model.recentlyLiked.take(6).forEach { model.thumbnails.load(it.id) }
            forage.app.LibraryView.LIST -> model.visibleRows.take(14).forEach { model.thumbnails.load(it.id) }
            forage.app.LibraryView.LATER -> model.later.take(8).forEach { model.thumbnails.load(it.id, large = true) }
            forage.app.LibraryView.TASTE -> model.topics.take(4).flatMap { t -> t.videos.take(6) }.forEach { model.thumbnails.load(it.id) }
            forage.app.LibraryView.PLAYLISTS -> {}
            forage.app.LibraryView.PLAYLIST -> model.playlistItems.take(8).forEach { model.thumbnails.load(it.id, large = true) }
        }
    }
}
