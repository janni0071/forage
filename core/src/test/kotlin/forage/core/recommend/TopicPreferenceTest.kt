package forage.core.recommend

import forage.core.PlatformId
import forage.core.embedding.Vectors
import forage.core.metadata.MetadataCache
import forage.core.metadata.details
import forage.core.metadata.memoryDatabase
import forage.core.profile.ProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicPreferenceTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private fun v(x: Float, y: Float, z: Float) = Vectors.normalize(floatArrayOf(x, y, z))
    private fun video(name: String, vector: FloatArray) = ProfileVideo(id(name), name, id("UC$name"), vector)
    private fun cluster(index: Int, vararg names: String, varied: Boolean = false) =
        Cluster(index, names.map { video(it, v(1f, 0f, 0f)) }, v(1f, 0f, 0f), listOf("topic$index"), isVaried = varied)

    private val db = memoryDatabase()
    private val store = TopicStore(db)
    private val profile = ProfileStore(db).profile()

    @Test
    fun `a pinned topic is recognised again after it gains and loses videos`() {
        store.set(profile, cluster(0, "a", "b", "c", "d"), TopicPreference.PINNED)
        // Recomputed: different index, one video gone, two new ones.
        val later = store.apply(profile, listOf(cluster(0, "x", "y", "z"), cluster(1, "a", "b", "c", "e", "f")))
        assertNull(later[0].preference)
        assertEquals(TopicPreference.PINNED, later[1].preference)
        // A topic that kept too little of it is something else.
        assertNull(store.apply(profile, listOf(cluster(0, "a", "q", "r", "s", "t"))).single().preference)
    }

    @Test
    fun `preferences can be changed and cleared, but not on the varied group`() {
        val topic = cluster(0, "a", "b", "c")
        store.set(profile, topic, TopicPreference.PINNED)
        store.set(profile, topic, TopicPreference.MUTED)
        assertEquals(TopicPreference.MUTED, store.apply(profile, listOf(topic)).single().preference)
        store.set(profile, topic, null)
        assertNull(store.apply(profile, listOf(topic)).single().preference)
        assertFailsWith<IllegalArgumentException> { store.set(profile, cluster(1, "x", varied = true), TopicPreference.MUTED) }
    }

    @Test
    fun `profiles keep their own preferences`() {
        val topic = cluster(0, "a", "b", "c")
        store.set(profile, topic, TopicPreference.MUTED)
        val other = ProfileStore(db).profile("Evening")
        assertNull(store.apply(other, listOf(topic)).single().preference)
    }

    // Ranking: x = woodworking, y = baking.
    private val wood = (1..3).map { video("w$it", v(1f, 0.05f * it, 0f)) }
    private val bake = (1..3).map { video("b$it", v(0.05f * it, 1f, 0f)) }
    private val taste = Profile(wood + bake)
    private val woodTopic = Cluster(0, wood, v(1f, 0f, 0f), listOf("wood"))
    private val bakeTopic = Cluster(1, bake, v(0f, 1f, 0f), listOf("bread"))
    private fun candidate(name: String, vector: FloatArray) = Candidate(id(name), name, id("UC$name"), name, false, null, vector, emptyList())
    // Woodworking candidates match the profile slightly better than baking ones.
    private val candidates = (1..8).map { candidate("wood$it", v(1f, 0.03f * it, 0f)) } +
        (1..8).map { candidate("bread$it", v(0.2f + 0.03f * it, 1f, 0f)) }

    @Test
    fun `a muted topic gets no picks`() {
        val picks = Ranker(RankingConfig(slots = 6, explorationSlots = 0, maxPerChannel = 9))
            .rank(taste, listOf(woodTopic.copy(preference = TopicPreference.MUTED), bakeTopic), candidates)
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.none { it.candidate.title.startsWith("wood") }, picks.map { it.candidate.title }.toString())
    }

    @Test
    fun `a pinned topic always gets its share`() {
        val config = RankingConfig(slots = 4, explorationSlots = 0, mmrLambda = 1f, perPinnedTopic = 2)
        val unpinned = Ranker(config).rank(taste, listOf(woodTopic, bakeTopic), candidates)
        val pinned = Ranker(config).rank(taste, listOf(woodTopic, bakeTopic.copy(preference = TopicPreference.PINNED)), candidates)
        val bakeBefore = unpinned.count { it.candidate.title.startsWith("bread") }
        assertTrue(bakeBefore < 2, "test setup: baking should lose without the pin ($bakeBefore)")
        assertEquals(2, pinned.count { it.candidate.title.startsWith("bread") })
        assertEquals(4, pinned.size)
    }

    @Test
    fun `muted topics supply no candidates`() {
        val cache = MetadataCache(db).apply {
            put(details("w1", related = listOf("rel1")))
            put(details("b1", related = listOf("rel2")))
        }
        val found = CandidateGenerator(cache).generate(
            listOf(Cluster(0, listOf(wood[0]), v(1f, 0f, 0f), listOf("wood"), preference = TopicPreference.MUTED), Cluster(1, listOf(bake[0]), v(0f, 1f, 0f), listOf("bread"))),
            emptySet(),
        )
        assertEquals(listOf("rel2"), found.map { it.stub.id.id })
    }
}
