package forage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import forage.app.AppModel
import forage.app.sorts
import forage.app.LibraryDialog
import forage.app.LibraryView
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WatchLater
import forage.app.LaterItem
import forage.app.LibraryRow
import forage.app.LibrarySource
import forage.app.StateFilter
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog

/** The library tab: its overview, or the list, playlists, queue or topics it leads to. */
@Composable
fun LibraryScreen(model: AppModel, onImport: () -> Unit) {
    if (model.addDialogOpen) AddVideosDialog(model)
    when (model.libraryView) {
        LibraryView.HUB -> if (model.libraryEmpty) EmptyLibrary(model, onImport) else LibraryHub(model)
        LibraryView.LIST -> LibraryList(model)
        LibraryView.PLAYLISTS -> PlaylistsScreen(model)
        LibraryView.PLAYLIST -> PlaylistScreen(model)
        LibraryView.LATER -> LaterScreen(model)
        LibraryView.TASTE -> TopicsScreen(model)
    }
}

/** One of the library's lists: likes, history, a playlist, what was turned down, or everything. */
@Composable
private fun LibraryList(model: AppModel) {
    val visible = model.visibleRows
    val compact = LocalCompact.current
    // On a phone the header scrolls away with the videos, so the list gets the whole screen;
    // in a window it stays put above the list.
    val header: @Composable () -> Unit = { LibraryHeader(model, visible.size) }
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        if (!compact) header()
        Box(Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                Column {
                    if (compact) header()
                    Text(
                        emptyListText(model),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp).widthIn(max = 640.dp),
                    )
                }
            } else if (bigRows(model)) {
                // Liked and the history are for playing and keeping: big rows with those buttons.
                val listState = rememberLazyListState()
                ScrollToTopOn("${model.listSort}/${model.stateFilter}/${model.query}", listState)
                val history = model.currentSource == LibrarySource.History
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = scrollbarGap(), bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (compact) item { header() }
                    items(visible, key = { it.id.toString() }) { row ->
                        val item = LaterItem(
                            row.id, row.title, row.channelName, row.durationSeconds, null,
                            (if (history) row.lastSeen else row.decidedAt) ?: java.time.Instant.EPOCH,
                        )
                        QueueRow(model, item, null, if (history) QueueActions.history(model, item, row.watchCount) else QueueActions.liked(model, item))
                    }
                }
                Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            } else if (model.libraryGrid) {
                val gridState = rememberLazyGridState()
                ScrollToTopOn("${model.listSort}/${model.stateFilter}/${model.query}", gridState)
                LazyVerticalGrid(
                    // Two columns on a phone, as many as fit in a window.
                    columns = GridCells.Adaptive(minSize = if (compact) 150.dp else 210.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(end = scrollbarGap(), bottom = 96.dp),
                ) {
                    if (compact) item(span = { GridItemSpan(maxLineSpan) }) { header() }
                    items(visible.size, key = { visible[it].id.toString() }) { i ->
                        LibraryCard(model, visible[i], anySelected = model.selection.isNotEmpty())
                    }
                }
                Scrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            } else {
                val listState = rememberLazyListState()
                ScrollToTopOn("${model.listSort}/${model.stateFilter}/${model.query}", listState)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = scrollbarGap()), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (compact) item { header() }
                    items(visible, key = { it.id.toString() }) { row -> VideoRow(model, row) }
                }
                Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** Liked and the history show big rows to play from; the other lists stay lists to select from. */
private fun bigRows(model: AppModel) = model.currentSource == LibrarySource.Liked || model.currentSource == LibrarySource.History

/** What an empty list says, depending on why it's empty. */
private fun emptyListText(model: AppModel): String = when {
    model.rows.isNotEmpty() -> "Nothing here matches. Try another filter or search."
    model.currentSource == LibrarySource.Liked ->
        "Nothing liked yet. Like videos from your history, a playlist or a search: recommendations come from what you like."
    model.currentSource == LibrarySource.History ->
        if (model.recordHistory) "Your history is empty. Videos you open from Forage show up here."
        else "Your history is empty, and paused: videos you open aren't recorded."
    model.currentSource == LibrarySource.NotInterested -> "Nothing turned down. Videos you mark \"Not interested\" show up here."
    model.currentSource == LibrarySource.All -> "The library is empty."
    else -> "This playlist is empty. Select videos in your history or likes and choose \"Add to playlist\"."
}

