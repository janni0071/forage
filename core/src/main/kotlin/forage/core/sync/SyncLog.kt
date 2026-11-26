package forage.core.sync

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import forage.core.backup.SqlJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** One row's change: which table and key, when and where it changed, and its values now (null: deleted). */
data class Change(val table: String, val key: JsonArray, val ts: Long, val origin: String, val values: Map<String, JsonElement>?)

/**
 * The library's change log, for sync. SQLite triggers note every row of the synced tables that
 * changes (its table and key, when, and on which device), so nothing that writes the library has
 * to know about sync. A change is sent as the row as it is now (or as gone); a device applies a
 * change only if it's later than what it has for that row ("latest wins", per row), and a removal
 * stays noted, so a row deleted on one device doesn't come back from another.
 *
 * Times are this device's clock in milliseconds, but never earlier than the latest change seen from
 * anywhere, so a change made after receiving one always wins over it, whatever the clocks say.
 */
class SyncLog(private val driver: SqlDriver, private val transacter: Transacter) {
    /** A synced table: its key, the columns that sync, and a column with a time in seconds for the first snapshot. */
    internal class Table(val name: String, val key: List<String>, val columns: List<String>, val stamp: String?)

    /** This device's id, made the first time sync starts. */
    val device: String
        get() = SqlJson.strings(driver, "SELECT device FROM sync_state WHERE device IS NOT NULL").firstOrNull()
            ?: UUID.randomUUID().toString().also { id -> driver.execute(null, "UPDATE sync_state SET device = ?", 1) { bindString(0, id) } }

    /** The last change number given out: changes up to it are in the log. */
    val counter: Long get() = long("SELECT counter FROM sync_state")

    /**
     * Starts noting changes: (re)creates the triggers, and the first time, notes every row there
     * already is, with the time it carries (when it was liked, saved, followed) where it has one.
     */
    fun start() = transacter.transaction {
        device
        for (table in TABLES) {
            for (kind in listOf("insert", "update", "delete", "rename")) driver.execute(null, "DROP TRIGGER IF EXISTS sync_${table.name}_$kind", 0)
            for (statement in triggers(table)) driver.execute(null, statement, 0)
        }
        if (long("SELECT counter FROM sync_state") == 0L) {
            // Each row a number of its own, so a first sync can go in batches.
            for (table in TABLES) {
                val stamp = table.stamp?.let { "COALESCE(t.\"$it\", 0) * 1000" } ?: "0"
                driver.execute(
                    null,
                    "INSERT OR IGNORE INTO sync_row (tbl, row_key, ts, origin, seq) SELECT '${table.name}', ${keyOf(table, "t.")}, $stamp, NULL, " +
                        "(SELECT counter FROM sync_state) + ROW_NUMBER() OVER () FROM \"${table.name}\" t" +
                        (if (table.name == "profile_video") " WHERE ${profileName("t.")} IS NOT NULL" else "") +
                        (if (table.name == "preference") " WHERE t.key NOT LIKE 'sync.%'" else ""),
                    0,
                )
                driver.execute(null, "UPDATE sync_state SET counter = MAX(1, (SELECT COALESCE(MAX(seq), 0) FROM sync_row))", 0)
            }
            driver.execute(null, "UPDATE sync_state SET clock = MAX(clock, (SELECT COALESCE(MAX(ts), 0) FROM sync_row))", 0)
        }
        spread()
        repairUnnoted()
    }

