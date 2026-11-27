@file:OptIn(ExperimentalFoundationApi::class)

package forage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.LibraryDialog
import forage.app.PlaylistSort
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.TextButton
import forage.app.LaterItem
import forage.app.LaterSort
import forage.app.Screen

@Composable
fun LaterScreen(model: AppModel) {
    val queue = model.sortedLater
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        val total = queue.mapNotNull { it.durationSeconds }.sum()
        val unknown = queue.count { it.durationSeconds == null }
        BackLink("Library", model::back)
        ScreenHeader(
            "Watch later",
            if (queue.isEmpty()) "Videos you save from Discover wait here"
            else "%,d videos".format(queue.size) + (if (total > 0) " · ${formatTotal(total)}" else "") + (if (unknown > 0 && total > 0) " (+%,d of unknown length)".format(unknown) else ""),
        ) {
            if (queue.isNotEmpty()) {
                for (sort in LaterSort.entries) {
                    FilterChip(
                        selected = model.laterSort == sort,
                        onClick = { model.laterSort = sort },
                        // Short labels on a phone, so the three fit on one line.
                        label = { Text(if (LocalCompact.current) sort.shortLabel else sort.label, maxLines = 1) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
        MissingDetails(model, queue)
        if (queue.isEmpty()) {
            EmptyQueue(model)
            return
        }
        Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            ScrollToTopOn(model.laterSort, listState)
            // Room for the scrollbar in a window; a phone has none.
            val end = if (LocalCompact.current) 0.dp else 16.dp
            LazyColumn(state = listState, contentPadding = PaddingValues(end = end, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(queue, key = { _, it -> it.id.toString() }) { index, item ->
                    if (index == 0) UpNext(model, item, QueueActions.later(model, item)) else QueueRow(model, item, index + 1, QueueActions.later(model, item))
                }
            }
            Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * One playlist, laid out like the queue: the first video large, the rest in rows to play, open or
 * remove. Reorder puts a handle on each video to drag it elsewhere; the order is the playlist's.
 */
@Composable
fun PlaylistScreen(model: AppModel) {
    val name = model.openPlaylistName ?: return
    val items = model.sortedPlaylist
    val reordering = model.reorderingPlaylist
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        val total = items.mapNotNull { it.durationSeconds }.sum()
        BackLink("Playlists", model::back)
        ScreenHeader(
            name,
            if (items.isEmpty()) "No videos yet" else "%,d videos".format(items.size) + (if (total > 0) " · ${formatTotal(total)}" else ""),
        ) {
            if (reordering) {
                AccentButton("Done", Icons.Filled.Check) { model.reorderingPlaylist = false }
            } else {
                if (items.size > 1) {
                    for (sort in PlaylistSort.entries) {
                        FilterChip(
                            selected = model.playlistSort == sort,
                            onClick = { model.playlistSort = sort },
                            label = { Text(if (LocalCompact.current) sort.shortLabel else sort.label, maxLines = 1) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                    TextButton(onClick = { model.playlistSort = PlaylistSort.CUSTOM; model.reorderingPlaylist = true }) {
                        Icon(Icons.Outlined.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reorder")
                    }
                }
                TextButton(onClick = { model.libraryDialog = LibraryDialog.RenamePlaylist(name) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rename")
                }
                TextButton(onClick = { model.libraryDialog = LibraryDialog.DeletePlaylist(name) }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
        if (!reordering) MissingDetails(model, items)
        if (items.isEmpty()) {
            Text(
                "Add videos with the Playlist button on their page, or from your likes and history.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return
        }
        Box(Modifier.weight(1f)) {
            if (reordering) {
                ReorderList(model, items)
            } else {
                val listState = rememberLazyListState()
                ScrollToTopOn(model.playlistSort, listState)
                LazyColumn(state = listState, contentPadding = PaddingValues(end = scrollbarGap(), bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(items, key = { _, it -> it.id.toString() }) { index, item ->
                        val actions = QueueActions.playlist(model, item)
                        if (index == 0) UpNext(model, item, actions) else QueueRow(model, item, index + 1, actions)
                    }
                }
                Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/**
 * The playlist with a handle on each video: drag one up or down and the others make room. The new
 * order is saved when it's let go. Near the top or bottom edge the list scrolls along.
 */
@Composable
private fun ReorderList(model: AppModel, items: List<LaterItem>) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    var order by remember(items) { mutableStateOf(items) }
    var dragged by remember { mutableStateOf<forage.core.PlatformId?>(null) }
    var offset by remember { mutableStateOf(0f) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column {
    Text(
        "Drag a video by its handle to move it.",
        style = MaterialTheme.typography.bodySmall,
        color = c.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(end = scrollbarGap(), bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(order, key = { _, it -> it.id.toString() }) { index, item ->
            val moving = item.id == dragged
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (moving) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (moving) offset else 0f
                        shadowElevation = if (moving) 12f else 0f
                        shape = RoundedCornerShape(14.dp)
                        clip = true
                    }
                    .background(if (moving) c.surfaceContainerHighest else c.surfaceContainerLow)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant, modifier = Modifier.width(if (compact) 28.dp else 36.dp))
                Thumbnail(model, item.id, if (compact) 104.dp else 160.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(item.title ?: "Untitled video", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.channelName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = { dragged = item.id; offset = 0f },
                                onDragEnd = {
                                    dragged = null
                                    offset = 0f
                                    model.reorderPlaylist(order.map { it.id })
                                },
                                onDragCancel = {
                                    dragged = null
                                    offset = 0f
                                    model.reorderPlaylist(order.map { it.id })
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val visible = state.layoutInfo.visibleItemsInfo
                                    val current = visible.firstOrNull { it.key == item.id.toString() } ?: return@detectDragGestures
                                    val middle = current.offset + offset + current.size / 2f
                                    // Past the middle of a neighbour, the two trade places.
                                    val target = visible.firstOrNull { it.key != current.key && middle >= it.offset && middle <= it.offset + it.size }
                                    if (target != null) {
                                        val from = order.indexOfFirst { it.id == item.id }
                                        val to = order.indexOfFirst { it.id.toString() == target.key }
                                        if (from >= 0 && to >= 0) {
                                            // A list keeps its first visible item in place, and would follow it
                                            // when it moves; hold the scroll where it is instead.
                                            val first = state.firstVisibleItemIndex
                                            val firstOffset = state.firstVisibleItemScrollOffset
                                            order = order.toMutableList().apply { add(to, removeAt(from)) }
                                            offset -= (target.offset - current.offset)
                                            if (from == first || to == first) scope.launch { state.scrollToItem(first, firstOffset) }
                                        }
                                    }
                                    // Near an edge, the list scrolls along.
                                    val edge = 96f
                                    val scrolled = when {
                                        middle > state.layoutInfo.viewportEndOffset - edge -> state.dispatchRawDelta(18f)
                                        middle < state.layoutInfo.viewportStartOffset + edge -> state.dispatchRawDelta(-18f)
                                        else -> 0f
                                    }
                                    offset += scrolled
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.DragHandle, contentDescription = "Drag to move", tint = c.onSurfaceVariant)
                }
            }
        }
    }
    }
}

/** The first video, large: what to watch next. */
@Composable
private fun UpNext(model: AppModel, item: LaterItem, actions: QueueActions) {
    val c = MaterialTheme.colorScheme
    if (LocalCompact.current) return UpNextCompact(model, item, actions)
    // Side by side only where the text keeps a good width beside the big thumbnail; stacked otherwise.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 900.dp) return@BoxWithConstraints UpNextCompact(model, item, actions)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceContainerLow)
            // Anywhere on the card opens the video; its buttons do their own thing.
            .clickable { model.showLater(item) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigThumbnail(model, item, width = 440.dp, playIcon = 56.dp)
        Column(Modifier.weight(1f).padding(start = 28.dp, end = 8.dp)) {
            Text("UP NEXT", style = MaterialTheme.typography.labelLarge, color = c.primary, fontWeight = FontWeight.Bold)
            Text(
                item.title ?: "Untitled video",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // The title opens the video's page, as everywhere.
                modifier = Modifier.padding(top = 6.dp).clickable { model.showLater(item) },
            )
            Text(
                details(item, actions),
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Buttons go beneath one another rather than breaking their labels.
            FlowRow(
                Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                AccentButton("Play", Icons.Filled.PlayArrow) { model.open(item.id) }
                actions.watched?.let { watched ->
                    OutlinedButton(onClick = watched, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Watched", maxLines = 1, softWrap = false)
                    }
                }
                OutlinedButton(onClick = actions.remove, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Remove", maxLines = 1, softWrap = false)
                }
            }
        }
    }
    }
}

/** Up next on a phone: the thumbnail across the width, the title and the buttons below it. */
@Composable
private fun UpNextCompact(model: AppModel, item: LaterItem, actions: QueueActions) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceContainerLow)
            .clickable { model.showLater(item) }
            .padding(12.dp),
    ) {
        BigThumbnail(model, item, width = null, playIcon = 52.dp)
        Text("UP NEXT", style = MaterialTheme.typography.labelLarge, color = c.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(
            item.title ?: "Untitled video",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(details(item, actions), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        // One row of equal buttons; Watched and Remove without icons, so the labels fit.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { model.open(item.id) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent.pastelPink, contentColor = Accent.onAccent),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(ControlHeight),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
            actions.watched?.let { watched ->
                OutlinedButton(
                    onClick = watched,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(ControlHeight),
                ) { Text("Watched", maxLines = 1) }
            }
            OutlinedButton(
                onClick = actions.remove,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(ControlHeight),
            ) { Text("Remove", maxLines = 1) }
        }
    }
}

/**
 * What a big row offers besides Play: Watched (the queue only), Add to playlist, and Remove from
 * wherever it is. [verb] says what the row's date is ("saved 3 days ago").
 */
internal class QueueActions(
    val watched: (() -> Unit)?,
    val playlist: (() -> Unit)?,
    val remove: () -> Unit,
    val removeHint: String,
    val verb: String,
    /** Something more to say about the video, e.g. how often it was watched. */
    val note: String? = null,
    /** The last button's icon: removing, unless it does something else. */
    val removeIcon: ImageVector = Icons.Outlined.Close,
    /** Shows the last button filled, for one that's on (saved, say). */
    val removeActive: Boolean = false,
) {
    companion object {
        fun later(model: AppModel, item: LaterItem) =
            QueueActions({ model.markWatched(item) }, null, { model.removeFromLater(item.id) }, "Remove from the queue", "saved")

        fun playlist(model: AppModel, item: LaterItem) =
            QueueActions(null, null, { model.removeFromPlaylist(listOf(item.id)) }, "Remove from the playlist", "added")

        fun liked(model: AppModel, item: LaterItem) = QueueActions(
            null, { model.openAddToPlaylist(listOf(item.id)) }, { model.mark(listOf(item.id), null) }, "Unlike", "liked",
        )

        /** An upload of someone followed: play it, keep it in a playlist, or save it for later. */
        fun upload(model: AppModel, item: LaterItem, saved: Boolean, onToggleLater: () -> Unit) = QueueActions(
            null, { model.openAddToPlaylist(listOf(item.id)) }, onToggleLater, if (saved) "Saved for later" else "Save for later", "",
            removeIcon = Icons.Outlined.WatchLater, removeActive = saved,
        )

        fun history(model: AppModel, item: LaterItem, watchCount: Int) = QueueActions(
            null, { model.openAddToPlaylist(listOf(item.id)) }, { model.forget(listOf(item.id)) }, "Remove from history",
            // "watched 4×, last 6 days ago" for a rewatched video.
            if (watchCount > 1) "watched $watchCount×, last" else "watched",
            note = "watched $watchCount×".takeIf { watchCount > 1 && item.savedAt == java.time.Instant.EPOCH },
        )
    }
}

/** A queued video on a phone: a small thumbnail, the title, and its actions under the title. */
@Composable
private fun QueueRowCompact(model: AppModel, item: LaterItem, actions: QueueActions) {
    val c = MaterialTheme.colorScheme
    // Anywhere on the row opens the video; its buttons do their own thing.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { model.showLater(item) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BigThumbnail(model, item, width = 136.dp, playIcon = 30.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                item.title ?: "Untitled video",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details(item, actions), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundAction(Icons.Filled.PlayArrow, "Play", filled = true) { model.open(item.id) }
                actions.watched?.let { RoundAction(Icons.Outlined.CheckCircle, "Mark as watched", onClick = it) }
                actions.playlist?.let { RoundAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "Add to a playlist", onClick = it) }
                RoundAction(actions.removeIcon, actions.removeHint, filled = actions.removeActive, onClick = actions.remove)
            }
        }
    }
}

/** A video in a big row: thumbnail, title, a line about it, and its actions. [position] numbers it in a window. */
@Composable
internal fun QueueRow(model: AppModel, item: LaterItem, position: Int?, actions: QueueActions) {
    if (LocalCompact.current) return QueueRowCompact(model, item, actions)
    // Too narrow for the thumbnail, the text and four buttons in a line: the phone's row instead.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 720.dp) return@BoxWithConstraints QueueRowCompact(model, item, actions)
    val thumbnail = (maxWidth * 0.26f).coerceIn(170.dp, 230.dp)
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (hovered) c.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
            .clickable { model.showLater(item) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            Text(
                "$position",
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
        }
        BigThumbnail(model, item, width = thumbnail, playIcon = 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Text(
                item.title ?: "Untitled video",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { model.showLater(item) },
            )
            Text(details(item, actions), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        RoundAction(Icons.Filled.PlayArrow, "Play", filled = true) { model.open(item.id) }
        actions.watched?.let {
            Spacer(Modifier.width(8.dp))
            RoundAction(Icons.Outlined.CheckCircle, "Mark as watched: off the queue and never recommended again", onClick = it)
        }
        actions.playlist?.let {
            Spacer(Modifier.width(8.dp))
            RoundAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "Add to a playlist", onClick = it)
        }
        Spacer(Modifier.width(8.dp))
        RoundAction(actions.removeIcon, actions.removeHint, filled = actions.removeActive, onClick = actions.remove)
    }
    }
}

/** A 16:9 thumbnail that plays on click, zooms a little under the mouse and shows the length. */
@Composable
private fun BigThumbnail(model: AppModel, item: LaterItem, width: Dp?, playIcon: Dp) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.05f else 1f)
    // On a phone, touching and holding the thumbnail plays its preview.
    var holding by remember { mutableStateOf(false) }
    Box(
        // A width, or (null) the whole width available.
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .tapOrHold(model.platform.isMobile, { model.showLater(item) }) { holding = it },
    ) {
        if (model.showThumbnails) ThumbnailImage(model, item.id, large = true, zoom = zoom, compact = width != null && width < 300.dp, preview = hovered || holding)
        if (hovered) {
            Box(
                Modifier.align(Alignment.Center).size(playIcon).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(playIcon * 0.6f))
            }
        }
        formatDuration(item.durationSeconds)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun RoundAction(icon: ImageVector, hint: String, filled: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Tooltip(
        tooltip = {
            Surface(color = c.inverseSurface, contentColor = c.inverseOnSurface, shape = RoundedCornerShape(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        },
        delayMillis = 300,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .then(if (filled) Modifier.accentGlow(CircleShape, 10.dp) else Modifier)
                .clip(CircleShape)
                .background(if (filled) Accent.pastelPink else c.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = hint, tint = if (filled) Accent.onAccent else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptyQueue(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.WatchLater, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text("Nothing saved yet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
        Text(
            "Save a recommendation with the bookmark on its card, and it waits here until you watch it.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        AccentButton("Go to Discover", Icons.Outlined.AutoAwesome) { model.go(Screen.DISCOVER) }
    }
}

private fun details(item: LaterItem, actions: QueueActions? = null): String = listOfNotNull(
    item.channelName,
    formatAge(item.uploadedAt),
    actions?.note,
    // Imports don't always say when a video was added.
    item.savedAt.takeIf { it != java.time.Instant.EPOCH }?.let { "${actions?.verb ?: "saved"} ${formatAge(it)}" },
).joinToString("  ·  ")

/** Fetches titles and lengths for imported videos that have none yet, a batch at a time. */
@Composable
private fun MissingDetails(model: AppModel, items: List<LaterItem>) {
    val missing = items.count { it.title == null }
    if (missing == 0) return
    val batch = minOf(missing, AppModel.FETCH_BATCH)
    // A line of its own under the header, which is full with the sort and the playlist's buttons.
    OutlinedButton(onClick = { model.fetchMissing(items) }, enabled = model.job == null, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp).height(ControlHeight)) {
        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (batch == missing) "Fetch details ($missing)" else "Fetch details (%,d of %,d)".format(batch, missing), maxLines = 1)
    }
}
