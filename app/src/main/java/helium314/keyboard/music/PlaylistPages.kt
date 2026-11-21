// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.net.Uri
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.LaunchedEffect
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

/** One song in a playlist, with a key that stays the same while you drag it around (the same song can be in twice). */
private data class PlEntry(val key: String, val song: Song)

@Composable
internal fun PlaylistPage(id: Long, lib: Library, ui: PlayerUi, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val p = Playlists.all.value.find { it.id == id } ?: run { onBack(); return }
    fun entries(ids: List<Long>): List<PlEntry> {
        val seen = HashMap<Long, Int>()
        return ids.mapNotNull { sid -> lib.byId[sid]?.let { song -> val n = seen.merge(sid, 1, Int::plus)!!; PlEntry("$sid/$n", song) } }
    }
    val order = remember(id) { androidx.compose.runtime.mutableStateListOf<PlEntry>().apply { addAll(entries(p.songs)) } }
    LaunchedEffect(p.songs) { val now = entries(p.songs); if (order.toList() != now) { order.clear(); order.addAll(now) } }
    val songs = order.map { it.song }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val menuFor = remember { mutableStateOf<String?>(null) }

    /*
     * Test 292 (Jarrod: the drag still stopped, three times): dragging is now handled by the page around the list, not by the
     * song's own row. The row being dragged can scroll off screen without ending the drag, and the list itself never moves rows
     * around until you let go, so nothing cancels the scrolling. While you drag, a copy of the song follows your finger and a
     * coloured line shows where it will land; near the top or bottom the list scrolls, faster the closer and the longer.
     */
    var dragKey by remember { mutableStateOf<String?>(null) }
    var fingerY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var grabY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragH by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var target by remember { androidx.compose.runtime.mutableIntStateOf(-1) } // insert before this index of order (order.size = at the end)
    var listH by remember { androidx.compose.runtime.mutableIntStateOf(1) }

    fun save(list: List<PlEntry> = order) {
        val gone = p.songs.filter { it !in lib.byId }
        Playlists.setSongs(ctx, id, list.map { it.song.id } + gone)
    }
    fun moveTo(key: String, top: Boolean) {
        val i = order.indexOfFirst { it.key == key }; if (i < 0) return
        val e = order.removeAt(i); if (top) order.add(0, e) else order.add(e)
        save()
    }
    /** where the song would land if you let go now */
    fun findTarget() {
        val songsShown = state.layoutInfo.visibleItemsInfo.filter { it.key is String }
        if (songsShown.isEmpty()) return
        val y = fingerY
        val hit = songsShown.firstOrNull { y < it.offset + it.size / 2f }
        target = if (hit != null) order.indexOfFirst { it.key == hit.key } else order.indexOfFirst { it.key == songsShown.last().key } + 1
    }
    fun drop() {
        val k = dragKey ?: return
        val from = order.indexOfFirst { it.key == k }
        var to = target
        if (from >= 0 && to >= 0) {
            if (to > from) to -= 1
            if (to != from) { order.add(to, order.removeAt(from)); save() }
        }
        dragKey = null; target = -1
    }
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        var heldMs = 0f; var last = 0L
        while (dragKey != null) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = if (last == 0L) 16f else ((now - last) / 1e6f).coerceIn(1f, 50f)
            last = now
            val zone = maxOf(150f, listH * 0.15f)
            val depth = when {
                fingerY < zone -> -((zone - fingerY) / zone).coerceAtMost(1.5f)
                fingerY > listH - zone -> ((fingerY - (listH - zone)) / zone).coerceAtMost(1.5f)
                else -> 0f
            }
            if (depth == 0f) { heldMs = 0f; findTarget(); continue }
            heldMs += dt
            val speed = (0.3f + 1.8f * depth * depth) * (1f + (heldMs / 700f).coerceAtMost(3f))
            try { state.scrollBy((if (depth < 0) -speed else speed) * dt) } catch (e: kotlinx.coroutines.CancellationException) { }
            findTarget()
        }
    }

    Box(Modifier.fillMaxSize()
        .onSizeChanged { listH = it.height }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // a long press on a song (without moving) picks it up; anything else is a normal tap or scroll
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (early != null) return@awaitEachGesture
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && down.position.y >= it.offset && down.position.y < it.offset + it.size }
                    ?: return@awaitEachGesture
                val key = item.key as String
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                fingerY = down.position.y; grabY = down.position.y - item.offset; dragH = item.size
                dragKey = key; findTarget()
                var moved = false
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    c.consume() // the list and the rows don't see it: no scrolling, no taps while you drag
                    if (!c.pressed) break
                    if (kotlin.math.abs(c.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                    fingerY = c.position.y
                    findTarget()
                }
                if (moved) drop() else { dragKey = null; target = -1; menuFor.value = key } // held without moving: the song's menu
            }
        }) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
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
            else item {
                PlayShuffle(songs, ui)
                Text("Hold a song and drag it to move it, or use ⋯ for Move to top / bottom.", Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            itemsIndexed(order, key = { _, e -> e.key }) { i, e ->
                val menuOpen = remember(e.key) { mutableStateOf(false) }
                LaunchedEffect(menuFor.value) { if (menuFor.value == e.key) { menuOpen.value = true; menuFor.value = null } }
                val lineTop = dragKey != null && target == i
                val lineBottom = dragKey != null && target == order.size && i == order.size - 1
                Box(Modifier.graphicsLayer { alpha = if (dragKey == e.key) 0.3f else 1f }
                    .drawWithContent {
                        drawContent()
                        val h = 3.dp.toPx()
                        if (lineTop) drawRect(Accent, size = androidx.compose.ui.geometry.Size(size.width, h))
                        if (lineBottom) drawRect(Accent, androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
                    }) {
                    SongRow(e.song, ui, lib, open, menuState = menuOpen, holdForMenu = false, extra = listOf(
                        "Move to top" to { moveTo(e.key, true) },
                        "Move to bottom" to { moveTo(e.key, false) },
                        "Move Up" to { val at = order.indexOfFirst { it.key == e.key }; if (at > 0) { order.add(at - 1, order.removeAt(at)); save() } },
                        "Move Down" to { val at = order.indexOfFirst { it.key == e.key }; if (at in 0 until order.size - 1) { order.add(at + 1, order.removeAt(at)); save() } },
                        "Remove from Playlist" to { val at = order.indexOfFirst { it.key == e.key }; if (at >= 0) { order.removeAt(at); save() } },
                    )) { ui.play(songs, i) }
                }
            }
        }
        // the song you're dragging, following your finger
        val dragged = dragKey?.let { k -> order.firstOrNull { it.key == k } }
        if (dragged != null) {
            Row(Modifier.fillMaxWidth()
                .graphicsLayer { translationY = fingerY - grabY; scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 20f }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(dragged.song, 160, Modifier.size(48.dp), corner = 6)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(dragged.song.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${dragged.song.artist} · ${dragged.song.album}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                Text("≡", fontSize = 22.sp, color = Accent)
            }
        }
    }
    if (renaming) NameDialog("Rename Playlist", p.name, "Save") { n -> renaming = false; if (n != null) Playlists.rename(ctx, id, n) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete “${p.name}”?") },
        text = { Text("The songs stay in your library.") },
        confirmButton = { TextButton({ deleting = false; Playlists.delete(ctx, id); onBack() }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
    @Suppress("UNUSED_EXPRESSION") dragH
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
