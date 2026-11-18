package forage.source.pornhub

import forage.core.PlatformId
import forage.core.source.SourceException
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Responses shaped like the API's, with placeholder content. */
class PornhubSourceTest {
    private fun video(key: String, title: String = "Sample video $key") = """
        {"duration":"10:30","views":1200,"video_id":"abc","rating":90.5,"ratings":10,"title":"$title",
         "url":"https://www.pornhub.com/view_video.php?viewkey=$key",
         "default_thumb":"https://img.example/$key.jpg?hdnea=1","thumb":"https://img.example/$key-t.jpg",
         "publish_date":"2025-11-03 08:24:34",
         "thumbs":[{"size":"320x240","width":"320","height":"240","src":"https://img.example/$key-1.jpg"}],
         "tags":[{"tag_name":"tag one"},{"tag_name":"Tag Two"}],
         "pornstars":[{"pornstar_name":"Performer A"}],
         "categories":[{"category":"Category X"},{"category":"tag one"}],
         "segment":"straight"}
    """.trimIndent()

    private class FakeHttp(val answer: (URI) -> PornhubSource.Http.Response) : PornhubSource.Http {
        val asked = ArrayList<URI>()
        override fun get(uri: URI): PornhubSource.Http.Response = answer(uri).also { asked += uri }
    }

    private fun source(http: FakeHttp, clock: () -> Instant = { Instant.parse("2026-01-01T00:00:00Z") }) =
        PornhubSource(http, pause = Duration.ZERO, clock = clock, sleep = {})

    private fun ok(body: String) = PornhubSource.Http.Response(200, body)

    @Test
    fun `search results become stubs with platform ids`() {
        val http = FakeHttp { ok("""{"videos":[${video("ph1111aaaa")},${video("2222bbbb", "A &amp; B")}]}""") }
        val stubs = source(http).search("tag one category")
        assertEquals(listOf(PlatformId("pornhub", "ph1111aaaa"), PlatformId("pornhub", "2222bbbb")), stubs.map { it.id })
        assertEquals("A & B", stubs[1].title)
        assertEquals(630L, stubs[0].durationSeconds)
        assertEquals(Instant.parse("2025-11-03T08:24:34Z"), stubs[0].uploadedAt)
        assertEquals("Performer A", stubs[0].channelName)
        assertEquals("/webmasters/search", http.asked.single().path)
        assertEquals("search=tag+one+category", http.asked.single().rawQuery)
    }

    @Test
    fun `no results is an empty list, not an error`() {
        val http = FakeHttp { ok("""{"code":"2001","message":"No videos found!","example":"x"}""") }
        assertEquals(emptyList(), source(http).search("nothing"))
    }

    @Test
    fun `video lookup keeps categories and tags once each, and names the performers as creators`() {
        val http = FakeHttp { ok("""{"video":${video("ph1111aaaa")}}""") }
        val details = source(http).video(PlatformId("pornhub", "ph1111aaaa"))
        val m = details.metadata
        assertEquals(listOf("Category X", "tag one", "Tag Two"), m.tags)
        assertEquals("Performer A", m.channelName)
        assertEquals("Performer A", m.extra["performers"])
        assertTrue(details.related.isEmpty())
        assertEquals("id=ph1111aaaa", http.asked.single().rawQuery)
    }

    @Test
    fun `a missing video is reported as gone`() {
        val http = FakeHttp { ok("""{"code":"2002","message":"No video with this ID."}""") }
        assertFailsWith<SourceException.NotAvailable> { source(http).video(PlatformId("pornhub", "ph0000zzzz")) }
    }

    @Test
    fun `refusals and web pages are reported as blocked`() {
        assertFailsWith<SourceException.Blocked> {
            source(FakeHttp { PornhubSource.Http.Response(451, "") }).search("x")
        }
        assertFailsWith<SourceException.Blocked> {
            source(FakeHttp { ok("<!DOCTYPE html><html>verify your age</html>") }).search("x")
        }
        val other = assertFailsWith<SourceException> { source(FakeHttp { PornhubSource.Http.Response(500, "") }).search("x") }
        assertTrue(other !is SourceException.Blocked)
    }

    @Test
    fun `thumbnails come from recent results and are looked up again once old`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val http = FakeHttp { uri ->
            if (uri.path.endsWith("search")) ok("""{"videos":[${video("ph1111aaaa")}]}""")
            else ok("""{"video":${video("ph1111aaaa")}}""")
        }
        val source = source(http) { now }
        val id = PlatformId("pornhub", "ph1111aaaa")
        source.search("x")
        assertEquals("https://img.example/ph1111aaaa.jpg?hdnea=1", source.thumbnailUrl(id))
        assertEquals(1, http.asked.size)
        assertNull(source.thumbnailUrl(id, large = true))
        // The alternate image and the preview frames follow, to try when the first won't load.
        assertEquals(
            listOf("https://img.example/ph1111aaaa.jpg?hdnea=1", "https://img.example/ph1111aaaa-t.jpg", "https://img.example/ph1111aaaa-1.jpg"),
            source.thumbnailUrls(id),
        )

