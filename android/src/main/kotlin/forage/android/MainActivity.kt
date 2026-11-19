package forage.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import forage.app.Theme
import forage.app.ui.App
import forage.app.ui.ForageTheme
import forage.core.importer.TakeoutFolder
import kotlinx.coroutines.Dispatchers
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
        when {
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> {
                val name = fileName(uri) ?: "the Takeout export"
                model.importTakeout(name) {
                    contentResolver.openInputStream(uri)?.use(TakeoutFolder::readZip) ?: error("Couldn't read $name")
                }
            }
            head.decodeToString(0, minOf(head.size, 15)) == "SQLite format 3" ->
                if (model.libraryEmpty) replaceDatabase(uri) else pendingDatabase = uri
            else -> Toast.makeText(this, "Pick a Takeout zip from Google, or a forage.db from your computer", Toast.LENGTH_LONG).show()
        }
    }

    /** The system file picker for the shared screens (the catalogue import, for one). */
    private var picked: ((Uri) -> Unit)? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val then = picked
        picked = null
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

    override fun onDestroy() {
        if (isFinishing) app.platform.picker = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.platform.picker = { mimeTypes, then ->
            picked = then
            pickFile.launch(mimeTypes)
        }
        setContent {
            val model = app.model ?: return@setContent
            val active = model.privateModel ?: model
            val dark = when (active.theme) {
                Theme.LIGHT -> false
                Theme.DARK -> true
                Theme.SYSTEM -> isSystemInDarkTheme()
            }
            ForageTheme(dark = dark) {
                // Drawn edge to edge; the content keeps clear of the status and navigation bars.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    App(active, onImport = { pickImport.launch(arrayOf("application/zip", "application/x-sqlite3", "application/octet-stream", "*/*")) })
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
