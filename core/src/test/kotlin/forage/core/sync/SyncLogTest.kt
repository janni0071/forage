package forage.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import forage.core.PlatformId
import forage.core.db.ForageDatabase
import forage.core.library.LibraryStore
import forage.core.library.PlaybackPosition
import forage.core.profile.ProfileStore
import forage.core.profile.VideoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncLogTest {
    /** One device: its database, the stores on it, and its log. */
    private class Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            ForageDatabase.Schema.create(it)
        }
        val db = ForageDatabase(driver)
        val library = LibraryStore(db)
        val profiles = ProfileStore(db)
        val log = SyncLog(driver, db)
        // How far each has got with the other, as the peers table keeps it.
        val received = HashMap<String, Long>()

        fun likes() = profiles.entries(profiles.profile()).filter { it.state == VideoState.LIKED }.map { it.video }.toSet()
    }

    /** Both ways, as a sync between two devices does. */
    private fun sync(a: Device, b: Device) {
        val toB = a.log.changesSince(b.received[a.log.device] ?: 0, b.log.device)
        val aUpTo = a.log.counter
        val toA = b.log.changesSince(a.received[b.log.device] ?: 0, a.log.device)
        val bUpTo = b.log.counter
        b.log.apply(toB)
        b.received[a.log.device] = aUpTo
        a.log.apply(toA)
        a.received[b.log.device] = bUpTo
    }

    private fun later() = Thread.sleep(3)

    private val x = PlatformId.youtube("xxxxxxxxxxx")
    private val y = PlatformId.youtube("yyyyyyyyyyy")

    private fun pair(): Pair<Device, Device> = (Device() to Device()).also { (a, b) -> a.log.start(); b.log.start() }

    @Test
    fun `likes, the queue, positions and subscriptions reach the other device`() {
        val (phone, laptop) = pair()
        phone.profiles.like(phone.profiles.profile(), x)
        phone.library.saveForLater(y)
        phone.library.savePlaybackPosition(x, PlaybackPosition(120, 600))
        phone.library.follow(PlatformId("youtube", "UCwren"), "Workshop Wren")
        phone.library.recordWatch(x)
        sync(phone, laptop)
        assertEquals(setOf(x), laptop.likes())
        assertEquals(listOf(y), laptop.library.watchLater())
        assertEquals(PlaybackPosition(120, 600), laptop.library.playbackPositions()[x])
        assertEquals(listOf("Workshop Wren"), laptop.library.following().map { it.name })
        assertEquals(1L, laptop.library.counts().second)
    }

    @Test
    fun `the latest change wins, and a removal doesn't come back`() {
        val (phone, laptop) = pair()
        phone.library.saveForLater(x)
        sync(phone, laptop)
        later()
        laptop.library.removeFromWatchLater(x)
        sync(phone, laptop)
        assertEquals(emptyList(), phone.library.watchLater())
        // Again, any number of times: still gone on both.
        sync(laptop, phone)
        sync(phone, laptop)
        assertEquals(emptyList(), phone.library.watchLater())
        assertEquals(emptyList(), laptop.library.watchLater())
    }

    @Test
    fun `changed on both, the later change counts on both`() {
        val (phone, laptop) = pair()
        phone.profiles.like(phone.profiles.profile(), x)
        later()
        laptop.profiles.notInterested(laptop.profiles.profile(), x)
        sync(phone, laptop)
        val onPhone = phone.profiles.entries(phone.profiles.profile()).single()
        val onLaptop = laptop.profiles.entries(laptop.profiles.profile()).single()
        assertEquals(VideoState.NOT_INTERESTED, onPhone.state)
        assertEquals(VideoState.NOT_INTERESTED, onLaptop.state)
    }

    @Test
    fun `a renamed playlist arrives renamed, with its videos`() {
        val (phone, laptop) = pair()
        phone.library.createPlaylist("Wood")
        phone.library.addToPlaylist("Wood", listOf(x, y))
        sync(phone, laptop)
        later()
        phone.library.renamePlaylist("Wood", "Woodwork")
        sync(phone, laptop)
        assertEquals(mapOf("Woodwork" to 2L), laptop.library.playlists())
    }

    @Test
    fun `likes find their profile by name, whatever its number on each device`() {
        val (phone, laptop) = pair()
        // Different ids for the same names on the two devices.
        laptop.profiles.profile("Scratch")
        val music = phone.profiles.profile("Music")
        phone.profiles.like(music, x)
        sync(phone, laptop)
        assertEquals(listOf(x), laptop.profiles.entries(laptop.profiles.find("Music")!!).map { it.video })
    }

    @Test
    fun `what was there before sync started goes across too`() {
        val phone = Device()
        val laptop = Device()
        phone.library.saveForLater(x)
        phone.profiles.like(phone.profiles.profile(), y)
        phone.log.start()
        laptop.log.start()
        sync(phone, laptop)
        assertEquals(listOf(x), laptop.library.watchLater())
        assertEquals(setOf(y), laptop.likes())
    }

    @Test
    fun `changes that came from a device aren't sent back to it, and checking feeds isn't a change`() {
        val (phone, laptop) = pair()
        phone.library.saveForLater(x)
        sync(phone, laptop)
        assertTrue(laptop.log.changesSince(0, phone.log.device).none { it.table == "watch_later" })
        phone.library.follow(PlatformId("youtube", "UCwren"), "Workshop Wren")
        val before = phone.log.counter
        phone.driver.execute(null, "UPDATE subscription SET checked_at = 123, avatar_url = 'x'", 0)
        assertEquals(before, phone.log.counter)
    }
}
