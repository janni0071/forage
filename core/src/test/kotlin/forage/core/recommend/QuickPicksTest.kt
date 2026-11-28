package forage.core.recommend

import forage.core.PlatformId
import forage.core.importer.ImportResult
import forage.core.importer.TakeoutData
import forage.core.importer.WatchEvent
import forage.core.library.LibraryStore
import forage.core.metadata.MetadataCache
import forage.core.metadata.details
import forage.core.metadata.memoryDatabase
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import forage.core.source.ChannelFeed
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickPicksTest {
    private fun id(s: String) = PlatformId.youtube(s)
    private val db = memoryDatabase()
    private val cache = MetadataCache(db)
    private val library = LibraryStore(db)
    private val profiles = ProfileStore(db)
    private val main = profiles.profile()
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private fun stub(s: String, channel: String, uploaded: Instant? = null) =
        VideoStub(id(s), "Video $s", PlatformId("youtube", channel), channel, 600, false, uploaded)

    @Test
    fun `from likes, the history and those followed, without the model`() {
        // A like with related videos; the history; a followed channel with new uploads.
        cache.put(details("a", related = listOf("rel1", "rel3", "seen")))
        profiles.mark(main, listOf(id("a")), VideoState.LIKED)
        library.saveForLater(id("seen"))
        library.follow(PlatformId("youtube", "UCfollowed"), "Followed")
        library.storeFeed(
            PlatformId("youtube", "UCfollowed"),
            ChannelFeed(listOf(stub("new1", "UCfollowed", now.minusSeconds(3600)), stub("old1", "UCfollowed", now.minusSeconds(90L * 86400)))),
        )
        val picks = QuickPicks(cache, library, profiles, now = { now }).run(main)
        val ids = picks.map { it.video.id }
        assertTrue("rel1" in ids && "new1" in ids)
        // Nothing already saved, watched or liked.
        assertTrue("seen" !in ids && "a" !in ids)
        // Something related to a like ranks above an old upload; a fresh upload above an old one.
        assertTrue(ids.indexOf("rel1") < ids.indexOf("old1"))
        assertTrue(ids.indexOf("new1") < ids.indexOf("old1"))
        assertTrue(picks.first { it.video.id == "rel1" }.reason.startsWith("Related to"))
        assertTrue(picks.all { it.foundVia == QuickPicks.FOUND_VIA })
        // Stored as the batch Discover shows.
        assertEquals(ids, profiles.picks(main).map { it.video.id })
    }

    @Test
    fun `with only a history, the latest of the channels watched most, three at most each`() {
        val watched = (1..6).map { WatchEvent(id("w$it"), "Watched $it", PlatformId("youtube", "UCfav"), "Fav", now.minusSeconds(it * 3600L)) }
        library.importTakeout(TakeoutData(ImportResult(watched, emptyMap()), emptyMap(), emptyList()))
        val feeds = object : ExtractorSource {
            override val platform = "youtube"
            override val capabilities = setOf(Capability.CHANNEL_FEED)
            override fun video(id: PlatformId): VideoDetails = error("not needed")
            override fun search(query: String): List<VideoStub> = emptyList()
            override fun watchUrl(id: PlatformId) = ""
            override fun channelFeed(channel: PlatformId, name: String?) =
                ChannelFeed((1..8).map { stub("f$it", channel.id, now.minusSeconds(it * 86400L)) } + stub("w1", channel.id), "Fav")
        }
        val picks = QuickPicks(cache, library, profiles, source = feeds, now = { now }).run(main)
        assertEquals(3, picks.size)
        assertTrue(picks.none { it.video.id == "w1" })
        assertTrue(picks.first().reason.contains("Fav"))
    }
}
