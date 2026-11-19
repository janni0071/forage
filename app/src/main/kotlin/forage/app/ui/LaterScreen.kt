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
            else "${queue.size} videos" + (if (total > 0) " · ${formatTotal(total)}" else "") + (if (unknown > 0 && total > 0) " (+$unknown of unknown length)" else ""),
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
                    if (index == 0) UpNext(model, item) else QueueRow(model, item, index + 1)
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

/** A queued video on a phone: a small thumbnail, the title, and its three actions under the title. */
@Composable
private fun QueueRowCompact(model: AppModel, item: LaterItem) {
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
            Text(details(item), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundAction(Icons.Filled.PlayArrow, "Play", filled = true) { model.open(item.id) }
                RoundAction(Icons.Outlined.CheckCircle, "Mark as watched") { model.markWatched(item) }
                RoundAction(Icons.Outlined.Close, "Remove from the queue") { model.removeFromLater(item.id) }
            }
        }
    }
}

@Composable
private fun QueueRow(model: AppModel, item: LaterItem, position: Int) {
    if (LocalCompact.current) return QueueRowCompact(model, item)
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
        Text(
            "$position",
            style = MaterialTheme.typography.titleMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
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
            Text(details(item), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        RoundAction(Icons.Filled.PlayArrow, "Play", filled = true) { model.open(item.id) }
        Spacer(Modifier.width(8.dp))
        RoundAction(Icons.Outlined.CheckCircle, "Mark as watched: off the queue and never recommended again") { model.markWatched(item) }
        Spacer(Modifier.width(8.dp))
        RoundAction(Icons.Outlined.Close, "Remove from the queue") { model.removeFromLater(item.id) }
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

private fun details(item: LaterItem): String = listOfNotNull(
    item.channelName,
    formatAge(item.uploadedAt),
    "saved ${formatAge(item.savedAt)}",
).joinToString("  ·  ")

/** "3 h 12 min" for a whole queue. */
private fun formatTotal(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600 + 59) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}
