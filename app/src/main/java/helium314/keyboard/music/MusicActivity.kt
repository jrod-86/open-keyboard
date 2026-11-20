// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val Accent = Color(0xFFFA2D48) // Apple Music style pink-red

/**
 * Open Keyboard Music (test 229, Jarrod): an Apple Music style player for the music on your phone.
 * A plugin of the keyboard (app drawer tile "Music"), running in its own ":music" process.
 */
class MusicActivity : ComponentActivity() {
    private val ui = PlayerUi()
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Accent, background = Color.Black, surface = Color(0xFF111111))
                else lightColorScheme(primary = Accent, background = Color.White, surface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MusicApp(ui) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicService::class.java))).buildAsync()
        future = f
        f.addListener({ runCatching { ui.attach(f.get()) } }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        ui.detach()
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

/** What the screen shows about playback, kept in step with the player. */
class PlayerUi {
    var controller by mutableStateOf<MediaController?>(null)
    var playing by mutableStateOf(false)
    var currentId by mutableLongStateOf(-1L)
    var current by mutableStateOf<MediaItem?>(null)
    var position by mutableLongStateOf(0L)
    /** when [position] was read (elapsedRealtime), so effects can move smoothly between reads */
    var positionAt = 0L
    var duration by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var upNext by mutableStateOf<List<Pair<Int, MediaItem>>>(emptyList())
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun attach(c: MediaController) { controller = c; c.addListener(listener); refresh() }
    fun detach() { controller?.removeListener(listener); controller = null }

    fun refresh() {
        val c = controller ?: return
        playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        current = c.currentMediaItem
        currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        position = c.currentPosition; positionAt = android.os.SystemClock.elapsedRealtime()
        duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        val t = c.currentTimeline
        val list = ArrayList<Pair<Int, MediaItem>>()
        if (!t.isEmpty && c.currentMediaItemIndex >= 0) {
            var i = t.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && list.size < 300) {
                list += i to c.getMediaItemAt(i)
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        upNext = list
    }

    fun play(songs: List<Song>, index: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        val start = if (shuffled) songs.indices.random() else index
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0)
        c.prepare(); c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song), 0)
        c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), song.toMediaItem())
    }

    fun playLast(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song), 0)
        c.addMediaItem(song.toMediaItem())
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun previous() { controller?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun next() { controller?.seekToNextMediaItem() }
    fun seek(ms: Long) { controller?.seekTo(ms); position = ms; positionAt = android.os.SystemClock.elapsedRealtime() }

    /** Where the song is right now, smoothly (for beat effects). */
    fun now(): Long = if (playing) position + (android.os.SystemClock.elapsedRealtime() - positionAt) else position
}

/** Puts a Music icon on the home screen that opens the player straight away. */
fun addHomeShortcut(ctx: Context) {
    val intent = android.content.Intent(ctx, MusicActivity::class.java).setAction(android.content.Intent.ACTION_MAIN)
    val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "music")
        .setShortLabel("Music")
        .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, helium314.keyboard.latin.R.drawable.ic_music_shortcut))
        .setIntent(intent).build()
    val ok = runCatching { androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    if (!ok) android.widget.Toast.makeText(ctx, "Your home screen doesn't allow shortcuts", android.widget.Toast.LENGTH_SHORT).show()
}

