package forage.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import forage.app.AppModel
import forage.app.LibraryView
import forage.app.Models
import forage.app.Platform
import forage.app.Player
import forage.app.Screen
import forage.app.ui.App
import forage.app.ui.ForageTheme
import forage.app.ui.LocalExtras
import forage.core.source.Sources
import forage.source.youtube.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * The README's screenshots, from a copy of a real library (its database, settings and cached
 * thumbnails; the copy is changed, never the library): window and phone sizes, light and dark.
 * Usage: gradle :desktop:readmeScreenshots -Pdata=DIR (writes docs/screenshots).
 */
private class Phone : Platform by DesktopPlatform {
    override val isMobile = true
    override fun embedderDownloaded(dataDir: File) = true
    override val players = listOf(Player.IN_APP, Player.OTHER_APP)
}

private object Window : Platform by DesktopPlatform {
    override fun embedderDownloaded(dataDir: File) = true
}

fun main(args: Array<String>) {
    val library = File(args[0])
    val out = File(args[1]).apply { mkdirs() }

    fun open(platform: Platform): AppModel {
        val dir = kotlin.io.path.createTempDirectory("forage-readme").toFile()
        listOf("forage.db", "settings.properties").forEach { File(library, it).takeIf(File::isFile)?.copyTo(File(dir, it)) }
        File(library, "thumbnails").takeIf(File::isDirectory)?.copyRecursively(File(dir, "thumbnails"))
        val m = AppModel(dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, DesktopStorage.plain(dir), Models(dir, platform), platform) {
            Sources(YouTubeSource())
        }
        runBlocking { m.reload() }
        m.closeWelcome()
        m.hideGuide()
        m.changeUpdateCheck(false)
        m.message = null
        return m
    }

    fun shot(m: AppModel, name: String, width: Int, height: Int, density: Float, dark: Boolean = false) {
        m.message = null
        runBlocking {
            preload(m)
            // Home's and Subscriptions' uploads too, which the shared preload leaves out.
            m.uploads.take(10).forEach { m.thumbnails.load(it.video.id) }
        }
        val scene = ImageComposeScene(width, height, Density(density)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalExtras provides forage.desktop.ui.DesktopExtras) {
                ForageTheme(dark) { App(m, onImport = {}) }
            }
        }
        repeat(12) { scene.render(it * 100_000_000L) }
        File(out, "$name.png").writeBytes(scene.render(1_200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name.png")
    }

    fun waitIdle(m: AppModel) {
        val until = System.currentTimeMillis() + 180_000
        Thread.sleep(500)
        while ((m.job != null || m.background != null) && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    val wide = open(Window)
    // The subscriptions' latest uploads, looked up as the app does.
    wide.refreshFeeds(force = true)
    waitIdle(wide)
    wide.go(Screen.HOME); shot(wide, "home", 1920, 1200, 1.5f)
    wide.go(Screen.DISCOVER); shot(wide, "discover", 1920, 1200, 1.5f)
    wide.go(Screen.LIBRARY); wide.openLibrary(LibraryView.LATER); shot(wide, "watch-later", 1920, 1200, 1.5f)
    wide.go(Screen.SUBSCRIPTIONS); shot(wide, "subscriptions", 1920, 1200, 1.5f, dark = true)
    wide.go(Screen.DISCOVER)
    wide.picks.firstOrNull()?.let { wide.showPick(it.pick) }
    waitIdle(wide)
    shot(wide, "video-page", 1920, 1200, 1.5f, dark = true)

    val phone = open(Phone())
    phone.refreshFeeds(force = true)
    waitIdle(phone)
    phone.go(Screen.HOME); shot(phone, "phone-home", 824, 1784, 2f)
    phone.go(Screen.DISCOVER); shot(phone, "phone-discover", 824, 1784, 2f)
    phone.go(Screen.LIBRARY); shot(phone, "phone-library", 824, 1784, 2f, dark = true)
    Runtime.getRuntime().halt(0)
}
