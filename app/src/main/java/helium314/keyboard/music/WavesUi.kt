// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Open Waves (test 238, Jarrod): the library laid out like Samsung Music, with swipeable tabs. */
val WavesTabs = listOf("Favourites", "Playlists", "Tracks", "Albums", "Artists", "Folders")

private val dimText @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

/** "♫ Open Waves" — Jarrod's logo first, then the name. */
@Composable
internal fun WavesTopBar(open: (Page) -> Unit, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(helium314.keyboard.latin.R.drawable.open_waves_mark), "Open Waves", Modifier.height(34.dp).aspectRatio(384f / 232f))
        Spacer(Modifier.width(6.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Open ") }
            withStyle(SpanStyle(brush = Brush.linearGradient(listOf(Color(0xFF1FE3FF), Color(0xFF8E5BFF))))) { append("Waves") }
        }, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        extra?.invoke()
        IconButton({ open(Page.Search) }) { Icon(MusicIcons.search, "Search", Modifier.size(26.dp)) }
        Box {
            IconButton({ menu = true }) { Icon(MusicIcons.more, "More", Modifier.size(26.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Edit") }, { menu = false; open(Page.Edit) })
                DropdownMenuItem({ Text("Add shortcut on Home screen") }, { menu = false; addHomeShortcut(ctx) })
                DropdownMenuItem({ Text("Themes") }, { menu = false; open(Page.Themes) })
                DropdownMenuItem({ Text("Sound quality and effects") }, { menu = false; open(Page.Settings) })
                DropdownMenuItem({ Text("Fix song info") }, { menu = false; open(Page.Fixer) })
                DropdownMenuItem({ Text("Import playlists") }, { menu = false; open(Page.Import) })
                DropdownMenuItem({ Text("Settings") }, { menu = false; open(Page.Settings) })
            }
        }
    }
}

/** The tab names: the one you're on big and bold in the middle, the others small beside it. Swipe or tap. */
@Composable
internal fun WavesTabHeader(pager: PagerState) {
    val scope = rememberCoroutineScope()
    val row = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { row.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    LazyRow(state = row, modifier = Modifier.fillMaxWidth().height(58.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(WavesTabs) { i, name ->
            val on = i == pager.currentPage
            Text(name, fontSize = if (on) 30.sp else 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onSurface else dimText,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.animateScrollToPage(i) } })
        }
    }
}

@Composable
internal fun WavesHome(lib: Library, ui: PlayerUi, pager: PagerState, open: (Page) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        WavesTopBar(open)
        WavesTabHeader(pager)
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> FavouritesTab(lib, ui, open)
                1 -> PlaylistsTab(lib, open)
                2 -> TracksTab(lib, ui, open)
                3 -> AlbumsTab(lib, open)
                4 -> ArtistsTab(lib, open)
                else -> FoldersTab(lib, open)
            }
        }
    }
}

// ---- sorting ----

object Sorts {
    val tracks = listOf("Name", "Artist", "Album", "Date added")
    val albums = listOf("Name", "Artist", "Release", "Date added")
    val artists = listOf("Name", "Most tracks")
    val trackSort = mutableIntStateOf(0); val albumSort = mutableIntStateOf(0); val artistSort = mutableIntStateOf(0)
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
        trackSort.intValue = p.getInt("sort_tracks", 0); albumSort.intValue = p.getInt("sort_albums", 0); artistSort.intValue = p.getInt("sort_artists", 0)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putInt("sort_tracks", trackSort.intValue)
            .putInt("sort_albums", albumSort.intValue).putInt("sort_artists", artistSort.intValue).apply()
    }
}

@Composable
private fun SortBar(options: List<String>, chosen: Int, onChoose: (Int) -> Unit, actions: (@Composable () -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MusicIcons.sort, "Sort", Modifier.size(20.dp), tint = dimText)
                Spacer(Modifier.width(8.dp))
                Text(options[chosen], fontSize = 14.sp, color = dimText)
            }
            DropdownMenu(menu, { menu = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o, fontWeight = if (i == chosen) FontWeight.Bold else FontWeight.Normal) }, { menu = false; onChoose(i) }) }
            }
        }
        Spacer(Modifier.weight(1f))
        actions?.invoke()
    }
}

