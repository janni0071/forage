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

    fun shot(name: String, setup: DesktopModel.() -> Unit) {
        model.setup()
        // Load the images up front; the headless renderer doesn't wait for them.
        val onScreen = when (model.screen) {
            Screen.LIBRARY -> model.visibleRows.take(14).map { it.id }
            Screen.DISCOVER -> model.picks.take(6).map { it.pick.video }
            Screen.LATER -> model.later.map { it.id }
        }
        runBlocking { onScreen.forEach { model.thumbnails.load(it) } }
        val scene = ImageComposeScene(width = 2560, height = 1640, density = Density(2f)) {
            ForageTheme { App(model, onImport = {}) }
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
        screen = Screen.DISCOVER
        // Show the feedback states: pick 2 saved, pick 3 marked "more like this".
        if (picks.size >= 3 && picks[1].feedback.isEmpty()) {
            feedback(picks[1], Feedback.SAVED)
            feedback(picks[2], Feedback.MORE)
        }
    }
    shot("4-later") { screen = Screen.LATER }
    shot("3-library-history") {
        screen = Screen.LIBRARY
        stateFilter = StateFilter.ALL
        selectSource(LibrarySource.History)
    }
}
