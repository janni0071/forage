package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.LibrarySource
import forage.app.LibraryView
import forage.app.Screen

/**
 * Home: what you chose, in one place (SPEC, Direction). The watch-later queue, the latest
 * Discover batch, what you watched lately and who you follow. Nothing here is recommended
 * unless you asked for it.
 */
@Composable
fun HomeScreen(model: AppModel, onImport: () -> Unit) {
    val compact = LocalCompact.current
    val tile = if (compact) 200.dp else 240.dp
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = scrollbarGap()),
            state = listState,
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 12.dp else 24.dp, bottom = 120.dp,
            ),
        ) {
            if (!compact) item { ScreenHeader("Home", "Your queue, your picks and what you watched lately") }
            item { GetStarted(model, onImport) }

            if (model.later.isNotEmpty()) {
                item { SectionTitle("Continue", "Watch later · ${model.later.size}") { model.openLibrary(LibraryView.LATER) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.sortedLater.take(12), key = { it.id.toString() }) { item ->
                            VideoTile(model, item.id, item.title, item.channelName, item.durationSeconds, tile) { model.showLater(item) }
                        }
                    }
                }
            }

            if (model.uploads.isNotEmpty()) {
                val newCount = model.newUploads
                item { SectionTitle(if (newCount > 0) "New from your subscriptions · $newCount" else "From your subscriptions", "All") { model.go(Screen.SUBSCRIPTIONS) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.uploads.take(12), key = { it.video.id.toString() }) { u ->
                            val v = u.video
                            VideoTile(model, v.id, v.title, listOfNotNull(v.channelName, formatAge(v.uploadedAt)).joinToString("  ·  "), v.durationSeconds, tile) {
                                model.showVideo(v)
                            }
                        }
                    }
                }
            }

            if (model.picks.isNotEmpty()) {
                item { SectionTitle("From your latest Discover batch", "All ${model.picks.size}") { model.go(Screen.DISCOVER) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.picks.take(12), key = { it.pick.position }) { view ->
                            val p = view.pick
                            VideoTile(model, p.video, p.title, p.channelName, p.durationSeconds, tile) { model.showPick(p) }
                        }
                    }
                }
            } else if (model.profileSize > 0) {
                item { DiscoverInvite(model) }
            }

            if (model.recentlyWatched.isNotEmpty()) {
                item { SectionTitle("Recently watched", "History") { model.openLibrary(LibraryView.LIST, LibrarySource.History) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(model.recentlyWatched, key = { it.id.toString() }) { row ->
                            VideoTile(model, row.id, row.title, listOfNotNull(row.channelName, formatAge(row.lastSeen)).joinToString("  ·  "), row.durationSeconds, tile) {
                                model.showRow(row)
                            }
                        }
                    }
                }
            }

            if (model.following.isNotEmpty()) {
                item { SectionTitle("Following", "All ${model.following.size}") { model.go(Screen.SUBSCRIPTIONS) } }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(model.following.take(30), key = { it.channel.toString() }) { s ->
                            Column(
                                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { model.go(Screen.SUBSCRIPTIONS) }
                                    .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(model, s.name ?: "?", model.pictureOf(s.channel), 56.dp)
                                Text(
                                    s.name ?: s.channel.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Someone's picture, round, where the platform has one; their first letter otherwise. */
@Composable
fun Avatar(model: AppModel, name: String, url: String?, size: androidx.compose.ui.unit.Dp) {
    var image by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url, model.pictureAttempt) {
        if (url == null || !model.showThumbnails) return@LaunchedEffect
        // A refused or broken image often loads on a later try.
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(model.thumbnails.retryAfter.toMillis() + 500)
            image = model.thumbnails.frame(url)
            if (image != null) return@LaunchedEffect
        }
    }
    val loaded = image
    if (loaded == null) return Initial(name, size)
    androidx.compose.foundation.Image(
        loaded, contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape),
    )
}

/** A round badge with someone's first letter, where there's no picture. */
@Composable
fun Initial(name: String, size: androidx.compose.ui.unit.Dp) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(CircleShape).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = if (size > 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = c.onSecondaryContainer,
        )
    }
}

