package forage.core.embedding

/** Turns text into unit-length vectors. Implementations wrap a specific model and runtime. */
interface Embedder : AutoCloseable {
    /** Identifies model and settings; vectors from different ids must never be compared. */
    val modelId: String
    val dimensions: Int

    /** One L2-normalised vector per text, in order. */
    fun embed(texts: List<String>): List<FloatArray>

    override fun close() {}
}

object Vectors {
    /** Cosine similarity of two unit vectors. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector sizes differ: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = kotlin.math.sqrt(sum).toFloat()
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }
}
