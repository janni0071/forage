package forage.core.library

import forage.core.PlatformId
import forage.core.importer.WatchEvent
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchHistoryTest {
    private fun event(id: String, day: Int, channel: String = "Workshop Wren", title: String? = "Video $id") = WatchEvent(
        video = PlatformId.youtube(id),
        title = title,
        channel = PlatformId.youtube("UC$channel"),
        channelName = channel,
        watchedAt = Instant.parse("2026-10-%02dT12:00:00Z".format(day)),
    )

    private val events = listOf(
        event("a", 20),
        event("b", 15, channel = "Brotzeit"),
        event("a", 10, title = null),
        event("c", 5),
        event("a", 1),
    )

    private fun ids(filter: HistoryFilter) = WatchHistory.select(events, filter).map { it.video.id }

    @Test
    fun `folds repeat watches and orders by count then recency`() {
        val videos = WatchHistory.select(events)
        assertEquals(listOf("a", "b", "c"), videos.map { it.video.id })
        val a = videos.first()
        assertEquals(3, a.watchCount)
        assertEquals("Video a", a.title)
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), a.firstWatched)
        assertEquals(Instant.parse("2026-10-20T12:00:00Z"), a.lastWatched)
    }

    @Test
    fun `filters by channel name or id`() {
        assertEquals(listOf("b"), ids(HistoryFilter(channels = listOf("brot"))))
        assertEquals(listOf("b"), ids(HistoryFilter(channels = listOf("youtube:UCBrotzeit"))))
        assertEquals(listOf("a", "b", "c"), ids(HistoryFilter(channels = listOf("brot", "wren"))))
    }

    @Test
    fun `filters by title`() {
        assertEquals(listOf("c"), ids(HistoryFilter(title = "video C")))
    }

    @Test
    fun `counts watches inside the date range only`() {
        val october = HistoryFilter(since = Instant.parse("2026-10-05T00:00:00Z"), until = Instant.parse("2026-10-16T00:00:00Z"))
        assertEquals(listOf("b", "a", "c"), ids(october))
        assertEquals(listOf("a"), ids(HistoryFilter(minWatches = 2)))
        assertEquals(emptyList(), ids(october.copy(minWatches = 2)))
    }
}
