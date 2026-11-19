package forage.embedding.onnx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.BreakIterator
import java.util.Base64

/**
 * The model's tokenizer (a SentencePiece Unigram model in Hugging Face's `tokenizer.json`) in plain
 * Kotlin, so it runs wherever the JVM does, Android included. It reproduces the reference library
 * step by step: the precompiled normalization table, whitespace splitting with `▁` word markers,
 * Viterbi segmentation over the vocabulary scores, fused unknown pieces, and `<s> … </s>` around the
 * result, truncated to the model's length.
 */
class UnigramTokenizer private constructor(
    private val pieces: Map<String, Piece>,
    private val maxPieceLength: Int,
    private val unkId: Int,
    private val unkScore: Double,
    private val normalizer: PrecompiledNormalizer?,
    private val bos: Int,
    private val eos: Int,
    val padId: Int,
) {
    private class Piece(val id: Int, val score: Double)

    /** Token ids for one text: `<s>`, the pieces, `</s>`, at most [maxTokens] in all. */
    fun encode(text: String, maxTokens: Int): IntArray {
        val normalized = normalizer?.normalize(text) ?: text
        val ids = ArrayList<Int>()
        for (word in normalized.split(WHITESPACE)) {
            if (word.isEmpty()) continue
            segment(WORD_MARK + word.replace(' ', WORD_MARK), ids)
        }
        val body = ids.take((maxTokens - 2).coerceAtLeast(0))
        return (listOf(bos) + body + eos).toIntArray()
    }

    /** Viterbi over the vocabulary: the segmentation with the highest total score. */
    private fun segment(word: String, out: MutableList<Int>) {
        // Positions are code point boundaries, so a piece never splits a surrogate pair.
        val bounds = IntArray(word.codePointCount(0, word.length) + 1)
        run {
            var i = 0
            var n = 0
            while (i < word.length) {
                bounds[n++] = i
                i += Character.charCount(word.codePointAt(i))
            }
            bounds[n] = word.length
        }
        val n = bounds.size - 1
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val via = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (start in 0 until n) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            var singleChar = false
            for (length in 1..minOf(maxPieceLength, n - start)) {
                val piece = pieces[word.substring(bounds[start], bounds[start + length])] ?: continue
                if (length == 1) singleChar = true
                val score = best[start] + piece.score
                val end = start + length
                if (from[end] == -1 || score > best[end]) {
                    best[end] = score
                    from[end] = start
                    via[end] = piece.id
                }
            }
            if (!singleChar) {
                val score = best[start] + unkScore
                if (from[start + 1] == -1 || score > best[start + 1]) {
                    best[start + 1] = score
                    from[start + 1] = start
                    via[start + 1] = unkId
                }
            }
        }
        val path = ArrayList<Int>()
        var at = n
        while (at > 0) {
            path += via[at]
            at = from[at]
        }
        path.reverse()
        // Consecutive unknown pieces within a word become one, as the reference does.
        var previousUnknown = false
        for (id in path) {
            if (id == unkId && previousUnknown) continue
            out += id
            previousUnknown = id == unkId
        }
    }

    companion object {
        private const val WORD_MARK = '▁'
        /** Unknown characters score this far below the worst piece. */
        private const val UNK_PENALTY = 10.0
        /** Unicode white space, as Rust's `char::is_whitespace`, which the reference splits on. */
        private val WHITESPACE = Regex("\\p{IsWhite_Space}+")

        fun load(file: File): UnigramTokenizer = parse(file.readText())

        fun parse(json: String): UnigramTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root.getValue("model").jsonObject
            require(model["type"]?.jsonPrimitive?.content == "Unigram") { "Not a Unigram tokenizer" }
            val vocab = model.getValue("vocab").jsonArray
            val pieces = HashMap<String, Piece>(vocab.size * 2)
            var minScore = Double.POSITIVE_INFINITY
            var maxLength = 1
            vocab.forEachIndexed { id, entry ->
                val pair = entry.jsonArray
                val piece = pair[0].jsonPrimitive.content
                val score = pair[1].jsonPrimitive.double
                if (score < minScore) minScore = score
                pieces.putIfAbsent(piece, Piece(id, score))
                maxLength = maxOf(maxLength, piece.codePointCount(0, piece.length))
            }
            val unkId = model["unk_id"]?.jsonPrimitive?.int ?: 0
            val normalizer = (root["normalizer"] as? JsonObject)?.let { n ->
                when (n["type"]?.jsonPrimitive?.content) {
                    "Precompiled" -> PrecompiledNormalizer(Base64.getDecoder().decode(n.getValue("precompiled_charsmap").jsonPrimitive.content))
                    else -> null
                }
            }
            fun special(name: String, default: Int): Int =
                (root["added_tokens"] as? JsonArray)?.map { it.jsonObject }
                    ?.firstOrNull { (it["content"] as? JsonPrimitive)?.content == name }?.get("id")?.jsonPrimitive?.int ?: default
            return UnigramTokenizer(
                pieces, maxLength, unkId, minScore - UNK_PENALTY, normalizer,
                bos = special("<s>", 0), eos = special("</s>", 2), padId = special("<pad>", 1),
            )
        }
    }
}

