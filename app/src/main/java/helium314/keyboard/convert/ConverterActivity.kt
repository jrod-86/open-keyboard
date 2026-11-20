// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import helium314.keyboard.latin.Plugins
import helium314.keyboard.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open Keyboard Converter (test 233, Jarrod): turn a document into another type, for example Word into PDF.
 * Everything happens on the phone. Runs in its own process (":convert") so it never slows typing.
 * Files can be picked here, or shared to it from any app ("Share" > "Convert").
 */
class ConverterActivity : ComponentActivity() {
    private val shared = mutableStateListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeShared(intent)
        setContent { ConverterTheme { ConverterScreen(shared) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        Plugins.init(this)
        if (!Plugins.isOn("app_converter")) {
            Toast.makeText(this, "Converter is switched off in the keyboard's Settings, Plugins", Toast.LENGTH_LONG).show()
            finish(); return
        }
        shared.clear(); shared.addAll(uris)
    }
}

private val Accent = Color(0xFF3D5AFE)

@Composable
private fun ConverterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF8C9EFF), onPrimary = Color(0xFF0B1033), background = Color(0xFF111318), surface = Color(0xFF111318), surfaceVariant = Color(0xFF1E2230))
    else lightColorScheme(primary = Accent, onPrimary = Color.White, background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB), surfaceVariant = Color(0xFFE8EBF6))
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

