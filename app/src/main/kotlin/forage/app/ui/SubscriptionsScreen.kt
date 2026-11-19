package forage.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.app.AppModel

/**
 * The channels and performers you follow. Their new uploads in date order come with the
 * Subscriptions step (SPEC, Direction: order of work); until then this is the list itself.
 */
@Composable
fun SubscriptionsScreen(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val listState = rememberLazyListState()
    val following = model.following
    // Someone unfollowed here stays on the list until the next visit, with Follow to take it back.
    var shown by remember { mutableStateOf(following) }
    LaunchedEffect(following) {
        val known = shown.mapTo(HashSet()) { it.channel }
        shown = shown + following.filter { it.channel !in known }
    }
    val followed = following.mapTo(HashSet()) { it.channel }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = scrollbarGap()),
            state = listState,
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 12.dp else 24.dp, bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ScreenHeader(
                    "Subscriptions",
                    if (following.isEmpty()) null else "You follow ${following.size}. Their new uploads will show here, newest first, with the next update.",
                )
            }
            if (following.isEmpty()) {
                item {
                    Text(
                        "You don't follow anyone yet. Follow channels from a video's page; " +
                            (if (model.isPrivate) "" else "a Takeout import brings your YouTube subscriptions along. ") +
                            "Their new uploads will show here, newest first, with no ranking.",
                        color = c.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
            }
            items(shown, key = { it.channel.toString() }) { s ->
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Initial(s.name ?: "?", 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name ?: s.channel.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (s.channel.id.startsWith("pornstar/")) "Performer" else "Channel",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (s.channel in followed) {
                        OutlinedButton(onClick = { model.unfollow(s.channel) }, shape = RoundedCornerShape(18.dp)) { Text("Following") }
                    } else {
                        Button(onClick = { model.follow(s) }, shape = RoundedCornerShape(18.dp)) { Text("Follow") }
                    }
                }
            }
        }
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
