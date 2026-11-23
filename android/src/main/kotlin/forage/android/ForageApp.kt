package forage.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import forage.app.AppModel
import forage.app.Models
import forage.app.Settings
import forage.app.Storage
import forage.core.db.ForageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Holds the model for the app's whole life, so it outlives screens being rebuilt (rotation) and is
 * there for "Share → Forage" without opening the app.
 */
class ForageApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var platform: AndroidPlatform
        private set

    var model by mutableStateOf<AppModel?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        open()
    }

    private fun storage(): Storage {
        val driver = AndroidSqliteDriver(ForageDatabase.Schema, this, DATABASE)
        return Storage(
            db = ForageDatabase(driver),
            driver = driver,
            settings = Settings(File(filesDir, "settings.properties")),
            thumbnailsDir = File(cacheDir, "thumbnails"),
            playerLog = null,
            closeAction = { driver.close() },
        )
    }

    private fun open() {
        val opened = AppModel(filesDir, scope, Dispatchers.IO, storage(), Models(filesDir, platform), platform)
        model = opened
        scope.launch {
            opened.reload()
            opened.backUpAutomatically()
            opened.warmUp()
        }
    }

    /**
     * Replaces this phone's database with [source], a forage.db from another device (until sync
     * arrives, that's how a library gets here). Returns why it couldn't, or null. Blocks.
     */
    fun replaceDatabase(source: InputStream): String? {
        val incoming = File(cacheDir, "incoming.db")
        source.use { input -> incoming.outputStream().use { input.copyTo(it) } }
        val header = incoming.inputStream().use { it.readNBytes(16) }
        if (!header.contentEquals(SQLITE_HEADER)) {
            incoming.delete()
            return "That file isn't a Forage database (forage.db)"
        }
        model?.close()
        val target = getDatabasePath(DATABASE)
        target.parentFile?.mkdirs()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(target.path + suffix).delete()
        check(incoming.renameTo(target) || incoming.copyTo(target, overwrite = true).exists()) { "Couldn't move the database into place" }
        incoming.delete()
        open()
        return null
    }

    companion object {
        const val DATABASE = "forage.db"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
