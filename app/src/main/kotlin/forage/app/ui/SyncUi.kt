package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forage.app.SyncController
import java.time.Duration
import java.time.Instant

/** "just now", "5 minutes ago", "yesterday at 18:02"... for when a device last synced. */
internal fun formatSynced(at: Instant?, now: Instant = Instant.now()): String {
    if (at == null) return "Not synced yet"
    val minutes = Duration.between(at, now).toMinutes()
    return "Synced " + when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 24 * 60 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} ago"
        else -> formatAge(at, now) ?: "a while ago"
    }
}

/** Settings' sync card: the paired devices, and pairing another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncCard(sync: SyncController) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
        for (device in sync.devices) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sync.isPhone) Icons.Outlined.Computer else Icons.Outlined.PhoneAndroid, contentDescription = null, tint = c.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (sync.syncing && sync.isPhone) "Syncing…" else formatSynced(device.syncedAt),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { sync.remove(device) }) { Text("Remove") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sync.isPhone) {
                AccentButton(if (sync.devices.isEmpty()) "Pair with a computer" else "Pair another computer", null, onClick = sync::findComputers)
                if (sync.devices.isNotEmpty()) {
                    OutlinedButton(onClick = { sync.syncNow(manual = true) }, enabled = !sync.syncing, shape = RoundedCornerShape(12.dp)) { Text("Sync now") }
                }
            } else {
                AccentButton(if (sync.devices.isEmpty()) "Pair a phone" else "Pair another phone", null, onClick = sync::openPairing)
            }
        }
        sync.problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            "Likes, the history, the queue, playlists, subscriptions and where you left off stay the same on both, over your " +
                "home Wi-Fi, encrypted, with no account or server in between. " +
                (if (sync.isPhone) "The phone syncs while Forage is open on both." else "The phone syncs while Forage is open on both; this computer just has to be on.") +
                " The private vault doesn't sync yet.",
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** The pairing dialogs: the computer waiting for a phone and comparing codes, the phone finding the computer. */
@Composable
fun SyncDialogs(sync: SyncController) {
    if (!sync.isPhone && sync.pairingOpen) ComputerPairing(sync)
    sync.pairing?.let { PhonePairing(sync, it) }
}

@Composable
private fun ComputerPairing(sync: SyncController) {
    val request = sync.request
    AlertDialog(
        onDismissRequest = sync::closePairing,
        title = { Text(if (request == null) "Pair a phone" else "Pair with ${request.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request == null) {
                    Text("On your phone, open Forage, then Settings, Sync, and tap Pair with a computer. The phone needs to be on the same Wi-Fi as this computer.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Waiting for the phone…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (sync.notFindable) {
                        Text(
                            "Another program uses the port phones look for Forage on (47731), so the phone may not find this computer.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "If the phone doesn't find this computer, a firewall may be in the way: Forage needs ports 47731 (UDP) and 47732 (TCP) on the home network.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("Pair if your phone shows the same code:")
                    Code(request.code)
                    Text(
                        "A different code means another device is answering in between: don't pair then.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (request != null) TextButton(onClick = { sync.answer(true) }) { Text("Pair") }
            else TextButton(onClick = sync::closePairing) { Text("Cancel") }
        },
        dismissButton = { if (request != null) TextButton(onClick = { sync.answer(false) }) { Text("Don't pair") } },
    )
}

@Composable
private fun PhonePairing(sync: SyncController, state: SyncController.Pairing) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = sync::closePhonePairing,
        title = {
            Text(
                when (state) {
                    is SyncController.Pairing.Confirming -> "Compare the codes"
                    is SyncController.Pairing.Paired -> "Paired"
                    else -> "Pair with a computer"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    SyncController.Pairing.Searching -> {
                        Text("On the computer, open Forage, then Settings, Sync, and choose Pair a phone. Both need to be on the same Wi-Fi.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Looking for it…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                    is SyncController.Pairing.Found -> {
                        Text("Choose your computer:")
                        for (computer in state.computers) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh)
                                    .clickable { sync.pairWith(computer) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Computer, contentDescription = null)
                                Text(computer.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    is SyncController.Pairing.Confirming -> {
                        Text("${state.computer.name} shows a code too. If it's this one, choose Pair there:")
                        Code(state.code)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Waiting for the computer…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                    is SyncController.Pairing.Paired -> Text("This phone and ${state.name} now stay in step. The first sync is running; the next ones happen by themselves while Forage is open on both.")
                    is SyncController.Pairing.Failed -> {
                        Text(state.message)
                        Text(
                            "Is Forage open on the computer, with Pair a phone showing, and are both on the same Wi-Fi?",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is SyncController.Pairing.Failed -> TextButton(onClick = sync::findComputers) { Text("Search again") }
                is SyncController.Pairing.Paired -> TextButton(onClick = sync::closePhonePairing) { Text("Done") }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is SyncController.Pairing.Paired) TextButton(onClick = sync::closePhonePairing) { Text("Cancel") }
        },
    )
}

/** A pairing code, big enough to compare across the room. */
@Composable
private fun Code(code: String) {
    Text(
        code,
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
