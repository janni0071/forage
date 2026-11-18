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
 * Each image is downloaded once; failures rest for [retryAfter] so a missing image isn't
 * requested on every scroll, but one that failed for a passing reason comes back soon. Large images fall back to small ones where they don't exist.
 */
class Thumbnails(
    /** Null keeps images in memory only, for the private vault. */
    private val dir: File?,
    /** How long a failed image rests before it may be tried again. */
    val retryAfter: Duration = Duration.ofSeconds(20),
    private val clock: () -> Long = System::nanoTime,
    /**
     * The video's preview images, best first: (video, large, fresh), where fresh asks for newly
     * looked-up addresses. Last, so callers can pass it as a trailing lambda.
     */
    private val urls: (PlatformId, Boolean, Boolean) -> List<String>,
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

    /** Videos whose images the server refused, so the UI can say the preview is only on the site. */
    private val refusedIds = Collections.synchronizedSet(HashSet<PlatformId>())

    /** True when the last try for [id] ended with the image server refusing every image. */
    fun refused(id: PlatformId): Boolean = id in refusedIds

    private val frames = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 160
    })
    private val failedFrames = Collections.synchronizedSet(HashSet<String>())

    /** One preview frame by its address, kept in memory only; null when it won't load. */
    suspend fun frame(url: String): ImageBitmap? {
        frames[url]?.let { return it }
        if (url in failedFrames) return null
        val image = permits.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val response = client.send(
                        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
                            .header("Accept", "image/jpeg,image/png,image/webp;q=0.9,image/gif;q=0.5").build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
                    if (response.statusCode() == 200) Image.makeFromEncoded(response.body()).toComposeImageBitmap() else null
                }.getOrNull()
            }
        }
        if (image == null) failedFrames += url else frames[url] = image
        return image
    }

    /** The image if it is already in memory; never blocks. */
    fun cached(id: PlatformId, large: Boolean = false): ImageBitmap? = memory[Key(id, large)]

    /** Why images didn't load in this session, most frequent first, e.g. "HTTP 403 from img.example". */
    fun problems(): List<Pair<String, Int>> =
        synchronized(problemCounts) { problemCounts.entries.map { it.key to it.value } }.sortedByDescending { it.second }

    private val problemCounts = HashMap<String, Int>()

    private class Failure(val reason: String, val refused: Boolean = false) : Exception(reason)

    suspend fun load(id: PlatformId, large: Boolean = false): ImageBitmap? {
        val key = Key(id, large)
        memory[key]?.let { return it }
        if (failedRecently(key)) return if (large) load(id, false) else null
        val image = permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    image(key).also { refusedIds -= id }
                } catch (e: Failure) {
                    if (e.refused) refusedIds += id
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
     * Downloads the first of the video's images that loads, within [MAX_REQUESTS]. Images on a
     * server that refused one are skipped. Once none are left, the addresses are looked up afresh,
     * up to [MAX_LOOKUPS] times: the first time the first new one is tried whatever its server
     * (signed links expire), after that only ones on other servers, since a platform may hand out
     * links on a different server each time it's asked. A network error or busy server gets one
     * more try.
     */
    private fun download(key: Key): ByteArray {
        var candidates = urls(key.id, key.large, false)
        if (candidates.isEmpty()) throw Failure(NO_ADDRESS)
        val tried = HashSet<String>()
        val refusedHosts = LinkedHashSet<String>()
        var lookups = 0
        var retried = false
        var requests = 0
        var last: Failure? = null
        while (requests < MAX_REQUESTS) {
            var address = candidates.firstOrNull { it !in tried && host(it) !in refusedHosts }
            while (address == null && lookups < MAX_LOOKUPS) {
                lookups++
                candidates = urls(key.id, key.large, true)
                address = candidates.firstOrNull { it !in tried && (lookups == 1 || host(it) !in refusedHosts) }
            }
            if (address == null) break
            tried += address
            requests++
            val host = host(address) ?: run { last = Failure("unreadable image address"); null } ?: continue
            val status = try {
                val response = client.send(
                    HttpRequest.newBuilder(URI(address))
                        .timeout(Duration.ofSeconds(20))
                        // Formats Skia decodes; some image servers would otherwise pick one by guesswork.
                        .header("Accept", "image/jpeg,image/png,image/webp;q=0.9,image/gif;q=0.5")
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                if (response.statusCode() == 200) return response.body()
                response.statusCode()
            } catch (e: java.io.IOException) {
                last = Failure("${e.javaClass.simpleName} from $host")
                if (!retried) {
                    retried = true
                    tried -= address
                    Thread.sleep(1500)
                }
                continue
            }
            when {
                status in REFUSALS -> {
                    refusedHosts += host
                    last = Failure("HTTP $status from ${refusedHosts.joinToString(", ")}", refused = true)
                }
                (status == 429 || status >= 500) && !retried -> {
                    retried = true
                    tried -= address
                    last = Failure("HTTP $status from $host")
                    Thread.sleep(1500)
                }
                else -> last = Failure("HTTP $status from $host")
            }
        }
        throw last ?: Failure(NO_ADDRESS)
    }

    private fun host(address: String): String? = runCatching { URI(address).host }.getOrNull()

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
        /** Requests per image, across its alternatives: enough to find one that loads, not enough to hammer a server. */
        const val MAX_REQUESTS = 5
        const val MAX_LOOKUPS = 3
        val REFUSALS = setOf(401, 403, 404, 410)
    }
}
