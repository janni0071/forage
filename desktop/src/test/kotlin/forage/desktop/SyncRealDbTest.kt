package forage.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import forage.core.sync.Peer
import forage.core.sync.SyncClient
import forage.core.sync.SyncLog
import forage.core.sync.SyncPeers
import forage.core.sync.SyncServer
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A computer with its real database file (several connections, one per thread) and a phone. */
class SyncRealDbTest {
    private fun run(phoneStarts: Boolean) {
        val dir = Files.createTempDirectory("pc2").toFile()
        val storage = DesktopStorage.plain(dir)
        val pcLog = SyncLog(storage.driver, storage.db)
        val pcPeers = SyncPeers(storage.driver)
        val requests = LinkedBlockingQueue<SyncServer.PairingRequest>()
        val listener = object : SyncServer.Listener {
            override fun pairingRequested(request: SyncServer.PairingRequest) { requests += request }
            override fun synced(peer: Peer, applied: Int) {}
        }
        val pcLibrary = SyncServer.Library(pcLog, pcPeers, listener)
        val pcServer = SyncServer({ "pc2" }, { pcLog.device }, { "computer" }, port = 0, discoveryPort = 0).apply { add(pcLibrary); start() }
        // Something in the library already, as on a used computer.
        LibraryStore(storage.db).saveForLater(PlatformId.youtube("ooooooooooo"))

        val phoneDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ForageDatabase.Schema.create(it) }
        val phoneDb = ForageDatabase(phoneDriver)
        val phoneLog = SyncLog(phoneDriver, phoneDb)
        val phonePeers = SyncPeers(phoneDriver)
        val phoneRequests = LinkedBlockingQueue<SyncServer.PairingRequest>()
        val phoneLibrary = SyncServer.Library(phoneLog, phonePeers, object : SyncServer.Listener {
            override fun pairingRequested(request: SyncServer.PairingRequest) { phoneRequests += request }
            override fun synced(peer: Peer, applied: Int) {}
        })
        val phoneServer = SyncServer({ "phone" }, { phoneLog.device }, { "phone" }, port = 0, discoveryPort = 0).apply { add(phoneLibrary); start() }
        val phoneClient = SyncClient(phoneLog, phonePeers, { "phone" }, { "phone" }, discoveryPort = pcServer.discoveryListeningPort)
        val here = listOf(InetAddress.getLoopbackAddress())
        try {
            if (phoneStarts) {
                pcServer.openPairing(pcLibrary)
                val pairing = phoneClient.startPairing(phoneClient.discover(targets = here).single())
                var ok = false
                val t = thread { ok = phoneClient.awaitPairing(pairing, 10_000) }
                pcServer.accept(requests.poll(5, TimeUnit.SECONDS)!!)
                t.join()
                assertTrue(ok)
            } else {
                phoneServer.openPairing(phoneLibrary)
                val pcClient = SyncClient(pcLog, pcPeers, { "pc2" }, { "computer" }, discoveryPort = phoneServer.discoveryListeningPort)
                val pairing = pcClient.startPairing(pcClient.discover(targets = here).single())
                var ok = false
                val t = thread { ok = pcClient.awaitPairing(pairing, 10_000) }
                phoneServer.accept(phoneRequests.poll(5, TimeUnit.SECONDS)!!)
                t.join()
                assertTrue(ok)
            }
            // The phone reaches out, as it does to a computer.
            phoneClient.sync(phonePeers.all().single())
            val phoneLibraryStore = LibraryStore(phoneDb)
            assertTrue(phoneLibraryStore.watchLater().any { it.id == "ooooooooooo" }, "what was there before pairing")

            // The phone's change reaches the computer…
            phoneLibraryStore.saveForLater(PlatformId.youtube("ppppppppppp"))
            phoneClient.sync(phonePeers.all().single())
            assertTrue(LibraryStore(storage.db).watchLater().any { it.id == "ppppppppppp" }, "phone to computer")

            // …and the computer's, made on another thread (its UI), reaches the phone.
            thread { LibraryStore(storage.db).saveForLater(PlatformId.youtube("ccccccccccc")) }.join()
            phoneClient.sync(phonePeers.all().single())
            assertTrue(phoneLibraryStore.watchLater().any { it.id == "ccccccccccc" }, "computer to phone: " + phoneLibraryStore.watchLater().map { it.id })
        } finally {
            pcServer.stop()
            phoneServer.stop()
            storage.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `computer's changes reach the phone, the phone having picked the computer`() = run(phoneStarts = true)

    @Test
    fun `computer's changes reach the phone, the computer having picked the phone`() = run(phoneStarts = false)

    @Test
    fun `a computer whose library was copied from another still sends the phone its changes, and the two are noticed`() {
        val first = Files.createTempDirectory("pc1").toFile()
        val second = Files.createTempDirectory("pc2").toFile()
        val listener = object : SyncServer.Listener {
            override fun pairingRequested(request: SyncServer.PairingRequest) {}
            override fun synced(peer: Peer, applied: Int) {}
        }
        // The phone, paired with the first computer, as pairing leaves them.
        val phoneDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ForageDatabase.Schema.create(it) }
        val phoneDb = ForageDatabase(phoneDriver)
        val phoneLog = SyncLog(phoneDriver, phoneDb)
        val phonePeers = SyncPeers(phoneDriver)
        var pc1 = DesktopStorage.plain(first)
        var pc1Log = SyncLog(pc1.driver, pc1.db)
        val key = forage.core.sync.SyncCrypto.pair(forage.core.sync.SyncCrypto.newKeyPair(), forage.core.sync.SyncCrypto.newKeyPair().public).key
        SyncPeers(pc1.driver).save(Peer(phoneLog.device, "phone", key, kind = "phone"))
        pc1Log.start()
        phoneLog.start()
        // The library copied to the second computer, identity and all.
        pc1.close()
        java.io.File(first, "forage.db").copyTo(java.io.File(second, "forage.db"))
        pc1 = DesktopStorage.plain(first)
        pc1Log = SyncLog(pc1.driver, pc1.db)
        val pc1Server = SyncServer({ "pc1" }, { pc1Log.device }, port = 0, discoveryPort = 0).apply { add(SyncServer.Library(pc1Log, SyncPeers(pc1.driver), listener)); start() }
        phonePeers.save(Peer(pc1Log.device, "pc1", key, "127.0.0.1", pc1Server.listeningPort, host = pc1Log.device, kind = "computer"))
        val client = SyncClient(phoneLog, phonePeers, { "phone" }, { "phone" })
        // The first computer moves on: many changes the copy never had.
        val pc1Library = LibraryStore(pc1.db)
        for (i in 0 until 30) pc1Library.saveForLater(PlatformId.youtube("a%010d".format(i)))
        client.sync(phonePeers.all().single())
        assertEquals(31, LibraryStore(phoneDb).watchLater().size + 1)
        pc1Server.stop()

        // The second computer, with the copy: the phone finds it under the same identity.
        val pc2 = DesktopStorage.plain(second)
        val pc2Log = SyncLog(pc2.driver, pc2.db)
        assertEquals(pc1Log.device, pc2Log.device)
        val pc2Server = SyncServer({ "pc2" }, { pc2Log.device }, port = 0, discoveryPort = 0).apply { add(SyncServer.Library(pc2Log, SyncPeers(pc2.driver), listener)); start() }
        try {
            phonePeers.save(phonePeers.all().single().copy(address = "127.0.0.1", port = pc2Server.listeningPort))
            LibraryStore(pc2.db).saveForLater(PlatformId.youtube("ccccccccccc"))
            client.sync(phonePeers.all().single())
            assertTrue(LibraryStore(phoneDb).watchLater().any { it.id == "ccccccccccc" }, "the copy's change reached the phone")

            // Both answering at once: the phone notices two devices under one identity.
            val again = SyncServer({ "pc1" }, { pc1Log.device }, port = 0, discoveryPort = 0).apply { add(SyncServer.Library(pc1Log, SyncPeers(pc1.driver), listener)); start() }
            try {
                val looking = SyncClient(phoneLog, phonePeers, { "phone" }, { "phone" }, discoveryPort = pc2Server.discoveryListeningPort)
                looking.discover(targets = listOf(InetAddress.getLoopbackAddress()))
                val looking2 = SyncClient(phoneLog, phonePeers, { "phone" }, { "phone" }, discoveryPort = again.discoveryListeningPort)
                // Two searches on two ports here stand in for one search answered twice on a real network.
                val both = looking.discover(targets = listOf(InetAddress.getLoopbackAddress())) + looking2.discover(targets = listOf(InetAddress.getLoopbackAddress()))
                assertEquals(2, both.size)
                assertEquals(1, both.map { it.id }.toSet().size)
            } finally {
                again.stop()
            }
        } finally {
            pc2Server.stop()
            pc1.close()
            pc2.close()
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }
}
