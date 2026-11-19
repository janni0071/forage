package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import forage.app.AppModel
import forage.app.ProfileDialog

/** The profile card at the bottom of the sidebar; click to switch, create or delete profiles. */
@Composable
fun ProfileSwitcher(model: AppModel) {
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
                Text("${model.profileSize} liked", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
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
            if (model.isPrivate) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    text = { Text("Lock private vault") },
                    onClick = { model.profileMenuOpen = false; model.onLock?.invoke() },
                )
            } else if (model.offersPrivateVault) {
                // Always shown, whether or not a private vault exists, so the menu never gives one away.
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    text = { Text("Private profiles…") },
                    onClick = { model.profileMenuOpen = false; model.privateError = null; model.profileDialog = ProfileDialog.PRIVATE },
                )
            }
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
private fun ProfileDialogs(model: AppModel) {
    when (model.profileDialog) {
        ProfileDialog.NEW -> NewProfileDialog(model)
        ProfileDialog.DELETE -> AlertDialog(
            onDismissRequest = { model.profileDialog = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete \"${model.profile.name}\"?") },
            text = {
                Text(
                    "Its ${model.profileSize} likes, topics, pins and recommendations go. " +
                        "Your library, watch history and watch later queue are shared and stay as they are."
                )
            },
            confirmButton = { TextButton(onClick = model::deleteProfile) { Text("Delete profile", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { model.profileDialog = null }) { Text("Keep it") } },
        )
        ProfileDialog.PRIVATE -> PrivateVaultDialog(model)
        null -> {}
    }
}

@Composable
private fun NewProfileDialog(model: AppModel) {
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
                    "A profile is a separate taste: its own likes, topics and recommendations, like \"Learning\" or \"Evening\". " +
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

/**
 * Unlocks the private vault, or creates one. It never says whether a vault exists: a wrong PIN
 * and no vault at all get the same answer.
 */
@Composable
private fun PrivateVaultDialog(model: AppModel) {
    var creating by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val shownProblem = problem ?: model.privateError
    val submit = {
        problem = when {
            pin.isEmpty() -> "Enter your PIN or passphrase"
            creating && pin.length < 6 -> "Use at least 6 characters; a few words are best"
            creating && pin != repeat -> "The two entries don't match"
            else -> null
        }
        if (problem == null) {
            model.unlockPrivate(pin.toCharArray(), create = creating)
            pin = ""
            repeat = ""
        }
    }
    AlertDialog(
        onDismissRequest = { if (!model.privateBusy) model.profileDialog = null },
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(if (creating) "Create a private vault" else "Private profiles") },
        text = {
            Column(Modifier.widthIn(max = 460.dp)) {
                Text(
                    if (creating) {
                        "A private vault is separate from everything else in Forage: its own library, profiles and " +
                            "recommendations, stored encrypted. Until you unlock it, nothing about it shows anywhere.\n\n" +
                            "There's no way to recover a forgotten PIN, not even for you. Prefer a few words over a short " +
                            "number: someone with the file could try short PINs one by one."
                    } else {
                        "Enter the PIN or passphrase of your private vault."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                SecretField(pin, if (creating) "New PIN or passphrase" else "PIN or passphrase", shownProblem.takeIf { !creating }, submit) {
                    pin = it; problem = null; model.privateError = null
                }
                if (creating) {
                    Spacer(Modifier.height(10.dp))
                    SecretField(repeat, "Repeat it", shownProblem, submit) { repeat = it; problem = null }
                }
                if (model.privateBusy) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Unlocking… this takes a moment on purpose", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!creating) {
                    TextButton(
                        onClick = { creating = true; problem = null; model.privateError = null },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Create a private vault…") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !model.privateBusy) { Text(if (creating) "Create" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = { if (creating) creating = false else model.profileDialog = null }, enabled = !model.privateBusy) {
                Text(if (creating) "Back" else "Cancel")
            }
        },
    )
}

@Composable
private fun SecretField(value: String, label: String, problem: String?, onSubmit: () -> Unit, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = problem != null,
        supportingText = problem?.let { { Text(it) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onSubmit(); true } else false
        },
    )
}
