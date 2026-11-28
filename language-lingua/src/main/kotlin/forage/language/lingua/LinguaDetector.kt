package forage.language.lingua

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.Language.*
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import forage.core.language.LanguageDetector

/**
 * Language detection with Lingua, which is built for short texts such as titles.
 *
 * It only considers the languages most videos are titled in. Two cheaper options were tried on a
 * real candidate pool and failed: Lingua's fast mode called plain English titles Tagalog or Latin
 * with high confidence, and OpenNLP's detector was never confident about any title. The accurate
 * mode costs memory (several hundred MB with this list), so Android will need another detector.
 *
 * A text in a language outside the list is reported as the closest listed one, which is still
 * enough to tell that it isn't one of the user's languages.
 */
class LinguaDetector(languages: List<Language> = COMMON_LANGUAGES) : LanguageDetector {
    private val detector = LanguageDetectorBuilder.fromLanguages(*languages.toTypedArray()).build()

    override fun scores(text: String): Map<String, Float> {
        // Titles are often shouted in capitals, which the models read as a different language.
        val values = detector.computeLanguageConfidenceValues(text.lowercase())
        if (values.isEmpty() || values.values.first() <= 0.0) return emptyMap()
        return values.entries.associate { it.key.isoCode639_1.toString().lowercase() to it.value.toFloat() }
    }

    companion object {
        val COMMON_LANGUAGES = listOf(
            ENGLISH, GERMAN, FRENCH, SPANISH, PORTUGUESE, ITALIAN, DUTCH, POLISH, CROATIAN, SWEDISH,
            TURKISH, INDONESIAN, VIETNAMESE,
            RUSSIAN, UKRAINIAN, GREEK, ARABIC, HEBREW, HINDI, THAI, CHINESE, JAPANESE, KOREAN,
        )
    }
}
