package forage.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * How two Forages talk on the home network. The computer listens; the phone finds it (a broadcast
 * asking "Forage here?", answered with the computer's name and port) and connects. A connection
 * carries one request and its answer, each a length and a JSON message. Pairing exchanges public
 * keys in the open; everything after that is sealed with the key they lead to.
 */
object SyncProtocol {
    /** The computer's port for connections, and for being found. */
    const val PORT = 47732
    const val DISCOVERY_PORT = 47731

    internal const val ASK = "FORAGE?1"
    private const val MAX_MESSAGE = 512 * 1024 * 1024

    internal fun write(socket: Socket, message: JsonObject) {
        val bytes = message.toString().toByteArray()
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    internal fun read(socket: Socket): JsonObject {
        val input = DataInputStream(socket.getInputStream())
        val size = input.readInt()
        require(size in 0..MAX_MESSAGE) { "Not a Forage message" }
        return Json.parseToJsonElement(ByteArray(size).also(input::readFully).decodeToString()).jsonObject
    }

    internal fun sealed(key: ByteArray, sender: String, inner: JsonObject) = buildJsonObject {
        put("type", "sealed")
        put("from", sender)
        put("box", Base64.getEncoder().encodeToString(SyncCrypto.seal(key, sender, inner.toString().toByteArray())))
    }

    internal fun open(key: ByteArray, message: JsonObject): JsonObject {
        val sender = message["from"]!!.jsonPrimitive.content
        val box = Base64.getDecoder().decode(message["box"]!!.jsonPrimitive.content)
        return Json.parseToJsonElement(SyncCrypto.open(key, sender, box).decodeToString()).jsonObject
    }

    internal fun encode(changes: List<Change>) = JsonArray(changes.map { c ->
        buildJsonObject {
            put("t", c.table)
            put("k", c.key)
            put("ts", c.ts)
            put("o", c.origin)
            put("v", c.values?.let(::JsonObject) ?: JsonNull)
        }
    })

    internal fun decode(json: JsonElement?): List<Change> = json?.jsonArray?.map { e ->
        val o = e.jsonObject
        Change(
            o["t"]!!.jsonPrimitive.content, o["k"]!!.jsonArray, o["ts"]!!.jsonPrimitive.long, o["o"]!!.jsonPrimitive.content,
            (o["v"] as? JsonObject)?.toMap(),
        )
    }.orEmpty()

    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    internal fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** Something sync can't get past, said for people. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The computer forgot this phone (it was removed there): pairing again is the way back. */
class UnpairedException(name: String) : Exception("$name no longer knows this device: pair them again")

/**
 * The computer's side: it listens on the home network, answers phones looking for Forage, pairs
 * with those the user accepts, and syncs with the paired ones whenever they connect.
 *
 * It serves libraries: the main one, and the private vault while it's unlocked, each with its own
 * log, paired devices and keys. A request goes to the library that knows its sender; one no
 * library knows gets the same answer whether a vault exists, is locked, or never was. A pairing
 * goes to the library whose "Pair a phone" is open, and is refused when none is.
 */
class SyncServer(
    private val name: () -> String,
    /** The id phones and tablets find this computer by (the main library's). */
    private val hostId: () -> String,
    /** What this device is, as phones and tablets are told. */
    private val kind: () -> String = { "computer" },
    private val port: Int = SyncProtocol.PORT,
    private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT,
    private val waitMs: Long = WAIT_MS,
) {
    interface Listener {
        /** A phone asks to pair: show [request]'s code and ask the user. */
        fun pairingRequested(request: PairingRequest)
        /** A paired device synced; [applied] of its changes changed something here. */
        fun synced(peer: Peer, applied: Int)
    }

    /** Runs a library's database work where it has to run (the private vault's on its one thread). */
    interface Runner {
        fun <T> run(block: () -> T): T

        object Direct : Runner {
            override fun <T> run(block: () -> T): T = block()
        }
    }

    /** One library served: its log, its paired devices, who to tell, and where its database work runs. */
    class Library(
        internal val log: SyncLog,
        internal val peers: SyncPeers,
        internal val listener: Listener,
        internal val runner: Runner = Runner.Direct,
    ) {
        internal val requests = ConcurrentHashMap<String, PairingRequest>()
        internal fun <T> run(block: () -> T): T = runner.run(block)
    }

    companion object {
        /** How long a phone's wait for changes is held before it's answered with "nothing yet". */
        const val WAIT_MS = 25_000L
    }

    /** A device asking to pair ([kind] says what it is; [host] is how it's found on the network), with the code both show. */
    class PairingRequest(
        val id: String, val name: String, val code: String, internal val key: ByteArray, internal val library: Library,
        val kind: String? = null, internal val host: String? = null,
    ) {
        @Volatile internal var answer: Boolean? = null
        internal val at: Instant = Instant.now()
    }

    private val libraries = java.util.concurrent.CopyOnWriteArrayList<Library>()
    @Volatile private var pairingInto: Library? = null
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var running = false

    /** The port it listens on (the one asked for, or another if that was taken). */
    val listeningPort: Int get() = tcp?.localPort ?: -1

    /** The port it answers searches on; -1 when another program has it (phones then can't find this computer). */
    val discoveryListeningPort: Int get() = udp?.localPort ?: -1

    val isRunning: Boolean get() = running

    fun add(library: Library) {
        if (library !in libraries) libraries += library
    }

    fun remove(library: Library) {
        libraries -= library
        if (pairingInto === library) pairingInto = null
    }

    /** "Pair a phone" is open in [library]: pairings go there. */
    fun openPairing(library: Library) {
        add(library)
        pairingInto = library
    }

    fun closePairing(library: Library) {
        if (pairingInto === library) pairingInto = null
        library.requests.values.forEach { if (it.answer == null) it.answer = false }
    }

    fun start() {
        if (running) return
        running = true
        val server = try {
            ServerSocket(port)
        } catch (e: java.net.BindException) {
            ServerSocket(0)
        }
        tcp = server
        Thread({
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ socket.use { handle(it) } }, "sync-connection").apply { isDaemon = true }.start()
            }
        }, "sync-server").apply { isDaemon = true }.start()
        val discovery = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(discoveryPort)) } }.getOrNull()
        udp = discovery
        if (discovery != null) Thread({ answerSearches(discovery) }, "sync-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
    }

    /** The user said the codes match: the phone is paired from now on. */
    fun accept(request: PairingRequest) {
        val library = request.library
        library.run {
            library.peers.save(Peer(request.id, request.name, request.key, kind = request.kind, host = request.host))
            library.log.start()
        }
        request.answer = true
    }

    fun decline(request: PairingRequest) {
        request.answer = false
    }

    private fun answerSearches(socket: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            if (String(packet.data, 0, packet.length) != SyncProtocol.ASK) continue
            val answer = buildJsonObject {
                put("forage", 1)
                put("id", hostId())
                put("name", name())
                put("kind", kind())
                put("port", listeningPort)
            }.toString().toByteArray()
            runCatching { socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port)) }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000
        val message = runCatching { SyncProtocol.read(socket) }.getOrNull() ?: return
        val reply = runCatching {
            when (message["type"]?.jsonPrimitive?.content) {
                "pair" -> pair(message)
                "sealed" -> sealed(message)
                else -> null
            }
        }.getOrNull() ?: buildJsonObject { put("type", "unknown") }
        runCatching { SyncProtocol.write(socket, reply) }
    }

    /** A phone's public key: ours back, and the user is asked whether the codes match. */
    private fun pair(message: JsonObject): JsonObject? {
        val library = pairingInto ?: return null
        val id = message["id"]!!.jsonPrimitive.content
        // This library's own identity, from another device: a copy of it. The other side says so.
        if (id == library.run { library.log.device }) return null
        val theirs = SyncProtocol.unb64(message["key"]!!.jsonPrimitive.content)
        val mine = SyncCrypto.newKeyPair()
        val paired = SyncCrypto.pair(mine, theirs)
        // Old requests go; one per phone at a time.
        library.requests.values.removeIf { it.at.isBefore(Instant.now().minusSeconds(300)) }
        val request = PairingRequest(
            id, message["name"]?.jsonPrimitive?.content ?: "A device", paired.code, paired.key, library, message["kind"]?.jsonPrimitive?.content,
            message["host"]?.jsonPrimitive?.content ?: id,
        )
        library.requests[id] = request
        library.listener.pairingRequested(request)
        return buildJsonObject {
            put("type", "pairing")
            put("id", library.run { library.log.device })
            put("name", name())
            put("kind", kind())
            put("key", SyncProtocol.b64(mine.public))
        }
    }

    private fun sealed(message: JsonObject): JsonObject? {
        val from = message["from"]!!.jsonPrimitive.content
        for (library in libraries) {
            val peer = library.run { library.peers.get(from) }
            val request = library.requests[from]
            val key = peer?.key ?: request?.key ?: continue
            val inner = runCatching { SyncProtocol.open(key, message) }.getOrNull() ?: continue
            val answer: JsonObject = when (inner["op"]?.jsonPrimitive?.content) {
                "status" -> buildJsonObject {
                    put("status", when {
                        peer != null && request?.answer != false -> "paired"
                        request?.answer == false -> "declined"
                        else -> "waiting"
                    })
                }
                "sync" -> if (peer == null) return null else sync(library, peer, inner)
                "wait" -> if (peer == null) return null else buildJsonObject { put("changed", waitForChanges(library, peer, inner["since"]!!.jsonPrimitive.long)) }
                else -> return null
            }
            if (peer != null && request?.answer == true) library.requests.remove(from)
            return SyncProtocol.sealed(key, library.run { library.log.device }, answer)
        }
        return null
    }

    /**
     * Holds a phone's request until something changes here that it doesn't have (true), or for
     * a while (false); the phone then syncs, or asks again. So changes made here reach an open
     * phone within moments, though only the phone can open a connection.
     */
    private fun waitForChanges(library: Library, peer: Peer, since: Long): Boolean {
        val until = System.currentTimeMillis() + waitMs
        while (running && library in libraries && System.currentTimeMillis() < until) {
            // More than this device ever made: its count is another's (see sync); a sync sets it right.
            if (library.run { since > library.log.counter || library.log.hasChangesSince(since, peer.id) }) return true
            Thread.sleep(400)
        }
        return false
    }

    /** Their changes in, ours out; one sync at a time. */
    @Synchronized
    private fun sync(library: Library, peer: Peer, request: JsonObject): JsonObject {
        val acked = request["acked"]!!.jsonPrimitive.long
        val upTo = request["upTo"]!!.jsonPrimitive.long
        val asked = request["since"]!!.jsonPrimitive.long
        val incoming = SyncProtocol.decode(request["changes"])
        val (applied, outgoing, counter, updated) = library.run {
            // Asked for changes after more than this device ever made: the other side's count is of
            // another device going by this one's identity (a library copied from here), or of this
            // library before it was restored. Everything goes, then; the latest still wins.
            val since = if (asked > library.log.counter) 0 else asked
            val applied = library.log.apply(incoming)
            // Changes of theirs this device never got (it was restored, say): they send them again.
            val received = if (acked > peer.received) peer.received else upTo
            val outgoing = library.log.changesSince(since, peer.id)
            val updated = peer.copy(
                name = request["name"]?.jsonPrimitive?.content ?: peer.name, kind = request["kind"]?.jsonPrimitive?.content ?: peer.kind,
                received = received, syncedAt = Instant.now(),
            )
            library.peers.save(updated)
            Quad(applied, outgoing, library.log.counter, updated)
        }
        library.listener.synced(updated, applied)
        return buildJsonObject {
            put("changes", SyncProtocol.encode(outgoing))
            put("upTo", counter)
            put("received", updated.received)
            put("name", name())
        }
    }

    private data class Quad(val applied: Int, val outgoing: List<Change>, val counter: Long, val peer: Peer)
}

