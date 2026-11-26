package forage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import forage.app.AppModel
import forage.app.Player
import forage.app.WelcomeStep

/** Where Google Takeout opens with only YouTube selected. */
const val TAKEOUT_URL = "https://takeout.google.com/takeout/custom/youtube"

/**
 * The welcome at first launch, in place of the app: what Forage is, then how to bring a library
 * along (Google Takeout, another device with Forage, or nothing), each walked through; on a
 * computer, which player plays the videos. Get started's Import opens it at the Takeout steps.
 */
@Composable
fun WelcomeScreen(model: AppModel, step: WelcomeStep, onImport: () -> Unit, chrome: WindowChrome?) {
    val c = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    Surface(color = c.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                chrome?.titleBar?.invoke()
                // Back and Skip (or Close), above the step.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step != WelcomeStep.HELLO && (model.welcomeAtFirstLaunch || step == WelcomeStep.PLAYER)) {
                        IconButton(onClick = model::welcomeBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (model.welcomeAtFirstLaunch) TextButton(onClick = model::closeWelcome) { Text("Skip") }
                    else IconButton(onClick = model::closeWelcome) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = if (compact) 20.dp else 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = if (compact) 8.dp else 32.dp, bottom = 32.dp)) {
                        when (step) {
                            WelcomeStep.HELLO -> Hello(model)
                            WelcomeStep.BRING -> Bring(model)
                            WelcomeStep.TAKEOUT -> Takeout(model, onImport)
                            WelcomeStep.PAIR -> Pair(model)
                            WelcomeStep.PLAYER -> ChoosePlayer(model)
                        }
                    }
                }
            }
            chrome?.overlay?.invoke(this)
        }
    }
}

@Composable
private fun ColumnScope.Hello(model: AppModel) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(64.dp).clip(CircleShape).background(Accent.pastelPink), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Accent.onAccent, modifier = Modifier.size(32.dp))
    }
    Title("Welcome to Forage", top = 20.dp)
    Lead("YouTube on your terms: no account, no feed pulling you along.")
    Feature(Icons.Outlined.NoAccounts, "No account", "Your likes, history and playlists stay on your devices. Nothing to sign in to.")
    Feature(Icons.Outlined.AutoAwesome, "Recommendations when you ask", "Discover suggests videos from what you like, only when you open it. No endless feed.")
    Feature(
        Icons.Outlined.PlayCircle, "Your player",
        if (Player.IN_APP in model.platform.players) "Videos play right here, without ads, or in another app you choose."
        else "Videos play in mpv, FreeTube or your browser, whichever you like.",
    )
    Spacer(Modifier.height(28.dp))
    AccentButton("Get started", null) { model.welcomeTo(WelcomeStep.BRING) }
}

@Composable
private fun ColumnScope.Bring(model: AppModel) {
    Title("Bring your YouTube along")
    Lead("Forage starts from what you've watched and liked. Pick a way in; the others stay possible later.")
    Choice(
        Icons.Outlined.FileDownload, "Import from YouTube",
        "Your watch history, playlists and subscriptions, through Google Takeout. A few clicks, then Google emails you the file.",
    ) { model.welcomeTo(WelcomeStep.TAKEOUT) }
    model.sync?.let {
        Choice(Icons.Outlined.Devices, "I use Forage on another device", "Pair the two, and your library comes over your Wi-Fi.") {
            model.welcomeTo(WelcomeStep.PAIR)
        }
    }
    Choice(Icons.Outlined.Search, "Start fresh", "Search for videos you enjoy and like a few: that's enough for recommendations.") {
        model.welcomeChosen()
        // The search bar is where starting fresh starts, once the welcome is done.
        if (model.welcome == null) model.askForSearch()
    }
}

