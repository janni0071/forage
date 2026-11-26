package forage.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Downloads the real yt-dlp and Deno. Skipped unless FORAGE_NET_TESTS is set. */
class MpvSetupNetTest {
    @Test
    fun `yt-dlp and Deno download, check out and run`() {
        if (System.getenv("FORAGE_NET_TESTS") == null) return
        val dir = Files.createTempDirectory("tools").toFile()
        MpvSetup.toolsDir = dir
        try {
            val seen = mutableListOf<String>()
            MpvSetup.installYtDlp { seen += it }
            MpvSetup.installDeno { seen += it }
            println("PROGRESS ${seen.first()} … ${seen.last()}")
            for (tool in listOf("yt-dlp", "deno")) {
                val p = ProcessBuilder(dir.resolve(tool).path, "--version").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                println("VERSION $tool: ${out.lines().first()}")
                assertTrue(p.waitFor() == 0, out)
            }
            val parts = MpvSetup.parts()
            println("PARTS " + parts.joinToString(" | ") { "${it.name} ${it.ready} ${it.detail}" })
            assertTrue(parts.filter { it.id != "mpv" }.all { it.ready })
        } finally {
            MpvSetup.toolsDir = null
            dir.deleteRecursively()
        }
    }
}
