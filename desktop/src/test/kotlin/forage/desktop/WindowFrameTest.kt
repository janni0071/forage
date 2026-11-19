package forage.desktop

import forage.app.*
import forage.app.ui.*

import forage.desktop.ui.resized
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowFrameTest {
    private val from = Rectangle(100, 100, 1000, 700)

    @Test
    fun `edges move the side they belong to and keep the minimum size`() {
        assertEquals(Rectangle(100, 100, 1050, 700), resized(from, 2, 50, 30, 860, 540))            // right
        assertEquals(Rectangle(150, 100, 950, 700), resized(from, 1, 50, 0, 860, 540))              // left, inwards
        assertEquals(Rectangle(240, 100, 860, 700), resized(from, 1, 500, 0, 860, 540))             // left, stopped at the minimum
        assertEquals(Rectangle(100, 60, 1000, 740), resized(from, 4, 0, -40, 860, 540))             // top, outwards
        assertEquals(Rectangle(100, 100, 1020, 720), resized(from, 2 or 8, 20, 20, 860, 540))       // bottom right corner
    }
}