@Composable
private fun ColumnScope.Takeout(model: AppModel, onImport: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val phone = model.platform.isMobile
    var linkFailed by remember { mutableStateOf(false) }
    Title("Import from YouTube")
    Lead("Google Takeout is how Google lets you download your data. Forage needs three parts of it.")
    Numbered(1, "Open Google Takeout and sign in with your YouTube account.", "It opens with only YouTube selected.") {
        OutlinedButton(onClick = { linkFailed = !model.platform.openLink(TAKEOUT_URL) }, shape = RoundedCornerShape(12.dp)) {
            Text("Open Google Takeout")
        }
        if (linkFailed) {
            Text("Couldn't open a browser. Open this address in one:", style = MaterialTheme.typography.bodySmall, color = c.error, modifier = Modifier.padding(top = 6.dp))
            SelectionContainer { Text(TAKEOUT_URL, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    val click = if (phone) "Tap" else "Click"
    Numbered(
        2, "$click \"All YouTube data included\", then \"Deselect all\", and tick history, playlists and subscriptions. Then OK.",
        "Your own uploaded videos stay out, which keeps the file small.",
    )
    Numbered(
        3, "$click \"Next step\", then \"Create export\".",
        "Google emails you when it's ready: usually within minutes, sometimes a few hours.",
    )
    Numbered(
        4,
        if (phone) "Open the email on this device and download the zip. Then tap Import and choose it."
        else "Download the zip from the email. Then click Import and choose it.",
        if (phone) "Forage reads the zip as it is; there's nothing to unpack."
        else "Forage reads the zip as it is, or the folder you unpacked it to.",
    )
    Spacer(Modifier.height(20.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentButton("Import the zip", Icons.Outlined.FileDownload) {
            onImport()
            model.welcomeChosen()
        }
        TextButton(onClick = model::welcomeChosen) { Text(if (model.welcomeAtFirstLaunch) "Wait for the email" else "Later") }
    }
    Hint("Meanwhile you can search and like videos already. Home's Get started keeps this step until it's done.")
}

@Composable
private fun ColumnScope.Pair(model: AppModel) {
    val c = MaterialTheme.colorScheme
    val sync = model.sync ?: return
    Title("From your other device")
    Lead("Pair this ${model.platform.deviceKind} with the other device, and your library comes over, kept the same on both from then on.")
    Numbered(1, "Open Forage on the other device, on the same Wi-Fi as this one.")
    Numbered(2, "There, open Settings, then Sync, and choose Pair a device.")
    Numbered(3, "Here, choose Pair a device, pick the other one and check that both show the same code.")
    Spacer(Modifier.height(20.dp))
    val paired = sync.devices.firstOrNull()
    if (paired == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Pair a device", Icons.Outlined.Devices, onClick = sync::openPairing)
            TextButton(onClick = {
                model.welcomeChosen()
                model.chooseBackup()
            }) { Text("Restore a backup file instead") }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Paired with ${paired.name}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (sync.syncing) "Your library is coming over…" else "Your library is here, and stays in step.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AccentButton("Continue", null, onClick = model::welcomeChosen)
    }
}

@Composable
private fun ColumnScope.ChoosePlayer(model: AppModel) {
    Title("How videos play")
    Lead("Forage finds and organizes videos; a player plays them. You can change this later in Settings.")
    // Checking for players starts small processes, so do it once.
    val problems = remember { model.platform.players.associateWith { model.platform.playerProblem(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (p in model.platform.players) {
            PlayerCard(p, problems[p], model.player == p, Modifier.weight(1f)) { model.choosePlayer(p) }
        }
    }
    when {
        problems[Player.MPV] == "not installed" ->
            Hint("mpv plays videos in a window of its own, without ads, and lets Forage remember where you stopped. Install it with \"sudo apt install mpv\", and yt-dlp with \"pipx install yt-dlp\"; or both at once from Flathub (io.mpv.Mpv). Then come back to Settings.")
        problems[Player.MPV] == "needs yt-dlp" ->
            Hint("mpv needs a current yt-dlp to play YouTube videos. Install it with \"pipx install yt-dlp\".")
    }
    Spacer(Modifier.height(24.dp))
    AccentButton("Done", null, onClick = model::closeWelcome)
}

@Composable
private fun Title(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = top))
}

@Composable
private fun Lead(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.onSurface, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surfaceContainer)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Accent.pastelPink), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Accent.onAccent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.onSurfaceVariant)
    }
}

@Composable
private fun Numbered(n: Int, text: String, detail: String? = null, extra: @Composable ColumnScope.() -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).border(2.dp, c.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c.primary)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 3.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            Column(Modifier.padding(top = 6.dp)) { extra() }
        }
    }
}
