package forage.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
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
    if (model.addDialogOpen) AddLinkDialog(model)
    if (model.libraryEmpty) {
        EmptyLibrary(model, onImport)
        return
    }
    val visible = model.visibleRows
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 16.dp, top = 24.dp)) {
        ScreenHeader("Library", "Choose what shapes your profile") {
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = { Text("Search titles and channels") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.width(300.dp),
            )
            ViewToggle(model)
            OutlinedButton(onClick = { model.addDialogOpen = true }, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add by link")
            }
            OutlinedButton(onClick = model::fetchDetails, enabled = model.job == null, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (model.selection.isEmpty()) "Fetch profile details" else "Fetch details of selection")
            }
        }
        SourceTabs(model)
        Filters(model)
        ActionBar(model, visible.size)
        Box(Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                Text(
                    "Nothing here matches. Try another filter or source.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else if (model.libraryGrid) {
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 210.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(end = 16.dp, bottom = 96.dp),
                ) {
                    items(visible.size, key = { visible[it].id.toString() }) { i ->
                        LibraryCard(model, visible[i], anySelected = model.selection.isNotEmpty())
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            } else {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 16.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(visible, key = { it.id.toString() }) { row -> VideoRow(model, row) }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun EmptyLibrary(model: DesktopModel, onImport: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(
                if (model.isPrivate) Icons.Outlined.Lock else Icons.Outlined.CloudDownload,
                contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(44.dp),
            )
        }
        Text(
            if (model.isPrivate) "Your private vault is empty" else "Your library is empty",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            if (model.isPrivate) {
                "Add videos by their link. They go into this vault's profile and stay here, encrypted,\n" +
                    "apart from everything else in Forage."
            } else {
                "Import a Google Takeout export of your YouTube data, or add videos by their link.\n" +
                    "Imported videos land in the library as watched; nothing enters your profile until you choose it."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!model.isPrivate) AccentButton("Import Takeout folder", Icons.Outlined.CloudDownload, onClick = onImport)
            if (model.isPrivate) AccentButton("Add a video by link", Icons.Outlined.Link) { model.addDialogOpen = true }
            else OutlinedButton(onClick = { model.addDialogOpen = true }, shape = RoundedCornerShape(12.dp)) { Text("Add a video by link") }
        }
    }
}

@Composable
private fun AddLinkDialog(model: DesktopModel) {
    var link by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { model.addDialogOpen = false },
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text("Add a video") },
        text = {
            Column {
                Text(
                    "Paste a link from ${model.linkPlatforms}, or several, one per line. The videos go straight into this profile.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; problem = null },
                    label = { Text("Links") },
                    placeholder = { Text("https://www.youtube.com/watch?v=…") },
                    minLines = 1,
                    maxLines = 6,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(440.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { problem = model.addByLink(link) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { model.addDialogOpen = false }) { Text("Cancel") } },
    )
}

/** List or grid, as a two-part switch. */
@Composable
private fun ViewToggle(model: DesktopModel) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))) {
        for ((grid, icon) in listOf(false to Icons.AutoMirrored.Outlined.ViewList, true to Icons.Outlined.GridView)) {
            val on = model.libraryGrid == grid
            Box(
                Modifier
                    .size(44.dp, 40.dp)
                    .background(if (on) c.primaryContainer else Color.Transparent)
                    .clickable { model.showLibraryAsGrid(grid) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = if (grid) "Grid" else "List", tint = if (on) c.onPrimaryContainer else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Where the videos come from: the watch history, each imported playlist, or this profile's decisions. */
@Composable
private fun SourceTabs(model: DesktopModel) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (source in model.sources) {
            val selected = source == model.currentSource
            val (name, count) = when (source) {
                LibrarySource.History -> "Watch history" to null
                LibrarySource.InProfile -> "Decided in this profile" to null
                is LibrarySource.Playlist -> source.name to source.size
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) c.primaryContainer else c.surfaceContainerLow)
                    .clickable { model.selectSource(source) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) c.onPrimaryContainer else c.onSurface,
                )
                (count ?: if (selected) model.rows.size.toLong() else null)?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("%,d".format(it), style = MaterialTheme.typography.labelMedium, color = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(model: DesktopModel) {
    val counts = remember(model.rows) {
        val byState = model.rows.groupingBy { it.state }.eachCount()
        StateFilter.entries.associateWith { f -> if (f == StateFilter.ALL) model.rows.size else byState.filterKeys(f::matches).values.sum() }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (f in StateFilter.entries) {
            FilterChip(
                selected = model.stateFilter == f,
                onClick = { model.stateFilter = f },
                label = { Text("${f.label}  ${"%,d".format(counts[f] ?: 0)}", maxLines = 1) },
                leadingIcon = if (model.stateFilter == f) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                shape = RoundedCornerShape(10.dp),
            )
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
            .padding(vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected > 0) c.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = if (selected > 0) 12.dp else 0.dp, vertical = 4.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (selected == 0) "%,d shown".format(visibleCount) else "$selected selected",
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
        } else {
            Text(
                "Select videos to add them to your profile, or use the buttons that appear on hover",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
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
private fun VideoRow(model: DesktopModel, row: LibraryRow) {
    val c = MaterialTheme.colorScheme
    val selected = row.id in model.selection
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> c.primaryContainer.copy(alpha = 0.55f)
                    hovered -> c.surfaceContainerLow
                    else -> Color.Transparent
                }
            )
            .hoverable(hover)
            .clickable { model.toggle(row.id) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected, visible = true, onImage = false) { model.toggle(row.id) }
        Spacer(Modifier.width(12.dp))
        LibraryThumbnail(model, row, Modifier.width(168.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Title(row, maxLines = 1)
            Text(details(row), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            StatePill(row.state, Modifier.padding(top = 8.dp))
        }
        if (hovered) QuickActions(model, row) else Spacer(Modifier.width(1.dp))
    }
}

/** The library as a grid: thumbnails first, which helps with the many videos that have no title yet. */
@Composable
private fun LibraryCard(model: DesktopModel, row: LibraryRow, anySelected: Boolean) {
    val c = MaterialTheme.colorScheme
    val selected = row.id in model.selection
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.primaryContainer.copy(alpha = 0.55f) else if (hovered) c.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
            .clickable { model.toggle(row.id) }
            .padding(6.dp),
    ) {
        Box {
            LibraryThumbnail(model, row, Modifier.fillMaxWidth(), playOnHover = hovered)
            SelectDot(selected, visible = hovered || anySelected, Modifier.align(Alignment.TopStart).padding(8.dp)) { model.toggle(row.id) }
            StatePill(row.state, Modifier.align(Alignment.TopEnd).padding(8.dp), solid = true)
        }
        Title(row, maxLines = 2, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        Text(
            details(row),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Title(row: LibraryRow, maxLines: Int, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Text(
        row.title ?: "Untitled · fetch details to see it",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (row.title != null) FontWeight.SemiBold else FontWeight.Normal,
        color = if (row.title != null) c.onSurface else c.onSurfaceVariant,
        minLines = if (maxLines > 1) maxLines else 1,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun details(row: LibraryRow): String = listOfNotNull(
    row.channelName,
    row.watchCount.takeIf { it > 1 }?.let { "watched $it×" },
    formatDate(row.lastSeen),
    "Short".takeIf { row.isShort },
).joinToString("  ·  ").ifEmpty { row.id.id }

@Composable
private fun LibraryThumbnail(model: DesktopModel, row: LibraryRow, modifier: Modifier, playOnHover: Boolean = false) {
    val zoom by animateFloatAsState(if (playOnHover) 1.04f else 1f)
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (model.showThumbnails) {
            val image by produceState(model.thumbnails.cached(row.id), row.id) { if (value == null) value = model.thumbnails.load(row.id) }
            image?.let {
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
            }
        }
        if (playOnHover) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { model.open(row.id) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        formatDuration(row.durationSeconds)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** A round check that marks a video as selected. */
@Composable
private fun SelectDot(selected: Boolean, visible: Boolean, modifier: Modifier = Modifier, onImage: Boolean = true, onClick: () -> Unit) {
    if (!visible && !selected) return
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            // Over a thumbnail the circle needs a dark backing to show; on the page an outline is enough.
            .background(if (selected) c.primary else if (onImage) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
            .border(2.dp, if (selected) c.primary else if (onImage) Color.White else c.outline, CircleShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.onPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun StatePill(state: VideoState?, modifier: Modifier = Modifier, solid: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val (label, container, content) = when (state) {
        VideoState.INCLUDED -> Triple("In profile", if (solid) Accent.pastelPink else c.primaryContainer, c.onPrimaryContainer)
        VideoState.EXCLUDED -> Triple("Excluded", c.surfaceContainerHighest, c.onSurfaceVariant)
        VideoState.NEGATIVE -> Triple("Not for me", c.errorContainer, c.onErrorContainer)
        null -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = content,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(container).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** One-click actions for a single video, shown while the mouse is over its row. */
@Composable
private fun QuickActions(model: DesktopModel, row: LibraryRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val inProfile = row.state == VideoState.INCLUDED
        QuickButton(Icons.Filled.PlayArrow, "Play") { model.open(row.id) }
        QuickButton(if (inProfile) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.Add, if (inProfile) "Take out of profile" else "Add to profile", accent = !inProfile) {
            model.mark(listOf(row.id), if (inProfile) null else VideoState.INCLUDED)
        }
        if (row.state != VideoState.NEGATIVE) QuickButton(Icons.Outlined.Block, "Not for me") { model.mark(listOf(row.id), VideoState.NEGATIVE) }
    }
}

@Composable
private fun QuickButton(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (accent) Accent.pastelPink else c.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (accent) c.onPrimaryContainer else c.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (accent) c.onPrimaryContainer else c.onSurfaceVariant)
    }
}
