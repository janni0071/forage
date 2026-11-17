package forage.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import forage.desktop.ui.App
import forage.desktop.ui.ForageTheme
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
        val onScreen = when (model.screen) {
            Screen.LIBRARY -> model.visibleRows.take(14).map { it.id }
            Screen.DISCOVER -> emptyList()
            Screen.LATER -> emptyList()
            Screen.TOPICS -> model.topics.take(4).flatMap { t -> t.videos.take(6).map { it.id } }
        }
        runBlocking {
            onScreen.forEach { model.thumbnails.load(it) }
            if (model.screen == Screen.DISCOVER) model.picks.take(9).forEach { model.thumbnails.load(it.pick.video, large = true) }
            if (model.screen == Screen.LATER) model.later.forEach { model.thumbnails.load(it.id, large = true) }
        }
        val scene = ImageComposeScene(width = 2560, height = 1640, density = Density(2f)) {
            ForageTheme(dark = dark) { App(model, onImport = {}) }
        }
        // Frames over about a second, so layout settles and menus and dialogs finish fading in.
        repeat(12) { scene.render(it * 100_000_000L) }
        val png = scene.render(1_200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(outDir, "$name.png").writeBytes(png)
        scene.close()
        println("wrote $name.png")
    }

    shot("1-library-watch-later") {
        screen = Screen.LIBRARY
        selectSource(sources.first { it is LibrarySource.Playlist && it.name == "Watch later" })
        stateFilter = StateFilter.INCLUDED
        listOf(1, 3, 4).mapNotNull { visibleRows.getOrNull(it)?.id }.forEach(::toggle)
    }
    shot("2-discover") {
        clearSelection()
        message = null
        loadTopics()
        message = null
        screen = Screen.DISCOVER
        // Show the feedback states: pick 2 saved, pick 3 marked "more like this".
        if (picks.size >= 3 && picks[1].feedback.isEmpty()) {
            feedback(picks[1], Feedback.SAVED)
            feedback(picks[2], Feedback.MORE)
        }
    }
    shot("4-later") {
        // A fuller queue than the two saved so far, to show the layout.
        picks.drop(3).take(5).forEach { feedback(it, Feedback.SAVED) }
        screen = Screen.LATER
    }
    shot("7-topics") {
        screen = Screen.TOPICS
        loadTopics()
        topics.getOrNull(0)?.let { setTopicPreference(it, forage.core.recommend.TopicPreference.PINNED) }
        topics.getOrNull(4)?.let { setTopicPreference(it, forage.core.recommend.TopicPreference.MUTED) }
    }
    shot("5-discover-dark", dark = true) { screen = Screen.DISCOVER }
    shot("6-library-dark", dark = true) {
        screen = Screen.LIBRARY
        selectSource(sources.first { it is LibrarySource.Playlist && it.name == "Watch later" })
        stateFilter = StateFilter.INCLUDED
        listOf(1, 3).mapNotNull { visibleRows.getOrNull(it)?.id }.forEach(::toggle)
    }
    shot("8-library-grid") {
        screen = Screen.LIBRARY
        stateFilter = StateFilter.ALL
        showLibraryAsGrid(true)
        selectSource(sources.first { it is LibrarySource.Playlist && it.name == "Watch later" })
        visibleRows.drop(2).take(2).forEach { toggle(it.id) }
        runBlocking { visibleRows.take(24).forEach { thumbnails.load(it.id) } }
    }
    shot("9-profile-menu") {
        message = null
        screen = Screen.DISCOVER
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
    shot("3-library-history") {
        settingsOpen = false
        showLibraryAsGrid(false)
        clearSelection()
        screen = Screen.LIBRARY
        stateFilter = StateFilter.ALL
        selectSource(LibrarySource.History)
    }
}
