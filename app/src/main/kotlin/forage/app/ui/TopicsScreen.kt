package forage.app.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import forage.core.recommend.TopicStore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import forage.app.AppModel

@Composable
fun TopicsScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 16.dp, top = 24.dp)) {
        ScreenHeader("Topics", "How your profile groups itself. Pin a topic to get picks from it in every batch; mute one to get none.") {
            AccentButton(
                if (model.topics.isEmpty()) "Show topics" else if (model.topicsCurrent) "Refresh" else "Refresh (profile changed)",
                Icons.Outlined.Refresh, enabled = model.job == null, onClick = model::loadTopics,
            )
        }
        if (model.topics.isEmpty()) {
            Text(
                "Press \"Show topics\" to group your profile. This also happens with every Discover batch.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return
        }
        Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 14.dp, bottom = 96.dp)) {
                items(model.topics, key = { it.index }) { TopicCard(model, it) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun TopicCard(model: AppModel, topic: Cluster) {
    val muted = topic.preference == TopicPreference.MUTED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (topic.preference == TopicPreference.PINNED) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (model.renamingTopic == topic.index) {
                        RenameField(model, topic)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                topic.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            if (!topic.isVaried) {
                                IconButton(onClick = { model.renamingTopic = topic.index }, modifier = Modifier.size(32.dp).padding(start = 4.dp)) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Text(
                        when {
                            topic.isVaried -> "${topic.videos.size} videos that share no topic with others. Each still finds its own picks."
                            // A named topic still shows what it's about, in the automatic keywords.
                            topic.customName != null -> "${topic.videos.size} videos · ${topic.keywords.joinToString(", ")}"
                            else -> "${topic.videos.size} videos" + topic.keywords.drop(3).takeIf { it.isNotEmpty() }?.let { " · also: ${it.joinToString(", ")}" }.orEmpty()
                        },
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
                    PreferenceButton(Icons.Outlined.PushPin, "Pin", "Pinned", topic.preference == TopicPreference.PINNED) {
                        model.setTopicPreference(topic, if (topic.preference == TopicPreference.PINNED) null else TopicPreference.PINNED)
                    }
                    PreferenceButton(Icons.Outlined.VisibilityOff, "Mute", "Muted", muted) {
                        model.setTopicPreference(topic, if (muted) null else TopicPreference.MUTED)
                    }
                }
            }
        }
    }
}

/** Edits a topic's name in place. Enter saves, Escape cancels, an empty name restores the automatic one. */
@Composable
private fun RenameField(model: AppModel, topic: Cluster) {
    var name by remember(topic.index) { mutableStateOf(TextFieldValue(topic.customName ?: "", TextRange(0, (topic.customName ?: "").length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.text.length <= TopicStore.MAX_NAME) name = it },
            placeholder = { Text(topic.autoLabel) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(360.dp).focusRequester(focus).onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Enter -> { model.renameTopic(topic, name.text); true }
                    event.key == Key.Escape -> { model.renamingTopic = null; true }
                    else -> false
                }
            },
        )
        AccentButton("Save", null) { model.renameTopic(topic, name.text) }
        TextButton(onClick = { model.renamingTopic = null }) { Text("Cancel") }
        if (topic.customName != null) {
            TextButton(onClick = { model.renameTopic(topic, null) }) { Text("Use automatic name") }
        }
    }
}

@Composable
private fun PreferenceButton(icon: ImageVector, label: String, activeLabel: String, active: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (active) activeLabel else label)
    }
    if (active) FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { content() }
    else OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { content() }
}
