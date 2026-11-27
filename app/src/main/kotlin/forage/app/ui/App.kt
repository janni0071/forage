package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Face3
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.Screen

/**
 * What a desktop window without the system's frame adds around the app: a title bar above the
 * content, somewhere to drag the window by, edges to resize it, and an outline.
 */
class WindowChrome(
    val titleBar: @Composable () -> Unit,
    val dragArea: Modifier,
    val overlay: @Composable BoxScope.() -> Unit,
    val outlined: Boolean,
)

/** True when the window is phone-narrow: no sidebar, bottom navigation, tighter margins. */
val LocalCompact = staticCompositionLocalOf { false }

/** The outer margins of a screen: generous in a window, tight on a phone. */
@Composable
fun screenPadding(): PaddingValues =
    if (LocalCompact.current) PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp) else PaddingValues(start = 32.dp, end = 16.dp, top = 24.dp)

/** Space kept clear at the right of a list for the scrollbar; phones scroll by swiping and have none. */
@Composable
fun scrollbarGap(): Dp = if (LocalCompact.current) 0.dp else 16.dp

@Composable
fun App(model: AppModel, onImport: () -> Unit, chrome: WindowChrome? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 640.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            val welcome = model.welcome
            if (model.playerFullscreen) FullscreenPlayer(model)
            else if (welcome != null) WelcomeScreen(model, welcome, onImport, chrome)
            else if (compact) PhoneApp(model, onImport) else WideApp(model, onImport, chrome)
            model.playerFailure?.let { PlayerFailureDialog(model, it) }
            BackupDialogs(model)
            model.sync?.let { SyncDialogs(it) }
        }
    }
}