object Favourites {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    val ids = mutableStateOf<Set<Long>>(emptySet())
    fun load(ctx: Context) { ids.value = prefs(ctx).getStringSet("favs", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet() }
    fun toggle(ctx: Context, id: Long) {
        ids.value = if (id in ids.value) ids.value - id else ids.value + id
        prefs(ctx).edit().putStringSet("favs", ids.value.map { it.toString() }.toSet()).apply()
    }
}

internal sealed class Page {
    data object Home : Page()
    data object Songs : Page()
    data object Albums : Page()
    data object Artists : Page()
    data object Favs : Page()
    data class AlbumPage(val id: Long) : Page()
    data class ArtistPage(val name: String) : Page()
    data object Settings : Page()
    data object Fixer : Page()
    data object Playlists : Page()
    data object Import : Page()
    data class PlaylistPage(val id: Long) : Page()
}

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MusicApp(ui: PlayerUi) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (!allowed) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(MusicIcons.note, null, tint = Accent, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Music", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("To play the songs on your phone, Music needs to see your music files. Nothing leaves your phone.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { ask.launch(audioPermission()) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Allow access to music") }
        }
        return
    }
    var lib by remember { mutableStateOf(Library.cached) }
    val appScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Favourites.load(ctx); MusicSettings.load(ctx); Playlists.load(ctx); lib = Library.load(ctx, fresh = true) }
    // keep the seek bar moving
    LaunchedEffect(ui.controller) { while (true) { ui.controller?.let { ui.position = it.currentPosition; ui.positionAt = android.os.SystemClock.elapsedRealtime() }; delay(250) } }

    val stack = remember { mutableStateListOf<Page>(Page.Home) }
    var tab by remember { mutableIntStateOf(0) }
    var nowPlaying by remember { mutableStateOf(false) }
    val library = lib
    BackHandler(enabled = nowPlaying || (tab == 0 && stack.size > 1) || tab == 1) {
        when {
            nowPlaying -> nowPlaying = false
            tab == 1 -> tab = 0
            else -> stack.removeAt(stack.lastIndex)
        }
    }
    fun open(p: Page) { tab = 0; stack.add(p); nowPlaying = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (library == null) Text("Finding your music…", Modifier.align(Alignment.Center))
                else if (library.songs.isEmpty()) Text("No music found on this phone yet.\nCopy songs to the Music folder and open this again.", Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
                else if (tab == 1) SearchPage(library, ui, ::open)
                else when (val p = stack.last()) {
                    Page.Home -> HomePage(library, ::open)
                    Page.Songs -> SongsPage("Songs", library.songs, ui, library, ::open, onBack = { stack.removeAt(stack.lastIndex) })
                    Page.Favs -> SongsPage("Favourites", library.songs.filter { it.id in Favourites.ids.value }, ui, library, ::open, onBack = { stack.removeAt(stack.lastIndex) })
                    Page.Albums -> AlbumsPage("Albums", library.albums, ::open) { stack.removeAt(stack.lastIndex) }
                    Page.Playlists -> PlaylistsPage(library, ::open) { stack.removeAt(stack.lastIndex) }
                    Page.Import -> ImportPage(library, ::open) { stack.removeAt(stack.lastIndex) }
                    is Page.PlaylistPage -> PlaylistPage(p.id, library, ui, ::open) { stack.removeAt(stack.lastIndex) }
                    Page.Settings -> SettingsPage(::open) { stack.removeAt(stack.lastIndex) }
                    Page.Fixer -> FixerPage(library, onBack = { stack.removeAt(stack.lastIndex) }) { appScope.launch { lib = Library.load(ctx, fresh = true) } }
                    Page.Artists -> ArtistsPage(library, ::open) { stack.removeAt(stack.lastIndex) }
                    is Page.AlbumPage -> library.albums.find { it.id == p.id }?.let { AlbumPage(it, ui, library, ::open) { stack.removeAt(stack.lastIndex) } }
                    is Page.ArtistPage -> library.artists.find { it.name == p.name }?.let { ArtistPage(it, ui, library, ::open) { stack.removeAt(stack.lastIndex) } }
                }
            }
            if (ui.current != null) MiniPlayer(ui, library) { nowPlaying = true }
            BottomTabs(tab) { if (it == 0 && tab == 0) { stack.clear(); stack.add(Page.Home) }; tab = it }
        }
        AnimatedVisibility(nowPlaying, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
            NowPlaying(ui, library, onClose = { nowPlaying = false }, open = ::open)
        }
        PlaylistPickerDialog()
    }
}

@Composable
private fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(MusicIcons.library to "Library", MusicIcons.search to "Search").forEachIndexed { i, (icon, label) ->
                val col = if (i == tab) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTab(i) }.padding(horizontal = 28.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, label, tint = col, modifier = Modifier.size(26.dp))
                    Text(label, fontSize = 11.sp, color = col)
                }
            }
        }
    }
}

@Composable
internal fun Cover(song: Song?, size: Int, modifier: Modifier, corner: Int = 8) {
    val ctx = LocalContext.current
    val bmp by produceState(song?.let { Covers.cached(it, size) }, song?.albumId, song?.artFile, size) { value = song?.let { Covers.load(ctx, it, size) } }
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(MusicIcons.note, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.4f))
    }
}

