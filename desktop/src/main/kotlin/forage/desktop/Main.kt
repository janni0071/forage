package forage.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import forage.desktop.ui.App
import forage.desktop.ui.ForageTheme
import java.io.File
import javax.swing.JFileChooser

/** Where the vault lives: `--data DIR`, else `$FORAGE_DATA`, else the platform's per-user data folder. */
fun dataDir(args: Array<String>): File {
    args.indexOf("--data").takeIf { it >= 0 }?.let { i -> args.getOrNull(i + 1)?.let { return File(it) } }
    System.getenv("FORAGE_DATA")?.let { return File(it) }
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> File(home, "Library/Application Support/Forage")
        "win" in os -> File(System.getenv("APPDATA") ?: home, "Forage")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "forage")
    }
}

fun main(args: Array<String>) {
    val dir = dataDir(args)
    application {
        val scope = rememberCoroutineScope()
        val model = remember { DesktopModel(dir, scope) }
        LaunchedEffect(model) { model.reload() }
        Window(
            onCloseRequest = { model.close(); exitApplication() },
            title = "Forage",
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
        ) {
            ForageTheme(dark = isSystemInDarkTheme()) {
                App(model, onImport = {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Choose the extracted Takeout folder"
                    }
                    if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) model.importTakeout(chooser.selectedFile)
                })
            }
        }
    }
}
