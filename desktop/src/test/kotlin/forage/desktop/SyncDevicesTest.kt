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

    @Test
    fun `watch later stays the same on both through adds and removals on either`() {
        val phone = device(Mobile("phone", "Pixel"))
        val tablet = device(Mobile("tablet", "Tab"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (i in 0 until 10) phone.model.library.saveForLater(PlatformId.youtube("h%010d".format(i)))
            phone.log.start()
            val key = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
            phone.peers.save(Peer(tablet.log.device, "Tab", key, "127.0.0.1", 9, host = tablet.log.device, kind = "tablet"))
            tablet.peers.save(Peer(phone.log.device, "Pixel", key, host = phone.log.device, kind = "phone"))
            tablet.log.start()
            runBlocking { phone.model.sync!!.start().join(); tablet.model.sync!!.start().join() }
            scope.launch { phone.model.sync!!.runWhileOpen() }
            scope.launch { tablet.model.sync!!.runWhileOpen() }
            waitFor("both listening") { phone.model.sync!!.listeningPort != null && tablet.model.sync!!.listeningPort != null }
            phone.peers.save(phone.peers.get(tablet.log.device)!!.copy(port = tablet.model.sync!!.listeningPort))
            tablet.peers.save(tablet.peers.get(phone.log.device)!!.copy(address = "127.0.0.1", port = phone.model.sync!!.listeningPort))
            waitFor("first sync", 30) { tablet.model.library.watchLater().size >= 10 }

            // As the app does it: through the model's own actions. Each step waits until it's done where it
            // was made, then until the other device has the same, in its database and in what it shows.
            fun stub(id: String) = forage.core.source.VideoStub(PlatformId.youtube(id), "Video $id", null, "Channel", 600, false)
            fun stored(d: Device) = d.model.library.watchLater().map { it.id }.toSet()
            fun shown(d: Device) = d.model.later.map { it.id.id }.toSet()
            var expected = stored(phone)
            val steps = listOf(
                Triple("add on phone", phone, "aaaaaaaaaaa"),
                Triple("add on tablet", tablet, "bbbbbbbbbbb"),
                Triple("remove on tablet what the phone added", tablet, "aaaaaaaaaaa"),
                Triple("add it again on phone", phone, "aaaaaaaaaaa"),
                Triple("remove on phone what the tablet added", phone, "bbbbbbbbbbb"),
                Triple("add it again on tablet", tablet, "bbbbbbbbbbb"),
                Triple("remove an old one on tablet", tablet, "h0000000003"),
                Triple("add the old one again on phone", phone, "h0000000003"),
            )
            for ((what, where, id) in steps) {
                // The device it's done on shows the current list first (as a person would see it).
                waitFor("$what: up to date before", 20) { shown(where) == expected }
                where.model.toggleLater(stub(id))
                expected = if (id in expected) expected - id else expected + id
                waitFor("$what: done where it was made", 10) { stored(where) == expected }
                val other = if (where === phone) tablet else phone
                val took = runCatching { waitFor("$what: on the other device", 20) { stored(other) == expected && shown(other) == expected } }
                println("STEP $what: " + took.fold({ "$it ms" }, { "FAILED: there ${stored(other).sorted()} (shown ${shown(other).sorted()}), expected ${expected.sorted()}" }))
                if (took.isFailure) {
                    for ((label, d) in listOf("phone" to phone, "tablet" to tablet)) {
                        val rows = d.storage.driver.executeQuery(null, "SELECT row_key, ts, origin, seq FROM sync_row WHERE tbl = 'watch_later' AND row_key LIKE '%$id%'", { c ->
                            val l = ArrayList<String>(); while (c.next().value) l += "${c.getString(0)} ts=${c.getLong(1)} origin=${c.getString(2)} seq=${c.getLong(3)}"; app.cash.sqldelight.db.QueryResult.Value(l)
                        }, 0).value
                        val state = d.storage.driver.executeQuery(null, "SELECT counter, clock, applying, device FROM sync_state", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value("counter=${c.getLong(0)} clock=${c.getLong(1)} applying=${c.getLong(2)} device=${c.getString(3)}") }, 0).value
                        val peers = d.peers.all().joinToString { "${it.name}: received=${it.received} acked=${it.acked}" }
                        println("DEBUG $label: $rows | $state | $peers")
                    }
                }
                took.getOrThrow()
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            phone.model.sync?.stop(); tablet.model.sync?.stop()
            phone.model.close(); tablet.model.close()
            phone.dir.deleteRecursively(); tablet.dir.deleteRecursively()
        }
    }
}
