package forage.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageFilterTest {
    /** Reads the language from a "xx:" or "xx?:" prefix; "?" marks a guess with English nearly as likely. */
    private val detector = object : LanguageDetector {
        override fun scores(text: String): Map<String, Float> {
            val tag = text.substringBefore(':', "").ifEmpty { return emptyMap() }
            val language = tag.trimEnd('?')
            return if (tag.endsWith("?")) mapOf(language to 1f, "en" to 0.9f) else mapOf(language to 1f, "en" to 0.1f)
        }
    }
    private val profile = List(30) { "en: video $it" } + List(8) { "de: Video $it" } + "fr: une seule vidéo" + "no signal"
    private val filter = LanguageFilter(detector, profile)

    @Test
    fun `profile languages need a real share of the profile`() {
        assertEquals(mapOf("en" to 30, "de" to 8, "fr" to 1), filter.profileLanguages)
        assertNull(filter.foreignLanguage("de: ein Video auf Deutsch"))
        assertEquals("fr", filter.foreignLanguage("fr: une vidéo en français"))
    }

    @Test
    fun `drops only clear detections of long enough titles`() {
        assertEquals("hr", filter.foreignLanguage("hr: podvodni dron istraživao je Titanic"))
        assertNull(filter.foreignLanguage("hr?: podvodni dron istraživao je Titanic"))
        assertNull(filter.foreignLanguage("hr: Sparxie Trailer"))
        // No spaces, but plenty of text to judge.
        assertEquals("zh", filter.foreignLanguage("zh:習近平秒變媽寶男找百歲老娘防政變"))
        assertNull(filter.foreignLanguage("zh:東京"))
        assertNull(filter.foreignLanguage("names and numbers only 2026"))
    }

    @Test
    fun `keeps everything when the profile's language is unknown`() {
        assertNull(LanguageFilter(detector, listOf("no signal")).foreignLanguage("hr: podvodni dron istraživao je Titanic"))
    }
}