    /**
     * Notes again what earlier versions failed to: a row added back here after another device's
     * removal of it was noted (an INSERT OR IGNORE kept the removal's note, see [triggers]). Such
     * a row is here, while its note is another device's and older than the row's own time (when it
     * was saved, liked, added).
     */
    private fun repairUnnoted() {
        for (table in TABLES) {
            val stamp = table.stamp ?: continue
            driver.execute(
                null,
                "UPDATE sync_row SET origin = NULL, ts = (SELECT clock FROM sync_state) + 1, seq = (SELECT counter FROM sync_state) + 1 " +
                    "WHERE tbl = '${table.name}' AND origin IS NOT NULL AND EXISTS (SELECT 1 FROM \"${table.name}\" t " +
                    "WHERE ${keyOf(table, "t.")} = sync_row.row_key AND t.\"$stamp\" * 1000 > sync_row.ts + 1000)",
                0,
            )
        }
        driver.execute(
            null,
            "UPDATE sync_state SET counter = MAX(counter, (SELECT COALESCE(MAX(seq), 0) FROM sync_row)), clock = MAX(clock, (SELECT COALESCE(MAX(ts), 0) FROM sync_row))",
            0,
        )
    }

    /**
     * Gives rows that share one number (the first snapshot and applied batches, as earlier versions
     * noted them) numbers of their own, so they too go in batches: each number n becomes n * k plus
     * the row's place among those sharing it, which keeps the order and never lowers a number (a
     * device that had changes up to n is sent all of n * k and later, a few again at most). How far
     * the paired devices have this device's changes moves along the same way.
     */
    private fun spread() {
        val biggest = long("SELECT COALESCE(MAX(n), 0) FROM (SELECT COUNT(*) AS n FROM sync_row GROUP BY seq)")
        if (biggest <= BATCH) return
        val k = biggest
        driver.execute(null, "DROP TABLE IF EXISTS temp.sync_spread", 0)
        driver.execute(
            null,
            "CREATE TEMP TABLE sync_spread (r INTEGER PRIMARY KEY, s INTEGER NOT NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO temp.sync_spread (r, s) SELECT rowid, seq * $k + (ROW_NUMBER() OVER (PARTITION BY seq ORDER BY rowid) - 1) FROM sync_row",
            0,
        )
        driver.execute(null, "UPDATE sync_row SET seq = (SELECT s FROM temp.sync_spread WHERE r = sync_row.rowid)", 0)
        driver.execute(null, "DROP TABLE temp.sync_spread", 0)
        driver.execute(null, "UPDATE sync_peer SET acked = acked * $k + ($k - 1) WHERE acked > 0", 0)
        driver.execute(null, "UPDATE sync_state SET counter = counter * $k + ($k - 1)", 0)
    }

    /** Stops noting changes (no devices left to sync with); what's noted stays. */
    fun stop() = transacter.transaction {
        for (table in TABLES) for (kind in listOf("insert", "update", "delete", "rename")) driver.execute(null, "DROP TRIGGER IF EXISTS sync_${table.name}_$kind", 0)
    }

    /** Whether anything was noted after [since] that didn't come from [peer]: a cheap look, for waiting on changes. */
    fun hasChangesSince(since: Long, peer: String?): Boolean = driver.executeQuery(
        null, "SELECT EXISTS (SELECT 1 FROM sync_row WHERE seq > ? AND (origin IS NULL OR origin IS NOT ?))",
        { c -> c.next(); QueryResult.Value(c.getLong(0) == 1L) }, 2,
    ) { bindLong(0, since); bindString(1, peer) }.value

    /** Changes noted after [since], except those that came from [peer] (it has them), oldest first. */
    fun changesSince(since: Long, peer: String?): List<Change> = batchSince(since, peer, Int.MAX_VALUE).changes

