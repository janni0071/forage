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
    private val server = SyncServer(laptop.log, laptop.peers, { "laptop" }, object : SyncServer.Listener {
        override fun pairingRequested(request: SyncServer.PairingRequest) { requests += request }
        override fun synced(peer: Peer, applied: Int) { syncs += applied }
    }, port = 0, discoveryPort = 0, waitMs = 1500).apply { start() }
    private val client = SyncClient(phone.log, phone.peers, { "phone" }, discoveryPort = server.discoveryListeningPort)
    private val here = listOf(InetAddress.getLoopbackAddress())

    @AfterTest
    fun stop() = server.stop()

    /** Pairs the phone with the laptop, the laptop's user saying yes (or no) to the code. */
    private fun pair(accept: Boolean = true): Boolean {
        val computer = client.discover(targets = here).single()
        assertEquals("laptop", computer.name)
        val pairing = client.startPairing(computer)
        var answer = false
        val waiting = thread { answer = client.awaitPairing(pairing, timeoutMs = 10_000) }
        val request = requests.poll(5, TimeUnit.SECONDS)!!
        // The same code on both screens.
        assertEquals(pairing.code, request.code)
        assertEquals("phone", request.name)
        if (accept) server.accept(request) else server.decline(request)
        waiting.join()
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
}
