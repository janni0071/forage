package forage.desktop

import forage.app.*
import forage.app.ui.*

import forage.core.PlatformId
import forage.core.library.LibraryStore
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import forage.core.vault.PrivateVaults
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedDatabaseTest {
    private val dir = Files.createTempDirectory("forage-private").toFile()
    private val vaults = PrivateVaults(dir, iterations = 1_000)
    private val pin = "a long private passphrase".toCharArray()
    private val video = PlatformId.youtube("aaaaaaaaaa1")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `what is written survives locking and unlocking, encrypted on disk`() {
        EncryptedDatabase(vaults, vaults.create(pin)).use { encrypted ->
            LibraryStore(encrypted.db).remember(video, "A very private title")
            val profile = ProfileStore(encrypted.db).profile("Private")
            ProfileStore(encrypted.db).mark(profile, listOf(video), VideoState.LIKED)
        }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.none { "very private" in it.readBytes().decodeToString(throwOnInvalidSequence = false) }, files.toString())

        EncryptedDatabase(vaults, vaults.unlock(pin)!!).use { encrypted ->
            assertEquals("A very private title", LibraryStore(encrypted.db).video(video)?.title)
            val profile = ProfileStore(encrypted.db).find("Private")!!
            assertEquals(listOf(video), ProfileStore(encrypted.db).entries(profile).map { it.video })
        }
    }

    @Test
    fun `saves only when something changed`() {
        EncryptedDatabase(vaults, vaults.create(pin)).use { encrypted ->
            assertFalse(encrypted.saveIfChanged())
            LibraryStore(encrypted.db).remember(video, "Title")
            assertTrue(encrypted.saveIfChanged())
            assertFalse(encrypted.saveIfChanged())
        }
    }

    @Test
    fun `a new vault exists on disk as soon as it is created`() {
        EncryptedDatabase(vaults, vaults.create(pin)).use { }
        assertTrue(vaults.unlock(pin) != null)
    }
}
