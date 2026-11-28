package forage.core.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import java.time.Instant

/**
 * A device this one is paired with: the key they share, where it was last reached (for a phone,
 * the computer's address), and how far each side has got: [received] is the last of its changes
 * this device has, [acked] the last of this device's changes it has.
 */
data class Peer(
    val id: String,
    val name: String,
    val key: ByteArray,
    val address: String? = null,
    val port: Int? = null,
    val received: Long = 0,
    val acked: Long = 0,
    val pairedAt: Instant = Instant.now(),
    val syncedAt: Instant? = null,
    /** The computer it's on, as it's found on the network (for a private vault, which has an id of its own). */
    val host: String? = null,
    /** What it is: "phone", "tablet" or "computer" (null from a Forage too old to say). */
    val kind: String? = null,
)

/** The paired devices, kept in the library's database (but never in its backups). */
class SyncPeers(private val driver: SqlDriver) {
    fun all(): List<Peer> = query("SELECT $COLUMNS FROM sync_peer ORDER BY paired_at")

    fun get(id: String): Peer? = query("SELECT $COLUMNS FROM sync_peer WHERE id = ?", id).firstOrNull()

    fun save(peer: Peer) {
        driver.execute(
            null,
            "INSERT OR REPLACE INTO sync_peer (id, name, key, address, port, received, acked, paired_at, synced_at, host, kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            11,
        ) {
            bindString(0, peer.id); bindString(1, peer.name); bindBytes(2, peer.key); bindString(3, peer.address)
            bindLong(4, peer.port?.toLong()); bindLong(5, peer.received); bindLong(6, peer.acked)
            bindLong(7, peer.pairedAt.epochSecond); bindLong(8, peer.syncedAt?.epochSecond); bindString(9, peer.host); bindString(10, peer.kind)
        }
    }

    fun remove(id: String) {
        driver.execute(null, "DELETE FROM sync_peer WHERE id = ?", 1) { bindString(0, id) }
    }

    private fun query(sql: String, vararg args: String): List<Peer> = driver.executeQuery(null, sql, { c ->
        val list = ArrayList<Peer>()
        while (c.next().value) list += peer(c)
        QueryResult.Value(list)
    }, args.size) { args.forEachIndexed { i, a -> bindString(i, a) } }.value

    private fun peer(c: SqlCursor) = Peer(
        id = c.getString(0)!!, name = c.getString(1)!!, key = c.getBytes(2)!!, address = c.getString(3),
        port = c.getLong(4)?.toInt(), received = c.getLong(5) ?: 0, acked = c.getLong(6) ?: 0,
        pairedAt = Instant.ofEpochSecond(c.getLong(7) ?: 0), syncedAt = c.getLong(8)?.let(Instant::ofEpochSecond),
        host = c.getString(9), kind = c.getString(10),
    )

    private companion object {
        const val COLUMNS = "id, name, key, address, port, received, acked, paired_at, synced_at, host, kind"
    }
}
