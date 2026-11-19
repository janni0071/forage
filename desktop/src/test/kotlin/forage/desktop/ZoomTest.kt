package forage.desktop

import forage.app.*
import forage.app.ui.*

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomTest {
    @Test
    fun `taller screens get a larger interface, in quarter steps`() {
        assertEquals(1f, Zoom.forScreenHeight(1080))
        assertEquals(1f, Zoom.forScreenHeight(900))
        assertEquals(1.25f, Zoom.forScreenHeight(1440))
        assertEquals(1.5f, Zoom.forScreenHeight(1600))
        assertEquals(1.75f, Zoom.forScreenHeight(1800))
        assertEquals(2f, Zoom.forScreenHeight(2160))
        assertEquals(2f, Zoom.forScreenHeight(2880))
    }

    @Test
    fun `the shortcuts step through the sizes and stop at the ends`() {
        assertEquals(1.25f, Zoom.step(1f, up = true))
        assertEquals(1.5f, Zoom.step(1.3f, up = true))
        assertEquals(1f, Zoom.step(1f, up = false))
        assertEquals(2f, Zoom.step(2f, up = true))
        assertEquals("125%", Zoom.label(1.25f))
    }
}
