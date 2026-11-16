package forage.embedding.onnx

import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .build()
        val url = "https://huggingface.co/${spec.repo}/resolve/${spec.revision}/$file"
        // Download next to the target and move into place, so an interrupted download is never mistaken for the file.
        val partial = target.resolveSibling("${target.fileName}.part")
        val response = client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(partial))
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial)
            throw IOException("Downloading $url failed with HTTP ${response.statusCode()}")
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
    }
}
