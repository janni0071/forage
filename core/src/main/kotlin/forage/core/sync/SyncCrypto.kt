package forage.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and sealing for sync. Pairing is a key exchange (X25519) between the two devices; both
 * then show a 6-digit code worked out from the shared key, and the user confirms that the codes
 * match, which rules out anyone in between (they'd end up with other keys, and other codes).
 * Everything sent after that is sealed with the shared key (AES-GCM), so only the paired device
 * reads it, and a message that was tampered with is refused.
 */
object SyncCrypto {
    private val random = SecureRandom()

    /** A key pair for one pairing. */
    class KeyPair(val private: ByteArray, val public: ByteArray)

    fun newKeyPair(): KeyPair {
        val private = ByteArray(32).also(random::nextBytes)
        return KeyPair(private, X25519.publicKey(private))
    }

    /** What both devices end up with after pairing: the key that seals their messages, and the code they show. */
    class Paired(val key: ByteArray, val code: String)

    fun pair(mine: KeyPair, theirs: ByteArray): Paired {
        val shared = X25519.sharedSecret(mine.private, theirs)
        require(shared.any { it != 0.toByte() }) { "Not a usable key" }
        // The same salt on both sides: the two public keys, in a fixed order.
        val (first, second) = listOf(mine.public, theirs).sortedWith { a, b -> compareBytes(a, b) }
        val salt = first + second
        val key = hkdf(shared, salt, "forage sync key", 32)
        val number = ByteBuffer.wrap(hkdf(shared, salt, "forage sync code", 4)).int.toLong() and 0xffffffffL
        val digits = "%06d".format(number % 1_000_000)
        return Paired(key, "${digits.take(3)} ${digits.drop(3)}")
    }

    /** Seals [plain] for the paired device: compressed, then encrypted with [key], bound to [sender]. */
    fun seal(key: ByteArray, sender: String, plain: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        cipher.updateAAD(sender.toByteArray())
        return nonce + cipher.doFinal(packed)
    }

    /** Opens what [sender] sealed; throws if it wasn't sealed with [key] or was changed on the way. */
    fun open(key: ByteArray, sender: String, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        }
        cipher.updateAAD(sender.toByteArray())
        val packed = cipher.doFinal(sealed, 12, sealed.size - 12)
        return GZIPInputStream(ByteArrayInputStream(packed)).readBytes()
    }

    /** HKDF with SHA-256 (RFC 5869). */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info.toByteArray() + byteArrayOf(counter++.toByte()))
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

/**
 * X25519 (RFC 7748), written out so the phone and the computer compute it the same way whatever
 * their Java's security providers offer. Pairing happens once per device, so plain BigInteger
 * arithmetic is fast enough.
 */
internal object X25519 {
    private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(private: ByteArray): ByteArray = sharedSecret(private, BASE)

    fun sharedSecret(private: ByteArray, public: ByteArray): ByteArray {
        val k = scalar(private)
        val x1 = coordinate(public)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = if (k.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = x1.multiply((da - cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa + A24.multiply(e)).mod(P)
        }
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        return encode(x2.multiply(z2.modPow(P - BigInteger.TWO, P)).mod(P))
    }

    private fun scalar(bytes: ByteArray): BigInteger {
        val k = bytes.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return littleEndian(k)
    }

    private fun coordinate(bytes: ByteArray): BigInteger {
        val u = bytes.copyOf(32)
        u[31] = (u[31].toInt() and 127).toByte()
        return littleEndian(u).mod(P)
    }

    private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun encode(n: BigInteger): ByteArray {
        val big = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return (ByteArray(32 - big.size) + big).reversedArray()
    }
}