private fun letterOf(s: String): Char {
    val c = s.trimStart('"', '\'', '(', '[', '#', '-', '.', '_', ' ').firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

/** A–Z down the right side: tap or slide to jump. */
@Composable
private fun AzBar(modifier: Modifier, keys: List<String>, jump: (Int) -> Unit) {
    val letters = remember { listOf('#') + ('A'..'Z').toList() }
    val firstIndex = remember(keys) {
        val m = HashMap<Char, Int>()
        keys.forEachIndexed { i, k -> m.putIfAbsent(letterOf(k), i) }
        m
    }
    fun go(y: Float, h: Int) {
        val li = ((y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
        // the letter, or the next one that has songs
        for (j in li until letters.size) firstIndex[letters[j]]?.let { jump(it); return }
    }
    BoxWithConstraints(modifier.width(22.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val h = constraints.maxHeight
        Column(Modifier.fillMaxSize()
            .pointerInput(keys) { detectTapGestures { o -> go(o.y, h) } }
            .pointerInput(keys) { detectVerticalDragGestures { change, _ -> go(change.position.y, h) } },
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            letters.forEach { Text(it.toString(), fontSize = 10.sp, color = dimText) }
        }
    }
}

// ---- tabs ----

@Composable
private fun FavouritesTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val favs = lib.songs.filter { it.id in Favourites.ids.value }
    @Suppress("UNUSED_VARIABLE") val v = PlayStats.version.value
    val most = remember(lib, PlayStats.version.value) { PlayStats.mostPlayed(lib, 20) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(Modifier.width(190.dp).clip(RoundedCornerShape(18.dp)).clickable { open(Page.Favs) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(190.dp).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Cover(favs.firstOrNull(), 400, Modifier.fillMaxSize(), corner = 18)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    Text("Favourite tracks", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("${favs.size} tracks", Modifier.padding(top = 8.dp), fontSize = 15.sp)
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)) }
        item { Text(if (most.isEmpty()) "Most played (play some songs and they'll show here)" else "Most played", fontSize = 17.sp, color = dimText) }
        item {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(most) { i, s ->
                    Column(Modifier.width(150.dp).clickable { ui.play(most, i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(s, 300, Modifier.size(150.dp), corner = 18)
                        Text(s.title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartTile(label: String, count: Int, cover: Song?, onClick: () -> Unit) {
    Column(Modifier.width(118.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(118.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (cover != null) Cover(cover, 300, Modifier.fillMaxSize(), corner = 18)
            else Icon(MusicIcons.note, null, Modifier.size(44.dp))
        }
        Text(label, Modifier.padding(top = 6.dp), fontSize = 14.sp, maxLines = 1)
        Text("$count tracks", fontSize = 12.sp, color = dimText)
    }
}

@Composable
private fun PlaylistsTab(lib: Library, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val v = PlayStats.version.value
    val added = remember(lib) { PlayStats.recentlyAdded(lib) }
    val most = remember(lib, v) { PlayStats.mostPlayed(lib) }
    val recent = remember(lib, v) { PlayStats.recentlyPlayed(lib) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SmartTile("Recently added", added.size, null) { open(Page.Smart("added")) } }
                item { SmartTile("Most played", most.size, most.firstOrNull()) { open(Page.Smart("most")) } }
                item { SmartTile("Recently played", recent.size, recent.firstOrNull()) { open(Page.Smart("recent")) } }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton({ naming = true }) { Icon(MusicIcons.plus, null, tint = Accent); Spacer(Modifier.width(4.dp)); Text("New playlist", color = Accent) }
                TextButton({ open(Page.Import) }) { Text("⇩  Import playlists", color = Accent) }
            }
        }
        items(Playlists.all.value, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { open(Page.PlaylistPage(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p.songs.firstNotNullOfOrNull { lib.byId[it] }, 160, Modifier.size(52.dp), corner = 10)
                Spacer(Modifier.width(14.dp))
                Text(p.name, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.songs.size} tracks", fontSize = 13.sp, color = dimText)
            }
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text("New playlist") },
        text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ naming = false; Playlists.create(ctx, name); name = "" }) { Text("Create", color = Accent) } },
        dismissButton = { TextButton({ naming = false }) { Text("Cancel") } })
}

private fun sortedTracks(lib: Library, by: Int) = when (by) {
    1 -> lib.songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    2 -> lib.songs.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    3 -> lib.songs.sortedByDescending { it.dateAdded }
    else -> lib.songs.sortedBy { it.title.lowercase() }
}

@Composable
private fun TracksTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val by = Sorts.trackSort.intValue
    val songs = remember(lib, by) { sortedTracks(lib, by) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SortBar(Sorts.tracks, by, { Sorts.trackSort.intValue = it; Sorts.save(ctx) }) {
            IconButton({ ui.play(songs, 0, true) }) { Icon(MusicIcons.shuffle, "Shuffle") }
            IconButton({ ui.play(songs, 0) }) { Icon(MusicIcons.play, "Play all") }
        }
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f), state = state) {
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, ui, lib, open) { ui.play(songs, i) } }
            }
            if (by <= 2 && songs.size > 20) AzBar(Modifier, songs.map { when (by) { 1 -> it.artist; 2 -> it.album; else -> it.title } }) { scope.launch { state.scrollToItem(it) } }
        }
    }
}

private fun sortedAlbums(lib: Library, by: Int) = when (by) {
    1 -> lib.albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    2 -> lib.albums.sortedByDescending { it.year }
    3 -> lib.albums.sortedByDescending { it.dateAdded }
    else -> lib.albums
}

@Composable
private fun AlbumsTab(lib: Library, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val by = Sorts.albumSort.intValue
    val albums = remember(lib, by) { sortedAlbums(lib, by) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SortBar(Sorts.albums, by, { Sorts.albumSort.intValue = it; Sorts.save(ctx) })
        Row(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(albums, key = { _, a -> a.id }) { _, a ->
                    Column(Modifier.padding(6.dp).clip(RoundedCornerShape(14.dp)).clickable { open(Page.AlbumPage(a.id)) }) {
                        Cover(a.cover, 400, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14)
                        Text(a.title, Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("${a.artist} | ${a.songs.size} track${if (a.songs.size == 1) "" else "s"}", Modifier.fillMaxWidth(), fontSize = 12.sp, color = dimText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            if (by <= 1 && albums.size > 20) AzBar(Modifier, albums.map { if (by == 1) it.artist else it.title }) { scope.launch { grid.scrollToItem(it) } }
        }
    }
}

@Composable
private fun ArtistsTab(lib: Library, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val by = Sorts.artistSort.intValue
    val artists = remember(lib, by) { if (by == 1) lib.artists.sortedByDescending { it.songs.size } else lib.artists }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SortBar(Sorts.artists, by, { Sorts.artistSort.intValue = it; Sorts.save(ctx) })
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f), state = state) {
                items(artists, key = { it.name }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { open(Page.ArtistPage(a.name)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(a.songs.first(), 160, Modifier.size(50.dp), corner = 10)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.albums.size} album${if (a.albums.size == 1) "" else "s"} | ${a.songs.size} track${if (a.songs.size == 1) "" else "s"}", fontSize = 13.sp, color = dimText)
                        }
                    }
                }
            }
            if (by == 0 && artists.size > 20) AzBar(Modifier, artists.map { it.name }) { scope.launch { state.scrollToItem(it) } }
        }
    }
}

