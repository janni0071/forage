package forage.source.pornhub

import forage.core.source.SourceException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Downloads the site's embed dump, the zip [PornhubCatalogue.import] reads. The download goes to
 * a `.part` file next to the target and continues where it stopped when the connection drops, or
 * when a stopped download is started again.
 */
object DumpDownload {
    /** Where the site publishes the dump, linked from its webmasters page. */
    const val URL = "https://www.pornhub.com/files/pornhub.com-db.zip"

    private val ZIP_START = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    fun download(
        target: File,
        uri: URI = URI(URL),
        /** Bytes so far and in all, when the server says. */
        progress: (Long, Long?) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        attempts: Int = 5,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(ProxySelector.getDefault())
            .build(),
    ): File {
        target.absoluteFile.parentFile.mkdirs()
        val part = File(target.absoluteFile.parentFile, "${target.name}.part")
        var failure: Exception? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) sleep(1000L shl (attempt - 1).coerceAtMost(4))
            try {
                if (fetch(client, uri, part, progress, cancelled)) {
                    target.delete()
                    check(part.renameTo(target)) { "Couldn't move the download into place" }
                    return target
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw SourceException("The download kept breaking off (${failure?.message ?: "no answer"}); start it again to continue where it stopped", failure)
    }

    /** One request, continuing the part file. True when the file is complete. */
    private fun fetch(client: HttpClient, uri: URI, part: File, progress: (Long, Long?) -> Unit, cancelled: () -> Boolean): Boolean {
        val have = if (part.isFile) part.length() else 0L
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2))
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()
        val length = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
        val append = when (status) {
            206 -> true
            200 -> false
            // Asked for more than there is: the part file already holds all of it.
            416 -> { response.body().close(); return have > 0 }
            403, 451 -> {
                response.body().close()
                throw SourceException.Blocked(
                    "The site refused the download (HTTP $status). It blocks some regions over age-verification laws; " +
                        "if the link opens in your browser, download it there and choose the file instead",
                )
            }
            else -> {
                response.body().close()
                throw SourceException("The site answered the download with HTTP $status")
            }
        }
        val type = response.headers().firstValue("Content-Type").orElse("")
        if ("text/html" in type) {
            response.body().close()
            throw SourceException.Blocked(
                "The site answered with a web page instead of the dump; where it requires age verification it shows that page " +
                    "to programs too. Download the dump in your browser and choose the file instead",
            )
        }
        val total = length?.let { if (append) have + it else it }
        response.body().use { body -> copy(body, part, append, have.takeIf { append } ?: 0L, total, progress, cancelled) }
        return total == null || part.length() >= total
    }

    private fun copy(body: InputStream, part: File, append: Boolean, start: Long, total: Long?, progress: (Long, Long?) -> Unit, cancelled: () -> Boolean) {
        val buffer = ByteArray(1 shl 20)
        var done = start
        var checked = append
        java.io.FileOutputStream(part, append).use { out ->
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                if (!checked) {
                    checked = true
                    // A zip starts with "PK"; anything else is an error page or a different file.
                    if (n < 4 || !(0 until 4).all { buffer[it] == ZIP_START[it] }) {
                        throw SourceException("What the site sent isn't a zip file, so it can't be the dump")
                    }
                }
                out.write(buffer, 0, n)
                done += n
                progress(done, total)
                if (cancelled()) throw InterruptedException("Download stopped")
            }
        }
    }
}
