package forage.embedding.onnx

import forage.core.embedding.Vectors
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real model. Skipped unless FORAGE_MODEL_DIR points at a directory holding (or to receive) it. */
class OnnxEmbedderTest {
    private val modelDir = System.getenv("FORAGE_MODEL_DIR")

    @Test
    fun `related texts score higher than unrelated ones, across languages`() {
        if (modelDir == null) return
        val spec = ModelSpec.MULTILINGUAL_MINILM
        OnnxEmbedder(spec, ModelFiles.ensure(spec, Path.of(modelDir))).use { embedder ->
            val (bread, brot, chess, long) = embedder.embed(
                listOf(
                    "How to bake bread at home",
                    "Brot backen zu Hause",
                    "Chess opening theory: the Sicilian Defence explained",
                    "bread ".repeat(2000),
                )
            )
            assertEquals(384, bread.size)
            for (v in listOf(bread, brot, chess, long)) assertTrue(abs(Vectors.dot(v, v) - 1f) < 1e-4f)
            assertTrue(Vectors.dot(bread, brot) > 0.8f, "bread/brot = ${Vectors.dot(bread, brot)}")
            assertTrue(Vectors.dot(bread, chess) < 0.2f, "bread/chess = ${Vectors.dot(bread, chess)}")
            // A single text must embed the same alone as in a padded batch.
            val alone = embedder.embed(listOf("How to bake bread at home")).single()
            assertTrue(Vectors.dot(alone, bread) > 0.999f)
        }
    }
}
