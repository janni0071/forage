package forage.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.app.EncryptedStore
import forage.app.Settings
import forage.app.Storage
import forage.core.db.ForageDatabase
import forage.core.vault.PrivateVaults
import org.sqlite.SQLiteConnection
import java.io.File
import java.util.Properties

/** The desktop's vault storage: a SQLite file through JDBC, and the private vault in memory. */
object DesktopStorage {
    fun plain(dataDir: File): Storage {
        val driver = openDriver(File(dataDir, "forage.db"))
        return Storage(
            db = ForageDatabase(driver),
            settings = Settings(File(dataDir, "settings.properties")),
            thumbnailsDir = File(dataDir, "thumbnails"),
            playerLog = File(dataDir, "logs/player.log"),
            closeAction = {
                // Recent changes sit in a side file (forage.db-wal) until folded in. Doing that on exit
                // leaves everything in forage.db alone, so copying that one file to another computer is enough.
                driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
                driver.close()
            },
        )
    }

    fun openDatabase(file: File): ForageDatabase = ForageDatabase(openDriver(file))

    /** Opens (creating or upgrading) the vault's database file. */
    fun openDriver(file: File): JdbcSqliteDriver {
        file.absoluteFile.parentFile.mkdirs()
        // Background fetches write while the UI reads; WAL lets them do so without blocking each other.
        val properties = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", "10000")
        }
        return JdbcSqliteDriver("jdbc:sqlite:${file.path}", properties, ForageDatabase.Schema)
    }
}

/**
 * A private vault's database: decrypted into memory when unlocked, encrypted back to its file
 * whenever it changed. Its contents never reach the disk unencrypted.
 *
 * All use must come from one thread at a time: the in-memory database is a single connection.
 */
class EncryptedDatabase(private val vaults: PrivateVaults, private val vault: PrivateVaults.Unlocked) : EncryptedStore {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
    private val connection = driver.getConnection().unwrap(SQLiteConnection::class.java)
    override val db: ForageDatabase
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
    override fun saveIfChanged(): Boolean {
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
