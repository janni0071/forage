package forage.app

import forage.core.db.ForageDatabase
import java.io.File

/**
 * Where a vault keeps its things. The normal vault uses files in the data folder; the private
 * vault keeps nothing on disk except its encrypted database file. Each platform creates these.
 */
class Storage(
    val db: ForageDatabase,
    val settings: Settings,
    /** Null keeps thumbnails in memory only. */
    val thumbnailsDir: File?,
    /** Null discards players' output. */
    val playerLog: File?,
    /** Set for the private vault. */
    val encrypted: EncryptedStore? = null,
    private val closeAction: () -> Unit = {},
) {
    val isPrivate get() = encrypted != null

    /** Closes the database. The private vault closes through its model's shutdown instead. */
    fun close() = closeAction()

    companion object {
        /** The private vault's storage. Settings start as a copy of [settings] and aren't written anywhere. */
        fun private(database: EncryptedStore, settings: Settings) = Storage(
            db = database.db,
            settings = Settings(null, copyOf = settings),
            thumbnailsDir = null,
            playerLog = null,
            encrypted = database,
        )
    }
}

/** A private vault's database, kept decrypted while unlocked and written back encrypted. */
interface EncryptedStore : AutoCloseable {
    val db: ForageDatabase

    /** Writes the vault if anything changed since the last save. Returns whether it did. */
    fun saveIfChanged(): Boolean

    /** Saves and closes. */
    override fun close()
}
