package forage.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import forage.app.AppModel
import forage.app.Feedback
import forage.app.LibraryView
import forage.app.Models
import forage.app.Platform
import forage.app.Player
import forage.app.Screen
import forage.app.ui.App
import forage.app.ui.ForageTheme
import forage.app.ui.LocalExtras
import forage.core.PlatformId
import forage.core.importer.ImportResult
import forage.core.importer.TakeoutData
import forage.core.importer.WatchEvent
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.source.Capability
import forage.core.source.ChannelFeed
import forage.core.source.ExtractorSource
import forage.core.source.VideoDetails
import forage.core.source.VideoMetadata
import forage.core.source.VideoStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The README's screenshots, from a made-up library: invented channels and videos with drawn
 * thumbnails, so no one's real history and no one else's images are shown.
 * Usage: gradle :desktop:demoScreenshots (writes docs/screenshots).
 */
private class Theme(val channel: String, val from: Int, val to: Int, val videos: List<Pair<String, String>>, val topic: String = "")

private val themes = listOf(
    Theme("Workshop Wren", 0xFF8A4B2A.toInt(), 0xFFE0A15A.toInt(), listOf(
        "Hand-cut dovetails, start to finish" to "DOVETAILS", "Sharpening chisels without a jig" to "SHARP", "A workbench from one sheet of plywood" to "WORKBENCH",
        "Why my mortises kept splitting" to "SPLIT?", "Restoring a 1950s hand plane" to "RESTORED", "Finishing with oil and wax" to "OIL + WAX"), topic = "woodworking"),
    Theme("Sourdough Sundays", 0xFFB5566B.toInt(), 0xFFF3C9A8.toInt(), listOf(
        "My starter routine, simplified" to "STARTER", "Open crumb: what actually matters" to "CRUMB", "Rye bread for beginners" to "RYE",
        "Shaping a tight boule" to "SHAPING", "The no-knead loaf, tested five ways" to "NO KNEAD", "Laminated dough in a small kitchen" to "LAYERS"), topic = "baking"),
    Theme("Night Sky Notes", 0xFF1B2556.toInt(), 0xFF6C4AB6.toInt(), listOf(
        "Finding Andromeda with binoculars" to "ANDROMEDA", "What I saw through a $150 telescope" to "\$150 SCOPE", "Photographing the Milky Way on a phone" to "MILKY WAY",
        "Why Jupiter's moons dance" to "JUPITER", "A beginner's star map for winter" to "WINTER SKY", "Dark sky places within a day's drive" to "DARK SKY"), topic = "astronomy"),
    Theme("Gravel & Grit", 0xFF1F6F5C.toInt(), 0xFF9BD08A.toInt(), listOf(
        "Three days on forest roads" to "3 DAYS", "Packing light for a bikepacking trip" to "PACK LIGHT", "Tubeless tyres, honestly" to "TUBELESS",
        "Climbing technique that saved my knees" to "CLIMB", "A quiet loop through the hills" to "THE LOOP", "Fixing a chain on the trail" to "FIXED"), topic = "cycling"),
    Theme("Chord Theory Club", 0xFF6A1B6E.toInt(), 0xFFE36FA8.toInt(), listOf(
        "Why this chord sounds sad" to "SAD CHORD", "Voice leading in five minutes" to "VOICES", "The circle of fifths, finally clear" to "FIFTHS",
        "Writing a bridge that lifts" to "THE BRIDGE", "Modes without the jargon" to "MODES", "Reharmonising a nursery rhyme" to "REHARM"), topic = "music theory"),
    Theme("Endgame Studio", 0xFF3A3F47.toInt(), 0xFFC9A94E.toInt(), listOf(
        "The endgame every player should know" to "ENDGAME", "Rook endings made simple" to "ROOKS", "How to stop blundering" to "NO BLUNDERS",
        "A quiet move that wins" to "QUIET MOVE", "Opposition, explained with coins" to "OPPOSITION", "Studying games from 1900" to "1900"), topic = "chess"),
    Theme("Slow Maps", 0xFF2A5D8F.toInt(), 0xFFE8D5A3.toInt(), listOf(
        "Why borders look the way they do" to "BORDERS", "A train across four countries" to "BY TRAIN", "The smallest islands people live on" to "ISLANDS",
        "Rivers that changed course" to "RIVERS", "Mapping my town by hand" to "HAND MAP", "Old maps, new eyes" to "OLD MAPS"), topic = "maps"),
)

