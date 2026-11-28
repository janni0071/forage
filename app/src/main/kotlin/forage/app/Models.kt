package forage.app

import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import java.io.File

/**
 * The recommendation models, loaded once and shared by every vault: they hold no personal data,
 * and each takes hundreds of megabytes of memory. Which models, and how they load, is up to the platform.
 */
class Models(private val dataDir: File, private val platform: Platform) : AutoCloseable {
    private val lock = Any()
    private var embedder: Embedder? = null
    private var detector: LanguageDetector? = null
    private var detectorLoaded = false

    val downloaded get() = platform.embedderDownloaded(dataDir)

    /** The embedding model, loaded (and downloaded on first use) when first needed. */
    fun embedder(update: (String) -> Unit = {}): Embedder = synchronized(lock) {
        embedder ?: platform.loadEmbedder(dataDir, update).also { embedder = it }
    }

    /** The language detector; null where the platform has none. */
    fun detector(): LanguageDetector? = synchronized(lock) {
        if (!detectorLoaded) {
            detector = platform.loadLanguageDetector()
            detectorLoaded = true
        }
        detector
    }

    override fun close() {
        synchronized(lock) { embedder?.close() }
    }
}

/**
 * The recommendation model, the same on every device: the multilingual MiniLM with 8-bit weights,
 * 118 MB instead of the full model's 470, about twice as fast on phones and computers alike, and
 * nearly the same picks (see [ModelSpec.MULTILINGUAL_MINILM_8BIT]).
 */
object RecommendationModel {
    val spec = ModelSpec.MULTILINGUAL_MINILM_8BIT

    private fun dir(dataDir: File) = File(dataDir, "models/minilm-8bit")

    fun downloaded(dataDir: File) = ModelFiles.isDownloaded(spec, dir(dataDir).toPath())

    /** Downloads it first if it isn't there, saying how far it got; a broken-off download continues. */
    fun load(dataDir: File, update: (String) -> Unit): Embedder {
        val files = ModelFiles.ensure(spec, dir(dataDir).toPath(), onProgress = { update(it.describe()) })
        return OnnxEmbedder(spec, files)
    }
}
