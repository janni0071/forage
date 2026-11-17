package forage.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.desktop.DesktopModel

@Composable
fun LaterScreen(model: DesktopModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Watch later", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Videos you saved from Discover. Imported YouTube playlists stay in the library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (model.later.isEmpty()) {
            Text("Nothing saved yet. Use \"Save for later\" on a recommendation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        LazyColumn {
            items(model.later, key = { it.id.toString() }) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(model, row.id, 128.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(row.title ?: row.id.toString(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(row.channelName, formatDuration(row.durationSeconds)).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { model.open(row.id) }) { Text("Open") }
                    TextButton(onClick = { model.removeFromLater(row.id) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
}
