package forage.desktop

import java.io.File
import java.util.concurrent.TimeUnit

enum class Theme(val label: String) {
    SYSTEM("Follow the system"), LIGHT("Light"), DARK("Dark");

    companion object {
        /**
         * Whether the desktop prefers a dark theme, for systems Compose can't ask itself: COSMIC
         * (Pop!_OS) keeps it in a config file, GNOME and others answer through gsettings.
         * Null when neither says.
         */
        fun linuxPrefersDark(): Boolean? {
            val config = System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config"
            val cosmic = File(config, "cosmic/com.system76.CosmicTheme.Mode/v1/is_dark")
            if (cosmic.isFile) return cosmic.readText().trim() == "true"
            return runCatching {
                val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                    .redirectErrorStream(true).start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) return null
                when (process.inputStream.bufferedReader().readText().trim().trim('\'')) {
                    "prefer-dark" -> true
                    "prefer-light", "default" -> false
                    else -> null
                }
            }.getOrNull()
        }
    }
}