/** A phone: profile and settings at the top, the four screens in a bottom navigation bar. */
@Composable
private fun PhoneApp(model: AppModel, onImport: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.background, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(c.surfaceContainerLow).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { ProfileSwitcher(model) }
                if (model.isPrivate) {
                    IconButton(onClick = { model.onLock?.invoke() }) { Icon(Icons.Outlined.Lock, contentDescription = "Lock private vault") }
                }
                IconButton(onClick = onImport) { Icon(Icons.Outlined.FileDownload, contentDescription = "Import a library") }
                // How sync stands, always in sight (and a reminder that it's there to set up).
                model.sync?.let { sync ->
                    IconButton(onClick = { model.openSettings(forage.app.SettingsPage.SYNC) }) {
                        androidx.compose.foundation.layout.Box(Modifier.semantics { contentDescription = "Sync: " + syncState(sync).name.lowercase() }) { SyncIcon(sync) }
                    }
                }
                IconButton(onClick = { model.openSettings() }) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
            }
            // The search bar slides away while the content scrolls down, and back when it scrolls up.
            var barShown by remember { mutableStateOf(true) }
            val hideOnScroll = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < -6f) barShown = false else if (available.y > 6f) barShown = true
                        return Offset.Zero
                    }
                }
            }
            LaunchedEffect(model.screen, model.libraryView, model.searchShown, model.focusSearch) { barShown = true }
            val withBar = model.videoPage == null
            val barOffset by animateDpAsState(if (barShown) 0.dp else 96.dp)
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(hideOnScroll)) {
                Screens(model, onImport)
                if (withBar) PhoneSearchBar(model, Modifier.align(Alignment.BottomCenter).offset(y = barOffset))
                // Above the search bar and Discover's floating button, not on top of them.
                val clear = (if (withBar && barShown) 72.dp else 12.dp) + (if (model.screen == Screen.DISCOVER && !model.searchShown && withBar) 76.dp else 0.dp)
                StatusToast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = clear))
                // Above the search bar, where a thumb reaches it.
                BackFromPlayerCard(model, Modifier.align(Alignment.BottomCenter).padding(bottom = if (withBar && barShown) 76.dp else 12.dp))
            }
            NavigationBar(containerColor = c.surfaceContainerLow) {
                for ((screen, tabIcon, label) in TABS) {
                    val icon = if (screen == Screen.SUBSCRIPTIONS) subscriptionsIcon(model) else tabIcon
                    NavigationBarItem(
                        selected = model.screen == screen,
                        onClick = { model.go(screen) },
                        icon = {
                            // How many new uploads wait in the subscriptions.
                            val count = if (screen == Screen.SUBSCRIPTIONS) model.newUploads else 0
                            if (count > 0) {
                                BadgedBox(badge = { Badge(containerColor = Accent.pastelPink, contentColor = Accent.onAccent) { Text(if (count > 99) "99+" else "$count") } }) {
                                    Icon(icon, contentDescription = null)
                                }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
    }
    if (model.settingsOpen) SettingsDialog(model)
}

private val TABS = listOf(
    Triple(Screen.HOME, Icons.Outlined.Home, "Home"),
    Triple(Screen.DISCOVER, Icons.Outlined.AutoAwesome, "Discover"),
    Triple(Screen.SUBSCRIPTIONS, Icons.Outlined.Subscriptions, "Subscriptions"),
    Triple(Screen.LIBRARY, Icons.Outlined.VideoLibrary, "Library"),
)

/** The subscriptions' icon: in the private vault a face, as it's where the performers you follow are. */
private fun subscriptionsIcon(model: AppModel) = if (model.isPrivate) Icons.Outlined.Face3 else Icons.Outlined.Subscriptions

@Composable
private fun Screens(model: AppModel, onImport: () -> Unit) {
    // The library's dialogs open from video pages and other screens too.
    LibraryDialogs(model)
    // Each screen keeps its scroll position while a video page or another screen is in front.
    val saved = rememberSaveableStateHolder()
    model.videoPage?.let { return VideoPageScreen(model, it) }
    val vault = if (model.isPrivate) "private" else "plain"
    if (model.searchShown) return saved.SaveableStateProvider("$vault/search") { SearchScreen(model) }
    val key = vault + "/" + if (model.screen == Screen.LIBRARY) "library/${model.libraryView}/${model.currentSource.label}/${model.openPlaylistName}" else model.screen.name
    saved.SaveableStateProvider(key) {
        when (model.screen) {
            Screen.HOME -> HomeScreen(model, onImport)
            Screen.DISCOVER -> DiscoverScreen(model)
            Screen.SUBSCRIPTIONS -> SubscriptionsScreen(model)
            Screen.LIBRARY -> LibraryScreen(model, onImport)
        }
    }
}

/** What the search bar says it searches: your library in the library tab, the platforms elsewhere. */
private fun searchHint(model: AppModel): String = when {
    model.screen == Screen.LIBRARY && !model.searchShown -> "Search your library"
    model.searchPlatforms.size == 1 -> "Search ${model.searchPlatforms.single().second}"
    else -> "Search videos"
}

/** The phone's search bar, just above the tabs where a thumb reaches it (SPEC, Direction). */
@Composable
private fun PhoneSearchBar(model: AppModel, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val inLibrary = model.screen == Screen.LIBRARY && !model.searchShown
    val value = if (inLibrary) model.query else model.searchText
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(model.focusSearch) { if (model.focusSearch > 0) runCatching { focus.requestFocus() } }
    Surface(
        color = c.surfaceContainerHigh,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().height(52.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { if (inLibrary) model.query = it else model.searchText = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                // Searching puts the keyboard away, so the results have the screen.
                keyboard?.hide()
                focusManager.clearFocus()
                model.submitSearch()
            }),
            modifier = Modifier.fillMaxSize().focusRequester(focus),
            decorationBox = { field ->
                Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(searchHint(model), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
                        field()
                    }
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { if (inLibrary) model.query = "" else model.searchText = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
        )
    }
}

/** The desktop's search bar, above every tab but the library, which has its own. */
@Composable
private fun TopSearchBar(model: AppModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(model.focusSearch) { if (model.focusSearch > 0) runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp)) {
        SearchField(
            model.searchText, { model.searchText = it }, searchHint(model),
            Modifier.widthIn(max = 560.dp).fillMaxWidth().focusRequester(focus),
            onSearch = model::submitSearch,
        )
    }
}

@Composable
private fun WideApp(model: AppModel, onImport: () -> Unit, chrome: WindowChrome?) {
    val outline = if (chrome?.outlined == true) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant) else Modifier
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(outline)) {
            Row {
                Sidebar(model, onImport, chrome)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    chrome?.titleBar?.invoke()
                    if (model.screen != Screen.LIBRARY || model.searchShown) TopSearchBar(model)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        // Beside the sidebar, a narrow window leaves a phone's width or less: the screens
                        // then lay out as on a phone (cards stacked, actions beneath titles) instead of
                        // squeezing a window's rows until their text breaks letter by letter.
                        CompositionLocalProvider(LocalCompact provides (maxWidth < 600.dp)) {
                            Screens(model, onImport)
                        }
                        StatusToast(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
                        // Above the status messages, in the corner.
                        BackFromPlayerCard(model, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 84.dp))
                    }
                }
            }
            chrome?.overlay?.invoke(this)
        }
    }
}

