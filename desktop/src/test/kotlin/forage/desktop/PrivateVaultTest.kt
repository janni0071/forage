package forage.desktop

import forage.app.*
import forage.app.ui.*

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
        override val capabilities = setOf(Capability.SEARCH)
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
        vault.profiles.mark(vault.profile, listOf(video), VideoState.LIKED)
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
    fun `the private vault imports a catalogue and can remove it`() {
        val zip = java.io.File(dir, "dump.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("db.csv"))
            val lines = (1..5).map { i ->
                """<iframe src="https://www.pornhub.com/embed/key$i"></iframe>|https://img.example/$i.jpg|https://img.example/$i-1.jpg|Video $i|tag a;tag b|Cat|""" +
                    "|600|5000|9|1|https://img.example/$i-big.jpg"
            }
            z.write(lines.joinToString("\n").toByteArray())
            z.closeEntry()
        }
        assertEquals(false, model.offersCatalogue)
        unlock("quiet harbour lantern", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        assertTrue(vault.offersCatalogue)
        assertNull(vault.catalogueInfo)
        vault.importCatalogue(PickedFile(zip.name, zip.length()) { zip.inputStream() })
        runBlocking { withTimeout(20_000) { while (vault.job != null) kotlinx.coroutines.delay(20) } }
        assertTrue(vault.catalogueInfo.orEmpty().startsWith("5 videos, imported"), "${vault.catalogueInfo} / ${vault.message}")
        val made = java.io.File(dir, "catalogues/pornhub.db")
        assertTrue(made.isFile)

        // A catalogue made on another device is taken over as it is.
        val elsewhere = java.io.File(dir, "from-laptop.db").also { made.copyTo(it) }
        vault.removeCatalogue()
        runBlocking { withTimeout(10_000) { while (vault.catalogueInfo != null) kotlinx.coroutines.delay(20) } }
        vault.importCatalogue(PickedFile(elsewhere.name, elsewhere.length()) { elsewhere.inputStream() })
        runBlocking { withTimeout(20_000) { while (vault.job != null) kotlinx.coroutines.delay(20) } }
        assertTrue(vault.catalogueInfo.orEmpty().startsWith("5 videos, imported"), "${vault.catalogueInfo} / ${vault.message}")
        assertTrue(vault.message.orEmpty().startsWith("Took over the catalogue"), vault.message)
        vault.removeCatalogue()
        runBlocking { withTimeout(10_000) { while (vault.catalogueInfo != null) kotlinx.coroutines.delay(20) } }
        assertTrue(!java.io.File(dir, "catalogues/pornhub.db").exists())
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

    @Test
    fun `a new vault is welcomed, searches its own platform, and uses YouTube only when chosen`() {
        unlock("quiet harbour lantern", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        // Created just now: its own welcome, from its first step.
        assertEquals(forage.app.WelcomeStep.VAULT_HELLO, vault.welcome)
        vault.welcomeTo(forage.app.WelcomeStep.VAULT_PLATFORMS)
        vault.back()
        assertEquals(forage.app.WelcomeStep.VAULT_LOOK, vault.welcome)
        // Without YouTube: only the vault's platform, nothing to choose.
        assertEquals(false, vault.vaultYouTube)
        assertEquals(listOf("pornhub"), vault.searchPlatforms.map { it.first })
        vault.changeVaultYouTube(true)
        // With it: the vault's platform still first, so it's what's searched until another is chosen.
        assertEquals(listOf("pornhub", "youtube"), vault.searchPlatforms.map { it.first })
        assertEquals(null, vault.searchPlatform)
        vault.searchPlatform = "youtube"
        vault.changeDiscoverPlatform("pornhub")
        vault.closeWelcome()
        assertEquals(null, vault.welcome)
        lock()
        // Kept inside the vault: YouTube on, the last platform searched, Discover's choice; no welcome again.
        unlock("quiet harbour lantern")
        val again = assertNotNull(model.privateModel, model.privateError)
        assertEquals(null, again.welcome)
        assertEquals(true, again.vaultYouTube)
        assertEquals("youtube", again.searchPlatform)
        assertEquals("pornhub", again.discoverPlatform)
        // Turned off again: YouTube's gone from search, and Discover goes back to all (that is, the vault's own).
        again.changeVaultYouTube(false)
        assertEquals(null, again.searchPlatform)
        assertEquals(null, again.discoverPlatform)
        lock()
    }

    @Test
    fun `a vault from before the choice keeps YouTube if it has YouTube videos`() {
        unlock("654321", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        vault.library.remember(video, "Something on YouTube")
        vault.profiles.mark(vault.profile, listOf(video), VideoState.LIKED)
        // As a vault made before the setting existed: nothing decided yet.
        vault.library.setPreference("vault.youtube", "")
        lock()
        unlock("654321")
        val again = assertNotNull(model.privateModel, model.privateError)
        assertEquals(true, again.vaultYouTube)
        lock()
    }

    @Test
    fun `the vault wears Neon until another look is chosen, and keeps it inside the vault`() {
        assertEquals(null, model.vaultLook)
        unlock("123456", create = true)
        val vault = assertNotNull(model.privateModel, model.privateError)
        assertEquals(forage.app.VaultLook.NEON, vault.vaultLook)
        vault.changeVaultLook(forage.app.VaultLook.NOIR)
        vault.hideGuide()
        lock()
        unlock("123456")
        val again = assertNotNull(model.privateModel, model.privateError)
        assertEquals(forage.app.VaultLook.NOIR, again.vaultLook)
        assertEquals(true, again.guideHidden)
        // The normal library's own guide is untouched.
        assertEquals(false, model.guideHidden)
        lock()
    }

    @Test
    fun `the vault pairs and syncs on its own while unlocked, and is refused once locked`() {
        unlock("quiet harbour lantern", create = true)
        val vault = assertNotNull(model.privateModel)
        val vaultSync = assertNotNull(vault.sync)
        assertTrue(vaultSync.vault)
        vaultSync.openPairing()

        // The phone's vault, as its sync sees it.
        val phoneDriver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
            .also { forage.core.db.ForageDatabase.Schema.create(it) }
        val phoneDb = forage.core.db.ForageDatabase(phoneDriver)
        val phoneLog = forage.core.sync.SyncLog(phoneDriver, phoneDb)
        val phonePeers = forage.core.sync.SyncPeers(phoneDriver)
        val client = forage.core.sync.SyncClient(phoneLog, phonePeers, { "phone" })
        try {
            val computer = client.discover(targets = listOf(java.net.InetAddress.getLoopbackAddress())).single()
            val pairing = client.startPairing(computer)
            var accepted = false
            val waiting = kotlin.concurrent.thread { accepted = client.awaitPairing(pairing, timeoutMs = 15_000) }
            val until = System.currentTimeMillis() + 10_000
            while (vaultSync.request == null && System.currentTimeMillis() < until) Thread.sleep(50)
            assertEquals(pairing.code, assertNotNull(vaultSync.request).code)
            vaultSync.answer(true)
            waiting.join()
            assertTrue(accepted)
            // Paired into the vault, not the main library.
            assertEquals(1, vaultSync.devices.size)
            assertEquals(0, assertNotNull(model.sync).devices.size)

            forage.core.library.LibraryStore(phoneDb).saveForLater(PlatformId.youtube("vvvvvvvvvvv"))
            client.sync(phonePeers.all().single())
            assertEquals(listOf("vvvvvvvvvvv"), vault.library.watchLater().map { it.id })
            assertEquals(emptyList(), model.library.watchLater().map { it.id }.filter { it == "vvvvvvvvvvv" })

            // Locked: the computer answers the vault's phone as it would an unknown device.
            lock()
            kotlin.test.assertFailsWith<forage.core.sync.UnpairedException> { client.sync(phonePeers.all().single()) }
        } finally {
            model.sync?.stop()
        }
    }

    @Test
    fun `ad blocking is on until turned off, and stays as set inside the vault`() {
        unlock("quiet harbour lantern", create = true)
        var vault = assertNotNull(model.privateModel)
        assertTrue(vault.blockAds)
        assertTrue(AdBlocking.enabled)
        vault.changeBlockAds(false)
        assertEquals(false, AdBlocking.enabled)
        lock()
        unlock("quiet harbour lantern")
        vault = assertNotNull(model.privateModel)
        assertEquals(false, vault.blockAds)
        assertEquals(false, AdBlocking.enabled)
        vault.changeBlockAds(true)

        // What the player asked, noted: kept out or let through.
        AdBlocking.clear()
        assertTrue(AdBlocking.check("https://ads.trafficjunky.net/ads?zone=1"))
        assertEquals(false, AdBlocking.check("https://ev-h.phncdn.com/hls/master.m3u8"))
        assertEquals(false, AdBlocking.check("https://ev-h.phncdn.com/hls/seg1.ts"))
        val servers = AdBlocking.servers()
        assertEquals(listOf("ads.trafficjunky.net", "ev-h.phncdn.com"), servers.map { it.host })
        assertEquals(2, servers[1].asked)
        assertEquals(1, AdBlocking.blockedCount)
        AdBlocking.clear()
    }
}
