package forage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.core.source.VideoStub

/**
 * The results of a search from the search bar, on a page of their own: big cards, to watch as much
 * as to keep. Back returns to the tab.
 */
@Composable
fun SearchScreen(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val state = rememberLazyGridState()
    val saved = model.later.mapTo(HashSet()) { it.id }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // One card across a phone, as many as fit in a window.
            columns = GridCells.Adaptive(minSize = if (compact) 320.dp else 300.dp),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = scrollbarGap()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 22.dp else 28.dp),
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 4.dp else 16.dp, bottom = 120.dp,
            ),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    BackLink("Back", model::back)
                    Text(
                        "Results for \"${model.searchText.trim()}\"",
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    )
                    if (model.searchPlatforms.size > 1) {
                        val current = model.searchPlatform ?: model.searchPlatforms.first().first
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("On", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                            for ((id, name) in model.searchPlatforms) {
                                ToggleChip(name, id == current) {
                                    if (id != current) {
                                        model.searchPlatform = id
                                        model.searchVideos(model.searchText)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            when {
                model.searching -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                model.searchResults.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(model.searchProblem ?: "Nothing found", color = c.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                }
                else -> items(model.searchResults, key = { it.id.toString() }) { stub ->
                    ResultCard(model, stub, liked = stub.id in model.searchLiked, saved = stub.id in saved)
                }
            }
        }
        Scrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** One result: the picture to tap (or hold for a preview), what it is, and Play, Like, Later and Playlist. */
@Composable
private fun ResultCard(model: AppModel, stub: VideoStub, liked: Boolean, saved: Boolean) {
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.04f else 1f)
    var holding by remember { mutableStateOf(false) }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceVariant)
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .tapOrHold(model.platform.isMobile, { model.showVideo(stub) }) { holding = it },
        ) {
            if (model.showThumbnails) ThumbnailImage(model, stub.id, large = true, zoom = zoom, preview = hovered || holding)
            formatDuration(stub.durationSeconds)?.let {
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
        Text(
            stub.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).clickable { model.showVideo(stub) },
        )
        val meta = listOfNotNull(stub.channelName, formatAge(stub.uploadedAt), "Short".takeIf { stub.isShort }).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundAction(Icons.Filled.PlayArrow, "Play", filled = true) { model.play(stub) }
            RoundAction(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (liked) "Liked" else "Like", filled = liked) { model.likeFromSearch(stub) }
            RoundAction(if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater, if (saved) "Saved for later" else "Save for later", filled = saved) { model.toggleLater(stub) }
            RoundAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "Add to a playlist") { model.openAddToPlaylist(listOf(stub.id)) }
        }
    }
}
