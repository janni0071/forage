package forage.core.importer

import forage.core.PlatformId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeoutCsvTest {
    @Test
    fun `reads playlist videos with the time they were added`() {
        val entries = TakeoutCsv.playlist(
            "Video ID,Playlist Video Creation Timestamp\n" +
                "aaaaaaaaaa1,2026-11-15T18:32:56+00:00\n" +
                " bbbbbbbbbb2,not a date\n" +
                "\n"
        )
        assertEquals(
            listOf(
                PlaylistEntry(PlatformId.youtube("aaaaaaaaaa1"), Instant.parse("2026-11-15T18:32:56Z")),
                PlaylistEntry(PlatformId.youtube("bbbbbbbbbb2"), null),
            ),
            entries,
        )
    }

    @Test
    fun `reads subscriptions, including quoted titles`() {
        val subs = TakeoutCsv.subscriptions(
            "Channel Id,Channel Url,Channel Title\r\n" +
                "UCaaaaaaaaaaaaaaaaaaaaaa,http://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa,Workshop Wren \r\n" +
                "UCbbbbbbbbbbbbbbbbbbbbbb,http://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb,\"Brot, Zeit & \"\"Mehr\"\"\"\r\n"
        )
        assertEquals(
            listOf(
                Subscription(PlatformId.youtube("UCaaaaaaaaaaaaaaaaaaaaaa"), "Workshop Wren"),
                Subscription(PlatformId.youtube("UCbbbbbbbbbbbbbbbbbbbbbb"), "Brot, Zeit & \"Mehr\""),
            ),
            subs,
        )
    }
}