    /**
     * A batch of changes for a sync: at most about [limit] of those noted after [since] (except
     * those from [peer]), oldest first, all those sharing a number together; [Batch.upTo] is how
     * far it goes, and [Batch.more] says whether there are later ones.
     */
    fun batchSince(since: Long, peer: String?, limit: Int = BATCH): Batch {
        val me = device
        val counter = counter
        fun rows(sql: String, vararg bind: Any?) = driver.executeQuery(null, sql, { c ->
            val list = ArrayList<Row>()
            while (c.next().value) list += Row(c.getString(0)!!, c.getString(1)!!, c.getLong(2)!!, c.getString(3), c.getLong(4)!!)
            QueryResult.Value(list)
        }, bind.size) {
            bind.forEachIndexed { i, v -> if (v is Long) bindLong(i, v) else bindString(i, v as String?) }
        }.value
        val first = rows(
            "SELECT tbl, row_key, ts, origin, seq FROM sync_row WHERE seq > ? AND seq <= ? AND (origin IS NULL OR origin IS NOT ?) ORDER BY seq LIMIT ?",
            since, counter, peer, limit.toLong() + 1,
        )
        val more = first.size > limit
        var picked = if (more) first.take(limit) else first
        val upTo = if (more) picked.last().seq else counter
        // Never half of what shares a number: the rest of the last one too.
        if (more) {
            val have = picked.filter { it.seq == upTo }.map { it.table to it.key }.toSet()
            picked = picked + rows(
                "SELECT tbl, row_key, ts, origin, seq FROM sync_row WHERE seq = ? AND (origin IS NULL OR origin IS NOT ?)", upTo, peer,
            ).filter { (it.table to it.key) !in have }
        }
        val tables = TABLES.associateBy { it.name }
        val changes = picked.mapNotNull { row ->
            val table = tables[row.table] ?: return@mapNotNull null
            val key = Json.parseToJsonElement(row.key).jsonArray
            Change(table.name, key, row.ts, row.origin ?: me, read(table, key))
        }
        return Batch(changes, upTo, more)
    }

    /** A batch of changes: what's in it, how far it goes, and whether there are more. */
    data class Batch(val changes: List<Change>, val upTo: Long, val more: Boolean)

    private data class Row(val table: String, val key: String, val ts: Long, val origin: String?, val seq: Long)

    /**
     * Applies [changes] from another device, each only if it's later than what this device has
     * for that row. Returns how many changed something here.
     */
    fun apply(changes: List<Change>): Int {
        if (changes.isEmpty()) return 0
        val me = device
        val tables = TABLES.associateBy { it.name }
        var applied = 0
        transacter.transaction {
            driver.execute(null, "UPDATE sync_state SET applying = 1", 0)
            // Each applied row a number of its own (so passing them on can go in batches too).
            var seq = long("SELECT counter FROM sync_state")
            for (change in changes) {
                val table = tables[change.table] ?: continue
                val keyText = change.key.toString()
                val local = driver.executeQuery(null, "SELECT ts, origin FROM sync_row WHERE tbl = ? AND row_key = ?", { c ->
                    QueryResult.Value(if (c.next().value) c.getLong(0)!! to (c.getString(1) ?: me) else null)
                }, 2) { bindString(0, table.name); bindString(1, keyText) }.value
                if (local != null && (local.first > change.ts || (local.first == change.ts && local.second >= change.origin))) continue
                if (change.values == null) delete(table, change.key) else write(table, change.key, change.values)
                seq++
                driver.execute(null, "INSERT OR REPLACE INTO sync_row (tbl, row_key, ts, origin, seq) VALUES (?, ?, ?, ?, ?)", 5) {
                    bindString(0, table.name); bindString(1, keyText); bindLong(2, change.ts)
                    bindString(3, change.origin.takeIf { it != me }); bindLong(4, seq)
                }
                applied++
            }
            driver.execute(null, "UPDATE sync_state SET applying = 0, counter = ?, clock = MAX(clock, ?)", 2) {
                bindLong(0, seq); bindLong(1, changes.maxOf { it.ts })
            }
        }
        return applied
    }

    // Reading and writing rows by key.

    private fun read(table: Table, key: JsonArray): Map<String, JsonElement>? {
        val columns = table.columns.filter { it != "profile" }
        val select = SqlJson.select(columns, "t.")
        val (where, args) = match(table, key)
        val row = driver.executeQuery(null, "SELECT $select FROM \"${table.name}\" t WHERE $where", { c ->
            QueryResult.Value(if (c.next().value) columns.indices.associate { columns[it] to SqlJson.value(c, it * 2) } else null)
        }, args.size) { args.forEachIndexed { i, a -> SqlJson.bind(this, i, a) } }.value ?: return null
        return if (table.name == "profile_video") row + ("profile" to key[0]) else row
    }

