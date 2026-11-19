package forage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

/**
 * What Forage draws in place of the system's title bar: a strip to move the window by, with
 * minimize, maximize and close buttons. Without the system frame the window also has no resize
 * edges, so [ResizeEdges] provides them.
 */
class WindowFrame(val window: ComposeWindow, val state: WindowState, val onClose: () -> Unit) {
    val maximized get() = state.placement == WindowPlacement.Maximized

    fun toggleMaximized() {
        state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
}

/** The strip at the top of the content: drag to move, double-click to maximize, buttons at the right. */
@Composable
fun TitleBar(frame: WindowFrame) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().moveWindow(frame))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 12.dp)) {
            WindowButton(Icons.Outlined.Remove, "Minimize") { frame.state.isMinimized = true }
            WindowButton(if (frame.maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare, if (frame.maximized) "Restore" else "Maximize") {
                frame.toggleMaximized()
            }
            WindowButton(Icons.Outlined.Close, "Close", danger = true, onClick = frame.onClose)
        }
    }
}

@Composable
private fun WindowButton(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background = when {
        hovered && danger -> c.error
        hovered -> c.surfaceContainerHighest
        else -> c.surfaceContainerHigh
    }
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(background).hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (hovered && danger) c.onError else c.onSurfaceVariant, modifier = Modifier.size(15.dp))
    }
}

/**
 * Drags move the window, a double-click maximizes or restores it. Positions come from the
 * pointer's place on screen, since the area moves along with the window.
 */
fun Modifier.moveWindow(frame: WindowFrame): Modifier = pointerInput(frame) {
    var lastClick = 0L
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        var origin = frame.window.location
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val now = MouseInfo.getPointerInfo()?.location ?: continue
            if (!moved && abs(now.x - start.x) + abs(now.y - start.y) > 4) {
                moved = true
                if (frame.maximized) {
                    // Dragging a maximized window restores it under the pointer, as system title bars do.
                    frame.state.placement = WindowPlacement.Floating
                    origin = Point(now.x - frame.window.width / 2, now.y - 20)
                    start = now
                }
            }
            if (moved) frame.window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
            change.consume()
        }
        if (!moved) {
            val time = System.currentTimeMillis()
            if (time - lastClick < 400) {
                frame.toggleMaximized()
                lastClick = 0
            } else {
                lastClick = time
            }
        }
    }
}

private const val LEFT = 1
private const val RIGHT = 2
private const val TOP = 4
private const val BOTTOM = 8

/** Thin edges and corners along the window's border that resize it, while it isn't maximized. */
@Composable
fun BoxScope.ResizeEdges(frame: WindowFrame) {
    if (frame.maximized) return
    val edge = 5.dp
    val corner = 14.dp
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge).resize(frame, TOP, Cursor.N_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge).resize(frame, BOTTOM, Cursor.S_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge).resize(frame, LEFT, Cursor.W_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge).resize(frame, RIGHT, Cursor.E_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.TopStart).size(corner).resize(frame, TOP or LEFT, Cursor.NW_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.TopEnd).size(corner).resize(frame, TOP or RIGHT, Cursor.NE_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.BottomStart).size(corner).resize(frame, BOTTOM or LEFT, Cursor.SW_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.BottomEnd).size(corner).resize(frame, BOTTOM or RIGHT, Cursor.SE_RESIZE_CURSOR))
}

private fun Modifier.resize(frame: WindowFrame, edges: Int, cursor: Int): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(frame, edges) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            val from = frame.window.bounds
            val min = frame.window.minimumSize
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                val now = MouseInfo.getPointerInfo()?.location ?: continue
                frame.window.bounds = resized(from, edges, now.x - start.x, now.y - start.y, min.width, min.height)
                change.consume()
            }
        }
    }

/** [from] with the given edges moved by (dx, dy), never smaller than the minimum size. */
internal fun resized(from: Rectangle, edges: Int, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
    var x = from.x
    var y = from.y
    var w = from.width
    var h = from.height
    if (edges and RIGHT != 0) w = maxOf(minWidth, from.width + dx)
    if (edges and BOTTOM != 0) h = maxOf(minHeight, from.height + dy)
    if (edges and LEFT != 0) {
        w = maxOf(minWidth, from.width - dx)
        x = from.x + from.width - w
    }
    if (edges and TOP != 0) {
        h = maxOf(minHeight, from.height - dy)
        y = from.y + from.height - h
    }
    return Rectangle(x, y, w, h)
}
