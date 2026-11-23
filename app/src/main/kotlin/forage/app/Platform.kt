package forage.app

import androidx.compose.ui.graphics.ImageBitmap
import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.source.pornhub.SqlFile
import java.io.File
import java.io.InputStream

/**
 * What differs between the desktop app and the Android app. The model and the screens are shared;
 * each app passes in its own implementation of this.
 */
interface Platform {
    /** True on phones and tablets: screens adapt to touch, and the system decides which app plays a video. */
    val isMobile: Boolean

    fun decodeImage(bytes: ByteArray): ImageBitmap

    /** Whether the embedding model is already on this device, so loading it needs no download. */
    fun embedderDownloaded(dataDir: File): Boolean

    /** Loads the embedding model, downloading it first if needed; [update] describes the progress. */
    fun loadEmbedder(dataDir: File, update: (String) -> Unit): Embedder

    /** The language detector, or null to leave the language filter out. */
    fun loadLanguageDetector(): LanguageDetector?

    /** The players offered in Settings; empty where the system picks the app. */
    val players: List<Player>

    /** Why [player] can't be used right now, or null. */
    fun playerProblem(player: Player): String?

    /**
     * Opens [url], the link to [id]. Throws with a readable message when it can't be opened;
     * [report] gets problems noticed later, such as a player quitting with an error. Players that
     * report back (see [reportsBack]) start where [watching] says and tell it how far they got.
     */
    fun openVideo(id: PlatformId, url: String, player: Player, log: File?, report: (String) -> Unit, watching: Watching? = null)

    /** Whether [player] says how far into [id] it got, and when it closes (mpv does). */
    fun reportsBack(player: Player, id: PlatformId): Boolean = false

    /** Opens [dir] in a file manager. False where that isn't possible. */
    fun openFolder(dir: File): Boolean

    /** The interface size to use when the user hasn't chosen one. */
    fun automaticUiScale(): Float

    /** Whether this platform has the private vault yet. */
    val hasPrivateVault: Boolean

    /**
     * Opens the private vault for [pin] (with [create], making one if that PIN has none), or
     * returns null when nothing unlocks with it. Throws VaultDamagedException for a vault that
     * can't be read. [settings] are the normal vault's, to start the private vault's in-memory
     * copy from. Each platform keeps its vaults its own way: the desktop seals a whole database,
     * Android encrypts page by page with SQLCipher.
     */
    fun openPrivateVault(dataDir: File, pin: CharArray, create: Boolean, settings: Settings): Storage?

    /**
     * Asks the user for a file with one of [extensions]; [chosen] gets it, perhaps later (a phone's
     * picker answers once the user is back), and isn't called when they cancel.
     */
    fun chooseFile(title: String, extensions: List<String>, chosen: (PickedFile) -> Unit)

    /** How this platform opens plain SQLite files, such as the catalogue. */
    val sqlFiles: SqlFile.Opener

    /** An empty database in memory, for putting a backup together before it's restored. */
    fun scratchDatabase(): app.cash.sqldelight.db.SqlDriver

    /**
     * Asks the user where to save a file called [name] (of [mimeType]); [chosen] gets where, perhaps
     * later, and isn't called when they cancel.
     */
    fun saveFile(title: String, name: String, mimeType: String, chosen: (SaveTarget) -> Unit)

    /** This device's name, for telling backups apart ("laptop", "Pixel 9"). */
    val deviceName: String?
}

/** Where the user chose to save a file: its name, and how to write it. */
class SaveTarget(val name: String, val open: () -> java.io.OutputStream)

/**
 * A video handed to a player that reports back: where it should start (seconds in, or null for
 * the start), and what it's told: how far in it is now and then, and that the player closed.
 */
class Watching(
    val startAt: Long?,
    val position: (seconds: Long, duration: Long) -> Unit,
    val closed: () -> Unit,
)

/** A file the user picked: its name and size, and how to read it (a phone's picker gives streams, not paths). */
class PickedFile(val name: String, val size: Long, val open: () -> InputStream)
