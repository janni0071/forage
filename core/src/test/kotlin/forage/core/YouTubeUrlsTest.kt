package forage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeUrlsTest {
    @Test
    fun `recognises the common video url shapes`() {
        val id = PlatformId.youtube("dQw4w9WgXcQ")
        for (url in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
        )) {
            assertEquals(YouTubeUrls.VideoRef(id, isShort = false), YouTubeUrls.video(url), url)
        }
    }

    @Test
    fun `flags shorts by url`() {
        assertEquals(
            YouTubeUrls.VideoRef(PlatformId.youtube("dQw4w9WgXcQ"), isShort = true),
            YouTubeUrls.video("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `rejects non-video and foreign urls`() {
        assertNull(YouTubeUrls.video("https://www.youtube.com/post/Ugkx123"))
        assertNull(YouTubeUrls.video("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeUrls.video("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrls.video("not a url"))
    }

    @Test
    fun `platform ids round-trip through their string form`() {
        val id = PlatformId.youtube("dQw4w9WgXcQ")
        assertEquals("youtube:dQw4w9WgXcQ", id.toString())
        assertEquals(id, PlatformId.parse("youtube:dQw4w9WgXcQ"))
    }
}
