package forage.embedding.onnx

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A sentence-embedding model published on Hugging Face in ONNX form. */
data class ModelSpec(
    val repo: String,
    /** Pinned commit, so everyone embeds with identical weights. */
    val revision: String,
    val modelFile: String,
    val maxTokens: Int,
    val dimensions: Int,
) {
    val id get() = "$repo@${revision.take(8)}/$modelFile/$maxTokens"

    companion object {
        /** The SPEC's choice: small, multilingual (German, English, French titles), 384 dimensions. */
        val MULTILINGUAL_MINILM = ModelSpec(
            repo = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
            revision = "e8f8c211226b894fcb81acc59f3b34ba3efd5f42",
            modelFile = "onnx/model.onnx",
            maxTokens = 128,
            dimensions = 384,
        )

        /**
         * The same model with 8-bit weights, for phones: 118 MB instead of 470 and twice as fast.
         * Measured on 2,400 titles: same-text cosine to the full model 0.994 on average, 8.9 of each
         * title's 10 nearest titles the same.
         */
        val MULTILINGUAL_MINILM_8BIT = MULTILINGUAL_MINILM.copy(modelFile = "onnx/model_qint8_arm64.onnx")
    }
}

/** Downloads a model's files on first use ("download on first use", SPEC Risks). */
object ModelFiles {
    private const val TOKENIZER = "tokenizer.json"

    data class Local(val model: Path, val tokenizer: Path)

    fun ensure(spec: ModelSpec, dir: Path, onDownload: (String) -> Unit = {}): Local {
        val local = Local(dir.resolve(spec.modelFile.substringAfterLast('/')), dir.resolve(TOKENIZER))
        Files.createDirectories(dir)
        download(spec, TOKENIZER, local.tokenizer, onDownload)
        download(spec, spec.modelFile, local.model, onDownload)
        return local
    }

    private fun download(spec: ModelSpec, file: String, target: Path, onDownload: (String) -> Unit) {
        if (Files.exists(target)) return
        onDownload(file)
        val url = "https://huggingface.co/${spec.repo}/resolve/${spec.revision}/$file"
        // Download next to the target and move into place, so an interrupted download is never mistaken for the file.
        val partial = target.resolveSibling("${target.fileName}.part")
        // HttpURLConnection, which the desktop JVM and Android both have; it follows the redirect to the file store.
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val status = connection.responseCode
            if (status != 200) throw IOException("Downloading $url failed with HTTP $status")
            connection.inputStream.use { input -> Files.newOutputStream(partial).use { input.copyTo(it, 1 shl 20) } }
        } catch (e: IOException) {
            Files.deleteIfExists(partial)
            throw e
        } finally {
            connection.disconnect()
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
