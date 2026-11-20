package forage.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import forage.core.PlatformId
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import forage.app.AppModel
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import forage.core.profile.VideoState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

fun formatDate(instant: Instant?): String? = instant?.let(DATE::format)

/** "3 weeks ago", as video sites put it. Upload dates from list results are approximate anyway. */
fun formatAge(instant: Instant?, now: Instant = Instant.now()): String? {
    val days = instant?.let { java.time.Duration.between(it, now).toDays() } ?: return null
    fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        days < 1 -> "today"
        days < 7 -> ago(days, "day")
        days < 31 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

fun formatDuration(seconds: Long?): String? = seconds?.let {
    val h = it / 3600
    val m = (it % 3600) / 60
    val s = it % 60
    if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The title area every screen starts with: a big title, a quiet subtitle, and the screen's main actions. */
@Composable
fun ScreenHeader(title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit = {}) {
    if (LocalCompact.current) {
        // A phone: the actions go under the title, wrapping onto more lines as needed.
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions() }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

/** A chip that switches an option on and off, with a check while on. */
@Composable
fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        leadingIcon = if (on) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
        shape = RoundedCornerShape(10.dp),
    )
}

/** The screen's main call to action, filled with the pastel accent. */
@Composable
fun AccentButton(label: String, icon: ImageVector?, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < 0.5f
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) c.primary else Accent.pastelPink,
            contentColor = if (dark) c.onPrimary else c.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** A small rounded label, e.g. a video's state. */
@Composable
fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A video's preview image, or a quiet placeholder while it loads or when thumbnails are off. */
@Composable
fun Thumbnail(model: AppModel, id: PlatformId, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val box = modifier.size(width, width * 9 / 16).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    if (!model.showThumbnails) return Box(box)
    Box(box) { ThumbnailImage(model, id, compact = true) }
}

private const val REFUSED_RETRIES = 3

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data class Shown(val image: ImageBitmap) : ThumbnailState
    data class Missing(val refused: Boolean) : ThumbnailState
}

/**
 * A video's thumbnail, filling the box it's in, loaded on first show. Where the site's image
 * server refused every image, says that the preview is only on the site instead of staying blank.
 */
@Composable
fun BoxScope.ThumbnailImage(
    model: AppModel,
    id: PlatformId,
    large: Boolean = false,
    zoom: Float = 1f,
    compact: Boolean = false,
    /** True while the pointer rests on the card: frames from across the video take turns, where the platform has them. */
    preview: Boolean = false,
) {
    // Keyed by the video: a card that shows another video after a new batch must not keep the old image.
    var state by remember(id, large) {
        mutableStateOf(model.thumbnails.cached(id, large)?.let { ThumbnailState.Shown(it) } ?: ThumbnailState.Loading)
    }
    LaunchedEffect(id, large) {
        if (state != ThumbnailState.Loading) return@LaunchedEffect
        // A refused image often loads on a later try, with links on another server; try again
        // a few times while the card is on screen.
        repeat(REFUSED_RETRIES + 1) { attempt ->
            if (attempt > 0) delay(model.thumbnails.retryAfter.toMillis() + 500)
            val image = model.thumbnails.load(id, large)
            state = image?.let { ThumbnailState.Shown(it) } ?: ThumbnailState.Missing(model.thumbnails.refused(id))
            if (image != null || !model.thumbnails.refused(id)) return@LaunchedEffect
        }
    }
    when (val s = state) {
        is ThumbnailState.Shown -> Image(
            s.image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
        )
        is ThumbnailState.Missing -> if (s.refused) RefusedPreview(compact)
        ThumbnailState.Loading -> {}
    }
    if (preview) PreviewFrames(model, id, zoom)
}

/**
 * Cycles through the video's frames over the thumbnail, with a bar of segments showing where in
 * the video the frame is. Starts after a short rest, so sweeping the pointer across cards doesn't
 * load every video's frames.
 */
@Composable
private fun BoxScope.PreviewFrames(model: AppModel, id: PlatformId, zoom: Float) {
    var frame by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var position by remember(id) { mutableStateOf(0 to 0) }
    LaunchedEffect(id) {
        delay(PREVIEW_REST_MS)
        var urls = withContext(Dispatchers.IO) { model.previewFrames(id) }
        var askedPlatform = false
        var i = 0
        var misses = 0
        while (urls.isNotEmpty() && misses < urls.size) {
            val image = model.thumbnails.frame(urls[i])
            if (image == null && i == 0 && !askedPlatform) {
                // Worked-out addresses that don't load: ask the platform for the real ones.
                askedPlatform = true
                urls = withContext(Dispatchers.IO) { model.previewFrames(id, fromPlatform = true) }
                continue
            }
            if (image != null) {
                misses = 0
                frame = image
                position = i to urls.size
                delay(PREVIEW_FRAME_MS)
            } else {
                misses++
            }
            i = (i + 1) % urls.size
        }
    }
    frame?.let {
        Image(
            it, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
        )
        val (at, of) = position
        // A soft shade under the bar keeps it readable on light frames.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(of) { n ->
                Box(
                    Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = if (n == at) 0.95f else 0.35f)),
                )
            }
        }
    }
}

