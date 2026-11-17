package forage.desktop.ui

import androidx.compose.foundation.background
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