/** Title, search, buttons, sources, filters and the selection bar. */
@Composable
private fun LibraryHeader(model: AppModel, visibleCount: Int) {
    val compact = LocalCompact.current
    val source = model.currentSource
    val missing = model.rows.count { !it.hasMetadata }
    Column {
        BackLink("Library", model::back)
        ScreenHeader(source.label, listSubtitle(model)) {
            // A phone searches with the bar at the bottom.
            if (!compact) SearchField(model.query, { model.query = it }, "Search titles and channels", Modifier.width(280.dp))
            if (!bigRows(model)) ViewToggle(model)
            // A phone has no room for them beside the sort: the history's switches come up here.
            if (compact && source == LibrarySource.History) {
                OutlinedIconButton(
                    onClick = { model.changeRecordHistory(!model.recordHistory) },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.size(ControlHeight),
                ) {
                    Icon(
                        if (model.recordHistory) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (model.recordHistory) "Pause history" else "Resume history",
                    )
                }
                OutlinedIconButton(
                    onClick = { model.libraryDialog = LibraryDialog.ClearHistory },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.size(ControlHeight),
                ) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Clear history") }
            }
            if (source == LibrarySource.Liked) {
                if (compact) {
                    OutlinedIconButton(onClick = { model.addDialogOpen = true }, enabled = model.job == null, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(ControlHeight)) {
                        Icon(Icons.Outlined.Link, contentDescription = "Add by link")
                    }
                } else {
                    OutlinedButton(onClick = { model.addDialogOpen = true }, enabled = model.job == null, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                        Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add by link")
                    }
                }
            }
            // Only where something lacks details: a title, a length, and what recommendations read.
            if (missing > 0 || model.selection.isNotEmpty()) {
                OutlinedButton(onClick = model::fetchDetails, enabled = model.job == null, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            model.selection.isNotEmpty() -> if (compact) "Fetch details" else "Fetch details of selection"
                            else -> "Fetch details (%,d)".format(minOf(missing, forage.app.AppModel.FETCH_BATCH))
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        // The order, and the history's own switches, on one line that swipes on a phone.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (sort in source.sorts()) {
                FilterChip(
                    selected = model.listSort == sort,
                    onClick = { model.sortList(sort) },
                    label = { Text(sort.label, maxLines = 1) },
                    shape = RoundedCornerShape(10.dp),
                )
            }
            if (source == LibrarySource.History && !compact) {
                Spacer(Modifier.width(8.dp))
                if (model.recordHistory) {
                    BarButton(Icons.Outlined.Pause, "Pause history") { model.changeRecordHistory(false) }
                } else {
                    BarButton(Icons.Filled.PlayArrow, "Resume history") { model.changeRecordHistory(true) }
                }
                BarButton(Icons.Outlined.DeleteOutline, "Clear history") { model.libraryDialog = LibraryDialog.ClearHistory }
            }
        }
        // Mixed lists can be narrowed to what you liked or didn't.
        if (source == LibrarySource.History || source == LibrarySource.All || source is LibrarySource.Playlist) Filters(model)
        if (bigRows(model)) Spacer(Modifier.height(8.dp)) else ActionBar(model, visibleCount)
    }
}

/** A line about the list on screen. */
private fun listSubtitle(model: AppModel): String {
    val n = "%,d videos".format(model.rows.size)
    return when (model.currentSource) {
        LibrarySource.Liked -> "$n · recommendations come from these"
        LibrarySource.History -> if (model.recordHistory) "$n you opened or imported" else "$n · paused: what you open isn't recorded"
        LibrarySource.NotInterested -> "$n · recommendations steer away from these"
        LibrarySource.All -> "$n the library knows"
        is LibrarySource.Playlist -> n
    }
}

@Composable
private fun EmptyLibrary(model: AppModel, onImport: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    Column(
        Modifier.fillMaxSize().padding(if (compact) 24.dp else 48.dp),
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
                "Search or add videos by their link and like them. They stay here, encrypted, " +
                    "apart from everything else in Forage."
            } else {
                "Import a Google Takeout export of your YouTube data, or search for videos and like them. " +
                    "Imported videos land in your history and playlists; recommendations come only from what you like."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).widthIn(max = 640.dp),
        )
        // Side by side in a window; stacked on a phone, where they don't fit next to each other.
        val importLabel = if (model.platform.isMobile) "Import Takeout zip" else "Import Takeout"
        val buttons: @Composable () -> Unit = {
            if (!model.isPrivate) AccentButton(importLabel, Icons.Outlined.CloudDownload, onClick = onImport)
            if (model.isPrivate) AccentButton("Search", Icons.Outlined.Search, onClick = model::askForSearch)
            else OutlinedButton(onClick = model::askForSearch, shape = RoundedCornerShape(12.dp)) { Text("Search for videos", maxLines = 1) }
            TextButton(onClick = { model.addDialogOpen = true }) { Text("Add by link", maxLines = 1) }
        }
        if (compact) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        }
    }
}

