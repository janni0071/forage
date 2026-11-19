package forage.app

import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
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