private data class Demo(val id: PlatformId, val title: String, val caption: String, val theme: Theme, val channel: PlatformId, val duration: Long, val uploaded: Instant)

private fun demoVideos(): List<Demo> {
    val now = Instant.parse("2026-11-28T12:00:00Z")
    return themes.flatMapIndexed { t, theme ->
        val channel = PlatformId.youtube("UC" + "demo${t}channel".padEnd(22, 'x').take(22))
        theme.videos.mapIndexed { i, (title, caption) ->
            Demo(
                PlatformId.youtube("demo${t}v${i}".padEnd(11, '0').take(11)), title, caption, theme, channel,
                listOf(487L, 1290L, 742L, 2215L, 615L, 958L)[(i + t) % 6], now.minus(Duration.ofDays((i * 9L + t * 4L) % 70 + 1)),
            )
        }
    }
}

/** A thumbnail: the theme's gradient, a few soft circles, and the video's caption in big letters. */
private fun thumbnail(demo: Demo, width: Int, height: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    val canvas = surface.canvas
    // A gradient across, drawn as thin bands from one colour to the other.
    val bands = 96
    fun mix(a: Int, b: Int, t: Float, shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
    for (k in 0 until bands) {
        val t = k / (bands - 1f)
        val color = Color.makeRGB(mix(demo.theme.from, demo.theme.to, t, 16), mix(demo.theme.from, demo.theme.to, t, 8), mix(demo.theme.from, demo.theme.to, t, 0))
        val w = width / bands.toFloat()
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(k * w, 0f, w + 1f, height.toFloat()), Paint().apply { this.color = color })
    }
    val seed = demo.id.id.hashCode()
    repeat(3) { k ->
        val r = height * (0.25f + 0.12f * k)
        val x = width * (0.55f + 0.18f * ((seed shr (k * 3)) and 3) / 3f)
        val y = height * (0.2f + 0.3f * ((seed shr (k * 5)) and 3) / 3f)
        canvas.drawCircle(x, y, r, Paint().apply { color = Color.makeARGB(38, 255, 255, 255) })
    }
    val typeface = FontMgr.default.matchFamilyStyle("DejaVu Sans", FontStyle.BOLD) ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
    val font = Font(typeface, height * 0.2f)
    val textPaint = Paint().apply { color = Color.WHITE }
    val shadow = Paint().apply { color = Color.makeARGB(90, 0, 0, 0) }
    var size = height * 0.2f
    // Clear of the length badge in the corner.
    while (Font(typeface, size).measureTextWidth(demo.caption) > width * 0.66f) size *= 0.92f
    font.size = size
    val x = width * 0.07f
    val y = height * 0.82f
    canvas.drawString(demo.caption, x + size * 0.04f, y + size * 0.04f, font, shadow)
    canvas.drawString(demo.caption, x, y, font, textPaint)
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 88)!!.bytes
}

private object DemoPlatform : Platform by DesktopPlatform {
    override fun embedderDownloaded(dataDir: File) = true
}

private class PhonePlatform : Platform by DesktopPlatform {
    override val isMobile = true
    override fun embedderDownloaded(dataDir: File) = true
    override val players = listOf(Player.IN_APP, Player.OTHER_APP)
}

fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val videos = demoVideos()
    val byId = videos.associateBy { it.id }
    fun stub(d: Demo) = VideoStub(d.id, d.title, d.channel, d.theme.channel, d.duration, false, d.uploaded)
    fun details(d: Demo) = VideoDetails(
        VideoMetadata(
            d.id, d.title,
            "A video from ${d.theme.channel}, made up for Forage's screenshots. Nothing here is real, but the layout is.",
            listOf(d.caption.lowercase()), d.duration, d.channel, d.theme.channel, "en", null, d.uploaded, false,
        ),
        videos.filter { it.theme == d.theme && it.id != d.id }.map(::stub) + videos.filter { it.theme != d.theme }.take(4).map(::stub),
    )
    // Every video "fetches" from the made-up catalogue, so pages open as they would online.
    val source = object : ExtractorSource {
        override val platform = "youtube"
        override val displayName = "YouTube"
        override val capabilities = setOf(Capability.SEARCH, Capability.RELATED_VIDEOS)
        override fun video(id: PlatformId): VideoDetails = details(byId[id] ?: error("not in the demo"))
        override fun search(query: String): List<VideoStub> = videos.filter { query.lowercase() in it.title.lowercase() }.map(::stub)
        override fun watchUrl(id: PlatformId) = "https://www.youtube.com/watch?v=${id.id}"
    }

    fun library(platform: Platform): AppModel {
        val dir = kotlin.io.path.createTempDirectory("forage-demo").toFile()
        File(dir, "thumbnails/youtube").apply { mkdirs() }.let { thumbs ->
            for (d in videos) {
                File(thumbs, "${d.id.id}.jpg").writeBytes(thumbnail(d, 480, 270))
                File(thumbs, "${d.id.id}-large.jpg").writeBytes(thumbnail(d, 1280, 720))
            }
        }
        val m = AppModel(dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, DesktopStorage.plain(dir), Models(dir, platform), platform) { source }
        val watched = videos.filterIndexed { i, _ -> i % 6 < 3 }
        m.library.importTakeout(TakeoutData(
            ImportResult(watched.mapIndexed { i, d -> WatchEvent(d.id, d.title, d.channel, d.theme.channel, Instant.parse("2026-11-27T20:00:00Z").minus(Duration.ofHours(i * 7L))) }, emptyMap()),
            emptyMap(), emptyList(),
        ))
        for (d in videos) m.library.remember(d.id, d.title, d.channel, d.theme.channel, false, d.duration, d.uploaded)
        for (d in videos) m.cache.put(details(d))
        m.profiles.mark(m.profile, watched.filterIndexed { i, _ -> i % 3 != 2 }.map { it.id }, VideoState.LIKED)
        videos.filterIndexed { i, _ -> i % 6 == 3 }.forEach { m.library.saveForLater(it.id) }
        themes.take(5).forEachIndexed { t, theme ->
            val channel = videos.first { it.theme == theme }.channel
            m.library.follow(channel, theme.channel)
            m.library.storeFeed(channel, ChannelFeed(videos.filter { it.theme == theme }.map(::stub), theme.channel))
        }
        val reasons = listOf(
            "Similar to 3 videos you liked, e.g. \"%s\"", "New from a channel you follow", "Close to your %s topic",
            "Related to \"%s\", which you liked", "Similar to 2 videos you liked, e.g. \"%s\"",
        )
        val picks = videos.filterIndexed { i, _ -> i % 6 >= 4 }.mapIndexed { i, d ->
            val liked = videos.first { it.theme == d.theme }
            StoredPick(i + 1, d.id, d.title, d.channel, d.theme.channel, 0.8f - i * 0.02f, reasons[i % reasons.size].format(if (i % 5 == 2) d.theme.topic else liked.title), "search", false, d.duration, d.uploaded)
        }
        m.profiles.savePicks(m.profile, picks)
        runBlocking { m.reload() }
        m.closeWelcome()
        m.hideGuide()
        m.changeUpdateCheck(false)
        m.message = null
        return m
    }

    fun shot(m: AppModel, name: String, width: Int, height: Int, density: Float, dark: Boolean = false) {
        runBlocking { preload(m) }
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

    val wide = library(DemoPlatform)
    wide.go(Screen.HOME); shot(wide, "home", 1920, 1200, 1.5f)
    wide.go(Screen.DISCOVER); wide.picks.getOrNull(1)?.let { wide.feedback(it, Feedback.SAVED) }; wide.picks.getOrNull(2)?.let { wide.feedback(it, Feedback.LIKE) }
    wide.message = null; shot(wide, "discover", 1920, 1200, 1.5f)
    wide.go(Screen.LIBRARY); wide.openLibrary(LibraryView.LATER); wide.message = null; shot(wide, "watch-later", 1920, 1200, 1.5f)
    wide.go(Screen.SUBSCRIPTIONS); wide.message = null; shot(wide, "subscriptions", 1920, 1200, 1.5f, dark = true)
    wide.go(Screen.HOME); wide.showVideo(videos[1].id, videos[1].title, videos[1].channel, videos[1].theme.channel, videos[1].duration, videos[1].uploaded)
    wide.message = null; shot(wide, "video-page", 1920, 1200, 1.5f, dark = true)

    val phone = library(PhonePlatform())
    phone.go(Screen.HOME); shot(phone, "phone-home", 824, 1784, 2f)
    phone.go(Screen.DISCOVER); phone.message = null; shot(phone, "phone-discover", 824, 1784, 2f)
    phone.go(Screen.LIBRARY); phone.message = null; shot(phone, "phone-library", 824, 1784, 2f, dark = true)
    Runtime.getRuntime().halt(0)
}
