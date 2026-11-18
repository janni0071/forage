package forage.core.vault

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted private vaults (SPEC, Sensitive platforms): each is a whole database, sealed with
 * AES-256-GCM under a key derived from the user's PIN with PBKDF2.
 *
 * A vault's file name is derived from the PIN as well, so the app never needs to know which
 * vaults exist: a wrong PIN and no vault at all look the same, and creating a vault can never
 * overwrite another. Working out the name costs as much as trying to decrypt, so it gives an
 * attacker nothing extra.
 *
 * The protection is only as strong as the PIN: someone with the file can try PINs offline, at
 * about one per [ITERATIONS] hash rounds. A long passphrase is far safer than a short number.
 */
class PrivateVaults(private val dir: File, private val iterations: Int = ITERATIONS) {
    /** Random per installation, so PINs can't be looked up in tables precomputed for everyone. */
    private val salt: ByteArray

    init {
        dir.mkdirs()
        val saltFile = File(dir, "salt")
        if (!saltFile.isFile) {
            val fresh = ByteArray(16).also(SecureRandom()::nextBytes)
            val partial = File(dir, "salt.part").apply { writeBytes(fresh) }
            Files.move(partial.toPath(), saltFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        salt = saltFile.readBytes()
    }

    /** An opened vault: its key, its file, and its decrypted contents (null for a new vault). */
    class Unlocked internal constructor(internal val key: SecretKeySpec, val file: File, val contents: ByteArray?)

    /** Opens the vault for [pin], or returns null when there is none (or, the same thing, the PIN is wrong). */
    fun unlock(pin: CharArray): Unlocked? {
        val (key, file) = derive(pin)
        if (!file.isFile) return null
        return Unlocked(key, file, open(key, file.readBytes()))
    }

    /** Creates a vault for [pin]. If one exists for that PIN already, it is opened instead, never replaced. */
    fun create(pin: CharArray): Unlocked {
        val (key, file) = derive(pin)
        if (file.isFile) return Unlocked(key, file, open(key, file.readBytes()))
        return Unlocked(key, file, null)
    }

    /** Encrypts [contents] into the vault's file, replacing it in one step so a crash can't leave half a file. */
    fun save(vault: Unlocked, contents: ByteArray) {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vault.key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        val sealed = ByteBuffer.allocate(MAGIC.size + NONCE_BYTES + cipher.getOutputSize(contents.size))
            .put(MAGIC).put(nonce).put(cipher.doFinal(contents)).array()
        val partial = File(vault.file.parentFile, "${vault.file.name}.part")
        partial.writeBytes(sealed)
        Files.move(partial.toPath(), vault.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun derive(pin: CharArray): Pair<SecretKeySpec, File> {
        require(pin.isNotEmpty()) { "Empty PIN" }
        val spec = PBEKeySpec(pin, salt, iterations, (KEY_BYTES + ID_BYTES) * 8)
        val bits = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val key = SecretKeySpec(bits.copyOfRange(0, KEY_BYTES), "AES")
        val id = bits.copyOfRange(KEY_BYTES, KEY_BYTES + ID_BYTES).joinToString("") { "%02x".format(it) }
        bits.fill(0)
        return key to File(dir, "$id.vault")
    }

    private fun open(key: SecretKeySpec, sealed: ByteArray): ByteArray {
        if (sealed.size < MAGIC.size + NONCE_BYTES || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw VaultDamagedException("Not a Forage vault, or a newer version")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, MAGIC.size, NONCE_BYTES))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(sealed, MAGIC.size + NONCE_BYTES, sealed.size - MAGIC.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            // The name matched, so the PIN was right: the file itself was changed or damaged.
            throw VaultDamagedException("The vault file is damaged or was tampered with", e)
        }
    }

    companion object {
        /** About half a second on a laptop: fine once per unlock, slow for anyone guessing. */
        const val ITERATIONS = 600_000
        private val MAGIC = "FORAGEV1".toByteArray()
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val ID_BYTES = 16
    }
}

class VaultDamagedException(message: String, cause: Throwable? = null) : Exception(message, cause)