class Folder(val path: String, val songs: List<Song>) { val name: String get() = path.substringAfterLast('/').ifBlank { path } }

fun folders(lib: Library): List<Folder> = lib.songs.groupBy { it.path.substringBeforeLast('/', "") }
    .map { (p, s) -> Folder(p, s.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))) }.sortedBy { it.name.lowercase() }

private fun shortPath(p: String) = p.replace(Regex("^/storage/emulated/0"), "/Internal storage").replace(Regex("^/storage/([0-9A-F-]{4,})"), "/SD card")

@Composable
private fun FoldersTab(lib: Library, open: (Page) -> Unit) {
    val list = remember(lib) { folders(lib) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SortBar(listOf("Name"), 0, {})
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f), state = state) {
                items(list, key = { it.path }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { open(Page.FolderPage(f.path)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Cover(f.songs.first(), 160, Modifier.size(50.dp), corner = 10)
                            Icon(MusicIcons.folder, null, Modifier.align(Alignment.BottomEnd).size(18.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(2.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shortPath(f.path), fontSize = 12.sp, color = dimText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (list.size > 20) AzBar(Modifier, list.map { it.name }) { scope.launch { state.scrollToItem(it) } }
        }
    }
}

// ---- search ----

object RecentSearches {
    val list = mutableStateListOf<String>()
    fun load(ctx: Context) {
        list.clear()
        ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getString("recent_searches", "")!!.split('\n').filter { it.isNotBlank() }.forEach { list += it }
    }
    private fun save(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("recent_searches", list.joinToString("\n")).apply()
    fun add(ctx: Context, q: String) { val t = q.trim(); if (t.isEmpty()) return; list.remove(t); list.add(0, t); while (list.size > 15) list.removeAt(list.lastIndex); save(ctx) }
    fun remove(ctx: Context, q: String) { list.remove(q); save(ctx) }
}

@Composable
internal fun WavesSearch(lib: Library, ui: PlayerUi, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { RecentSearches.load(ctx) }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { q = it; RecentSearches.add(ctx, it) }
    }
    val (songs, albums, artists) = remember(q, lib) { lib.search(q) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(MusicIcons.arrowBack, "Back") }
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search", fontSize = 22.sp, color = dimText)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { RecentSearches.add(ctx, q) }))
            }
            if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(MusicIcons.close, "Clear") }
            else IconButton({
                runCatching { speak.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
            }) { Icon(MusicIcons.mic, "Say it") }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)).padding(horizontal = 26.dp, vertical = 8.dp)) {
                Text("My music", fontSize = 14.sp)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            if (q.isBlank()) {
                if (RecentSearches.list.isNotEmpty()) item { Text("Recent searches", Modifier.padding(16.dp, 8.dp), fontSize = 14.sp, color = dimText) }
                items(RecentSearches.list.toList(), key = { "r$it" }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { q = r }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r, Modifier.weight(1f), fontSize = 17.sp)
                        IconButton({ RecentSearches.remove(ctx, r) }) { Icon(MusicIcons.close, "Remove", Modifier.size(18.dp)) }
                    }
                }
            } else {
                if (artists.isNotEmpty()) {
                    item { Text("Artists", Modifier.padding(16.dp, 10.dp), fontWeight = FontWeight.Bold) }
                    items(artists, key = { "a" + it.name }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { RecentSearches.add(ctx, q); open(Page.ArtistPage(a.name)) }.padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(a.songs.first(), 160, Modifier.size(46.dp), corner = 10); Spacer(Modifier.width(12.dp)); Text(a.name, fontSize = 16.sp)
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item { Text("Albums", Modifier.padding(16.dp, 10.dp), fontWeight = FontWeight.Bold) }
                    items(albums, key = { "l" + it.id }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { RecentSearches.add(ctx, q); open(Page.AlbumPage(a.id)) }.padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(a.cover, 160, Modifier.size(46.dp), corner = 10); Spacer(Modifier.width(12.dp))
                            Column { Text(a.title, fontSize = 16.sp, maxLines = 1); Text(a.artist, fontSize = 13.sp, color = dimText, maxLines = 1) }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { Text("Tracks", Modifier.padding(16.dp, 10.dp), fontWeight = FontWeight.Bold) }
                    itemsIndexed(songs, key = { _, s -> "s" + s.id }) { i, s -> SongRow(s, ui, lib, open) { RecentSearches.add(ctx, q); ui.play(songs, i) } }
                }
                if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) item { Text("Nothing found for “$q”.", Modifier.padding(16.dp)) }
            }
        }
    }
}

