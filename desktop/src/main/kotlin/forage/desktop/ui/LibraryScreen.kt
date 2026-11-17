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
import androidx.compose.foundation.layout.width
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
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceMenu(model)
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = { Text("Filter by title or channel") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in StateFilter.entries) {
                FilterChip(selected = model.stateFilter == f, onClick = { model.stateFilter = f }, label = { Text(f.label) })
            }
        }
        ActionBar(model, visible.size)
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                items(visible, key = { it.id.toString() }) { row ->
                    VideoRow(model, row, selected = row.id in model.selection, onToggle = { model.toggle(row.id) }, onOpen = { model.open(row.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
        OutlinedButton(onClick = { open = true }) { Text("${model.currentSource.label}  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in model.sources) {
                val label = if (s is LibrarySource.Playlist) "${s.label} (${s.size})" else s.label
                DropdownMenuItem(text = { Text(label) }, onClick = { model.selectSource(s); open = false })
            }
        }
    }
}

@Composable
private fun ActionBar(model: DesktopModel, visibleCount: Int) {
    val selected = model.selection.size
    val busy = model.job != null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (selected == 0) "$visibleCount videos" else "$selected selected",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(110.dp),
        )
        TextButton(onClick = model::selectVisible, enabled = visibleCount > 0) { Text("Select all shown") }
        TextButton(onClick = model::clearSelection, enabled = selected > 0) { Text("Select none") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { model.markSelected(VideoState.INCLUDED) }, enabled = selected > 0) { Text("Add to profile") }
        FilledTonalButton(onClick = { model.markSelected(VideoState.EXCLUDED) }, enabled = selected > 0) { Text("Exclude") }
        FilledTonalButton(onClick = { model.markSelected(VideoState.NEGATIVE) }, enabled = selected > 0) { Text("Not for me") }
        TextButton(onClick = { model.markSelected(null) }, enabled = selected > 0) { Text("Clear") }
        OutlinedButton(onClick = model::fetchDetails, enabled = !busy) {
            Text(if (selected > 0) "Fetch details" else "Fetch profile details")
        }
    }
}

@Composable
private fun VideoRow(model: DesktopModel, row: LibraryRow, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Thumbnail(model, row.id, 96.dp, Modifier.padding(start = 4.dp))
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
