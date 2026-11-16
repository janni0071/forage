package forage.core.recommend

import forage.core.PlatformId
import forage.core.embedding.Vectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses hand-made 3-D vectors: x = woodworking, y = baking, z = chess. */
class RecommendTest {
    private fun v(x: Float, y: Float, z: Float) = Vectors.normalize(floatArrayOf(x, y, z))
    private fun id(s: String) = PlatformId.youtube(s)

    private val wood1 = ProfileVideo(id("w1"), "Hand-cut dovetails", id("UCwren"), v(1f, 0.1f, 0f))
    private val wood2 = ProfileVideo(id("w2"), "Dovetails with a router", id("UCwren"), v(1f, 0f, 0.1f))
    private val bake1 = ProfileVideo(id("b1"), "Sourdough starter basics", id("UCbrot"), v(0f, 1f, 0.1f))
    private val bake2 = ProfileVideo(id("b2"), "Sourdough shaping", id("UCbrot"), v(0.1f, 1f, 0f))
    private val profile = Profile(listOf(wood1, bake1, wood2, bake2))
    private val clusters = Clusters.of(profile, emptyMap(), k = 2)

    private fun candidate(name: String, vector: FloatArray, channel: String = "UC$name", short: Boolean = false) =
        Candidate(id(name), name, id(channel), channel, short, null, vector, emptyList())

    @Test
    fun `k-means separates the topics and names them by their distinctive words`() {
        assertEquals(setOf(setOf("w1", "w2"), setOf("b1", "b2")), clusters.map { c -> c.videos.map { it.id.id }.toSet() }.toSet())
        assertEquals(listOf("dovetails"), clusters.single { wood1 in it.videos }.keywords)
        assertEquals(listOf("sourdough"), clusters.single { bake1 in it.videos }.keywords)
    }

    @Test
    fun `every vector is assigned and identical input gives identical clusters`() {
        val vectors = List(40) { i -> v(1f + i % 3, (i % 5).toFloat(), (i % 7).toFloat()) }
        val a = Clustering.kMeans(vectors, k = 4, seed = 7)
        assertEquals((0 until 40).toList(), a.flatten().sorted())
        assertEquals(a, Clustering.kMeans(vectors, k = 4, seed = 7))
        assertEquals(1, Clustering.kMeans(List(5) { v(1f, 0f, 0f) }, k = 3).size)
    }

    @Test
    fun `ranks by similarity and explains each pick`() {
        val picks = Ranker(RankingConfig(slots = 2, explorationSlots = 0)).rank(
            profile, clusters,
            listOf(candidate("chess", v(0f, 0f, 1f)), candidate("joinery", v(1f, 0.1f, 0f), channel = "UCwren")),
        )
        assertEquals(listOf("joinery", "chess"), picks.map { it.candidate.title })
        assertEquals("similar to 2 videos in your profile, e.g. \"Hand-cut dovetails\"", picks[0].reason)
        assertFalse(picks[0].newChannel)
        assertTrue(picks[1].newChannel)
    }

    @Test
    fun `negative examples push similar candidates down`() {
        val candidates = listOf(candidate("a", v(1f, 0f, 0.3f)), candidate("b", v(1f, 0.3f, 0f)))
        val config = RankingConfig(slots = 2, explorationSlots = 0)
        val liked = Profile(profile.videos, negatives = listOf(v(0.2f, 0f, 1f)))
        assertEquals("b", Ranker(config).rank(liked, clusters, candidates).first().candidate.title)
    }

    @Test
    fun `diversity re-ranking keeps one topic and one channel from filling the list`() {
        val candidates = (1..6).map { candidate("wood$it", v(1f, 0.02f * it, 0f), channel = "UCsame") } +
            candidate("bread", v(0.1f, 1f, 0.1f))
        val picks = Ranker(RankingConfig(slots = 3, explorationSlots = 0)).rank(profile, clusters, candidates)
        assertTrue("bread" in picks.map { it.candidate.title })
        assertEquals(2, picks.count { it.candidate.channelName == "UCsame" })
    }

    @Test
    fun `hides shorts unless asked and reserves exploration slots for unfamiliar channels`() {
        val candidates = (1..10).map { candidate("wood$it", v(1f, 0.1f * it, 0.05f * it)) } +
            candidate("short", v(1f, 0f, 0f), short = true)
        val picks = Ranker(RankingConfig(slots = 5, explorationSlots = 2)).rank(profile, clusters, candidates)
        assertEquals(5, picks.size)
        assertFalse("short" in picks.map { it.candidate.title })
        assertEquals(2, picks.count { it.exploration })
        assertTrue(picks.filter { it.exploration }.all { it.reason.startsWith("exploration") })
        assertEquals(5, picks.map { it.candidate.id }.distinct().size)
        assertTrue("short" in Ranker(RankingConfig(showShorts = true)).rank(profile, clusters, candidates).map { it.candidate.title })
    }

    @Test
    fun `keywords ignore stopwords and words every cluster shares`() {
        val top = Keywords.top(
            listOf(
                listOf("How to cut dovetails by hand", "The best dovetails video"),
                listOf("How to bake the best bread", "Bread baking video for beginners"),
            )
        )
        assertEquals(listOf("dovetails"), top[0])
        assertEquals(listOf("bread"), top[1])
    }
}
