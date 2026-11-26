package forage.desktop

import forage.app.PlayerPart
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * What mpv needs to play YouTube and Pornhub, and installing it: mpv itself (the system's package,
 * behind the system's password prompt; or its Flatpak), yt-dlp, which fetches the videos for mpv,
 * and Deno, the JavaScript runtime yt-dlp solves YouTube's checks with. yt-dlp and Deno are the
 * projects' own release builds, checked against their published checksums, kept in Forage's own
 * folder ([toolsDir]) without any password, and put first on the PATH of the mpv Forage starts, so
 * mpv finds this yt-dlp and yt-dlp this Deno. The release yt-dlp includes what Pornhub needs
 * (curl_cffi) and YouTube's challenge scripts; Forage updates it once a day ([updateInBackground]).
 */
object MpvSetup {
    /** Forage's own tools: `tools` in its data folder. Set at startup. */
    @Volatile
    var toolsDir: File? = null

    private val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    private val home = File(System.getProperty("user.home"))

    val ownYtDlp: File? get() = toolsDir?.resolve("yt-dlp")?.takeIf { it.isFile && it.canExecute() }
    private val ownDeno: File? get() = toolsDir?.resolve("deno")?.takeIf { it.isFile && it.canExecute() }

    /** The system's mpv, not the Flatpak. */
    fun nativeMpv(): String? = onPath("mpv")

    /** The yt-dlp mpv would use: Forage's own, else one on the PATH. */
    fun ytDlp(): String? = ownYtDlp?.path ?: onPath("yt-dlp") ?: onPath("youtube-dl")

    private fun deno(): String? = ownDeno?.path ?: onPath("deno") ?: home.resolve(".deno/bin/deno").takeIf { it.canExecute() }?.path

    /** Distribution packages of yt-dlp lag behind YouTube's changes by months. */
    private fun fromDistribution(path: String) = path.startsWith("/usr/bin/") || path.startsWith("/bin/")

    /** Puts Forage's own tools (and Deno's usual place) first on the PATH of a native mpv. */
    fun prepare(process: ProcessBuilder) {
        val extra = listOfNotNull(toolsDir?.takeIf { it.isDirectory }, home.resolve(".deno/bin").takeIf { it.isDirectory })
        if (extra.isEmpty()) return
        val env = process.environment()
        env["PATH"] = (extra.map { it.path } + listOfNotNull(env["PATH"])).joinToString(File.pathSeparator)
    }

    fun parts(): List<PlayerPart> {
        val mpv = nativeMpv()
        val flatpakOnly = mpv == null && Players.flatpakHas(Players.MPV_FLATPAK)
        val parts = ArrayList<PlayerPart>()
        parts += PlayerPart(
            "mpv", "mpv", "Plays the videos, in a window of its own.",
            ready = mpv != null || flatpakOnly,
            detail = when {
                mpv != null -> mpv
                flatpakOnly -> "Flatpak"
                onPath("apt-get") != null -> "Installs with your system's package manager; asks for your password"
                onPath("flatpak") != null -> "Installs from Flathub"
                else -> "Install it from your system's software centre"
            },
            installable = onPath("apt-get") != null || onPath("flatpak") != null,
            command = if (onPath("apt-get") != null) "sudo apt install mpv" else "flatpak install flathub io.mpv.Mpv",
        )
        if (flatpakOnly) {
            // The Flatpak brings its own yt-dlp, and runs in a sandbox Forage's tools don't reach.
            parts += PlayerPart("yt-dlp", "yt-dlp", "Fetches the videos for mpv.", ready = true, detail = "Included in mpv's Flatpak", installable = false, command = null)
            return parts
        }
        val ytdlp = ytDlp()
        val stale = ytdlp != null && ownYtDlp == null && fromDistribution(ytdlp)
        parts += PlayerPart(
            "yt-dlp", "yt-dlp", "Fetches the videos for mpv from YouTube and other sites.",
            ready = ytdlp != null && !stale,
            detail = when {
                ownYtDlp != null -> "Forage's own, kept up to date"
                stale -> "Your system's ($ytdlp) is usually too old for YouTube; Forage can keep its own up to date"
                ytdlp != null -> ytdlp
                else -> "Forage downloads its own (about 40 MB) and keeps it up to date; no password needed"
            },
            installable = true,
            command = "pipx install \"yt-dlp[default,curl-cffi]\"",
        )
        val deno = deno()
        parts += PlayerPart(
            "deno", "Deno", "yt-dlp runs YouTube's checks with it; without it, some YouTube videos won't play.",
            ready = deno != null,
            detail = when {
                ownDeno != null -> "Forage's own"
                deno != null -> deno
                else -> "Forage downloads its own (about 40 MB); no password needed"
            },
            installable = true,
            command = "curl -fsSL https://deno.land/install.sh | sh",
        )
        return parts
    }

    /** Installs every missing part, saying what it's doing. Blocks; throws with a readable message. */
    fun install(update: (String) -> Unit) {
        for (part in parts().filter { !it.ready && it.installable }) {
            when (part.id) {
                "mpv" -> installMpv(update)
                "yt-dlp" -> installYtDlp(update)
                "deno" -> installDeno(update)
            }
        }
    }

    private fun installMpv(update: (String) -> Unit) {
        if (onPath("apt-get") != null) {
            update("Installing mpv (enter your password in the system's prompt)")
            run(listOf("pkexec", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "mpv"), "Installing mpv") { code ->
                // pkexec: 126 when the prompt was dismissed, 127 when not allowed.
                if (code == 126 || code == 127) "The password prompt was closed, so mpv wasn't installed." else null
            }
        } else {
            update("Installing mpv from Flathub")
            run(listOf("flatpak", "install", "-y", "--noninteractive", "flathub", Players.MPV_FLATPAK), "Installing mpv from Flathub")
            Players.forgetFlatpaks()
        }
    }

    internal fun installYtDlp(update: (String) -> Unit) {
        val dir = tools()
        val name = if (arm) "yt-dlp_linux_aarch64" else "yt-dlp_linux"
        val base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        val sums = String(fetch("$base/SHA2-256SUMS"))
        val expected = sums.lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1] == name }?.get(0)
            ?: throw IOException("yt-dlp's release lists no checksum for $name")
        val part = File(dir, "yt-dlp.part")
        download("$base/$name", part) { update("Downloading yt-dlp: $it") }
        verify(part, expected, "yt-dlp")
        part.setExecutable(true)
        check(part.renameTo(File(dir, "yt-dlp"))) { "Couldn't put yt-dlp in place" }
        File(dir, "yt-dlp.updated").writeText("")
    }

    internal fun installDeno(update: (String) -> Unit) {
        val dir = tools()
        val name = if (arm) "deno-aarch64-unknown-linux-gnu.zip" else "deno-x86_64-unknown-linux-gnu.zip"
        val base = "https://github.com/denoland/deno/releases/latest/download"
        val expected = String(fetch("$base/$name.sha256sum")).trim().split(Regex("\\s+")).first()
        val zip = File(dir, "deno.zip.part")
        download("$base/$name", zip) { update("Downloading Deno: $it") }
        verify(zip, expected, "Deno")
        val part = File(dir, "deno.part")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: throw IOException("Deno's download holds no deno")
                if (entry.name == "deno") {
                    part.outputStream().use { input.copyTo(it) }
                    break
                }
            }
        }
        zip.delete()
        part.setExecutable(true)
        check(part.renameTo(File(dir, "deno"))) { "Couldn't put Deno in place" }
    }

    /** Updates Forage's own yt-dlp once a day (YouTube changes often, and old versions stop working). */
    fun updateInBackground() {
        val ytdlp = ownYtDlp ?: return
        val marker = File(ytdlp.parentFile, "yt-dlp.updated")
        if (marker.exists() && System.currentTimeMillis() - marker.lastModified() < TimeUnit.DAYS.toMillis(1)) return
        Thread {
            runCatching {
                val process = ProcessBuilder(ytdlp.path, "-U").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                if (process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) marker.writeText("")
            }
        }.apply { isDaemon = true; name = "yt-dlp update" }.start()
    }

    private fun tools(): File = (toolsDir ?: error("No folder for Forage's tools")).apply { mkdirs() }

    private fun run(command: List<String>, what: String, explain: (Int) -> String? = { null }) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        val code = process.waitFor()
        if (code != 0) {
            throw IOException(explain(code) ?: "$what failed (code $code): ${output.takeLast(4).joinToString(" ").ifEmpty { "no output" }}")
        }
    }

    private fun connect(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            if (responseCode != 200) throw IOException("Downloading $url failed with HTTP $responseCode")
        }

    private fun fetch(url: String): ByteArray = connect(url).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun download(url: String, target: File, progress: (String) -> Unit) {
        val connection = connect(url)
        try {
            val total = connection.contentLengthLong
            var done = 0L
            var shown = -1L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val mb = done / 1_000_000
                        if (mb != shown) {
                            shown = mb
                            progress(if (total > 0) "$mb of ${total / 1_000_000} MB" else "$mb MB")
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download broke off at $done of $total bytes")
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun verify(file: File, expected: String, what: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("$what's download didn't match its published checksum, so it wasn't installed")
        }
    }

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.path
}
