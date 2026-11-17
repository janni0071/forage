package forage.desktop

import java.io.File
import java.util.Properties

/** Per-device preferences, kept next to the database as a plain properties file. */
class Settings(private val file: File) {
    private val properties = Properties().apply { if (file.isFile) file.inputStream().use(::load) }

    var player: Player
        get() = properties.getProperty("player")?.let { runCatching { Player.valueOf(it) }.getOrNull() }
            ?: Player.entries.firstOrNull { it != Player.BROWSER && it.problem == null } ?: Player.BROWSER
        set(value) = set("player", value.name)

    var searchYouTube: Boolean
        get() = properties.getProperty("discover.search", "true").toBoolean()
        set(value) = set("discover.search", value.toString())

    var showShorts: Boolean
        get() = properties.getProperty("discover.shorts", "false").toBoolean()
        set(value) = set("discover.shorts", value.toString())

    /** Thumbnails are fetched from the platform, one request per video, so they can be switched off. */
    var showThumbnails: Boolean
        get() = properties.getProperty("thumbnails", "true").toBoolean()
        set(value) = set("thumbnails", value.toString())

    var libraryGrid: Boolean
        get() = properties.getProperty("library.grid", "false").toBoolean()
        set(value) = set("library.grid", value.toString())

    var theme: Theme
        get() = properties.getProperty("theme")?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.SYSTEM
        set(value) = set("theme", value.name)

    private fun set(key: String, value: String) {
        properties.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Forage settings") }
    }
}
