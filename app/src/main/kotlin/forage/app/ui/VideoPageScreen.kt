package forage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.Creator
import forage.app.VideoPage
import forage.core.profile.VideoState
import forage.core.source.VideoStub

/**
 * A video's own page: what it is, what you've done with it, and Play as the way out. Related
 * videos open their own pages; Back (or Escape) goes back one page.
 */
@Composable
fun VideoPageScreen(model: AppModel, page: VideoPage) {
    val compact = LocalCompact.current
    val focus = remember { FocusRequester() }
    // Keys reach the page only while it has focus: Escape goes back.
    LaunchedEffect(page.id) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    model.closeVideo()
                    true
                } else false
            },
    ) {
        // Related videos beside the video in a wide window, below it otherwise.
        val sideBySide = maxWidth >= 1000.dp
        val relatedWidth = if (maxWidth >= 1300.dp) 400.dp else 340.dp
        // The picture takes a little over half the height at most, so Play shows without scrolling.
        val mainWidth = if (compact) Dp.Infinity else minOf(880.dp, maxHeight * 0.55f * 16f / 9f)
        val related = page.details?.related.orEmpty().filter { model.settings.showShorts || !it.isShort }
        val listState = rememberLazyListState()
        LaunchedEffect(page.id) { listState.scrollToItem(0) }
        if (sideBySide) {
            Row(Modifier.fillMaxSize().padding(screenPadding())) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(end = 24.dp, bottom = 48.dp)) {
                    item { BackRow(model) }
                    item { Main(model, page, mainWidth) }
                }
                val sideState = rememberLazyListState()
                LaunchedEffect(page.id) { sideState.scrollToItem(0) }
                Box(Modifier.width(relatedWidth).fillMaxHeight()) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = scrollbarGap()), state = sideState, contentPadding = PaddingValues(top = 52.dp, bottom = 48.dp)) {
                        relatedItems(model, page, related)
                    }
                    Scrollbar(sideState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(end = scrollbarGap()),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                        top = if (compact) 4.dp else 24.dp, bottom = 48.dp,
                    ),
                ) {
                    item { BackRow(model) }
                    item { Main(model, page, mainWidth) }
                    relatedItems(model, page, related)
                }
                Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun BackRow(model: AppModel) = BackLink(if (model.videoStack.size > 1) "Back to the previous video" else "Back", model::closeVideo)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Main(model: AppModel, page: VideoPage, maxWidth: Dp) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val metadata = page.details?.metadata
    // Wide enough for a big picture, narrow enough that Play stays in view.
    Column(Modifier.widthIn(max = maxWidth)) {
        BigVideoThumbnail(model, page)

        Text(
            page.shownTitle ?: "Untitled video",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (page.shownTitle != null) c.onSurface else c.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
        val facts = listOfNotNull(
            formatDuration(page.shownDuration),
            formatAge(page.shownUploadedAt),
            "Short".takeIf { metadata?.isShort == true },
        )
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        ChannelRow(model, page)

        // Play first: it's the way out to the player.
        val play: @Composable () -> Unit = {
            Row(
                Modifier
                    .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(220.dp))
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Accent.pastelPink)
                    .clickable(onClick = model::playPage)
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF3E0423), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF3E0423))
            }
        }
        val liked = page.state == VideoState.LIKED
        val rejected = page.state == VideoState.NOT_INTERESTED
        val inPlaylists = page.inLibrary.playlists
        val actions: @Composable () -> Unit = {
            PageAction(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (liked) "Liked" else "Like", liked) { model.choose(VideoState.LIKED) }
            PageAction(if (rejected) Icons.Filled.Block else Icons.Outlined.Block, "Not interested", rejected) { model.choose(VideoState.NOT_INTERESTED) }
            PageAction(
                if (page.inLibrary.savedForLater) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                if (page.inLibrary.savedForLater) "Saved" else "Later", page.inLibrary.savedForLater, onClick = model::toggleLater,
            )
            PageAction(
                if (inPlaylists.isEmpty()) Icons.AutoMirrored.Outlined.PlaylistAdd else Icons.AutoMirrored.Filled.PlaylistAddCheck,
                when (inPlaylists.size) {
                    0 -> "Playlist"
                    1 -> "In 1 playlist"
                    else -> "In ${inPlaylists.size} playlists"
                },
                inPlaylists.isNotEmpty(),
            ) { model.openAddToPlaylist(listOf(page.id)) }
        }
        if (compact) {
            Box(Modifier.padding(top = 14.dp)) { play() }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) { actions() }
        } else {
            // The actions move below Play when the column is too narrow for both.
            FlowRow(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.Top,
            ) {
                // Level with the round buttons, not with their labels below: their circles are
                // 44 dp tall under 4 dp of padding, so their middle is 26 dp down, as Play's is here.
                Box(Modifier.padding(top = 2.dp, end = 8.dp)) { play() }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { actions() }
            }
        }

        LibraryNote(page)

        when {
            page.loading && page.details == null -> Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading the description and related videos", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            page.gone -> Text(
                "This video is no longer available: ${page.problem}",
                style = MaterialTheme.typography.bodyMedium, color = c.error, modifier = Modifier.padding(top = 24.dp),
            )
            page.problem != null && page.details == null -> Column(Modifier.padding(top = 24.dp)) {
                Text("Couldn't load the details: ${page.problem}", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                OutlinedButton(onClick = model::retryVideo, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
            }
        }

        metadata?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { Description(it) }
        val tags = metadata?.tags.orEmpty().filter { it.isNotBlank() }.distinct().take(16)
        if (tags.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { Tag(it, c.surfaceContainerHigh, c.onSurface) }
            }
        }
    }
}

