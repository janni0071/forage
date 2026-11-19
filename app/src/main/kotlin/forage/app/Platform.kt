package forage.app

import androidx.compose.ui.graphics.ImageBitmap
import forage.core.PlatformId
import forage.core.embedding.Embedder
import forage.core.language.LanguageDetector
import forage.core.vault.PrivateVaults
import java.io.File

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
     * The storage of an unlocked private vault, or null where this platform has no private vault
     * yet. [settings] are the normal vault's, to start the private vault's in-memory copy from.
     */
    fun privateStorage(vaults: PrivateVaults, vault: PrivateVaults.Unlocked, settings: Settings): Storage?

    /** Asks the user for a file with [extension]; null when cancelled or not offered. */
    fun chooseFile(title: String, extension: String): File?
}
