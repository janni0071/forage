package forage.desktop

import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/**
 * How big the interface is drawn. On Linux under Wayland, Java often doesn't learn the screen's
 * scaling and draws at 100%, which makes everything tiny on high-resolution laptop screens.
 * Forage then picks a size from the screen's resolution; the user can always choose their own.
 */
object Zoom {
    val choices = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

    /** The size to use when the user hasn't chosen one. */
    fun auto(): Float {
        val device = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice }.getOrNull() ?: return 1f
        // The system scales Java already: draw as it says.
        if (device.defaultConfiguration.defaultTransform.scaleX > 1.01) return 1f
        return forScreenHeight(device.displayMode.height)
    }

    /** A 1080-pixel-high screen is drawn at 100%; taller screens proportionally larger, in quarter steps. */
    fun forScreenHeight(pixels: Int): Float {
        if (pixels <= 0) return 1f
        val steps = (pixels / 1080f * 4).roundToInt() / 4f
        return steps.coerceIn(1f, 2f)
    }

    fun label(scale: Float) = "${(scale * 100).roundToInt()}%"

    /** The next size up or down from [current], for the keyboard shortcuts. */
    fun step(current: Float, up: Boolean): Float {
        val next = if (up) choices.firstOrNull { it > current + 0.01f } else choices.lastOrNull { it < current - 0.01f }
        return next ?: current
    }
}
