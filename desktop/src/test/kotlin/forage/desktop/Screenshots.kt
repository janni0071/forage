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
            Screen.LATER -> model.later.map { it.id }
            Screen.TOPICS -> model.topics.take(4).flatMap { t -> t.videos.take(6).map { it.id } }
        }
        runBlocking {
            onScreen.forEach { model.thumbnails.load(it) }
            if (model.screen == Screen.DISCOVER) model.picks.take(9).forEach { model.thumbnails.load(it.pick.video, large = true) }
        }
        val scene = ImageComposeScene(width = 2560, height = 1640, density = Density(2f)) {
            ForageTheme(dark = dark) { App(model, onImport = {}) }
        }
        // A few frames so layout and lazy lists settle.
        repeat(3) { scene.render(it * 16_000_000L) }
        val png = scene.render(64_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
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
    shot("4-later") { screen = Screen.LATER }
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
    shot("3-library-history") {
        screen = Screen.LIBRARY
        stateFilter = StateFilter.ALL
        selectSource(LibrarySource.History)
    }
}