        now += Duration.ofHours(7)
        assertEquals("https://img.example/ph1111aaaa.jpg?hdnea=1", source.thumbnailUrl(id))
        assertEquals("/webmasters/video_by_id", http.asked.last().path)
    }

    @Test
    fun `links are recognised on the site's domains only`() {
        val id = PlatformId("pornhub", "ph5f1a2b3c4d")
        assertEquals(id, PornhubSource.parseLink("https://www.pornhub.com/view_video.php?viewkey=ph5f1a2b3c4d"))
        assertEquals(id, PornhubSource.parseLink("https://de.pornhub.com/view_video.php?pkey=1&viewkey=ph5f1a2b3c4d"))
        assertEquals(id, PornhubSource.parseLink("https://www.pornhub.com/embed/ph5f1a2b3c4d"))
        assertEquals(id, PornhubSource.parseLink("pornhub:ph5f1a2b3c4d"))
        assertNull(PornhubSource.parseLink("https://example.com/view_video.php?viewkey=ph5f1a2b3c4d"))
        assertNull(PornhubSource.parseLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(PornhubSource.parseLink("https://www.pornhub.com/categories"))
    }

    @Test
    fun `upload dates come from image addresses when nothing states them`() {
        assertEquals(
            Instant.parse("2025-10-05T00:00:00Z"),
            PornhubSource.uploadDateFromImage("https://pix-fl.phncdn.com/c6371/videos/202510/05/25069335/original/x.jpg?hdnea=1"),
        )
        assertEquals(
            Instant.parse("2019-01-31T00:00:00Z"),
            PornhubSource.uploadDateFromImage("https://ei.phncdn.com/videos/201901/31/123456/original/(m=eaAaGwObaaaa)(mh=abc)1.jpg"),
        )
        assertNull(PornhubSource.uploadDateFromImage("https://ei.phncdn.com/videos/201913/45/1/x.jpg"))
        assertNull(PornhubSource.uploadDateFromImage("https://img.example/a.jpg"))
    }

    @Test
    fun `preview frames are worked out from a numbered image address`() {
        val frames = PornhubSource.framesFromImage("https://ei.phncdn.com/videos/201901/31/1/original/(m=eaAaGwObaaaa)(mh=a1b2)12.jpg")
        assertEquals(16, frames.size)
        assertEquals("https://ei.phncdn.com/videos/201901/31/1/original/(m=eaAaGwObaaaa)(mh=a1b2)1.jpg", frames.first())
        assertEquals("https://ei.phncdn.com/videos/201901/31/1/original/(m=eaAaGwObaaaa)(mh=a1b2)16.jpg", frames.last())
        val signed = PornhubSource.framesFromImage("https://pix.example/videos/202510/05/2/original/x.mov/plain/(m=a)(mh=b)3.jpg?hdnea=st=1~exp=2")
        assertEquals("https://pix.example/videos/202510/05/2/original/x.mov/plain/(m=a)(mh=b)7.jpg?hdnea=st=1~exp=2", signed[6])
        assertEquals(emptyList(), PornhubSource.framesFromImage("https://img.example/a.jpg"))
    }

    @Test
    fun `the API's preview frames come from the last answer, or a lookup`() {
        val http = FakeHttp { uri ->
            if (uri.path.endsWith("search")) ok("""{"videos":[${video("ph1111aaaa")}]}""") else ok("""{"video":${video("ph2222bbbb")}}""")
        }
        val source = source(http)
        source.search("x")
        assertEquals(listOf("https://img.example/ph1111aaaa-1.jpg"), source.previewFrames(PlatformId("pornhub", "ph1111aaaa")))
        assertEquals(1, http.asked.size)
        assertEquals(listOf("https://img.example/ph2222bbbb-1.jpg"), source.previewFrames(PlatformId("pornhub", "ph2222bbbb")))
        assertEquals("/webmasters/video_by_id", http.asked.last().path)
    }

    @Test
    fun `durations and dates`() {
        assertEquals(3723L, PornhubSource.parseDuration("1:02:03"))
        assertEquals(45L, PornhubSource.parseDuration("0:45"))
        assertNull(PornhubSource.parseDuration("soon"))
        assertNull(PornhubSource.parseDate("yesterday"))
    }

    @Test
    fun `requests wait for each other`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val waits = ArrayList<Duration>()
        val source = PornhubSource(FakeHttp { ok("""{"videos":[]}""") }, Duration.ofMillis(400), { now }, { waits += it; now += it })
        source.search("a")
        source.search("b")
        assertEquals(listOf(Duration.ofMillis(400)), waits)
    }
}
