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
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        Header(model)
        if (model.topics.isNotEmpty()) TopicStrip(model)
        if (model.picks.isEmpty()) {
            Text(
                "No recommendations yet. Add videos to your profile in the library, fetch their details,\n" +
                    "then press \"Find recommendations\". Nothing is ever recommended unless you ask.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return
        }
        Box(Modifier.weight(1f).padding(top = 16.dp)) {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 290.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(end = scrollbarGap(), top = 8.dp, bottom = 96.dp),
            ) {
                items(model.picks, key = { it.pick.position }) { PickCard(model, it) }
            }
            Scrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun Header(model: AppModel) {
    var search by remember { mutableStateOf(model.settings.searchYouTube) }
    var shorts by remember { mutableStateOf(model.settings.showShorts) }
    ScreenHeader(
        "Discover",
        model.discoverNote ?: if (model.picks.isEmpty()) "Recommendations from your profile, only when you ask"
        else "${model.picks.size} picks in the last batch",
    ) {
        ToggleChip(if (model.isPrivate) "Search online" else "Search YouTube", search) { search = it; model.settings.searchYouTube = it }
        ToggleChip("Shorts", shorts) { shorts = it; model.settings.showShorts = it }
        Spacer(Modifier.width(6.dp))
        AccentButton(if (model.picks.isEmpty()) "Find recommendations" else "New batch", Icons.Filled.AutoAwesome, enabled = model.job == null, onClick = model::discover)
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
                    .clickable { model.screen = forage.app.Screen.TOPICS }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PickCard(model: AppModel, view: PickView) {
    val pick = view.pick
    val rejected = Feedback.NOT_FOR_ME in view.feedback
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered && !rejected) 1.05f else 1f)

    Column(Modifier.alpha(if (rejected) 0.45f else 1f)) {
        // The thumbnail: click to watch; zooms gently under the mouse.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { model.open(pick.video) },
        ) {
            if (model.showThumbnails) ThumbnailImage(model, pick.video, large = true, zoom = zoom, preview = hovered && !rejected)
            formatDuration(pick.durationSeconds)?.let {
                Badge(it, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pick.exploration) Badge("Exploration", background = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                if (Feedback.SAVED in view.feedback) Badge("Saved", background = Accent.pastelPink, content = MaterialTheme.colorScheme.onPrimaryContainer)
                if (Feedback.MORE in view.feedback) Badge("More like this", background = Accent.pastelPink, content = MaterialTheme.colorScheme.onPrimaryContainer)
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
            modifier = Modifier.padding(top = 10.dp).clickable { model.open(pick.video) },
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
            Action(Icons.Outlined.ThumbUp, "More like this", Feedback.MORE in view.feedback) { model.feedback(view, Feedback.MORE) }
            Action(Icons.Outlined.Block, "Not for me", rejected) { model.feedback(view, Feedback.NOT_FOR_ME) }
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
