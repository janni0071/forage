package forage.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
        VideoState.INCLUDED -> Tag("In profile", c.primaryContainer, c.onPrimaryContainer)
        VideoState.EXCLUDED -> Tag("Excluded", c.surfaceVariant, c.onSurfaceVariant)
        VideoState.NEGATIVE -> Tag("Not for me", c.errorContainer, c.onErrorContainer)
        null -> {}
    }
}
