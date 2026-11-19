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
import forage.app.Platform
import forage.app.Player
import forage.app.Settings
import forage.app.Storage
import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.core.vault.PrivateVaults
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

    // The private vault comes to Android in the next step.
    override val hasPrivateVault = false

    override fun privateStorage(vaults: PrivateVaults, vault: PrivateVaults.Unlocked, settings: Settings): Storage? = null

    override fun chooseFile(title: String, extension: String): File? = null
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