@Composable
internal fun Header(title: String, onBack: (() -> Unit)?, onSettings: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
        if (onSettings != null) Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onSettings) { Icon(MusicIcons.settings, "Settings", tint = Accent) }
        } else if (onBack != null) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MusicIcons.back, "Back", tint = Accent, modifier = Modifier.size(20.dp)); Text("Library", color = Accent, fontSize = 17.sp)
        } else Spacer(Modifier.height(36.dp))
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomePage(lib: Library, open: (Page) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(span = { GridItemSpan(2) }) { Header("Library", null, onSettings = { open(Page.Settings) }) }
        val rows = listOf(
            Triple("Playlists", MusicIcons.queue, Page.Playlists), Triple("Favourites", MusicIcons.heart, Page.Favs), Triple("Artists", MusicIcons.person, Page.Artists),
            Triple("Albums", MusicIcons.album, Page.Albums), Triple("Songs", MusicIcons.note, Page.Songs),
        )
        rows.forEach { (label, icon, page) ->
            item(span = { GridItemSpan(2) }) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { open(page) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    }
                    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
        item(span = { GridItemSpan(2) }) { Text("Recently Added", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
        items(lib.recentlyAdded, key = { it.id }) { AlbumTile(it, open) }
        item(span = { GridItemSpan(2) }) {
            val ctx = LocalContext.current
            TextButton({ addHomeShortcut(ctx) }, Modifier.padding(8.dp)) { Text("Add Music to Home screen", color = Accent) }
        }
    }
}

@Composable
private fun AlbumTile(a: Album, open: (Page) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { open(Page.AlbumPage(a.id)) }.padding(8.dp)) {
        Cover(a.cover, 400, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(a.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(a.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}

@Composable
private fun AlbumsPage(title: String, albums: List<Album>, open: (Page) -> Unit, onBack: () -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(2) }) { Header(title, onBack) }
        items(albums, key = { it.id }) { AlbumTile(it, open) }
    }
}

@Composable
private fun ArtistsPage(lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Artists", onBack) }
        items(lib.artists, key = { it.name }) { a ->
            Row(Modifier.fillMaxWidth().clickable { open(Page.ArtistPage(a.name)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(a.songs.first(), 160, Modifier.size(52.dp), corner = 26)
                Spacer(Modifier.width(14.dp))
                Text(a.name, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
internal fun PlayShuffle(songs: List<Song>, ui: PlayerUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        listOf(Triple("Play", MusicIcons.play, false), Triple("Shuffle", MusicIcons.shuffle, true)).forEach { (label, icon, sh) ->
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg).clickable { ui.play(songs, 0, sh) }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(label, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongRow(song: Song, ui: PlayerUi, lib: Library, open: (Page) -> Unit, number: Int? = null,
                     extra: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val isCurrent = ui.currentId == song.id
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (number != null) Text(if (isCurrent) "♪" else "$number", Modifier.width(28.dp), color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            else { Cover(song, 160, Modifier.size(48.dp), corner = 6); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface)
                Text(if (number != null) time(song.durationMs) else "${song.artist} · ${song.album}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            if (song.id in Favourites.ids.value) Icon(MusicIcons.heart, null, tint = Accent, modifier = Modifier.size(14.dp))
            IconButton(onClick = { menu = true }) { Icon(MusicIcons.more, "More", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Play Next") }, { ui.playNext(song); menu = false })
            DropdownMenuItem({ Text("Play Last") }, { ui.playLast(song); menu = false })
            DropdownMenuItem({ Text("Add to Playlist…") }, { PlaylistPicker.songs.value = listOf(song.id); menu = false })
            extra.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { menu = false; action() }) }
            DropdownMenuItem({ Text(if (song.id in Favourites.ids.value) "Remove from Favourites" else "Favourite") }, { Favourites.toggle(ctx, song.id); menu = false })
            DropdownMenuItem({ Text("Go to Album") }, { open(Page.AlbumPage(song.albumId)); menu = false })
            DropdownMenuItem({ Text("Go to Artist") }, { open(Page.ArtistPage(song.albumArtist.ifBlank { song.artist })); menu = false })
        }
    }
}

@Composable
private fun SongsPage(title: String, songs: List<Song>, ui: PlayerUi, lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(title, onBack) }
        if (songs.isEmpty()) item { Text(if (title == "Favourites") "Tap ♥ on a song you love and it shows up here." else "No songs.", Modifier.padding(16.dp)) }
        else item { PlayShuffle(songs, ui) }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s -> SongRow(s, ui, lib, open) { ui.play(songs, i) } }
    }
}

@Composable
private fun AlbumPage(a: Album, ui: PlayerUi, lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("", onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(a.cover, 600, Modifier.size(250.dp).shadow(18.dp, RoundedCornerShape(10.dp)), corner = 10)
                Spacer(Modifier.height(14.dp))
                Text(a.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Text(a.artist, fontSize = 20.sp, color = Accent, modifier = Modifier.clickable { open(Page.ArtistPage(a.artist)) })
                if (a.year > 0) Text("${a.year}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
            }
        }
        item { PlayShuffle(a.songs, ui) }
        itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, ui, lib, open, number = s.track.takeIf { it > 0 } ?: (i + 1)) { ui.play(a.songs, i) } }
        item {
            val mins = a.songs.sumOf { it.durationMs } / 60000
            Text("${a.songs.size} songs, $mins minutes", Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ArtistPage(a: Artist, ui: PlayerUi, lib: Library, open: (Page) -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(a.name, onBack) }
        item { PlayShuffle(a.songs, ui) }
        if (a.albums.isNotEmpty()) item {
            Text("Albums", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) { items(a.albums, key = { it.id }) { Box(Modifier.width(170.dp)) { AlbumTile(it, open) } } }
        }
        item { Text("Songs", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        itemsIndexed(a.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, ui, lib, open) { ui.play(a.songs, i) } }
    }
}

@Composable
private fun SearchPage(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    var q by remember { mutableStateOf("") }
    val (songs, albums, artists) = remember(q, lib) { lib.search(q) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Search", null) }
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                placeholder = { Text("Artists, Songs, Albums") }, leadingIcon = { Icon(MusicIcons.search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(MusicIcons.close, "Clear") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = RoundedCornerShape(12.dp))
        }
        if (artists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            items(artists, key = { "ar" + it.name }) { a ->
                Row(Modifier.fillMaxWidth().clickable { open(Page.ArtistPage(a.name)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(a.songs.first(), 160, Modifier.size(48.dp), corner = 24); Spacer(Modifier.width(12.dp)); Text(a.name, fontSize = 16.sp)
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { SectionTitle("Albums") }
            items(albums, key = { "al" + it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { open(Page.AlbumPage(a.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(a.cover, 160, Modifier.size(48.dp), corner = 6); Spacer(Modifier.width(12.dp))
                    Column { Text(a.title, fontSize = 16.sp, maxLines = 1); Text("Album · ${a.artist}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1) }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            itemsIndexed(songs, key = { _, s -> "s" + s.id }) { i, s -> SongRow(s, ui, lib, open) { ui.play(songs, i) } }
        }
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))

private fun songOf(ui: PlayerUi, lib: Library?): Song? = lib?.byId?.get(ui.currentId) ?: ui.current?.let { m ->
    Song(ui.currentId, m.mediaMetadata.title?.toString() ?: "", m.mediaMetadata.artist?.toString() ?: "", m.mediaMetadata.albumTitle?.toString() ?: "",
        -1, "", 0, 0, 0, 0, 0, "")
}

@Composable
private fun MiniPlayer(ui: PlayerUi, lib: Library?, onOpen: () -> Unit) {
    val song = songOf(ui, lib) ?: return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.clickable { onOpen() }.pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -20) onOpen() } }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(song, 160, Modifier.size(44.dp), corner = 6)
            Spacer(Modifier.width(12.dp))
            Text(song.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            IconButton(onClick = ui::toggle) { Icon(if (ui.playing) MusicIcons.pause else MusicIcons.play, "Play or pause", Modifier.size(30.dp)) }
            IconButton(onClick = ui::next) { Icon(MusicIcons.next, "Next", Modifier.size(28.dp)) }
        }
    }
}

/** The full screen player: big cover, moving blurred background, lyrics and Up Next. */
@Composable
private fun NowPlaying(ui: PlayerUi, lib: Library?, onClose: () -> Unit, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val song = songOf(ui, lib)
    var mode by remember { mutableIntStateOf(0) } // 0 cover, 1 lyrics, 2 up next
    var drag by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val bmp by produceState<Bitmap?>(null, song?.id, song?.artFile) { value = song?.let { Covers.load(ctx, it, 600) } }
    BackHandler(enabled = mode != 0) { mode = 0 }
    // beat effects (test 234): beats worked out once per song, then everything moves with the song's position
    val beatMap = rememberBeatMap(song)
    val clock = rememberBeatClock(ui, MusicSettings.effectsOn && beatMap != null && ui.playing)
    val tint = remember(bmp) { bmp?.let { averageColor(it) } ?: Color(0xFF8E8E93) }

    Box(Modifier.fillMaxSize().offset { IntOffset(0, drag.roundToInt()) }.background(Color(0xFF1C1C1E))
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { if (drag > with(density) { 140.dp.toPx() }) onClose(); drag = 0f },
                onDragCancel = { drag = 0f }) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
        }) {
        MovingBackground(bmp)
        BeatBackground(beatMap, clock, tint, if (mode == 0) 0.38f else 0.12f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
            }
            when (mode) {
                0 -> {
                    val scale by animateFloatAsState(if (ui.playing) 1f else 0.8f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val liftPx = with(density) { 16.dp.toPx() }
                        // cover size from Settings > Customise (test 236); bigger than 100% uses the side margins
                        val side = minOf(maxWidth * MusicSettings.coverSize, maxHeight)
                        if (MusicSettings.effectsOn && MusicSettings.cover == 3) Box(Modifier.requiredSize(side).graphicsLayer {
                            val m = coverMove(beatMap, clock.ms)
                            alpha = m.glow; scaleX = scale * (1.04f + 0.14f * m.glow); scaleY = scaleX
                        }.blur(36.dp).clip(RoundedCornerShape(24.dp)).background(tint.copy(alpha = 1f)))
                        Cover(song, 600, Modifier.requiredSize(side).graphicsLayer {
                            val m = coverMove(beatMap, clock.ms)
                            scaleX = scale * m.scale; scaleY = scale * m.scale
                            rotationZ = m.rotation; translationY = m.lift * liftPx
                        }.shadow((24 * scale).dp, RoundedCornerShape(12.dp)), corner = 12)
                    }
                    TitleRow(song, ui, open, onClose)
                }
                1 -> { SmallHeader(song, ui, open, onClose); LyricsView(song, ui, Modifier.weight(1f)) }
                else -> { SmallHeader(song, ui, open, onClose); UpNext(ui, lib, Modifier.weight(1f)) }
            }
            SeekBar(ui)
            Controls(ui)
            if (mode == 0) VolumeBar()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ModeButton(MusicIcons.lyrics, "Lyrics", mode == 1) { mode = if (mode == 1) 0 else 1 }
                ModeButton(MusicIcons.queue, "Up Next", mode == 2) { mode = if (mode == 2) 0 else 2 }
            }
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White.copy(alpha = 0.85f) else Color.Transparent).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (on) Color(0xFF333333) else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
    }
}

/** The cover, huge and blurred, slowly turning behind everything, like Apple Music's animated background. */
@Composable
private fun MovingBackground(bmp: Bitmap?) {
    val t = rememberInfiniteTransition(label = "bg")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "turn")
    val breathe by t.animateFloat(1.6f, 2.0f, infiniteRepeatable(tween(14_000), RepeatMode.Reverse), label = "breathe")
    if (bmp == null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4A4A52), Color(0xFF1C1C1E)))))
        return
    }
    val img = remember(bmp) { bmp.asImageBitmap() }
    val avg = remember(bmp) { averageColor(bmp) }
    Box(Modifier.fillMaxSize().background(avg))
    if (Build.VERSION.SDK_INT >= 31) {
        Image(img, null, Modifier.fillMaxSize().graphicsLayer { rotationZ = turn; scaleX = breathe; scaleY = breathe }.blur(90.dp), contentScale = ContentScale.Crop)
        Image(img, null, Modifier.fillMaxSize().graphicsLayer { rotationZ = -turn * 1.3f; scaleX = breathe * 0.8f; scaleY = breathe * 0.8f; alpha = 0.6f }.blur(110.dp), contentScale = ContentScale.Crop)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.45f)))))
}

private fun averageColor(b: Bitmap): Color {
    val s = Bitmap.createScaledBitmap(b, 4, 4, true)
    var r = 0; var g = 0; var bl = 0
    for (x in 0 until 4) for (y in 0 until 4) { val p = s.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255 }
    return Color(r / 16, g / 16, bl / 16).copy(alpha = 1f).let { Color(it.red * 0.7f, it.green * 0.7f, it.blue * 0.7f) }
}

@Composable
private fun TitleRow(song: Song?, ui: PlayerUi, open: (Page) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.65f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { song?.let { open(Page.ArtistPage(it.albumArtist.ifBlank { it.artist })) } })
        }
        song?.let { s ->
            val fav = s.id in Favourites.ids.value
            IconButton({ Favourites.toggle(ctx, s.id) }) { Icon(if (fav) MusicIcons.heart else MusicIcons.heartOutline, "Favourite", tint = if (fav) Accent else Color.White) }
        }
        MoreMenu(song, open)
    }
}

