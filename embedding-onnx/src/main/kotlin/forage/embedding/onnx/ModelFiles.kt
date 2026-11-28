package forage.embedding.onnx

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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

    /** How far a download is: [done] of [total] bytes of [file] (total null while unknown). */
    data class Progress(val file: String, val done: Long, val total: Long?) {
        val isModel get() = file != TOKENIZER

        /** For people: what is downloading, and how much of it is there. */
        fun describe(): String {
            fun mb(bytes: Long) = (bytes / 1_000_000).toString()
            val amount = if (total != null) "${mb(done)} of ${mb(total)} MB" else "${mb(done)} MB"
            return if (isModel) "Downloading the recommendation model (once): $amount"
            else "Downloading the recommendation model's word list (once): $amount"
        }
    }

    fun isDownloaded(spec: ModelSpec, dir: Path) =
        Files.exists(dir.resolve(spec.modelFile.substringAfterLast('/'))) && Files.exists(dir.resolve(TOKENIZER))

    /** [onProgress] is told at the start and end of each download, and in between at most once per megabyte. */
    fun ensure(spec: ModelSpec, dir: Path, onProgress: (Progress) -> Unit = {}, host: String = "https://huggingface.co"): Local {
        val local = Local(dir.resolve(spec.modelFile.substringAfterLast('/')), dir.resolve(TOKENIZER))
        Files.createDirectories(dir)
        download(spec, TOKENIZER, local.tokenizer, onProgress, host)
        download(spec, spec.modelFile, local.model, onProgress, host)
        return local
    }

    private fun download(spec: ModelSpec, file: String, target: Path, onProgress: (Progress) -> Unit, host: String) {
        if (Files.exists(target)) return
        val url = "$host/${spec.repo}/resolve/${spec.revision}/$file"
        // Download next to the target and move into place, so an interrupted download is never mistaken
        // for the file; what's there of it is kept, and the next try asks only for the rest.
        val partial = target.resolveSibling("${target.fileName}.part")
        val have = if (Files.exists(partial)) Files.size(partial) else 0L
        // HttpURLConnection, which the desktop JVM and Android both have; it follows the redirect to the file store.
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val status = connection.responseCode
            val start = when {
                status == 206 && have > 0 -> have
                status == 200 -> 0L
                // Asked for the rest of a file that's all there already.
                status == 416 && have > 0 -> -1L
                else -> throw IOException("Downloading $url failed with HTTP $status")
            }
            if (start >= 0) {
                val length = connection.contentLengthLong
                val total = if (length >= 0) start + length else null
                var done = start
                var reported = -1L
                fun report() {
                    val mb = done / 1_000_000
                    if (mb != reported) { reported = mb; onProgress(Progress(file, done, total)) }
                }
                report()
                val append = start > 0
                connection.inputStream.use { input ->
                    val options = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    Files.newOutputStream(partial, *options).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            report()
                        }
                    }
                }
                if (total != null && done != total) throw IOException("The download of $file broke off at $done of $total bytes")
                onProgress(Progress(file, done, total))
            }
        } finally {
            connection.disconnect()
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
