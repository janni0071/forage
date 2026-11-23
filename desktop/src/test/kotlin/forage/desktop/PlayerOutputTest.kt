package forage.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerOutputTest {
    @Test
    fun `what a player said last is kept, without its status lines, with or without a log`() {
        val script = "echo 'Playing: link'; printf 'AV: 00:00:01 / 00:10:00\\rAV: 00:00:02 / 00:10:00\\r'; echo '[ytdl_hook] ERROR: [PornHub] abc: Unable to extract'; exit 2"
        val withoutLog = PlayerOutput(ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start(), null)
        assertEquals(listOf("Playing: link", "[ytdl_hook] ERROR: [PornHub] abc: Unable to extract"), withoutLog.tail())

        val log = Files.createTempDirectory("log").resolve("logs/player.log").toFile()
        val withLog = PlayerOutput(ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start(), log)
        assertEquals(2, withLog.tail().size)
        assertEquals(listOf("Playing: link", "[ytdl_hook] ERROR: [PornHub] abc: Unable to extract"), log.readLines())
        log.parentFile.parentFile.deleteRecursively()
    }
}
