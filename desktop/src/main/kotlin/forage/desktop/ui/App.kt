@file:OptIn(ExperimentalMaterial3Api::class)

package forage.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forage.desktop.DesktopModel
import forage.desktop.Player
import forage.desktop.Screen
import forage.desktop.Theme

@Composable
fun App(model: DesktopModel, onImport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            TopBar(model, onImport)
            Row(Modifier.weight(1f)) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(8.dp))
                    RailItem(model, Screen.LIBRARY, "▤", "Library")
                    RailItem(model, Screen.DISCOVER, "✦", "Discover")
                    RailItem(model, Screen.LATER, "◷", "Later", badge = model.later.size.takeIf { it > 0 })
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (model.screen) {
                        Screen.LIBRARY -> LibraryScreen(model, onImport)
                        Screen.DISCOVER -> DiscoverScreen(model)
                        Screen.LATER -> LaterScreen(model)
                    }
                }
            }
            StatusBar(model)
        }
    }
}

@Composable
private fun RailItem(model: DesktopModel, screen: Screen, icon: String, label: String, badge: Int? = null) {
    NavigationRailItem(
        selected = model.screen == screen,
        onClick = { model.screen = screen },
        icon = { Text(icon, fontSize = 20.sp) },
        label = { Text(if (badge != null) "$label ($badge)" else label) },
    )
}

@Composable
private fun TopBar(model: DesktopModel, onImport: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Forage", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(16.dp))
                Text(
                    "Profile \"${model.profile.name}\" · ${model.profileSize} videos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            SettingsMenu(model)
            TextButton(onClick = onImport, enabled = model.job == null) { Text("Import Takeout…") }
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun SettingsMenu(model: DesktopModel) {
    var open by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf(model.settings.player) }
    Box {
        TextButton(onClick = { open = true }) { Text("Settings ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Open videos in",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (p in Player.entries) {
                val problem = p.problem
                DropdownMenuItem(
                    text = { Text((if (p == player) "● " else "○ ") + if (problem == null) p.label else "${p.label} ($problem)") },
                    // A player that needs yt-dlp stays selectable: opening it explains what to install.
                    enabled = problem != "not installed",
                    onClick = {
                        model.settings.player = p
                        player = p
                    },
                )
            }
            HorizontalDivider()
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (t in Theme.entries) {
                DropdownMenuItem(
                    text = { Text((if (t == model.theme) "● " else "○ ") + t.label) },
                    onClick = { model.changeTheme(t) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text((if (model.showThumbnails) "☑ " else "☐ ") + "Show thumbnails") },
                onClick = { model.setThumbnails(!model.showThumbnails) },
            )
        }
    }
}

@Composable
private fun StatusBar(model: DesktopModel) {
    val job = model.job
    val text = job?.let { j ->
        listOfNotNull(j.label, j.total?.let { "${j.done} of $it" }, j.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
    } ?: model.message
    val background = model.background
    if (text == null && background == null) return
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        if (job != null) {
            val total = job.total
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { job.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
            if (background != null) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(background, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (job == null && text != null) TextButton(onClick = { model.message = null }) { Text("Dismiss") }
        }
    }
}