    private fun delete(table: Table, key: JsonArray) {
        if (table.name == "profile") {
            // A profile goes with what's in it.
            val name = key[0].jsonPrimitive.content
            for (dependent in listOf("profile_video", "pick", "topic_preference")) {
                driver.execute(null, "DELETE FROM $dependent WHERE profile_id = (SELECT id FROM profile WHERE name = ?)", 1) { bindString(0, name) }
            }
        }
        val (where, args) = match(table, key, alias = "")
        driver.execute(null, "DELETE FROM \"${table.name}\" WHERE $where", args.size) { args.forEachIndexed { i, a -> SqlJson.bind(this, i, a) } }
    }

    private fun write(table: Table, key: JsonArray, values: Map<String, JsonElement>) {
        var row = values
        if (table.name == "profile_video") {
            // Profiles are known by name across devices; here, by this database's id.
            val name = key[0].jsonPrimitive.content
            driver.execute(null, "INSERT OR IGNORE INTO profile (name, created_at) VALUES (?, strftime('%s', 'now'))", 1) { bindString(0, name) }
            row = values - "profile" + ("profile_id" to JsonPrimitive(long("SELECT id FROM profile WHERE name = ?", name)))
        }
        val present = SqlJson.columns(driver, table.name).toSet()
        val columns = row.keys.filter { it in present }
        val keyColumns = table.key.map { if (it == "profile") "profile_id" else it }
        val updates = columns.filter { it !in keyColumns }
        val sql = "INSERT INTO \"${table.name}\" (${columns.joinToString { "\"$it\"" }}) VALUES (${columns.joinToString { "?" }}) " +
            "ON CONFLICT (${keyColumns.joinToString { "\"$it\"" }}) DO " +
            (if (updates.isEmpty()) "NOTHING" else "UPDATE SET ${updates.joinToString { "\"$it\" = excluded.\"$it\"" }}")
        driver.execute(null, sql, columns.size) { columns.forEachIndexed { i, c -> SqlJson.bind(this, i, row[c]) } }
    }

    /** The WHERE clause matching [key] in [table], and its arguments. */
    private fun match(table: Table, key: JsonArray, alias: String = "t."): Pair<String, List<JsonElement>> {
        val parts = table.key.mapIndexed { i, column ->
            if (column == "profile") "${alias}profile_id = (SELECT id FROM profile WHERE name = ?)" else "$alias\"$column\" IS ?"
        }
        return parts.joinToString(" AND ") to table.key.indices.map { key.getOrElse(it) { JsonNull } }
    }

    private fun long(sql: String, vararg args: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, args.size) {
            args.forEachIndexed { i, a -> bindString(i, a) }
        }.value

    // The triggers.

    private fun profileName(prefix: String) = "(SELECT name FROM profile WHERE id = ${prefix}profile_id)"

    /** The key as a JSON array, from a row reached by [prefix] (`NEW.`, `OLD.`, `t.`). */
    private fun keyOf(table: Table, prefix: String): String =
        "json_array(" + table.key.joinToString { if (it == "profile") profileName(prefix) else "$prefix\"$it\"" } + ")"

    private fun triggers(table: Table): List<String> {
        val t = table.name
        val now = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
        // The old note goes first, then the new one is written: not INSERT OR REPLACE, since SQLite
        // runs a trigger's statements under the conflict rule of the statement that set it off, and
        // under an INSERT OR IGNORE (adding to the queue) an existing note (a removal from another
        // device) would stay, and the change go unnoted.
        fun note(key: String, where: String = "") =
            "DELETE FROM sync_row WHERE tbl = '$t' AND row_key = $key${if (where.isEmpty()) "" else " AND $where"}; " +
                "INSERT INTO sync_row (tbl, row_key, ts, origin, seq) SELECT '$t', $key, clock, NULL, counter FROM sync_state${if (where.isEmpty()) "" else " WHERE $where"};"
        val tick = "UPDATE sync_state SET counter = counter + 1, clock = MAX(clock + 1, $now);"
        // This device's settings in the preference table stay its own.
        fun own(row: String) = if (t == "preference") " AND $row.key NOT LIKE 'sync.%'" else ""
        // A like whose profile is gone went with the profile, which is noted itself.
        fun known(row: String) = if (t == "profile_video") " AND ${profileName("$row.")} IS NOT NULL" else ""
        val active = "(SELECT applying FROM sync_state) = 0"
        val changed = table.columns.filter { it != "profile" }.joinToString(" OR ") { "OLD.\"$it\" IS NOT NEW.\"$it\"" }
        val list = mutableListOf(
            "CREATE TRIGGER sync_${t}_insert AFTER INSERT ON \"$t\" WHEN $active${own("NEW")}${known("NEW")} BEGIN $tick ${note(keyOf(table, "NEW."))} END",
            "CREATE TRIGGER sync_${t}_update AFTER UPDATE ON \"$t\" WHEN $active${own("NEW")}${known("NEW")} AND ($changed) BEGIN $tick " +
                note(keyOf(table, "NEW.")) +
                // A changed key (a renamed playlist): the old one is gone.
                " " + note(keyOf(table, "OLD."), "${keyOf(table, "OLD.")} IS NOT ${keyOf(table, "NEW.")}") + " END",
            "CREATE TRIGGER sync_${t}_delete AFTER DELETE ON \"$t\" WHEN $active${own("OLD")}${known("OLD")} BEGIN $tick ${note(keyOf(table, "OLD."))} END",
        )
        if (t == "profile") {
            // A renamed profile: its likes are known by its name elsewhere, so they move too.
            list += "CREATE TRIGGER sync_profile_rename AFTER UPDATE OF name ON profile WHEN $active AND OLD.name IS NOT NEW.name BEGIN " +
                listOf("NEW", "OLD").joinToString(" ") { which ->
                    "DELETE FROM sync_row WHERE tbl = 'profile_video' AND row_key IN (SELECT json_array($which.name, v.video_id) FROM profile_video v WHERE v.profile_id = NEW.id); " +
                        "INSERT INTO sync_row (tbl, row_key, ts, origin, seq) SELECT 'profile_video', json_array($which.name, v.video_id), s.clock, NULL, s.counter " +
                        "FROM profile_video v, sync_state s WHERE v.profile_id = NEW.id;"
                } + " END"
        }
        return list
    }

    companion object {
        /** How many changes go in one exchange, at most (about): a big library's first sync goes in many. */
        const val BATCH = 1000

        /**
         * What syncs: the library, likes and profiles, and the vault's preferences. Not what each
         * device fetches or works out for itself (video details, pictures, feeds, picks, topics)
         * or keeps for itself (sync's own tables, a subscription's last check).
         */
        internal val TABLES = listOf(
            Table("library_video", listOf("id"), listOf("id", "title", "channel_id", "channel_name", "is_short", "added_at", "duration_seconds", "uploaded_at"), "added_at"),
            Table("watch_event", listOf("video_id", "watched_at"), listOf("video_id", "watched_at"), "watched_at"),
            Table("playlist", listOf("name"), listOf("name", "created_at"), "created_at"),
            Table("playlist_item", listOf("playlist", "video_id"), listOf("playlist", "video_id", "added_at", "position"), "added_at"),
            Table("subscription", listOf("channel_id"), listOf("channel_id", "name", "added_at", "seen_at"), "added_at"),
            Table("watch_later", listOf("video_id"), listOf("video_id", "added_at"), "added_at"),
            Table("playback_position", listOf("video_id"), listOf("video_id", "position_seconds", "duration_seconds", "updated_at"), "updated_at"),
            Table("profile", listOf("name"), listOf("name", "created_at"), "created_at"),
            Table("profile_video", listOf("profile", "video_id"), listOf("profile", "video_id", "state", "weight", "source", "changed_at"), "changed_at"),
            Table("preference", listOf("key"), listOf("key", "value"), null),
        )
    }
}
