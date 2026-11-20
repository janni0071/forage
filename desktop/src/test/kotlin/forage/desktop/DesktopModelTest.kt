package forage.desktop

import forage.app.*
import forage.app.ui.*

import forage.core.PlatformId
import forage.core.importer.ImportResult
import forage.core.importer.PlaylistEntry
import forage.core.importer.TakeoutData
import forage.core.importer.WatchEvent
import forage.core.profile.StoredPick
import forage.core.profile.VideoState
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopModelTest {
    private fun id(s: String) = PlatformId.youtube(s)

    /** Answers every video as gone, noting what the status bar said while it was asked. */
    private val backgroundDuringFetch = ArrayList<String?>()
    private val offline = object : ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<Capability>()
        override fun video(id: PlatformId): VideoDetails {
            backgroundDuringFetch += model.background
            throw SourceException.NotAvailable("offline")
        }
        override fun search(query: String): List<VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
        override fun parseLink(text: String) =
            text.removePrefix("https://www.youtube.com/watch?v=").takeIf { it.length == 11 && "/" !in it }?.let(PlatformId::youtube)
    }

    private val dir = Files.createTempDirectory("forage-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Unconfined dispatchers run every action to completion before it returns.
    private val model: DesktopModel = DesktopModel(
        dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    ) { offline }.apply {
        library.importTakeout(
            TakeoutData(
                ImportResult(
                    listOf(
                        WatchEvent(id("a"), "Hand-cut dovetails", null, "Workshop Wren", Instant.parse("2026-11-01T10:00:00Z")),
                        WatchEvent(id("b"), "Sourdough basics", null, "Brotzeit", Instant.parse("2026-11-02T10:00:00Z")),
                    ),
                    emptyMap(),
                ),
                mapOf("Woodwork" to listOf(PlaylistEntry(id("c"), null))),
                emptyList(),
            )
        )
        runBlocking { reload() }
    }

    @Test
    fun `search results can be liked, and unliked again`() {
        val searchable = object : ExtractorSource by offline {
            override val capabilities = setOf(Capability.SEARCH)
            override fun forPlatform(platform: String) = takeIf { platform == "youtube" }
            override fun search(query: String) = listOf(
                VideoStub(id("sssssssss01"), "Found $query", null, "Someone", 600, false),
                VideoStub(id("a"), "Already watched", null, null, null, false),
            )
        }
        val m = DesktopModel(Files.createTempDirectory("forage-search").toFile(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { searchable }
        try {
            assertEquals(listOf("youtube" to "youtube"), m.searchPlatforms)
            m.searchVideos("  dovetails ")
            assertEquals(listOf("Found dovetails", "Already watched"), m.searchResults.map { it.title })
            assertEquals(emptySet(), m.searchLiked)
            m.likeFromSearch(m.searchResults.first())
            assertEquals(setOf(id("sssssssss01")), m.searchLiked)
            assertEquals(listOf(id("sssssssss01")), m.profiles.entries(m.profile, VideoState.LIKED).map { it.video })
            assertEquals("Found dovetails", m.library.video(id("sssssssss01"))?.title)
            m.likeFromSearch(m.searchResults.first())
            assertEquals(emptySet(), m.searchLiked)
            assertEquals(emptyList(), m.profiles.entries(m.profile))
        } finally {
            m.close()
        }
    }

    @Test
    fun `several links can be added at once, and anything else is refused`() {
        assertEquals("That doesn't look like a link to a video on youtube", model.addByLink("https://example.com/x"))
        val refused = model.addByLink("https://www.youtube.com/watch?v=aaaaaaaaaa1\nnot-a-link")
        assertEquals("1 of these aren't video links Forage knows, e.g. not-a-link", refused)
        assertNull(model.addByLink(" https://www.youtube.com/watch?v=aaaaaaaaaa1\n\nhttps://www.youtube.com/watch?v=bbbbbbbbbb2 "))
        val liked = model.profiles.entries(model.profile, VideoState.LIKED).map { it.video.id }
        assertEquals(setOf("aaaaaaaaaa1", "bbbbbbbbbb2"), liked.toSet())
        assertEquals("Liked 2 videos; 2 still need details (Fetch details)", model.message)
        assertEquals(LibrarySource.Liked, model.currentSource)
    }

    @Test
    fun `lists the history and filters it by text and state`() {
        assertEquals(listOf("Liked", "History", "Woodwork", "Not interested", "All videos"), model.sources.map { it.label })
        assertEquals(LibrarySource.Liked, model.currentSource)
        assertEquals(emptyList(), model.rows)
        model.selectSource(LibrarySource.History)
        // Most recently watched first.
        assertEquals(listOf("b", "a"), model.rows.map { it.id.id })
        model.sortList(ListSort.OLDEST)
        assertEquals(listOf("a", "b"), model.rows.map { it.id.id })
        model.sortList(ListSort.MOST_WATCHED)
        assertEquals(setOf("a", "b"), model.rows.map { it.id.id }.toSet())
        model.query = "wren"
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
        model.query = ""
        model.stateFilter = StateFilter.LIKED
        assertEquals(emptyList(), model.visibleRows)
        // The filter is for mixed lists only.
        model.selectSource(LibrarySource.Liked)
        model.mark(listOf(id("a")), VideoState.LIKED)
        model.stateFilter = StateFilter.NOT_INTERESTED
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
    }

    @Test
    fun `bulk actions like videos, fetch their details and clear the selection`() {
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        assertEquals(setOf(id("a"), id("b")), model.selection)
        model.markSelected(VideoState.LIKED)
        assertEquals(emptySet(), model.selection)
        assertEquals(2, model.profileSize)
        assertTrue(model.rows.all { it.state == VideoState.LIKED })
        assertEquals("Liked 2 videos", model.message)
        // A like counts at once; the details follow in the background.
        assertEquals(listOf<String?>("Fetching details of 2 videos", "Fetching details of 2 videos"), backgroundDuringFetch)

        model.toggle(id("b"))
        model.markSelected(VideoState.NOT_INTERESTED)
        assertEquals(VideoState.NOT_INTERESTED, model.rows.single { it.id == id("b") }.state)
        model.selectSource(LibrarySource.Liked)
        assertEquals(listOf(id("a")), model.rows.map { it.id })
        model.selectSource(LibrarySource.NotInterested)
        assertEquals(listOf(id("b")), model.rows.map { it.id })
        assertEquals(1, model.notInterestedSize)
    }

    @Test
    fun `playlists are made, filled, renamed, emptied and deleted on a page of their own`() {
        assertNull(model.createPlaylist("  Bread  "))
        assertEquals(LibraryView.PLAYLIST, model.libraryView)
        assertEquals("Bread", model.openPlaylistName)
        assertTrue("already" in model.createPlaylist("Bread")!!)
        model.openAddToPlaylist(listOf(id("b"), id("a")))
        assertEquals(LibraryDialog.AddToPlaylist(listOf(id("b"), id("a"))), model.libraryDialog)
        assertNull(model.addToPlaylist("Bread", listOf(id("b"), id("a"))))
        assertNull(model.libraryDialog)
        assertEquals("Added 2 videos to \"Bread\"", model.message)
        // In the order they were added.
        assertEquals(listOf(id("b"), id("a")), model.sortedPlaylist.map { it.id })
        assertEquals("Sourdough basics", model.sortedPlaylist.first().title)
        // Dragged into a new order, which the playlist keeps and its cover follows.
        model.reorderPlaylist(listOf(id("a"), id("b")))
        assertEquals(listOf(id("a"), id("b")), model.sortedPlaylist.map { it.id })
        assertEquals(listOf(id("a"), id("b")), model.library.playlistItems("Bread").map { it.first })
        assertEquals(id("a"), model.playlistCovers["Bread"])
        model.playlistSort = PlaylistSort.SHORTEST
        model.playlistSort = PlaylistSort.CUSTOM

        assertTrue("already" in model.renamePlaylist("Bread", "Woodwork")!!)
        assertNull(model.renamePlaylist("Bread", "Baking"))
        assertEquals("Baking", model.openPlaylistName)
        model.removeFromPlaylist(listOf(id("a")))
        assertEquals(listOf(id("b")), model.playlistItems.map { it.id })
        model.deletePlaylist("Baking")
        assertEquals(LibraryView.PLAYLISTS, model.libraryView)
        assertEquals(listOf("Liked", "History", "Woodwork", "Not interested", "All videos"), model.sources.map { it.label })
        assertEquals("Sourdough basics", model.library.video(id("b"))?.title)
        // Back from a playlist goes to the playlists, and from there to the overview; while
        // reordering, it ends that first.
        model.openPlaylist("Woodwork")
        model.reorderingPlaylist = true
        model.back()
        assertEquals(false, model.reorderingPlaylist)
        assertEquals(LibraryView.PLAYLIST, model.libraryView)
        model.back()
        assertEquals(LibraryView.PLAYLISTS, model.libraryView)
        model.back()
        assertEquals(LibraryView.HUB, model.libraryView)
    }

    @Test
    fun `the history forgets videos, clears and pauses`() {
        model.selectSource(LibrarySource.History)
        model.forget(listOf(id("a")))
        assertEquals(listOf(id("b")), model.rows.map { it.id })
        model.clearHistory()
        assertEquals(emptyList(), model.rows)
        // The playlist is untouched.
        assertEquals(1L, model.library.playlists()["Woodwork"])
        assertTrue(model.recordHistory)
        model.changeRecordHistory(false)
        assertEquals(false, Settings(dir.resolve("settings.properties")).recordHistory)
    }

    @Test
    fun `playlists show untitled videos until their details are fetched`() {
        model.selectSource(model.sources.single { it is LibrarySource.Playlist })
        val row = model.rows.single()
        assertEquals(id("c"), row.id)
        assertEquals(null, row.title)
    }

    @Test
    fun `feedback buttons toggle and show in the pick list`() {
        val pick = StoredPick(1, id("p"), "A pick", null, "Channel", 0.5f, "similar to X", "related to X", false)
        model.profiles.savePicks(model.profile, listOf(pick))
        runBlocking { model.reload() }
        val view = model.picks.single()
        model.feedback(view, Feedback.SAVED)
        model.feedback(model.picks.single(), Feedback.LIKE)
        assertEquals(setOf(Feedback.SAVED, Feedback.LIKE), model.picks.single().feedback)
        assertEquals(1, model.profileSize)
        assertEquals(listOf(id("p")), model.later.map { it.id })
        assertEquals("A pick", model.later.single().title)

        model.feedback(model.picks.single(), Feedback.LIKE)
        model.removeFromLater(id("p"))
        assertEquals(emptySet(), model.picks.single().feedback)
    }

    @Test
    fun `feedback fetches the video's details in the background and says so`() {
        val pick = StoredPick(1, id("p"), "A pick", null, "Channel", 0.5f, "similar to X", "related to X", false)
        model.profiles.savePicks(model.profile, listOf(pick))
        runBlocking { model.reload() }
        model.feedback(model.picks.single(), Feedback.NOT_INTERESTED)
        assertEquals(listOf<String?>("Fetching the video's details"), backgroundDuringFetch)
        assertNull(model.background)
        // Saving needs no details, so nothing is fetched.
        model.feedback(model.picks.single(), Feedback.SAVED)
        assertEquals(1, backgroundDuringFetch.size)
    }

    @Test
    fun `the queue sorts, keeps the pick's details and marks videos watched`() {
        fun pick(n: Int, v: String, seconds: Long?) =
            StoredPick(n, id(v), "Video $v", null, "Channel", 0.5f, "similar to X", "related to X", false, durationSeconds = seconds)
        model.profiles.savePicks(model.profile, listOf(pick(1, "long", 3600), pick(2, "short", 60), pick(3, "unknown", null)))
        runBlocking { model.reload() }
        model.picks.forEach { model.feedback(it, Feedback.SAVED) }
        model.laterSort = LaterSort.SHORTEST
        assertEquals(listOf("short", "long", "unknown"), model.sortedLater.map { it.id.id })
        assertEquals(3600L, model.later.single { it.id == id("long") }.durationSeconds)

        model.markWatched(model.later.single { it.id == id("short") })
        assertEquals(setOf(id("long"), id("unknown")), model.later.map { it.id }.toSet())
        assertTrue(id("short") in model.library.knownVideoIds())
    }

    @Test
    fun `switching profiles shows each profile's own decisions and remembers the choice`() {
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        model.markSelected(VideoState.LIKED)
        assertEquals("Default", model.profile.name)
        assertTrue("already" in model.createProfile("Default")!!)
        assertTrue(model.createProfile("  ")!!.isNotEmpty())

        assertEquals(null, model.createProfile("Evening"))
        assertEquals("Evening", model.profile.name)
        assertEquals(0, model.profileSize)
        assertTrue(model.rows.all { it.state == null })
        assertEquals(listOf("Default" to 2, "Evening" to 0), model.profileList.map { it.first.name to it.second })
        assertEquals("Evening", model.settings.lastProfile)

        model.switchProfile(model.profileList.first().first)
        assertEquals(2, model.profileSize)
    }

    @Test
    fun `deleting a profile switches to another and keeps the library`() {
        model.selectSource(LibrarySource.History)
        model.createProfile("Evening")
        model.deleteProfile()
        assertEquals("Default", model.profile.name)
        assertEquals(listOf("Default"), model.profileList.map { it.first.name })
        assertEquals(2, model.rows.size)
        // The last profile can't be deleted.
        model.deleteProfile()
        assertEquals(listOf("Default"), model.profileList.map { it.first.name })
    }

    @Test
    fun `a background task failing with an Error shows a message instead of crashing`() {
        val broken = object : ExtractorSource by offline {
            override fun video(id: PlatformId): VideoDetails = throw AssertionError("native library missing")
        }
        val other = DesktopModel(dir.resolve("other"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { broken }
        other.profiles.savePicks(other.profile, listOf(StoredPick(1, id("p"), "A pick", null, null, 0.5f, "r", "f", false)))
        runBlocking { other.reload() }
        other.feedback(other.picks.single(), Feedback.LIKE)
        assertTrue("native library missing" in other.message.orEmpty(), other.message)
        assertEquals(setOf(Feedback.LIKE), other.picks.single().feedback)
    }

    @Test
    fun `renaming a topic changes its label and can be undone`() {
        val topic = forage.core.recommend.Cluster(
            0, listOf(forage.core.recommend.ProfileVideo(id("a"), "A", null, floatArrayOf(1f))), floatArrayOf(1f), listOf("cube", "cubing"),
        )
        model.topics = listOf(topic)

        model.renamingTopic = 0
        model.renameTopic(topic, "  Speedcubing  ")
        assertEquals(null, model.renamingTopic)
        assertEquals("Speedcubing", model.topics.single().label)
        model.renameTopic(model.topics.single(), "")
        assertEquals("cube, cubing", model.topics.single().label)
    }

    @Test
    fun `closing folds every change into the database file`() {
        val folder = dir.resolve("closing")
        val plain = DesktopModel(folder, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { offline }
        plain.library.remember(id("z"), "Written just before closing")
        plain.close()
        val wal = folder.resolve("forage.db-wal")
        assertTrue(!wal.exists() || wal.length() == 0L, "pending changes left in ${wal.name}")
        // A fresh open of the file alone sees the change.
        assertEquals("Written just before closing", forage.core.library.LibraryStore(DesktopStorage.openDatabase(folder.resolve("forage.db"))).video(id("z"))?.title)
    }

    @Test
    fun `the interface size is remembered, and automatic when unset`() {
        assertEquals(null, model.uiScale)
        model.changeUiScale(1.5f)
        assertEquals(1.5f, model.effectiveUiScale)
        assertEquals(1.5f, Settings(dir.resolve("settings.properties")).uiScale)
        model.changeUiScale(null)
        assertEquals(null, Settings(dir.resolve("settings.properties")).uiScale)
    }

    @Test
    fun `discover on an empty profile explains what to do`() {
        model.discover()
        assertTrue("Nothing liked yet" in model.message!!, model.message)
        model.selectSource(LibrarySource.History)
        model.selectVisible()
        model.markSelected(VideoState.LIKED)
        model.discover()
        assertTrue("have details yet" in model.message!!, model.message)
        assertEquals(null, model.job)
    }

    private fun cached(v: String, title: String, channel: String? = null, related: List<VideoStub> = emptyList()) = model.cache.put(
        VideoDetails(
            forage.core.source.VideoMetadata(
                id(v), title, "About $title", listOf("tag"), 300, channel?.let(::id), channel?.let { "Channel $it" },
                null, null, null, false,
            ),
            related,
        )
    )

    @Test
    fun `a video page shows cached details, takes likes and saves, and goes back`() {
        cached("b", "Sourdough basics", channel = "UCbread", related = listOf(VideoStub(id("r"), "Rye bread", null, "Brotzeit", 600, false)))
        model.showVideo(id("b"), "Sourdough basics")
        val page = model.videoPage!!
        assertEquals("About Sourdough basics", page.details?.metadata?.description)
        assertEquals(false, page.loading)
        assertEquals(1, page.inLibrary.watchCount)

        model.choose(VideoState.LIKED)
        assertEquals(VideoState.LIKED, model.videoPage!!.state)
        assertEquals(listOf(id("b")), model.profiles.entries(model.profile, VideoState.LIKED).map { it.video })
        model.choose(VideoState.LIKED)
        assertEquals(null, model.videoPage!!.state)
        assertEquals(emptyList(), model.profiles.entries(model.profile))

        model.toggleLater()
        assertTrue(model.videoPage!!.inLibrary.savedForLater)
        assertEquals(listOf(id("b")), model.later.map { it.id })

        assertEquals(listOf(Creator(id("UCbread"), "Channel UCbread")), model.videoPage!!.creators)
        model.toggleFollow(model.videoPage!!.creators.single())
        assertEquals(setOf(id("UCbread")), model.videoPage!!.following)
        assertTrue(model.library.follows(id("UCbread")))

        model.addToPlaylist("Bread", listOf(id("b")))
        assertEquals(listOf("Bread"), model.videoPage!!.inLibrary.playlists)

        // A related video opens on top; back returns to the first, and again to the screen.
        model.showVideo(model.videoPage!!.details!!.related.single())
        assertEquals(listOf(id("b"), id("r")), model.videoStack.map { it.id })
        assertTrue(model.videoPage!!.gone)
        model.closeVideo()
        assertEquals(id("b"), model.videoPage?.id)
        model.closeVideo()
        assertNull(model.videoPage)
    }

    @Test
    fun `the search bar searches the platforms outside the library and the library inside it`() {
        assertEquals(Screen.HOME, model.screen)
        model.searchText = "dovetails"
        model.submitSearch()
        assertTrue(model.searchShown)
        assertTrue(model.hasSearched)
        // A video opened from the results goes back to them.
        model.showVideo(VideoStub(id("s"), "Found", null, null, null, false))
        assertTrue(model.canGoBack)
        model.back()
        assertNull(model.videoPage)
        assertTrue(model.searchShown)
        model.back()
        assertEquals(false, model.searchShown)
        assertEquals(false, model.canGoBack)

        // In the library, searching from the overview looks through every video.
        model.go(Screen.LIBRARY)
        assertEquals(LibraryView.HUB, model.libraryView)
        model.query = "wren"
        model.submitSearch()
        assertEquals(LibraryView.LIST, model.libraryView)
        assertEquals(LibrarySource.All, model.currentSource)
        assertEquals(listOf(id("a")), model.visibleRows.map { it.id })
        model.back()
        assertEquals(LibraryView.HUB, model.libraryView)

        // Switching tabs closes pages and results.
        model.showVideo(id("a"))
        model.go(Screen.DISCOVER)
        assertNull(model.videoPage)
        assertEquals(Screen.DISCOVER, model.screen)
    }

    @Test
    fun `home knows what was watched, liked and followed`() {
        assertEquals(listOf(id("b"), id("a")), model.recentlyWatched.map { it.id })
        assertEquals(2L, model.historySize)
        assertTrue(model.imported)
        model.mark(listOf(id("a")), VideoState.LIKED)
        assertEquals(listOf(id("a")), model.recentlyLiked.map { it.id })
        model.library.follow(id("UCwren"), "Workshop Wren")
        model.unfollow(id("UCwren"))
        assertEquals(emptyList(), model.following)
        model.follow(forage.core.library.FollowedChannel(id("UCwren"), "Workshop Wren"))
        assertEquals(listOf("Workshop Wren"), model.following.map { it.name })
    }

    @Test
    fun `performers on a platform without channels are followed one by one`() {
        val video = PlatformId("pornhub", "ph0001")
        model.cache.put(
            VideoDetails(
                forage.core.source.VideoMetadata(
                    video, "A video", "", emptyList(), 600, null, "Performer A, Performer B", null, null, null, false,
                    extra = mapOf("performers" to "Performer A, Performer B, Third Name"),
                ),
                emptyList(),
            )
        )
        model.showVideo(video, "A video", channelName = "Performer A, Performer B")
        val creators = model.videoPage!!.creators
        assertEquals(listOf("Performer A", "Performer B", "Third Name"), creators.map { it.name })
        assertEquals(PlatformId("pornhub", "pornstar/performer-b"), creators[1].id)
        model.toggleFollow(creators[1])
        assertEquals(setOf(creators[1].id), model.videoPage!!.following)
        assertTrue(model.library.follows(PlatformId("pornhub", "pornstar/performer-b")))
        assertEquals(false, model.library.follows(PlatformId("pornhub", "pornstar/performer-a")))
        model.toggleFollow(creators[1])
        assertEquals(emptySet(), model.videoPage!!.following)
    }

    @Test
    fun `subscriptions look up new uploads, mark them new once, and save them for later`() {
        var uploads = listOf(VideoStub(id("old"), "An older one", id("UCwren"), "Workshop Wren", 600, false, Instant.parse("2026-11-01T00:00:00Z")))
        val feeds = object : ExtractorSource by offline {
            override val capabilities = setOf(Capability.CHANNEL_FEED)
            override fun forPlatform(platform: String) = takeIf { platform == "youtube" }
            override fun channelFeed(channel: PlatformId, name: String?) = forage.core.source.ChannelFeed(uploads, "Workshop Wren", "https://img/wren.jpg")
        }
        val m = DesktopModel(dir.resolve("feeds"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { feeds }
        try {
            m.library.follow(id("UCwren"), "Workshop Wren")
            m.refreshFeeds(force = true)
            // The first look fills the list without calling it new.
            assertEquals(listOf("old"), m.uploads.map { it.video.id.id })
            assertEquals(0, m.newUploads)
            assertEquals("https://img/wren.jpg", m.following.single().avatarUrl)

            // Times are kept to the second; the next look comes a little later.
            Thread.sleep(1_100)
            uploads = listOf(VideoStub(id("new"), "Just out", id("UCwren"), "Workshop Wren", 300, false, Instant.now())) + uploads
            m.refreshFeeds(force = true)
            assertEquals(listOf("new", "old"), m.uploads.map { it.video.id.id })
            assertEquals(1, m.newUploads)
            assertEquals("1 new upload", m.message)

            // Seen once the subscriptions were visited and left.
            m.go(Screen.SUBSCRIPTIONS)
            assertEquals(1, m.newUploads)
            m.go(Screen.HOME)
            assertEquals(0, m.newUploads)

            m.toggleLater(m.uploads.first().video)
            assertEquals(listOf(id("new")), m.later.map { it.id })
            assertEquals("Just out", m.later.single().title)

            // Someone else's video carrying their name: marked not interested, it leaves the list.
            m.mark(listOf(id("old")), VideoState.NOT_INTERESTED)
            assertEquals(listOf("new"), m.uploads.map { it.video.id.id })
        } finally {
            m.close()
        }
    }

    @Test
    fun `the people you follow can be ordered, and video pages look up channel pictures`() {
        var asked = 0
        val pictures = object : ExtractorSource by offline {
            override fun channelPicture(channel: PlatformId): String? = "https://img/${channel.id}.jpg".also { asked++ }
        }
        val m = DesktopModel(dir.resolve("pictures"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { pictures }
        try {
            m.library.follow(id("UCbee"), "Bee")
            Thread.sleep(1_100)
            m.library.follow(id("UCant"), "Ant")
            runBlocking { m.reload() }
            assertEquals(listOf("Ant", "Bee"), m.sortedFollowing.map { it.name })
            m.sortFollowing(FollowSort.FOLLOWED)
            assertEquals(listOf("Ant", "Bee"), m.sortedFollowing.map { it.name })
            m.sortFollowing(FollowSort.NAME)
            assertEquals("NAME", Settings(dir.resolve("pictures/settings.properties")).followSort)

            // A channel nobody follows gets its picture looked up once, then remembered.
            m.cache.put(VideoDetails(forage.core.source.VideoMetadata(id("v1"), "A video", "", emptyList(), 60, id("UCnew"), "New channel", null, null, null, false), emptyList()))
            m.showVideo(id("v1"))
            assertEquals("https://img/UCnew.jpg", m.pictureOf(id("UCnew")))
            m.closeVideo()
            m.showVideo(id("v1"))
            assertEquals(1, asked)
        } finally {
            m.close()
        }
    }

    @Test
    fun `a followed performer named only in a video's tags is credited, and missing pictures say why`() {
        val noPage = object : ExtractorSource by offline {
            override fun channelPicture(channel: PlatformId): String? = throw SourceException.NotAvailable("No profile page found")
        }
        val m = DesktopModel(dir.resolve("tags"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { noPage }
        try {
            val performer = forage.source.pornhub.PornhubSource.performerId("Candy Love")
            m.library.follow(performer, "Candy Love")
            runBlocking { m.reload() }
            val video = PlatformId("pornhub", "ph0002")
            m.cache.put(
                VideoDetails(
                    forage.core.source.VideoMetadata(video, "A video", "", listOf("pov", "candy love"), 600, null, null, null, null, null, false),
                    emptyList(),
                )
            )
            m.showVideo(video, "A video")
            assertEquals(listOf(Creator(performer, "Candy Love")), m.videoPage!!.creators)
            assertEquals(setOf(performer), m.videoPage!!.following)
            assertEquals("no profile page found for them", m.missingPicture(performer))

            m.mark(listOf(video), VideoState.LIKED)
            m.selectSource(LibrarySource.Liked)
            assertEquals("Candy Love", m.rows.single().channelName)
        } finally {
            m.close()
        }
    }

    @Test
    fun `performers can be followed by name, and private videos get related ones from a search`() {
        val site = object : ExtractorSource by offline {
            override val platform = "pornhub"
            override val capabilities = setOf(Capability.SEARCH)
            override fun forPlatform(platform: String) = takeIf { platform == "pornhub" }
            override fun search(query: String) = listOf(
                VideoStub(PlatformId("pornhub", "ph1"), "The video itself", null, null, 60, false),
                VideoStub(PlatformId("pornhub", "ph2"), "Another with $query", null, null, 60, false),
            )
        }
        val m = DesktopModel(dir.resolve("site"), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { site }
        try {
            assertTrue(m.followsPerformers)
            assertEquals("Type a name or paste a link", m.followFromText("  "))
            assertNull(m.followFromText("candy love"))
            assertEquals(listOf("Candy Love"), m.following.map { it.name })
            assertEquals(forage.source.pornhub.PornhubSource.performerId("Candy Love"), m.following.single().channel)

            val video = PlatformId("pornhub", "ph1")
            m.cache.put(
                VideoDetails(
                    forage.core.source.VideoMetadata(video, "The video itself", "", listOf("pov"), 60, null, "Someone", null, null, null, false, extra = mapOf("performers" to "Someone")),
                    emptyList(),
                )
            )
            m.showVideo(video)
            assertEquals(listOf("Another with Someone"), m.videoPage!!.details!!.related.map { it.title })

            m.searchFor("pov")
            assertNull(m.videoPage)
            assertTrue(m.searchShown)
            assertEquals("pov", m.searchText)
        } finally {
            m.close()
        }
    }
}
