package forage.core.recommend

import kotlin.math.ln

/** Picks the words that set each group of texts apart from the other groups (TF-IDF across groups). */
object Keywords {
    private val WORD = Regex("""[\p{L}][\p{L}\p{N}'’-]*""")

    // Function words of the languages the SPEC expects in titles. Deliberately short: IDF handles the rest.
    private val STOPWORDS = """
        the and for with you your that this from are was were what when why how who will can not but all has have had
        its it's our out about into over more most just than then them they their there here get got one two new
        der die das und ein eine einen einem einer mit von für auf ist im in den dem des zu zum zur nicht auch wie was
        wir ich sie er es sich aus bei nach über unter oder aber wenn man noch nur so als am an um
        les des une un le la et en du de pour avec dans sur est pas que qui au aux ce ces son sa ses plus par
        video videos official feat ft vs part
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    fun tokens(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value.trim('\'', '’', '-') }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toList()

    /**
     * [groups] holds one list of texts (one text per video) for each cluster.
     * Returns up to [count] keywords per cluster, most characteristic first. Words in [ignore]
     * are never keywords: channel names say who made a video, not what it is about.
     */
    fun top(groups: List<List<String>>, count: Int = 5, ignore: Set<String> = emptySet()): List<List<String>> =
        topTerms(groups.map { texts -> texts.map(::tokens) }, count, ignore)

    /**
     * Like [top], for texts already split into terms: one list of terms per video. Lets whole
     * tags ("tag one") be keywords instead of the words in them.
     */
    fun topTerms(groups: List<List<List<String>>>, count: Int = 5, ignore: Set<String> = emptySet()): List<List<String>> {
        // Count each term once per video, so one keyword-stuffed description can't dominate.
        val frequencies = groups.map { videos ->
            videos.flatMap { it.distinct() }.groupingBy { it }.eachCount()
        }
        val groupsContaining = HashMap<String, Int>()
        frequencies.forEach { f -> f.keys.forEach { groupsContaining.merge(it, 1, Int::plus) } }

        return frequencies.mapIndexed { g, f ->
            val size = groups[g].size.coerceAtLeast(1)
            f.entries
                // A word seen in a single video describes that video, not the cluster.
                .filter { (it.value >= 2 || size == 1) && it.key !in ignore }
                .map { (word, n) -> word to (n.toDouble() / size) * ln((groups.size + 1.0) / groupsContaining.getValue(word)) }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(count)
                .map { it.first }
        }
    }
}
