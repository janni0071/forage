package forage.desktop

import forage.app.AppModel
import forage.app.Models
import forage.app.Platform
import forage.core.PlatformId
import forage.core.sync.Peer
import forage.core.sync.SyncCrypto
import forage.core.sync.SyncLog
import forage.core.sync.SyncPeers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Two phones-or-tablets with the real app's sync, both open: does each one's change reach the other? */
class SyncDevicesTest {
    private class Mobile(val kindName: String, val nameOf: String) : Platform by DesktopPlatform {
        override val isMobile = true
        override val deviceKind get() = kindName
        override val deviceName get() = nameOf
    }

    private val offline = object : forage.core.source.ExtractorSource {
        override val platform = "youtube"
        override val capabilities = emptySet<forage.core.source.Capability>()
        override fun video(id: PlatformId): forage.core.source.VideoDetails = throw forage.core.source.SourceException.NotAvailable("offline")
        override fun search(query: String): List<forage.core.source.VideoStub> = emptyList()
        override fun watchUrl(id: PlatformId) = ""
    }

    private class Device(val dir: java.io.File, val model: AppModel, val storage: forage.app.Storage) {
        val log = SyncLog(storage.driver, storage.db)
        val peers = SyncPeers(storage.driver)
    }

    private fun device(platform: Platform): Device {
        val dir = Files.createTempDirectory("dev").toFile()
        val storage = DesktopStorage.plain(dir)
        val model = AppModel(dir, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, storage, Models(dir, platform), platform) { offline }
        runBlocking { model.reload() }
        return Device(dir, model, storage)
    }

    private fun waitFor(what: String, seconds: Int = 15, check: () -> Boolean): Long {
        val at = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - at > seconds * 1000L) throw AssertionError("$what: not within $seconds s")
            Thread.sleep(100)
        }
        return System.currentTimeMillis() - at
    }

    @Test
    fun `a phone with a history and other paired devices gets a new tablet's changes`() {
        val phone = device(Mobile("phone", "Pixel"))
        val tablet = device(Mobile("tablet", "Tab"))
        try {
            // The phone's history: a library, synced before with a computer that's off now.
            for (i in 0 until 20) phone.model.library.saveForLater(PlatformId.youtube("h%010d".format(i)))
            phone.log.start()
            val oldKey = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
            phone.peers.save(Peer("pc-one", "pop-os", oldKey, "127.0.0.1", 1, host = "pc-one", kind = "computer", received = 4000, acked = 25))
            phone.peers.save(Peer("pc-two", "pop-os", oldKey, "127.0.0.1", 2, host = "pc-two", kind = "computer", received = 300, acked = 25))

            // The tablet, paired with the phone as pairing leaves them (the phone picked the tablet).
            val key = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
            phone.peers.save(Peer(tablet.log.device, "Tab", key, "127.0.0.1", 9, host = tablet.log.device, kind = "tablet"))
            tablet.peers.save(Peer(phone.log.device, "Pixel", key, host = phone.log.device, kind = "phone"))
            tablet.log.start()

            // Both open: listening and reaching out.
            runBlocking { phone.model.sync!!.start().join(); tablet.model.sync!!.start().join() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope.launch { phone.model.sync!!.runWhileOpen() }
            scope.launch { tablet.model.sync!!.runWhileOpen() }
            waitFor("both listening") { phone.model.sync!!.listeningPort != null && tablet.model.sync!!.listeningPort != null }
            // Where each listens (on a real network, found by its broadcast).
            phone.peers.save(phone.peers.get(tablet.log.device)!!.copy(port = tablet.model.sync!!.listeningPort))
            tablet.peers.save(tablet.peers.get(phone.log.device)!!.copy(address = "127.0.0.1", port = phone.model.sync!!.listeningPort))

            println("first sync: " + waitFor("the phone's library on the tablet", 30) { tablet.model.library.watchLater().size >= 20 } + " ms")

            phone.model.library.saveForLater(PlatformId.youtube("ppppppppppp"))
            println("phone -> tablet: " + waitFor("phone's change on the tablet", 30) { tablet.model.library.watchLater().any { it.id == "ppppppppppp" } } + " ms")

            tablet.model.library.saveForLater(PlatformId.youtube("ttttttttttt"))
            println("tablet -> phone: " + waitFor("tablet's change on the phone", 30) { phone.model.library.watchLater().any { it.id == "ttttttttttt" } } + " ms")
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        } finally {
            phone.model.sync?.stop(); tablet.model.sync?.stop()
            phone.model.close(); tablet.model.close()
            phone.dir.deleteRecursively(); tablet.dir.deleteRecursively()
        }
    }
}
