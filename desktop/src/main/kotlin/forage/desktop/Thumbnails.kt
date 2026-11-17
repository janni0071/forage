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
 * Each image is downloaded once; failures are remembered for the session so a missing image
 * isn't requested on every scroll.
 */
class Thumbnails(private val dir: File, private val url: (PlatformId) -> String?) {
    private val memory = Collections.synchronizedMap(object : LinkedHashMap<PlatformId, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlatformId, ImageBitmap>) = size > 400
    })
    private val failed = Collections.synchronizedSet(HashSet<PlatformId>())
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
    fun cached(id: PlatformId): ImageBitmap? = memory[id]

    suspend fun load(id: PlatformId): ImageBitmap? {
        memory[id]?.let { return it }
        if (id in failed) return null
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { decode(bytes(id) ?: return@withContext null) }
                    .onFailure { failed += id }
                    .getOrNull()
                    ?.also { memory[id] = it }
            }
        }
    }

    private fun bytes(id: PlatformId): ByteArray? {
        val file = File(dir, "${id.platform}/${id.id}.jpg")
        if (file.isFile) return file.readBytes()
        val address = url(id) ?: return null
        val response = client.send(
            HttpRequest.newBuilder(URI(address)).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() != 200) {
            failed += id
            return null
        }
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        partial.writeBytes(response.body())
        partial.renameTo(file)
        return response.body()
    }

    private fun decode(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
