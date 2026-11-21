// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
internal val allTabs = listOf("Popular", "Moods", "Favourites", "Playlists", "Tracks", "Albums", "Artists", "Folders") // test 300: Jarrod's order // test 297: Popular
/** test 298: the Popular tab goes when popularity features are turned off. Test 316 (Jarrod): any tab can be hidden; at least one always stays. */
val WavesTabs: List<String> get() = allTabs.filter { t ->
    t !in MusicSettings.hiddenTabs && (t != "Popular" || MusicSettings.popularOn)
}.ifEmpty { listOf("Tracks") }

/** Test 316: small per-tab tweaks (each tab's own settings box). */
object TabOpts {
    var az by mutableStateOf(true)        // A–Z bar down the side of long lists
    var favMost by mutableStateOf(true)   // Most played row on Favourites
    fun load(ctx: Context) { val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE); az = p.getBoolean("tab_az", true); favMost = p.getBoolean("fav_most", true) }
    fun save(ctx: Context) { ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putBoolean("tab_az", az).putBoolean("fav_most", favMost).apply() }
}

private val tabBlurbs = mapOf("Popular" to "Your songs ranked by how popular they are in the charts.", "Moods" to "Mixes for how you feel, made from your songs.",
    "Favourites" to "Songs you hearted, and your most played.", "Playlists" to "Your playlists and smart playlists.", "Tracks" to "Every song.",
    "Albums" to "Your albums.", "Artists" to "Your artists.", "Folders" to "Songs by the folder they're in.")

