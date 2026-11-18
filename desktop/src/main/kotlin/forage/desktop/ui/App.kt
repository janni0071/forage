package forage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.desktop.DesktopModel
import forage.desktop.Screen

@Composable
fun App(model: DesktopModel, onImport: () -> Unit, frame: WindowFrame? = null) {
    val outline = if (frame != null && !frame.maximized) Modifier.border(1.dp, frameBorderColor()) else Modifier
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(outline)) {
            Row {
                Sidebar(model, onImport, frame)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    frame?.let { TitleBar(it) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (model.screen) {
                            Screen.LIBRARY -> LibraryScreen(model, onImport)
                            Screen.TOPICS -> TopicsScreen(model)
                            Screen.DISCOVER -> DiscoverScreen(model)
                            Screen.LATER -> LaterScreen(model)
                        }
                        StatusToast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
                    }
                }
            }
            frame?.let { ResizeEdges(it) }
        }
    }
}

@Composable
private fun Sidebar(model: DesktopModel, onImport: () -> Unit, frame: WindowFrame?) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.width(236.dp).fillMaxHeight().background(c.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 22.dp),
    ) {
        // Without the system title bar, the logo is somewhere to move the window by too.
        val move = frame?.let { Modifier.moveWindow(it) } ?: Modifier
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 30.dp).then(move)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Accent.pastelPink, Accent.lavender))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF3E0423), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Forage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (model.isPrivate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Private vault", style = MaterialTheme.typography.labelMedium, color = c.tertiary, maxLines = 1)
                    }
                }
            }
        }

        NavItem(Icons.Outlined.VideoLibrary, "Library", model.screen == Screen.LIBRARY) { model.screen = Screen.LIBRARY }
        NavItem(Icons.Outlined.BubbleChart, "Topics", model.screen == Screen.TOPICS) { model.screen = Screen.TOPICS }
        NavItem(Icons.Outlined.AutoAwesome, "Discover", model.screen == Screen.DISCOVER, count = model.picks.size.takeIf { it > 0 }) {
            model.screen = Screen.DISCOVER
        }
        NavItem(Icons.Outlined.WatchLater, "Watch later", model.screen == Screen.LATER, count = model.later.size.takeIf { it > 0 }) {
            model.screen = Screen.LATER
        }

        Spacer(Modifier.weight(1f))
        if (model.isPrivate) {
            NavItem(Icons.Outlined.Lock, "Lock private vault", selected = false) { model.onLock?.invoke() }
        } else {
            NavItem(Icons.Outlined.FileUpload, "Import Takeout", selected = false, enabled = model.job == null, onClick = onImport)
        }
        SettingsItem(model)
        HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 8.dp), color = c.outlineVariant)
        ProfileSwitcher(model)
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    count: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val content = if (selected) c.onPrimaryContainer else c.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) c.onPrimaryContainer else c.onSurface,
            modifier = Modifier.weight(1f),
        )
        count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = content) }
    }
}

@Composable
private fun SettingsItem(model: DesktopModel) {
    NavItem(Icons.Outlined.Settings, "Settings", selected = model.settingsOpen) { model.settingsOpen = true }
    if (model.settingsOpen) SettingsDialog(model)
}

/** Progress and messages float above the content, like a snackbar. */
@Composable
private fun StatusToast(model: DesktopModel, modifier: Modifier) {
    val job = model.job
    val text = job?.let { j ->
        listOfNotNull(j.label, j.total?.let { "${j.done} of $it" }, j.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
    } ?: model.message
    val background = model.background
    // Messages close themselves after a while: longer ones, and problems, stay a little longer.
    val message = model.message
    LaunchedEffect(message, job == null) {
        if (message == null || job != null) return@LaunchedEffect
        val problem = listOf("failed", "couldn't", "can't", "isn't", "wrong", "needs").any { it in message.lowercase() }
        delay(((if (problem) 9_000L else 4_000L) + message.length * 40L).coerceAtMost(15_000L))
        if (model.message == message) model.message = null
    }
    if (text == null && background == null) return
    val c = MaterialTheme.colorScheme
    Surface(
        color = c.inverseSurface,
        contentColor = c.inverseOnSurface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp),
    ) {
        Column {
            if (job != null) {
                val total = job.total
                val bar = Modifier.fillMaxWidth().height(3.dp)
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { job.done.toFloat() / total }, modifier = bar, color = c.inversePrimary, trackColor = Color.Transparent)
                } else {
                    LinearProgressIndicator(modifier = bar, color = c.inversePrimary, trackColor = Color.Transparent)
                }
            }
            Row(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (background != null && text == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.inversePrimary)
                }
                Text(text ?: background.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (background != null && text != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.inversePrimary)
                }
                if (job == null && text != null) {
                    IconButton(onClick = { model.message = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = c.inverseOnSurface, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
