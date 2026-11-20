@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package forage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.core.recommend.TopicPreference
import forage.app.AppModel
import forage.app.Feedback
import forage.app.PickView

@Composable
fun DiscoverScreen(model: AppModel) {
    val compact = LocalCompact.current
    val header: @Composable () -> Unit = {
        Column {
            Header(model)
            if (model.topics.isNotEmpty()) TopicStrip(model)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(screenPadding())) {
            // On a phone the header scrolls away with the cards; in a window it stays above them.
            if (!compact || model.picks.isEmpty()) header()
            if (model.picks.isEmpty()) {
                Text(
                    "No recommendations yet. Like a few videos in the library or from a search, " +
                        "then press \"Find recommendations\". Nothing is ever recommended unless you ask.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                Box(Modifier.weight(1f).padding(top = if (compact) 0.dp else 16.dp)) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 290.dp),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                        contentPadding = PaddingValues(end = scrollbarGap(), top = 8.dp, bottom = 96.dp),
                    ) {
                        if (compact) item(span = { GridItemSpan(maxLineSpan) }) { header() }
                        items(model.picks, key = { it.pick.position }) { PickCard(model, it) }
                    }
                    Scrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        // A phone's main action floats at the bottom right, where a thumb reaches it, so the
        // header's chips fit on one line. Hidden while a batch is being found.
        if (compact && model.job == null) {
            ExtendedFloatingActionButton(
                onClick = model::discover,
                icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                text = { Text(if (model.picks.isEmpty()) "Find recommendations" else "New batch") },
                containerColor = Accent.pastelPink,
                contentColor = Color(0xFF3E0423),
                // Above the search bar.
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp),
            )
        }
    }
}

@Composable
private fun Header(model: AppModel) {
    var search by remember { mutableStateOf(model.settings.searchYouTube) }
    var shorts by remember { mutableStateOf(model.settings.showShorts) }
    ScreenHeader(
        "Discover",
        model.discoverNote ?: if (model.picks.isEmpty()) "Recommendations from what you like, only when you ask"
        else "${model.picks.size} picks in the last batch",
    ) {
        ToggleChip(if (model.isPrivate) "Search online" else "Search YouTube", search) { search = it; model.settings.searchYouTube = it }
        ToggleChip("Shorts", shorts) { shorts = it; model.settings.showShorts = it }
        if (!LocalCompact.current) {
            Spacer(Modifier.width(6.dp))
            AccentButton(if (model.picks.isEmpty()) "Find recommendations" else "New batch", Icons.Filled.AutoAwesome, enabled = model.job == null, onClick = model::discover)
        }
    }
}

/** The profile's topics as a row of chips; the full view with pin and mute is on the Topics screen. */
@Composable
private fun TopicStrip(model: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(end = scrollbarGap()).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (topic in model.topics.filter { !it.isVaried }) {
            val c = MaterialTheme.colorScheme
            val (container, content, prefix) = when (topic.preference) {
                TopicPreference.PINNED -> Triple(c.primaryContainer, c.onPrimaryContainer, "Pinned: ")
                TopicPreference.MUTED -> Triple(c.surfaceContainer, c.onSurfaceVariant.copy(alpha = 0.6f), "Muted: ")
                null -> Triple(c.surfaceContainerHigh, c.onSurface, "")
            }
            Text(
                "$prefix${topic.label}  ${topic.videos.size}",
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(container)
                    .clickable { model.openLibrary(forage.app.LibraryView.TASTE) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PickCard(model: AppModel, view: PickView) {
    val pick = view.pick
    val rejected = Feedback.NOT_INTERESTED in view.feedback
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered && !rejected) 1.05f else 1f)
    // On a phone, touching and holding the thumbnail plays its preview.
    var holding by remember { mutableStateOf(false) }

    // Anywhere on the card opens the video; its buttons do their own thing.
    Column(
        Modifier.alpha(if (rejected) 0.45f else 1f).clip(RoundedCornerShape(14.dp))
            .clickable { model.showPick(pick) }.pointerHoverIcon(PointerIcon.Hand),
    ) {
        // The thumbnail: click to watch; zooms gently under the mouse.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .tapOrHold(model.platform.isMobile, { model.showPick(pick) }) { holding = it },
        ) {
            if (model.showThumbnails) ThumbnailImage(model, pick.video, large = true, zoom = zoom, preview = (hovered || holding) && !rejected)
            formatDuration(pick.durationSeconds)?.let {
                Badge(it, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pick.exploration) Badge("Exploration", background = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                if (Feedback.SAVED in view.feedback) Badge("Saved", background = Accent.pastelPink, content = MaterialTheme.colorScheme.onPrimaryContainer)
                if (Feedback.LIKE in view.feedback) Badge("Liked", background = Accent.pastelPink, content = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Text(
            pick.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Two lines reserved even for short titles, so the cards in a row line up.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).clickable { model.showPick(pick) },
        )
        Text(
            listOfNotNull(pick.channelName, formatAge(pick.uploadedAt)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        // The reason, kept short on the card; the whole of it, and where the pick came from, on hover.
        Tooltip(
            tooltip = {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(420.dp),
                ) {
                    Text("Why: ${pick.reason}\nFound via ${pick.foundVia}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            },
            delayMillis = 400,
        ) {
            Text(
                shortReason(pick.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Wraps to a second line on narrow cards rather than squeezing a button.
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(Icons.Outlined.ThumbUp, "Like", Feedback.LIKE in view.feedback) { model.feedback(view, Feedback.LIKE) }
            Action(Icons.Outlined.Block, "Not interested", rejected) { model.feedback(view, Feedback.NOT_INTERESTED) }
            val saved = Feedback.SAVED in view.feedback
            // A bookmark alone reads as "save" and keeps the three on one line.
            Tooltip(tooltip = { Hint(if (saved) "Saved for later; click to remove" else "Save for later") }, delayMillis = 300) {
                Action(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null, saved) { model.feedback(view, Feedback.SAVED) }
            }
        }
    }
}

/** The reason with its most telling part first: "similar to …" reads better on a small card than the topic. */
private fun shortReason(reason: String): String {
    val parts = reason.removePrefix("exploration: ").split("; ")
    val similar = parts.firstOrNull { it.startsWith("similar to") }
    val rest = parts.filter { it != similar && it != "recent upload" }
    return listOfNotNull(similar, rest.firstOrNull(), "recent upload".takeIf { "recent upload" in parts })
        .joinToString(" · ")
        .replaceFirstChar { it.uppercase() }
}

@Composable
private fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color.Black.copy(alpha = 0.75f),
    content: Color = Color.White,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = content,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A compact pill button that stays filled while its feedback is active. */
@Composable
private fun Action(icon: ImageVector, label: String?, active: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val content = if (active) c.onPrimaryContainer else c.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) c.primaryContainer else c.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 9.dp, end = if (label == null) 9.dp else 11.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        if (label != null) {
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Hint(text: String) = Surface(
    color = MaterialTheme.colorScheme.inverseSurface,
    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    shape = RoundedCornerShape(8.dp),
) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
}
