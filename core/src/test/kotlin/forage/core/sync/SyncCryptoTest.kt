package forage.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SyncCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `X25519 gives RFC 7748's answers`() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(alice).hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519.publicKey(bob).hex())
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", X25519.sharedSecret(alice, X25519.publicKey(bob)).hex())
    }

    @Test
    fun `X25519 matches the JDK's own`() {
        val pair = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair()
        // The raw key is the last 32 bytes of the encoded forms.
        val theirPublic = pair.public.encoded.takeLast(32).toByteArray()
        val mine = SyncCrypto.newKeyPair()
        val agreement = javax.crypto.KeyAgreement.getInstance("X25519")
        agreement.init(pair.private)
        val myPublicForJdk = java.security.KeyFactory.getInstance("X25519").generatePublic(
            java.security.spec.X509EncodedKeySpec(pair.public.encoded.dropLast(32).toByteArray() + mine.public),
        )
        agreement.doPhase(myPublicForJdk, true)
        assertEquals(agreement.generateSecret().hex(), X25519.sharedSecret(mine.private, theirPublic).hex())
    }

    @Test
    fun `both devices get the same key and code, someone in between gets other ones`() {
        val phone = SyncCrypto.newKeyPair()
        val laptop = SyncCrypto.newKeyPair()
        val onPhone = SyncCrypto.pair(phone, laptop.public)
        val onLaptop = SyncCrypto.pair(laptop, phone.public)
        assertEquals(onPhone.code, onLaptop.code)
        assertEquals(onPhone.key.hex(), onLaptop.key.hex())
        assertEquals(7, onPhone.code.length)
        val intruder = SyncCrypto.newKeyPair()
        assertNotEquals(onPhone.code, SyncCrypto.pair(phone, intruder.public).code)
    }

    @Test
    fun `sealed messages open with the key and sender only`() {
        val key = SyncCrypto.pair(SyncCrypto.newKeyPair(), SyncCrypto.newKeyPair().public).key
        val sealed = SyncCrypto.seal(key, "phone", "hello".toByteArray())
        assertEquals("hello", SyncCrypto.open(key, "phone", sealed).decodeToString())
        assertFailsWith<Exception> { SyncCrypto.open(key, "someone else", sealed) }
        assertFailsWith<Exception> { SyncCrypto.open(ByteArray(32), "phone", sealed) }
        sealed[20] = (sealed[20] + 1).toByte()
        assertFailsWith<Exception> { SyncCrypto.open(key, "phone", sealed) }
    }
}
