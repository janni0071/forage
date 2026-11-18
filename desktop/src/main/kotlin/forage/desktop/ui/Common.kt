package forage.desktop.ui

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
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import forage.core.PlatformId
import forage.desktop.DesktopModel
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
fun Thumbnail(model: DesktopModel, id: PlatformId, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val box = modifier.size(width, width * 9 / 16).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    if (!model.showThumbnails) return Box(box)
    Box(box) { ThumbnailImage(model, id, compact = true) }
}

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
fun BoxScope.ThumbnailImage(model: DesktopModel, id: PlatformId, large: Boolean = false, zoom: Float = 1f, compact: Boolean = false) {
    val start = model.thumbnails.cached(id, large)?.let { ThumbnailState.Shown(it) } ?: ThumbnailState.Loading
    val state by produceState<ThumbnailState>(start, id, large) {
        if (value == ThumbnailState.Loading) {
            value = model.thumbnails.load(id, large)?.let { ThumbnailState.Shown(it) }
                ?: ThumbnailState.Missing(model.thumbnails.refused(id))
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
}

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
