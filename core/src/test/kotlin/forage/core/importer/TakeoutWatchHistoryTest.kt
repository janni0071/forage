package forage.core.importer

import forage.core.PlatformId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TakeoutWatchHistoryTest {
    private val result = javaClass.getResourceAsStream("/takeout/watch-history.json")!!
        .use(TakeoutWatchHistory::parse)

    @Test
    fun `keeps video entries in file order`() {
        assertEquals(
            listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3", "dddddddddd4", "aaaaaaaaaa1"),
            result.events.map { it.video.id },
        )
    }

    @Test
    fun `reads title, channel and timestamp`() {
        val first = result.events.first()
        assertEquals(PlatformId.youtube("aaaaaaaaaa1"), first.video)
        assertEquals("Hand-cut dovetails for beginners", first.title)
        assertEquals("Workshop Wren", first.channelName)
        assertEquals(PlatformId.youtube("UCaaaaaaaaaaaaaaaaaaaaaa"), first.channel)
        assertEquals(Instant.parse("2026-10-30T19:12:44.123Z"), first.watchedAt)
    }

    @Test
    fun `strips the localized title wrapper`() {
        assertEquals("Sauerteig für Anfänger", result.events[1].title)
    }

    @Test
    fun `entry whose title is only the url has no title and no channel`() {
        val bare = result.events[2]
        assertNull(bare.title)
        assertNull(bare.channel)
        assertNull(bare.channelName)
    }

    @Test
    fun `counts skipped entries by reason`() {
        assertEquals(
            mapOf(SkipReason.AD to 1, SkipReason.NO_URL to 1, SkipReason.NOT_A_VIDEO to 1),
            result.skipped,
        )
    }
}
