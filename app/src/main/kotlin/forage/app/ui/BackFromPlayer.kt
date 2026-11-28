package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.core.profile.VideoState

/**
 * Back from the player: the video just watched in another app, with what you'd want to do now
 * (like it, turn it down, keep it for later or in a playlist), so it isn't forgotten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackFromPlayerCard(model: AppModel, modifier: Modifier) {
    val card = model.backFromPlayer ?: return
    val c = MaterialTheme.colorScheme
    Surface(
        color = c.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 10.dp,
        modifier = modifier.widthIn(max = 520.dp).padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Thumbnail(model, card.video.id, 104.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Back from the player", style = MaterialTheme.typography.labelMedium, color = c.primary, fontWeight = FontWeight.SemiBold)
                    Text(
                        card.video.title ?: "The video you just watched",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    card.video.channelName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = model::dismissBackFromPlayer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            // Four round buttons in a row, as on a video page, so the card stays small on a phone.
            val liked = card.state == VideoState.LIKED
            val rejected = card.state == VideoState.NOT_INTERESTED
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CardAction(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (liked) "Liked" else "Like", liked) {
                    model.answerBackFromPlayer(if (liked) null else VideoState.LIKED)
                }
                CardAction(if (rejected) Icons.Filled.Block else Icons.Outlined.Block, "Not interested", rejected) {
                    model.answerBackFromPlayer(if (rejected) null else VideoState.NOT_INTERESTED)
                }
                if (card.savedForLater) CardAction(Icons.Outlined.CheckCircle, "Watched", false, model::laterFromBackFromPlayer)
                else CardAction(Icons.Outlined.WatchLater, "Later", false, model::laterFromBackFromPlayer)
                CardAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "Playlist", false, model::playlistFromBackFromPlayer)
            }
        }
    }
}

/** A round button with its label below; filled with the accent while it's on. */
@Composable
private fun CardAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(42.dp).then(if (active) Modifier.accentGlow(CircleShape, 8.dp) else Modifier)
                .clip(CircleShape).background(if (active) Accent.pastelPink else c.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) Accent.onAccent else c.onSurface, modifier = Modifier.size(20.dp))
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
