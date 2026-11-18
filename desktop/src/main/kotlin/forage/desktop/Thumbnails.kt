package forage.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import forage.core.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections

/**
 * Loads video thumbnails: from memory, then from a folder on disk, then from the network.
 * Each image is downloaded once; failures are remembered for a few minutes so a missing image
 * isn't requested on every scroll, but one that failed for a passing reason comes back later. Large images fall back to small ones where they don't exist.
 */
class Thumbnails(
    /** Null keeps images in memory only, for the private vault. */
    private val dir: File?,
    private val retryAfter: Duration = Duration.ofMinutes(5),
    private val clock: () -> Long = System::nanoTime,
    /**
     * The image's address: (video, large, fresh), where fresh asks for a newly looked-up one.
     * Last, so callers can pass it as a trailing lambda.
     */
    private val url: (PlatformId, Boolean, Boolean) -> String?,
) {
    private data class Key(val id: PlatformId, val large: Boolean)

    private val memory = Collections.synchronizedMap(object : LinkedHashMap<Key, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>) = size > 400
    })
    /** When each failed image may be tried again, in [clock] time. */
    private val failed = Collections.synchronizedMap(HashMap<Key, Long>())

    private fun failedRecently(key: Key): Boolean {
        val until = failed[key] ?: return false
        if (clock() < until) return true
        failed.remove(key)
        return false
    }
    // Polite to the image host and to the CPU decoding them.
    private val permits = Semaphore(4)
    private val client by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build()
    }

    /** The image if it is already in memory; never blocks. */
    fun cached(id: PlatformId, large: Boolean = false): ImageBitmap? = memory[Key(id, large)]

    /** Why images didn't load in this session, most frequent first, e.g. "HTTP 403 from img.example". */
    fun problems(): List<Pair<String, Int>> =
        synchronized(problemCounts) { problemCounts.entries.map { it.key to it.value } }.sortedByDescending { it.second }

    private val problemCounts = HashMap<String, Int>()

    private class Failure(val reason: String) : Exception(reason)

    suspend fun load(id: PlatformId, large: Boolean = false): ImageBitmap? {
        val key = Key(id, large)
        memory[key]?.let { return it }
        if (failedRecently(key)) return if (large) load(id, false) else null
        val image = permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    image(key)
                } catch (e: Failure) {
                    // A large image missing is normal: most platforms have one size only.
                    if (!(large && e.reason == NO_ADDRESS)) synchronized(problemCounts) { problemCounts.merge(e.reason, 1, Int::plus) }
                    null
                }
            }
        }
        if (image == null) {
            failed[key] = clock() + retryAfter.toNanos()
            return if (large) load(id, false) else null
        }
        memory[key] = image
        return image
    }

    private fun image(key: Key): ImageBitmap {
        val file = dir?.let { File(it, "${key.id.platform}/${key.id.id}${if (key.large) "-large" else ""}.jpg") }
        if (file?.isFile == true) return decode(file.readBytes(), "a saved image")
        val bytes = download(key)
        val image = decode(bytes, "the image")
        if (file != null) {
            file.parentFile.mkdirs()
            val partial = File(file.parentFile, "${file.name}.part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return image
    }

    /**
     * Downloads the image, trying once more where that can help: after a network error or a busy
     * server, and with a newly looked-up address when the server refuses one (signed links expire).
     */
    private fun download(key: Key): ByteArray {
        var address = url(key.id, key.large, false) ?: throw Failure(NO_ADDRESS)
        var refreshed = false
        var retried = false
        while (true) {
            val host = runCatching { URI(address).host }.getOrNull() ?: throw Failure("unreadable image address")
            val status: Int
            try {
                val response = client.send(
                    HttpRequest.newBuilder(URI(address))
                        .timeout(Duration.ofSeconds(20))
                        // Formats Skia decodes; some image servers would otherwise pick one by guesswork.
                        .header("Accept", "image/jpeg,image/png,image/webp;q=0.9,image/gif;q=0.5")
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                status = response.statusCode()
                if (status == 200) return response.body()
            } catch (e: java.io.IOException) {
                if (!retried) {
                    retried = true
                    Thread.sleep(1500)
                    continue
                }
                throw Failure("${e.javaClass.simpleName} from $host")
            }
            when {
                status in listOf(401, 403, 404, 410) && !refreshed -> {
                    refreshed = true
                    val fresh = url(key.id, key.large, true)
                    if (fresh != null && fresh != address) {
                        address = fresh
                        continue
                    }
                }
                (status == 429 || status >= 500) && !retried -> {
                    retried = true
                    Thread.sleep(1500)
                    continue
                }
            }
            throw Failure("HTTP $status from $host")
        }
    }

    private fun decode(bytes: ByteArray, what: String): ImageBitmap = try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        throw Failure("$what isn't in a format Forage can show (${format(bytes)})")
    }

    /** What the first bytes say the file is, for the problem report. */
    private fun format(bytes: ByteArray): String {
        fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it].toInt() and 0xff == b[it] }
        return when {
            bytes.isEmpty() -> "empty"
            starts(0xff, 0xd8) -> "JPEG"
            starts(0x89, 0x50, 0x4e, 0x47) -> "PNG"
            bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "WebP"
            bytes.size > 12 && String(bytes, 4, 8, Charsets.ISO_8859_1).startsWith("ftyp") -> "AVIF or HEIF"
            bytes[0] == '<'.code.toByte() -> "a web page"
            else -> "unknown, ${bytes.size} bytes"
        }
    }

    private companion object {
        const val NO_ADDRESS = "no image address"
    }
}
