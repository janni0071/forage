package forage.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import forage.app.ui.Accent
import forage.app.ui.formatDuration
import kotlinx.coroutines.delay

/** Where the embedded page's video is, as its player reports it. */
data class EmbedState(val seconds: Double = 0.0, val duration: Double = 0.0, val paused: Boolean = true)

/**
 * Forage's own controls over an embedded video, once the page shows nothing but the video: a tap
 * shows them (or hides them), they hide by themselves while it plays, a double tap on either side
 * skips ten seconds.
 */
@Composable
fun EmbedControls(
    state: EmbedState,
    fullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    // Any touch on the controls starts the wait to hide them again.
    var touched by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(shown, state.paused, touched, dragging) {
        if (shown && !state.paused && dragging == null) {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(state.duration) {
                detectTapGestures(
                    onTap = { shown = !shown; touched++ },
                    onDoubleTap = { at ->
                        val step = if (at.x < size.width / 2) -SKIP_SECONDS else SKIP_SECONDS
                        onSeek((state.seconds + step).coerceIn(0.0, maxOf(state.duration - 1, 0.0)))
                        touched++
                    },
                )
            },
    ) {
        AnimatedVisibility(shown || state.paused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.6f))))) {
                IconButton(
                    onClick = { onPlayPause(); touched++ },
                    modifier = Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.paused) "Play" else "Pause",
                        tint = Color.White, modifier = Modifier.size(40.dp),
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val at = dragging?.toDouble() ?: state.seconds
                    Text(
                        "${formatDuration(at.toLong())} / ${formatDuration(state.duration.toLong())}",
                        style = MaterialTheme.typography.labelMedium, color = Color.White,
                    )
                    Slider(
                        value = if (state.duration > 0) (at / state.duration).toFloat().coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = (it * state.duration).toFloat(); touched++ },
                        onValueChangeFinished = {
                            dragging?.let { onSeek(it.toDouble()) }
                            dragging = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Accent.pastelPink, activeTrackColor = Accent.pastelPink,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onFullscreen(!fullscreen); touched++ }) {
                        Icon(
                            if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            contentDescription = if (fullscreen) "Leave full screen" else "Full screen", tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private const val HIDE_AFTER_MS = 3000L
private const val SKIP_SECONDS = 10.0