@Composable
private fun SmallHeader(song: Song?, ui: PlayerUi, open: (Page) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(song, 160, Modifier.size(60.dp).shadow(8.dp, RoundedCornerShape(6.dp)), corner = 6)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MoreMenu(song, open)
    }
}

@Composable
private fun MoreMenu(song: Song?, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val sleepAt by MusicState.sleepAt.collectAsState()
    Box {
        IconButton({ menu = true }) { Icon(MusicIcons.more, "More", tint = Color.White) }
        DropdownMenu(menu, { menu = false }) {
            song?.takeIf { it.durationMs > 0 }?.let { s ->
                DropdownMenuItem({ Text("Go to Album") }, { menu = false; open(Page.AlbumPage(s.albumId)) })
                DropdownMenuItem({ Text("Go to Artist") }, { menu = false; open(Page.ArtistPage(s.albumArtist.ifBlank { s.artist })) })
            }
            DropdownMenuItem({ Text(if (sleepAt != 0L) "Sleep timer (on)" else "Sleep timer") }, { menu = false; sleep = true })
            DropdownMenuItem({ Text("Beat effects") }, { menu = false; open(Page.Settings) })
            song?.takeIf { it.durationMs > 0 }?.let { s -> DropdownMenuItem({ Text("Add to Playlist…") }, { menu = false; PlaylistPicker.songs.value = listOf(s.id) }) }
            DropdownMenuItem({ Text("Add to Home screen") }, { menu = false; addHomeShortcut(ctx) })
        }
        DropdownMenu(sleep, { sleep = false }) {
            listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", -1 to "End of this song").forEach { (m, label) ->
                DropdownMenuItem({ Text(label) }, {
                    MusicState.sleepAt.value = when { m == 0 -> 0L; m < 0 -> -1L; else -> System.currentTimeMillis() + m * 60_000L }
                    sleep = false
                })
            }
        }
    }
}

