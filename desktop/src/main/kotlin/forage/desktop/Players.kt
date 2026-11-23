package forage.desktop

import forage.app.Player
import forage.app.Player.BROWSER
import forage.app.Player.FREETUBE
import forage.app.Player.MPV
import forage.core.PlatformId
import forage.core.Platforms
import java.awt.Desktop
import java.io.File
import java.net.URI

/** Starting the desktop players: FreeTube and mpv as programs (or Flatpaks), the browser through Java. */
object Players {
    /** How this player would be started, or why it can't be. */
    sealed interface Launch {
        data class Command(val args: List<String>) : Launch
        data class Unavailable(val reason: String) : Launch
    }

    /** [mpvArgs] go to mpv before the link (to report back, say). */
    fun launch(player: Player, url: String, extraMpvArgs: List<String> = emptyList()): Launch = when (player) {
        FREETUBE -> onPath("freetube")?.let { Launch.Command(listOf(it, url)) }
            ?: flatpak(FREETUBE_FLATPAK, url)
            ?: Launch.Unavailable("not installed")
        MPV -> {
            val mpvArgs = MPV_ARGS + extraMpvArgs
            val mpv = onPath("mpv")
            // mpv hands YouTube links to yt-dlp; without it, it quits without playing anything.
            val ytdlp = onPath("yt-dlp") ?: onPath("youtube-dl")
            when {
                mpv != null && ytdlp != null -> Launch.Command(listOf(mpv) + mpvArgs + url)
                // The Flatpak build ships its own yt-dlp; its sandbox is opened to where the socket goes.
                else -> flatpak(MPV_FLATPAK, url, mpvArgs, listOf(MpvWatcher.flatpakAccess).takeIf { extraMpvArgs.isNotEmpty() }.orEmpty())
                    ?: if (mpv != null) Launch.Unavailable("needs yt-dlp") else Launch.Unavailable("not installed")
            }
        }
        // Android's choices; the desktop treats them as the browser.
        BROWSER, Player.IN_APP, Player.OTHER_APP -> Launch.Command(emptyList())
    }

    /** Null when the player can be used, otherwise a short reason. */
    fun problem(player: Player): String? = (launch(player, "x") as? Launch.Unavailable)?.reason

    /**
     * Anything but AV1 where YouTube has it (VP9 or H.264, as good or nearly): graphics cards'
     * AV1 decoders are young, and one that fails takes FFmpeg down with it ("Assertion ... failed
     * at libavcodec/decode.c"), so the video never plays. Where there's nothing else, AV1 it is.
     */
    internal val MPV_ARGS = listOf("--ytdl-format=bestvideo[vcodec!^=av01]+bestaudio/best[vcodec!^=av01]/bestvideo+bestaudio/best")

    private const val FREETUBE_FLATPAK = "io.freetubeapp.FreeTube"
    private const val MPV_FLATPAK = "io.mpv.Mpv"

    /**
     * Opens [url], the link to [id], in [player]. FreeTube and mpv are for YouTube; videos from
     * other platforms open in the browser. Returns the started process, so the caller can report
     * it if it quits with an error; null for the browser. Throws with a readable message when
     * the player can't be started at all.
     */
    fun open(player: Player, id: PlatformId, url: String, log: File?, mpvArgs: List<String> = emptyList()): Process? {
        val player = if (id.platform == Platforms.YOUTUBE) player else BROWSER
        if (player == BROWSER) {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                error("This system can't open a browser from Java; copy the link instead: $url")
            }
            Desktop.getDesktop().browse(URI(url))
            return null
        }
        return when (val launch = launch(player, url, mpvArgs)) {
            is Launch.Unavailable -> error(
                when (launch.reason) {
                    "needs yt-dlp" -> "mpv needs a current yt-dlp to play YouTube videos. Install it (e.g. 'pipx install yt-dlp'; " +
                        "distribution packages are often too old), or install mpv from Flathub, which includes it."
                    else -> "${player.label} isn't installed"
                }
            )
            is Launch.Command -> {
                log?.parentFile?.mkdirs()
                ProcessBuilder(launch.args).redirectErrorStream(true)
                    .redirectOutput(log?.let(ProcessBuilder.Redirect::to) ?: ProcessBuilder.Redirect.DISCARD).start()
            }
        }
    }

    private fun flatpak(app: String, url: String, args: List<String> = emptyList(), access: List<String> = emptyList()): Launch? =
        if (flatpakHas(app)) Launch.Command(listOf("flatpak", "run") + access + app + args + url) else null

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.path

    private val flatpaks = HashMap<String, Boolean>()

    private fun flatpakHas(app: String): Boolean = synchronized(flatpaks) {
        flatpaks.getOrPut(app) {
            onPath("flatpak") != null && runCatching {
                ProcessBuilder("flatpak", "info", app).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
            }.getOrDefault(false)
        }
    }
}
