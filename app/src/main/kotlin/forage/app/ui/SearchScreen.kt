package forage.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import forage.app.AppModel

/** The results of a search from the search bar, on a page of their own. Back returns to the tab. */
@Composable
fun SearchScreen(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = scrollbarGap()),
            state = listState,
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 4.dp else 16.dp, bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { BackLink("Back", model::back) }
            item {
                Column(Modifier.padding(bottom = 10.dp)) {
                    Text(
                        "Results for \"${model.searchText.trim()}\"",
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    )
                    if (model.searchPlatforms.size > 1) {
                        val current = model.searchPlatform ?: model.searchPlatforms.first().first
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("On", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                            for ((id, name) in model.searchPlatforms) {
                                ToggleChip(name, id == current) {
                                    if (id != current) {
                                        model.searchPlatform = id
                                        model.searchVideos(model.searchText)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            when {
                model.searching -> item {
                    Box(Modifier.padding(top = 48.dp).widthIn(max = 880.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                model.searchResults.isEmpty() -> item {
                    Text(
                        model.searchProblem ?: "Nothing found",
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                else -> items(model.searchResults, key = { it.id.toString() }) { stub ->
                    Box(Modifier.widthIn(max = 880.dp)) { SearchResultRow(model, stub) }
                }
            }
        }
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
