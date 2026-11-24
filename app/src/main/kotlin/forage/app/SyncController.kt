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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sync between devices, for one library: the main one, or a private vault. Any two devices pair:
 * a computer with a phone, a tablet or another computer, a phone with a tablet. Every device
 * listens on the home network (a computer whenever Forage runs, a phone or tablet while Forage is
 * open on it) and reaches out to the devices it's paired with. Of each pair, one side reaches out
 * and the other is reached: a phone or tablet reaches out to a computer (which can't reach it
 * once Forage goes to the back); between two of a kind, the one with the smaller id. Changes that
 * arrive refresh what's on screen, and a computer passes changes on between its other devices.
 *
 * A private vault pairs and syncs on its own, with its own id and key, and only while it's
 * unlocked: it joins the main library's listener while unlocked ([host]).
 */
class SyncController(
    private val storage: Storage,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** For a private vault: the main library's sync, whose listener it joins. */
    private val host: SyncController? = null,
    /** Shows what arrived: the library is read again. */
    private val refresh: suspend () -> Unit,
) {
    /** This syncs a private vault. */
    val vault get() = storage.isPrivate
    private val log = SyncLog(storage.driver, storage.db)
    private val peers = SyncPeers(storage.driver)

    /**
     * Where waiting on the network happens: not on [io], which for a private vault is its one
     * database thread (a request held half a minute by the other device would hold up the vault).
     */
    private val net = kotlinx.coroutines.Dispatchers.IO
    private val name get() = platform.deviceName ?: if (platform.isMobile) "Phone" else "Computer"

    /** A phone or tablet: it listens only while Forage is open, and reaches out to computers. */
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

    /** Whether this device reaches out to any of its paired devices (it can then sync on request). */
    val reachesOut: Boolean get() = devices.any(::reachesOutTo)

    /**
     * Whether this device reaches out to [peer] (else [peer] reaches out here): a phone or tablet
     * to a computer; between two of a kind, the one with the smaller id. A device from an older
     * Forage that didn't say what it is: a computer to a phone, a phone to a computer, as before.
     */
    private fun reachesOutTo(peer: Peer): Boolean {
        val theyRoam = peer.kind?.let { it != "computer" } ?: !isPhone
        return if (isPhone != theyRoam) isPhone else device < peer.id
    }

    /** This library's id on this device. */
    private val device by lazy { log.device }

    /** The id this device is found by on the network: its main library's. */
    private val hostId: String by lazy { (host ?: this).device }

    // Listening.

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

    /** "Pair a device" is open: this device can be found, and looks for others. */
    var pairingOpen by mutableStateOf(false)
        private set

    /** A device asking to pair, waiting for the user to compare codes. */
    var request by mutableStateOf<SyncServer.PairingRequest?>(null)
        private set

    /** Other devices can't find this one: another program has the port they ask on. */
    private var serverNotFindable by mutableStateOf(false)
    val notFindable: Boolean get() = (host ?: this).serverNotFindable

    // Reaching out.

    sealed interface Pairing {
        data class Confirming(val device: Found, val code: String) : Pairing
        data class Paired(val name: String) : Pairing
        data class Failed(val message: String) : Pairing
    }

    /** A pairing this device started, while it runs; null otherwise. */
    var pairing by mutableStateOf<Pairing?>(null)
        private set

    /** The devices found while "Pair a device" is open (not yet paired with this library). */
    var found by mutableStateOf<List<Found>>(emptyList())
        private set

    private val client by lazy { SyncClient(log, peers, { name }, { platform.deviceKind }, { hostId }) }

    /** On a computer: reaching out to its devices for as long as Forage runs. */
    private var reaching: Job? = null

    /** As Forage starts (or the vault unlocks): keeps noting changes where there's a paired device; a computer listens and reaches out. */
    fun start() = scope.launch {
        withContext(io) {
            devices = peers.all()
            if (devices.isNotEmpty()) log.start()
        }
        if (!isPhone) {
            if (devices.isNotEmpty()) serve()
            if (reaching == null) reaching = scope.launch { reachOut() }
        }
    }

    /** Stops: the main library's sync as Forage closes, a vault's as it locks. */
    fun stop() {
        reaching?.cancel()
        reaching = null
        searching?.cancel()
        if (host != null) {
            host.leave(library)
            return
        }
        stopListening()
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
        if (devices.isEmpty() && !pairingOpen) (host ?: this@SyncController).leave(library)
    }

    /**
     * While Forage is in front on a phone or tablet: listens (so paired devices can reach it), and
     * reaches out to those it reaches out to. Cancel it when Forage goes to the back.
     */
    suspend fun runWhileOpen(): Unit = coroutineScope {
        if (!isPhone) return@coroutineScope
        if (devices.isNotEmpty() || withContext(io) { peers.all() }.isNotEmpty()) serve()
        try {
            reachOut()
        } finally {
            if (host == null) stopListening() else host.leave(library)
        }
    }

    // Listening.

    /** The listener (started on the main library's sync), serving [library] too. */
    private fun serve(): SyncServer = (host ?: this).join(library)

    private fun join(library: SyncServer.Library): SyncServer {
        val listening = server ?: SyncServer({ name }, { device }, { platform.deviceKind }).also {
            runCatching { it.start() }.onFailure { e -> problem = "Couldn't listen on the network: ${e.message}" }
            serverNotFindable = it.discoveryListeningPort < 0
            platform.listening(true)
            server = it
        }
        listening.add(library)
        served += library
        return listening
    }

    /**
     * [library] is no longer served (a vault locked, the last device removed). The listener itself
     * goes on (on a computer until Forage closes): if it stopped as a vault locked, the network
     * would show when a vault is open.
     */
    private fun leave(library: SyncServer.Library) {
        server?.remove(library)
        served -= library
    }

    private fun stopListening() {
        server?.stop()
        if (server != null) platform.listening(false)
        server = null
        served.clear()
    }

    // Pairing: both devices open "Pair a device"; one picks the other; both compare the codes.

    private var searching: Job? = null

    fun openPairing() {
        pairingOpen = true
        pairing = null
        request = null
        found = emptyList()
        serve().openPairing(library)
        searching?.cancel()
        searching = scope.launch {
            while (true) {
                val answered = withContext(net) { runCatching { client.discover(timeoutMs = 1500) }.getOrNull() }
                if (answered != null) {
                    val paired = devices.map { it.host ?: it.id }.toSet()
                    found = answered.filter { it.id != hostId && it.id !in paired }
                }
                delay(1500)
            }
        }
    }

    fun closePairing() {
        pairingOpen = false
        searching?.cancel()
        searching = null
        pairing = null
        (host ?: this).server?.closePairing(library)
        request = null
        found = emptyList()
        if (devices.isEmpty()) (host ?: this).leave(library)
    }

    /** The user compared the codes of a device asking to pair: [accept] if they match. */
    fun answer(accept: Boolean) {
        val asked = request ?: return
        request = null
        val listening = (host ?: this).server ?: return
        scope.launch {
            // Not on the library's own thread: the listener takes a vault's work there itself.
            withContext(kotlinx.coroutines.Dispatchers.IO) { if (accept) listening.accept(asked) else listening.decline(asked) }
            devices = withContext(io) { peers.all() }
            if (accept) {
                pairing = Pairing.Paired(asked.name)
                listening.closePairing(library)
                if (reachesOut) syncNow()
            }
        }
    }

    /** Pairs with [other], found on the network: both now show a code, and the other side accepts. */
    fun pairWith(other: Found) {
        scope.launch {
            val started = withContext(net) { runCatching { client.startPairing(other) } }
            val begun = started.getOrElse {
                pairing = Pairing.Failed(it.message ?: "Couldn't reach ${other.name}")
                return@launch
            }
            pairing = Pairing.Confirming(other, begun.code)
            val accepted = withContext(net) { runCatching { client.awaitPairing(begun) { pairing !is Pairing.Confirming } } }
            if (pairing !is Pairing.Confirming) return@launch
            pairing = when {
                accepted.getOrNull() == true -> Pairing.Paired(other.name)
                accepted.isFailure -> Pairing.Failed(accepted.exceptionOrNull()?.message ?: "Pairing broke off")
                else -> Pairing.Failed("${other.name} didn't accept the pairing")
            }
            devices = withContext(io) { peers.all() }
            if (accepted.getOrNull() == true) {
                (host ?: this@SyncController).server?.closePairing(library)
                if (reachesOut) syncNow()
            }
        }
    }

    /** Back from a pairing that failed or finished to the list of devices. */
    fun dismissPairing() {
        if (pairing is Pairing.Paired) closePairing() else pairing = null
    }

    // Reaching out.

    private companion object {
        /** How often this device looks for changes made on it, to send them right away. */
        const val LOCAL_CHECK_MS = 2_000L

        /** After a device couldn't be reached: asking again after this, doubling up to the most. */
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 20_000L
    }

    /** This device's change number when it last synced: changes after it haven't been sent yet. */
    @Volatile private var sentUpTo = -1L

    /**
     * Reaching out to the devices this one reaches out to: syncs now, again within moments of any
     * change made here, and as soon as one of them says it has changes (a request waits there for
     * them). Every minute regardless, in case either was missed.
     */
    private suspend fun reachOut(): Unit = coroutineScope {
        syncOnce()
        // Changes made here.
        launch {
            while (true) {
                delay(LOCAL_CHECK_MS)
                if (reachesOut && withContext(io) { log.counter } > sentUpTo) syncOnce()
            }
        }
        // Changes made there: a request waits on each; once one is answered, this device syncs, and
        // only then waits again (with what it now has).
        launch {
            val waiting = HashMap<String, Job>()
            while (true) {
                val ids = devices.filter(::reachesOutTo).map { it.id }.toSet()
                waiting.keys.filter { it !in ids }.forEach { waiting.remove(it)?.cancel() }
                for (id in ids) if (id !in waiting) waiting[id] = launch { waitOn(id) }
                delay(5_000)
            }
        }
        while (true) {
            delay(60_000)
            syncOnce()
        }
    }

    /** Waits on the device [id] for changes, again and again, syncing whenever it has some. */
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
                // Refused, yet syncing works: the other device's Forage is older than this one's.
                !vault && changed.exceptionOrNull() is UnpairedException && syncOnce() -> {
                    problem = "${peer.name} runs an older Forage, so its changes take up to a minute to arrive here. " +
                        "Update Forage there, and if it's updated already, close it and open it again."
                    delay(60_000)
                }
                // Not reachable (asleep, away, Forage closed there): again soon, then less often.
                else -> {
                    delay(pause)
                    pause = minOf(pause * 2, MAX_RETRY_MS)
                }
            }
        }
    }

    /** One sync at a time; others wait their turn. */
    private val syncLock = Mutex()

    /** Syncs with the devices this one reaches out to (quietly, unless [manual]), in the background. */
    fun syncNow(manual: Boolean = false) {
        if (!reachesOut) return
        if (!manual && syncLock.isLocked) return
        scope.launch { syncOnce(manual) }
    }

    /** Syncs with the devices this one reaches out to, after any sync already running; true if it all worked. */
    private suspend fun syncOnce(manual: Boolean = false): Boolean = syncLock.withLock {
        val paired = devices.filter(::reachesOutTo).ifEmpty { return@withLock true }
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
                    // A phone or tablet with Forage closed can't be reached: that's expected, not a problem to show.
                    if (peer.kind == "computer" || peer.kind == null) trouble = e.message ?: "Couldn't sync"
                }
            }
            devices = peers.all()
            if (devices.isEmpty()) log.stop()
        }
        syncing = false
        // A note that the other device's Forage is older stays until a sync goes wrong or one is asked for.
        if (trouble != null || manual || problem?.contains("older Forage") != true) problem = trouble
        // What arrived counts as sent: it came from there.
        if (trouble == null) sentUpTo = maxOf(sentUpTo, withContext(io) { log.counter })
        if (arrived > 0) refresh()
        trouble == null
    }
}
