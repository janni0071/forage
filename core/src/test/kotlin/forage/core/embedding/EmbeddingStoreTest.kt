package forage.core.embedding

import forage.core.PlatformId
import forage.core.metadata.details
import forage.core.metadata.memoryDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmbeddingStoreTest {
    /** Encodes text length and first letter, and records what it was asked to embed. */
    private class FakeEmbedder(override val modelId: String = "fake") : Embedder {
        val seen = ArrayList<String>()
        override val dimensions = 2
        override fun embed(texts: List<String>): List<FloatArray> {
            seen += texts
            return texts.map { floatArrayOf(it.length.toFloat(), it.first().code.toFloat()) }
        }
    }

    private val db = memoryDatabase()
    private val a = PlatformId.youtube("a")
    private val b = PlatformId.youtube("b")

    @Test
    fun `embeds each text once and returns stored vectors afterwards`() {
        val embedder = FakeEmbedder()
        val store = EmbeddingStore(db, embedder)
        val first = store.embed(mapOf(a to "alpha", b to "be"))
        assertContentEquals(floatArrayOf(5f, 'a'.code.toFloat()), first.getValue(a))
        val second = store.embed(mapOf(a to "alpha", b to "be"))
        assertContentEquals(first.getValue(b), second.getValue(b))
        assertEquals(listOf("alpha", "be"), embedder.seen)
        assertEquals(setOf(a, b), store.all().keys)
    }

    @Test
    fun `changed text and a different model are embedded again`() {
        val embedder = FakeEmbedder()
        EmbeddingStore(db, embedder).embed(mapOf(a to "alpha"))
        EmbeddingStore(db, embedder).embed(mapOf(a to "alpha, edited"))
        assertEquals(listOf("alpha", "alpha, edited"), embedder.seen)

        val other = FakeEmbedder("other-model")
        EmbeddingStore(db, other).embed(mapOf(a to "alpha, edited"))
        assertEquals(listOf("alpha, edited"), other.seen)
    }

    @Test
    fun `embedding text leads with title, channel and tags and drops links`() {
        val video = details("a").metadata.copy(description = "Mein Rezept: https://example.invalid/x \n\n  Viel Spaß!")
        val text = EmbeddingText.of(video, notes = "für Sonntag")
        assertEquals(
            "Sauerteig für Anfänger – a\nBrotzeit\nbrot, sauerteig, mit komma\nfür Sonntag\nMein Rezept:\nViel Spaß!",
            text,
        )
        assertFalse("http" in text)
    }
}
