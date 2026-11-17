package forage.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
    val image by produceState(model.thumbnails.cached(id), id) { if (value == null) value = model.thumbnails.load(id) }
    Box(box) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
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
