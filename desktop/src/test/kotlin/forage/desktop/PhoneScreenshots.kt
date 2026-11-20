package forage.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import forage.app.AppModel
import forage.app.Models
import forage.app.Platform
import forage.app.Player
import forage.app.Screen
import forage.app.ui.App
import forage.app.ui.ForageTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** The desktop's platform posing as a phone, to see the shared screens at phone size without a device. */
private object PhonePreview : Platform by DesktopPlatform {
    override val isMobile = true
    override val players = emptyList<Player>()
    override val hasPrivateVault = true
}

/**
 * Renders the screens at a phone's size (412 × 892 dp) against a real vault.
 * Usage: PhoneScreenshotsKt <data-dir> <output-dir>
 */
fun main(args: Array<String>) {
    val (dataDir, outDir) = args.map(::File)
    outDir.mkdirs()
    val model = AppModel(
        dataDir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
        DesktopStorage.plain(dataDir), Models(dataDir, PhonePreview), PhonePreview,
    )
    runBlocking { model.reload() }
    fun shot(name: String, dark: Boolean = false, setup: AppModel.() -> Unit) {
        model.setup()
        runBlocking { preload(model) }
        val scene = ImageComposeScene(width = 824, height = 1784, density = Density(2f)) {
            ForageTheme(dark = dark) { App(model.privateModel ?: model, onImport = {}) }
        }
        repeat(12) { scene.render(it * 100_000_000L) }
        File(outDir, "phone-$name.png").writeBytes(scene.render(1_200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote phone-$name.png")
    }
    shot("home") { go(Screen.HOME) }
    shot("library-hub") { go(Screen.LIBRARY) }
    shot("playlists") { openLibrary(forage.app.LibraryView.PLAYLISTS) }
    shot("playlist") { sources.filterIsInstance<forage.app.LibrarySource.Playlist>().firstOrNull()?.let { openPlaylist(it.name) } }
    shot("library") { openLibrary(forage.app.LibraryView.LIST, forage.app.LibrarySource.Liked) }
    shot("history") { openLibrary(forage.app.LibraryView.LIST, forage.app.LibrarySource.History) }
    shot("library-grid") { showLibraryAsGrid(true) }
    shot("discover") { showLibraryAsGrid(false); go(Screen.DISCOVER) }
    shot("later") {
        // A few saved videos, so the queue shows its rows.
        picks.take(4).forEach { if (it.pick.video !in later.map { l -> l.id }) feedback(it, forage.app.Feedback.SAVED) }
        openLibrary(forage.app.LibraryView.LATER)
    }
    shot("topics") { openLibrary(forage.app.LibraryView.TASTE); loadTopics() }
    shot("subscriptions") { go(Screen.SUBSCRIPTIONS) }
    shot("settings") { settingsOpen = true }
    shot("add") {
        settingsOpen = false
        openLibrary(forage.app.LibraryView.LIST)
        go(Screen.HOME)
        searchText = "geowizard"
        submitSearch()
        runBlocking { searchResults.take(5).forEach { thumbnails.load(it.id) } }
    }
    shot("discover-dark", dark = true) { closeAddDialog(); go(Screen.DISCOVER) }
    shot("private-dialog", dark = true) { profileDialog = forage.app.ProfileDialog.PRIVATE }
    shot("private-settings", dark = true) {
        profileDialog = null
        runBlocking { unlockPrivate("phone screenshot passphrase".toCharArray(), create = true)?.join() }
        privateModel?.settingsOpen = true
    }

    // A new phone: nothing imported yet.
    val empty = java.nio.file.Files.createTempDirectory("phone-empty").toFile()
    val fresh = AppModel(
        empty, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
        DesktopStorage.plain(empty), Models(empty, PhonePreview), PhonePreview,
    )
    runBlocking { fresh.reload() }
    val scene = ImageComposeScene(width = 824, height = 1784, density = Density(2f)) { ForageTheme(dark = true) { App(fresh, onImport = {}) } }
    repeat(12) { scene.render(it * 100_000_000L) }
    File(outDir, "phone-empty.png").writeBytes(scene.render(1_200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("wrote phone-empty.png")
}