/** A device running Forage, found on the home network: its id, name, what it is, and where it listens. */
data class Found(val id: String, val name: String, val address: InetAddress, val port: Int, val kind: String? = null)

/**
 * The phone's side: it finds the computer on the home network, pairs with it once, and then syncs
 * whenever it's asked to (Forage opening, now and then while it's open).
 */
class SyncClient(
    private val log: SyncLog,
    private val peers: SyncPeers,
    private val name: () -> String,
    /** What this device is ("phone", "tablet", "computer"), as the other is told. */
    private val kind: () -> String = { "phone" },
    /** The id this device is found by on the network (its main library's), so the other can find it too. */
    private val hostId: () -> String = { log.device },
    private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT,
) {
    /**
     * Devices that answered the last search under an id another device answered under too: one's
     * library was copied from the other's, so both go by one identity.
     */
    @Volatile var twins: List<Found> = emptyList()
        private set

    /** Asks the home network for Forage, for [timeoutMs]; every device that answers. */
    fun discover(timeoutMs: Int = 1500, targets: List<InetAddress> = broadcastAddresses()): List<Found> {
        val found = LinkedHashMap<String, Found>()
        val doubles = LinkedHashMap<String, Found>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val ask = SyncProtocol.ASK.toByteArray()
            for (target in targets) runCatching { socket.send(DatagramPacket(ask, ask.size, target, discoveryPort)) }
            val until = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val json = runCatching { Json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject }.getOrNull() ?: continue
                if (json["forage"] == null) continue
                val id = json["id"]!!.jsonPrimitive.content
                if (id == log.device) continue
                val answer = Found(id, json["name"]!!.jsonPrimitive.content, packet.address, json["port"]!!.jsonPrimitive.int, json["kind"]?.jsonPrimitive?.content)
                val earlier = found[id]
                if (earlier != null && (earlier.address != answer.address || earlier.port != answer.port)) {
                    doubles["${earlier.address}:${earlier.port}"] = earlier
                    doubles["${answer.address}:${answer.port}"] = answer
                } else {
                    found[id] = answer
                }
            }
        }
        twins = doubles.values.toList()
        return found.values.toList()
    }

    /**
     * A pairing begun: the code to show, until the computer's user accepts or declines. [peerId]
     * is the library's id on the computer (its private vault has one of its own).
     */
    class Pairing(val with: Found, val code: String, internal val key: ByteArray, val peerId: String, internal val kind: String? = null)

    /** Starts pairing with [computer]: both now show the same code. */
    fun startPairing(computer: Found): Pairing {
        val mine = SyncCrypto.newKeyPair()
        val reply = request(computer.address.hostAddress, computer.port, buildJsonObject {
            put("type", "pair")
            put("id", log.device)
            put("host", hostId())
            put("name", name())
            put("kind", kind())
            put("key", SyncProtocol.b64(mine.public))
        })
        if (reply["type"]?.jsonPrimitive?.content != "pairing") throw SyncException("${computer.name} isn't pairing: open Pair a device there too")
        val paired = SyncCrypto.pair(mine, SyncProtocol.unb64(reply["key"]!!.jsonPrimitive.content))
        val theirs = reply["id"]!!.jsonPrimitive.content
        if (theirs == log.device) {
            throw SyncException("${computer.name} goes by this device's own sync identity: one's library was copied from the other's. On one of them, choose Reset sync (in Settings, Sync), then pair again.")
        }
        return Pairing(computer, paired.code, paired.key, theirs, reply["kind"]?.jsonPrimitive?.content ?: computer.kind ?: "computer")
    }

    /**
     * Waits for the computer's user to answer (asking every second): true once accepted (the
     * computer is then paired here too), false when declined. Gives up after [timeoutMs].
     */
    fun awaitPairing(pairing: Pairing, timeoutMs: Long = 180_000, cancelled: () -> Boolean = { false }): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        val computer = pairing.with
        while (System.currentTimeMillis() < until && !cancelled()) {
            val reply = request(computer.address.hostAddress, computer.port, SyncProtocol.sealed(pairing.key, log.device, buildJsonObject { put("op", "status") }))
            if (reply["type"]?.jsonPrimitive?.content != "sealed") throw SyncException("${computer.name} stopped pairing")
            when (SyncProtocol.open(pairing.key, reply)["status"]?.jsonPrimitive?.content) {
                "paired" -> {
                    peers.save(Peer(pairing.peerId, computer.name, pairing.key, computer.address.hostAddress, computer.port, host = computer.id, kind = pairing.kind))
                    log.start()
                    return true
                }
                "declined" -> return false
            }
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Waits (up to half a minute) for [peer] to have changes this device hasn't got: true when it
     * has, false when nothing came. Throws when it can't be reached.
     */
    fun waitForChanges(peer: Peer): Boolean {
        val attempt = runCatching { waitAt(peer) }
        attempt.getOrNull()?.let { return it }
        val problem = attempt.exceptionOrNull()
        if (problem is UnpairedException) throw problem
        // Not where it was: look for it, as a sync would.
        val moved = locate(peer) ?: throw SyncException("${peer.name} isn't on this network right now, or Forage isn't open there", problem)
        return waitAt(moved)
    }

    /** Finds [peer] on the network again and keeps where it is now; null when it doesn't answer. */
    private fun locate(peer: Peer): Peer? {
        val found = discover().firstOrNull { it.id == (peer.host ?: peer.id) } ?: return null
        val moved = peer.copy(address = found.address.hostAddress, port = found.port)
        peers.save(moved)
        return moved
    }

    private fun waitAt(peer: Peer): Boolean {
        val address = peer.address ?: throw SyncException("Not found yet")
        val port = peer.port ?: throw SyncException("Not found yet")
        val reply = request(address, port, SyncProtocol.sealed(peer.key, log.device, buildJsonObject {
            put("op", "wait")
            put("since", peer.received)
        }))
        if (reply["type"]?.jsonPrimitive?.content != "sealed") throw UnpairedException(peer.name)
        return SyncProtocol.open(peer.key, reply)["changed"]?.jsonPrimitive?.content == "true"
    }

    /** What a sync did: how many of the other side's changes changed something here, and how many went there. */
    data class Result(val applied: Int, val sent: Int)

    /**
     * Syncs with [peer]: where it was last found, or, if it's not there, wherever it answers on the
     * network now. Throws [UnpairedException] if it no longer knows this device.
     */
    fun sync(peer: Peer): Result {
        val attempt = runCatching { exchange(peer, peer.address, peer.port) }
        attempt.getOrNull()?.let { return it }
        val problem = attempt.exceptionOrNull()
        if (problem is UnpairedException) throw problem
        // Moved (a new address from the router), or Forage restarted on another port.
        val moved = locate(peer) ?: throw SyncException("${peer.name} isn't on this network right now, or Forage isn't open there", problem)
        return exchange(moved, moved.address, moved.port)
    }

    private fun exchange(peer: Peer, address: String?, port: Int?): Result {
        if (address == null || port == null) throw SyncException("Not found yet")
        val outgoing = log.changesSince(peer.acked, peer.id)
        val upTo = log.counter
        val reply = request(address, port, SyncProtocol.sealed(peer.key, log.device, buildJsonObject {
            put("op", "sync")
            put("name", name())
            put("kind", kind())
            put("since", peer.received)
            put("acked", peer.acked)
            put("upTo", upTo)
            put("changes", SyncProtocol.encode(outgoing))
        }))
        if (reply["type"]?.jsonPrimitive?.content != "sealed") throw UnpairedException(peer.name)
        val answer = SyncProtocol.open(peer.key, reply)
        val applied = log.apply(SyncProtocol.decode(answer["changes"]))
        peers.save(
            peer.copy(
                name = answer["name"]?.jsonPrimitive?.content ?: peer.name,
                received = answer["upTo"]!!.jsonPrimitive.long,
                acked = answer["received"]!!.jsonPrimitive.long,
                syncedAt = Instant.now(),
            ),
        )
        return Result(applied, outgoing.size)
    }

    private fun request(address: String, port: Int, message: JsonObject): JsonObject = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 3000)
            socket.soTimeout = 120_000
            SyncProtocol.write(socket, message)
            SyncProtocol.read(socket)
        }
    } catch (e: java.io.IOException) {
        throw SyncException("Couldn't reach the other device", e)
    }

    companion object {
        /** Where to ask: every network's broadcast address, and the general one. */
        fun broadcastAddresses(): List<InetAddress> {
            val own = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
            }.getOrDefault(emptyList())
            return (own + InetAddress.getByName("255.255.255.255")).distinct()
        }
    }
}
