package forage.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.core.recommend.TopicPreference
import forage.desktop.DesktopModel
import forage.desktop.ui.Thumbnail
import forage.desktop.Feedback
import forage.desktop.PickView

@Composable
fun DiscoverScreen(model: DesktopModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Header(model)
        Row(Modifier.weight(1f).padding(top = 12.dp)) {
            Box(Modifier.weight(1f)) {
                if (model.picks.isEmpty()) {
                    Text(
                        "No recommendations yet. Add videos to your profile in the library, fetch their details,\n" +
                            "then press \"Find recommendations\". Nothing is ever recommended unless you ask.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 14.dp, bottom = 16.dp),
                    ) {
                        items(model.picks, key = { it.pick.position }) { PickCard(model, it) }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (model.topics.isNotEmpty()) {
                Spacer(Modifier.width(20.dp))
                TopicsPanel(model)
            }
        }
    }
}

@Composable
private fun Header(model: DesktopModel) {
    var search by remember { mutableStateOf(model.settings.searchYouTube) }
    var shorts by remember { mutableStateOf(model.settings.showShorts) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Discover", style = MaterialTheme.typography.headlineSmall)
            Text(
                model.discoverNote ?: if (model.picks.isEmpty()) "Recommendations from your profile, on request"
                else "Last batch: ${model.picks.size} picks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = search, onCheckedChange = { search = it; model.settings.searchYouTube = it })
            Text("Search YouTube too")
            Spacer(Modifier.width(12.dp))
            Checkbox(checked = shorts, onCheckedChange = { shorts = it; model.settings.showShorts = it })
            Text("Show Shorts")
            Spacer(Modifier.width(20.dp))
            Button(onClick = model::discover, enabled = model.job == null) {
                Text(if (model.picks.isEmpty()) "Find recommendations" else "Find a new batch")
            }
        }
    }
}

@Composable
private fun PickCard(model: DesktopModel, view: PickView) {
    val pick = view.pick
    val dimmed = Feedback.NOT_FOR_ME in view.feedback
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pick.exploration) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "${pick.position}.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Thumbnail(model, pick.video, 208.dp)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pick.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(pick.channelName ?: "Unknown channel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pick.exploration) {
                    Tag("Exploration", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                "Why: ${pick.reason.removePrefix("exploration: ")}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Found via ${pick.foundVia}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // Text buttons carry their own inner padding; shift them so the label lines up with the text above.
            Row(Modifier.padding(top = 8.dp).offset(x = (-12).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.open(pick.video) }) { Text("Open") }
                FeedbackButton("More like this", "✓ More like this", Feedback.MORE in view.feedback) { model.feedback(view, Feedback.MORE) }
                FeedbackButton("Not for me", "✓ Not for me", Feedback.NOT_FOR_ME in view.feedback) { model.feedback(view, Feedback.NOT_FOR_ME) }
                FeedbackButton("Save for later", "✓ Saved", Feedback.SAVED in view.feedback) { model.feedback(view, Feedback.SAVED) }
            }
            }
        }
    }
}

@Composable
private fun FeedbackButton(label: String, activeLabel: String, active: Boolean, onClick: () -> Unit) {
    if (active) FilledTonalButton(onClick = onClick) { Text(activeLabel) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun TopicsPanel(model: DesktopModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(280.dp).fillMaxHeight(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your topics", style = MaterialTheme.typography.titleMedium)
            Text(
                "How the profile grouped itself for this batch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(model.topics, key = { it.index }) { topic ->
                    Row {
                        Text(
                            topic.label + when (topic.preference) {
                                TopicPreference.PINNED -> "  · pinned"
                                TopicPreference.MUTED -> "  · muted"
                                null -> ""
                            },
                            color = if (topic.preference == TopicPreference.MUTED) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${topic.videos.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
