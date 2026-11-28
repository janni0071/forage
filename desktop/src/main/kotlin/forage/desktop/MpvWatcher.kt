package forage.desktop

import forage.app.Watching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Listens to mpv over its control socket (its JSON IPC): how far into the video it is, whether it's
 * paused, whether it played to the end, and when it closes, and tells [watching].
 */
class MpvWatcher(
    private val socket: File,
    private val watching: Watching,
    /** Still running? Until mpv's socket shows up, its process is the only sign of life. */
    private val alive: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seconds: Double? = null
    private var duration: Double? = null
    private var lastReport = 0L
    private var ended = false

    /** Watches until mpv closes (on a thread of its own: it takes as long as the video). */
    fun start() = Thread(::run, "mpv-watcher").apply { isDaemon = true }.start()

    fun run() {
        try {
            val channel = connect() ?: return
            channel.use {
                val out = Channels.newWriter(channel, Charsets.UTF_8)
                for ((id, property) in listOf(1 to "time-pos", 2 to "duration", 3 to "pause")) {
                    out.write("""{"command":["observe_property",$id,"$property"]}""" + "\n")
                }
                out.flush()
                Channels.newReader(channel, Charsets.UTF_8).buffered().lineSequence().forEach(::handle)
            }
        } catch (_: Exception) {
            // mpv closed mid-line, or the socket broke: it's done either way.
        } finally {
            if (!ended) report()
            socket.delete()
            watching.closed()
        }
    }

    /** Connects once mpv has made its socket; null when mpv quit (or never made one) first. */
    private fun connect(): SocketChannel? {
        val deadline = clock() + CONNECT_WAIT_MS
        while (clock() < deadline) {
            if (socket.exists()) {
                runCatching {
                    return SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket.toPath())) }
                }
            }
            if (!alive()) return null
            Thread.sleep(200)
        }
        return null
    }

    /** One line from mpv: a property it was asked to watch changed, or an event. */
    internal fun handle(line: String) {
        val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (message.string("event")) {
            "property-change" -> {
                val data = message["data"]?.jsonPrimitive
                when (message.string("name")) {
                    "time-pos" -> {
                        seconds = data?.doubleOrNull ?: return
                        if (clock() - lastReport >= REPORT_EVERY_MS) report()
                    }
                    "duration" -> duration = data?.doubleOrNull
                    // Paused: worth noting where, in case it's closed next.
                    "pause" -> if (data?.booleanOrNull == true) report()
                }
            }
            // Played to the end: nothing to carry on from.
            "end-file" -> if (message.string("reason") == "eof") {
                duration?.let { watching.position(it.toLong(), it.toLong()) }
                ended = true
            }
        }
    }

    private fun report() {
        val at = seconds ?: return
        val length = duration?.takeIf { it > 0 } ?: return
        lastReport = clock()
        watching.position(at.toLong(), length.toLong())
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        /** mpv takes a moment to start (longer as a Flatpak, and yt-dlp first looks the video up). */
        private const val CONNECT_WAIT_MS = 60_000L
        private const val REPORT_EVERY_MS = 5_000L

        /**
         * Where mpv's sockets go: the session's runtime directory (private to the user, cleared at
         * log-out), else the cache. With the Flatpak's sandbox opened to it by [flatpakAccess].
         */
        private val runtimeDir: Pair<File, String> = System.getenv("XDG_RUNTIME_DIR")?.takeIf { File(it).isDirectory }
            ?.let { File(it, "forage") to "xdg-run/forage" }
            ?: (File(System.getProperty("user.home"), ".cache/forage-run") to "xdg-cache/forage-run")

        /** A new socket for one mpv. */
        fun newSocket(): File = File(runtimeDir.first.apply { mkdirs() }, "mpv-${UUID.randomUUID().toString().take(8)}.sock")

        /** What `flatpak run` needs so the sandboxed mpv can make its socket there. */
        val flatpakAccess: String get() = "--filesystem=${runtimeDir.second}:create"
    }
}

/** The options that make mpv report back, for [socket], starting [startAt] seconds in. */
fun mpvReportArgs(socket: File, startAt: Long?): List<String> =
    listOfNotNull("--input-ipc-server=${socket.path}", startAt?.let { "--start=$it" })
