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
    private val wood3 = ProfileVideo(id("w3"), "Dovetails in oak", id("UCwren"), v(1f, 0.1f, 0.1f))
    private val bake3 = ProfileVideo(id("b3"), "Sourdough in a Dutch oven", id("UCbrot"), v(0.1f, 1f, 0.1f))
    private val chess = ProfileVideo(id("c1"), "The Sicilian Defence", id("UCchess"), v(0f, 0f, 1f))
    private val profile = Profile(listOf(wood1, bake1, wood2, bake2, wood3, bake3, chess))
    private val clusters = Clusters.of(profile)

    private fun candidate(name: String, vector: FloatArray, channel: String = "UC$name", short: Boolean = false) =
        Candidate(id(name), name, id(channel), channel, short, null, vector, emptyList())

    @Test
    fun `groups the topics, names them by their distinctive words and keeps one-offs apart`() {
        assertEquals(3, clusters.size)
        val wood = clusters.single { wood1 in it.videos }
        val bake = clusters.single { bake1 in it.videos }
        assertEquals(setOf("w1", "w2", "w3"), wood.videos.map { it.id.id }.toSet())
        assertEquals(listOf("dovetails"), wood.keywords)
        assertEquals(listOf("sourdough"), bake.keywords)
        val varied = clusters.last()
        assertTrue(varied.isVaried)
        assertEquals(listOf(chess), varied.videos)
        assertEquals("varied interests", varied.label)
    }

    @Test
    fun `clustering assigns every vector once, is repeatable and never merges below the threshold`() {
        val vectors = List(40) { i -> v(1f + i % 3, (i % 5).toFloat(), (i % 7).toFloat()) }
        val a = Clustering.agglomerate(vectors, threshold = 0.9f)
        assertEquals((0 until 40).toList(), a.flatten().sorted())
        assertEquals(a, Clustering.agglomerate(vectors, threshold = 0.9f))
        assertEquals(1, Clustering.agglomerate(List(5) { v(1f, 0f, 0f) }).size)
        assertEquals(3, Clustering.agglomerate(listOf(v(1f, 0f, 0f), v(0f, 1f, 0f), v(0f, 0f, 1f))).size)
    }

    @Test
    fun `channel names never become keywords`() {
        val named = Clusters.of(profile, channelNames = listOf("Dovetails Daily"))
        assertEquals(emptyList(), named.single { wood1 in it.videos }.keywords)
    }

    @Test
    fun `ranks by similarity and explains each pick`() {
        val picks = Ranker(RankingConfig(slots = 3, explorationSlots = 0)).rank(
            profile, clusters,
            listOf(
                candidate("gambit", v(0f, 0.1f, 1f)),
                candidate("joinery", v(1f, 0.1f, 0f), channel = "UCwren"),
                candidate("planes", v(1f, 0f, 0f)),
            ),
        ).associateBy { it.candidate.title }
        assertEquals("similar to 3 videos in your profile, e.g. \"Hand-cut dovetails\"", picks.getValue("joinery").reason)
        assertFalse(picks.getValue("joinery").newChannel)
        assertTrue(picks.getValue("planes").reason.startsWith("new channel near your dovetails topic; similar to 3 videos"))
        // A candidate nearest a one-off interest is explained by that video, not by a topic.
        assertEquals("new channel; similar to \"The Sicilian Defence\"", picks.getValue("gambit").reason)
        assertTrue(picks.getValue("joinery").score > picks.getValue("gambit").score)
    }

    @Test
    fun `negative examples sink what resembles them more than the profile, and nothing else`() {
        // "tutorial" sits between woodworking and the rejected video; "joinery" is plain woodworking.
        val candidates = listOf(candidate("tutorial", v(1f, 0f, 0.9f)), candidate("joinery", v(1f, 0.1f, 0.05f)))
        val config = RankingConfig(slots = 2, explorationSlots = 0, mmrLambda = 1f)
        val before = Ranker(config).rank(profile, clusters, candidates).associateBy { it.candidate.title }
        val rejecting = Profile(profile.videos, negatives = listOf(v(0.8f, 0f, 1f)))
        val after = Ranker(config).rank(rejecting, clusters, candidates).associateBy { it.candidate.title }
        assertTrue(after.getValue("tutorial").score < before.getValue("tutorial").score - 0.1f)
        assertEquals(before.getValue("joinery").score, after.getValue("joinery").score)
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
    fun `recent uploads win where the user follows releases, and only there`() {
        val now = java.time.Instant.parse("2026-11-17T00:00:00Z")
        val trailers = Profile(
            listOf(
                ProfileVideo(id("t1"), "Character trailer A", null, v(1f, 0f, 0f), ageWhenAddedDays = 2),
                ProfileVideo(id("t2"), "Character trailer B", null, v(1f, 0.1f, 0f), ageWhenAddedDays = 5),
                ProfileVideo(id("d1"), "Old documentary", null, v(0f, 1f, 0f), ageWhenAddedDays = 900),
                ProfileVideo(id("d2"), "Older documentary", null, v(0f, 1f, 0.1f), ageWhenAddedDays = 2000),
            )
        )
        fun dated(name: String, vector: FloatArray, daysOld: Long) =
            candidate(name, vector).copy(uploadedAt = now.minus(java.time.Duration.ofDays(daysOld)))
        val config = RankingConfig(slots = 4, explorationSlots = 0, nearest = 2, mmrLambda = 1f)
        val picks = Ranker(config).rank(
            trailers, Clusters.of(trailers),
            listOf(
                dated("old trailer", v(1f, 0.04f, 0f), 240), dated("new trailer", v(1f, 0.06f, 0f), 3),
                dated("old doc", v(0f, 1f, 0.04f), 2400), dated("new doc", v(0f, 1f, 0.06f), 3),
            ),
            now = now,
        ).associateBy { it.candidate.title }
        val trailerGap = picks.getValue("new trailer").score - picks.getValue("old trailer").score
        val docGap = picks.getValue("new doc").score - picks.getValue("old doc").score
        assertTrue(trailerGap > 0.1f, "trailer gap $trailerGap")
        assertTrue(docGap in 0.01f..0.05f, "documentary gap $docGap")
        assertTrue(picks.getValue("new trailer").reason.endsWith("; recent upload"))
        assertFalse("recent upload" in picks.getValue("new doc").reason)
    }

    @Test
    fun `candidates found only by search rank below equally similar related videos`() {
        val searched = candidate("searched", v(1f, 0.1f, 0f)).copy(sources = listOf(CandidateSource.Search("dovetails", 0)))
        val related = candidate("related", v(1f, 0.1f, 0f)).copy(sources = listOf(CandidateSource.RelatedTo(wood1)))
        val picks = Ranker(RankingConfig(slots = 2, explorationSlots = 0, mmrLambda = 1f)).rank(profile, clusters, listOf(searched, related))
        assertEquals(listOf("related", "searched"), picks.map { it.candidate.title })
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

    @Test
    fun `videos with descriptive tags are grouped by their rarer tags and topics named after whole tags`() {
        // Alike to the text model, as on a site where every video shares the same generic tags.
        val alike = v(1f, 0.1f, 0f)
        val groups = listOf("garden pond", "night sky", "old trains")
        val videos = groups.flatMapIndexed { g, _ -> (0 until 3).map { i -> ProfileVideo(PlatformId("other", "v$g$i"), "Video $g$i", null, alike) } }
        val tags = videos.associate { video ->
            val g = video.id.id[1].digitToInt()
            video.id to listOf("generic tag", "everywhere", groups[g], "${groups[g]} extra", "only ${video.id.id}")
        }
        val profile = Profile(videos)
        assertEquals(1, Clusters.of(profile).count { !it.isVaried }, "embeddings alone put everything together")

        val topics = Clusters.of(profile, tagTerms = tags)
        assertEquals(3, topics.size)
        assertTrue(topics.none { it.isVaried })
        assertEquals(groups.toSet(), topics.map { it.keywords.first() }.toSet())
        assertTrue(topics.none { "generic tag" in it.keywords })
    }

    @Test
    fun `names are never topic keywords`() {
        val alike = v(1f, 0f, 0f)
        val named = (0 until 3).map { ProfileVideo(PlatformId("other", "n$it"), "Clip $it", null, alike) }
        val others = (0 until 4).map { ProfileVideo(PlatformId("other", "m$it"), "Other $it", null, alike) }
        val tags = named.associate { it.id to listOf("shared subject", "some name") } +
            others.associate { it.id to listOf("other subject", "more") }
        val topics = Clusters.of(Profile(named + others), tagTerms = tags, channelNames = listOf("Some Name"))
        val topic = topics.single { named.first() in it.videos }
        assertEquals(listOf("shared subject"), topic.keywords)
    }

    @Test
    fun `tagged videos sharing a few rare tags among many form a topic`() {
        val alike = v(1f, 0.1f, 0f)
        // Each pair of these three shares two rare tags out of twelve; the rest are their own.
        val ids = (0 until 3).map { PlatformId("other", "p$it") }
        val tags = ids.associateWith { id -> listOf("rare a", "rare b") + (0 until 10).map { "own $id $it" } }
        val outsider = PlatformId("other", "q")
        val others = (0 until 3).map { PlatformId("other", "o$it") }
        val all = ids + others + outsider
        val videos = all.map { ProfileVideo(it, it.id, null, alike) }
        val terms = tags + others.associateWith { listOf("rare c", "rare d") + (0 until 10).map { n -> "own $it $n" } } +
            (outsider to (0 until 12).map { "own q $it" })
        val topics = Clusters.of(Profile(videos), tagTerms = terms)
        assertEquals(setOf(ids.toSet(), others.toSet()), topics.filter { !it.isVaried }.map { g -> g.videos.map { it.id }.toSet() }.toSet())
        assertEquals(listOf(outsider), topics.single { it.isVaried }.videos.map { it.id })
    }
}
