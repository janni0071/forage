package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.FORAGE_VERSION

private const val WHAT_IT_SENDS = "Forage asks GitHub, where its releases are published, once a day. Nothing else is sent, and nothing installs by itself."

/**
 * On Home: a newer Forage is out (with its notes and the file for this device), or, for whoever
 * hasn't been asked yet (the welcome skipped), whether to hear about new versions at all.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UpdateNotes(model: AppModel) {
    if (model.isPrivate || model.welcome != null) return
    val c = MaterialTheme.colorScheme
    val update = model.availableUpdate
    val card = Modifier.padding(bottom = 16.dp).fillMaxWidth().widthIn(max = 720.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(18.dp)
    if (update != null) {
        Column(card) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NewReleases, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
                Text("Forage ${update.version} is out", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = model::skipUpdate) { Icon(Icons.Outlined.Close, contentDescription = "Not this version") }
            }
            Text(
                "You have $FORAGE_VERSION. " + if (model.platform.isMobile) "Download the new app and open it to install it over this one; your library stays."
                else "Download the new package and open it to install it over this one; your library stays.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                update.download?.let { AccentButton("Download", null) { model.platform.openLink(it) } }
                OutlinedButton(onClick = { model.platform.openLink(update.page) }, shape = RoundedCornerShape(12.dp)) { Text("What's new") }
            }
        }
    } else if (model.updateCheck == null) {
        Column(card) {
            Text("Hear about new versions?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(WHAT_IT_SENDS, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("Yes, tell me", null) { model.changeUpdateCheck(true) }
                TextButton(onClick = { model.changeUpdateCheck(false) }) { Text("No thanks") }
            }
        }
    }
}

/** The welcome's question: a switch, off unless chosen. */
@Composable
fun UpdateSwitch(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
            .clickable { model.changeUpdateCheck(model.updateCheck != true) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Tell me when there's a new version", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(WHAT_IT_SENDS, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Switch(checked = model.updateCheck == true, onCheckedChange = model::changeUpdateCheck)
    }
}

/** Settings' part on updates: the version, the switch, looking now, and the new version if there is one. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UpdateSettings(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
        Text("Forage $FORAGE_VERSION · ${forage.app.Edition.NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clickable { model.changeUpdateCheck(model.updateCheck != true) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Tell me when there's a new version", style = MaterialTheme.typography.bodyLarge)
                Text(WHAT_IT_SENDS, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Switch(checked = model.updateCheck == true, onCheckedChange = model::changeUpdateCheck)
        }
        model.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)) }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.checkForUpdates(manual = true) }, shape = RoundedCornerShape(12.dp)) { Text("Look now") }
            model.availableUpdate?.download?.let { link -> AccentButton("Download ${model.availableUpdate?.version}", null) { model.platform.openLink(link) } }
            TextButton(onClick = { model.platform.openLink(forage.app.Updates.RELEASES_PAGE) }) { Text("All releases") }
        }
    }
}
