package forage.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.provider.OpenableColumns
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import forage.app.EncryptedStore
import forage.app.PickedFile
import forage.app.Platform
import forage.app.Player
import forage.app.Settings
import forage.app.Storage
import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.core.db.ForageDatabase
import forage.core.vault.PrivateVaults
import forage.core.vault.VaultDamagedException
import forage.source.pornhub.SqlFile
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import java.io.File

/** Android's side of the app: its own image decoder and language detector, the 8-bit model, and links opened by the system. */
class AndroidPlatform(private val context: Context) : Platform {
    override val isMobile = true

    override fun decodeImage(bytes: ByteArray): ImageBitmap =
        (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Not an image Android can decode")).asImageBitmap()

    private val spec = ModelSpec.MULTILINGUAL_MINILM_8BIT
    private fun modelDir(dataDir: File) = File(dataDir, "models/minilm-8bit")

    override fun embedderDownloaded(dataDir: File) = File(modelDir(dataDir), spec.modelFile.substringAfterLast('/')).isFile

    override fun loadEmbedder(dataDir: File, update: (String) -> Unit): Embedder {
        val files = ModelFiles.ensure(spec, modelDir(dataDir).toPath()) { update("Downloading $it (about 120 MB, once)") }
        return OnnxEmbedder(spec, files)
    }

    override fun loadLanguageDetector(): LanguageDetector = SystemLanguageDetector(context)

    // The system opens video links in whatever app the user has for them (LibreTube, NewPipe, a browser…).
    override val players = emptyList<Player>()

    override fun playerProblem(player: Player): String? = null

    override fun openVideo(id: PlatformId, url: String, player: Player, log: File?, report: (String) -> Unit) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            error("No app on this phone opens $url")
        }
    }

    override fun openFolder(dir: File) = false

    override fun automaticUiScale() = 1f

    override val hasPrivateVault = true

    /**
     * The private vault with SQLCipher: the database file is encrypted page by page, so its
     * contents never reach the disk in the clear, with the key and the hidden file name derived
     * from the PIN exactly as on the desktop (only the file ending differs).
     */
    override fun openPrivateVault(dataDir: File, pin: CharArray, create: Boolean, settings: Settings): Storage? {
        val derived = PrivateVaults(File(dataDir, "private")).derived(pin, "cipher")
        if (!create && !derived.file.isFile) {
            derived.key.fill(0)
            return null
        }
        System.loadLibrary("sqlcipher")
        // The factory keeps the key for as long as the vault is open; it goes with the driver.
        val driver = AndroidSqliteDriver(ForageDatabase.Schema, context, derived.file.absolutePath, SupportOpenHelperFactory(derived.key))
        try {
            // Opening is lazy: read something now, so a damaged file shows at unlock, not later.
            driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0)
        } catch (e: Exception) {
            driver.close()
            throw VaultDamagedException("The vault file is damaged or can't be read", e)
        }
        val db = ForageDatabase(driver)
        return Storage.private(
            object : EncryptedStore {
                override val db = db
                // Every change is encrypted as it's written; there's nothing to save later.
                override fun saveIfChanged() = false
                override fun close() = driver.close()
            },
            settings,
        )
    }

    /** Set by the open screen: shows the system's file picker and passes on what was picked. */
    var picker: ((mimeTypes: Array<String>, picked: (Uri) -> Unit) -> Unit)? = null

    override fun chooseFile(title: String, extensions: List<String>, chosen: (PickedFile) -> Unit) {
        val mimes = extensions.flatMap { MIME_TYPES[it].orEmpty() }.distinct().toTypedArray().ifEmpty { arrayOf("*/*") }
        val show = picker ?: return
        show(mimes) { uri ->
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            chosen(PickedFile(name, size) { context.contentResolver.openInputStream(uri) ?: error("Couldn't read $name") })
        }
    }

    override val sqlFiles: SqlFile.Opener = AndroidSqlFile

    private companion object {
        // Many file managers label SQLite files as plain binary data.
        val MIME_TYPES = mapOf(
            "zip" to listOf("application/zip", "application/x-zip-compressed"),
            "db" to listOf("application/x-sqlite3", "application/vnd.sqlite3", "application/octet-stream"),
        )
    }
}

/**
 * Language detection with Android's built-in, on-device text classifier: no download and little
 * memory, unlike the desktop's detector. Scores are relative to the most likely language, as the
 * language filter expects.
 */
class SystemLanguageDetector(context: Context) : LanguageDetector {
    private val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier

    override fun scores(text: String): Map<String, Float> {
        if (text.isBlank()) return emptyMap()
        val result = runCatching { classifier.detectLanguage(TextLanguage.Request.Builder(text).build()) }.getOrNull() ?: return emptyMap()
        val scores = (0 until result.localeHypothesisCount).associate { i ->
            val locale = result.getLocale(i)
            locale.toLanguageTag().substringBefore('-') to result.getConfidenceScore(locale)
        }
        val best = scores.values.maxOrNull()?.takeIf { it > 0f } ?: return emptyMap()
        return scores.mapValues { it.value / best }.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }
}
