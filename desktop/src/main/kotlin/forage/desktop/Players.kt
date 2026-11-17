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

    /** The command that opens [url] in this player, or null when it isn't installed. */
    fun command(url: String): List<String>? = when (this) {
        FREETUBE -> onPath("freetube")?.let { listOf(it, url) }
            ?: if (flatpakHas(FREETUBE_FLATPAK)) listOf("flatpak", "run", FREETUBE_FLATPAK, url) else null
        MPV -> onPath("mpv")?.let { listOf(it, url) }
        BROWSER -> null
    }

    val available: Boolean get() = this == BROWSER || command("x") != null

    companion object {
        private const val FREETUBE_FLATPAK = "io.freetubeapp.FreeTube"

        fun watchUrl(id: PlatformId): String {
            require(id.platform == "youtube") { "No player link for $id" }
            return "https://www.youtube.com/watch?v=${id.id}"
        }

        /** Opens [id] in [player]; throws with a readable message when that isn't possible. */
        fun open(player: Player, id: PlatformId) {
            val url = watchUrl(id)
            if (player == BROWSER) {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    error("This system can't open a browser from Java; copy the link instead: $url")
                }
                Desktop.getDesktop().browse(URI(url))
                return
            }
            val command = player.command(url) ?: error("${player.label} isn't installed (not found on PATH)")
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }

        private fun onPath(name: String): String? =
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, name) }
                .firstOrNull { it.isFile && it.canExecute() }?.path

        private fun flatpakHas(app: String): Boolean = onPath("flatpak") != null && runCatching {
            ProcessBuilder("flatpak", "info", app).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false)
    }
}
