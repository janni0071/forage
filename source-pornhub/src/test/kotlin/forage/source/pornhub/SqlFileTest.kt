package forage.source.pornhub

import forage.core.sql.JdbcSqlFile
import forage.core.sql.SqlFile
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SqlFileTest {
    @Test
    fun `large blobs are read whole, in pieces`() {
        val file = Files.createTempFile("blob", ".db").toFile()
        try {
            val big = Random(1).nextBytes(3 * 1024 * 1024 + 123)
            JdbcSqlFile.open(file, writable = true).use { db ->
                db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, data BLOB)")
                db.prepare("INSERT INTO t (id, data) VALUES (?, ?)").use { it.run(1, big); it.run(2, null) }
                assertContentEquals(big, db.blob("t", "data", "id = ?", 1))
                assertNull(db.blob("t", "data", "id = ?", 2))
                assertNull(db.blob("t", "data", "id = ?", 3))
            }
        } finally {
            file.delete()
        }
    }
}
