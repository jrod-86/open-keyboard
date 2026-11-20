// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The song waiting for "Add to Playlist" (the picker shows while this is set). */
object PlaylistPicker { val songs = mutableStateOf<List<Long>?>(null) }

@Composable
private fun NameDialog(title: String, start: String, ok: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onDone(name) }) { Text(ok, color = Accent) } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } })
}

/** "Add to Playlist": pick one, or make a new one. */
@Composable
internal fun PlaylistPickerDialog() {
    val ctx = LocalContext.current
    val songs = PlaylistPicker.songs.value ?: return
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New Playlist", "", "Create") { n -> naming = false; if (n != null) { Playlists.create(ctx, n, songs); PlaylistPicker.songs.value = null } }
        return
    }
    AlertDialog(onDismissRequest = { PlaylistPicker.songs.value = null }, title = { Text("Add to Playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Text("＋ New Playlist…", Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 12.dp), color = Accent, fontSize = 16.sp)
                }
                items(Playlists.all.value, key = { it.id }) { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable { Playlists.add(ctx, p.id, songs); PlaylistPicker.songs.value = null }.padding(vertical = 12.dp), fontSize = 16.sp)
                }
            }
        },
        confirmButton = { TextButton({ PlaylistPicker.songs.value = null }) { Text("Cancel") } })
}

@Composable
internal fun PlaylistsPage(lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Playlists", onBack) }
        item { ActionRow("＋", "New Playlist") { naming = true } }
        item { ActionRow("⇩", "Import Playlists") { open(Page.Import) } }
        items(Playlists.all.value, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { open(Page.PlaylistPage(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p.songs.firstNotNullOfOrNull { lib.byId[it] }, 160, Modifier.size(56.dp), corner = 6)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.songs.size} songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
    }
    if (naming) NameDialog("New Playlist", "", "Create") { n -> naming = false; if (n != null) Playlists.create(ctx, n) }
}

@Composable
private fun ActionRow(icon: String, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text(icon, color = Accent, fontSize = 26.sp)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 17.sp, color = Accent)
    }
}

