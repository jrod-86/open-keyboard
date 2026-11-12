// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A pack offered in the sticker store. Every pack in the catalog is under a licence that allows sharing. */
data class StorePack(
    val id: String, val name: String, val author: String, val license: String, val credit: String,
    val count: Int, val featured: Boolean, val iconUrl: String, val zipUrl: String, val sizeKb: Int,
)

/** The catalog lives on the repo's `stickers` branch, so packs can be added without a new app version. */
object StickerStore {
    private const val BASE = "https://raw.githubusercontent.com/jrod-86/open-keyboard/stickers/"

    fun catalog(): List<StorePack> {
        val json = JSONObject(String(Klipy.download(BASE + "catalog.json")))
        val packs = json.getJSONArray("packs")
        return (0 until packs.length()).map { i ->
            val p = packs.getJSONObject(i)
            StorePack(
                p.getString("id"), p.getString("name"), p.optString("author"), p.optString("license"),
                p.optString("credit"), p.optInt("count"), p.optBoolean("featured"),
                BASE + p.getString("icon"), BASE + p.getString("zip"), p.optInt("size") / 1024,
            )
        }
    }

    fun install(ctx: Context, pack: StorePack): Int {
        val bytes = Klipy.download(pack.zipUrl)
        return StickerPacks.importZip(ctx, pack.name, bytes.inputStream(), pack.id)
    }
}

class StickerStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Theme { StoreScreen(onBack = { finish() }) } }
    }
}

private val iconCache = mutableMapOf<String, Bitmap>()

@Composable
private fun RemoteIcon(url: String) {
    val bmp by produceState(iconCache[url], url) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { Klipy.download(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        }?.also { iconCache[url] = it }
    }
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp)) }
    }
}

@Composable
private fun LocalIcon(file: File?) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
        }
    }
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf<List<StorePack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(StickerPacks.installedStoreIds(ctx)) }
    var local by remember { mutableStateOf(StickerPacks.packs(ctx)) }
    var search by remember { mutableStateOf("") }
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    fun refreshLocal() { installed = StickerPacks.installedStoreIds(ctx); local = StickerPacks.packs(ctx) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { StickerStore.catalog() } }
        result.onSuccess { catalog = it }.onFailure {
            Log.e("StickerStore", "catalog failed", it)
            error = "Couldn't load the sticker store. Check your internet connection."
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("Sticker store") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Featured", "All", "Your stickers").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            if (tab == 2) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                ctx.startActivity(Intent(ctx, GifSetupActivity::class.java))
                            }) { Text("Make your own pack") }
                        }
                    }
                    if (local.isEmpty()) item {
                        Text("No sticker packs yet. Download one from Featured or All, or make your own.", Modifier.padding(16.dp))
                    }
                    items(local, key = { it.absolutePath }) { pack ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LocalIcon(StickerPacks.stickers(pack).firstOrNull())
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                                Text("${StickerPacks.stickers(pack).size} stickers", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { StickerPacks.delete(pack); refreshLocal() }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
                return@Column
            }
            val list = catalog
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp))
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val shown = if (tab == 0) list.filter { it.featured }
                        else list.filter { search.isBlank() || it.name.contains(search, true) || it.author.contains(search, true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (tab == 1) item {
                            OutlinedTextField(
                                value = search, onValueChange = { search = it }, singleLine = true,
                                label = { Text("Search for sticker packs") },
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                        items(shown, key = { it.id }) { pack ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RemoteIcon(pack.iconUrl)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                                    Text(pack.author, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${pack.count} stickers · ${"%.1f".format(pack.sizeKb / 1024.0)} MB · ${pack.license}" +
                                                if (pack.credit.isNotEmpty()) "\n${pack.credit}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                when {
                                    pack.id in installed -> Text("✓", style = MaterialTheme.typography.titleLarge)
                                    busy[pack.id] == true -> CircularProgressIndicator(Modifier.size(28.dp))
                                    else -> OutlinedButton(onClick = {
                                        busy[pack.id] = true
                                        scope.launch {
                                            val added = withContext(Dispatchers.IO) { runCatching { StickerStore.install(ctx, pack) }.getOrDefault(0) }
                                            busy[pack.id] = false
                                            if (added == 0) error = "Couldn't download ${pack.name}. Check your internet connection."
                                            refreshLocal()
                                        }
                                    }) { Text("Get") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
