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
            // Room for the scrollbar in a window; a phone has none.
            val end = if (LocalCompact.current) 0.dp else 16.dp
            LazyColumn(state = listState, contentPadding = PaddingValues(end = end, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(queue, key = { _, it -> it.id.toString() }) { index, item ->
                    if (index == 0) UpNext(model, item) else QueueRow(model, item, index + 1, QueueActions.later(model, item))
                }
            }
            Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** One playlist, laid out like the queue: its videos in order, each to play, open or remove. */
@Composable
fun PlaylistScreen(model: AppModel) {
    val name = model.openPlaylistName ?: return
    val items = model.sortedPlaylist
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        val total = items.mapNotNull { it.durationSeconds }.sum()
        BackLink("Playlists", model::back)
        ScreenHeader(
            name,
            if (items.isEmpty()) "No videos yet" else "%,d videos".format(items.size) + (if (total > 0) " · ${formatTotal(total)}" else ""),
        ) {
            if (items.size > 1) {
                for (sort in LaterSort.entries) {
                    FilterChip(
                        selected = model.playlistSort == sort,
                        onClick = { model.playlistSort = sort },
                        label = { Text(if (LocalCompact.current) sort.shortLabel else sort.label, maxLines = 1) },
                        shape = RoundedCornerShape(10.dp),
                    )
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
        MissingDetails(model, items)
        if (items.isEmpty()) {
            Text(
                "Add videos with the Playlist button on their page, or select them in your likes or history.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return
        }
        Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, contentPadding = PaddingValues(end = scrollbarGap(), bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items, key = { _, it -> it.id.toString() }) { index, item ->
                    QueueRow(model, item, index + 1, QueueActions.playlist(model, item))
                }
            }
            Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** The first video, large: what to watch next. */
@Composable
private fun UpNext(model: AppModel, item: LaterItem) {
    val c = MaterialTheme.colorScheme
    if (LocalCompact.current) return UpNextCompact(model, item)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceContainerLow)
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
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                details(item),
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentButton("Play", Icons.Filled.PlayArrow) { model.open(item.id) }
                OutlinedButton(onClick = { model.markWatched(item) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Watched")
                }
                OutlinedButton(onClick = { model.removeFromLater(item.id) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Remove")
                }
            }
        }
    }
}

/** Up next on a phone: the thumbnail across the width, the title and the buttons below it. */
@Composable
private fun UpNextCompact(model: AppModel, item: LaterItem) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceContainerLow)
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
        Text(details(item), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        // One row of three equal buttons; Watched and Remove without icons, so the labels fit.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { model.open(item.id) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent.pastelPink, contentColor = Color(0xFF3E0423)),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(ControlHeight),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { model.markWatched(item) },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(ControlHeight),
            ) { Text("Watched", maxLines = 1) }
            OutlinedButton(
                onClick = { model.removeFromLater(item.id) },
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
) {
    companion object {
        fun later(model: AppModel, item: LaterItem) =
            QueueActions({ model.markWatched(item) }, null, { model.removeFromLater(item.id) }, "Remove from the queue", "saved")

        fun playlist(model: AppModel, item: LaterItem) =
            QueueActions(null, null, { model.removeFromPlaylist(listOf(item.id)) }, "Remove from the playlist", "added")

        fun liked(model: AppModel, item: LaterItem) = QueueActions(
            null, { model.openAddToPlaylist(listOf(item.id)) }, { model.mark(listOf(item.id), null) }, "Unlike", "liked",
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
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
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
                RoundAction(Icons.Outlined.Close, actions.removeHint, onClick = actions.remove)
            }
        }
    }
}

/** A video in a big row: thumbnail, title, a line about it, and its actions. [position] numbers it in a window. */
@Composable
internal fun QueueRow(model: AppModel, item: LaterItem, position: Int?, actions: QueueActions) {
    if (LocalCompact.current) return QueueRowCompact(model, item, actions)
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (hovered) c.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
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
        BigThumbnail(model, item, width = 230.dp, playIcon = 36.dp)
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
        RoundAction(Icons.Outlined.Close, actions.removeHint, onClick = actions.remove)
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
private fun RoundAction(icon: ImageVector, hint: String, filled: Boolean = false, onClick: () -> Unit) {
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
                .clip(CircleShape)
                .background(if (filled) Accent.pastelPink else c.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = hint, tint = if (filled) c.onPrimaryContainer else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
