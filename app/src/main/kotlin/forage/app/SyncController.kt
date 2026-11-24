package forage.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import forage.core.sync.Found
import forage.core.sync.Peer
import forage.core.sync.SyncClient
import forage.core.sync.SyncLog
import forage.core.sync.SyncPeers
import forage.core.sync.SyncServer
import forage.core.sync.UnpairedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sync between the phone and the computer, for the main library (the private vault comes later).
 * The computer listens on the home network while it has a paired phone (or is pairing one); the
 * phone finds it, pairs once (both show a code; the user accepts on the computer), and syncs when
 * Forage opens and now and then while it's open. Changes that arrive refresh what's on screen.
 */
class SyncController(
    private val storage: Storage,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Shows what arrived: the library is read again. */
    private val refresh: suspend () -> Unit,
) {
    private val log = SyncLog(storage.driver, storage.db)
    private val peers = SyncPeers(storage.driver)
    private val name get() = platform.deviceName ?: if (platform.isMobile) "Phone" else "Computer"

    /** The phone reaches out; the computer waits to be reached. */
    val isPhone get() = platform.isMobile

    /** The devices this one syncs with. */
    var devices by mutableStateOf<List<Peer>>(emptyList())
        private set

    /** Why the last sync didn't work, if it didn't. */
    var problem by mutableStateOf<String?>(null)
        private set

    var syncing by mutableStateOf(false)
        private set

    // The computer's side.

    private var server: SyncServer? = null

    /** "Pair a phone" is open on the computer. */
    var pairingOpen by mutableStateOf(false)
        private set

    /** A phone asking to pair, waiting for the user to compare codes. */
    var request by mutableStateOf<SyncServer.PairingRequest?>(null)
        private set

    /** Phones can't find this computer: another program has the port they ask on. */
    var notFindable by mutableStateOf(false)
        private set

    // The phone's side.

    sealed interface Pairing {
        data object Searching : Pairing
        data class Found(val computers: List<forage.core.sync.Found>) : Pairing
        data class Confirming(val computer: forage.core.sync.Found, val code: String) : Pairing
        data class Paired(val name: String) : Pairing
        data class Failed(val message: String) : Pairing
    }

    var pairing by mutableStateOf<Pairing?>(null)
        private set

    private val client by lazy { SyncClient(log, peers, { name }) }

    /** As Forage starts: keeps noting changes where there's a paired device, and the computer listens. */
    fun start() = scope.launch {
        withContext(io) {
            devices = peers.all()
            if (devices.isNotEmpty()) log.start()
        }
        if (!isPhone && devices.isNotEmpty()) listen()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** A library put in place from another device's file: this device's sync starts afresh. */
    fun forgetIdentity() {
        storage.driver.execute(null, "DELETE FROM sync_peer", 0)
        storage.driver.execute(null, "DELETE FROM sync_row", 0)
        storage.driver.execute(null, "UPDATE sync_state SET device = NULL, counter = 0, clock = 0, applying = 0", 0)
        log.stop()
        devices = emptyList()
    }

    fun remove(peer: Peer) = scope.launch {
        withContext(io) {
            peers.remove(peer.id)
            devices = peers.all()
            if (devices.isEmpty()) log.stop()
        }
        if (!isPhone && devices.isEmpty() && !pairingOpen) stop()
    }

    // The computer.

    private fun listen() {
        if (server != null) return
        server = SyncServer(log, peers, { name }, object : SyncServer.Listener {
            override fun pairingRequested(request: SyncServer.PairingRequest) {
                this@SyncController.request = request
            }

            override fun synced(peer: Peer, applied: Int) {
                scope.launch {
                    devices = withContext(io) { peers.all() }
                    problem = null
                    if (applied > 0) refresh()
                }
            }
        }).also {
            runCatching { it.start() }.onFailure { e -> problem = "Couldn't listen on the network: ${e.message}" }
            notFindable = it.discoveryListeningPort < 0
        }
    }

    fun openPairing() {
        pairingOpen = true
        listen()
    }

    fun closePairing() {
        pairingOpen = false
        request?.let { server?.decline(it) }
        request = null
        if (devices.isEmpty()) stop()
    }

    /** The user compared the codes: [accept] if they match. */
    fun answer(accept: Boolean) {
        val asked = request ?: return
        request = null
        scope.launch {
            withContext(io) { if (accept) server?.accept(asked) else server?.decline(asked) }
            devices = withContext(io) { peers.all() }
            if (accept) pairingOpen = false
        }
    }

    // The phone.

    fun findComputers() {
        pairing = Pairing.Searching
        scope.launch {
            val found = withContext(io) { runCatching { client.discover(timeoutMs = 2500) } }
            pairing = found.fold(
                { if (it.isEmpty()) Pairing.Failed("No computer with Forage found on this Wi-Fi") else Pairing.Found(it) },
                { Pairing.Failed("Couldn't search the network: ${it.message}") },
            )
        }
    }

    fun pairWith(computer: Found) {
        scope.launch {
            val started = withContext(io) { runCatching { client.startPairing(computer) } }
            val begun = started.getOrElse {
                pairing = Pairing.Failed(it.message ?: "Couldn't reach ${computer.name}")
                return@launch
            }
            pairing = Pairing.Confirming(computer, begun.code)
            val accepted = withContext(io) { runCatching { client.awaitPairing(begun) { pairing !is Pairing.Confirming } } }
            if (pairing !is Pairing.Confirming) return@launch
            pairing = when {
                accepted.getOrNull() == true -> Pairing.Paired(computer.name)
                accepted.isFailure -> Pairing.Failed(accepted.exceptionOrNull()?.message ?: "Pairing broke off")
                else -> Pairing.Failed("${computer.name} didn't accept the pairing")
            }
            devices = withContext(io) { peers.all() }
            if (accepted.getOrNull() == true) syncNow()
        }
    }

    private companion object {
        /** How often the phone looks for changes made on it, to send them right away. */
        const val LOCAL_CHECK_MS = 2_000L
    }

    fun closePhonePairing() {
        pairing = null
    }

    /** This device's change number when it last synced: changes after it haven't been sent yet. */
    @Volatile private var sentUpTo = -1L

    /**
     * While Forage is in front on the phone: syncs now, again within moments of any change made
     * here, and as soon as the computer says it has changes (a request waits there for them).
     * Every minute regardless, in case either was missed. Cancel it when Forage goes to the back.
     */
    fun whileOpen(): kotlinx.coroutines.Job = scope.launch {
        if (!isPhone) return@launch
        syncNow()
        // Changes made here.
        launch {
            while (true) {
                kotlinx.coroutines.delay(LOCAL_CHECK_MS)
                if (devices.isNotEmpty() && withContext(io) { log.counter } > sentUpTo) syncNow()
            }
        }
        // Changes made on the computer.
        launch {
            while (true) {
                val peer = devices.firstOrNull()?.let { withContext(io) { peers.get(it.id) } }
                if (peer == null) {
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                val changed = withContext(io) { runCatching { client.waitForChanges(peer) } }
                when {
                    changed.getOrNull() == true -> syncNow()
                    // Not reachable (asleep, away): the regular sync finds it again; try again later.
                    changed.isFailure -> kotlinx.coroutines.delay(20_000)
                }
            }
        }
        while (true) {
            kotlinx.coroutines.delay(60_000)
            syncNow()
        }
    }

    /** Syncs with every paired computer (the phone; quietly, unless [manual]). */
    fun syncNow(manual: Boolean = false) {
        if (!isPhone || syncing) return
        val paired = devices.ifEmpty { return }
        syncing = true
        scope.launch {
            sentUpTo = withContext(io) { log.counter }
            var arrived = 0
            var trouble: String? = null
            withContext(io) {
                for (peer in paired) {
                    try {
                        arrived += client.sync(peers.get(peer.id) ?: continue).applied
                    } catch (e: UnpairedException) {
                        peers.remove(peer.id)
                        trouble = e.message
                    } catch (e: Exception) {
                        trouble = e.message ?: "Couldn't sync"
                    }
                }
                devices = peers.all()
                if (devices.isEmpty()) log.stop()
            }
            syncing = false
            problem = trouble
            // What arrived counts as sent: it came from there.
            if (trouble == null) sentUpTo = maxOf(sentUpTo, withContext(io) { log.counter })
            if (arrived > 0) refresh()
        }
    }
}
