package forage.android

import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import forage.app.Theme
import forage.app.ui.App
import forage.app.ui.ForageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as ForageApp

    /** Picks a forage.db copied from the laptop and makes it this phone's library. */
    private val importDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        app.scope.launch {
            val problem = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.let(app::replaceDatabase) ?: "Couldn't read that file" }
                    .getOrElse { "Couldn't import it: ${it.message}" }
            }
            Toast.makeText(this@MainActivity, problem ?: "Imported your library", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    App(active, onImport = { importDatabase.launch(arrayOf("*/*")) })
                }
            }
        }
    }
}
