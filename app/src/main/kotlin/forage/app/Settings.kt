package forage.app

import java.io.File
import java.util.Properties

/**
 * Per-device preferences, kept next to the database as a plain properties file. Without a file
 * they live in memory only, starting from [copyOf]: the private vault uses that, so that nothing
 * about it is written in the clear.
 */
class Settings(private val file: File?, copyOf: Settings? = null) {
    private val properties: Properties = Properties().apply {
        if (file?.isFile == true) file.inputStream().use(::load)
        copyOf?.let { putAll(it.properties); remove("profile") }
    }

    /** The chosen player; null until the user picks one, and the app picks a default. */
    var player: Player?
        get() = properties.getProperty("player")?.let { runCatching { Player.valueOf(it) }.getOrNull() }
        set(value) = if (value == null) Unit.also { remove("player") } else set("player", value.name)

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

    /** False while the history is paused. */
    var recordHistory: Boolean
        get() = properties.getProperty("history.record", "true").toBoolean()
        set(value) = set("history.record", value.toString())

    /** Whether a search was ever made here, for Home's "Get started". */
    var hasSearched: Boolean
        get() = properties.getProperty("search.used", "false").toBoolean()
        set(value) = set("search.used", value.toString())

    var libraryGrid: Boolean
        get() = properties.getProperty("library.grid", "false").toBoolean()
        set(value) = set("library.grid", value.toString())

    /** The profile open when the app last closed. */
    var lastProfile: String?
        get() = properties.getProperty("profile")
        set(value) = if (value == null) Unit.also { properties.remove("profile") } else set("profile", value)

    /** True to keep the system's title bar instead of Forage's own; read at start. */
    var systemTitleBar: Boolean
        get() = properties.getProperty("window.systemTitleBar", "false").toBoolean()
        set(value) = set("window.systemTitleBar", value.toString())

    /** Whether preferences are kept between starts. */
    val persistent get() = file != null

    /** Interface size chosen by the user; null picks one automatically. */
    var uiScale: Float?
        get() = properties.getProperty("ui.scale")?.toFloatOrNull()
        set(value) = if (value == null) Unit.also { remove("ui.scale") } else set("ui.scale", value.toString())

    private fun remove(key: String) {
        properties.remove(key)
        if (file == null) return
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Forage settings") }
    }

    var theme: Theme
        get() = properties.getProperty("theme")?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.SYSTEM
        set(value) = set("theme", value.name)

    private fun set(key: String, value: String) {
        properties.setProperty(key, value)
        if (file == null) return
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Forage settings") }
    }
}
