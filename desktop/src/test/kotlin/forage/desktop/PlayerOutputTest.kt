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

class ExplainPlayerFailureTest {
    @Test
    fun `known failures get a plain explanation`() {
        val refused = listOf("[ytdl_hook] ERROR: [PornHub] 642e3afbbe228: Unable to download webpage: HTTP Error 410: Gone (caused by <HTTPError 410: Gone>)")
        kotlin.test.assertTrue(explainPlayerFailure(refused)!!.contains("pipx inject yt-dlp curl_cffi"))
        val premium = listOf("[ytdl_hook] ERROR: [PornHub] abc: This video is only available for Premium members")
        kotlin.test.assertTrue(explainPlayerFailure(premium)!!.startsWith("This video is for Pornhub Premium"))
        kotlin.test.assertEquals(null, explainPlayerFailure(listOf("Failed to recognize file format.")))
    }
}