// ---- Edit: pick many songs ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditPage(lib: Library, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val songs = remember(lib) { sortedTracks(lib, Sorts.trackSort.intValue) }
    val picked = remember { mutableStateListOf<Long>() }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(MusicIcons.arrowBack, "Back") }
            Text(if (picked.isEmpty()) "Select tracks" else "${picked.size} selected", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton({ if (picked.size == songs.size) picked.clear() else { picked.clear(); picked.addAll(songs.map { it.id }) } }) {
                Text(if (picked.size == songs.size) "None" else "All", color = Accent)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(songs, key = { it.id }) { s ->
                val on = s.id in picked
                Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(s.id) else picked.add(s.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { if (it) picked.add(s.id) else picked.remove(s.id) }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                    Cover(s, 160, Modifier.size(44.dp), corner = 8); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, fontSize = 13.sp, color = dimText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val chosen = { songs.filter { it.id in picked } }
            TextButton({ PlaylistPicker.songs.value = picked.toList() }) { Icon(MusicIcons.plus, null); Spacer(Modifier.width(4.dp)); Text("Add to playlist") }
            TextButton({ SongActions.share(ctx, chosen()) }) { Icon(MusicIcons.share, null); Spacer(Modifier.width(4.dp)); Text("Share") }
            TextButton({ confirm = true }) { Icon(MusicIcons.delete, null); Spacer(Modifier.width(4.dp)); Text("Delete") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete ${picked.size} tracks from your phone?") },
        text = { Text("The music files are removed from your phone.") },
        confirmButton = { TextButton({ confirm = false; SongActions.delete(ctx, songs.filter { it.id in picked }); picked.clear() }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/** Track details box (from the song ⋮ menu). */
@Composable
internal fun TrackDetailsDialog() {
    val ctx = LocalContext.current
    val s = SongActions.details.value ?: return
    val extra = remember(s.id) { SongActions.fileInfo(ctx, s) }
    AlertDialog(onDismissRequest = { SongActions.details.value = null }, title = { Text("Track details") },
        text = {
            LazyColumn {
                val rows = listOf("Title" to s.title, "Artist" to s.artist, "Album" to s.album, "Album artist" to s.albumArtist,
                    "Track" to (if (s.track > 0) "${s.track}" else ""), "Year" to (if (s.year > 0) "${s.year}" else ""), "Length" to time(s.durationMs)) +
                    extra + listOf("Location" to shortPath(s.path))
                items(rows.filter { it.second.isNotBlank() }) { (k, v) ->
                    Column(Modifier.padding(vertical = 5.dp)) { Text(k, fontSize = 12.sp, color = dimText); Text(v, fontSize = 15.sp) }
                }
            }
        },
        confirmButton = { TextButton({ SongActions.details.value = null }) { Text("OK", color = Accent) } })
}

/** Samsung-style mini player, size adjustable in Settings > Customise (Jarrod: "way too small"). */
@Composable
internal fun WavesMiniPlayer(song: Song, ui: PlayerUi, tint: Color, onOpen: () -> Unit, onQueue: () -> Unit) {
    val k = MusicSettings.miniSize
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).clip(RoundedCornerShape((26 * k).dp))
        .background(tint).clickable { onOpen() }
        .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -20) onOpen() } }
        .padding(horizontal = (10 * k).dp, vertical = (8 * k).dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(song, 200, Modifier.size((46 * k).dp), corner = (23 * k).roundToInt())
        Spacer(Modifier.width((10 * k).dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = (16 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.75f), fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        @Composable fun B(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Float, onClick: () -> Unit) {
            Box(Modifier.size((44 * k).dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size((size * k).dp), tint = Color.White)
            }
        }
        B(MusicIcons.previous, "Previous", 26f) { ui.previous() }
        B(if (ui.playing) MusicIcons.pause else MusicIcons.play, "Play or pause", 30f) { ui.toggle() }
        B(MusicIcons.next, "Next", 26f) { ui.next() }
        B(MusicIcons.queue, "Queue", 24f) { onQueue() }
    }
}

@Suppress("unused") private fun unusedState(l: LazyListState, g: LazyGridState) = l to g
@Suppress("unused") private val unusedCrop = ContentScale.Crop
@Suppress("unused") private val unusedSpan = GridItemSpan(1)
