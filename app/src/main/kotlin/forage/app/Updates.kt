package forage.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** A newer Forage than this one: its version, its release page and the file for this device. */
data class Update(val version: String, val page: String, val download: String?)

/**
 * Looking for a new version: Forage's latest release on GitHub, only when the user said yes, at most
 * once a day, asking nothing but that (no identifier, no library data; the request names only
 * "Forage" as its client). Nothing is downloaded or installed by itself: Home says there's a new
 * version, with links to the release and the file for this device.
 */
object Updates {
    /** Where Forage's releases are published. */
    const val REPOSITORY = "janni0071/forage"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
    private const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /** The latest release's JSON, from GitHub's API; null when there's none yet. */
    fun fetchLatest(): String? {
        val connection = URI(LATEST).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Forage")
            return when (val status = connection.responseCode) {
                200 -> connection.inputStream.use { it.readBytes().decodeToString() }
                404 -> null
                else -> throw IOException("GitHub answered $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The release described by [release] (GitHub's JSON), if it's newer than [current]; the
     * download is the file [fileFor] names for its version (this version of Forage's APK or
     * package: Standard and Full have files of their own).
     */
    fun newer(release: String, current: String, fileFor: (String) -> String): Update? {
        val o = json.parseToJsonElement(release) as? JsonObject ?: return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (o["draft"]?.jsonPrimitive?.contentOrNull == "true" || o["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
        val version = tag.removePrefix("v")
        if (compare(version, current) <= 0) return null
        val file = fileFor(version)
        val download = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { a ->
            a["name"]?.jsonPrimitive?.contentOrNull == file
        }?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        val page = o["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE
        return Update(version, page, download)
    }

    /** Compares versions like 0.21.0 number by number; a part that isn't a number counts as 0. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
