package forage.core.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TakeoutZipTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun resource(name: String) = javaClass.getResourceAsStream("/takeout/$name")!!.readBytes()

    @Test
    fun `reads the YouTube files from a Takeout zip by name, whatever the folders are called`() {
        // Folder names follow the account's language; file names and CSV columns don't.
        val history = resource("watch-history.json")
        val bytes = zip(
            "Takeout/YouTube und YouTube Music/Verlauf/watch-history.json" to history,
            "Takeout/YouTube und YouTube Music/Playlists/Watch later-videos.csv" to "Video ID,Playlist Video Creation Timestamp\naaaaaaaaaa1,2026-10-30T19:12:44+00:00\n".toByteArray(),
            "Takeout/YouTube und YouTube Music/Abos/subscriptions.csv" to "Channel Id,Channel Url,Channel Title\nUCaaaaaaaaaaaaaaaaaaaaaa,http://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa,Workshop Wren\n".toByteArray(),
            "Takeout/archive_browser.html" to "<html></html>".toByteArray(),
        )
        val data = TakeoutFolder.readZip(bytes.inputStream())
        assertEquals(TakeoutWatchHistory.parse(history.inputStream()).events, data.history.events)
        assertEquals(listOf("Watch later"), data.playlists.keys.toList())
        assertEquals("aaaaaaaaaa1", data.playlists.getValue("Watch later").single().video.id)
        assertEquals(1, data.subscriptions.size)
    }

    @Test
    fun `a zip without YouTube data is refused`() {
        assertFailsWith<IllegalArgumentException> { TakeoutFolder.readZip(zip("photos/a.jpg" to ByteArray(3)).inputStream()) }
    }
}
