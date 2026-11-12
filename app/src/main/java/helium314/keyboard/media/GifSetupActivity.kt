// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Theme

/** Turn KLIPY stickers and GIFs on, and manage the user's own sticker packs. */
class GifSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Theme { GifSetupScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GifSetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(MediaPrefs.gifsEnabled(ctx)) }
    var key by remember { mutableStateOf(MediaPrefs.klipyKey(ctx)) }
    var packs by remember { mutableStateOf(StickerPacks.packs(ctx)) }
    var packName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun finish(added: Int) {
        busy = false
        packs = StickerPacks.packs(ctx)
        status = if (added > 0) "Added a pack with $added stickers. Find it in the sticker panel." else "No pictures could be added."
        packName = ""
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; status = "Adding…"
        val name = packName.ifBlank { "My pack" }
        scope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { StickerPacks.createFromImages(ctx, name, uris) }.getOrDefault(0) }
            finish(added)
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "Importing…"
        val fileName = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            }
        }.getOrNull()?.substringBeforeLast('.')
        val name = packName.ifBlank { fileName ?: "Imported pack" }
        scope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { StickerPacks.importZip(ctx, name, uri) }.getOrDefault(0) }
            finish(added)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("Stickers and GIFs") }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Stickers and GIFs", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Stickers and GIFs come from KLIPY, a free online service. When this is on, the categories you tap " +
                                        "are sent to KLIPY with a random ID. Nothing else you type is sent.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it; MediaPrefs.setGifsEnabled(ctx, it) })
                    }
                    Text("1. Get a free KLIPY API key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sign up at the KLIPY partner panel, open API Keys and create a key. A new key works straight away " +
                                "(up to 100 searches an hour until KLIPY approves it for more).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://partner.klipy.com/")))
                    }) { Text("Open KLIPY partner panel") }
                    Text("2. Paste the key here", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; MediaPrefs.setKlipyKey(ctx, it) },
                        label = { Text("KLIPY API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("My sticker packs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Make a pack of your favourite character from pictures on your phone, or import a pack " +
                                "someone shared as a .zip of pictures. Packs stay on your phone and work offline.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = packName,
                        onValueChange = { packName = it },
                        label = { Text("Pack name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { pickImages.launch("image/*") }) { Text("From photos") }
                        OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, StickerStoreActivity::class.java)) }) { Text("Store") }
                        OutlinedButton(enabled = !busy, onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) { Text("Import .zip") }
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                    packs.forEach { pack ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${pack.name} (${StickerPacks.stickers(pack).size})", Modifier.weight(1f))
                            TextButton(onClick = { StickerPacks.delete(pack); packs = StickerPacks.packs(ctx) }) { Text("Delete") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
    }
}
