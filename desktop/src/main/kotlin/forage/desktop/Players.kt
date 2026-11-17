package forage.desktop

import forage.core.PlatformId
import java.awt.Desktop
import java.io.File
import java.net.URI

/** Where videos play (SPEC: the app finds and organizes videos, playback happens elsewhere). */
enum class Player(val label: String) {
    FREETUBE("FreeTube"),
    MPV("mpv"),
    BROWSER("Browser"),
    ;

    /** How this player would be started, or why it can't be. */
    sealed interface Launch {
        data class Command(val args: List<String>) : Launch
        data class Unavailable(val reason: String) : Launch
    }

    fun launch(url: String): Launch = when (this) {
        FREETUBE -> onPath("freetube")?.let { Launch.Command(listOf(it, url)) }
            ?: flatpak(FREETUBE_FLATPAK, url)
            ?: Launch.Unavailable("not installed")
        MPV -> {
            val mpv = onPath("mpv")
            // mpv hands YouTube links to yt-dlp; without it, it quits without playing anything.
            val ytdlp = onPath("yt-dlp") ?: onPath("youtube-dl")
            when {
                mpv != null && ytdlp != null -> Launch.Command(listOf(mpv, url))
                // The Flatpak build ships its own yt-dlp.
                else -> flatpak(MPV_FLATPAK, url)
                    ?: if (mpv != null) Launch.Unavailable("needs yt-dlp") else Launch.Unavailable("not installed")
            }
        }
        BROWSER -> Launch.Command(emptyList())
    }

    /** Null when the player can be used, otherwise a short reason. */
    val problem: String? get() = (launch("x") as? Launch.Unavailable)?.reason

    companion object {
        private const val FREETUBE_FLATPAK = "io.freetubeapp.FreeTube"
        private const val MPV_FLATPAK = "io.mpv.Mpv"

        fun watchUrl(id: PlatformId): String {
            require(id.platform == "youtube") { "No player link for $id" }
            return "https://www.youtube.com/watch?v=${id.id}"
        }

        /**
         * Opens [id] in [player]. Returns the started process, so the caller can report it if it
         * quits with an error; null for the browser. Throws with a readable message when the player
         * can't be started at all.
         */
        fun open(player: Player, id: PlatformId, log: File): Process? {
            val url = watchUrl(id)
            if (player == BROWSER) {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    error("This system can't open a browser from Java; copy the link instead: $url")
                }
                Desktop.getDesktop().browse(URI(url))
                return null
            }
            return when (val launch = player.launch(url)) {
                is Launch.Unavailable -> error(
                    when (launch.reason) {
                        "needs yt-dlp" -> "mpv needs a current yt-dlp to play YouTube videos. Install it (e.g. 'pipx install yt-dlp'; " +
                            "distribution packages are often too old), or install mpv from Flathub, which includes it."
                        else -> "${player.label} isn't installed"
                    }
                )
                is Launch.Command -> {
                    log.parentFile?.mkdirs()
                    ProcessBuilder(launch.args).redirectErrorStream(true).redirectOutput(log).start()
                }
            }
        }

        private fun flatpak(app: String, url: String): Launch? =
            if (flatpakHas(app)) Launch.Command(listOf("flatpak", "run", app, url)) else null

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
}
