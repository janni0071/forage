package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

/** Settings' backup card: back up now, restore, and the automatic backups to go back to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupCard(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Back up now", null, enabled = model.job == null, onClick = model::backUp)
            OutlinedButton(onClick = model::chooseBackup, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                Text("Restore from a backup…", maxLines = 1)
            }
        }
        val backups = model.automaticBackups
        if (model.backupsDir != null) {
            Text(
                "Forage also backs up by itself once a day, keeping the last week, on this " +
                    (if (model.platform.isMobile) "phone" else "computer") + ". They help after a mistake, not if the " +
                    (if (model.platform.isMobile) "phone" else "computer") + " is lost: for that, Back up now to somewhere else " +
                    "(a cloud folder, another device).",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            for (file in backups) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(WHEN.format(Instant.ofEpochMilli(file.lastModified())), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull("Before a restore".takeIf { "before-restore" in file.name }, size(file.length())).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { model.restoreAutomatic(file) }, enabled = model.job == null) { Text("Restore") }
                }
            }
        } else {
            Text(
                "A backup of this vault is encrypted with a PIN or passphrase you choose; the vault's own is easiest to remember. Restore it " +
                    "inside a private vault, on this device or another. There are no automatic backups of a vault: they'd need its PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun size(bytes: Long): String =
    if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${maxOf(1, bytes / 1024)} KB"

/** Settings' export card: readable copies for other apps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportCard(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::exportLibrary, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                Text("Everything, as JSON", maxLines = 1)
            }
            if (model.youTubeFollowing > 0) {
                OutlinedButton(onClick = model::exportSubscriptions, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                    Text("Subscriptions for other apps", maxLines = 1)
                }
            }
        }
        Text(
            "Everything: what you liked, the history, playlists, the queue, who you follow and where you left off, readable by " +
                "people and programs." +
                (if (model.youTubeFollowing > 0)  " Subscriptions: your ${model.youTubeFollowing} YouTube channel${if (model.youTubeFollowing == 1) "" else "s"} in NewPipe's format, " +
                    "which NewPipe, FreeTube and LibreTube import." else "") +
                (if (model.isPrivate) " Exports aren't encrypted: whoever has the file can read it." else ""),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** The dialogs backing up and restoring ask with: a PIN to seal or open a backup, and "replace your library?". */
@Composable
fun BackupDialogs(model: AppModel) {
    if (model.askingBackupPin) {
        PinDialog(
            title = "Encrypt this backup",
            text = "Choose a PIN or passphrase for the backup, at least 6 characters. The vault's own is easiest to remember. Without it nobody can open the backup, you neither; a few words are safer than a short number if the file might end up somewhere others can get at it.",
            confirmLabel = "Back up",
            repeat = true,
            problem = null,
            onConfirm = model::backUpWithPin,
            onDismiss = { model.askingBackupPin = false },
        )
    }
    model.restorePinFor?.let { (file, problem) ->
        PinDialog(
            title = "Open this backup",
            text = "${file.name} is encrypted. Enter the PIN or passphrase it was backed up with.",
            confirmLabel = "Open",
            repeat = false,
            problem = problem,
            onConfirm = { model.offerRestore(file, it) },
            onDismiss = model::cancelRestore,
        )
    }
    model.restoreOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = model::cancelRestore,
            title = { Text(if (model.isPrivate) "Replace this vault with the backup?" else "Replace your library with the backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "From ${WHEN.format(offer.about.createdAt)}" + (offer.about.device?.let { " on $it" } ?: "") + ".",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text("It holds ${offer.summary}.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Everything now in " + (if (model.isPrivate) "this vault" else "your library") + " is replaced by it. " +
                            (if (model.isPrivate) "There's no way back unless you back up the vault first."
                            else "Forage backs up the library as it is first, so you can go back."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = model::confirmRestore) { Text("Replace") } },
            dismissButton = { TextButton(onClick = model::cancelRestore) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinDialog(
    title: String,
    text: String,
    confirmLabel: String,
    repeat: Boolean,
    problem: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = repeat && again.isNotEmpty() && again != pin
    val ready = pin.isNotEmpty() && (!repeat || (pin.length >= 6 && again == pin))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    pin, { pin = it }, label = { Text("PIN or passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                )
                if (repeat) {
                    OutlinedTextField(
                        again, { again = it }, label = { Text("The same again") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) ({ Text("Not the same") }) else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pin.toCharArray()) }, enabled = ready) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
