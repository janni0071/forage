package forage.core.profile

import forage.core.PlatformId
import forage.core.library.WatchedVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileFileTest {
    @Test
    fun `reads ids and urls, ignoring comments and blank lines`() {
        val parsed = ProfileFile.parse(
            """
            # woodworking
            youtube:aaaaaaaaaa1  # Hand-cut dovetails — Workshop Wren (2×)

            https://youtu.be/bbbbbbbbbb2
            https://www.youtube.com/watch?v=cccccccccc3#t=10 # with a fragment
            youtube:aaaaaaaaaa1
            just some words
            https://example.com/video
            """.trimIndent()
        )
        assertEquals(listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), parsed.videos.map { it.id })
        assertEquals(listOf(PlatformId.youtube("aaaaaaaaaa1")), parsed.duplicates)
        assertEquals(listOf(7 to "just some words", 8 to "https://example.com/video"), parsed.invalid)
    }

    @Test
    fun `written lines parse back to the same video`() {
        val video = WatchedVideo(
            video = PlatformId.youtube("aaaaaaaaaa1"),
            title = "C# in 100 seconds\n#shorts",
            channel = null,
            channelName = "Some Channel",
            watchCount = 3,
            firstWatched = null,
            lastWatched = null,
            isShort = false,
        )
        val line = ProfileFile.line(video)
        assertEquals("youtube:aaaaaaaaaa1  # C# in 100 seconds #shorts — Some Channel (3×)", line)
        assertEquals(listOf(video.video), ProfileFile.parse(line).videos)
    }
}
