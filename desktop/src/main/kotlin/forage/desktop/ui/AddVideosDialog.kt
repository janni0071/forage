package forage.desktop.ui

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
import forage.desktop.DesktopModel

/** Adding videos to the profile: by searching (SPEC, Manual add) or by pasting links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVideosDialog(model: DesktopModel) {
    val c = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(if (model.searchPlatforms.isEmpty()) Tab.LINKS else Tab.SEARCH) }
    BasicAlertDialog(onDismissRequest = model::closeAddDialog) {
        Surface(shape = RoundedCornerShape(28.dp), color = c.surfaceContainerLow, modifier = Modifier.width(720.dp)) {
            Column(Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add videos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = model::closeAddDialog) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }
                Text(
                    "They go straight into the profile \"${model.profile.name}\".",
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
                if (model.searchPlatforms.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        ToggleChip("Search", tab == Tab.SEARCH) { tab = Tab.SEARCH }
                        ToggleChip("Paste links", tab == Tab.LINKS) { tab = Tab.LINKS }
                    }
                }
                when (tab) {
                    Tab.SEARCH -> SearchTab(model)
                    Tab.LINKS -> LinksTab(model)
                }
            }
        }
    }
}

private enum class Tab { SEARCH, LINKS }

@Composable
private fun SearchTab(model: DesktopModel) {
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val platform = model.searchPlatform ?: model.searchPlatforms.first().first

    if (model.searchPlatforms.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Search on", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            for ((id, name) in model.searchPlatforms) {
                ToggleChip(name, id == platform) {
                    if (id != platform) {
                        model.searchPlatform = id
                        model.searchVideos(query)
                    }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("What are you looking for?") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.searchVideos(query) }),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    model.searchVideos(query)
                    true
                } else false
            },
        )
        AccentButton("Search", null, enabled = query.isNotBlank() && !model.searching) { model.searchVideos(query) }
    }

    Box(Modifier.fillMaxWidth().height(440.dp).padding(top = 12.dp)) {
        when {
            model.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            model.searchResults.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(model.searchResults, key = { it.id.toString() }) { stub -> SearchRow(model, stub) }
            }
            else -> Text(
                model.searchProblem ?: "Search for videos to add, like you would on the site itself.",
                color = c.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun SearchRow(model: DesktopModel, stub: VideoStub) {
    val c = MaterialTheme.colorScheme
    val added = stub.id in model.searchInProfile
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (added) c.primaryContainer.copy(alpha = 0.35f) else c.surfaceContainerLow)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.clickable { model.open(stub.id) }.pointerHoverIcon(PointerIcon.Hand)) {
            Thumbnail(model, stub.id, 160.dp)
            formatDuration(stub.durationSeconds)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stub.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(stub.channelName, formatAge(stub.uploadedAt), "Short".takeIf { stub.isShort }).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (added) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("In profile", style = MaterialTheme.typography.labelLarge, color = c.primary)
            }
        } else {
            FilledTonalButton(onClick = { model.addFromSearch(stub) }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
    }
}

@Composable
private fun LinksTab(model: DesktopModel) {
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
