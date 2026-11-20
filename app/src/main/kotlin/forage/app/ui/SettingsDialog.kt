@file:OptIn(ExperimentalMaterial3Api::class)

package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.Player
import forage.app.Theme

@Composable
fun SettingsDialog(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    BasicAlertDialog(onDismissRequest = { model.settingsOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = !compact)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = c.surfaceContainerLow,
            modifier = if (compact) Modifier.fillMaxWidth().padding(horizontal = 12.dp) else Modifier.width(600.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 20.dp else 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { model.settingsOpen = false }) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }

                // Phones open videos in the app the system has for the link, so there's nothing to choose.
                if (model.platform.players.isNotEmpty()) {
                    Section("Playback", "Where videos open. Forage only finds and organizes them.")
                    // Checking for players starts small processes, so do it once per opening.
                    val problems = remember { model.platform.players.associateWith { model.platform.playerProblem(it) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (p in model.platform.players) {
                            PlayerCard(
                                player = p,
                                problem = problems[p],
                                selected = model.player == p,
                                modifier = Modifier.weight(1f),
                            ) { model.choosePlayer(p) }
                        }
                    }
                    if (model.player == Player.MPV && problems[Player.MPV] == "needs yt-dlp") {
                        Hint("mpv needs a current yt-dlp to play YouTube videos. Install it with 'pipx install yt-dlp', or use mpv from Flathub, which includes it.")
                    }
                }

                val look = model.vaultLook
                if (look != null) {
                    // The private vault wears a look of its own, always dark, kept inside the vault.
                    Section("Look", "The private vault's own colours, apart from the rest of Forage.")
                    Segmented(
                        options = listOf(
                            Triple(forage.app.VaultLook.NEON, "Neon", null),
                            Triple(forage.app.VaultLook.VELVET, "Velvet", null),
                            Triple(forage.app.VaultLook.NOIR, "Noir", null),
                        ),
                        selected = look,
                        onSelect = model::changeVaultLook,
                    )
                } else {
                    Section("Appearance", null)
                    Segmented(
                        options = listOf(
                            Triple(Theme.SYSTEM, "System", Icons.Outlined.BrightnessAuto),
                            Triple(Theme.LIGHT, "Light", Icons.Outlined.LightMode),
                            Triple(Theme.DARK, "Dark", Icons.Outlined.DarkMode),
                        ),
                        selected = model.theme,
                        onSelect = model::changeTheme,
                    )
                }

                if (!model.isPrivate && !model.platform.isMobile) {
                    SwitchRow(
                        title = "Forage's own title bar",
                        text = "Window buttons in the top corner instead of the system's title bar. Turn off if moving or resizing " +
                            "the window misbehaves on your desktop. Takes effect when Forage starts next.",
                        checked = !model.systemTitleBar,
                        onChange = { model.useSystemTitleBar(!it) },
                    )
                }

                // Phones size the interface through the system's display settings.
                if (!model.platform.isMobile) {
                    Section("Interface size", "Also Ctrl + Plus, Ctrl + Minus, and Ctrl + 0 for automatic.")
                    Segmented(
                        options = listOf<Triple<Float?, String, androidx.compose.ui.graphics.vector.ImageVector?>>(
                            Triple(null, "Auto", null),
                        ) + forage.app.Zoom.choices.map { Triple(it, forage.app.Zoom.label(it), null) },
                        selected = model.uiScale,
                        onSelect = model::changeUiScale,
                    )
                }

                Section("Thumbnails", null)
                SwitchRow(
                    title = "Show thumbnails",
                    text = if (model.isPrivate) "Loaded from each site's image server, one request per video, and kept in memory only. " +
                        "Turn off to send no requests for them."
                    else "Loaded from YouTube's image server (i.ytimg.com), one request per video, then kept on disk. " +
                        "Turn off to send no requests for them.",
                    checked = model.showThumbnails,
                    onChange = model::setThumbnails,
                )
                val thumbnailProblems = remember { model.thumbnails.problems() }
                if (thumbnailProblems.isNotEmpty()) {
                    Hint(
                        "Some didn't load since Forage started: " +
                            thumbnailProblems.joinToString("; ") { (reason, n) -> "$n × $reason" } + ".",
                    )
                }

                Section("History", null)
                SwitchRow(
                    title = "Record history",
                    text = "Videos you open from Forage go into the history in your library, and aren't recommended again. " +
                        "Remove single videos or clear it all there." +
                        (if (model.isPrivate) " This vault's history stays in the vault, encrypted." else ""),
                    checked = model.recordHistory,
                    onChange = model::changeRecordHistory,
                )

                if (model.offersCatalogue) {
                    Section(
                        "Catalogue",
                        "The site's public list of all its videos (its \"embed dump\"), imported once: Discover then matches " +
                            "your taste against millions of videos instead of a few searches, and related videos and previews come " +
                            "from it. Kept unencrypted in Forage's data folder; it holds only the site's catalogue, nothing about you.",
                    )
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
                        Text(model.catalogueInfo ?: "Not imported yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (model.catalogueInfo == null) "Forage downloads the dump from the site (1.6 GB), imports it in a few minutes and deletes " +
                                "the download; the catalogue then takes about 1.3 GB. If the download breaks off, starting it again continues it." +
                                (if (model.platform.isMobile) " A catalogue made on a computer (its catalogues/pornhub.db) works too." else "")
                            else "The site updates the dump now and then; download it again to catch up." +
                            (if (model.platform.isMobile) " If a phone runs out of memory importing it, import it on a computer and choose its catalogues/pornhub.db here instead." else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                        )
                        // Wrapped on a phone, where the three don't fit side by side.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccentButton(if (model.catalogueInfo == null) "Download and import" else "Download again", null, enabled = model.job == null) {
                                model.downloadCatalogue()
                            }
                            OutlinedButton(onClick = { model.chooseCatalogueFile() }, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                                Text(if (compact) "Choose a file…" else "Choose a downloaded file…", maxLines = 1)
                            }
                            if (model.catalogueInfo != null) TextButton(onClick = model::removeCatalogue) { Text("Remove") }
                        }
                    }
                }

                if (model.isPrivate) {
                    Section(
                        "This private vault",
                        "Stored encrypted; nothing from it is written to disk in the clear, not even thumbnails or settings. " +
                            "Changes to these settings last until you lock the vault.",
                    )
                    return@Column
                }
                // A phone keeps the data inside the app, where no file manager reaches.
                if (model.platform.isMobile) return@Column
                Section("Your data", "Everything Forage knows lives in one folder on this computer. Nothing is sent anywhere else.")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val home = System.getProperty("user.home")
                    Text(
                        model.dataDir.absolutePath.let { if (it.startsWith(home)) "~" + it.removePrefix(home) else it },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = model::openDataFolder, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String?) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 26.dp, bottom = if (text == null) 10.dp else 2.dp),
    )
    text?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun PlayerCard(player: Player, problem: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val usable = problem != "not installed"
    val icon = when (player) {
        Player.FREETUBE -> Icons.Outlined.SmartDisplay
        Player.MPV -> Icons.Outlined.OndemandVideo
        Player.BROWSER -> Icons.Outlined.Language
    }
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.primaryContainer else c.surfaceContainer)
            .border(2.dp, if (selected) c.primary else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(enabled = usable, onClick = onClick)
            .pointerHoverIcon(if (usable) PointerIcon.Hand else PointerIcon.Default)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (selected) Accent.pastelPink else c.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    // The pastel circle is light in both themes, so the icon on it stays dark.
                    tint = when {
                        selected -> Accent.onAccent
                        usable -> c.onSurface
                        else -> c.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = c.primary, modifier = Modifier.size(22.dp))
        }
        Text(
            player.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (usable) c.onSurface else c.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 12.dp),
        )
        val (status, color) = when (problem) {
            null -> "Ready" to c.primary
            "needs yt-dlp" -> "Needs yt-dlp" to c.error
            else -> "Not installed" to c.onSurfaceVariant.copy(alpha = 0.6f)
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun <T> Segmented(options: List<Triple<T, String, ImageVector?>>, selected: T, onSelect: (T) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(4.dp)) {
        for ((value, label, icon) in options) {
            val on = value == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.surfaceContainerLowest else Color.Transparent)
                    .clickable { onSelect(value) }
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = if (on) c.primary else c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) c.onSurface else c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
    )
}