@Composable
private fun Sidebar(model: AppModel, onImport: () -> Unit, chrome: WindowChrome?) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.width(236.dp).fillMaxHeight().background(c.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 22.dp),
    ) {
        // Without the system title bar, the logo is somewhere to move the window by too.
        val move = chrome?.dragArea ?: Modifier
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 30.dp).then(move)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Accent.pastelPink, Accent.lavender))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent.onAccent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Forage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (model.isPrivate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Private vault", style = MaterialTheme.typography.labelMedium, color = c.tertiary, maxLines = 1)
                    }
                }
            }
        }

        NavItem(Icons.Outlined.Home, "Home", model.screen == Screen.HOME) { model.go(Screen.HOME) }
        NavItem(Icons.Outlined.AutoAwesome, "Discover", model.screen == Screen.DISCOVER, count = model.picks.size.takeIf { it > 0 }) {
            model.go(Screen.DISCOVER)
        }
        NavItem(subscriptionsIcon(model), "Subscriptions", model.screen == Screen.SUBSCRIPTIONS, count = model.newUploads.takeIf { it > 0 }) {
            model.go(Screen.SUBSCRIPTIONS)
        }
        NavItem(Icons.Outlined.VideoLibrary, "Library", model.screen == Screen.LIBRARY) { model.go(Screen.LIBRARY) }

        Spacer(Modifier.weight(1f))
        if (model.isPrivate) {
            NavItem(Icons.Outlined.Lock, "Lock private vault", selected = false) { model.onLock?.invoke() }
        } else {
            NavItem(Icons.Outlined.FileDownload, "Import Takeout", selected = false, enabled = model.job == null, onClick = onImport)
        }
        model.sync?.let { SyncItem(model, it) }
        SettingsItem(model)
        HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 8.dp), color = c.outlineVariant)
        ProfileSwitcher(model)
    }
}

/** The sidebar's sync status: who this computer syncs with and when it last did, or a reminder that it can. */
@Composable
private fun SyncItem(model: AppModel, sync: forage.app.SyncController) {
    val c = MaterialTheme.colorScheme
    val off = sync.devices.isEmpty()
    val selected = model.settingsOpen && model.settingsPage == forage.app.SettingsPage.SYNC
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .clickable { model.openSettings(forage.app.SettingsPage.SYNC) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncIcon(sync, 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Sync", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = c.onSurface)
            Text(
                if (off) (if (model.isPrivate) "Set up for this vault" else "Set up with your other devices") else syncLine(sync),
                style = MaterialTheme.typography.bodySmall, color = if (sync.problem != null) c.error else c.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    count: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val content = if (selected) c.onPrimaryContainer else c.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(44.dp)
            .then(if (selected) Modifier.accentGlow(RoundedCornerShape(12.dp), 10.dp) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) c.onPrimaryContainer else c.onSurface,
            modifier = Modifier.weight(1f),
        )
        count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = content) }
    }
}

@Composable
private fun SettingsItem(model: AppModel) {
    NavItem(Icons.Outlined.Settings, "Settings", selected = model.settingsOpen && model.settingsPage != forage.app.SettingsPage.SYNC) { model.openSettings() }
    if (model.settingsOpen) SettingsDialog(model)
}

/** Progress and messages float above the content, like a snackbar. */
@Composable
private fun StatusToast(model: AppModel, modifier: Modifier) {
    val job = model.job
    val text = job?.let { j ->
        listOfNotNull(j.label, j.total?.let { "${j.done} of $it" }, j.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
    } ?: model.message
    val background = model.background
    // Messages close themselves after a while: longer ones, and problems, stay a little longer.
    val message = model.message
    LaunchedEffect(message, job == null) {
        if (message == null || job != null) return@LaunchedEffect
        val problem = listOf("failed", "couldn't", "can't", "isn't", "wrong", "needs").any { it in message.lowercase() }
        delay(((if (problem) 9_000L else 4_000L) + message.length * 40L).coerceAtMost(15_000L))
        if (model.message == message) model.message = null
    }
    if (text == null && background == null) return
    val c = MaterialTheme.colorScheme
    Surface(
        color = c.inverseSurface,
        contentColor = c.inverseOnSurface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp),
    ) {
        Column {
            if (job != null) {
                val total = job.total
                val bar = Modifier.fillMaxWidth().height(3.dp)
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { job.done.toFloat() / total }, modifier = bar, color = c.inversePrimary, trackColor = Color.Transparent)
                } else {
                    LinearProgressIndicator(modifier = bar, color = c.inversePrimary, trackColor = Color.Transparent)
                }
            }
            Row(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (background != null && text == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.inversePrimary)
                }
                Text(text ?: background.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (background != null && text != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.inversePrimary)
                }
                if (job == null && text != null) {
                    IconButton(onClick = { model.message = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = c.inverseOnSurface, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

/** A player quit with an error: what it said, in full, to read or copy, until it's closed. */
@Composable
private fun PlayerFailureDialog(model: AppModel, text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { model.playerFailure = null },
        title = { Text("The player couldn't play it") },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { model.playerFailure = null }) { Text("Close") } },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
        },
    )
}
