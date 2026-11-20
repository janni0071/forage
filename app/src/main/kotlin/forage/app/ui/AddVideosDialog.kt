package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.core.source.VideoStub
import forage.app.AppModel

/** Adding videos by their links (SPEC, Manual add); searching happens in the search bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVideosDialog(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    // On a phone the dialog takes the whole width; the system's default leaves a quarter of it empty.
    BasicAlertDialog(onDismissRequest = model::closeAddDialog, properties = DialogProperties(usePlatformDefaultWidth = !compact)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = c.surfaceContainerLow,
            modifier = if (compact) Modifier.fillMaxWidth().padding(horizontal = 12.dp) else Modifier.width(640.dp),
        ) {
            Column(Modifier.padding(if (compact) 20.dp else 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add by link", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = model::closeAddDialog) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }
                Text(
                    "The videos are liked in \"${model.profile.name}\". To find videos instead, use the search bar.",
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
                LinksTab(model)
            }
        }
    }
}

@Composable
private fun LinksTab(model: AppModel) {
    var link by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Text(
        "Paste a link from ${model.linkPlatforms}, or several, one per line.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    OutlinedTextField(
        value = link,
        onValueChange = { link = it; problem = null },
        label = { Text("Links") },
        placeholder = { Text("https://www.youtube.com/watch?v=…") },
        minLines = 4,
        maxLines = 10,
        isError = problem != null,
        supportingText = problem?.let { { Text(it) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = model::closeAddDialog) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        AccentButton("Add", null, enabled = link.isNotBlank()) { problem = model.addByLink(link) }
    }
}
