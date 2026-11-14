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
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Logs", style = MaterialTheme.typography.titleMedium)
                Text("Something acting up? Get the keyboard's latest logs (and any crash reports) as one zip file. " +
                        "Save it to your phone, or share it straight to email, Messenger or Drive. You can attach it to a bug report too.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { withLogs { saveLogs.launch(it.name) } }) { Text("Save logs") }
                    androidx.compose.material3.OutlinedButton(onClick = { withLogs { shareLogs(it) } }) { Text("Share logs") }
                }
            }
        }
    }

    private var logZip: java.io.File? = null

    private val saveLogs = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = logZip ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { out -> zip.inputStream().use { it.copyTo(out) } } }.isSuccess
        android.widget.Toast.makeText(this, if (ok) "Logs saved" else "Couldn't save the logs", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Gathers the logs into a zip in the background, then hands it to [then]. */
    private fun withLogs(then: (java.io.File) -> Unit) {
        android.widget.Toast.makeText(this, "Getting the logs…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val zip = runCatching { buildLogZip() }.getOrNull()
            runOnUiThread {
                if (zip == null) android.widget.Toast.makeText(this, "Couldn't get the logs", android.widget.Toast.LENGTH_SHORT).show()
                else { logZip = zip; then(zip) }
            }
        }.start()
    }

    private fun buildLogZip(): java.io.File {
        val dir = java.io.File(cacheDir, "logs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
        val zip = java.io.File(dir, "OpenKeyboard_logs_$date.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            val logcat = runCatching { Runtime.getRuntime().exec("logcat -d -b all *:W").inputStream.use { it.reader().readText() } }.getOrDefault("")
            val internal = helium314.keyboard.latin.utils.Log.getLog().joinToString("\n")
            val phone = "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Open Keyboard: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n"
            z.putNextEntry(java.util.zip.ZipEntry("log.txt"))
            z.write((phone + internal + "\n\n---- system log ----\n" + logcat).toByteArray())
            z.closeEntry()
            val crashFiles = (helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(this)?.listFiles().orEmpty().toList() +
                getExternalFilesDir(null)?.listFiles().orEmpty().toList()).filter { it.isFile && it.name.startsWith("crash_report") }
            crashFiles.distinctBy { it.name }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry("crash_reports/" + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return zip
    }

    private fun shareLogs(zip: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.media", zip)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Open Keyboard logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share logs"))
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
