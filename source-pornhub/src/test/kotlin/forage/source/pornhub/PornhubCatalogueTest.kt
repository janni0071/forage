package forage.source.pornhub

import forage.core.PlatformId
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A dump laid out like the real one, with placeholder content. */
class PornhubCatalogueTest {
    private val dir = Files.createTempDirectory("catalogue").toFile()
    private val target = File(dir, "catalogues/pornhub.db")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun line(
        key: String, title: String = "Video $key", tags: String = "tag a;tag b", categories: String = "Cat X",
        performers: String = "", duration: String = "630", views: String = "5000", extra: String = "",
    ) = """<iframe src="https://www.pornhub.com/embed/$key" frameborder="0" width="560" height="315" scrolling="no" allowfullscreen></iframe>""" +
        "|https://img.example/videos/202403/15/$key/original/(m=a)(mh=b)1.jpg|https://img.example/$key-1.jpg;https://img.example/$key-2.jpg|$title|$tags|$categories|$performers" +
        "|$duration|$views|90|10|https://img.example/$key-big.jpg;https://img.example/$key-big2.jpg$extra"

    private fun zip(lines: List<String>): File {
        val file = File(dir, "pornhub.com-db.zip")
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("pornhub.com-db.csv"))
            z.write(lines.joinToString("\n").toByteArray())
            z.closeEntry()
        }
        return file
    }

    /** Twelve ordinary videos so tags are common enough to keep, plus the ones a test cares about. */
    private fun filler() = (1..12).map { line("fill$it", tags = "tag a;tag b;common", categories = "Cat X") }

    @Test
    fun `imports the dump, skipping unreadable lines and videos hardly anyone watched`() {
        val lines = filler() + listOf(
            line("special1", title = "A | B", tags = "Rare-One;tag a", performers = "Perf One;Perf Two;Perf Three"),
            line("special2", tags = "rare one;tag b"),
            line("special3", tags = "rare one"),
            line("few", views = "12"),
            "not a dump line at all",
            line("broken", duration = "soon"),
        )
        val summary = PornhubCatalogue.import(zip(lines), target, now = { 1_700_000_000 })
        assertEquals(15, summary.videos)
        assertEquals(2, summary.skippedUnreadable)
        assertEquals(1, summary.skippedFewViews)
        assertTrue(target.isFile)
        assertFalse(File(target.parentFile, "pornhub.db.importing").exists())

        PornhubCatalogue.open(target).use { c ->
            assertEquals(15, c.size)
            assertEquals(1_700_000_000, c.importedAt)
            assertEquals("https://img.example/videos/202403/15/special1/original/(m=a)(mh=b)1.jpg", c.thumbnailUrl(PlatformId("pornhub", "special1")))
            assertNull(c.thumbnailUrl(PlatformId("pornhub", "few")))

            // "rare one" (any spelling) is on three videos, "tag a" on most: the rare tag decides.
            val matches = c.matching(mapOf("rare one" to 1.0, "tag a" to 1.0), limit = 3)
            assertEquals(setOf("special1", "special2", "special3"), matches.map { it.stub.id.id }.toSet())
            val first = matches.single { it.stub.id.id == "special1" }
            assertEquals("A | B", first.stub.title)
            assertEquals("Perf One, Perf Two", first.stub.channelName)
            assertEquals(630L, first.stub.durationSeconds)
            assertEquals(java.time.Instant.parse("2024-03-15T00:00:00Z"), first.stub.uploadedAt)
            assertEquals(listOf("rare one", "tag a"), first.sharedTags)
            assertTrue("rare one" in first.stub.tags.map(PornhubCatalogue::normalize))

            val without = c.matching(mapOf("rare one" to 1.0), limit = 5, exclude = setOf(PlatformId("pornhub", "special2")))
            assertEquals(setOf("special1", "special3"), without.map { it.stub.id.id }.toSet())
            assertEquals(emptyList(), c.matching(mapOf("unheard of" to 1.0), limit = 5))
        }
    }

    @Test
    fun `a file laid out differently stops early with a description instead of its content`() {
        val e = assertFailsWith<PornhubCatalogue.LayoutException> {
            PornhubCatalogue.import(zip((1..2500).map { "Secret title $it;tag;other;1234" }), target)
        }
        assertTrue("Secret" !in e.layout && "1 fields" in e.layout, e.layout)
        assertFalse(target.exists())
    }

    @Test
    fun `a stopped import leaves the earlier catalogue in place`() {
        PornhubCatalogue.import(zip(filler()), target)
        val before = target.length()
        assertFailsWith<InterruptedException> {
            PornhubCatalogue.import(zip((1..50_000).map { line("v$it") }), target, cancelled = { true })
        }
        assertEquals(before, target.length())
        assertFalse(File(target.parentFile, "pornhub.db.importing").exists())
    }

    @Test
    fun `counts are found by content when lines carry more fields than expected`() {
        val lines = filler() + listOf(
            line("more1", title = "Title | with a bar", tags = "special;tag a", performers = "Perf One", duration = "603", views = "299759", extra = "|2024-01-02|1"),
            line("more2", tags = "special", performers = "Perf Two", duration = "1200", views = "4812136", extra = "|x"),
            line("more3", tags = "special"),
        )
        val summary = PornhubCatalogue.import(zip(lines), target)
        assertEquals(15, summary.videos)
        assertEquals("12 fields, counts in fields 8–11 (most lines)", summary.layout)
        PornhubCatalogue.open(target).use { c ->
            val m = c.matching(mapOf("special" to 1.0), limit = 20).associateBy { it.stub.id.id }
            val one = m.getValue("more1").stub
            assertEquals("Title | with a bar", one.title)
            assertEquals("Perf One", one.channelName)
            assertEquals(603L, one.durationSeconds)
            assertEquals(setOf("special", "tag a", "cat x"), one.tags.toSet())
            assertEquals("Perf Two", m.getValue("more2").stub.channelName)
            assertEquals(1200L, m.getValue("more2").stub.durationSeconds)
        }
    }
}
