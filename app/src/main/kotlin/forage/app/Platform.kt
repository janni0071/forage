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
     * [report] gets problems noticed later, such as a player quitting with an error.
     */
    fun openVideo(id: PlatformId, url: String, player: Player, log: File?, report: (String) -> Unit)

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
}

/** A file the user picked: its name and size, and how to read it (a phone's picker gives streams, not paths). */
class PickedFile(val name: String, val size: Long, val open: () -> InputStream)
