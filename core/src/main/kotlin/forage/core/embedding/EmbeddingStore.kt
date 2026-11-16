package forage.core.embedding

import forage.core.PlatformId
import forage.core.db.ForageDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Persists vectors so each text is embedded once per model. */
class EmbeddingStore(db: ForageDatabase, private val embedder: Embedder) {
    private val queries = db.embeddingQueries

    /**
     * Vectors for [texts] (video id to its embedding text), computing and storing those that are
     * missing or were made from a different text. [onProgress] gets the number done so far.
     */
    fun embed(texts: Map<PlatformId, String>, batchSize: Int = 16, onProgress: (Int) -> Unit = {}): Map<PlatformId, FloatArray> {
        val result = LinkedHashMap<PlatformId, FloatArray>()
        val missing = ArrayList<Pair<PlatformId, String>>()
        for ((id, text) in texts) {
            val row = queries.selectEmbedding(id.toString(), embedder.modelId).executeAsOneOrNull()
            if (row != null && row.text_hash == hash(text)) result[id] = decode(row.vector) else missing += id to text
        }
        var done = result.size
        for (batch in missing.chunked(batchSize)) {
            val vectors = embedder.embed(batch.map { it.second })
            queries.transaction {
                batch.zip(vectors).forEach { (entry, vector) ->
                    queries.upsertEmbedding(entry.first.toString(), embedder.modelId, hash(entry.second), encode(vector))
                    result[entry.first] = vector
                }
            }
            done += batch.size
            onProgress(done)
        }
        return texts.keys.associateWith { result.getValue(it) }
    }

    /** Every stored vector for this embedder's model. */
    fun all(): Map<PlatformId, FloatArray> =
        queries.selectEmbeddingsForModel(embedder.modelId).executeAsList()
            .associate { PlatformId.parse(it.video_id) to decode(it.vector) }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    private fun encode(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { asFloatBuffer().put(vector) }.array()

    private fun decode(bytes: ByteArray): FloatArray =
        FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}
