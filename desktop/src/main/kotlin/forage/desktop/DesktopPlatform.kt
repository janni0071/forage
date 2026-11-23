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

    override fun openVideo(id: PlatformId, url: String, player: Player, log: File?, report: (String) -> Unit) {
        val process = Players.open(player, id, url, log) ?: return
        // A player that can't play the link usually quits within seconds; say why instead of doing nothing.
        Thread {
            if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() != 0) {
                // The last line is usually a generic "Exiting"; the line naming the error says more.
                val lines = runCatching { log?.readLines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
                val last = lines.lastOrNull { "ERROR" in it } ?: lines.lastOrNull()
                report("${player.label} couldn't play the video" + (last?.let { ": $it" } ?: "") + (log?.let { " (full output: ${it.path})" } ?: ""))
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
