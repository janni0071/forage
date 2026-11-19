package forage.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import forage.app.Settings
import forage.app.Theme
import forage.app.Zoom
import forage.app.ui.App
import forage.app.ui.LocalExtras
import forage.desktop.ui.DesktopExtras
import forage.app.ui.ForageTheme
import forage.app.ui.WindowChrome
import forage.desktop.ui.ResizeEdges
import forage.desktop.ui.TitleBar
import forage.desktop.ui.WindowFrame
import forage.desktop.ui.moveWindow
import java.io.File
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.runBlocking
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
    // The window opens at a size that fits the interface size, read before anything is shown.
    val startSettings = Settings(File(dir, "settings.properties"))
    val startScale = startSettings.uiScale ?: automaticZoom()
    // Forage draws its own title bar unless asked not to; a window's frame can only be chosen before it opens.
    val ownFrame = !startSettings.systemTitleBar
    application {
        val scope = rememberCoroutineScope()
        val model = remember { DesktopModel(dir, scope) }
        LaunchedEffect(model) {
            model.reload()
            model.warmUp()
        }
        // While the private vault is unlocked, it replaces the normal one on screen.
        val active = model.privateModel ?: model
        val windowState = rememberWindowState(size = startSize(startScale))
        val close = {
            runBlocking { model.privateModel?.shutdown() }
            model.close()
            exitApplication()
        }
        Window(
            onCloseRequest = close,
            title = "Forage",
            icon = remember { BitmapPainter(useResource("forage.png", ::loadImageBitmap)) },
            state = windowState,
            undecorated = ownFrame,
            onKeyEvent = { event ->
                // Ctrl + Plus / Minus / 0, as in browsers.
                if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return@Window false
                when (event.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { active.changeUiScale(Zoom.step(active.effectiveUiScale, up = true)); true }
                    Key.Minus, Key.NumPadSubtract -> { active.changeUiScale(Zoom.step(active.effectiveUiScale, up = false)); true }
                    Key.Zero, Key.NumPad0 -> { active.changeUiScale(null); true }
                    else -> false
                }
            },
        ) {
            val frame = if (ownFrame) remember(window, windowState) { WindowFrame(window, windowState, close) } else null
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(860, 540)
                // Some Linux desktops report the system frame's size only after the window appears, and
                // the content keeps the size measured before; laying it out again once fixes that.
                if (!ownFrame) {
                    kotlinx.coroutines.delay(600)
                    window.invalidate()
                    window.validate()
                    if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Floating) {
                        val size = windowState.size
                        windowState.size = DpSize(size.width + 1.dp, size.height)
                        kotlinx.coroutines.delay(100)
                        windowState.size = size
                    }
                }
            }
            val systemDark = isSystemInDarkTheme()
            val linuxDark = remember { if ("linux" in System.getProperty("os.name").lowercase()) linuxPrefersDark() else null }
            val dark = when (active.theme) {
                Theme.LIGHT -> false
                Theme.DARK -> true
                Theme.SYSTEM -> linuxDark ?: systemDark
            }
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density * active.effectiveUiScale, base.fontScale),
                LocalExtras provides DesktopExtras,
            ) {
            ForageTheme(dark = dark) {
                val chrome = frame?.let { f ->
                    WindowChrome(titleBar = { TitleBar(f) }, dragArea = Modifier.moveWindow(f), overlay = { ResizeEdges(f) }, outlined = !f.maximized)
                }
                App(active, chrome = chrome, onImport = {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                        dialogTitle = "Choose the Takeout zip, or the folder it was extracted to"
                    }
                    if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) model.importTakeout(chooser.selectedFile)
                })
            }
            }
        }
    }
}

/** 1280×860 at the given interface size, but never larger than 90% of the screen. */
private fun startSize(scale: Float): DpSize {
    val screen = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val width = 1280f * scale
    val height = 860f * scale
    if (screen == null) return DpSize(width.dp, height.dp)
    // Window sizes are in the system's units, which are pixels when Java isn't scaled.
    return DpSize(minOf(width, screen.width * 0.9f).dp, minOf(height, screen.height * 0.9f).dp)
}