/**
 * SentencePiece's precompiled normalization: a double-array trie mapping character sequences to
 * their replacements, applied per grapheme cluster where it has an entry, else per character.
 */
internal class PrecompiledNormalizer(blob: ByteArray) {
    private val trie: IntArray
    private val normalized: ByteArray

    init {
        val trieSize = (blob[0].toInt() and 0xff) or ((blob[1].toInt() and 0xff) shl 8) or
            ((blob[2].toInt() and 0xff) shl 16) or ((blob[3].toInt() and 0xff) shl 24)
        trie = IntArray(trieSize / 4) { i ->
            val o = 4 + i * 4
            (blob[o].toInt() and 0xff) or ((blob[o + 1].toInt() and 0xff) shl 8) or
                ((blob[o + 2].toInt() and 0xff) shl 16) or ((blob[o + 3].toInt() and 0xff) shl 24)
        }
        normalized = blob.copyOfRange(4 + trieSize, blob.size)
    }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        val graphemes = BreakIterator.getCharacterInstance()
        graphemes.setText(text)
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val replaced = if (grapheme.toByteArray(Charsets.UTF_8).size < 6) transform(grapheme) else null
            if (replaced != null) {
                out.append(replaced)
            } else {
                var i = 0
                while (i < grapheme.length) {
                    val cp = grapheme.codePointAt(i)
                    val char = String(Character.toChars(cp))
                    out.append(transform(char) ?: char)
                    i += Character.charCount(cp)
                }
            }
            start = end
            end = graphemes.next()
        }
        return out.toString()
    }

    /** The replacement for the first (shortest) prefix of [chunk] the table has an entry for, if any. */
    private fun transform(chunk: String): String? {
        val key = chunk.toByteArray(Charsets.UTF_8)
        val index = firstPrefixMatch(key) ?: return null
        var endIndex = index
        while (endIndex < normalized.size && normalized[endIndex] != 0.toByte()) endIndex++
        return String(normalized, index, endIndex - index, Charsets.UTF_8)
    }

    /** Darts double-array common prefix search, returning the first value found, as SentencePiece uses it. */
    private fun firstPrefixMatch(key: ByteArray): Int? {
        var pos = 0
        var unit = trie[pos]
        pos = pos xor offset(unit)
        for (b in key) {
            val c = b.toInt() and 0xff
            if (c == 0) break
            pos = pos xor c
            if (pos !in trie.indices) return null
            unit = trie[pos]
            if (label(unit) != c) return null
            pos = pos xor offset(unit)
            if (hasLeaf(unit)) return value(trie[pos])
        }
        return null
    }

    private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
    private fun value(unit: Int) = unit and 0x7fffffff
    private fun label(unit: Int) = unit and (0x80000000.toInt() or 0xff)
    private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)
}
