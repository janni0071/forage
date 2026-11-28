package forage.core.importer

import forage.core.PlatformId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TakeoutWatchHistoryHtmlTest {
    private val result = javaClass.getResourceAsStream("/takeout/watch-history.html")!!
        .use(TakeoutWatchHistoryHtml::parse)

    @Test
    fun `reads video, title, channel and local timestamp`() {
        val first = result.events.first()
        assertEquals(PlatformId.youtube("aaaaaaaaaa1"), first.video)
        assertEquals("Tom & Anna's hand-cut dovetails", first.title)
        assertEquals("Workshop Wren", first.channelName)
        assertEquals(PlatformId.youtube("UCaaaaaaaaaaaaaaaaaaaaaa"), first.channel)
        assertEquals(Instant.parse("2026-11-15T18:33:29Z"), first.watchedAt)
    }

    @Test
    fun `entry whose title is only the url has no title and no channel`() {
        val bare = result.events.single { it.video.id == "cccccccccc3" }
        assertNull(bare.title)
        assertNull(bare.channelName)
        assertEquals(Instant.parse("2026-07-01T10:05:00Z"), bare.watchedAt)
    }

    @Test
    fun `skips posts, ads and removed videos`() {
        assertEquals(listOf("aaaaaaaaaa1", "cccccccccc3"), result.events.map { it.video.id })
        assertEquals(
            mapOf(SkipReason.AD to 1, SkipReason.NO_URL to 1, SkipReason.NOT_A_VIDEO to 1),
            result.skipped,
        )
    }

    @Test
    fun `unknown date formats give no timestamp`() {
        assertNull(TakeoutWatchHistoryHtml.parseDate("the day before yesterday"))
        assertEquals(Instant.parse("2026-11-15T18:33:29Z"), TakeoutWatchHistoryHtml.parseDate("15.11.2026, 19:33:29 MEZ"))
    }
}
