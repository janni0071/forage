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
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
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

/** The time now, moving on every [everyMs], so "5 minutes ago" stays true while it's on screen. */
@Composable
internal fun rememberNow(everyMs: Long = 30_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(everyMs) {
        while (true) {
            kotlinx.coroutines.delay(everyMs)
            now = Instant.now()
        }
    }
    return now
}

/** How sync stands, in a line: "Synced with Pixel 9 · just now", or that it isn't set up. */
@Composable
internal fun syncLine(sync: SyncController): String {
    val now = rememberNow()
    val device = sync.devices.maxByOrNull { it.syncedAt ?: Instant.EPOCH } ?: return "Not paired yet"
    return when {
        sync.problem != null -> "${device.name}: can't sync right now"
        sync.syncing && sync.reachesOut -> "Syncing with ${device.name}…"
        device.syncedAt == null -> "${device.name} · not synced yet"
        else -> "${device.name} · " + shortAgo(device.syncedAt!!, now)
    }
}

/** How sync stands, for its icon: not set up, fine, or in trouble. */
internal enum class SyncState { OFF, SYNCED, SYNCING, TROUBLE }

internal fun syncState(sync: SyncController): SyncState = when {
    sync.devices.isEmpty() -> SyncState.OFF
    sync.problem != null -> SyncState.TROUBLE
    sync.syncing && sync.reachesOut -> SyncState.SYNCING
    else -> SyncState.SYNCED
}

/**
 * The sync icon: crossed out until a device is paired, with a dot once one is (the accent when all
 * is well, the error colour when it can't sync), turning while the phone syncs.
 */
@Composable
fun SyncIcon(sync: SyncController, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val c = MaterialTheme.colorScheme
    val state = syncState(sync)
    val turning = androidx.compose.animation.core.rememberInfiniteTransition()
    val angle by turning.animateFloat(
        0f, -360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
    )
    androidx.compose.foundation.layout.Box {
        Icon(
            if (state == SyncState.OFF) Icons.Outlined.SyncDisabled else Icons.Outlined.Sync,
            contentDescription = null,
            tint = if (state == SyncState.OFF) c.onSurfaceVariant else c.onSurface,
            modifier = Modifier.size(size).then(if (state == SyncState.SYNCING) Modifier.rotate(angle) else Modifier),
        )
        if (state != SyncState.OFF) {
            androidx.compose.foundation.layout.Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(c.surfaceContainerLow).padding(1.5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (state == SyncState.TROUBLE) c.error else Accent.pastelPink),
            )
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago": short, for one line beside a name. */
internal fun shortAgo(at: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> formatAge(at, now) ?: "a while ago"
    }
}

/** Settings' sync card: the paired devices, and pairing another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncCard(sync: SyncController) {
    val c = MaterialTheme.colorScheme
    val now = rememberNow()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp)) {
        for (device in sync.devices) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(device.kind ?: if (sync.isPhone) "computer" else "phone"), contentDescription = null, tint = c.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (sync.syncing && sync.reachesOut) "Syncing…" else formatSynced(device.syncedAt, now),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { sync.remove(device) }) { Text("Remove") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(if (sync.devices.isEmpty()) "Pair a device" else "Pair another device", null, onClick = sync::openPairing)
            if (sync.reachesOut) {
                OutlinedButton(onClick = { sync.syncNow(manual = true) }, enabled = !sync.syncing, shape = RoundedCornerShape(12.dp)) { Text("Sync now") }
            }
        }
        sync.problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            if (sync.vault) {
                "This vault syncs with the private vault on each paired device while it's unlocked on both, paired apart from the " +
                    "main library with a key of its own. While it's locked, nothing about it shows on the network, not even that " +
                    "there is one; changes wait until both are unlocked again."
            } else {
                "Likes, the history, the queue, playlists, subscriptions and where you left off stay the same on your devices, " +
                    "over your home Wi-Fi, encrypted, with no account or server in between. A computer syncs whenever Forage runs " +
                    "on it; a phone or tablet while Forage is open on it. So two phones or tablets sync when Forage is open on both " +
                    "at once; with a computer paired too, it passes changes on between them at any time. The private vault syncs " +
                    "on its own: pair it from inside the vault."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A device's icon: a phone, a tablet, a computer. */
internal fun kindIcon(kind: String?): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    "tablet" -> Icons.Outlined.TabletAndroid
    "computer" -> Icons.Outlined.Computer
    else -> Icons.Outlined.PhoneAndroid
}

/** The pairing dialog, while "Pair a device" is open. */
@Composable
fun SyncDialogs(sync: SyncController) {
    if (sync.pairingOpen) PairDialog(sync)
}

/**
 * Pairing, the same on every device: open "Pair a device" on both; each lists the other; pick it
 * on one, and the other asks whether the codes match. A device asking to pair comes first.
 */
@Composable
private fun PairDialog(sync: SyncController) {
    val c = MaterialTheme.colorScheme
    val request = sync.request
    val pairing = sync.pairing
    AlertDialog(
        onDismissRequest = sync::closePairing,
        title = {
            Text(
                when {
                    request != null -> "Pair with ${request.name}?"
                    pairing is SyncController.Pairing.Confirming -> "Compare the codes"
                    pairing is SyncController.Pairing.Paired -> "Paired"
                    else -> "Pair a device"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    request != null -> {
                        Text("Pair if ${request.name} shows the same code:")
                        Code(request.code)
                        Text(
                            "A different code means another device is answering in between: don't pair then.",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                    pairing is SyncController.Pairing.Confirming -> {
                        Text("${pairing.device.name} shows a code too. If it's this one, choose Pair there:")
                        Code(pairing.code)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Waiting for ${pairing.device.name}…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    }
                    pairing is SyncController.Pairing.Paired -> Text(
                        "This ${sync.kind} and ${pairing.name} now stay in step. The first sync is running; the next ones happen by themselves.",
                    )
                    pairing is SyncController.Pairing.Failed -> {
                        Text(pairing.message)
                        Text(
                            "Is " + (if (sync.vault) "the private vault unlocked there, with Pair a device open in its Settings" else "Pair a device open there too") +
                                ", and are both on the same Wi-Fi?",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            if (sync.vault) "On the other device, unlock the private vault, then open Settings, Sync, Pair a device. Both need to be on the same Wi-Fi."
                            else "On the other device, open Forage, then Settings, Sync, Pair a device. Both need to be on the same Wi-Fi.",
                        )
                        for (other in sync.found) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh)
                                    .clickable { sync.pairWith(other) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(kindIcon(other.kind ?: "computer"), contentDescription = null)
                                Text(other.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                if (sync.found.isEmpty()) "  Looking for other devices…" else "  Still looking…",
                                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (sync.notFindable) "Another program uses the port Forage is looked for on (47731), so other devices may not find this one."
                            else "If they don't find each other, a firewall may be in the way: Forage uses ports 47731 (UDP) and 47732 (TCP) on the home network.",
                            style = MaterialTheme.typography.bodySmall, color = if (sync.notFindable) c.error else c.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                request != null -> TextButton(onClick = { sync.answer(true) }) { Text("Pair") }
                pairing is SyncController.Pairing.Paired -> TextButton(onClick = sync::closePairing) { Text("Done") }
                pairing is SyncController.Pairing.Failed -> TextButton(onClick = sync::dismissPairing) { Text("Back") }
                else -> TextButton(onClick = sync::closePairing) { Text("Cancel") }
            }
        },
        dismissButton = {
            if (request != null) TextButton(onClick = { sync.answer(false) }) { Text("Don't pair") }
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
