package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.LaterItem
import forage.core.library.FollowedChannel
import forage.core.library.Upload

/**
 * The latest uploads of everyone you follow, newest first, with no ranking (SPEC, Direction).
 * The people themselves along the top, to narrow the list to one; Manage changes who they are.
 */
@Composable
fun SubscriptionsScreen(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val listState = rememberLazyListState()
    val following = model.following
    val channel = model.feedChannel
    val shown = model.uploads.filter { channel == null || it.video.channel == channel }
    val saved = model.later.mapTo(HashSet()) { it.id }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = scrollbarGap()),
            state = listState,
            contentPadding = PaddingValues(
                start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 16.dp,
                top = if (compact) 12.dp else 24.dp, bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Header(model) }
            when {
                following.isEmpty() -> item {
                    Text(
                        "You don't follow anyone yet. Follow channels from a video's page" +
                            (if (model.isPrivate) "" else ", or import your YouTube subscriptions from Takeout") +
                            ". Their new uploads show here, newest first, with no ranking.",
                        color = c.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
                model.managingFollowing -> item { ManageList(model) }
                else -> {
                    item { People(model) }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                if (model.checkingFeeds) "Looking up their latest uploads…" else "No uploads found yet. Refresh to look them up.",
                                color = c.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                    val (fresh, earlier) = shown.partition { it.isNew }
                    if (fresh.isNotEmpty()) {
                        item { SectionTitle("New · ${fresh.size}") }
                        items(fresh, key = { "new/${it.video.id}" }) { UploadRow(model, it, it.video.id in saved) }
                    }
                    if (earlier.isNotEmpty()) {
                        if (fresh.isNotEmpty()) item { SectionTitle("Earlier") }
                        items(earlier, key = { "old/${it.video.id}" }) { UploadRow(model, it, it.video.id in saved) }
                    }
                }
            }
        }
        Scrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Header(model: AppModel) {
    val n = model.following.size
    val gone = model.following.count { it.gone != null }
    val checked = model.following.mapNotNull { it.checkedAt }.maxOrNull()
    val goneNote = if (gone == 0) "" else " · $gone no longer on the platform"
    val subtitle = when {
        n == 0 -> null
        model.managingFollowing -> "You follow $n. Changes take effect right away."
        model.checkingFeeds -> "From $n you follow · checking for new uploads…"
        checked != null -> "From $n you follow · checked ${formatAge(checked)}$goneNote"
        else -> "From $n you follow"
    }
    ScreenHeader("Subscriptions", subtitle) {
        if (n > 0) {
            if (model.managingFollowing) {
                AccentButton("Done", Icons.Filled.Check) { model.managingFollowing = false }
            } else {
                OutlinedButton(onClick = { model.refreshFeeds(force = true) }, enabled = !model.checkingFeeds, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                    if (model.checkingFeeds) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
                OutlinedButton(onClick = { model.managingFollowing = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(ControlHeight)) {
                    Icon(Icons.Outlined.ManageAccounts, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage")
                }
            }
        }
    }
}

/** Everyone followed, along the top: a dot for new uploads; tap one to see only theirs. */
@Composable
private fun People(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val withNew = model.uploads.filter { it.isNew }.mapNotNullTo(HashSet()) { it.video.channel }
    // In the order chosen in Manage. Those that are gone stay out: they have nothing to show.
    val ordered = model.sortedFollowing.filter { it.gone == null }
    LazyRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ordered, key = { it.channel.toString() }) { f ->
            val selected = model.feedChannel == f.channel
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) c.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { model.feedChannel = if (selected) null else f.channel }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Avatar(model, f.name ?: "?", model.pictureOf(f.channel), 56.dp)
                    if (f.channel in withNew) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Accent.pastelPink)
                                .border(2.dp, c.background, CircleShape),
                        )
                    }
                }
                Text(
                    f.name ?: f.channel.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) c.onPrimaryContainer else c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun UploadRow(model: AppModel, upload: Upload, saved: Boolean) {
    val v = upload.video
    val item = LaterItem(v.id, v.title, v.channelName, v.durationSeconds, v.uploadedAt, java.time.Instant.EPOCH)
    QueueRow(model, item, null, QueueActions.upload(model, item, saved) { model.toggleLater(v) })
}

/**
 * Who you follow, to change it. Someone unfollowed stays on the list until the next visit, with
 * Follow to take it back.
 */
@Composable
private fun ManageList(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val following = model.following
    var shown by remember { mutableStateOf(following) }
    LaunchedEffect(following) {
        val known = shown.mapTo(HashSet()) { it.channel }
        shown = shown + following.filter { it.channel !in known }
    }
    val followed = following.mapTo(HashSet()) { it.channel }
    // The chosen order; someone unfollowed just now keeps their place until the next visit.
    val order = model.sortedFollowing.map { it.channel }.withIndex().associate { (i, ch) -> ch to i }
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Order", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            for (sort in forage.app.FollowSort.entries) {
                androidx.compose.material3.FilterChip(
                    selected = model.followSort == sort,
                    onClick = { model.sortFollowing(sort) },
                    label = { Text(sort.label, maxLines = 1) },
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }
        // The ones that are gone first: they're the ones to unfollow.
        shown.sortedWith(compareBy({ it.gone == null }, { order[it.channel] ?: Int.MAX_VALUE })).forEach { s ->
            Row(
                Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(model, s.name ?: "?", model.pictureOf(s.channel), 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name ?: s.channel.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Why someone shows a letter, where Forage knows: the way to tell what to fix.
                    val missing = if (s.gone == null) model.missingPicture(s.channel) else null
                    Text(
                        when {
                            s.gone != null -> goneText(s.gone.orEmpty())
                            else -> (if (s.channel.id.startsWith("pornstar/")) "Performer" else "Channel") +
                                (missing?.let { " · no picture: $it" } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.gone != null) c.error else c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
}

/** The platform's long explanation for a removed channel, in a few words. */
private fun goneText(reason: String): String = when {
    "terminated" in reason.lowercase() -> "Gone: account terminated"
    "removed" in reason.lowercase() -> "Gone: channel removed"
    else -> "Gone: $reason"
}
