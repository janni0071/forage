package forage.desktop

import forage.app.AppModel
import forage.app.FORAGE_VERSION
import forage.app.Models
import forage.app.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private fun release(tag: String, prerelease: Boolean = false) = """
        {"tag_name":"$tag","html_url":"https://github.com/${Updates.REPOSITORY}/releases/tag/$tag","draft":false,"prerelease":$prerelease,
         "assets":[{"name":"forage-${tag.removePrefix("v")}.apk","browser_download_url":"https://example.org/forage.apk"},
                   {"name":"forage-full-${tag.removePrefix("v")}.apk","browser_download_url":"https://example.org/forage-full.apk"},
                   {"name":"forage_${tag.removePrefix("v")}_amd64.deb","browser_download_url":"https://example.org/forage.deb"},
                   {"name":"forage-full_${tag.removePrefix("v")}_amd64.deb","browser_download_url":"https://example.org/forage-full.deb"},
                   {"name":"SHA256SUMS","browser_download_url":"https://example.org/sums"}]}
    """.trimIndent()

    @Test
    fun `versions compare number by number`() {
        assertTrue(Updates.compare("0.21.0", "0.20.9") > 0)
        assertTrue(Updates.compare("0.21.0", "0.21.0") == 0)
        assertTrue(Updates.compare("0.21.10", "0.21.9") > 0)
        assertTrue(Updates.compare("1.0", "0.99.99") > 0)
    }

    @Test
    fun `a newer release is found with the file for this device, an older one or a prerelease isn't`() {
        val standardDeb = { v: String -> "forage_${v}_amd64.deb" }
        val update = Updates.newer(release("v99.0.0"), "0.21.0", standardDeb)!!
        assertEquals("99.0.0", update.version)
        assertEquals("https://example.org/forage.deb", update.download)
        // Each version gets its own file: Standard's and Full's APKs both end in .apk.
        assertEquals("https://example.org/forage.apk", Updates.newer(release("v99.0.0"), "0.21.0") { "forage-$it.apk" }!!.download)
        assertEquals("https://example.org/forage-full.apk", Updates.newer(release("v99.0.0"), "0.21.0") { "forage-full-$it.apk" }!!.download)
        assertEquals("https://example.org/forage-full.deb", Updates.newer(release("v99.0.0"), "0.21.0", forage.app.Edition::releaseFile.let { f -> { v: String -> f(v, false) } })!!.download)
        assertNull(Updates.newer(release("v0.20.0"), "0.21.0", standardDeb))
        assertNull(Updates.newer(release("v0.21.0"), "0.21.0", standardDeb))
        assertNull(Updates.newer(release("v99.0.0", prerelease = true), "0.21.0", standardDeb))
    }

    @Test
    fun `nothing is asked until allowed, and a version put away isn't mentioned again`() {
        val home = Files.createTempDirectory("updates").toFile()
        var asked = 0
        fun open() = AppModel(home, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, DesktopStorage.plain(home), Models(home, DesktopPlatform), DesktopPlatform)
            .also { it.fetchLatestRelease = { asked++; release("v99.0.0") } }
        try {
            var m = open()
            runBlocking { m.reload() }
            // Not asked yet: nothing is looked up.
            assertNull(m.updateCheck)
            m.checkForUpdates()
            assertEquals(0, asked)
            // Yes: it looks at once.
            m.changeUpdateCheck(true)
            assertEquals(1, asked)
            assertEquals("99.0.0", m.availableUpdate?.version)
            // Put away: not on Home again, and not looked up again within a day.
            m.skipUpdate()
            assertNull(m.availableUpdate)
            m.close()
            m = open()
            runBlocking { m.reload() }
            assertEquals(true, m.updateCheck)
            m.checkForUpdates()
            assertEquals(1, asked)
            // Looking now (Settings) says it's out, and doesn't count a day.
            m.checkForUpdates(manual = true)
            assertEquals(2, asked)
            assertEquals("Forage 99.0.0 is out", m.updateStatus)
            m.close()
        } finally {
            home.deleteRecursively()
        }
        assertTrue(FORAGE_VERSION.isNotEmpty())
    }
}