/** Test 316 (Jarrod): every tab has its own little settings box: show or hide it, open the app on it, and that tab's tweaks. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TabSettingsDialog(tab: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    @Composable fun SwitchRow(title: String, note: String?, on: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp); note?.let { Text(it, fontSize = 12.sp, color = dim) } }
            Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
    }
    @Composable fun SortChips(options: List<String>, chosen: androidx.compose.runtime.MutableIntState) {
        Text("Sort by", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o -> Chip(o, chosen.intValue == i) { chosen.intValue = i; Sorts.save(ctx) } }
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("$tab tab") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tabBlurbs[tab] ?: "", fontSize = 13.sp, color = dim)
            val shown = tab !in MusicSettings.hiddenTabs
            val others = allTabs.count { it != tab && it !in MusicSettings.hiddenTabs }
            SwitchRow("Show this tab", if (shown && others == 0) "It's the only tab left, so it stays." else "Off hides it. You can bring it back in Settings > Tabs.", shown) {
                if (!it && others == 0) return@SwitchRow
                MusicSettings.hiddenTabs = if (it) MusicSettings.hiddenTabs - tab else MusicSettings.hiddenTabs + tab; MusicSettings.save(ctx)
            }
            SwitchRow("Open the app on this tab", null, MusicSettings.startTab == tab) {
                MusicSettings.startTab = if (it) tab else "Tracks"; MusicSettings.save(ctx)
            }
            when (tab) {
                "Tracks" -> { SortChips(Sorts.tracks, Sorts.trackSort); SwitchRow("A–Z bar down the side", null, TabOpts.az) { TabOpts.az = it; TabOpts.save(ctx) } }
                "Albums" -> { SortChips(Sorts.albums, Sorts.albumSort); SwitchRow("A–Z bar down the side", null, TabOpts.az) { TabOpts.az = it; TabOpts.save(ctx) } }
                "Artists" -> { SortChips(Sorts.artists, Sorts.artistSort); SwitchRow("A–Z bar down the side", null, TabOpts.az) { TabOpts.az = it; TabOpts.save(ctx) } }
                "Playlists" -> SortChips(Sorts.playlists, Sorts.playlistSort)
                "Folders" -> SwitchRow("A–Z bar down the side", null, TabOpts.az) { TabOpts.az = it; TabOpts.save(ctx) }
                "Favourites" -> SwitchRow("Show Most played", "The row of your most played songs under Favourite tracks.", TabOpts.favMost) { TabOpts.favMost = it; TabOpts.save(ctx) }
                "Moods" -> MoodTabOptions()
                "Popular" -> SwitchRow("Popularity features", "Off also turns off Sort by popularity and chart facts, and hides this tab.", MusicSettings.popularOn) {
                    MusicSettings.popularOn = it; MusicSettings.save(ctx); if (!it && PopScan.running.value) PopScan.pause(ctx)
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("Done", color = Accent) } })
}

/** Settings > Tabs: a switch for every tab and a ⚙ for its own settings. */
@Composable
internal fun TabsSettings() {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var open by remember { mutableStateOf<String?>(null) }
    Column {
        Section("Tabs")
        Text("Pick which tabs show. Tap ⚙ for a tab's own settings (you can also long-press a tab's name).", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
        allTabs.forEach { t ->
            val shown = t !in MusicSettings.hiddenTabs
            val others = allTabs.count { it != t && it !in MusicSettings.hiddenTabs }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t + if (MusicSettings.startTab == t) "  · opens here" else "", fontSize = 17.sp)
                    if (t == "Popular" && !MusicSettings.popularOn) Text("Popularity features are off", fontSize = 12.sp, color = dim)
                }
                IconButton({ open = t }) { Icon(MusicIcons.settings, "$t settings", Modifier.size(22.dp)) }
                Switch(shown, { if (it || others > 0) { MusicSettings.hiddenTabs = if (it) MusicSettings.hiddenTabs - t else MusicSettings.hiddenTabs + t; MusicSettings.save(ctx) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
    }
    open?.let { TabSettingsDialog(it) { open = null } }
}

private val dimText @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

/** "♫ Open Waves" — Jarrod's logo first, then the name. */
@Composable
internal fun WavesTopBar(open: (Page) -> Unit, playing: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WavesLogo(playing, 34.dp)
        Spacer(Modifier.width(6.dp))
        // test 288 (Jarrod): a CapCut-style text effect every 60 s while music plays, 10 of them in turn
        Box(Modifier.weight(1f)) { AnimatedTitle(playing) }
        extra?.invoke()
        IconButton({ open(Page.Search) }) { Icon(MusicIcons.search, "Search", Modifier.size(26.dp)) }
        Box {
            IconButton({ menu = true }) { Icon(MusicIcons.more, "More", Modifier.size(26.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Edit") }, { menu = false; open(Page.Edit) })
                DropdownMenuItem({ Text("Add to Home screen") }, { menu = false; addHomeShortcut(ctx) })
                DropdownMenuItem({ Text("Design studio") }, { menu = false; open(Page.Studio) })
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
    var tabSettings by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pager.currentPage) { row.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    LazyRow(state = row, modifier = Modifier.fillMaxWidth().height(58.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(WavesTabs) { i, name ->
            val on = i == pager.currentPage
            Text(name, fontSize = if (on) 30.sp else 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onSurface else dimText,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onLongClick = { tabSettings = name }) { scope.launch { pager.animateScrollToPage(i) } })
        }
        // test 316: the open tab's own settings
        item { IconButton({ tabSettings = WavesTabs.getOrNull(pager.currentPage) }) { Icon(MusicIcons.settings, "Tab settings", Modifier.size(20.dp), tint = dimText) } }
    }
    tabSettings?.let { TabSettingsDialog(it) { tabSettings = null } }
}

@Composable
internal fun WavesHome(lib: Library, ui: PlayerUi, pager: PagerState, open: (Page) -> Unit) {
    val selecting = PlaylistSelect.active && WavesTabs.getOrNull(pager.currentPage) == "Playlists"
    BackHandler(enabled = PlaylistSelect.active) { PlaylistSelect.clear() }
    LaunchedEffect(pager.currentPage) { if (WavesTabs.getOrNull(pager.currentPage) != "Playlists") PlaylistSelect.clear() }
    Column(Modifier.fillMaxSize()) {
        if (selecting) SelectHeader()
        else WavesTopBar(open, ui.playing, extra = if (WavesTabs.getOrNull(pager.currentPage) == "Playlists") ({ IconButton({ PlaylistSelect.naming = true }) { Icon(MusicIcons.sPlus, "New playlist", Modifier.size(26.dp)) } }) else null)
        WavesTabHeader(pager)
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(if (MusicSettings.theme != -1) Color.Black.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer), beyondViewportPageCount = 0, userScrollEnabled = !selecting) { page ->
            when (WavesTabs.getOrNull(page)) {
                "Favourites" -> FavouritesTab(lib, ui, open)
                "Playlists" -> PlaylistsTab(lib, ui, open)
                "Tracks" -> TracksTab(lib, ui, open)
                "Popular" -> PopularTab(lib, ui, open)
                "Moods" -> MoodsTab(lib, ui, open)
                "Albums" -> AlbumsTab(lib, open)
                "Artists" -> ArtistsTab(lib, open)
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
    val playlists = listOf("My order", "Name")
    val trackSort = mutableIntStateOf(0); val albumSort = mutableIntStateOf(0); val artistSort = mutableIntStateOf(0); val playlistSort = mutableIntStateOf(0)
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
        trackSort.intValue = p.getInt("sort_tracks", 0); albumSort.intValue = p.getInt("sort_albums", 0); artistSort.intValue = p.getInt("sort_artists", 0); playlistSort.intValue = p.getInt("sort_playlists", 0)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putInt("sort_tracks", trackSort.intValue)
            .putInt("sort_albums", albumSort.intValue).putInt("sort_artists", artistSort.intValue).putInt("sort_playlists", playlistSort.intValue).apply()
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

/** A–Z down the right side, Samsung style: tap or slide your finger to jump; a big letter shows where you are. */
@Composable
private fun AzBar(modifier: Modifier, keys: List<String>, jump: (Int) -> Unit) {
    val letters = remember { ('A'..'Z').toList() + '#' }
    val firstIndex = remember(keys) {
        val m = HashMap<Char, Int>()
        keys.forEachIndexed { i, k -> m.putIfAbsent(letterOf(k), i) }
        m
    }
    var shown by remember { mutableStateOf<Char?>(null) }
    var bubbleY by remember { mutableFloatStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun go(y: Float, h: Int) {
        val li = ((y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
        bubbleY = y.coerceIn(0f, h.toFloat())
        if (shown != letters[li]) { shown = letters[li]; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
        // the letter, or the next one that has something
        for (j in li until letters.size) firstIndex[letters[j]]?.let { jump(it); return }
        for (j in li downTo 0) firstIndex[letters[j]]?.let { jump(it); return }
    }
    Box(modifier.fillMaxHeight()) {
        BoxWithConstraints(Modifier.padding(end = 4.dp, top = 6.dp, bottom = 6.dp).width(26.dp).fillMaxHeight()
            .clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
            val h = constraints.maxHeight
            Column(Modifier.fillMaxSize().padding(vertical = 6.dp)
                .pointerInput(keys) { detectTapGestures(onPress = { o -> go(o.y, h); tryAwaitRelease(); shown = null }) }
                .pointerInput(keys) { detectVerticalDragGestures(onDragEnd = { shown = null }, onDragCancel = { shown = null }) { change, _ -> change.consume(); go(change.position.y, h) } },
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                letters.forEach { Text(it.toString(), fontSize = 11.sp, color = if (it == shown) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
            }
        }
        val l = shown
        if (l != null) {
            val dy = with(androidx.compose.ui.platform.LocalDensity.current) { bubbleY.toDp() }
            Box(Modifier.offset(x = (-76).dp, y = (dy - 10.dp).coerceAtLeast(0.dp)).size(60.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Text(l.toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
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
        if (TabOpts.favMost) item { HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)) }
        if (TabOpts.favMost) item { Text(if (most.isEmpty()) "Most played (play some songs and they'll show here)" else "Most played", fontSize = 17.sp, color = dimText) }
        if (TabOpts.favMost) item {
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
        Text(if (count >= 0) "$count tracks" else " ", fontSize = 12.sp, color = dimText)
    }
}

/** Long-press selection on the Playlists tab (test 243, like Samsung). */
object PlaylistSelect {
    val ids = mutableStateListOf<Long>()
    var active by mutableStateOf(false)
    var naming by mutableStateOf(false)
    var total by mutableIntStateOf(0)
    var allIds: List<Long> = emptyList()
    fun clear() { ids.clear(); active = false }
    fun toggle(id: Long) { if (id in ids) ids.remove(id) else ids.add(id); if (ids.isEmpty()) active = false }
}

@Composable
private fun SelectHeader() {
    val all = PlaylistSelect.ids.size == PlaylistSelect.total && PlaylistSelect.total > 0
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 14.dp, bottom = 6.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
            if (all) PlaylistSelect.clear() else { PlaylistSelect.ids.clear(); PlaylistSelect.ids.addAll(PlaylistSelect.allIds) }
        }.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SelectCircle(all)
            Text("All", fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("${PlaylistSelect.ids.size} selected", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SelectCircle(on: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Accent else Color.Transparent)
        .border(1.5.dp, if (on) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlaylistsTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val v = PlayStats.version.value
    val added = remember(lib) { PlayStats.recentlyAdded(lib) }
    val most = remember(lib, v) { PlayStats.mostPlayed(lib) }
    val recent = remember(lib, v) { PlayStats.recentlyPlayed(lib) }
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val by = Sorts.playlistSort.intValue
    // the list as shown; while you drag a playlist it changes here first, then gets saved when you let go
    // test 279 (Jarrod: Back still went to the top): the list has to be full on the very first frame, or the list
    // only has its 3 top rows for a moment and the saved scroll spot gets lost
    fun sorted() = if (by == 1) Playlists.all.value.sortedBy { it.name.lowercase() } else Playlists.all.value
    val order = remember { mutableStateListOf<Playlist>().apply { addAll(sorted()) } }
    LaunchedEffect(Playlists.all.value, by) {
        val now = sorted()
        if (order.toList() != now) { order.clear(); order.addAll(now) }
    }
    PlaylistSelect.total = order.size; PlaylistSelect.allIds = order.map { it.id }
    val state = TabScroll.playlists
    /*
     * Test 293 (Jarrod: sometimes the drag still drops): the same rebuild as inside a playlist (test 292). The page around the
     * list handles the drag, the rows only move when you let go, a copy follows your finger and a line shows where it lands.
     */
    var dragId by remember { mutableStateOf<Long?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grabY by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) } // insert before this index of order (order.size = at the end)
    var listH by remember { mutableIntStateOf(1) }
    var releasedAt by remember { mutableLongStateOf(0L) }
    val head = 3 // items above the playlists: smart tiles, buttons, sort bar

    fun findTarget() {
        val shown = state.layoutInfo.visibleItemsInfo.filter { it.key is Long }
        if (shown.isEmpty()) return
        val hit = shown.firstOrNull { fingerY < it.offset + it.size / 2f }
        target = if (hit != null) order.indexOfFirst { it.id == hit.key } else order.indexOfFirst { it.id == shown.last().key } + 1
    }
    fun drop() {
        val id = dragId ?: return
        val from = order.indexOfFirst { it.id == id }
        var to = target
        if (from >= 0 && to >= 0) {
            if (to > from) to -= 1
            if (to != from) {
                order.add(to, order.removeAt(from))
                Playlists.reorder(ctx, order.map { it.id })
                if (Sorts.playlistSort.intValue != 0) { Sorts.playlistSort.intValue = 0; Sorts.save(ctx) }
            }
        }
        dragId = null; target = -1
    }
    // held near the top or bottom: the list scrolls by itself, faster the closer and the longer
    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        var heldMs = 0f; var last = 0L
        while (dragId != null) {
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).onSizeChanged { listH = it.height }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // hold a playlist (without moving) to select it and pick it up; anything else is a normal tap, scroll or swipe
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                            if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    if (early != null) return@awaitEachGesture
                    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long && down.position.y >= it.offset && down.position.y < it.offset + it.size }
                        ?: return@awaitEachGesture
                    val id = item.key as Long
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (!PlaylistSelect.active) PlaylistSelect.active = true
                    if (id !in PlaylistSelect.ids) PlaylistSelect.ids.add(id)
                    fingerY = down.position.y; grabY = down.position.y - item.offset
                    dragId = id; findTarget()
                    var moved = false
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        c.consume() // the list, the tabs and the rows don't see it while you drag
                        if (!c.pressed) break
                        if (kotlin.math.abs(c.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                        fingerY = c.position.y
                        findTarget()
                    }
                    if (moved) drop() else { dragId = null; target = -1 }
                    releasedAt = android.os.SystemClock.uptimeMillis()
                }
            }) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                item {
                    LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { SmartTile("Recently added", added.size, null) { open(Page.Smart("added")) } }
                        item { SmartTile("Most played", most.size, most.firstOrNull()) { open(Page.Smart("most")) } }
                        item { SmartTile("Recently played", recent.size, recent.firstOrNull()) { open(Page.Smart("recent")) } }
                        item { SmartTile("🎉 Your year", -1, null) { open(Page.Year) } }
                        // test 311: your own smart playlists (rules), and a tile to make one
                        items(SmartLists.all.value, key = { "smart" + it.id }) { s ->
                            val first = remember(s, lib) { SmartLists.songs(ctx, lib, s).firstOrNull() }
                            SmartTile("⚡ " + s.name, -1, first) { open(Page.SmartListPage(s.id)) }
                        }
                        item { SmartTile("＋ Smart playlist", -1, null) { SmartLists.editing.value = SmartList(System.currentTimeMillis(), "Top rated", minStars = 4, sort = "rating") } }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        TextButton({ PlaylistSelect.naming = true }) { Icon(MusicIcons.plus, null, tint = Accent); Spacer(Modifier.width(4.dp)); Text("New playlist", color = Accent) }
                        TextButton({ open(Page.Import) }) { Text("⇩  Import playlists", color = Accent) }
                    }
                }
                item { SortBar(Sorts.playlists, by, { Sorts.playlistSort.intValue = it; Sorts.save(ctx) }) }
                items(order, key = { it.id }) { p ->
                    val dragging = dragId == p.id
                    val picked = p.id in PlaylistSelect.ids
                    val idx = order.indexOf(p)
                    val lineTop = dragId != null && target == idx
                    val lineBottom = dragId != null && target == order.size && idx == order.size - 1
                    Row(Modifier.fillMaxWidth()
                        .graphicsLayer { alpha = if (dragging) 0.3f else 1f }
                        .drawWithContent {
                            drawContent()
                            val h = 3.dp.toPx()
                            if (lineTop) drawRect(Accent, size = androidx.compose.ui.geometry.Size(size.width, h))
                            if (lineBottom) drawRect(Accent, androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
                        }
                        .background(if (picked) Accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable {
                            // letting go after a long press is not a tap
                            if (dragId != null || android.os.SystemClock.uptimeMillis() - releasedAt < 400) return@clickable
                            if (PlaylistSelect.active) PlaylistSelect.toggle(p.id) else open(Page.PlaylistPage(p.id))
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (PlaylistSelect.active) { SelectCircle(picked); Spacer(Modifier.width(12.dp)) }
                        Cover(p.songs.firstNotNullOfOrNull { lib.byId[it] }, 160, Modifier.size(52.dp), corner = 10)
                        Spacer(Modifier.width(14.dp))
                        Text(p.name, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.songs.size} tracks", fontSize = 13.sp, color = dimText)
                        if (PlaylistSelect.active) Text("  ≡", fontSize = 22.sp, color = dimText)
                    }
                }
            }
            // the playlist you're dragging, following your finger
            val dragged = dragId?.let { d -> order.firstOrNull { it.id == d } }
            if (dragged != null) Row(Modifier.fillMaxWidth()
                .graphicsLayer { translationY = fingerY - grabY; scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 20f }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectCircle(true); Spacer(Modifier.width(12.dp))
                Cover(dragged.songs.firstNotNullOfOrNull { lib.byId[it] }, 160, Modifier.size(52.dp), corner = 10)
                Spacer(Modifier.width(14.dp))
                Text(dragged.name, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${dragged.songs.size} tracks", fontSize = 13.sp, color = dimText)
                Text("  ≡", fontSize = 22.sp, color = Accent)
            }
            }
            if (TabOpts.az && order.size > 8) AzBar(Modifier, order.map { it.name }) { scope.launch { state.scrollToItem(it + head) } }
        }
        // test 289 (Jarrod): send the chosen playlists straight to the top or the bottom
        if (PlaylistSelect.active) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            fun edge(top: Boolean) {
                Playlists.moveToEdge(ctx, PlaylistSelect.ids.toList(), top)
                if (Sorts.playlistSort.intValue != 0) { Sorts.playlistSort.intValue = 0; Sorts.save(ctx) }
                scope.launch { state.scrollToItem(if (top) head else (head + order.size - 1).coerceAtLeast(0)) }
            }
            TextButton({ edge(true) }, enabled = PlaylistSelect.ids.isNotEmpty()) { Text("⤒  Move to top", color = Accent) }
            TextButton({ edge(false) }, enabled = PlaylistSelect.ids.isNotEmpty()) { Text("⤓  Move to bottom", color = Accent) }
        }
        // Samsung's bottom bar while playlists are selected
        if (PlaylistSelect.active) {
            val chosen = order.filter { it.id in PlaylistSelect.ids }
            val songs = chosen.flatMap { pl -> pl.songs.mapNotNull { lib.byId[it] } }
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                @Composable fun A(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val c = if (enabled) MaterialTheme.colorScheme.onSurface else dimText.copy(alpha = 0.3f)
                        Icon(icon, label, Modifier.size(24.dp), tint = c); Text(label, fontSize = 11.sp, color = c)
                    }
                }
                A(MusicIcons.sPlay, "Play", songs.isNotEmpty()) { ui.play(songs, 0); PlaylistSelect.clear() }
                A(MusicIcons.sPlus, "Add", songs.isNotEmpty()) { PlaylistPicker.songs.value = songs.map { it.id }; PlaylistSelect.clear() }
                A(MusicIcons.share, "Share", songs.isNotEmpty()) { SongActions.share(ctx, songs) }
                A(MusicIcons.delete, "Delete", chosen.isNotEmpty()) { confirmDelete = true }
                A(MusicIcons.edit, "Rename", chosen.size == 1) { renaming = chosen.firstOrNull(); name = chosen.firstOrNull()?.name ?: "" }
            }
        }
    }
    if (PlaylistSelect.naming) AlertDialog(onDismissRequest = { PlaylistSelect.naming = false }, title = { Text("New playlist") },
        text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ PlaylistSelect.naming = false; Playlists.create(ctx, name); name = "" }) { Text("Create", color = Accent) } },
        dismissButton = { TextButton({ PlaylistSelect.naming = false }) { Text("Cancel") } })
    renaming?.let { r ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename playlist") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ Playlists.rename(ctx, r.id, name); renaming = null; name = ""; PlaylistSelect.clear() }) { Text("Rename", color = Accent) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(if (PlaylistSelect.ids.size == 1) "Delete this playlist?" else "Delete ${PlaylistSelect.ids.size} playlists?") },
        text = { Text("Only the playlist goes. Your songs stay on your phone.") },
        confirmButton = { TextButton({ PlaylistSelect.ids.toList().forEach { Playlists.delete(ctx, it) }; confirmDelete = false; PlaylistSelect.clear() }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

private fun sortedTracks(lib: Library, by: Int) = lib.uniqueSongs.let { songs -> when (by) {
    1 -> songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    2 -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    3 -> songs.sortedByDescending { it.dateAdded }
    else -> songs.sortedBy { it.title.lowercase() }
} }

@Composable
private fun TracksTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val by = Sorts.trackSort.intValue
    val songs = remember(lib, by) { sortedTracks(lib, by) }
    val state = TabScroll.tracks
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
            if (TabOpts.az && by <= 2 && songs.size > 12) AzBar(Modifier, songs.map { when (by) { 1 -> it.artist; 2 -> it.album; else -> it.title } }) { scope.launch { state.scrollToItem(it) } }
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
    // test 282: real albums only, also leaving out anything named like one of your playlists with mixed artists
    val names = Playlists.all.value.map { it.name.trim().lowercase() }.toSet()
    val albums = remember(lib, by, names) { sortedAlbums(lib, by).filter { AlbumRules.looksLikeAlbum(it, names) } }
    val grid = TabScroll.albums
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
            if (TabOpts.az && by <= 1 && albums.size > 12) AzBar(Modifier, albums.map { if (by == 1) it.artist else it.title }) { scope.launch { grid.scrollToItem(it) } }
        }
    }
}

@Composable
private fun ArtistsTab(lib: Library, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val by = Sorts.artistSort.intValue
    val artists = remember(lib, by) { if (by == 1) lib.artists.sortedByDescending { it.songs.size } else lib.artists }
    val state = TabScroll.artists
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
            if (TabOpts.az && by == 0 && artists.size > 12) AzBar(Modifier, artists.map { it.name }) { scope.launch { state.scrollToItem(it) } }
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
    val state = TabScroll.folders
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
            if (TabOpts.az && list.size > 12) AzBar(Modifier, list.map { it.name }) { scope.launch { state.scrollToItem(it) } }
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
    // Test 261 (Jarrod): past about a third of the size slider the name got squeezed out by the big buttons,
    // so then the song name goes on top and the artist underneath, in a smaller font that fits.
    // test 281 (Jarrod): the switch between the two layouts made a sudden jump in size, and he liked the big one's
    // writing best, so every size now uses it (name on top, artist underneath) and grows smoothly
    val big = true
    val tk = 1f + (k - 1f) * 0.4f
    @Composable fun B(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Float, onClick: () -> Unit) {
        val bk = if (big) minOf(k, 1.65f) else k
        Box(Modifier.size((36 * bk).dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size((size * bk).dp), tint = Color.White)
        }
    }
    @Composable fun Buttons() {
        B(MusicIcons.sPrevious, "Previous", 24f) { ui.previous() }
        B(if (ui.playing) MusicIcons.sPause else MusicIcons.sPlay, "Play or pause", 28f) { ui.toggle() }
        B(MusicIcons.sNext, "Next", 24f) { ui.next() }
        B(MusicIcons.sQueue, "Queue", 24f) { onQueue() }
    }
    // Design studio (test 264): the mini player can have its own theme, and its cover can move to the beat
    val mt = MusicSettings.miniTheme.let { if (it == -2) MusicSettings.theme else it }
    val bg = when { mt == -3 || (MusicSettings.miniTheme == -2 && mt == -1) -> tint; mt == -1 -> Color(0xFF111114); else -> Color.Transparent }
    val beatMap = rememberBeatMap(song, MusicSettings.miniCover != 0)
    val clock = rememberBeatClock(ui, MusicSettings.miniCover != 0 && beatMap != null && ui.playing)
    val outer = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).clip(RoundedCornerShape((26 * k).dp))
        .background(bg)
        .clickable { onOpen() }
        .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -20) onOpen() } }
    @Composable fun MiniCover(px: Int) {
        val liftPx = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
        Box(contentAlignment = Alignment.Center) {
            if (MusicSettings.miniCover == 3) Box(Modifier.size((46 * k).dp).graphicsLayer {
                val m = coverMove(beatMap, clock.ms, 3)
                alpha = m.glow; scaleX = 1.05f + 0.25f * m.glow; scaleY = scaleX
            }.then(Modifier.blur(14.dp)).clip(CircleShape).background(Accent))
            Cover(song, px, Modifier.size((46 * k).dp).graphicsLayer {
                val m = coverMove(beatMap, clock.ms, MusicSettings.miniCover)
                scaleX = m.scale; scaleY = m.scale; rotationZ = m.rotation; translationY = m.lift * liftPx
            }, corner = (23 * k).roundToInt())
        }
    }
    Box(outer) {
    if (mt >= 0) ThemeBackground(mt, Modifier.matchParentSize(), dim = 0.4f, preview = true)
    if (big) {
        Column(Modifier.fillMaxWidth().padding(horizontal = (10 * k).dp, vertical = (5 * k).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // test 262 (Jarrod): scroll long names like the small player does (fillMaxWidth on the text itself stopped it)
            // test 263 (Jarrod): the name fits on the big player, so the normal marquee never moved; this one always rolls
            RollText(song.title, Color.White, 14f * tk)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MiniCover(300)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { Buttons() }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ShineText(song.artist, Color.White.copy(alpha = 0.75f), 12f * tk)
            }
        }
    } else Row(Modifier.fillMaxWidth().padding(horizontal = (10 * k).dp, vertical = (8 * k).dp), verticalAlignment = Alignment.CenterVertically) {
        MiniCover(200)
        Spacer(Modifier.width((10 * k).dp))
        Column(Modifier.weight(1f).widthIn(min = 60.dp)) {
            // text grows a bit less than the rest, and long names scroll across so you can read them (test 242)
            val tk = 1f + (k - 1f) * 0.6f
            Text(song.title, color = Color.White, fontSize = (15 * tk).sp, maxLines = 1, modifier = Modifier.basicMarquee(initialDelayMillis = 1500))
            ShineText(song.artist, Color.White.copy(alpha = 0.75f), 12.5f * tk)
        }
        Buttons()
    }
    }
}

/** Test 262 (Jarrod): the artist name sits still for about 6 s, then plays a 3 s effect and settles again.
 *  The effects take turns: a bright shine sweeping across (with a soft glow), then the letters dissolving away
 *  one by one in a random order and reappearing the same way (Jarrod: "dissolve and then reappear"). */
@Composable
internal fun ShineText(text: String, color: Color, size: Float) {
    val p = remember { androidx.compose.animation.core.Animatable(-1f) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        p.snapTo(-1f)
        while (true) {
            kotlinx.coroutines.delay(6000)
            p.snapTo(0f)
            p.animateTo(1f, androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing))
            p.snapTo(-1f)
            round++
        }
    }
    // each letter gets its own moment to vanish and to come back
    val order = remember(text) { val rnd = java.util.Random(text.hashCode().toLong()); FloatArray(text.length) { rnd.nextFloat() } }
    var w by remember { mutableIntStateOf(1) }
    val v = p.value
    val bump = if (v < 0f) 0f else kotlin.math.sin(Math.PI * v).toFloat() // 0 → 1 → 0 over the 3 s
    val shine = round % 2 == 0
    val mod = Modifier.onSizeChanged { w = it.width.coerceAtLeast(1) }
    // test 263 (Jarrod: "the size of the box changes"): the shine used its own text style with a different line
    // height, so the player grew during it. Same base style for every effect, inside a fixed-height box.
    val base = androidx.compose.material3.LocalTextStyle.current
    val lineH = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 1.45f).sp.toDp() }
    Box(Modifier.height(lineH), contentAlignment = Alignment.Center) { when {
        v < 0f -> Text(text, color = color, fontSize = size.sp, lineHeight = (size * 1.45f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = mod)
        shine -> {
            val x = (-0.5f + 2f * v) * w // the shine travels from left of the text to past its right side
            val band = 0.35f * w
            val style = base.merge(TextStyle(fontSize = size.sp, lineHeight = (size * 1.45f).sp,
                brush = Brush.linearGradient(listOf(color, Color.White, Accent, Color.White, color),
                    start = androidx.compose.ui.geometry.Offset(x - band, 0f), end = androidx.compose.ui.geometry.Offset(x + band, 0f)),
                shadow = androidx.compose.ui.graphics.Shadow(Accent.copy(alpha = 0.8f * bump), blurRadius = 14f * bump)))
            val scale = 1f + 0.06f * bump
            Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = mod.graphicsLayer { scaleX = scale; scaleY = scale })
        }
        else -> {
            val shown = androidx.compose.ui.text.buildAnnotatedString {
                text.forEachIndexed { i, c ->
                    val r = order[i]
                    val out = ((v - r * 0.35f) / 0.12f).coerceIn(0f, 1f)          // fades away between 0 and ~0.47
                    val back = ((v - (0.55f + r * 0.33f)) / 0.12f).coerceIn(0f, 1f) // comes back between 0.55 and 1
                    val a = 1f - out + back
                    // a letter on its way out or in flashes in the theme colour
                    val flash = if (a in 0.05f..0.95f) Accent else color
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = flash.copy(alpha = color.alpha * a.coerceIn(0f, 1f))))
                    append(c)
                    pop()
                }
            }
            Text(shown, fontSize = size.sp, lineHeight = (size * 1.45f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = mod.graphicsLayer { translationY = -3f * bump })
        }
    }
    }
}

/** Test 263: a name that always rolls across: rests 2 s in the middle, slides out left while it comes back in from the right. */
@Composable
internal fun RollText(text: String, color: Color, size: Float, center: Boolean = true, bold: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val cw = constraints.maxWidth.toFloat()
        val dens = androidx.compose.ui.platform.LocalDensity.current
        var tw by remember(text) { mutableIntStateOf(0) }
        val gap = with(dens) { 48.dp.toPx() }
        val d = maxOf(tw + gap, cw)
        val start = if (center && tw < cw) (cw - tw) / 2f else 0f
        val off = remember(text) { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(text, tw, cw) {
            if (tw == 0) return@LaunchedEffect
            off.snapTo(0f)
            val ms = (d / with(dens) { 32.dp.toPx() } * 1000).toInt() // about 32 dp a second
            while (true) {
                kotlinx.coroutines.delay(2000)
                off.animateTo(d, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
                off.snapTo(0f)
            }
        }
        @Composable fun copy(x: Float, measure: Boolean) = Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .then(if (measure) Modifier.onSizeChanged { tw = it.width } else Modifier)
                .offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0) })
        Box(Modifier.fillMaxWidth()) {
            copy(start - off.value, true)
            if (off.value > 0f) copy(start - off.value + d, false)
        }
    }
}

@Suppress("unused") private fun unusedState(l: LazyListState, g: LazyGridState) = l to g
@Suppress("unused") private val unusedCrop = ContentScale.Crop
@Suppress("unused") private val unusedSpan = GridItemSpan(1)

/** Test 278 (Jarrod): where each tab was scrolled to, kept while you look inside a playlist, album or artist,
 *  so Back brings you to the same spot instead of the top. */
internal object TabScroll {
    val playlists = LazyListState()
    val tracks = LazyListState()
    val popular = LazyListState()
    val moods = LazyListState()
    val artists = LazyListState()
    val folders = LazyListState()
    val albums = LazyGridState()
}
