package forage.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.core.recommend.Cluster
import forage.core.recommend.TopicPreference
import forage.desktop.DesktopModel

@Composable
fun TopicsScreen(model: DesktopModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Topics", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How your profile groups itself. Pin a topic to get picks from it in every batch; mute one to get none.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = model::loadTopics, enabled = model.job == null) {
                Text(if (model.topics.isEmpty()) "Show topics" else if (model.topicsCurrent) "Refresh" else "Refresh (profile changed)")
            }
        }
        if (model.topics.isEmpty()) {
            Text(
                "Press \"Show topics\" to group your profile. This also happens with every Discover batch.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return
        }
        Box(Modifier.weight(1f).padding(top = 12.dp)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 14.dp, bottom = 16.dp)) {
                items(model.topics, key = { it.index }) { TopicCard(model, it) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun TopicCard(model: DesktopModel, topic: Cluster) {
    val muted = topic.preference == TopicPreference.MUTED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (topic.preference == TopicPreference.PINNED) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        topic.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (topic.isVaried) "${topic.videos.size} videos that share no topic with others. Each still finds its own picks."
                        else "${topic.videos.size} videos" + topic.keywords.drop(3).takeIf { it.isNotEmpty() }?.let { " · also: ${it.joinToString(", ")}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (topic.preference) {
                    TopicPreference.PINNED -> Tag("Pinned", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    TopicPreference.MUTED -> Tag("Muted", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> {}
                }
            }
            // A strip of the topic's videos says more than its keywords.
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (video in topic.videos.take(6)) {
                    Column(Modifier.weight(1f)) {
                        Thumbnail(model, video.id, 150.dp)
                        Text(
                            video.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                repeat((6 - topic.videos.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
            }
            if (!topic.isVaried) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreferenceButton("Pin", "✓ Pinned", topic.preference == TopicPreference.PINNED) {
                        model.setTopicPreference(topic, if (topic.preference == TopicPreference.PINNED) null else TopicPreference.PINNED)
                    }
                    PreferenceButton("Mute", "✓ Muted", muted) {
                        model.setTopicPreference(topic, if (muted) null else TopicPreference.MUTED)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceButton(label: String, activeLabel: String, active: Boolean, onClick: () -> Unit) {
    if (active) FilledTonalButton(onClick = onClick) { Text(activeLabel) } else OutlinedButton(onClick = onClick) { Text(label) }
}