@Composable
private fun SeekBar(ui: PlayerUi) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val dur = ui.duration.coerceAtLeast(1)
    val frac = dragFrac ?: (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    val h by animateDpAsState(if (dragFrac != null) 12.dp else 6.dp, label = "seek")
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { ui.seek((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(dur) { detectTapGestures { o -> ui.seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } },
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(frac).height(h).clip(CircleShape).background(Color.White.copy(alpha = if (dragFrac != null) 1f else 0.75f)))
        }
        val shown = ((dragFrac ?: frac) * dur).toLong()
        Row(Modifier.fillMaxWidth()) {
            Text(time(shown), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("-" + time(ui.duration - shown), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val k = MusicSettings.buttonSize
        Press(MusicIcons.previous, "Previous", (44 * k).toInt()) { ui.previous() }
        Press(if (ui.playing) MusicIcons.pause else MusicIcons.play, "Play or pause", (64 * k).toInt()) { ui.toggle() }
        Press(MusicIcons.next, "Next", (44 * k).toInt()) { ui.next() }
    }
}

@Composable
private fun Press(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) 0.82f else 1f, label = "press")
    Box(Modifier.size((size + 24).dp).scale(s).clip(CircleShape).background(if (down) Color.White.copy(alpha = 0.15f) else Color.Transparent)
        .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { onClick() }) },
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun VolumeBar() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    LaunchedEffect(Unit) { while (true) { delay(800); vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max } }
    fun set(f: Float) { vol = f.coerceIn(0f, 1f); runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * max).roundToInt(), 0) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(MusicIcons.volumeLow, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        BoxWithConstraints(Modifier.weight(1f).height(28.dp).padding(horizontal = 10.dp)
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x / size.width) } }
            .pointerInput(Unit) { detectTapGestures { o -> set(o.x / size.width) } }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(vol).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.75f)))
        }
        Icon(MusicIcons.volumeHigh, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LyricsView(song: Song?, ui: PlayerUi, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val embedded by MusicState.embeddedLyrics.collectAsState()
    var lyrics by remember(song?.id) { mutableStateOf<Lyrics?>(null) }
    var looked by remember(song?.id) { mutableStateOf(false) }
    var searching by remember(song?.id) { mutableStateOf(false) }
    var notFound by remember(song?.id) { mutableStateOf(false) }
    LaunchedEffect(song?.id, embedded) {
        if (song == null) return@LaunchedEffect
        lyrics = Lyrics.local(ctx, song)
            ?: embedded?.takeIf { it.first == song.id }?.second?.let { Lyrics.parse(it, "In the song file") }
        looked = true
    }
    val l = lyrics
    Box(modifier.fillMaxWidth()) {
        if (l == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!looked) return@Column
                Text(if (notFound) "Couldn't find lyrics for this song." else "No lyrics for this song yet.", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                if (song != null && !notFound) Button(enabled = !searching, onClick = {
                    searching = true
                    scope.launch { val found = Lyrics.online(ctx, song); lyrics = found; notFound = found == null; searching = false }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)) {
                    Text(if (searching) "Looking…" else "Find lyrics online")
                }
                Spacer(Modifier.height(8.dp))
                Text("Looks up the song's name and artist on LRCLIB.net, a free lyrics site. Saved on your phone after.", color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
            }
        } else {
            val state = rememberLazyListState()
            val cur = l.lineAt(ui.position)
            val viewH = remember { mutableIntStateOf(0) }
            LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem(cur, -(viewH.intValue / 3)) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                viewH.intValue = constraints.maxHeight
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 40.dp)) {
                    itemsIndexed(l.lines) { i, line ->
                        val on = !l.synced || i == cur
                        val a by animateFloatAsState(if (on) 1f else 0.32f, tween(300), label = "line")
                        val s by animateFloatAsState(if (i == cur) 1.04f else 1f, tween(300), label = "lineScale")
                        Text(line.text.ifBlank { "♪" }, color = Color.White, fontSize = if (l.synced) 28.sp else 20.sp, fontWeight = FontWeight.Bold,
                            lineHeight = if (l.synced) 34.sp else 28.sp,
                            modifier = Modifier.fillMaxWidth().alpha(a).graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                                .clickable(enabled = l.synced) { ui.seek(line.timeMs) }.padding(vertical = 10.dp))
                    }
                    item { Text("Lyrics: ${l.source}", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UpNext(ui: PlayerUi, lib: Library?, modifier: Modifier) {
    val c = ui.controller
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(MusicIcons.shuffle, "Shuffle", ui.shuffle, Modifier.weight(1f)) { c?.shuffleModeEnabled = !ui.shuffle }
            Pill(if (ui.repeat == Player.REPEAT_MODE_ONE) MusicIcons.repeatOne else MusicIcons.repeat,
                when (ui.repeat) { Player.REPEAT_MODE_ONE -> "Repeat 1"; Player.REPEAT_MODE_ALL -> "Repeat"; else -> "Repeat" },
                ui.repeat != Player.REPEAT_MODE_OFF, Modifier.weight(1f)) {
                c?.repeatMode = when (ui.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            }
        }
        Text("Playing Next", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        if (ui.upNext.isEmpty()) Text("Nothing else in the queue.", color = Color.White.copy(alpha = 0.6f))
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.upNext, key = { it.first.toString() + "/" + it.second.mediaId }) { (index, item) ->
                val s = lib?.byId?.get(item.mediaId.toLongOrNull() ?: -1)
                Row(Modifier.fillMaxWidth().clickable { c?.seekToDefaultPosition(index); c?.play() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(s, 160, Modifier.size(44.dp), corner = 5)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.mediaMetadata.title?.toString() ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.mediaMetadata.artist?.toString() ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ c?.removeMediaItem(index) }) { Icon(MusicIcons.close, "Remove", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.15f)).clickable { onClick() }.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val col = if (on) Color(0xFF333333) else Color.White
        Icon(icon, null, tint = col, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, color = col, fontWeight = FontWeight.SemiBold)
    }
}
