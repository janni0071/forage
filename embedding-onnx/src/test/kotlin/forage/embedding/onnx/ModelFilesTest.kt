package forage.embedding.onnx

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelFilesTest {
    private val spec = ModelSpec("someone/model", "abcdef1234567890", "onnx/model.onnx", 128, 384)
    private val model = ByteArray(3_500_000) { (it % 251).toByte() }
    private val tokenizer = "{\"tokens\": []}".toByteArray()

    @Test
    fun `a download that broke off carries on where it stopped, saying how far it got`() {
        val ranges = mutableListOf<String?>()
        var breakOffAt: Int? = 1_200_000
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path.endsWith("tokenizer.json")) tokenizer else model
            val range = exchange.requestHeaders.getFirst("Range")
            if (body === model) ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val rest = body.copyOfRange(from, body.size)
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, rest.size.toLong())
            val cut = breakOffAt
            exchange.responseBody.use { out ->
                if (body === model && cut != null) {
                    out.write(rest, 0, cut)
                    out.flush()
                    breakOffAt = null
                    // Hang up mid-file, as a dropped connection does.
                    exchange.close()
                } else {
                    out.write(rest)
                }
            }
        }
        server.start()
        val dir = Files.createTempDirectory("model")
        try {
            val host = "http://127.0.0.1:${server.address.port}"
            val seen = mutableListOf<ModelFiles.Progress>()
            assertFailsWith<IOException> { ModelFiles.ensure(spec, dir, { seen += it }, host) }
            assertTrue(Files.exists(dir.resolve("tokenizer.json")))
            assertTrue(!Files.exists(dir.resolve("model.onnx")))
            assertEquals(1_200_000L, Files.size(dir.resolve("model.onnx.part")))

            val local = ModelFiles.ensure(spec, dir, { seen += it }, host)
            assertEquals(listOf(null, "bytes=1200000-"), ranges)
            assertContentEquals(model, Files.readAllBytes(local.model))
            assertTrue(ModelFiles.isDownloaded(spec, dir))
            val last = seen.last()
            assertEquals(ModelFiles.Progress("onnx/model.onnx", 3_500_000, 3_500_000), last)
            assertEquals("Downloading the recommendation model (once): 3 of 3 MB", last.describe())
            // The second try started from what was there, not from nothing.
            assertTrue(seen.any { it.isModel && it.done == 1_200_000L })
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }
}