/**
 * The first steps for a new library, each gone once done; the card goes when all are. In the
 * private vault there's nothing to import.
 */
@Composable
private fun GetStarted(model: AppModel, onImport: () -> Unit) {
    var confirmDownload by remember { mutableStateOf(false) }
    val steps = buildList {
        if (model.isPrivate) {
            // The private vault starts empty, with the site to search and its catalogue to fetch.
            add(Step("Find videos with the search bar", model.hasSearched, "Search", model::askForSearch,
                "It searches the site. Tap a result to open it; Play hands it to your player."))
            add(Step("Like a few videos", model.profileSize >= 3, null, {},
                "Discover recommends from what you like, and nothing else."))
            add(Step("Follow your favourite performers", model.following.isNotEmpty(), null, {},
                "Tap Follow on a video's page, or tap a tag with their name. Their new videos show under Subscriptions."))
            if (model.offersCatalogue) {
                add(Step("Download the catalogue (optional)", model.catalogueInfo != null, "Download", { confirmDownload = true },
                    "The site's public list of all its videos, about 1.6 GB to download and 1.3 GB on this device. With it, " +
                        "Discover matches your taste against millions of videos instead of a few searches, and related videos " +
                        "and previews come from it. It holds nothing about you."))
            }
        } else {
            add(Step("Import your YouTube history from Google Takeout", model.imported, "Import", onImport))
            add(Step("Search for videos you enjoy", model.hasSearched, "Search", model::askForSearch))
            add(
                Step(
                    "Like a few videos: recommendations come from what you like", model.profileSize >= 3,
                    if (model.historySize > 0) "Your history" else null,
                    { model.openLibrary(LibraryView.LIST, LibrarySource.History) },
                )
            )
        }
        // Like an account, without one: the other device stays in step.
        model.sync?.let { sync ->
            add(
                Step(
                    if (model.isPrivate) "Sync this vault with your other devices (optional)" else "Pair your other devices (optional)",
                    sync.devices.isNotEmpty(), "Pair", { model.openSettings(forage.app.SettingsPage.SYNC) },
                    if (model.isPrivate) "Its likes, history and who you follow stay the same in the vault on each, while it's unlocked on both."
                    else "Likes, history, playlists and where you left off stay the same on all of them, over your Wi-Fi, with no account.",
                ),
            )
        }
    }
    if (model.guideHidden || steps.all { it.done }) return
    if (confirmDownload) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download the catalogue?") },
            text = {
                Text(
                    "About 1.6 GB, best on Wi-Fi. Forage imports it in a few minutes and then deletes the download; " +
                        "if it breaks off, starting again continues where it stopped. You can also do this later in Settings."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDownload = false; model.downloadCatalogue() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Not now") } },
        )
    }
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().widthIn(max = 720.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Get started", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // Optional steps can stay undone for good; the card can go anyway.
            TextButton(onClick = model::hideGuide) { Text("Hide") }
        }
        Text(
            if (model.isPrivate) "This vault is encrypted and kept apart from everything else in Forage. A few steps to fill it:"
            else "Forage keeps your likes, history and playlists on this device, with no account anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        steps.forEach { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape)
                        .background(if (step.done) Accent.pastelPink else Color.Transparent)
                        .border(2.dp, if (step.done) Accent.pastelPink else c.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = Accent.onAccent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.text, style = MaterialTheme.typography.bodyLarge, color = if (step.done) c.onSurfaceVariant else c.onSurface)
                    if (!step.done && step.detail != null) {
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (!step.done && step.action != null) TextButton(onClick = step.onClick) { Text(step.action) }
            }
        }
    }
}

private class Step(val text: String, val done: Boolean, val action: String?, val onClick: () -> Unit, val detail: String? = null)

@Composable
private fun DiscoverInvite(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth().widthIn(max = 720.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("Find new videos from what you like", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        AccentButton("Discover", null) { model.go(Screen.DISCOVER) }
    }
}
