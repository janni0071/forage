package forage.desktop

import com.sun.net.httpserver.HttpServer
import forage.core.PlatformId
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThumbnailsTest {
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val (status, body) = when (exchange.requestURI.path) {
                "/fresh.png" -> 200 to png
                "/page" -> 200 to "<html></html>".toByteArray()
                else -> 403 to ByteArray(0)
            }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val id = PlatformId("other", "v1")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `a refused link is looked up again`() {
        val asked = ArrayList<Boolean>()
        val thumbnails = Thumbnails(null) { _, large, fresh ->
            asked += fresh
            if (large) null else if (fresh) "$base/fresh.png" else "$base/expired.png"
        }
        assertNotNull(runBlocking { thumbnails.load(id, large = true) })
        assertEquals(listOf(false, false, true), asked)
        // A missing large size is normal and not reported.
        assertEquals(emptyList(), thumbnails.problems())
    }

    @Test
    fun `failures are reported by reason`() {
        val thumbnails = Thumbnails(null) { v, _, _ -> if (v.id == "v1") "$base/expired.png" else "$base/page" }
        assertNull(runBlocking { thumbnails.load(id) })
        assertNull(runBlocking { thumbnails.load(PlatformId("other", "v2")) })
        assertEquals(
            setOf("HTTP 403 from 127.0.0.1", "the image isn't in a format Forage can show (a web page)"),
            thumbnails.problems().map { it.first }.toSet(),
        )
    }
}
