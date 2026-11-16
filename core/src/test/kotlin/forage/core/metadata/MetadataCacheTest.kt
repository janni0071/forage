package forage.core.metadata

import forage.core.PlatformId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataCacheTest {
    private var now = Instant.parse("2026-11-16T12:00:00Z")
    private val cache = MetadataCache(memoryDatabase()) { now }
    private val id = PlatformId.youtube("a")

    @Test
    fun `returns what was stored, related videos in order`() {
        assertNull(cache.video(id))
        cache.put(details("a"))
        assertEquals(details("a"), cache.video(id))
        assertEquals(1, cache.size())
    }

    @Test
    fun `storing again replaces metadata and related videos`() {
        cache.put(details("a"))
        cache.put(details("a", related = listOf("rel9")))
        assertEquals(listOf("rel9"), cache.video(id)!!.related.map { it.id.id })
        assertEquals(1, cache.size())
    }

    @Test
    fun `entries older than maxAge count as missing`() {
        cache.put(details("a"))
        now += Duration.ofHours(25)
        assertNull(cache.video(id, maxAge = Duration.ofDays(1)))
        assertNotNull(cache.video(id, maxAge = Duration.ofDays(2)))
        assertNotNull(cache.video(id))
    }

    @Test
    fun `remembers unavailable videos until they are stored`() {
        cache.putUnavailable(id, "private video")
        assertEquals("private video", cache.unavailableReason(id))
        cache.put(details("a"))
        assertNull(cache.unavailableReason(id))
    }
}
