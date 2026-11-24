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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sync between the phone and the computer, for one library: the main one, or a private vault.
 * The computer listens on the home network while it has a paired phone (or is pairing one); the
 * phone finds it, pairs once (both show a code; the user accepts on the computer), and syncs when
 * Forage opens and now and then while it's open. Changes that arrive refresh what's on screen.
 *
 * A private vault pairs and syncs on its own, with its own id and key, and only while it's
 * unlocked: on the computer it joins the main library's listener while unlocked ([host]).
 */
class SyncController(
    private val storage: Storage,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** For a private vault on the computer: the main library's sync, whose listener it joins. */
    private val host: SyncController? = null,
    /** Shows what arrived: the library is read again. */
    private val refresh: suspend () -> Unit,
) {
    /** This syncs a private vault. */
    val vault get() = storage.isPrivate
    private val log = SyncLog(storage.driver, storage.db)

    /**
     * Where waiting on the network happens: not on [io], which for a private vault is its one
     * database thread (a request held half a minute by the computer would hold up the vault).
     */
    private val net = kotlinx.coroutines.Dispatchers.IO
    private val peers = SyncPeers(storage.driver)
    private val name get() = platform.deviceName ?: if (platform.isMobile) "Phone" else "Computer"

    /** Phones and tablets reach out; the computer waits to be reached (and passes changes on between them). */
    val isPhone get() = platform.isMobile

    /** What this device is: "phone", "tablet" or "computer". */
    val kind get() = platform.deviceKind

    /** The devices this one syncs with. */
    var devices by mutableStateOf<List<Peer>>(emptyList())
        private set

    /** Why the last sync didn't work, if it didn't. */
    var problem by mutableStateOf<String?>(null)
        private set

    var syncing by mutableStateOf(false)
        private set

    // The computer's side.

    /** The listener, on the main library's sync (a vault's joins it). */
    private var server: SyncServer? = null

    /** The libraries the listener serves now (the main one, a vault while unlocked). */
    private val served = mutableSetOf<SyncServer.Library>()

    /** This library, as the listener serves it: a vault's database work runs on the vault's own thread. */
    private val library by lazy {
        val runner = object : SyncServer.Runner {
            override fun <T> run(block: () -> T): T = kotlinx.coroutines.runBlocking(io) { block() }
        }
        SyncServer.Library(log, peers, object : SyncServer.Listener {
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
        }, if (vault) runner else SyncServer.Runner.Direct)
    }

    /** "Pair a phone" is open on the computer. */
    var pairingOpen by mutableStateOf(false)
        private set

    /** A phone asking to pair, waiting for the user to compare codes. */
    var request by mutableStateOf<SyncServer.PairingRequest?>(null)
        private set

    /** Phones can't find this computer: another program has the port they ask on. */
    private var serverNotFindable by mutableStateOf(false)
    val notFindable: Boolean get() = (host ?: this).serverNotFindable

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

    private val client by lazy { SyncClient(log, peers, { name }, { platform.deviceKind }) }

    /** As Forage starts: keeps noting changes where there's a paired device, and the computer listens. */
    fun start() = scope.launch {
        withContext(io) {
            devices = peers.all()
            if (devices.isNotEmpty()) log.start()
        }
        if (!isPhone && devices.isNotEmpty()) serve()
    }

    /** Stops: the main library's sync as Forage closes, a vault's as it locks. */
    fun stop() {
        if (host != null) {
            host.leave(library)
            return
        }
        server?.stop()
        server = null
        served.clear()
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
        if (!isPhone && devices.isEmpty() && !pairingOpen) (host ?: this@SyncController).leave(library)
    }

    // The computer.

    /** The listener (started on the main library's sync), serving [library] too. */
    private fun serve(): SyncServer = (host ?: this).join(library)

    private fun join(library: SyncServer.Library): SyncServer {
        val listening = server ?: SyncServer({ name }, { log.device }, { platform.deviceKind }).also {
            runCatching { it.start() }.onFailure { e -> problem = "Couldn't listen on the network: ${e.message}" }
            serverNotFindable = it.discoveryListeningPort < 0
            server = it
        }
        listening.add(library)
        served += library
        return listening
    }

    /**
     * [library] is no longer served (a vault locked, the last phone removed). The listener itself
     * goes on until Forage closes: if it stopped as a vault locked, the network would show when a
     * vault is open.
     */
    private fun leave(library: SyncServer.Library) {
        server?.remove(library)
        served -= library
    }

    fun openPairing() {
        pairingOpen = true
        serve().openPairing(library)
    }

    fun closePairing() {
        pairingOpen = false
        (host ?: this).server?.closePairing(library)
        request = null
        if (devices.isEmpty()) (host ?: this).leave(library)
    }

    /** The user compared the codes: [accept] if they match. */
    fun answer(accept: Boolean) {
        val asked = request ?: return
        request = null
        val listening = (host ?: this).server ?: return
        scope.launch {
            // Not on the library's own thread: the listener takes a vault's work there itself.
            withContext(kotlinx.coroutines.Dispatchers.IO) { if (accept) listening.accept(asked) else listening.decline(asked) }
            devices = withContext(io) { peers.all() }
            if (accept) {
                pairingOpen = false
                listening.closePairing(library)
            }
        }
    }

    // The phone.

    fun findComputers() {
        pairing = Pairing.Searching
        scope.launch {
            val found = withContext(net) { runCatching { client.discover(timeoutMs = 2500) } }
            pairing = found.fold(
                { if (it.isEmpty()) Pairing.Failed("No computer with Forage found on this Wi-Fi") else Pairing.Found(it) },
                { Pairing.Failed("Couldn't search the network: ${it.message}") },
            )
        }
    }

    fun pairWith(computer: Found) {
        scope.launch {
            val started = withContext(net) { runCatching { client.startPairing(computer) } }
            val begun = started.getOrElse {
                pairing = Pairing.Failed(it.message ?: "Couldn't reach ${computer.name}")
                return@launch
            }
            pairing = Pairing.Confirming(computer, begun.code)
            val accepted = withContext(net) { runCatching { client.awaitPairing(begun) { pairing !is Pairing.Confirming } } }
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

        /** After the computer couldn't be reached: asking again after this, doubling up to the most. */
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 20_000L
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
    suspend fun runWhileOpen(): Unit = kotlinx.coroutines.coroutineScope {
        if (!isPhone) return@coroutineScope
        syncOnce()
        // Changes made here.
        launch {
            while (true) {
                kotlinx.coroutines.delay(LOCAL_CHECK_MS)
                if (devices.isNotEmpty() && withContext(io) { log.counter } > sentUpTo) syncOnce()
            }
        }
        // Changes made on a computer: a request waits on each paired one; once one is answered,
        // this device syncs, and only then waits again (with what it now has).
        launch {
            val waiting = HashMap<String, kotlinx.coroutines.Job>()
            while (true) {
                val ids = devices.map { it.id }.toSet()
                waiting.keys.filter { it !in ids }.forEach { waiting.remove(it)?.cancel() }
                for (id in ids) if (id !in waiting) waiting[id] = launch { waitOn(id) }
                kotlinx.coroutines.delay(5_000)
            }
        }
        while (true) {
            kotlinx.coroutines.delay(60_000)
            syncOnce()
        }
    }

    /** Waits on the computer [id] for changes, again and again, syncing whenever it has some. */
    private suspend fun waitOn(id: String) {
        var pause = RETRY_MS
        while (true) {
            val peer = withContext(io) { peers.get(id) } ?: return
            val changed = withContext(net) { runCatching { client.waitForChanges(peer) } }
            when {
                changed.getOrNull() == true -> {
                    syncOnce()
                    pause = RETRY_MS
                }
                changed.getOrNull() == false -> pause = RETRY_MS
                // Refused, yet syncing works: the computer's Forage is older than this one's.
                !vault && changed.exceptionOrNull() is UnpairedException && syncOnce() -> {
                    problem = "${peer.name} runs an older Forage, so its changes take up to a minute to arrive here. " +
                        "Update Forage there, and if it's updated already, close it and open it again."
                    kotlinx.coroutines.delay(60_000)
                }
                // Not reachable (asleep, away): again soon, then less often.
                else -> {
                    kotlinx.coroutines.delay(pause)
                    pause = minOf(pause * 2, MAX_RETRY_MS)
                }
            }
        }
    }

    /** One sync at a time; others wait their turn. */
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    /** Syncs with every paired computer (the phone; quietly, unless [manual]), in the background. */
    fun syncNow(manual: Boolean = false) {
        if (!isPhone || devices.isEmpty()) return
        if (!manual && syncLock.isLocked) return
        scope.launch { syncOnce(manual) }
    }

    /** Syncs with every paired computer, after any sync already running; true if it all worked. */
    private suspend fun syncOnce(manual: Boolean = false): Boolean = syncLock.withLock {
        val paired = devices.ifEmpty { return@withLock true }
        syncing = true
        sentUpTo = withContext(io) { log.counter }
        var arrived = 0
        var trouble: String? = null
        withContext(io) {
            for (peer in paired) {
                try {
                    arrived += client.sync(peers.get(peer.id) ?: continue).applied
                } catch (e: UnpairedException) {
                    // A vault can't tell "locked there" from "forgotten there": it keeps the pairing.
                    if (vault) {
                        trouble = "Unlock the private vault on ${peer.name} to sync it"
                    } else {
                        peers.remove(peer.id)
                        trouble = e.message
                    }
                } catch (e: Exception) {
                    trouble = e.message ?: "Couldn't sync"
                }
            }
            devices = peers.all()
            if (devices.isEmpty()) log.stop()
        }
        syncing = false
        // A note that the computer's Forage is older stays until a sync goes wrong or one is asked for.
        if (trouble != null || manual || problem?.contains("older Forage") != true) problem = trouble
        // What arrived counts as sent: it came from there.
        if (trouble == null) sentUpTo = maxOf(sentUpTo, withContext(io) { log.counter })
        if (arrived > 0) refresh()
        trouble == null
    }
}
