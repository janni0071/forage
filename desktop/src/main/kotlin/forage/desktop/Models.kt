package forage.desktop

import forage.core.language.LanguageDetector
import forage.embedding.onnx.ModelFiles
import forage.embedding.onnx.ModelSpec
import forage.embedding.onnx.OnnxEmbedder
import forage.language.lingua.LinguaDetector
import java.io.File

/**
 * The recommendation models, loaded once and shared by every vault: they hold no personal data,
 * and each takes hundreds of megabytes of memory.
 */
class Models(private val dataDir: File) : AutoCloseable {
    private val lock = Any()
    private var embedder: OnnxEmbedder? = null
    private var detector: LanguageDetector? = null

    val downloaded get() = File(dataDir, "models/minilm/model.onnx").isFile

    /** The embedding model, loaded (and downloaded on first use) when first needed. */
    fun embedder(update: (String) -> Unit = {}): OnnxEmbedder = synchronized(lock) {
        embedder ?: run {
            val spec = ModelSpec.MULTILINGUAL_MINILM
            val files = ModelFiles.ensure(spec, File(dataDir, "models/minilm").toPath()) { update("Downloading $it (about 470 MB)") }
            OnnxEmbedder(spec, files).also { embedder = it }
        }
    }

    fun detector(): LanguageDetector = synchronized(lock) {
        detector ?: LinguaDetector().also { detector = it }
    }

    override fun close() {
        synchronized(lock) { embedder?.close() }
    }
}