/** The thumbnail: previews on hover or while held, and plays when clicked or tapped. */
@Composable
private fun BigVideoThumbnail(model: AppModel, page: VideoPage) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var holding by remember(page.id) { mutableStateOf(false) }
    val zoom by animateFloatAsState(if (hovered) 1.02f else 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .tapOrHold(model.platform.isMobile, model::playPage) { holding = it },
    ) {
        if (model.showThumbnails) ThumbnailImage(model, page.id, large = true, zoom = zoom, preview = hovered || holding)
        if (!holding) {
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (hovered) 0.7f else 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(38.dp))
            }
        }
        formatDuration(page.shownDuration)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

/** The channel, or on platforms without channels each performer, with Follow. */
@Composable
private fun ChannelRow(model: AppModel, page: VideoPage) {
    val creators = page.creators.take(MAX_CREATORS)
    if (creators.isEmpty()) return
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        creators.forEach { CreatorRow(model, it, following = it.id in page.following) }
    }
}

private const val MAX_CREATORS = 6

@Composable
private fun CreatorRow(model: AppModel, creator: Creator, following: Boolean) {
    val c = MaterialTheme.colorScheme
    val name = creator.name
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Their picture where it's known: for those you follow, from their channel page.
        Avatar(model, name, model.following.firstOrNull { it.channel == creator.id }?.avatarUrl, 40.dp)
        Spacer(Modifier.width(12.dp))
        // The name takes the room, so Follow buttons line up at the right, one under the other.
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (creator.id != null) {
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (following) c.surfaceContainerHigh else c.onSurface)
                    .clickable { model.toggleFollow(creator) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (following) "Following" else "Follow",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (following) c.onSurface else c.surface,
                )
            }
        }
    }
}

/** A round button with its label below, as in a phone's share sheet; filled while it's on. */
@Composable
private fun PageAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (active) Accent.pastelPink else c.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) Color(0xFF3E0423) else c.onSurface, modifier = Modifier.size(21.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** Where the video already is: the history and playlists. */
@Composable
private fun LibraryNote(page: VideoPage) {
    val lib = page.inLibrary
    val parts = listOfNotNull(
        when {
            lib.watchCount == 0 -> null
            lib.watchCount == 1 -> "Watched" + (formatDate(lib.lastWatched)?.let { " on $it" } ?: "")
            else -> "Watched ${lib.watchCount}×" + (formatDate(lib.lastWatched)?.let { ", last on $it" } ?: "")
        },
        lib.playlists.takeIf { it.isNotEmpty() }?.let { "In " + it.joinToString(", ") { name -> "\"$name\"" } },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** The description, a few lines at first. */
@Composable
private fun Description(text: String) {
    val c = MaterialTheme.colorScheme
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceContainerLow)
            .clickable(enabled = overflows || expanded) { expanded = !expanded }
            .padding(14.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelLarge,
                color = c.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun LazyListScope.relatedItems(model: AppModel, page: VideoPage, related: List<VideoStub>) {
    if (related.isEmpty()) return
    item {
        Text(
            "Related",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
        )
    }
    items(related, key = { "${page.id}/${it.id}" }) { stub -> RelatedRow(model, stub) }
}

@Composable
private fun RelatedRow(model: AppModel, stub: VideoStub) {
    val c = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) c.surfaceContainerLow else Color.Transparent)
            .hoverable(hover)
            .clickable { model.showVideo(stub) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Thumbnail(model, stub.id, 152.dp)
            formatDuration(stub.durationSeconds)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stub.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(stub.channelName, formatAge(stub.uploadedAt)).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
