package forage.core.language

/** Guesses the language of a text. Implementations wrap a detection library. */
interface LanguageDetector {
    /**
     * How well [text] fits each language (ISO 639-1 code), relative to the best fit, which
     * scores 1. Most likely language first; empty when the text gives no usable signal.
     */
    fun scores(text: String): Map<String, Float>
}

/**
 * Keeps recommendations to the languages the profile is in. The embedding model matches across
 * languages, so without this a video in a language the user can't follow ranks as high as any other.
 *
 * It errs on the side of keeping: titles are short and full of names, so a candidate is only
 * dropped when another language fits clearly better than every language of the profile.
 */
class LanguageFilter(
    private val detector: LanguageDetector,
    /** Texts of the profile videos (title plus description works best). */
    profileTexts: List<String>,
    /** A language counts as the user's when at least this share of the profile is in it. */
    minShare: Float = 0.03f,
    /**
     * A title is foreign only if none of the profile's languages scores above this (the best fit
     * scores 1). On a real pool of 1,500 candidates, everything below 0.8 was truly foreign, while
     * English titles misread as another language still scored 0.8 or more for English.
     */
    private val maxOwnScore: Float = 0.8f,
    /** Titles with fewer words than this are never judged. */
    private val minWords: Int = 4,
) {
    /** The profile's languages with the number of videos in each, most common first. */
    val profileLanguages: Map<String, Int> = profileTexts
        .mapNotNull { detector.scores(it).keys.firstOrNull() }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.associate { it.key to it.value }

    private val allowed: Set<String> = profileLanguages
        .filterValues { it >= 2 && it >= minShare * profileTexts.size }.keys

    /** The language [title] was rejected for, or null if the candidate may stay. */
    fun foreignLanguage(title: String): String? {
        // With no known profile language there is nothing to compare against.
        if (allowed.isEmpty() || wordCount(title) < minWords) return null
        val scores = detector.scores(title)
        val best = scores.keys.firstOrNull() ?: return null
        val own = allowed.maxOf { scores[it] ?: 0f }
        return best.takeIf { it !in allowed && own <= maxOwnScore }
    }

    /** Words separated by spaces; Chinese, Japanese and Thai write without them, so there two characters count as a word. */
    private fun wordCount(text: String): Int {
        val unspaced = text.count { Character.UnicodeScript.of(it.code) in UNSPACED_SCRIPTS }
        val spaced = text.split(Regex("\\s+")).count { word ->
            word.any { it.isLetter() && Character.UnicodeScript.of(it.code) !in UNSPACED_SCRIPTS }
        }
        return spaced + unspaced / 2
    }

    private companion object {
        val UNSPACED_SCRIPTS = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
        )
    }
}
