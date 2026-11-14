// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Theme

/**
 * "Feedback" tile: ask the developer for a feature or report a bug. Sending opens a ready-filled
 * issue on the project's GitHub page, so nothing leaves the phone until you press submit there.
 */
class FeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startWithBug = intent.getBooleanExtra(EXTRA_BUG, false)
        setContent { Theme { Screen(startWithBug) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun Screen(startWithBug: Boolean) {
        var bug by remember { mutableStateOf(startWithBug) }
        var title by remember { mutableStateOf("") }
        var details by remember { mutableStateOf("") }
        var steps by remember { mutableStateOf("") }
        var includePhone by remember { mutableStateOf(true) }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { TopAppBar(title = { Text("Bug report & ideas") }) },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = bug, onClick = { bug = true }, label = { Text("🐞 Report a bug") })
                    FilterChip(selected = !bug, onClick = { bug = false }, label = { Text("💡 Request a feature") })
                }
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (bug) "What went wrong? (short)" else "Your idea in a few words") })
                OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text(if (bug) "What happened, and what did you expect?" else "Tell us more: what should it do?") })
                if (bug) {
                    OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        label = { Text("How to make it happen (which app, what you pressed)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includePhone, { includePhone = it })
                        Text("Include phone model, Android and keyboard version")
                    }
                }
                Text("Sending opens the project's GitHub page with your report filled in. " +
                        "You'll need a free GitHub account to post it, and nothing is sent until you press Submit there.",
                    style = MaterialTheme.typography.bodySmall)
                Button(enabled = title.isNotBlank() || details.isNotBlank(), onClick = { send(bug, title, details, steps, includePhone) }) {
                    Text("Send")
                }
            }
        }
    }

    private fun send(bug: Boolean, title: String, details: String, steps: String, includePhone: Boolean) {
        val body = buildString {
            append(if (bug) "### What happened\n" else "### The idea\n")
            append(details.ifBlank { title }).append("\n\n")
            if (bug && steps.isNotBlank()) append("### How to make it happen\n").append(steps).append("\n\n")
            if (bug && includePhone) {
                append("### Phone\n")
                append("- Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("- Open Keyboard: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            }
            append("\n_Sent from the keyboard's Feedback tile_")
        }
        val prefix = if (bug) "[Bug] " else "[Feature] "
        val url = Uri.parse(ISSUES_URL).buildUpon()
            .appendQueryParameter("title", prefix + title.ifBlank { details.take(60) })
            .appendQueryParameter("body", body)
            .appendQueryParameter("labels", if (bug) "bug" else "enhancement")
            .build()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object {
        const val EXTRA_BUG = "bug"
        private const val ISSUES_URL = "https://github.com/jrod-86/open-keyboard/issues/new"
    }
}
