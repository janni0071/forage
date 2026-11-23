package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.VideoPage
import forage.core.source.Playback

/**
 * A platform's own video player, drawn where a video plays in Forage. The player behind it lives
 * outside the screen that shows it, so going full screen and back carries on where it was.
 */
interface VideoSurface {
    /**
     * Shows [playback], filling [modifier]. [onFullscreen] asks to fill the screen or to leave it;
     * [onProblem] says it won't play after all; [onEnded] says it played to the end.
     */
    @Composable
    fun Player(
        playback: Playback,
        fullscreen: Boolean,
        onFullscreen: (Boolean) -> Unit,
        onProblem: (String) -> Unit,
        onEnded: () -> Unit,
        modifier: Modifier,
    )

    /** Lets go of the player: nothing plays any more. */
    fun release()
}

/** The platform's player; null where videos go to other apps. */
val LocalVideoSurface = staticCompositionLocalOf<VideoSurface?> { null }

/** Where the video on [page] plays: the player once its streams are known, and what to do if it won't play. */
@Composable
fun PlayerArea(model: AppModel, page: VideoPage, modifier: Modifier = Modifier) {
    val surface = LocalVideoSurface.current
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val playback = page.playback
        val problem = page.playbackProblem ?: "Forage can't play videos here".takeIf { surface == null }
        when {
            problem != null -> {
                if (model.showThumbnails) Box(Modifier.fillMaxSize().alpha(0.25f)) { ThumbnailImage(model, page.id, large = true) }
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("It won't play here", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        problem, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = model::playElsewhere, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("  Another app", color = Color.White)
                        }
                        OutlinedButton(onClick = model::retryPlayback, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("  Try again", color = Color.White)
                        }
                    }
                }
            }
            playback == null || surface == null -> {
                if (model.showThumbnails) Box(Modifier.fillMaxSize().alpha(0.4f)) { ThumbnailImage(model, page.id, large = true) }
                CircularProgressIndicator(color = Color.White)
            }
            else -> surface.Player(
                playback,
                fullscreen = false,
                onFullscreen = { model.playerFullscreen = it },
                onProblem = { model.playbackFailed(page.id, it) },
                onEnded = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Stops it and brings the picture back.
        IconButton(
            onClick = model::stopPlaying,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Stop playing", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** The playing video across the whole screen; Back, or the player's own button, leaves it. */
@Composable
fun FullscreenPlayer(model: AppModel) {
    val page = model.videoPage
    val playback = page?.playback
    val surface = LocalVideoSurface.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (page == null || playback == null || surface == null) {
            CircularProgressIndicator(color = Color.White)
            return@Box
        }
        surface.Player(
            playback,
            fullscreen = true,
            onFullscreen = { model.playerFullscreen = it },
            onProblem = { model.playbackFailed(page.id, it) },
            onEnded = { model.playerFullscreen = false },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
