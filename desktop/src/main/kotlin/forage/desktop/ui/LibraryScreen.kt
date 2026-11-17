package forage.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.core.profile.VideoState
import forage.desktop.DesktopModel
import forage.desktop.LibraryRow
import forage.desktop.LibrarySource
import forage.desktop.StateFilter

@Composable
fun LibraryScreen(model: DesktopModel, onImport: () -> Unit) {
    if (model.sources.size <= 1 && model.rows.isEmpty() && model.currentSource == LibrarySource.History) {
        EmptyLibrary(onImport)
        return
    }
    val visible = model.visibleRows
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 24.dp, top = 24.dp)) {
        val where = when (val s = model.currentSource) {
            LibrarySource.History -> "your watch history"
            LibrarySource.InProfile -> "this profile's decisions"
            is LibrarySource.Playlist -> "\"${s.name}\""
        }
        ScreenHeader("Library", "${model.rows.size} videos in $where · ${model.profileSize} in your profile") {
            OutlinedButton(onClick = model::fetchDetails, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (model.selection.isEmpty()) "Fetch profile details" else "Fetch details of selection")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceMenu(model)
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = { Text("Search titles and channels") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(min = 300.dp, max = 440.dp),
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in StateFilter.entries) {
                FilterChip(
                    selected = model.stateFilter == f,
                    onClick = { model.stateFilter = f },
                    label = { Text(f.label, maxLines = 1) },
                    leadingIcon = if (model.stateFilter == f) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }
        ActionBar(model, visible.size)
        Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(visible, key = { it.id.toString() }) { row ->
                    VideoRow(model, row, selected = row.id in model.selection, onToggle = { model.toggle(row.id) }, onOpen = { model.open(row.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Import a Google Takeout export of your YouTube data. Videos land in the library as watched;\n" +
                "nothing enters your profile until you choose it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onImport) { Text("Import Takeout folder…") }
    }
}

@Composable
private fun SourceMenu(model: DesktopModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
            Text(model.currentSource.label)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in model.sources) {
                val label = if (s is LibrarySource.Playlist) "${s.label} (${s.size})" else s.label
                DropdownMenuItem(text = { Text(label) }, onClick = { model.selectSource(s); open = false })
            }
        }
    }
}

/** Selection tools: quiet while nothing is selected, a highlighted bar with the bulk actions once something is. */
@Composable
private fun ActionBar(model: DesktopModel, visibleCount: Int) {
    val selected = model.selection.size
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected > 0) c.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = if (selected > 0) 12.dp else 0.dp, vertical = 4.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (selected == 0) "$visibleCount shown" else "$selected selected",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(end = 6.dp),
        )
        TextButton(onClick = model::selectVisible, enabled = visibleCount > 0) { Text("Select all shown") }
        if (selected > 0) TextButton(onClick = model::clearSelection) { Text("Select none") }
        Spacer(Modifier.weight(1f))
        if (selected > 0) {
            AccentButton("Add to profile", Icons.Outlined.Add) { model.markSelected(VideoState.INCLUDED) }
            BarButton(Icons.Outlined.RemoveCircleOutline, "Exclude") { model.markSelected(VideoState.EXCLUDED) }
            BarButton(Icons.Outlined.Block, "Not for me") { model.markSelected(VideoState.NEGATIVE) }
            BarButton(Icons.AutoMirrored.Outlined.Undo, "Clear") { model.markSelected(null) }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun VideoRow(model: DesktopModel, row: LibraryRow, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Thumbnail(model, row.id, 112.dp, Modifier.padding(start = 4.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                row.title ?: "Untitled — fetch details to see it",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.title != null) FontWeight.Medium else FontWeight.Normal,
                color = if (row.title != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                row.channelName,
                formatDuration(row.durationSeconds),
                row.watchCount.takeIf { it > 1 }?.let { "watched $it×" },
                formatDate(row.lastSeen),
                "Short".takeIf { row.isShort },
                row.id.id.takeIf { row.title == null },
            )
            Text(
                details.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        StateTag(row.state)
        TextButton(onClick = onOpen) { Text("Open") }
    }
}
