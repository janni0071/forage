package forage.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.app.AppModel
import forage.app.Models
import forage.app.Platform
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import forage.core.sync.Peer
import forage.core.sync.SyncCrypto
import forage.core.sync.SyncLog
import forage.core.sync.SyncPeers
import forage.core.sync.SyncServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** The phone's real sync loop against a computer: how long a change takes to cross, each way. */
class SyncLatencyTest {
    private object Phone : Platform by DesktopPlatform {
        override val isMobile = true
    }

    private val offline = object : forage.core.source.ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<forage.core.source.Capability>()
        override fun video(id: PlatformId): forage.core.source.VideoDetails = throw forage.core.source.SourceException.NotAvailable("offline")
        override fun search(query: String): List<forage.core.source.VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
    }

    @Test
    fun `changes cross within seconds, both ways, while the phone app is open`() {
        // The computer: its library, log and server.
        val laptopDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ForageDatabase.Schema.create(it) }
        val laptopDb = ForageDatabase(laptopDriver)
        val laptopLog = SyncLog(laptopDriver, laptopDb)
        val laptopPeers = SyncPeers(laptopDriver)
        val server = SyncServer(laptopLog, laptopPeers, { "laptop" }, object : SyncServer.Listener {
            override fun pairingRequested(request: SyncServer.PairingRequest) {}
            override fun synced(peer: Peer, applied: Int) {}
        }, port = 0, discoveryPort = 0).apply { start() }

        // The phone: the app's model, with its sync controller.
        val dir = Files.createTempDirectory("phone").toFile()
        val phone = AppModel(dir, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, DesktopStorage.plain(dir), Models(dir, Phone), Phone) { offline }
        try {
            runBlocking { phone.reload() }
            // A second connection to the phone's database, as the pairing writes it.
            val phoneDriver = JdbcSqliteDriver("jdbc:sqlite:${java.io.File(dir, "forage.db").path}")
            val phoneLog = SyncLog(phoneDriver, ForageDatabase(phoneDriver))
            // Paired as the pairing leaves them.
            val key = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
            laptopPeers.save(Peer(phoneLog.device, "phone", key))
            laptopLog.start()
            SyncPeers(phoneDriver).save(Peer(laptopLog.device, "laptop", key, "127.0.0.1", server.listeningPort))
            phoneLog.start()
            runBlocking { phone.sync!!.start().join() }
            val open = phone.sync!!.whileOpen()
            Thread.sleep(1500)

            // On the computer: does it reach the phone at once?
            val laptopLibrary = LibraryStore(laptopDb)
            var at = System.currentTimeMillis()
            laptopLibrary.saveForLater(PlatformId.youtube("lllllllllll"))
            while (phone.library.watchLater().none { it.id == "lllllllllll" } && System.currentTimeMillis() - at < 15_000) Thread.sleep(50)
            val toPhone = System.currentTimeMillis() - at
            println("computer -> phone: $toPhone ms")
            assertTrue(toPhone < 3_000, "took $toPhone ms")

            // On the phone: does it reach the computer at once?
            at = System.currentTimeMillis()
            phone.library.saveForLater(PlatformId.youtube("ppppppppppp"))
            while (laptopLibrary.watchLater().none { it.id == "ppppppppppp" } && System.currentTimeMillis() - at < 15_000) Thread.sleep(50)
            val toComputer = System.currentTimeMillis() - at
            println("phone -> computer: $toComputer ms")
            assertTrue(toComputer < 4_000, "took $toComputer ms")

            // And again, a second change from the computer, after the first round.
            Thread.sleep(500)
            at = System.currentTimeMillis()
            laptopLibrary.saveForLater(PlatformId.youtube("mmmmmmmmmmm"))
            while (phone.library.watchLater().none { it.id == "mmmmmmmmmmm" } && System.currentTimeMillis() - at < 15_000) Thread.sleep(50)
            val again = System.currentTimeMillis() - at
            println("computer -> phone, again: $again ms")
            assertTrue(again < 3_000, "took $again ms")
            open.cancel()
        } finally {
            // The computer goes first, so the phone's waiting request ends now rather than in half a minute.
            server.stop()
            Thread.sleep(500)
            phone.close()
            dir.deleteRecursively()
        }
    }
}
