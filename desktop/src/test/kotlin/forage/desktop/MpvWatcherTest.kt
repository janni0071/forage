package forage.desktop

import forage.app.Watching
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvWatcherTest {
    @Test
    fun `mpv's reports reach Forage, and its closing too`() {
        val dir = Files.createTempDirectory("mpv")
        val socket = dir.resolve("mpv.sock").toFile()
        val positions = ArrayList<Pair<Long, Long>>()
        val closed = CountDownLatch(1)
        val watcher = MpvWatcher(socket, Watching(null, { s, d -> synchronized(positions) { positions += s to d } }, { closed.countDown() }), alive = { true })
        val asked = ArrayList<String>()
        // A stand-in for mpv: answers on the socket as mpv does, then quits.
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket.toPath()))
            watcher.start()
            server.accept().use { client ->
                val input = Channels.newReader(client, Charsets.UTF_8).buffered()
                repeat(3) { asked += input.readLine() }
                val out = Channels.newWriter(client, Charsets.UTF_8)
                out.write("""{"event":"property-change","id":2,"name":"duration","data":600.5}""" + "\n")
                out.write("""{"event":"property-change","id":1,"name":"time-pos","data":12.4}""" + "\n")
                out.write("""{"event":"property-change","id":1,"name":"time-pos","data":13.0}""" + "\n")
                out.write("""{"event":"property-change","id":1,"name":"time-pos","data":131.9}""" + "\n")
                out.write("""{"event":"property-change","id":3,"name":"pause","data":true}""" + "\n")
                out.write("""{"request_id":0,"error":"success"}""" + "\n")
                out.flush()
            }
            assertTrue(closed.await(10, TimeUnit.SECONDS))
        }
        assertTrue(asked.all { "observe_property" in it })
        // Every five seconds of playing at most, on pausing, and on closing.
        assertEquals(listOf(12L to 600L, 131L to 600L, 131L to 600L), positions)
        assertTrue(!socket.exists())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `played to the end, there's nothing to carry on from`() {
        val positions = ArrayList<Pair<Long, Long>>()
        val watcher = MpvWatcher(java.io.File("/nonexistent"), Watching(null, { s, d -> positions += s to d }, {}), alive = { false })
        watcher.handle("""{"event":"property-change","id":2,"name":"duration","data":300}""")
        watcher.handle("""{"event":"end-file","reason":"eof"}""")
        assertEquals(listOf(300L to 300L), positions)
    }

    @Test
    fun `mpv quitting before it made a socket still counts as closed`() {
        var closed = false
        MpvWatcher(java.io.File("/nonexistent/mpv.sock"), Watching(null, { _, _ -> }, { closed = true }), alive = { false }).run()
        assertTrue(closed)
    }
}
