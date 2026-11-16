package forage.core.recommend

import forage.core.embedding.Vectors
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Spherical k-means: clusters unit vectors by cosine similarity. Deterministic for a given seed. */
object Clustering {
    /** A rule of thumb that gives 5 clusters for 50 videos and 7 for 100. */
    fun defaultK(n: Int): Int = sqrt(n / 2.0).roundToInt().coerceIn(1, 12).coerceAtMost(n.coerceAtLeast(1))

    /** Returns the member indices of each cluster, largest cluster first. Every vector lands in exactly one. */
    fun kMeans(vectors: List<FloatArray>, k: Int = defaultK(vectors.size), seed: Long = 1, iterations: Int = 50): List<List<Int>> {
        if (vectors.isEmpty()) return emptyList()
        val random = Random(seed)
        val centroids = seedCentroids(vectors, k.coerceIn(1, vectors.size), random)
        var assignment = IntArray(vectors.size) { -1 }
        repeat(iterations) {
            val next = IntArray(vectors.size) { i -> centroids.indices.maxBy { c -> Vectors.dot(vectors[i], centroids[c]) } }
            if (next.contentEquals(assignment)) return@repeat
            assignment = next
            for (c in centroids.indices) {
                val members = vectors.indices.filter { assignment[it] == c }
                // An emptied cluster keeps its old centroid; it simply ends up dropped below.
                if (members.isNotEmpty()) centroids[c] = mean(members.map { vectors[it] })
            }
        }
        return centroids.indices
            .map { c -> vectors.indices.filter { assignment[it] == c } }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }
    }

    fun mean(vectors: List<FloatArray>): FloatArray {
        val sum = FloatArray(vectors.first().size)
        for (v in vectors) for (d in sum.indices) sum[d] += v[d]
        return Vectors.normalize(sum)
    }

    /** k-means++: start from one random vector, then prefer vectors far from the centroids chosen so far. */
    private fun seedCentroids(vectors: List<FloatArray>, k: Int, random: Random): MutableList<FloatArray> {
        val centroids = mutableListOf(vectors[random.nextInt(vectors.size)])
        while (centroids.size < k) {
            val distances = vectors.map { v -> (1f - centroids.maxOf { Vectors.dot(v, it) }).coerceAtLeast(0f).let { it * it } }
            val total = distances.sum()
            if (total <= 0f) break   // all remaining vectors coincide with a centroid
            var target = random.nextFloat() * total
            var chosen = distances.lastIndex
            for (i in distances.indices) {
                target -= distances[i]
                if (target <= 0f) { chosen = i; break }
            }
            centroids += vectors[chosen]
        }
        return centroids
    }
}
