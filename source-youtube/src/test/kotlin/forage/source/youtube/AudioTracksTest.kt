package forage.source.youtube

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioTracksTest {
    private fun candidate(url: String, track: String?, locale: Locale?, original: Boolean = false, m4a: Boolean = true, bitrate: Int = 128) =
        YouTubeSource.AudioCandidate(url, track, null, locale, original, secondary = false, m4a = m4a, bitrate = bitrate)

    @Test
    fun `the original track comes first, one stream per track`() {
        val tracks = YouTubeSource().audioTracks(
            listOf(
                candidate("es-low", "es.4", Locale.forLanguageTag("es"), bitrate = 48),
                candidate("es", "es.4", Locale.forLanguageTag("es")),
                candidate("en-webm", "en.4", Locale.ENGLISH, original = true, m4a = false, bitrate = 160),
                candidate("en", "en.4", Locale.ENGLISH, original = true),
            ),
        )
        assertEquals(listOf("en", "es"), tracks.map { it.url })
        assertEquals(listOf("English (original)", "Spanish"), tracks.map { it.label })
    }

    @Test
    fun `a video with one track has no names to show`() {
        val tracks = YouTubeSource().audioTracks(listOf(candidate("a", null, null), candidate("b", null, null, bitrate = 160)))
        assertEquals(listOf("b"), tracks.map { it.url })
    }
}
