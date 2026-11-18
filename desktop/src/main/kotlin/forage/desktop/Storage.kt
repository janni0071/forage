package forage.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.db.ForageDatabase
import forage.core.vault.PrivateVaults
import org.sqlite.SQLiteConnection
import java.io.File
import java.util.Properties

/**
 * Where a vault keeps its things. The normal vault uses files in the data folder; the private
 * vault keeps nothing on disk except its encrypted database file.
 */
class Storage(
    val db: ForageDatabase,
    val settings: Settings,
    /** Null keeps thumbnails in memory only. */
    val thumbnailsDir: File?,
    /** Null discards players' output. */
    val playerLog: File?,
    /** Set for the private vault. */
    val encrypted: EncryptedDatabase? = null,
) {
    val isPrivate get() = encrypted != null

    companion object {
        fun plain(dataDir: File) = Storage(
            db = DesktopModel.openDatabase(File(dataDir, "forage.db")),
            settings = Settings(File(dataDir, "settings.properties")),
            thumbnailsDir = File(dataDir, "thumbnails"),
            playerLog = File(dataDir, "logs/player.log"),
        )

        /** The private vault's storage. Settings start as a copy of [settings] and aren't written anywhere. */
        fun private(database: EncryptedDatabase, settings: Settings) = Storage(
            db = database.db,
            settings = Settings(null, copyOf = settings),
            thumbnailsDir = null,
            playerLog = null,
            encrypted = database,
        )
    }
}

/**
 * A private vault's database: decrypted into memory when unlocked, encrypted back to its file
 * whenever it changed. Its contents never reach the disk unencrypted.
 *
 * All use must come from one thread at a time: the in-memory database is a single connection.
 */
class EncryptedDatabase(private val vaults: PrivateVaults, private val vault: PrivateVaults.Unlocked) : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
    private val connection = driver.getConnection().unwrap(SQLiteConnection::class.java)
    val db: ForageDatabase
    private var savedChanges = -1L

    init {
        vault.contents?.let { connection.deserialize("main", it) }
        val migrated = migrate()
        db = ForageDatabase(driver)
        // A new or upgraded vault is written straight away, so it exists in its current form.
        if (vault.contents == null || migrated) save() else savedChanges = changes()
    }

    /** Writes the vault if anything changed since the last save. Returns whether it did. */
    @Synchronized
    fun saveIfChanged(): Boolean {
        val now = changes()
        if (now == savedChanges) return false
        save(now)
        return true
    }

    @Synchronized
    private fun save(now: Long = changes()) {
        vaults.save(vault, connection.serialize("main"))
        savedChanges = now
    }

    /** Saves and closes; the decrypted copy goes with the connection. */
    override fun close() {
        saveIfChanged()
        driver.close()
    }

    /** Rows changed through this connection so far; a cheap way to notice there's something to save. */
    private fun changes(): Long = query("SELECT total_changes()")

    private fun migrate(): Boolean {
        val version = query("PRAGMA user_version")
        val target = ForageDatabase.Schema.version
        if (version == target) return false
        if (version == 0L) ForageDatabase.Schema.create(driver) else ForageDatabase.Schema.migrate(driver, version, target)
        connection.createStatement().use { it.execute("PRAGMA user_version = $target") }
        return true
    }

    private fun query(sql: String): Long =
        connection.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }
}
