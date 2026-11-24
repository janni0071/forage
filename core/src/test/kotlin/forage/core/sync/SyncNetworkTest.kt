package forage.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncNetworkTest {
    private class Device(name: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ForageDatabase.Schema.create(it) }
        val db = ForageDatabase(driver)
        val library = LibraryStore(db)
        val log = SyncLog(driver, db)
        val peers = SyncPeers(driver)
        val label = name
    }

    private val laptop = Device("laptop")
    private val phone = Device("phone")
    private val requests = LinkedBlockingQueue<SyncServer.PairingRequest>()
    private val syncs = LinkedBlockingQueue<Int>()
    private val listener = object : SyncServer.Listener {
        override fun pairingRequested(request: SyncServer.PairingRequest) { requests += request }
        override fun synced(peer: Peer, applied: Int) { syncs += applied }
    }
    private val main = SyncServer.Library(laptop.log, laptop.peers, listener)
    private val server = SyncServer({ "laptop" }, { laptop.log.device }, port = 0, discoveryPort = 0, waitMs = 1500).apply {
        add(main)
        start()
    }
    private val client = SyncClient(phone.log, phone.peers, { "phone" }, discoveryPort = server.discoveryListeningPort)
    private val here = listOf(InetAddress.getLoopbackAddress())

    @AfterTest
    fun stop() = server.stop()

    /** Pairs the phone with the laptop, the laptop's user saying yes (or no) to the code. */
    private fun pair(accept: Boolean = true, into: SyncServer.Library = main, using: SyncClient = client): Boolean {
        server.openPairing(into)
        val computer = using.discover(targets = here).single()
        assertEquals("laptop", computer.name)
        val pairing = using.startPairing(computer)
        var answer = false
        val waiting = thread { answer = using.awaitPairing(pairing, timeoutMs = 10_000) }
        val request = requests.poll(5, TimeUnit.SECONDS)!!
        // The same code on both screens.
        assertEquals(pairing.code, request.code)
        if (accept) server.accept(request) else server.decline(request)
        waiting.join()
        server.closePairing(into)
        return answer
    }

    @Test
    fun `found, paired with matching codes, and synced both ways`() {
        phone.library.saveForLater(PlatformId.youtube("ppppppppppp"))
        laptop.library.saveForLater(PlatformId.youtube("lllllllllll"))
        assertTrue(pair())
        val computer = phone.peers.all().single()
        assertEquals("laptop", computer.name)
        assertEquals("phone", laptop.peers.all().single().name)

        val result = client.sync(computer)
        assertEquals(1, result.applied)
        assertEquals(setOf("ppppppppppp", "lllllllllll"), phone.library.watchLater().map { it.id }.toSet())
        assertEquals(setOf("ppppppppppp", "lllllllllll"), laptop.library.watchLater().map { it.id }.toSet())
        assertEquals(1, syncs.poll(1, TimeUnit.SECONDS))

        // Nothing new: nothing to send either way.
        val again = client.sync(phone.peers.all().single())
        assertEquals(0, again.sent)
        assertEquals(0, again.applied)

        laptop.library.removeFromWatchLater(PlatformId.youtube("ppppppppppp"))
        client.sync(phone.peers.all().single())
        assertEquals(listOf("lllllllllll"), phone.library.watchLater().map { it.id })
    }

    @Test
    fun `a declined pairing pairs nothing`() {
        assertFalse(pair(accept = false))
        assertEquals(emptyList(), phone.peers.all())
        assertEquals(emptyList(), laptop.peers.all())
    }

    @Test
    fun `a computer that forgot the phone says so`() {
        assertTrue(pair())
        laptop.peers.remove(phone.log.device)
        assertFailsWith<UnpairedException> { client.sync(phone.peers.all().single()) }
    }

    @Test
    fun `a change on the computer ends the phone's wait at once, and nothing new lets it time out`() {
        assertTrue(pair())
        client.sync(phone.peers.all().single())
        // Nothing new: the computer answers "nothing yet" once the wait is over.
        val started = System.currentTimeMillis()
        assertFalse(client.waitForChanges(phone.peers.all().single()))
        assertTrue(System.currentTimeMillis() - started >= 1400)

        var changed: Boolean? = null
        val waiting = thread { changed = client.waitForChanges(phone.peers.all().single()) }
        Thread.sleep(300)
        val at = System.currentTimeMillis()
        laptop.library.saveForLater(PlatformId.youtube("nnnnnnnnnnn"))
        waiting.join()
        assertEquals(true, changed)
        assertTrue(System.currentTimeMillis() - at < 1200)
        client.sync(phone.peers.all().single())
        assertEquals(listOf("nnnnnnnnnnn"), phone.library.watchLater().map { it.id })
    }

    @Test
    fun `no pairing without Pair a phone open`() {
        val computer = client.discover(targets = here).single()
        assertFailsWith<SyncException> { client.startPairing(computer) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a private vault pairs and syncs apart from the main library, and while locked answers like none`() {
        val laptopVault = Device("laptop vault")
        val phoneVault = Device("phone vault")
        val vault = SyncServer.Library(laptopVault.log, laptopVault.peers, listener)
        val vaultClient = SyncClient(phoneVault.log, phoneVault.peers, { "phone" }, discoveryPort = server.discoveryListeningPort)
        assertTrue(pair())
        // The vault is unlocked on the laptop: its pairing goes to the vault.
        server.add(vault)
        assertTrue(pair(into = vault, using = vaultClient))
        assertEquals(1, laptopVault.peers.all().size)
        assertEquals(1, laptop.peers.all().size)
        val vaultPeer = phoneVault.peers.all().single()
        assertEquals(laptop.log.device, vaultPeer.host)
        assertTrue(vaultPeer.id != laptop.log.device)

        phoneVault.library.saveForLater(PlatformId.youtube("vvvvvvvvvvv"))
        phone.library.saveForLater(PlatformId.youtube("mmmmmmmmmmm"))
        vaultClient.sync(vaultPeer)
        client.sync(phone.peers.all().single())
        assertEquals(listOf("vvvvvvvvvvv"), laptopVault.library.watchLater().map { it.id })
        assertEquals(listOf("mmmmmmmmmmm"), laptop.library.watchLater().map { it.id })

        // Locked: the vault isn't served, and its phone gets the answer an unknown device gets.
        server.remove(vault)
        assertFailsWith<UnpairedException> { vaultClient.sync(phoneVault.peers.all().single()) }
    }

    @Test
    fun `a phone and a tablet stay in step through the computer`() {
        val tablet = Device("tablet")
        val tabletClient = SyncClient(tablet.log, tablet.peers, { "tablet" }, { "tablet" }, discoveryPort = server.discoveryListeningPort)
        assertTrue(pair())
        assertTrue(pair(using = tabletClient))
        assertEquals(setOf("phone", "tablet"), laptop.peers.all().map { it.kind }.toSet())
        assertEquals("computer", tablet.peers.all().single().kind)

        tablet.library.saveForLater(PlatformId.youtube("ttttttttttt"))
        tabletClient.sync(tablet.peers.all().single())
        client.sync(phone.peers.all().single())
        assertEquals(listOf("ttttttttttt"), phone.library.watchLater().map { it.id })

        // And back: removed on the phone, gone on the tablet.
        phone.library.removeFromWatchLater(PlatformId.youtube("ttttttttttt"))
        client.sync(phone.peers.all().single())
        tabletClient.sync(tablet.peers.all().single())
        assertEquals(emptyList(), tablet.library.watchLater())
    }

    @Test
    fun `either side reaches the other once paired, as two computers or a phone and a tablet do`() {
        // The phone listens too, as a phone or tablet does while Forage is open on it.
        val phoneServer = SyncServer({ "phone" }, { phone.log.device }, { "phone" }, port = 0, discoveryPort = 0).apply {
            add(SyncServer.Library(phone.log, phone.peers, listener))
            start()
        }
        try {
            assertTrue(pair())
            // The laptop knows how to find the phone, though the phone started the pairing.
            assertEquals(phone.log.device, laptop.peers.all().single().host)
            assertEquals(null, laptop.peers.all().single().address)

            // The laptop reaches out this time: it looks for the phone, finds it, and syncs.
            val laptopClient = SyncClient(laptop.log, laptop.peers, { "laptop" }, { "computer" }, { laptop.log.device }, discoveryPort = phoneServer.discoveryListeningPort)
            phone.library.saveForLater(PlatformId.youtube("ppppppppppp"))
            laptop.library.saveForLater(PlatformId.youtube("lllllllllll"))
            val result = laptopClient.sync(laptop.peers.all().single())
            assertEquals(1, result.applied)
            assertEquals(setOf("ppppppppppp", "lllllllllll"), laptop.library.watchLater().map { it.id }.toSet())
            assertEquals(setOf("ppppppppppp", "lllllllllll"), phone.library.watchLater().map { it.id }.toSet())
            assertTrue(laptop.peers.all().single().address != null)
        } finally {
            phoneServer.stop()
        }
    }
}
