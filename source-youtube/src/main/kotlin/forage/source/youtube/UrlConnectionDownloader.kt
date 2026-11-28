package forage.source.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/**
 * NewPipeExtractor leaves HTTP to the host app. This backs it with `HttpURLConnection`, which the
 * desktop JVM and Android both have (Android's also handles gzip by itself).
 */
internal class UrlConnectionDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = request.httpMethod()
            var hasUserAgent = false
            for ((name, values) in request.headers()) {
                if (name.lowercase() in SKIPPED_HEADERS) continue
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                values.forEachIndexed { i, value -> if (i == 0) connection.setRequestProperty(name, value) else connection.addRequestProperty(name, value) }
            }
            if (!hasUserAgent) connection.setRequestProperty("User-Agent", USER_AGENT)
            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val finalUrl = connection.url.toString()
            if (status == 429 || "/sorry/" in finalUrl) throw ReCaptchaException("Bot check or rate limit", finalUrl)
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            // The status line comes back under a null key; NewPipe wants header names only.
            val headers = connection.headerFields.filterKeys { it != null }
            return Response(status, connection.responseMessage.orEmpty(), headers, body, finalUrl)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"

        // Set by the connection itself; and asking for gzip would leave the desktop JVM with bytes it doesn't decompress.
        val SKIPPED_HEADERS = setOf("content-length", "host", "connection", "upgrade", "expect", "accept-encoding")
    }
}
