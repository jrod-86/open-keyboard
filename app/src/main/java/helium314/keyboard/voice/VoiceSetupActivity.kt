// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Lets the user grant the microphone, download speech models and pick the accuracy model. */
class VoiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Theme { VoiceSetupScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var micGranted by remember { mutableStateOf(hasMic(ctx)) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    // per model: null = idle, 0..1 = downloading
    val progress = remember { mutableStateMapOf<VoiceModel, Float>() }
    val errors = remember { mutableStateMapOf<VoiceModel, String>() }
    var installedTick by remember { mutableStateOf(0) }
    var accuracy by remember { mutableStateOf(VoicePrefs.accuracyModel(ctx)) }
    var noiseReduction by remember { mutableStateOf(VoicePrefs.noiseReduction(ctx)) }
    var region by remember { mutableStateOf(VoicePrefs.region(ctx)) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("Voice typing setup") }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Voice typing runs entirely on your phone. Models are downloaded once from Hugging Face; " +
                        "after that nothing you say leaves the device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Microphone", style = MaterialTheme.typography.titleMedium)
                    if (micGranted) Text("Allowed")
                    else Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                }
            }

            Text("2. Download models", style = MaterialTheme.typography.titleMedium)
            VoiceModel.entries.forEach { model ->
                installedTick.let { } // recompose after install/delete
                val installed = model.isInstalled(ctx)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.title, style = MaterialTheme.typography.titleSmall)
                        Text(model.description, style = MaterialTheme.typography.bodySmall)
                        Text("About ${model.approxMb} MB · ${model.license}", style = MaterialTheme.typography.bodySmall)
                        val p = progress[model]
                        when {
                            p != null -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Installed")
                                Spacer(Modifier.width(16.dp))
                                OutlinedButton(onClick = {
                                    VoiceEngine.release()
                                    model.dir(ctx).deleteRecursively()
                                    installedTick++
                                }) { Text("Delete") }
                            }
                            else -> Button(onClick = {
                                errors.remove(model)
                                progress[model] = 0f
                                scope.launch {
                                    try {
                                        download(ctx, model) { progress[model] = it }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        errors[model] = "Download failed: ${e.message}"
                                    } finally {
                                        progress.remove(model)
                                        installedTick++
                                    }
                                }
                            }) { Text("Download") }
                        }
                        errors[model]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            Text("3. Accuracy check", style = MaterialTheme.typography.titleMedium)
            Text(
                "When you pause, this model re-checks the sentence and adds punctuation and capitals. " +
                        "Try both and keep the one that works best for your voice.",
                style = MaterialTheme.typography.bodySmall,
            )
            (VoiceModel.accuracyModels + listOf<VoiceModel?>(null)).forEach { model ->
                val label = model?.title ?: "None (fastest, no punctuation)"
                Row(
                    Modifier.fillMaxWidth().selectable(selected = accuracy == model, onClick = {
                        accuracy = model
                        VoicePrefs.setAccuracyModel(ctx, model)
                    }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = accuracy == model, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label + if (model != null && !model.isInstalled(ctx)) " (not downloaded)" else "")
                }
            }

            Text("4. Background noise", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().selectable(selected = noiseReduction, onClick = {
                    noiseReduction = !noiseReduction
                    VoicePrefs.setNoiseReduction(ctx, noiseReduction)
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce background noise")
                    Text(
                        "Uses your phone's noise suppression on the mic and cleans each sentence before the " +
                                "accuracy check. Turn off if it makes quiet speech worse.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = noiseReduction, onCheckedChange = null)
            }

            var callMic by remember { mutableStateOf(VoicePrefs.callMic(ctx)) }
            Row(
                Modifier.fillMaxWidth().selectable(selected = callMic, onClick = {
                    callMic = !callMic
                    VoicePrefs.setCallMic(ctx, callMic)
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Loud places mode")
                    Text(
                        "Records like a phone call, so your phone uses all its mics to cancel noise. Try it in " +
                                "the car, a truck or on the street. Turn off if quiet speech gets worse.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = callMic, onCheckedChange = null)
            }
            var keep by remember { mutableStateOf(VoicePrefs.keepRecording(ctx)) }
            Row(
                Modifier.fillMaxWidth().selectable(selected = keep, onClick = {
                    keep = !keep
                    VoicePrefs.setKeepRecording(ctx, keep)
                    if (!keep) VoicePrefs.lastRecording(ctx).delete()
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep my last recording")
                    Text(
                        "Saves only your most recent voice typing on the phone, so you can share it to help " +
                                "tune accuracy in noisy places. Nothing is sent anywhere unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = keep, onCheckedChange = null)
            }
            if (keep) OutlinedButton(onClick = {
                val f = VoicePrefs.lastRecording(ctx)
                if (!f.isFile) android.widget.Toast.makeText(ctx, "No recording yet: use voice typing first", android.widget.Toast.LENGTH_SHORT).show()
                else {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".media", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("audio/wav")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share recording"))
                }
            }) { Text("Share last recording") }

            Text("5. Where you're from", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sets the spelling (colour or color, realise or realize). For strong accents, try Whisper " +
                        "large-v3-turbo as the accuracy check: it was trained on the widest range of accents.",
                style = MaterialTheme.typography.bodySmall,
            )
            EnglishRegion.entries.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = region == r, onClick = {
                        region = r
                        VoicePrefs.setRegion(ctx, r)
                    }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = region == r, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(r.title)
                }
            }

            Text("6. Use it", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap the mic at the top right of the keyboard and start talking. Tap it again, " +
                        "or stay quiet for 30 seconds, to finish.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
    }
}

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Downloads every file of [model] into its folder, reporting overall progress from 0 to 1. */
private suspend fun download(ctx: Context, model: VoiceModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val dir = model.dir(ctx).apply { mkdirs() }
    val totalBytes = model.approxMb * 1_000_000L
    var doneBytes = 0L
    var lastReported = 0f
    for (name in model.files) {
        val target = File(dir, name)
        if (target.isFile && target.length() > 0) { doneBytes += target.length(); continue }
        val part = File(dir, "$name.part")
        var url = URL(model.url(name))
        var conn: HttpURLConnection
        var redirects = 0
        while (true) { // follow redirects to the Hugging Face CDN
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code for $name")
            break
        }
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    doneBytes += n
                    val p = (doneBytes.toFloat() / totalBytes).coerceAtMost(0.99f)
                    if (p - lastReported >= 0.01f) {
                        lastReported = p
                        withContext(Dispatchers.Main) { onProgress(p) }
                    }
                }
            }
        }
        conn.disconnect()
        if (!part.renameTo(target)) throw IllegalStateException("could not save $name")
    }
    VoiceEngine.release() // load the new model next time
}
