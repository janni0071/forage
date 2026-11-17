package forage.language.lingua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinguaDetectorTest {
    private val detector = LinguaDetector()

    @Test
    fun `recognises the language of typical video titles`() {
        val titles = mapOf(
            "Why Switzerland is Considering a Population Cap" to "en",
            "Der mit Abstand schlimmste Fernsehsender Deutschlands" to "de",
            "Une spécialité française à goûter absolument" to "fr",
            "Podvodni dron istraživao je Titanic — ono što je pronašao bilo je zastrašujuće" to "hr",
            "Как собрать кубик Рубика за минуту" to "ru",
        )
        for ((title, language) in titles) assertEquals(language, detector.scores(title).keys.first(), title)
        // Short shouted titles are misread (this one as Spanish), but English stays a close second.
        assertTrue(detector.scores("THE BEST SPEEDCUBES OF ALL TIME").getValue("en") > 0.9f)
        assertEquals(emptyMap(), detector.scores("2026 ⚡ 42"))
    }
}
