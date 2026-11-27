package forage.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import forage.app.Theme
import forage.app.ui.App
import forage.app.ui.ForageTheme
import forage.app.ui.LocalVideoSurface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as ForageApp

    /** A forage.db picked while the phone already has a library: replacing it waits for a yes. */
    private var pendingDatabase by mutableStateOf<Uri?>(null)

    /**
     * Import takes a Google Takeout zip, as a phone downloads it, or a forage.db copied from a
     * computer (until sync makes that unnecessary). Which one it is, the file's first bytes tell.
     */
    private val pickImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val model = app.model ?: return@registerForActivityResult
        val head = runCatching { contentResolver.openInputStream(uri)?.use { it.readNBytes(16) } }.getOrNull() ?: ByteArray(0)
        val name = fileName(uri) ?: "the export"
        val open = { contentResolver.openInputStream(uri) ?: error("Couldn't read $name") }
        when {
            // A Forage backup: restored into what's on screen (the private vault, if it's unlocked).
            forage.core.backup.Backups.isBackup(head) ->
                (model.privateModel ?: model).offerRestore(forage.app.PickedFile(name, -1, open))
            // A forage.db from a computer (NewPipe's own database is imported like its export).
            head.decodeToString(0, minOf(head.size, 15)) == "SQLite format 3" && !name.contains("newpipe", ignoreCase = true) ->
                if (model.libraryEmpty) replaceDatabase(uri) else pendingDatabase = uri
            // Takeout, NewPipe, FreeTube, LibreTube: told apart by their contents, and refused with
            // what to pick instead if they're none of these.
            else -> model.importFile(name, open)
        }
    }

    /** The system file picker for the shared screens (the catalogue import, for one). */
    private var picked: ((Uri) -> Unit)? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val then = picked
        picked = null
        if (uri != null) then?.invoke(uri)
    }

    /** The system's "save as" for the shared screens (backups, exports); the type is set per file. */
    private var saveType = "application/octet-stream"
    private var saved: ((Uri) -> Unit)? = null
    private val saveFile = registerForActivityResult(object : ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context: android.content.Context, input: String) = super.createIntent(context, input).setType(saveType)
    }) { uri ->
        val then = saved
        saved = null
        if (uri != null) then?.invoke(uri)
    }

    private fun replaceDatabase(uri: Uri) {
        app.scope.launch {
            val problem = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.let(app::replaceDatabase) ?: "Couldn't read that file" }
                    .getOrElse { "Couldn't import it: ${it.message}" }
            }
            Toast.makeText(this@MainActivity, problem ?: "Imported your library", Toast.LENGTH_LONG).show()
        }
    }

    private fun fileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Forage's own player, kept by the activity so the page and full screen share it. */
    private val videos by lazy { AndroidVideoSurface(this) }

    override fun onDestroy() {
        if (isFinishing) {
            app.platform.picker = null
            app.platform.saver = null
            videos.release()
        }
        super.onDestroy()
    }

    override fun onStop() {
        videos.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        videos.resume()
    }

    private var sideways = false

    /** Turning the phone sideways while a video plays fills the screen with it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowSideways = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val active = app.model?.let { it.privateModel ?: it }
        if (nowSideways && !sideways) active?.turnedSideways()
        // Upright again: full screen ends, as on YouTube.
        if (!nowSideways && sideways) {
            videos.leavePageFullscreen()
            active?.playerFullscreen = false
        }
        sideways = nowSideways
    }

    /**
     * Full screen starts sideways whichever way the phone is held; once it's held sideways, the
     * screen follows it again, so turning it upright leaves full screen. (Not with auto-rotate off:
     * the screen would turn upright at once. Back leaves full screen then.)
     */
    // Made on first use: the sensors it asks for aren't there before the activity is created.
    private val heldSideways by lazy { object : OrientationEventListener(this) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN || !(degrees in 60..120 || degrees in 240..300)) return
            disable()
            val autoRotate = runCatching { Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(false)
            if (autoRotate && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
    } }

    /** Full screen: the video across the whole screen, sideways, without the system's bars. */
    private fun fillScreen(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (on && heldSideways.canDetectOrientation()) heldSideways.enable() else heldSideways.disable()
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Syncing with the computer while Forage is in front: on opening, after changes on either side, and every minute. */
    private var syncing: kotlinx.coroutines.Job? = null

    /** Back from the player: Forage asks about the video just watched. */
    override fun onResume() {
        super.onResume()
        app.model?.let { (it.privateModel ?: it).cameBack() }
        syncing?.cancel()
        // The main library's sync, and the private vault's while it's unlocked.
        syncing = app.scope.launch {
            launch { app.model?.sync?.runWhileOpen() }
            androidx.compose.runtime.snapshotFlow { app.model?.privateModel?.sync }.collectLatest { it?.runWhileOpen() }
        }
    }

    override fun onPause() {
        syncing?.cancel()
        syncing = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sideways = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        app.platform.picker = { mimeTypes, then ->
            picked = then
            pickFile.launch(mimeTypes)
        }
        app.platform.saver = { name, mimeType, then ->
            saveType = mimeType
            saved = then
            saveFile.launch(name)
        }
        setContent {
            val model = app.model ?: return@setContent
            val active = model.privateModel ?: model
            // The private vault's look is always dark.
            val dark = active.vaultLook != null || when (active.theme) {
                Theme.LIGHT -> false
                Theme.DARK -> true
                Theme.SYSTEM -> isSystemInDarkTheme()
            }
            // The system's back gesture steps back inside the app (a video page, search results, a
            // library list) before it leaves it.
            BackHandler(enabled = active.canGoBack) { active.back() }
            // The embedded player's own full screen, over everything: Back leaves it first.
            BackHandler(enabled = videos.inPageFullscreen) { videos.leavePageFullscreen() }
            val fullscreen = active.playerFullscreen
            val anyFullscreen = fullscreen || videos.inPageFullscreen
            LaunchedEffect(anyFullscreen) { fillScreen(anyFullscreen) }
            ForageTheme(dark = dark, vault = active.vaultLook) {
                // Drawn edge to edge; the content keeps clear of the status and navigation bars
                // (all but a video playing full screen, which hides them).
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
                ) {
                    CompositionLocalProvider(LocalVideoSurface provides videos) {
                        App(active, onImport = { pickImport.launch(arrayOf("application/zip", "application/x-sqlite3", "application/octet-stream", "*/*")) })
                    }
                }
                pendingDatabase?.let { uri ->
                    AlertDialog(
                        onDismissRequest = { pendingDatabase = null },
                        title = { Text("Replace this phone's library?") },
                        text = { Text("The forage.db you picked takes the place of everything Forage has on this phone: its library, profiles and picks.") },
                        confirmButton = { TextButton(onClick = { pendingDatabase = null; replaceDatabase(uri) }) { Text("Replace") } },
                        dismissButton = { TextButton(onClick = { pendingDatabase = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