private sealed class Stage {
    data object Pick : Stage()
    data class Chosen(val files: List<InFile>) : Stage()
    data class Working(val files: List<InFile>, val to: Fmt, val step: String) : Stage()
    data class Done(val files: List<InFile>, val outs: List<OutFile>) : Stage()
    data class Failed(val files: List<InFile>, val message: String) : Stage()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConverterScreen(shared: MutableList<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Pick) }
    var recent by remember { mutableStateOf(Recent.load(ctx)) }
    var letter by remember { mutableStateOf(ctx.prefs().getBoolean("letter", false)) }
    var joinPhotos by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }

    fun choose(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.map { Converter.describe(ctx, it) } }
            stage = Stage.Chosen(files)
        }
    }
    if (shared.isNotEmpty()) { val u = shared.toList(); shared.clear(); choose(u) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { choose(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { choose(it) }

    fun convert(files: List<InFile>, to: Fmt) {
        stage = Stage.Working(files, to, "Starting…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Converter.run(ctx, files, to, letter, joinPhotos) { s -> scope.launch { (stage as? Stage.Working)?.let { stage = it.copy(step = s) } } } }
            }
            stage = result.fold(
                onSuccess = { outs -> Recent.add(ctx, outs); recent = Recent.load(ctx); Stage.Done(files, outs) },
                onFailure = { e ->
                    val msg = when (e) {
                        is ConvertProblem -> e.message.orEmpty()
                        is OutOfMemoryError -> "That file is too big for the phone's memory. Try a smaller file, or fewer at once."
                        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}. This file may use something the converter doesn't understand yet."
                    }
                    Stage.Failed(files, msg)
                },
            )
        }
    }

    BackHandler(enabled = stage !is Stage.Pick && stage !is Stage.Working) { stage = Stage.Pick }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Converter", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                TextButton({ menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Add to Home screen") }, { menu = false; addHomeShortcut(ctx) })
                    DropdownMenuItem({ Text(if (letter) "Page size: US Letter" else "Page size: A4") }, {
                        letter = !letter; ctx.prefs().edit().putBoolean("letter", letter).apply(); menu = false
                    })
                    DropdownMenuItem({ Text("Open the Converted folder") }, { menu = false; openFolder(ctx) })
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val s = stage) {
                is Stage.Pick -> {
                    item {
                        Card {
                            Text("Change a document into another type", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Word to PDF, PDF to pictures, Excel to CSV and more. It all happens on your phone, nothing is uploaded.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 14.sp)
                            Spacer(Modifier.height(14.dp))
                            Button({ pickFiles.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("📄  Choose a file", fontSize = 16.sp) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton({ pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("🖼️  Photos to PDF") }
                            Spacer(Modifier.height(8.dp))
                            Text("Tip: in any app, tap Share on a file and pick Convert.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    item { Supported() }
                    if (recent.isNotEmpty()) {
                        item { Text("Recent", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(recent, key = { it.uri.toString() }) { r -> OutRow(r) }
                        item { TextButton({ Recent.clear(ctx); recent = emptyList() }) { Text("Clear this list (files stay in Downloads)") } }
                    }
                }
                is Stage.Chosen -> {
                    item { Files(s.files) { stage = Stage.Pick } }
                    val unknown = s.files.firstOrNull { it.fmt == null || !it.fmt.canRead }
                    val targets = Converter.targets(s.files)
                    if (unknown != null) item { Problem(Converter.unknownMessage(unknown)) }
                    else if (targets.isEmpty()) item { Problem(if (s.files.any { it.fmt == Fmt.PDF } && !PdfTextReader.canRead) "This phone can only turn PDFs into pictures (reading their words needs Android 15). Pick a PDF on its own to make pictures." else "These files can't all become the same type. Try converting them one type at a time.") }
                    else {
                        val photos = s.files.size > 1 && s.files.all { it.fmt?.kind == Kind.IMAGE }
                        item {
                            Card {
                                Text("Turn into", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Spacer(Modifier.height(10.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    targets.forEach { f -> FormatChip(f, highlight = f == targets.first()) { convert(s.files, f) } }
                                }
                                if (photos) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Put all photos in one PDF", Modifier.weight(1f), fontSize = 14.sp)
                                        Switch(joinPhotos, { joinPhotos = it })
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(hint(s.files), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
                is Stage.Working -> {
                    item { Files(s.files, null) }
                    item {
                        Card {
                            Text("Making ${s.to.label} (.${s.to.ext})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(s.step, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
                is Stage.Done -> {
                    item {
                        Card {
                            Text("✅  Done", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Saved in Downloads › $FOLDER_LABEL", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            if (s.outs.size > 1) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton({ shareAll(ctx, s.outs) }, Modifier.fillMaxWidth()) { Text("Share all ${s.outs.size}") }
                            }
                        }
                    }
                    items(s.outs, key = { it.uri.toString() }) { OutRow(it) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ stage = Stage.Chosen(s.files) }, Modifier.weight(1f)) { Text("Another type") }
                            Button({ stage = Stage.Pick }, Modifier.weight(1f)) { Text("New file") }
                        }
                    }
                }
                is Stage.Failed -> {
                    item { Files(s.files) { stage = Stage.Pick } }
                    item { Problem(s.message) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ stage = Stage.Chosen(s.files) }, Modifier.weight(1f)) { Text("Try another type") }
                            Button({ stage = Stage.Pick }, Modifier.weight(1f)) { Text("Pick another file") }
                        }
                    }
                }
            }
        }
    }
}

private const val FOLDER_LABEL = Converter.FOLDER

private fun hint(files: List<InFile>): String {
    val kinds = files.mapNotNull { it.fmt?.kind }.toSet()
    return when {
        Kind.PDF in kinds -> "PDF to pictures makes one picture per page. PDF to Word or text keeps the words only, not the layout."
        Kind.SLIDES in kinds -> "Slides keep their words, pictures and tables; each slide starts a new page."
        Kind.SHEET in kinds -> "Each sheet becomes its own table. CSV holds one sheet (the first)."
        Kind.IMAGE in kinds -> "PDF pages are A4 (change in ⋮). Photos keep their full quality."
        else -> "Headings, bold and italic, lists, tables, links and pictures are kept. Fancy layouts (columns, text boxes) are simplified."
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp)) { content() }
}

@Composable
private fun Problem(msg: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x33FF6E40)).padding(18.dp)) {
        Text("Can't convert this", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(msg, fontSize = 14.sp)
    }
}

@Composable
private fun Files(files: List<InFile>, onClear: (() -> Unit)?) {
    val ctx = LocalContext.current
    Card {
        files.take(8).forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(f.fmt?.ext ?: f.ext.ifEmpty { "?" })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text((f.fmt?.label ?: "Unknown type") + if (f.size >= 0) " · " + Formatter.formatShortFileSize(ctx, f.size) else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
        if (files.size > 8) Text("and ${files.size - 8} more", fontSize = 13.sp)
        if (onClear != null) TextButton(onClear, Modifier.align(Alignment.End)) { Text("Change") }
    }
}

@Composable
private fun Badge(ext: String) {
    val color = when (ext.lowercase()) {
        "pdf" -> Color(0xFFE53935); "docx", "doc", "rtf" -> Color(0xFF1E66D0); "xlsx", "xls", "csv", "ods" -> Color(0xFF1E8E3E)
        "pptx", "ppt", "odp" -> Color(0xFFD9541E); "odt" -> Color(0xFF2B7BB9); "jpg", "jpeg", "png", "webp", "heic", "gif", "bmp" -> Color(0xFF8E44AD)
        "epub" -> Color(0xFF7CB342); else -> Color(0xFF607D8B)
    }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(ext.uppercase().take(4), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (ext.length > 3) 11.sp else 13.sp)
    }
}

@Composable
private fun FormatChip(f: Fmt, highlight: Boolean, onClick: () -> Unit) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(f.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(".${f.ext}", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun OutRow(o: OutFile) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { open(ctx, o) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(o.name.substringAfterLast('.', "?"))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (o.size > 0) Text(Formatter.formatShortFileSize(ctx, o.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        TextButton({ open(ctx, o) }) { Text("Open") }
        TextButton({ share(ctx, listOf(o)) }) { Text("Share") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Supported() {
    var open by remember { mutableStateOf(false) }
    Card {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("What it can convert", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼")
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            val lines = listOf(
                "📝 Documents" to "Word (.docx), OpenDocument (.odt), Rich text (.rtf), plain text, Markdown, web pages, eBooks (.epub) → PDF, Word, OpenDocument, RTF, text, Markdown, web page, eBook, JPG/PNG pages",
                "📊 Spreadsheets" to "Excel (.xlsx), OpenDocument (.ods), CSV → PDF, Excel, OpenDocument, CSV, web page, Word, text",
                "📽️ Slides" to "PowerPoint (.pptx), OpenDocument (.odp) → PDF, Word, OpenDocument, text, web page, JPG/PNG pages",
                "📕 PDF" to "→ JPG or PNG (one per page)" + if (PdfTextReader.canRead) "; → Word, text and more (words only)" else "",
                "🖼️ Pictures" to "JPG, PNG, WebP, HEIC, GIF, BMP → PDF (several photos in one), JPG, PNG, WebP, Word",
                "⛔ Not yet" to "Old Office files (.doc, .xls, .ppt from before 2007) and Apple Pages/Numbers/Keynote",
            )
            lines.forEach { (t, d) ->
                Text(t, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Text(d, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

// ---------------------------------------------------------------- actions

private fun Context.prefs() = getSharedPreferences("converter", Context.MODE_PRIVATE)

private fun open(ctx: Context, o: OutFile) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(o.uri, o.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
        .onFailure { Toast.makeText(ctx, "No app on the phone opens .${o.name.substringAfterLast('.')} files", Toast.LENGTH_LONG).show() }
}

private fun share(ctx: Context, outs: List<OutFile>) {
    val i = if (outs.size == 1) Intent(Intent.ACTION_SEND).setType(outs[0].mime).putExtra(Intent.EXTRA_STREAM, outs[0].uri)
    else Intent(Intent.ACTION_SEND_MULTIPLE).setType(outs.map { it.mime }.distinct().singleOrNull() ?: "*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outs.map { it.uri }))
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareAll(ctx: Context, outs: List<OutFile>) = share(ctx, outs)

private fun openFolder(ctx: Context) {
    // the Files app's Downloads view; the "Converted" folder is inside it
    val i = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "Open your Files app, then Downloads, then $FOLDER_LABEL", Toast.LENGTH_LONG).show() }
}

private fun addHomeShortcut(ctx: Context) {
    val info = ShortcutInfoCompat.Builder(ctx, "tile_converter")
        .setShortLabel("Converter")
        .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_converter_shortcut))
        .setIntent(Intent(ctx, ConverterActivity::class.java).setAction(Intent.ACTION_MAIN))
        .build()
    val ok = runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    if (!ok) Toast.makeText(ctx, "Your home screen doesn't take shortcuts", Toast.LENGTH_LONG).show()
}

/** The last 30 converted files, so they're easy to find again. */
private object Recent {
    fun load(ctx: Context): List<OutFile> = runCatching {
        val a = JSONArray(ctx.prefs().getString("recent", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { OutFile(it.getString("n"), Uri.parse(it.getString("u")), it.getString("m"), it.optLong("s")) }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, outs: List<OutFile>) {
        val all = (outs.reversed() + load(ctx)).distinctBy { it.uri }.take(30)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("n", it.name).put("u", it.uri.toString()).put("m", it.mime).put("s", it.size)) }
        ctx.prefs().edit().putString("recent", a.toString()).apply()
    }

    fun clear(ctx: Context) = ctx.prefs().edit().remove("recent").apply()
}
