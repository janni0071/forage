package forage.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * "Share → Forage" (SPEC, M3: add to the profile in under three seconds): takes the link from the
 * shared text, adds the video to the open profile, says so, and is gone. Its details are fetched
 * in the background.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = (application as ForageApp).model
        // While the private vault is unlocked, shared videos go there; the private platform's only there.
        val model = app?.privateModel ?: app
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        // Apps share "Title https://…" or just the link; keep the links.
        val links = text.split(Regex("\\s+")).filter { "://" in it }
        val problem = when {
            model == null -> "Forage isn't ready yet; try again in a moment"
            links.isEmpty() -> "There's no link in what was shared"
            !model.isPrivate && links.any { link -> forage.app.Edition.vaultSite?.parseLink(link) != null } ->
                "Open Forage and unlock the private vault first, then share it again"
            else -> model.addByLink(links.joinToString("\n"))
        }
        Toast.makeText(this, problem ?: "Adding to “${model?.profile?.name}”", Toast.LENGTH_SHORT).show()
        finish()
    }
}
