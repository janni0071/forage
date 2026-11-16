package forage.source.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** NewPipeExtractor leaves HTTP to the host app; this backs it with the JDK's client. */
internal class JdkDownloader : Downloader() {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(ProxySelector.getDefault())
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()
            ?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI(request.url()))
            .timeout(Duration.ofSeconds(30))
            .method(request.httpMethod(), body)
        var hasUserAgent = false
        for ((name, values) in request.headers()) {
            if (name.lowercase() in SKIPPED_HEADERS) continue
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            values.forEach { builder.header(name, it) }
        }
        if (!hasUserAgent) builder.header("User-Agent", USER_AGENT)

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val finalUrl = response.uri().toString()
        if (response.statusCode() == 429 || "/sorry/" in finalUrl) {
            throw ReCaptchaException("Bot check or rate limit", finalUrl)
        }
        return Response(response.statusCode(), "", response.headers().map(), response.body(), finalUrl)
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"

        // The JDK client sets these itself and rejects them; it also doesn't decompress, so never ask for gzip.
        val SKIPPED_HEADERS = setOf("content-length", "host", "connection", "upgrade", "expect", "accept-encoding")
    }
}
