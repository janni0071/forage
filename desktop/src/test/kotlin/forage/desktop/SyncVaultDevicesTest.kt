package forage.desktop

import forage.app.AppModel
import forage.app.Models
import forage.app.Platform
import forage.core.PlatformId
import forage.core.sync.Peer
import forage.core.sync.SyncCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test

/** Two phones-or-tablets, each with its private vault unlocked and paired with the other's, both open. */
class SyncVaultDevicesTest {
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

    private fun device(platform: Platform): Pair<java.io.File, AppModel> {
        val dir = Files.createTempDirectory("vaultdev").toFile()
        val model = AppModel(dir, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, DesktopStorage.plain(dir), Models(dir, platform), platform,
            privateSourceFactory = { offline }) { offline }
        runBlocking { model.reload() }
        runBlocking { withTimeout(30_000) { model.unlockPrivate("quiet harbour lantern".toCharArray(), create = true)?.join() } }
        return dir to model
    }

    private fun waitFor(what: String, seconds: Int = 30, check: () -> Boolean): Long {
        val at = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - at > seconds * 1000L) throw AssertionError("$what: not within $seconds s")
            Thread.sleep(100)
        }
        return System.currentTimeMillis() - at
    }

    @Test
    fun `vaults on two phones or tablets sync both ways while both are open`() {
        val (phoneDir, phone) = device(Mobile("phone", "Pixel"))
        val (tabletDir, tablet) = device(Mobile("tablet", "Tab"))
        val phoneVault = phone.privateModel!!
        val tabletVault = tablet.privateModel!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Paired, as pairing leaves them; where each listens is filled in once they listen.
            val key = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
            val phoneId = phoneVault.sync!!.deviceIdForTests
            val tabletId = tabletVault.sync!!.deviceIdForTests
            phoneVault.sync!!.pairForTests(Peer(tabletId, "Tab", key, kind = "tablet"))
            tabletVault.sync!!.pairForTests(Peer(phoneId, "Pixel", key, kind = "phone"))
            runBlocking { phoneVault.sync!!.start().join(); tabletVault.sync!!.start().join() }

            // Open on both, as the app runs them: the main library's sync and the vault's.
            for (m in listOf(phone, tablet)) {
                scope.launch { m.sync!!.runWhileOpen() }
                scope.launch { m.privateModel!!.sync!!.runWhileOpen() }
            }
            waitFor("both listening") { phoneVault.sync!!.listeningPort != null && tabletVault.sync!!.listeningPort != null }
            phoneVault.sync!!.pairForTests(Peer(tabletId, "Tab", key, "127.0.0.1", tabletVault.sync!!.listeningPort, kind = "tablet"))
            tabletVault.sync!!.pairForTests(Peer(phoneId, "Pixel", key, "127.0.0.1", phoneVault.sync!!.listeningPort, kind = "phone"))

            phoneVault.library.saveForLater(PlatformId.youtube("ppppppppppp"))
            println("phone vault -> tablet vault: " + waitFor("phone's change in the tablet's vault") { runBlocking { kotlinx.coroutines.withContext(Dispatchers.IO) { tabletVault.library.watchLater() } }.any { it.id == "ppppppppppp" } } + " ms")
            tabletVault.library.saveForLater(PlatformId.youtube("ttttttttttt"))
            println("tablet vault -> phone vault: " + waitFor("tablet's change in the phone's vault") { phoneVault.library.watchLater().any { it.id == "ttttttttttt" } } + " ms")

            // The race: the tablet's vault stopped answering (closed right after pairing). It answers again by itself.
            tabletVault.sync!!.stopAnsweringForTests()
            Thread.sleep(6_000)
            phoneVault.library.saveForLater(PlatformId.youtube("qqqqqqqqqqq"))
            println("after the race: " + waitFor("phone's change after the tablet's vault answered again") { tabletVault.library.watchLater().any { it.id == "qqqqqqqqqqq" } } + " ms")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            for (m in listOf(phone, tablet)) {
                runBlocking { m.lockPrivate()?.join() }
                m.sync?.stop()
                m.close()
            }
            phoneDir.deleteRecursively(); tabletDir.deleteRecursively()
        }
    }
}