/** List or grid, as a two-part switch. */
@Composable
private fun ViewToggle(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))) {
        for ((grid, icon) in listOf(false to Icons.AutoMirrored.Outlined.ViewList, true to Icons.Outlined.GridView)) {
            val on = model.libraryGrid == grid
            Box(
                Modifier
                    .size(44.dp, ControlHeight)
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

/** The library's dialogs: adding to a playlist, making, renaming and deleting one, and clearing the history. */
@Composable
internal fun LibraryDialogs(model: AppModel) {
    when (val dialog = model.libraryDialog) {
        null -> {}
        is LibraryDialog.AddToPlaylist -> AddToPlaylistDialog(model, dialog.videos)
        LibraryDialog.NewPlaylist -> NameDialog(
            title = "New playlist", initial = "", confirm = "Make playlist",
            onDismiss = { model.libraryDialog = null }, onConfirm = model::createPlaylist,
        )
        is LibraryDialog.RenamePlaylist -> NameDialog(
            title = "Rename \"${dialog.name}\"", initial = dialog.name, confirm = "Rename",
            onDismiss = { model.libraryDialog = null }, onConfirm = { model.renamePlaylist(dialog.name, it) },
        )
        is LibraryDialog.DeletePlaylist -> AlertDialog(
            onDismissRequest = { model.libraryDialog = null },
            title = { Text("Delete \"${dialog.name}\"?") },
            text = { Text("The playlist goes; its videos stay in your library, with their likes.") },
            confirmButton = { TextButton(onClick = { model.deletePlaylist(dialog.name) }) { Text("Delete playlist", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { model.libraryDialog = null }) { Text("Keep it") } },
        )
        LibraryDialog.ClearHistory -> AlertDialog(
            onDismissRequest = { model.libraryDialog = null },
            title = { Text("Clear the whole history?") },
            text = {
                Text(
                    "Every video in it goes, imported ones included, and they may be recommended again. " +
                        "Your likes, playlists and watch later stay as they are."
                )
            },
            confirmButton = { TextButton(onClick = model::clearHistory) { Text("Clear history", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { model.libraryDialog = null }) { Text("Cancel") } },
        )
    }
}

/** Asks for a name; [onConfirm] returns why it isn't accepted, or null. */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var name by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it; problem = null },
                singleLine = true,
                label = { Text("Name") },
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = { TextButton(onClick = { problem = onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a playlist for [videos], or names a new one. */
@Composable
private fun AddToPlaylistDialog(model: AppModel, videos: List<forage.core.PlatformId>) {
    val c = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val playlists = model.sources.filterIsInstance<LibrarySource.Playlist>()
    AlertDialog(
        onDismissRequest = { model.libraryDialog = null },
        title = { Text(if (videos.size == 1) "Add to a playlist" else "Add ${videos.size} videos to a playlist") },
        text = {
            Column(Modifier.widthIn(min = 280.dp)) {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(playlists, key = { it.name }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { model.addToPlaylist(p.name, videos) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("%,d".format(p.size), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name, { name = it; problem = null },
                        singleLine = true,
                        placeholder = { Text("New playlist") },
                        isError = problem != null,
                        supportingText = problem?.let { { Text(it) } },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { problem = model.addToPlaylist(name, videos) }, enabled = name.isNotBlank()) { Text("Make and add") }
        },
        dismissButton = { TextButton(onClick = { model.libraryDialog = null }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(model: AppModel) {
    val counts = remember(model.rows) {
        val byState = model.rows.groupingBy { it.state }.eachCount()
        StateFilter.entries.associateWith { f -> if (f == StateFilter.ALL) model.rows.size else byState.filterKeys(f::matches).values.sum() }
    }
    // One line on a phone, swiped sideways; wrapped in a window.
    val compact = LocalCompact.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxLines = if (compact) 1 else Int.MAX_VALUE,
        modifier = if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth(),
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
private fun ActionBar(model: AppModel, visibleCount: Int) {
    val selected = model.selection.size
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val actions: @Composable () -> Unit = {
        val ids = model.selection.toList()
        if (model.currentSource != LibrarySource.Liked) AccentButton("Like", Icons.Outlined.ThumbUp) { model.markSelected(VideoState.LIKED) }
        if (model.currentSource != LibrarySource.NotInterested) BarButton(Icons.Outlined.Block, "Not interested") { model.markSelected(VideoState.NOT_INTERESTED) }
        BarButton(Icons.AutoMirrored.Outlined.PlaylistAdd, "Add to playlist") { model.openAddToPlaylist(ids) }
        when (model.currentSource) {
            LibrarySource.Liked -> BarButton(Icons.AutoMirrored.Outlined.Undo, "Unlike") { model.markSelected(null) }
            LibrarySource.NotInterested -> BarButton(Icons.AutoMirrored.Outlined.Undo, "Undo") { model.markSelected(null) }
            LibrarySource.History -> {
                BarButton(Icons.AutoMirrored.Outlined.Undo, "Clear") { model.markSelected(null) }
                BarButton(Icons.Outlined.DeleteOutline, if (compact) "Remove from history" else "Remove") { model.forget(ids) }
            }
            is LibrarySource.Playlist -> {
                BarButton(Icons.AutoMirrored.Outlined.Undo, "Clear") { model.markSelected(null) }
                BarButton(Icons.Outlined.RemoveCircleOutline, if (compact) "Remove from playlist" else "Remove") { model.removeFromPlaylist(ids) }
            }
            LibrarySource.All -> BarButton(Icons.AutoMirrored.Outlined.Undo, "Clear") { model.markSelected(null) }
        }
    }
    // On a phone the line appears once something is selected (by its circle or a long press).
    if (compact && selected == 0) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 8.dp else 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected > 0) c.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = if (selected > 0) 12.dp else 0.dp, vertical = 4.dp),
    ) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (selected == 0) "%,d shown".format(visibleCount) else "$selected selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 6.dp),
            )
            TextButton(onClick = model::selectVisible, enabled = visibleCount > 0) { Text(if (compact) "Select all" else "Select all shown") }
            if (selected > 0) TextButton(onClick = model::clearSelection) { Text(if (compact) "None" else "Select none") }
            if (compact || selected == 0) Spacer(Modifier.weight(1f))
            when {
                // At the right, scrolling sideways when a narrow window has no room for all of them.
                selected > 0 && !compact -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
                selected == 0 && !compact -> Text(
"Click a video to open it. Select with the circles to like several at once or add them to a playlist",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
        }
        // A phone has no room beside the count: the actions get a row of their own.
        if (selected > 0 && compact) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
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
private fun VideoRow(model: AppModel, row: LibraryRow) {
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
            .openOrSelect(model, row)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val compact = LocalCompact.current
        SelectDot(selected, visible = true, onImage = false) { model.toggle(row.id) }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        LibraryThumbnail(model, row, Modifier.width(if (compact) 120.dp else 168.dp), compact = true)
        Column(Modifier.weight(1f).padding(horizontal = if (compact) 12.dp else 16.dp)) {
            Title(row, maxLines = if (compact) 2 else 1)
            Text(details(row), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            if (showsStates(model)) StatePill(row.state, Modifier.padding(top = 8.dp))
        }
        if (hovered) QuickActions(model, row) else Spacer(Modifier.width(1.dp))
    }
}

/** The library as a grid: thumbnails first, which helps with the many videos that have no title yet. */
@Composable
private fun LibraryCard(model: AppModel, row: LibraryRow, anySelected: Boolean) {
    val c = MaterialTheme.colorScheme
    val selected = row.id in model.selection
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.primaryContainer.copy(alpha = 0.55f) else if (hovered) c.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
            .openOrSelect(model, row)
            .padding(6.dp),
    ) {
        Box {
            LibraryThumbnail(model, row, Modifier.fillMaxWidth(), playOnHover = hovered)
            SelectDot(selected, visible = hovered || anySelected, Modifier.align(Alignment.TopStart).padding(8.dp)) { model.toggle(row.id) }
            if (showsStates(model)) StatePill(row.state, Modifier.align(Alignment.TopEnd).padding(8.dp), solid = true)
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

/**
 * A click opens the video's page; while videos are selected it adds or removes this one instead.
 * A long press (or right-click hold) starts selecting, as in a phone's gallery.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.openOrSelect(model: AppModel, row: LibraryRow): Modifier = combinedClickable(
    onClick = {
        if (model.selection.isEmpty()) model.showVideo(row.id, row.title, null, row.channelName, row.durationSeconds)
        else model.toggle(row.id)
    },
    onLongClick = { model.toggle(row.id) },
)

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
    row.durationSeconds?.takeIf { row.channelName == null }?.let(::formatDuration),
    row.watchCount.takeIf { it > 1 }?.let { "watched $it×" },
    formatDate(row.lastSeen),
    "Short".takeIf { row.isShort },
).joinToString("  ·  ").ifEmpty { if (row.hasMetadata) "" else "No details yet" }

@Composable
private fun LibraryThumbnail(model: AppModel, row: LibraryRow, modifier: Modifier, playOnHover: Boolean = false, compact: Boolean = false) {
    val zoom by animateFloatAsState(if (playOnHover) 1.04f else 1f)
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (model.showThumbnails) ThumbnailImage(model, row.id, zoom = zoom, compact = compact, preview = playOnHover)
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

/** In the liked and not-interested lists every video has the same state, so it isn't labelled. */
private fun showsStates(model: AppModel) =
    model.currentSource is LibrarySource.History || model.currentSource is LibrarySource.Playlist || model.currentSource == LibrarySource.All

@Composable
private fun StatePill(state: VideoState?, modifier: Modifier = Modifier, solid: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val (label, container, content) = when (state) {
        VideoState.LIKED -> Triple("Liked", if (solid) Accent.pastelPink else c.primaryContainer, c.onPrimaryContainer)
        VideoState.NOT_INTERESTED -> Triple("Not interested", c.errorContainer, c.onErrorContainer)
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
private fun QuickActions(model: AppModel, row: LibraryRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val liked = row.state == VideoState.LIKED
        QuickButton(Icons.Filled.PlayArrow, "Play") { model.open(row.id) }
        QuickButton(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (liked) "Liked" else "Like", accent = !liked) {
            model.mark(listOf(row.id), if (liked) null else VideoState.LIKED)
        }
        if (row.state != VideoState.NOT_INTERESTED) QuickButton(Icons.Outlined.Block, "Not interested") { model.mark(listOf(row.id), VideoState.NOT_INTERESTED) }
        QuickButton(Icons.AutoMirrored.Outlined.PlaylistAdd, "Playlist") { model.openAddToPlaylist(listOf(row.id)) }
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

/** The library's overview (SPEC, Direction: Screens): the lists it holds, your taste and recent likes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHub(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = scrollbarGap()),
            state = listState,
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 12.dp else 24.dp, bottom = 120.dp,
            ),
        ) {
            item {
                ScreenHeader("Library", "Everything you keep") {
                    // A phone searches with the bar at the bottom.
                    if (!compact) SearchField(model.query, { model.query = it }, "Search your library", Modifier.width(300.dp), onSearch = model::submitSearch)
                    OutlinedButton(onClick = { model.addDialogOpen = true }, enabled = model.job == null, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                        Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add by link")
                    }
                }
            }
            item {
                val total = model.later.mapNotNull { it.durationSeconds }.sum()
                val playlists = model.sources.filterIsInstance<LibrarySource.Playlist>()
                val cards = listOf<Triple<ImageVector, String, Pair<String, () -> Unit>>>(
                    Triple(Icons.Outlined.ThumbUp, "Liked", "%,d videos · your taste".format(model.profileSize) to { model.openLibrary(LibraryView.LIST, LibrarySource.Liked) }),
                    Triple(Icons.Outlined.WatchLater, "Watch later", ("%,d videos".format(model.later.size) + (if (total > 0) " · ${formatTotal(total)}" else "")) to { model.openLibrary(LibraryView.LATER) }),
                    Triple(Icons.AutoMirrored.Outlined.PlaylistPlay, "Playlists", (if (playlists.isEmpty()) "None yet" else if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists") to { model.openLibrary(LibraryView.PLAYLISTS) }),
                    Triple(Icons.Outlined.History, "History", ("%,d videos".format(model.historySize) + (if (!model.recordHistory) " · paused" else "")) to { model.openLibrary(LibraryView.LIST, LibrarySource.History) }),
                )
                val perRow = if (compact) 2 else 4
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.chunked(perRow).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (icon, title, rest) -> HubCard(icon, title, rest.first, Modifier.weight(1f), rest.second) }
                        }
                    }
                }
            }
            item { TasteCard(model) }
            item {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { model.openLibrary(LibraryView.LIST, LibrarySource.NotInterested) }) { Text("Not interested · ${model.notInterestedSize}") }
                    TextButton(onClick = { model.openLibrary(LibraryView.LIST, LibrarySource.All) }) { Text("All videos") }
                }
            }
            if (model.recentlyLiked.isNotEmpty()) {
                item { SectionTitle("Recently liked", "All ${model.profileSize}") { model.openLibrary(LibraryView.LIST, LibrarySource.Liked) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.recentlyLiked, key = { it.id.toString() }) { row ->
                            VideoTile(model, row.id, row.title, row.channelName, row.durationSeconds, if (compact) 200.dp else 240.dp) { model.showRow(row) }
                        }
                    }
                }
            }
        }
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun HubCard(icon: ImageVector, title: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // All the same height, whatever they say.
    Column(
        modifier
            .height(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceContainer)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Your likes as topics (what used to be the Topics tab), with the pinned ones first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TasteCard(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceContainer)
            .clickable { model.openLibrary(LibraryView.TASTE) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Your taste", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val named = model.topics.count { !it.isVaried }
            Text(
                if (model.topics.isEmpty()) "What your likes are about" else "$named topics",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }
        val shown = model.topics.filter { !it.isVaried }
            .sortedBy { if (it.preference == forage.core.recommend.TopicPreference.PINNED) 0 else 1 }.take(8)
        if (shown.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { t ->
                    val pinned = t.preference == forage.core.recommend.TopicPreference.PINNED
                    val muted = t.preference == forage.core.recommend.TopicPreference.MUTED
                    Tag(
                        (if (pinned) "Pinned: " else "") + t.label,
                        if (pinned) c.primaryContainer else c.surfaceContainerHigh,
                        if (muted) c.onSurfaceVariant else c.onSurface,
                    )
                }
            }
        } else {
            Text(
                "Open it to group your likes into topics, then rename, pin or mute them.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Every playlist as a card with its first video's picture, and making a new one. */
@Composable
private fun PlaylistsScreen(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val playlists = model.sources.filterIsInstance<LibrarySource.Playlist>()
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Two columns on a phone, as many as fit in a window.
            columns = GridCells.Adaptive(minSize = if (compact) 150.dp else 240.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(end = scrollbarGap()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 20.dp),
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 4.dp else 16.dp, bottom = 120.dp,
            ),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    BackLink("Library", model::back)
                    ScreenHeader("Playlists", if (playlists.isEmpty()) null else if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists") {
                        OutlinedButton(onClick = { model.libraryDialog = LibraryDialog.NewPlaylist }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New playlist")
                        }
                    }
                    if (playlists.isEmpty()) {
                        Text("No playlists yet. Make one here, or add a video to a new one from its page.", color = c.onSurfaceVariant)
                    }
                }
            }
            gridItems(playlists, key = { it.name }) { p -> PlaylistCard(model, p) }
        }
        Scrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun PlaylistCard(model: AppModel, playlist: LibrarySource.Playlist) {
    val c = MaterialTheme.colorScheme
    val cover = model.playlistCovers[playlist.name]
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.04f else 1f)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .hoverable(hover)
            .clickable { model.openPlaylist(playlist.name) }
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh)) {
            if (cover != null && model.showThumbnails) {
                ThumbnailImage(model, cover, large = true, zoom = zoom)
            } else {
                Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(40.dp))
            }
            // How many videos, on the picture, as playlists show it on the sites.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("%,d".format(playlist.size), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            if (playlist.size == 1L) "1 video" else "%,d videos".format(playlist.size),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
    }
}
