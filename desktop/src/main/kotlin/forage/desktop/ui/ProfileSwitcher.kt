package forage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.desktop.DesktopModel
import forage.desktop.ProfileDialog

/** The profile card at the bottom of the sidebar; click to switch, create or delete profiles. */
@Composable
fun ProfileSwitcher(model: DesktopModel) {
    val c = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (model.profileMenuOpen) c.surfaceContainerHigh else Color.Transparent)
                .clickable { model.profileMenuOpen = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(model.profile.name, size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("${model.profileSize} videos", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Outlined.UnfoldMore, contentDescription = "Switch profile", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = model.profileMenuOpen, onDismissRequest = { model.profileMenuOpen = false }, modifier = Modifier.widthIn(min = 260.dp)) {
            Text(
                "Profiles",
                style = MaterialTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            for ((ref, size) in model.profileList) {
                DropdownMenuItem(
                    leadingIcon = { Avatar(ref.name, size = 28) },
                    text = {
                        Column {
                            Text(ref.name, fontWeight = if (ref == model.profile) FontWeight.SemiBold else FontWeight.Normal)
                            Text("$size videos", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (ref == model.profile) Icon(Icons.Filled.Check, contentDescription = "Current", tint = c.primary) },
                    onClick = { model.switchProfile(ref) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New profile…") },
                onClick = { model.profileMenuOpen = false; model.profileDialog = ProfileDialog.NEW },
            )
            if (model.profileList.size > 1) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    text = { Text("Delete \"${model.profile.name}\"…") },
                    onClick = { model.profileMenuOpen = false; model.profileDialog = ProfileDialog.DELETE },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // Always shown, whether or not a private vault exists, so the menu never gives one away.
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                text = { Text("Private profiles…") },
                onClick = { model.profileMenuOpen = false; model.profileDialog = ProfileDialog.PRIVATE },
            )
        }
    }
    ProfileDialogs(model)
}

@Composable
private fun Avatar(name: String, size: Int) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
        Text(
            name.take(1).uppercase(),
            style = if (size >= 36) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = c.onPrimaryContainer,
        )
    }
}

@Composable
private fun ProfileDialogs(model: DesktopModel) {
    when (model.profileDialog) {
        ProfileDialog.NEW -> NewProfileDialog(model)
        ProfileDialog.DELETE -> AlertDialog(
            onDismissRequest = { model.profileDialog = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete \"${model.profile.name}\"?") },
            text = {
                Text(
                    "Its ${model.profileSize} profile videos, topics, pins and recommendations go. " +
                        "Your library, watch history and watch later queue are shared and stay as they are."
                )
            },
            confirmButton = { TextButton(onClick = model::deleteProfile) { Text("Delete profile", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { model.profileDialog = null }) { Text("Keep it") } },
        )
        ProfileDialog.PRIVATE -> AlertDialog(
            onDismissRequest = { model.profileDialog = null },
            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            title = { Text("Private profiles") },
            text = {
                Text(
                    "Private profiles will live in their own encrypted vault, unlocked with a PIN. Until you unlock it, " +
                        "nothing about it shows anywhere in Forage, and it never mixes with your other profiles.\n\n" +
                        "This isn't built yet: it comes together with the first private platform."
                )
            },
            confirmButton = { TextButton(onClick = { model.profileDialog = null }) { Text("OK") } },
        )
        null -> {}
    }
}

@Composable
private fun NewProfileDialog(model: DesktopModel) {
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val create = { problem = model.createProfile(name) }
    AlertDialog(
        onDismissRequest = { model.profileDialog = null },
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        title = { Text("New profile") },
        text = {
            Column {
                Text(
                    "A profile is a separate taste: its own videos, topics and recommendations, like \"Learning\" or \"Evening\". " +
                        "It shares your library and watch later queue.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; problem = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { create() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { model.profileDialog = null }) { Text("Cancel") } },
    )
}
