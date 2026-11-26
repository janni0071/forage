package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.PlayerPart
import kotlinx.coroutines.delay

/**
 * Setting up mpv, the desktop's best player (no ads, and Forage remembers where you stopped):
 * what it needs (mpv, yt-dlp, Deno), what's there, one button for the rest, and the terminal
 * commands to copy for those who'd rather. Once everything's there, one line says so. Nothing
 * where no player needs installing (phones).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MpvSetupCard(model: AppModel) {
    val parts = model.playerParts
    if (parts.isEmpty()) return
    val c = MaterialTheme.colorScheme
    if (parts.all { it.ready } && model.playerSetupProblem == null) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
            Text(
                "mpv is all set: " + parts.joinToString(", ") { p -> p.name + (p.detail?.takeIf { "Forage's own" in it }?.let { " ($it)" } ?: "") } + ".",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }
    val busy = model.playerSetupStatus != null
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surfaceContainer)
            .border(2.dp, c.primary, RoundedCornerShape(18.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Accent.pastelPink), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.OndemandVideo, contentDescription = null, tint = Accent.onAccent, modifier = Modifier.size(22.dp))
            }
            Text(
                "Best with mpv", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            "No ads, and Forage remembers where you stopped in every video. It takes three free programs:",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
        for (part in parts) PartRow(part)
        Spacer(Modifier.size(12.dp))
        val missing = parts.filter { !it.ready }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            AccentButton(if (missing.size == parts.size) "Install all three" else "Install what's missing", null, enabled = !busy && missing.any { it.installable }, onClick = model::setUpPlayer)
            TextButton(onClick = model::checkPlayers, enabled = !busy) { Text("Check again") }
        }
        if (missing.any { it.id == "mpv" && it.installable }) {
            Text(
                "mpv asks for your password, as installing system software does; yt-dlp and Deno go into Forage's own folder and need none.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
        model.playerSetupStatus?.let { status ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
            }
        }
        model.playerSetupProblem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(top = 10.dp))
        }
        val commands = missing.mapNotNull { it.command }
        if (commands.isNotEmpty()) {
            Text(
                "Or install them yourself in a terminal, then Check again:",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            for (command in commands) Command(command)
        }
    }
}

@Composable
private fun PartRow(part: PlayerPart) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (part.ready) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (part.ready) "Installed" else "Missing",
            tint = if (part.ready) c.primary else c.outline,
            modifier = Modifier.padding(top = 1.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(part.name, style = MaterialTheme.typography.titleSmall)
            Text(part.purpose, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            part.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (part.ready) c.primary else c.onSurfaceVariant) }
        }
    }
}

/** A terminal command with a Copy button. */
@Composable
private fun Command(command: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceContainerHighest)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(command))
            copied = true
        }) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, contentDescription = if (copied) "Copied" else "Copy", modifier = Modifier.size(18.dp))
        }
    }
}
