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
 */
class SyncServer(
    private val log: SyncLog,
    private val peers: SyncPeers,
    private val name: () -> String,
    private val listener: Listener,
    private val port: Int = SyncProtocol.PORT,
    private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT,
) {
    interface Listener {
        /** A phone asks to pair: show [request]'s code and ask the user. */
        fun pairingRequested(request: PairingRequest)
        /** A paired device synced; [applied] of its changes changed something here. */
        fun synced(peer: Peer, applied: Int)
    }

    /** A phone asking to pair, with the code both show. */
    class PairingRequest(val id: String, val name: String, val code: String, internal val key: ByteArray) {
        @Volatile internal var answer: Boolean? = null
        internal val at: Instant = Instant.now()
    }

    private val requests = ConcurrentHashMap<String, PairingRequest>()
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var running = false

    /** The port it listens on (the one asked for, or another if that was taken). */
    val listeningPort: Int get() = tcp?.localPort ?: -1

    /** The port it answers searches on; -1 when another program has it (phones then can't find this computer). */
    val discoveryListeningPort: Int get() = udp?.localPort ?: -1

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
        peers.save(Peer(request.id, request.name, request.key))
        log.start()
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
                put("id", log.device)
                put("name", name())
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
    private fun pair(message: JsonObject): JsonObject {
        val id = message["id"]!!.jsonPrimitive.content
        val theirs = SyncProtocol.unb64(message["key"]!!.jsonPrimitive.content)
        val mine = SyncCrypto.newKeyPair()
        val paired = SyncCrypto.pair(mine, theirs)
        // Old requests go; one per phone at a time.
        requests.values.removeIf { it.at.isBefore(Instant.now().minusSeconds(300)) }
        val request = PairingRequest(id, message["name"]?.jsonPrimitive?.content ?: "A device", paired.code, paired.key)
        requests[id] = request
        listener.pairingRequested(request)
        return buildJsonObject {
            put("type", "pairing")
            put("id", log.device)
            put("name", name())
            put("key", SyncProtocol.b64(mine.public))
        }
    }

    private fun sealed(message: JsonObject): JsonObject? {
        val from = message["from"]!!.jsonPrimitive.content
        val peer = peers.get(from)
        val request = requests[from]
        val key = peer?.key ?: request?.key ?: return null
        val inner = SyncProtocol.open(key, message)
        val answer: JsonObject = when (inner["op"]?.jsonPrimitive?.content) {
            "status" -> buildJsonObject {
                put("status", when {
                    peer != null && request?.answer != false -> "paired"
                    request?.answer == false -> "declined"
                    else -> "waiting"
                })
            }
            "sync" -> if (peer == null) return null else sync(peer, inner)
            else -> return null
        }
        if (peer != null && request?.answer == true) requests.remove(from)
        return SyncProtocol.sealed(key, log.device, answer)
    }

    /** Their changes in, ours out; one sync at a time. */
    @Synchronized
    private fun sync(peer: Peer, request: JsonObject): JsonObject {
        val acked = request["acked"]!!.jsonPrimitive.long
        val upTo = request["upTo"]!!.jsonPrimitive.long
        val since = request["since"]!!.jsonPrimitive.long
        val applied = log.apply(SyncProtocol.decode(request["changes"]))
        // Changes of theirs this device never got (it was restored, say): they send them again.
        val gap = acked > peer.received
        val received = if (gap) peer.received else upTo
        val outgoing = log.changesSince(since, peer.id)
        val counter = log.counter
        val updated = peer.copy(name = request["name"]?.jsonPrimitive?.content ?: peer.name, received = received, syncedAt = Instant.now())
        peers.save(updated)
        listener.synced(updated, applied)
        return buildJsonObject {
            put("changes", SyncProtocol.encode(outgoing))
            put("upTo", counter)
            put("received", received)
            put("name", name())
        }
    }
}

/** A computer running Forage, found on the home network. */
data class Found(val id: String, val name: String, val address: InetAddress, val port: Int)

/**
 * The phone's side: it finds the computer on the home network, pairs with it once, and then syncs
 * whenever it's asked to (Forage opening, now and then while it's open).
 */
class SyncClient(
    private val log: SyncLog,
    private val peers: SyncPeers,
    private val name: () -> String,
    private val discoveryPort: Int = SyncProtocol.DISCOVERY_PORT,
) {
    /** Asks the home network for Forage, for [timeoutMs]; every computer that answers. */
    fun discover(timeoutMs: Int = 1500, targets: List<InetAddress> = broadcastAddresses()): List<Found> {
        val found = LinkedHashMap<String, Found>()
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
                found[id] = Found(id, json["name"]!!.jsonPrimitive.content, packet.address, json["port"]!!.jsonPrimitive.int)
            }
        }
        return found.values.toList()
    }

    /** A pairing begun: the code to show, until the computer's user accepts or declines. */
    class Pairing(val with: Found, val code: String, internal val key: ByteArray)

    /** Starts pairing with [computer]: both now show the same code. */
    fun startPairing(computer: Found): Pairing {
        val mine = SyncCrypto.newKeyPair()
        val reply = request(computer.address.hostAddress, computer.port, buildJsonObject {
            put("type", "pair")
            put("id", log.device)
            put("name", name())
            put("key", SyncProtocol.b64(mine.public))
        })
        if (reply["type"]?.jsonPrimitive?.content != "pairing") throw SyncException("${computer.name} didn't answer the pairing")
        val paired = SyncCrypto.pair(mine, SyncProtocol.unb64(reply["key"]!!.jsonPrimitive.content))
        return Pairing(computer, paired.code, paired.key)
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
                    peers.save(Peer(computer.id, computer.name, pairing.key, computer.address.hostAddress, computer.port))
                    log.start()
                    return true
                }
                "declined" -> return false
            }
            Thread.sleep(1000)
        }
        return false
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
        val found = discover().firstOrNull { it.id == peer.id }
            ?: throw SyncException("${peer.name} isn't on this network right now, or Forage isn't open there", problem)
        val moved = peer.copy(address = found.address.hostAddress, port = found.port)
        peers.save(moved)
        return exchange(moved, moved.address, moved.port)
    }

    private fun exchange(peer: Peer, address: String?, port: Int?): Result {
        if (address == null || port == null) throw SyncException("Not found yet")
        val outgoing = log.changesSince(peer.acked, peer.id)
        val upTo = log.counter
        val reply = request(address, port, SyncProtocol.sealed(peer.key, log.device, buildJsonObject {
            put("op", "sync")
            put("name", name())
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
