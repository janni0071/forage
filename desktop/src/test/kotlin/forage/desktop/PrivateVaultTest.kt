package forage.desktop

import forage.core.PlatformId
import forage.core.profile.VideoState
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.SourceException
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole private vault flow, with the real key derivation (so each unlock takes a moment). */
class PrivateVaultTest {
    private val dir = Files.createTempDirectory("forage-private-flow").toFile()
    private val offline = object : ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<Capability>()
        override fun video(id: PlatformId): VideoDetails = throw SourceException.NotAvailable("offline")
        override fun search(query: String): List<VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
    }
    /** The real plugin, answering every lookup as gone without touching the network. */
    private val privatePlatform = forage.source.pornhub.PornhubSource(
        http = { forage.source.pornhub.PornhubSource.Http.Response(200, """{"code":"2002","message":"No video with this ID."}""") },
        sleep = {},
    )
    private val model = DesktopModel(
        dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
        privateSourceFactory = { forage.core.source.Sources(offline, privatePlatform) },
    ) { offline }
    private val video = PlatformId.youtube("aaaaaaaaaa1")

    @AfterTest
    fun cleanUp() {
        model.close()
        dir.deleteRecursively()
    }

    private fun unlock(pin: String, create: Boolean = false) = runBlocking { withTimeout(30_000) { model.unlockPrivate(pin.toCharArray(), create)?.join() } }
    private fun lock() = runBlocking { withTimeout(30_000) { model.lockPrivate()?.join() } }

    @Test
    fun `create, use, lock and unlock a private vault`() {
        unlock("quiet harbour lantern", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        assertTrue(vault.isPrivate)
        assertEquals("Private", vault.profile.name)
        assertTrue(vault.libraryEmpty)

        vault.library.remember(video, "A very private title")
        vault.profiles.mark(vault.profile, listOf(video), VideoState.INCLUDED)
        vault.settings.lastProfile = "Private"
        lock()
        assertNull(model.privateModel)

        // Nothing readable on disk: no title, no settings entry, no thumbnails folder from the vault.
        val readable = dir.walkTopDown().filter { it.isFile }.map { it.readBytes().decodeToString(throwOnInvalidSequence = false) }
        assertTrue(readable.none { "very private" in it || "Private" in it })
        // The normal vault doesn't see it.
        assertEquals(null, model.library.video(video))

        unlock("quiet harbour lantern")
        val again = assertNotNull(model.privateModel, model.privateError)
        assertEquals("A very private title", again.library.video(video)?.title)
        assertEquals(listOf(video), again.profiles.entries(again.profile).map { it.video })
        lock()
    }

    @Test
    fun `only the private vault takes links from the private platform`() {
        val link = "https://www.pornhub.com/view_video.php?viewkey=ph0000000001"
        assertNotNull(model.addByLink(link))
        unlock("quiet harbour lantern", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        assertEquals("youtube and Pornhub", vault.linkPlatforms)
        assertNull(vault.addByLink(link))
        // The private vault works on its own thread.
        runBlocking { withTimeout(10_000) { while (vault.job != null) kotlinx.coroutines.delay(20) } }
        assertEquals(listOf(PlatformId("pornhub", "ph0000000001")), vault.profiles.entries(vault.profile).map { it.video })
        lock()
    }

    @Test
    fun `a wrong PIN and no vault get the same answer`() {
        unlock("nothing here yet")
        assertEquals("Nothing unlocks with that PIN", model.privateError)
        unlock("quiet harbour lantern", create = true)
        lock()
        unlock("quiet harbour lanterns")
        assertNull(model.privateModel)
        assertEquals("Nothing unlocks with that PIN", model.privateError)
    }
}
