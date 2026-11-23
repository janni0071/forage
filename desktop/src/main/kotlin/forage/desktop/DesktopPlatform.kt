package forage.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import forage.app.AppModel
import forage.app.Models
import forage.app.PickedFile
import forage.app.Platform
import forage.app.Player
import forage.app.Settings
import forage.app.Storage
import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.core.source.ExtractorSource
import forage.core.source.Sources
import forage.core.vault.PrivateVaults
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import forage.language.lingua.LinguaDetector
import forage.source.pornhub.JdbcSqlFile
import forage.source.pornhub.PornhubSource
import forage.source.pornhub.SqlFile
import forage.source.youtube.YouTubeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.TimeUnit

/** The desktop's side of the app: JDBC storage, the full model, Lingua, players as programs, Skia images. */
object DesktopPlatform : Platform {
    override val isMobile = false

    override fun decodeImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

    override fun embedderDownloaded(dataDir: File) = File(dataDir, "models/minilm/model.onnx").isFile

    override fun loadEmbedder(dataDir: File, update: (String) -> Unit): Embedder {
        val spec = ModelSpec.MULTILINGUAL_MINILM
        val files = ModelFiles.ensure(spec, File(dataDir, "models/minilm").toPath()) { update("Downloading $it (about 470 MB)") }
        return OnnxEmbedder(spec, files)
    }

    override fun loadLanguageDetector(): LanguageDetector = LinguaDetector()

    override val players = listOf(Player.FREETUBE, Player.MPV, Player.BROWSER)

    override fun playerProblem(player: Player): String? = Players.problem(player)

    /** mpv says how far it got over its control socket (for every platform it plays); FreeTube and the browser can't. */
    override fun reportsBack(player: Player, id: PlatformId) =
        player == Player.MPV && Players.problem(Player.MPV) == null

    override fun openVideo(id: PlatformId, url: String, player: Player, log: File?, report: (String) -> Unit, watching: forage.app.Watching?) {
        val socket = if (watching != null && reportsBack(player, id)) MpvWatcher.newSocket() else null
        val process = Players.open(player, id, url, socket?.let { mpvReportArgs(it, watching?.startAt) }.orEmpty()) ?: return
        // The private vault has no log (nothing about its videos on disk): what the player says is
        // then kept in memory only, for as long as it's needed to say why it failed.
        val output = PlayerOutput(process, log)
        if (socket != null && watching != null) MpvWatcher(socket, watching, alive = process::isAlive).start()
        // A player that can't play the link usually quits within seconds; say why instead of doing nothing.
        Thread {
            if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() != 0) {
                val lines = output.tail()
                val tail = lines.joinToString("\n")
                report(
                    "${player.label} couldn't play the video (it quit with code ${process.exitValue()})." +
                        (explainPlayerFailure(lines)?.let { "\n\n$it" } ?: "") +
                        (if (tail.isNotEmpty()) "\n\nWhat it said last:\n$tail" else "") +
                        (log?.let { "\n\nEverything it said is in ${it.path}" } ?: ""),
                )
            }
        }.apply { isDaemon = true }.start()
    }

    override fun openFolder(dir: File): Boolean = runCatching { java.awt.Desktop.getDesktop().open(dir) }.isSuccess

    override fun automaticUiScale(): Float = automaticZoom()

    override val hasPrivateVault = true

    override fun openPrivateVault(dataDir: File, pin: CharArray, create: Boolean, settings: Settings): Storage? {
        val vaults = PrivateVaults(File(dataDir, "private"))
        val vault = (if (create) vaults.create(pin) else vaults.unlock(pin)) ?: return null
        return Storage.private(EncryptedDatabase(vaults, vault), settings)
    }

    override fun chooseFile(title: String, extensions: List<String>, chosen: (PickedFile) -> Unit) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(extensions.joinToString(", ") { ".$it" }, *extensions.toTypedArray())
            currentDirectory = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        chosen(PickedFile(file.name, file.length()) { file.inputStream() })
    }

    override val sqlFiles: SqlFile.Opener = JdbcSqlFile
}

/** The shared model, as the desktop app (and its tests) create it. */
typealias DesktopModel = AppModel

@Suppress("FunctionName")
fun DesktopModel(
    dataDir: File,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    storage: Storage = DesktopStorage.plain(dataDir),
    models: Models = Models(dataDir, DesktopPlatform),
    privateSourceFactory: () -> ExtractorSource = { Sources(YouTubeSource(), PornhubSource()) },
    sourceFactory: () -> ExtractorSource = { YouTubeSource() },
): AppModel = AppModel(dataDir, scope, io, storage, models, DesktopPlatform, privateSourceFactory, sourceFactory)

/**
 * What a player says, read as it comes: written to [log] where there is one, and the last lines
 * kept in memory, for the error dialog. Status lines a player redraws (mpv's "AV: 00:03:25 / ...")
 * say nothing and are left out.
 */
class PlayerOutput(process: Process, log: File?) {
    private val last = ArrayDeque<String>()
    private val finished = java.util.concurrent.CountDownLatch(1)

    init {
        Thread({
            try {
                val writer = log?.let { runCatching { it.parentFile?.mkdirs(); it.bufferedWriter() }.getOrNull() }
                writer.use {
                    // mpv ends its status lines with a carriage return; reading lines splits on it too.
                    process.inputStream.bufferedReader().forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || STATUS.any { line.startsWith(it) }) return@forEachLine
                        writer?.apply { write(line); newLine(); flush() }
                        synchronized(last) {
                            last.addLast(line)
                            while (last.size > KEPT) last.removeFirst()
                        }
                    }
                }
            } catch (_: Exception) {
                // The player went away mid-line: what came before is what there is.
            } finally {
                finished.countDown()
            }
        }, "player-output").apply { isDaemon = true }.start()
    }

    /** Its last lines, once it's done talking (or after a moment, if it isn't). */
    fun tail(lines: Int = 15): List<String> {
        finished.await(2, TimeUnit.SECONDS)
        return synchronized(last) { last.toList().takeLast(lines) }
    }

    private companion object {
        val STATUS = listOf("AV:", "A:", "V:", "(Paused)", "(Buffering)")
        const val KEPT = 60
    }
}

/** What a player's last lines mean, where it's a known case worth explaining; null otherwise. */
internal fun explainPlayerFailure(lines: List<String>): String? {
    val text = lines.joinToString("\n")
    return when {
        "premium" in text.lowercase() ->
            "This video is for Pornhub Premium members only; Forage can't play it."
        "[PornHub]" in text && "HTTP Error 410" in text ->
            "Pornhub turns yt-dlp away (\"410 Gone\") unless it can pass for a browser, which takes the curl_cffi add-on. " +
                "With yt-dlp from pipx: pipx inject yt-dlp curl_cffi (from pip: pip install curl_cffi). " +
                "If the video still won't play, it may be for Premium members only."
        else -> null
    }
}
