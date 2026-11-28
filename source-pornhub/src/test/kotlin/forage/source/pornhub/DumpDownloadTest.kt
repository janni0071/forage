package forage.source.pornhub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forage.core.source.SourceException
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DumpDownloadTest {
    private val dir = Files.createTempDirectory("download").toFile()
    private val target = File(dir, "dump.zip")
    /** Looks like a zip to the check on its first bytes. */
    private val dump = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + Random(1).nextBytes(3_000_000)
    private var handler: (HttpExchange) -> Unit = {}
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { handler(it) }
        start()
    }
    private val uri = URI("http://127.0.0.1:${server.address.port}/files/db.zip")

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    /** Serves the dump, honouring Range; [cutAfter] breaks the first answer off after that many bytes. */
    private fun serve(cutAfter: Int? = null) {
        var first = true
        handler = { e ->
            val from = e.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = dump.copyOfRange(from, dump.size)
            e.responseHeaders.add("Content-Type", "application/zip")
            e.sendResponseHeaders(if (from > 0) 206 else 200, body.size.toLong())
            val cut = if (first && cutAfter != null) cutAfter else body.size
            first = false
            // Closing after part of the body breaks the connection off, as a dropped download would.
            runCatching { e.responseBody.write(body, 0, cut); e.responseBody.flush() }
            e.close()
        }
    }

    @Test
    fun `downloads the dump and reports progress`() {
        serve()
        var last = 0L to 0L
        DumpDownload.download(target, uri, progress = { done, total -> last = done to (total ?: -1) }, sleep = {})
        assertContentEquals(dump, target.readBytes())
        assertEquals(dump.size.toLong() to dump.size.toLong(), last)
        assertFalse(File(dir, "dump.zip.part").exists())
    }

    @Test
    fun `a broken-off download continues where it stopped`() {
        serve(cutAfter = 1_000_000)
        val ranges = ArrayList<String?>()
        val inner = handler
        handler = { e -> ranges += e.requestHeaders.getFirst("Range"); inner(e) }
        DumpDownload.download(target, uri, sleep = {})
        assertContentEquals(dump, target.readBytes())
        assertEquals(null, ranges.first())
        assertEquals(true, ranges.last()?.startsWith("bytes="))
    }

    @Test
    fun `refusals and web pages are reported instead of saved`() {
        handler = { e -> e.sendResponseHeaders(451, -1); e.close() }
        assertFailsWith<SourceException.Blocked> { DumpDownload.download(target, uri, sleep = {}) }
        handler = { e ->
            val page = "<html>verify your age</html>".toByteArray()
            e.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            e.sendResponseHeaders(200, page.size.toLong()); e.responseBody.use { it.write(page) }
        }
        assertFailsWith<SourceException.Blocked> { DumpDownload.download(target, uri, sleep = {}) }
        handler = { e ->
            val other = "not a zip at all".toByteArray()
            e.sendResponseHeaders(200, other.size.toLong()); e.responseBody.use { it.write(other) }
        }
        assertFailsWith<SourceException> { DumpDownload.download(target, uri, sleep = {}) }
        assertFalse(target.exists())
    }
}
