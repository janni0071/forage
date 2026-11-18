package forage.core.vault

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrivateVaultsTest {
    private val dir = Files.createTempDirectory("forage-vaults").toFile()
    // Few rounds keep the tests fast; the real default is far higher.
    private val vaults = PrivateVaults(dir, iterations = 1_000)
    private val secret = "the quiet details of a private profile".toByteArray()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `contents survive a save and unlock, and the file doesn't contain them`() {
        val vault = vaults.create("correct horse".toCharArray())
        assertNull(vault.contents)
        vaults.save(vault, secret)
        assertContentEquals(secret, vaults.unlock("correct horse".toCharArray())!!.contents)
        assertFalse(vault.file.readBytes().decodeToString().contains("private profile"))
    }

    @Test
    fun `a wrong PIN looks exactly like no vault`() {
        assertNull(vaults.unlock("anything".toCharArray()))
        vaults.save(vaults.create("correct horse".toCharArray()), secret)
        assertNull(vaults.unlock("correct horsE".toCharArray()))
    }

    @Test
    fun `different PINs make separate vaults, and creating never replaces one`() {
        vaults.save(vaults.create("first pin".toCharArray()), "one".toByteArray())
        vaults.save(vaults.create("second pin".toCharArray()), "two".toByteArray())
        assertEquals("one", vaults.create("first pin".toCharArray()).contents!!.decodeToString())
        assertEquals("two", vaults.unlock("second pin".toCharArray())!!.contents!!.decodeToString())
    }

    @Test
    fun `the same PIN opens the vault after a restart, with the same salt`() {
        vaults.save(vaults.create("correct horse".toCharArray()), secret)
        assertNotNull(PrivateVaults(dir, iterations = 1_000).unlock("correct horse".toCharArray()))
    }

    @Test
    fun `a changed file is reported as damaged, not as a wrong PIN`() {
        val vault = vaults.create("correct horse".toCharArray())
        vaults.save(vault, secret)
        val bytes = vault.file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        vault.file.writeBytes(bytes)
        assertFailsWith<VaultDamagedException> { vaults.unlock("correct horse".toCharArray()) }
    }
}