private const val PREVIEW_REST_MS = 350L
private const val PREVIEW_FRAME_MS = 750L

@Composable
private fun BoxScope.RefusedPreview(compact: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.align(Alignment.Center).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.HideImage, contentDescription = "Preview only on the site", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(if (compact) 22.dp else 30.dp))
        if (!compact) {
            Text("Preview only on the site", style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.Center)
            Text("Its image server doesn't share it with apps", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun StateTag(state: VideoState?) {
    val c = MaterialTheme.colorScheme
    when (state) {
        VideoState.LIKED -> Tag("Liked", c.primaryContainer, c.onPrimaryContainer)
        VideoState.NOT_INTERESTED -> Tag("Not interested", c.errorContainer, c.onErrorContainer)
        null -> {}
    }
}

/** The height of a screen's header controls (search field, buttons, switches), so they line up. */
val ControlHeight = 44.dp

/**
 * A search field as tall as the buttons beside it; Material's text field is taller and stands out
 * in a row of buttons. Highlighted while focused, with a clear button once there's text.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
        interactionSource = interaction,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
        // A desktop keyboard's Enter doesn't always come through as the search action.
        modifier = modifier.height(ControlHeight).onPreviewKeyEvent { event ->
            if (onSearch != null && event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                (event.key == androidx.compose.ui.input.key.Key.Enter || event.key == androidx.compose.ui.input.key.Key.NumPadEnter)
            ) {
                onSearch()
                true
            } else false
        },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize()
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.primary else c.outline, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/**
 * Opens on a tap; on a phone, touching and holding sets [holding] until the finger lifts, so a
 * preview can play while it's held (phones have no hover). With a mouse it's an ordinary click.
 */
@Composable
fun Modifier.tapOrHold(mobile: Boolean, onTap: () -> Unit, holding: (Boolean) -> Unit): Modifier {
    if (!mobile) return clickable(onClick = onTap)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The system's long press waits about half a second, which feels slow for a preview; this
    // starts it sooner. A drag (the list scrolling) cancels it, as with a normal long press.
    return pointerInput(onTap) {
        awaitEachGesture {
            awaitFirstDown()
            var cancelled = false
            val up = withTimeoutOrNull(HOLD_MILLIS) { waitForUpOrCancellation().also { if (it == null) cancelled = true } }
            when {
                up != null -> {
                    up.consume()
                    onTap()
                }
                cancelled -> {}
                else -> {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    holding(true)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        holding(false)
                    }
                }
            }
        }
    }
}

/** How long a finger rests on a thumbnail before its preview starts. */
private const val HOLD_MILLIS = 250L

/** "← Back" at the top of a page that was opened from somewhere. */
@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = c.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onSurface)
    }
}

/** A video in a sideways row (Home, the library's overview): thumbnail, title, a line about it. */
@Composable
fun VideoTile(
    model: AppModel,
    id: PlatformId,
    title: String?,
    subtitle: String?,
    durationSeconds: Long?,
    width: Dp,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant)) {
            if (model.showThumbnails) ThumbnailImage(model, id, compact = width < 220.dp)
            formatDuration(durationSeconds)?.let {
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
        Text(
            title ?: "Untitled video",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (title != null) c.onSurface else c.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        subtitle?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** A section's title on Home and the library's overview, with a link to the whole list. */
@Composable
fun SectionTitle(title: String, link: String? = null, onLink: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (link != null) {
            Text(
                link,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLink)
                    .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** "3 h 12 min" for a whole queue or playlist. */
fun formatTotal(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600 + 59) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}

/**
 * Starts a list at the top when [order] changes (another sort or filter): left alone, a list keeps
 * the video that was at the top in view, wherever the new order puts it. Coming back to a list
 * keeps its place.
 */
@Composable
fun ScrollToTopOn(order: Any, state: androidx.compose.foundation.lazy.LazyListState) {
    var last by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(order.toString()) }
    LaunchedEffect(order) {
        if (order.toString() != last) {
            last = order.toString()
            state.scrollToItem(0)
        }
    }
}

@Composable
fun ScrollToTopOn(order: Any, state: androidx.compose.foundation.lazy.grid.LazyGridState) {
    var last by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(order.toString()) }
    LaunchedEffect(order) {
        if (order.toString() != last) {
            last = order.toString()
            state.scrollToItem(0)
        }
    }
}
