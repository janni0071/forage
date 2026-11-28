package forage.desktop

import forage.app.Zoom
import java.awt.GraphicsEnvironment

/** The interface size to use when the user hasn't chosen one, from the screen Java draws on. */
fun automaticZoom(): Float {
    val device = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice }.getOrNull() ?: return 1f
    // The system scales Java already: draw as it says.
    if (device.defaultConfiguration.defaultTransform.scaleX > 1.01) return 1f
    return Zoom.forScreenHeight(device.displayMode.height)
}
