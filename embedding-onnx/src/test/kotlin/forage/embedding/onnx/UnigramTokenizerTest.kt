package forage.embedding.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin tokenizer against the reference library, on the model's real tokenizer file. Runs
 * when FORAGE_MODEL_DIR points at a downloaded model; FORAGE_TITLES adds a file of titles.
 */
class UnigramTokenizerTest {
    private val dir = System.getenv("FORAGE_MODEL_DIR")?.let(::File)?.takeIf { File(it, "tokenizer.json").isFile }

    private val samples = listOf(
        "Hand-cut dovetails for beginners",
        "Sauerteig für Anfänger – Brot backen",
        "Ünïcödé façade — naïve café",
        "日本語のタイトル　全角スペース",
        "emoji 🎉🔥 test 👍🏽 family 👨‍👩‍👧",
        "Ｆｕｌｌｗｉｄｔｈ ＡＢＣ １２３",
        "tabs\tand\nnewlines",
        "  leading and   multiple   spaces  ",
        "İstanbul ıi Straße ß",
        "ﬁ ligature ½ ™ ①",
        "x nbsp narrow",
        "",
        "word ".repeat(200),
    )

    @Test
    fun `matches the reference tokenizer`() {
        val dir = dir ?: return
        val reference = HuggingFaceTokenizer.builder()
            .optTokenizerPath(File(dir, "tokenizer.json").toPath())
            .optTruncation(true).optMaxLength(128).optPadding(false)
            .build()
        val kotlin = UnigramTokenizer.load(File(dir, "tokenizer.json"))
        val titles = System.getenv("FORAGE_TITLES")?.let { File(it).readLines() }.orEmpty()
        var mismatches = 0
        val examples = ArrayList<String>()
        for (text in samples + titles) {
            val expected = reference.encode(text).ids.map { it.toInt() }
            val actual = kotlin.encode(text, 128).toList()
            if (expected != actual) {
                mismatches++
                if (examples.size < 5) examples += "${text.take(60)}\n  expected $expected\n  actual   $actual"
            }
        }
        println("compared ${samples.size + titles.size} texts, $mismatches differ")
        assertEquals(0, mismatches, examples.joinToString("\n"))
    }
}