@Composable
internal fun PlaylistPage(id: Long, lib: Library, ui: PlayerUi, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = Playlists.all.value.find { it.id == id } ?: run { onBack(); return }
    val songs = p.songs.mapNotNull { lib.byId[it] }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(p.name, onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${songs.size} songs, ${songs.sumOf { it.durationMs } / 60000} minutes", Modifier.weight(1f), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Box {
                    IconButton({ menu = true }) { Icon(MusicIcons.more, "More", tint = Accent) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                        DropdownMenuItem({ Text("Delete Playlist") }, { menu = false; deleting = true })
                    }
                }
            }
        }
        if (songs.isEmpty()) item { Text("No songs yet. Use ⋯ on any song, then Add to Playlist.", Modifier.padding(16.dp)) }
        else item { PlayShuffle(songs, ui) }
        itemsIndexed(songs, key = { i, s -> "$i/${s.id}" }) { i, s ->
            SongRow(s, ui, lib, open, extra = listOf(
                "Remove from Playlist" to { val at = indexInList(p.songs, songs, i); if (at >= 0) Playlists.removeAt(ctx, id, at) },
                "Move Up" to { val at = indexInList(p.songs, songs, i); if (at > 0) Playlists.move(ctx, id, at, at - 1) },
                "Move Down" to { val at = indexInList(p.songs, songs, i); if (at >= 0 && at < p.songs.size - 1) Playlists.move(ctx, id, at, at + 1) },
            )) { ui.play(songs, i) }
        }
    }
    if (renaming) NameDialog("Rename Playlist", p.name, "Save") { n -> renaming = false; if (n != null) Playlists.rename(ctx, id, n) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete “${p.name}”?") },
        text = { Text("The songs stay in your library.") },
        confirmButton = { TextButton({ deleting = false; Playlists.delete(ctx, id); onBack() }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

/** Position in the saved playlist of the [i]th song shown (songs no longer on the phone are hidden). */
private fun indexInList(saved: List<Long>, shown: List<Song>, i: Int): Int {
    val target = shown[i].id
    val nth = shown.subList(0, i).count { it.id == target }
    return saved.withIndex().filter { it.value == target }.map { it.index }.getOrElse(nth) { -1 }
}

@Composable
internal fun ImportPage(lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<ImportResult>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; done = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                PlaylistImport.resolve(lib, uris.flatMap { runCatching { PlaylistImport.readFile(ctx, it) }.getOrDefault(emptyList()) })
            }
            results = (results.orEmpty() + r)
            if (r.isEmpty()) done = "Couldn't read a playlist from that file."
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Import Playlists", onBack) }
        item {
            Text("Bring your playlists from Samsung Music, Apple Music, Spotify or another player. Songs are matched to the music on this phone.",
                Modifier.padding(horizontal = 16.dp), fontSize = 14.sp, color = dim)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ pick.launch(arrayOf("*/*")) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Choose files") }
                OutlinedButton({
                    busy = true; done = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            PlaylistImport.fromPhone(ctx).map { (l, ids) -> ImportResult(l, ids.filter { it in lib.byId }, emptyList()) }.filter { it.found.isNotEmpty() }
                        }
                        results = results.orEmpty() + r
                        if (r.isEmpty()) done = "No saved playlists found on this phone. Export them from your old player as a file and use Choose files."
                        busy = false
                    }
                }, Modifier.weight(1f)) { Text("Find on phone", color = Accent) }
            }
        }
        item {
            Text(if (help) "Hide how to export ▲" else "How do I get my playlists out of my old player? ▼", Modifier.clickable { help = !help }.padding(horizontal = 16.dp, vertical = 6.dp),
                color = Accent, fontSize = 14.sp)
            if (help) Column(Modifier.padding(horizontal = 16.dp)) {
                listOf(
                    "Samsung Music" to "Open Playlists, tap ⋮ then Export (or Share) and save the playlist files. Then tap Find on phone, or Choose files.",
                    "Apple Music / iTunes on a computer" to "Select the playlist, then File > Library > Export Playlist (text, XML or M3U). For everything at once: File > Library > Export Library. Copy the file to your phone.",
                    "Spotify, YouTube Music, Apple Music on iPhone" to "Use a free site like Exportify or TuneMyMusic to save the playlist as a CSV file, then copy it to your phone.",
                    "Poweramp, Musicolet, VLC, Windows Media Player and others" to "Export or save the playlist (M3U, PLS, XSPF or WPL) and choose it here.",
                ).forEach { (who, how) ->
                    Text(who, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(how, fontSize = 13.sp, color = dim)
                }
            }
        }
        if (busy) item { Text("Reading…", Modifier.padding(16.dp)) }
        if (done.isNotEmpty()) item { Text(done, Modifier.padding(16.dp), color = Accent) }
        val list = results.orEmpty()
        if (list.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Found ${list.size} playlists", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Button({
                        val n = PlaylistImport.save(ctx, list)
                        results = null
                        done = "Imported $n playlists. 🎉"
                        if (n > 0) open(Page.Playlists)
                    }, enabled = list.any { it.selected.value && it.found.isNotEmpty() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Import ticked")
                    }
                }
            }
            items(list) { r -> ImportRow(r) }
        }
    }
}

@Composable
private fun ImportRow(r: ImportResult) {
    var more by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { r.selected.value = !r.selected.value }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(r.selected.value, { r.selected.value = it }, enabled = r.found.isNotEmpty(), colors = CheckboxDefaults.colors(checkedColor = Accent))
            Column(Modifier.weight(1f)) {
                Text(r.list.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val total = r.found.size + r.missing.size
                Text("${r.found.size} of $total songs found · ${r.list.from}", fontSize = 13.sp, color = if (r.missing.isEmpty()) dim else Accent)
            }
            if (r.missing.isNotEmpty()) TextButton({ more = !more }) { Text(if (more) "Hide" else "Missing", color = Accent) }
        }
        if (more) Column(Modifier.padding(start = 48.dp, bottom = 6.dp)) {
            r.missing.take(100).forEach { Text("• " + it.label(), fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (r.missing.size > 100) Text("…and ${r.missing.size - 100} more", fontSize = 13.sp, color = dim)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}
