package forage.core.recommend

import forage.core.embedding.Vectors

/**
 * Groups unit vectors by cosine similarity with average-linkage agglomerative clustering:
 * start with every vector on its own and keep merging the two most similar groups until no
 * pair is similar enough.
 *
 * Unlike k-means this doesn't fix the number of clusters. A real profile is a few strong topics
 * plus many one-off interests, and forcing those into k groups produced mixed clusters.
 */
object Clustering {
    /** Average similarity two groups need to be merged. Found by looking at a real 150-video profile. */
    const val DEFAULT_THRESHOLD = 0.35f

    /** Returns the member indices of each group, largest first. Every vector lands in exactly one. */
    fun agglomerate(vectors: List<FloatArray>, threshold: Float = DEFAULT_THRESHOLD): List<List<Int>> {
        val n = vectors.size
        // similarity[a][b] is the average pairwise similarity between groups a and b.
        val similarity = Array(n) { a -> FloatArray(n) { b -> Vectors.dot(vectors[a], vectors[b]) } }
        val members = MutableList<MutableList<Int>?>(n) { mutableListOf(it) }

        while (true) {
            var bestA = -1
            var bestB = -1
            var best = threshold
            for (a in 0 until n) {
                if (members[a] == null) continue
                for (b in a + 1 until n) {
                    if (members[b] != null && similarity[a][b] >= best) {
                        best = similarity[a][b]; bestA = a; bestB = b
                    }
                }
            }
            if (bestA < 0) break

            val sizeA = members[bestA]!!.size
            val sizeB = members[bestB]!!.size
            for (c in 0 until n) {
                if (members[c] == null || c == bestA || c == bestB) continue
                val merged = (similarity[bestA][c] * sizeA + similarity[bestB][c] * sizeB) / (sizeA + sizeB)
                similarity[bestA][c] = merged
                similarity[c][bestA] = merged
            }
            members[bestA]!!.addAll(members[bestB]!!)
            members[bestB] = null
        }
        return members.filterNotNull().map { it.sorted() }.sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it.first() })
    }

    fun mean(vectors: List<FloatArray>): FloatArray {
        val sum = FloatArray(vectors.first().size)
        for (v in vectors) for (d in sum.indices) sum[d] += v[d]
        return Vectors.normalize(sum)
    }
}
