// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.Intent
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

/** the main colour: pink-red, or the chosen theme's colour (test 239) */
internal val Accent: Color get() = WavesThemes.of(MusicSettings.theme)?.accent ?: Color(0xFFFA2D48)

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
        MusicSettings.load(this)
        OpenWavesIconWidget.refreshAll(this)
        setContent {
            val themed = MusicSettings.theme != -1
            val dark = isSystemInDarkTheme() || themed
            MaterialTheme(colorScheme = if (themed) darkColorScheme(primary = Accent, background = Color.Transparent, surface = Color(0xFF1C1C1E),
                    surfaceContainer = Color(0xFF232327), surfaceContainerHigh = Color(0xFF2A2A2E), surfaceContainerHighest = Color(0xFF303035), surfaceContainerLow = Color(0xFF1C1C1F))
                else if (dark) darkColorScheme(primary = Accent, background = Color.Black, surface = Color(0xFF111111), surfaceContainer = Color(0xFF17171A))
                else lightColorScheme(primary = Accent, background = Color(0xFFF2F2F5), surface = Color.White, surfaceContainer = Color.White)) {
                Box(Modifier.fillMaxSize().background(if (themed) Color.Black else MaterialTheme.colorScheme.background)) {
                    if (themed) ThemeBackground(MusicSettings.theme)
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = if (dark) Color.White else Color.Black) { MusicApp(ui) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MusicState.appVisible = true; MusicState.floatClosed = false
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicService::class.java))).buildAsync()
        future = f
        f.addListener({ runCatching { ui.attach(f.get()) } }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        MusicState.appVisible = false
        ui.detach()
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

/** What the screen shows about playback, kept in step with the player. */
class PlayerUi {
    var controller by mutableStateOf<Player?>(null)
    var playing by mutableStateOf(false)
    var currentId by mutableLongStateOf(-1L)
    var current by mutableStateOf<MediaItem?>(null)
    /** test 369: live ICY song title broadcast by an internet radio stream, if available */
    var liveTitle by mutableStateOf("")
    val isLiveStream: Boolean get() = current?.mediaId?.startsWith("radio:") == true
    val isPodcast: Boolean get() = current?.mediaId?.startsWith("pod:") == true
    val isAudiobook: Boolean get() = current?.mediaId?.startsWith("book:") == true
    val isLongForm: Boolean get() = isPodcast || isAudiobook
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

    fun attach(c: Player) { controller = c; c.addListener(listener); refresh() }
    fun detach() { controller?.removeListener(listener); controller = null }

    fun refresh() {
        val c = controller ?: return
        playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        current = c.currentMediaItem
        currentId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        liveTitle = if (c.currentMediaItem?.mediaId?.startsWith("radio:") == true) {
            c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() && it != c.currentMediaItem?.mediaMetadata?.title?.toString() } ?: ""
        } else ""
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

    /** [shuffled] null keeps shuffle as you left it (test 308, Jarrod: shuffle turned itself off in the next playlist). */
    fun play(songs: List<Song>, index: Int, shuffled: Boolean? = null) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        Radio.stop() // playing something else ends the radio (Radio.start turns it back on after)
        val isShuffled = shuffled ?: c.shuffleModeEnabled
        c.shuffleModeEnabled = isShuffled
        val items = songs.map { it.toMediaItem() }
        // test 352 (Jarrod): when Shuffle is on and starting a mix/playlist (or tapping Shuffle), use resetPosition=true
        // so ExoPlayer starts at the FIRST song of its ShuffleOrder instead of a random mid/end spot that stops after 1-2 songs!
        if (shuffled == true || (isShuffled && index == 0)) {
            c.setMediaItems(items, true)
        } else {
            c.setMediaItems(items, index, 0)
        }
        c.prepare(); c.play()
    }

    /** test 369 (Jarrod): stream a live internet radio station through the same player/service. */
    fun playStream(name: String, subtitle: String, url: String, id: String, artworkUrl: String = "") {
        val c = controller ?: return
        Radio.stop()
        Announcer.hush()
        val md = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(subtitle)
            .apply { if (artworkUrl.startsWith("http")) setArtworkUri(android.net.Uri.parse(artworkUrl)) }
            .build()
        val item = MediaItem.Builder()
            .setMediaId("radio:$id")
            .setUri(url)
            .setMediaMetadata(md)
            .build()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** test 370 (Jarrod): play or resume a podcast episode (uses offline file if downloaded, otherwise streams). */
    fun playPodcast(ep: PodcastEpisode, localFile: java.io.File?, resumeMs: Long) {
        val c = controller ?: return
        Radio.stop()
        Announcer.hush()
        val md = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(ep.title)
            .setArtist(ep.showTitle)
            .apply { if (ep.artworkUrl.startsWith("http")) setArtworkUri(android.net.Uri.parse(ep.artworkUrl)) }
            .build()
        val uri = if (localFile != null) android.net.Uri.fromFile(localFile) else android.net.Uri.parse(ep.audioUrl)
        val item = MediaItem.Builder()
            .setMediaId("pod:${ep.id}")
            .setUri(uri)
            .setMediaMetadata(md)
            .build()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        if (resumeMs > 5_000L) c.setMediaItem(item, resumeMs) else c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    /** test 371 (Jarrod): queue all chapters of an audiobook seamlessly from [startIdx] (using offline files when downloaded). */
    fun playAudiobook(ctx: Context, book: Audiobook, chapters: List<BookChapter>, startIdx: Int, resumeMs: Long) {
        val c = controller ?: return
        if (chapters.isEmpty()) return
        Radio.stop()
        Announcer.hush()
        val items = chapters.mapIndexed { idx, ch ->
            val offline = if (book.isLocal) null else AudiobookStore.downloadedFile(ctx, book.id, idx)
            val uri = when {
                offline != null -> android.net.Uri.fromFile(offline)
                else -> android.net.Uri.parse(ch.audioUrl)
            }
            val md = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(ch.title)
                .setArtist(book.title)
                .setAlbumTitle(book.author)
                .build()
            MediaItem.Builder()
                .setMediaId("book:${book.id}:$idx")
                .setUri(uri)
                .setMediaMetadata(md)
                .build()
        }
        val idx = startIdx.coerceIn(0, items.lastIndex)
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setMediaItems(items, idx, if (resumeMs > 5_000L) resumeMs else 0L)
        c.prepare()
        c.play()
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

/** Puts an Open Waves icon on the home screen that opens the player straight away. */
fun addHomeShortcut(ctx: Context) = OpenWavesIcon.add(ctx)

/**
 * Test 245 (Jarrod): a pinned shortcut always gets the launcher's little badge in the corner, so Open Waves gets a
 * real app icon instead. It's an activity-alias that stays off until asked for; switching it on puts Open Waves in
 * the phone's apps list with its neon icon, ready to drag to the Home screen.
 */
object OpenWavesIcon {
    private const val ALIAS = "helium314.keyboard.music.OpenWavesLauncher"
    private fun cn(ctx: Context) = android.content.ComponentName(ctx.packageName, ALIAS)

    fun isOn(ctx: Context) = runCatching {
        ctx.packageManager.getComponentEnabledSetting(cn(ctx)) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(cn(ctx),
                if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * Test 286 (Jarrod: still nothing on the home screen): Samsung only puts new icons on the home screen for newly installed
     * apps, so Open Waves offers a 1x1 home screen widget that looks just like an app icon (no shortcut badge). Android
     * asks "Add to Home screen?" and places it. The app icon in the apps list stays too.
     */
    fun add(ctx: Context) {
        set(ctx, true)
        val app = ctx.applicationContext
        val awm = android.appwidget.AppWidgetManager.getInstance(app)
        val ok = android.os.Build.VERSION.SDK_INT >= 26 && runCatching {
            awm.isRequestPinAppWidgetSupported &&
                awm.requestPinAppWidget(android.content.ComponentName(app, OpenWavesIconWidget::class.java), null, null)
        }.getOrDefault(false)
        if (!ok) android.widget.Toast.makeText(app, "Open Waves is in your apps list. Press and hold it there, then drag it to your Home screen.",
            android.widget.Toast.LENGTH_LONG).show()
    }
}

/** The badge-free home screen icon: tap it to open Open Waves. Re-binds automatically after APK updates and reboots. */
class OpenWavesIconWidget : android.appwidget.AppWidgetProvider() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            refreshAll(ctx)
            // Android's AppWidgetService often resets RemoteViews to initialLayout shortly after MY_PACKAGE_REPLACED;
            // re-bind again at +2 s and +5 s so our click PendingIntent always wins the race.
            val app = ctx.applicationContext
            val pending = goAsync()
            android.os.Handler(android.os.Looper.getMainLooper()).apply {
                postDelayed({ refreshAll(app) }, 2_000L)
                postDelayed({ refreshAll(app); runCatching { pending.finish() } }, 5_000L)
            }
        }
    }

    override fun onUpdate(ctx: Context, awm: android.appwidget.AppWidgetManager, ids: IntArray) {
        val open = android.app.PendingIntent.getActivity(ctx, 7,
            android.content.Intent(ctx, MusicActivity::class.java)
                .setAction(android.content.Intent.ACTION_MAIN)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val views = android.widget.RemoteViews(ctx.packageName, helium314.keyboard.latin.R.layout.open_waves_icon_widget)
        views.setOnClickPendingIntent(helium314.keyboard.latin.R.id.ow_icon_root, open)
        views.setOnClickPendingIntent(helium314.keyboard.latin.R.id.ow_icon_img, open)
        awm.updateAppWidget(ids, views)
    }

    companion object {
        @JvmStatic
        fun refreshAll(ctx: Context) {
            runCatching {
                val app = ctx.applicationContext
                val awm = android.appwidget.AppWidgetManager.getInstance(app)
                val ids = awm.getAppWidgetIds(android.content.ComponentName(app, OpenWavesIconWidget::class.java))
                if (ids.isNotEmpty()) OpenWavesIconWidget().onUpdate(app, awm, ids)
            }
        }
    }
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
    data object Search : Page()
    data object Edit : Page()
    /** "added", "most" or "recent" */
    data class Smart(val kind: String) : Page()
    data class FolderPage(val path: String) : Page()
    /** ringtone cutter for one song (test 239) */
    data class Ringtone(val id: Long) : Page()
    data object Themes : Page()
    /** Design studio (test 264): every look setting in one place */
    data object Studio : Page()
    /** "Add artwork" for one song, from the Now Playing menu (test 267) */
    data class FixOne(val id: Long) : Page()
    /** test 311: Open Waves' own equaliser */
    data object Equaliser : Page()
    data class SmartListPage(val id: Long) : Page()
    data object Year : Page()
    data object Car : Page()
    data class Stems(val id: Long) : Page()
}

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicApp(ui: PlayerUi) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (!allowed) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(helium314.keyboard.latin.R.drawable.open_waves_mark), null, Modifier.height(80.dp).aspectRatio(384f / 232f))
            Spacer(Modifier.height(16.dp))
            Text("Open Waves", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("To play the songs on your phone, Open Waves needs to see your music files. Nothing leaves your phone.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { ask.launch(audioPermission()) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Allow access to music") }
        }
        return
    }
    var lib by remember { mutableStateOf(Library.cached) }
    val appScope = rememberCoroutineScope()
    fun reload() { appScope.launch { lib = Library.load(ctx, fresh = true) } }
    // Android's own "Allow Open Waves to delete?" box (test 238)
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) reload() }
    DisposableEffect(Unit) {
        SongActions.askDelete = { sender -> runCatching { deleter.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) } }
        SongActions.afterDelete = { reload() }
        onDispose { SongActions.askDelete = null; SongActions.afterDelete = null }
    }
    // test 307: "Not music, hide it" asks Android before marking the file
    val markWriter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        NotMusic.writeAnswered(ctx, it.resultCode == android.app.Activity.RESULT_OK)
    }
    DisposableEffect(Unit) {
        NotMusic.askWrite = { sender -> markWriter.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
        NotMusic.afterHide = { reload() }
        onDispose { NotMusic.askWrite = null; NotMusic.afterHide = null }
    }
    // test 311: the tag editor asks Android before changing the file too
    val tagWriter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        TagEdit.writeAnswered(ctx, it.resultCode == android.app.Activity.RESULT_OK)
    }
    DisposableEffect(Unit) {
        TagEdit.askWrite = { sender -> tagWriter.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
        TagEdit.afterSave = { reload() }
        onDispose { TagEdit.askWrite = null; TagEdit.afterSave = null }
    }
    LaunchedEffect(Unit) {
        Favourites.load(ctx); MusicSettings.load(ctx); Playlists.load(ctx); Sorts.load(ctx); TabOpts.load(ctx); RecentSearches.load(ctx)
        SmartLists.load(ctx); Playback.load(ctx); Eq.load(ctx); Announcer.load(ctx)
        withContext(Dispatchers.IO) { PlayStats.load(ctx) }
        lib = Library.load(ctx, fresh = true)
        // files marked "not music" (test 307), looked at once each
        lib?.let { l -> if (NotMusic.check(ctx, l.songs) > 0) lib = Library.load(ctx, fresh = true) }
    }
    // keep the seek bar moving
    LaunchedEffect(ui.controller) { while (true) { ui.controller?.let { ui.position = it.currentPosition; ui.positionAt = android.os.SystemClock.elapsedRealtime() }; delay(250) } }

    val stack = remember { mutableStateListOf<Page>(Page.Home) }
    val pager = rememberPagerState(initialPage = (WavesTabs.indexOf(MusicSettings.startTab).takeIf { it >= 0 } ?: WavesTabs.indexOf("Tracks")).coerceAtLeast(0)) { WavesTabs.size }
    var nowPlaying by remember { mutableStateOf(false) }
    var npMode by remember { mutableIntStateOf(0) }
    val npAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val library = lib
    // test 312: a page opened from Now Playing (Equaliser, Car mode, Design studio…) goes back to Now Playing
    var backToNowAt by remember { mutableIntStateOf(-1) }
    fun back() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        if (stack.size == backToNowAt) { backToNowAt = -1; nowPlaying = true }
        else if (backToNowAt > stack.size) backToNowAt = -1
    }
    BackHandler(enabled = nowPlaying || stack.size > 1) {
        if (nowPlaying) nowPlaying = false else back()
    }
    fun open(p: Page) {
        if (p == Page.Home) { stack.clear(); stack.add(Page.Home); backToNowAt = -1 }
        else { if (nowPlaying) backToNowAt = stack.size; stack.add(p) }
        nowPlaying = false
    }
    val song = songOf(ui, library)
    val art by produceState<Bitmap?>(null, song?.id, song?.artFile) { value = song?.let { Covers.load(ctx, it, 200) } }
    val tint = remember(art) { art?.let { averageColor(it) } ?: Color(0xFF3A3A40) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (library == null) Text("Finding your music…", Modifier.align(Alignment.Center))
                else if (library.songs.isEmpty() && stack.last() == Page.Home) Text("No music found on this phone yet.\nCopy songs to the Music folder and open this again.", Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
                else when (val p = stack.last()) {
                    Page.Home -> WavesHome(library, ui, pager, ::open)
                    Page.Search -> WavesSearch(library, ui, ::open, ::back)
                    Page.Edit -> EditPage(library, ::back)
                    Page.Themes -> ThemesPage(::back)
                    Page.Studio -> StudioPage(::open, ::back)
                    Page.Equaliser -> EqualiserPage(::back)
                    is Page.SmartListPage -> SmartListPage(p.id, library, ui, ::open, ::back)
                    Page.Year -> YearPage(library, ui, ::open, ::back)
                    Page.Car -> CarModePage(ui, library, ::back)
                    is Page.Stems -> (song ?: library.byId[p.id])?.let { StemsPage(it, ui, ::back) }
                    is Page.Ringtone -> library.byId[p.id]?.let { RingtonePage(it, ui, ::back) }
                    Page.Songs -> SongsPage("Tracks", library.songs, ui, library, ::open, ::back)
                    Page.Favs -> SongsPage("Favourite tracks", library.songs.filter { it.id in Favourites.ids.value }, ui, library, ::open, ::back)
                    is Page.Smart -> {
                        PlayStats.version.value // refresh when counts change
                        when (p.kind) {
                            "most" -> SongsPage("Most played", PlayStats.mostPlayed(library), ui, library, ::open, ::back)
                            "recent" -> SongsPage("Recently played", PlayStats.recentlyPlayed(library), ui, library, ::open, ::back)
                            else -> SongsPage("Recently added", PlayStats.recentlyAdded(library), ui, library, ::open, ::back)
                        }
                    }
                    is Page.FolderPage -> SongsPage(p.path.substringAfterLast('/').ifBlank { p.path },
                        library.songs.filter { it.path.substringBeforeLast('/', "") == p.path }.sortedBy { it.path.lowercase() }, ui, library, ::open, ::back)
                    Page.Albums -> AlbumsPage("Albums", library.albums, ::open, ::back)
                    Page.Playlists -> PlaylistsPage(library, ::open, ::back)
                    Page.Import -> ImportPage(library, ::open, ::back)
                    is Page.PlaylistPage -> PlaylistPage(p.id, library, ui, ::open, ::back)
                    Page.Settings -> SettingsPage(::open, ::back) { reload() }
                    Page.Fixer -> FixerPage(library, onBack = ::back) { reload() }
                    is Page.FixOne -> FixerPage(library, onBack = ::back, only = p.id) { reload() }
                    Page.Artists -> ArtistsPage(library, ::open, ::back)
                    is Page.AlbumPage -> library.albums.find { it.id == p.id }?.let { AlbumPage(it, ui, library, ::open, ::back) }
                    is Page.ArtistPage -> library.artists.find { it.name == p.name }?.let { ArtistPage(it, ui, library, ::open, ::back) }
                }
            }
            if (ui.current != null && song != null && stack.last() != Page.Edit && stack.last() !is Page.Ringtone)
                WavesMiniPlayer(song, ui, tint, onOpen = { npMode = 0; nowPlaying = true }, onQueue = { npMode = 2; nowPlaying = true })
            Spacer(Modifier.navigationBarsPadding())
        }
        // Now Playing grows out of the mini player and shrinks back into it, following your finger (test 240)
        LaunchedEffect(nowPlaying) {
            if (nowPlaying) npAnim.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
            else npAnim.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
        }
        if (nowPlaying || npAnim.value > 0.002f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = npAnim.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.55f)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val hPx = with(LocalDensity.current) { maxHeight.toPx() }
                NowPlaying(ui, library, npMode, progress = { npAnim.value },
                    onDrag = { d -> appScope.launch { npAnim.snapTo((npAnim.value - d / hPx).coerceIn(0f, 1f)) } },
                    onRelease = {
                        if (npAnim.value < 0.82f) { if (nowPlaying) nowPlaying = false else appScope.launch { npAnim.animateTo(0f) } }
                        else appScope.launch { npAnim.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 400f)) }
                    },
                    onClose = { nowPlaying = false }, open = ::open)
            }
        }
        PlaylistPickerDialog()
        TrackDetailsDialog()
        NotMusicDialog(ui)
        TagEditDialog()
        LyricsEditDialog(ui)
        SmartListDialog(); SpeedDialog(); SupportArtistDialogHost()
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
internal fun Header(title: String, onBack: (() -> Unit)?, onSettings: (() -> Unit)? = null, titleContent: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
        if (onSettings != null) Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onSettings) { Icon(MusicIcons.settings, "Settings", tint = Accent) }
        } else if (onBack != null) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MusicIcons.arrowBack, "Back", modifier = Modifier.size(24.dp)); Spacer(Modifier.width(6.dp)); Text("Back", fontSize = 17.sp)
        } else Spacer(Modifier.height(36.dp))
        if (titleContent != null) titleContent() else Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
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
        listOf(Triple("Play", MusicIcons.play, null), Triple("Shuffle", MusicIcons.shuffle, true)).forEach { (label, icon, sh) ->
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
                     extra: List<Pair<String, () -> Unit>> = emptyList(), menuState: androidx.compose.runtime.MutableState<Boolean>? = null,
                     holdForMenu: Boolean = true, slim: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    // test 289: inside a playlist, holding a song starts dragging it, and the menu opens from outside when you let go without moving
    val ownMenu = remember { mutableStateOf(false) }
    val menuS: androidx.compose.runtime.MutableState<Boolean> = menuState ?: ownMenu
    var menu by menuS
    var confirmDelete by remember { mutableStateOf(false) }
    val isCurrent = ui.currentId == song.id
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = if (holdForMenu) ({ menu = true }) else null).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (number != null) Text(if (isCurrent) "♪" else "$number", Modifier.width(28.dp), color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            else {
                if (isCurrent && ui.playing) PlayingCover(song, ui) else Cover(song, 160, Modifier.size(48.dp), corner = 6)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                val second = if (number != null) time(song.durationMs) else "${song.artist} · ${song.album}"
                // test 308 (Jarrod): the song that's playing moves like the mini player: rolling name, artist shine
                if (isCurrent && ui.playing) {
                    RollText(song.title, Accent, 16f, center = false, bold = false)
                    ShineText(second, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), 13f)
                } else {
                    Text(song.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface)
                    Text(second, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
            if (song.id in Favourites.ids.value) Icon(MusicIcons.heart, null, tint = Accent, modifier = Modifier.size(14.dp))
            IconButton(onClick = { menu = true }) { Icon(MusicIcons.more, "More", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        }
        DropdownMenu(menu, { menu = false }) {
            // test 308 (Jarrod: too many options in a playlist): [slim] drops Play Last, Favourite and Share, adds Metadata / artwork
            DropdownMenuItem({ Text("Play Next") }, { ui.playNext(song); menu = false })
            if (!slim) DropdownMenuItem({ Text("Play Last") }, { ui.playLast(song); menu = false })
            DropdownMenuItem({ Text("Add to Playlist…") }, { PlaylistPicker.songs.value = listOf(song.id); menu = false })
            DropdownMenuItem({ Text("📻 Radio from this song") }, { menu = false; Radio.start(ctx, ui, lib, song) })
            extra.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { menu = false; action() }) }
            if (!slim) DropdownMenuItem({ Text(if (song.id in Favourites.ids.value) "Remove from Favourites" else "Favourite") }, { Favourites.toggle(ctx, song.id); menu = false })
            DropdownMenuItem({ Text("Go to Album") }, { open(Page.AlbumPage(song.albumId)); menu = false })
            DropdownMenuItem({ Text("Go to Artist") }, { open(Page.ArtistPage(song.albumArtist.ifBlank { song.artist })); menu = false })
            if (slim) DropdownMenuItem({ Text("Metadata / artwork") }, { menu = false; open(Page.FixOne(song.id)) })
            if (!slim) DropdownMenuItem({ Text("Share") }, { menu = false; SongActions.share(ctx, listOf(song)) })
            DropdownMenuItem({ Text("Track details") }, { menu = false; SongActions.details.value = song })
            DropdownMenuItem({ Text("Set as ringtone…") }, { menu = false; open(Page.Ringtone(song.id)) })
            DropdownMenuItem({ Text("Not music, hide it") }, { menu = false; NotMusic.asking.value = song })
            DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = true })
        }
    }
    if (confirmDelete) DeleteDialog(listOf(song)) { confirmDelete = false }
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
private fun SectionTitle(t: String) = Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))

internal fun songOf(ui: PlayerUi, lib: Library?): Song? = lib?.byId?.get(ui.currentId) ?: ui.current?.let { m ->
    val artist = if (ui.isLiveStream && ui.liveTitle.isNotBlank()) "LIVE · ${ui.liveTitle}" else m.mediaMetadata.artist?.toString() ?: ""
    Song(ui.currentId, m.mediaMetadata.title?.toString() ?: "", artist, m.mediaMetadata.albumTitle?.toString() ?: "",
        -1, "", 0, 0, 0, 0, 0, "")
}

@Composable
private fun NowPlaying(ui: PlayerUi, lib: Library?, startMode: Int, progress: () -> Float, onDrag: (Float) -> Unit, onRelease: () -> Unit,
                       onClose: () -> Unit, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val song = songOf(ui, lib)
    var mode by remember(startMode) { mutableIntStateOf(startMode) } // 0 cover, 1 lyrics, 2 up next
    val density = LocalDensity.current
    val bmp by produceState<Bitmap?>(null, song?.id, song?.artFile) { value = song?.let { Covers.load(ctx, it, 600) } }
    BackHandler(enabled = mode != 0) { mode = 0 }
    // beat effects (test 234): beats worked out once per song, then everything moves with the song's position
    val beatMap = rememberBeatMap(song)
    val clock = rememberBeatClock(ui, MusicSettings.effectsOn && beatMap != null && ui.playing)
    val tint = remember(bmp) { bmp?.let { averageColor(it) } ?: Color(0xFF3A3A40) }
    val k = MusicSettings.buttonSize
    var hint by remember { mutableStateOf<String?>(null) }
    var shownHint by remember { mutableStateOf("") }
    // test 301 (Jarrod): the label shows over the middle of the heart row, not over the song's chart line
    var hintY by remember { mutableIntStateOf(-1) }
    // test 302 (Jarrod: too low, it covered the heart): halfway between the song's words and the heart
    var artistBottom by remember { mutableIntStateOf(-1) }
    var storyBottom by remember { mutableIntStateOf(-1) }
    var hintH by remember { mutableIntStateOf(0) }
    LaunchedEffect(hint) { hint?.let { shownHint = it; delay(1600); hint = null } }

    // minimise (test 240): the whole screen follows your finger, shrinking into a rounded card that drops into the mini player
    val cornerPx = with(density) { 36.dp.toPx() }
    Box(Modifier.fillMaxSize()
        .graphicsLayer {
            // the spring can overshoot past 1 a little: keep everything in range (negative corners crashed test 240)
            val p = progress().coerceIn(0f, 1f)
            translationY = (1f - p) * size.height * 0.9f
            val sc = 0.86f + 0.14f * p
            scaleX = sc; scaleY = sc
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            alpha = (p * 2.2f).coerceIn(0f, 1f)
            shape = RoundedCornerShape(((1f - p) * 4f).coerceIn(0f, 1f) * cornerPx)
            clip = true
        }
        .background(Color.Black)
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = onRelease, onDragCancel = onRelease) { change, d -> change.consume(); onDrag(d) }
        }) {
        if (MusicSettings.theme != -1) ThemeBackground(MusicSettings.theme, dim = 0.45f)
        else if (MusicSettings.blurBackground) MovingBackground(bmp)
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black, 0.35f to Color.Black, 1f to tint)))
        BeatBackground(beatMap, clock, tint, if (mode == 0) 0.36f else 0.12f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            NowTopBar(song, ui, open, if (mode != 0) ({ mode = 0 }) else onClose, back = mode != 0, onHint = { hint = it }, onSingOn = { mode = 1 })
            StemNowBanner(song)
            when (mode) {
                0 -> {
                    val scale by animateFloatAsState(if (ui.playing) 1f else 0.94f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")
                    // Samsung layout: cover near the top, title and artist right under it, space below
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        val liftPx = with(density) { 16.dp.toPx() }
                        val side = minOf(maxWidth * MusicSettings.coverSize, maxHeight - 110.dp).coerceAtLeast(80.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(28.dp))
                            Box(contentAlignment = Alignment.Center) {
                                if (MusicSettings.effectsOn && MusicSettings.cover == 3) Box(Modifier.requiredSize(side).graphicsLayer {
                                    val m = coverMove(beatMap, clock.ms)
                                    alpha = m.glow; scaleX = scale * (1.04f + 0.14f * m.glow); scaleY = scaleX
                                }.blur(36.dp).clip(RoundedCornerShape(24.dp)).background(tint.copy(alpha = 1f)))
                                if (MusicSettings.coverBorder > 0) {
                                    val pulse = if (MusicSettings.effectsOn && beatMap != null) (beatMap.pulse(clock.ms) * MusicSettings.strength).coerceIn(0f, 1.3f) else 0f
                                    CoverBorders.Overlay(
                                        style = MusicSettings.coverBorder,
                                        side = side,
                                        corner = 22.dp,
                                        playing = ui.playing,
                                        beatPulse = pulse,
                                        modifier = Modifier.graphicsLayer {
                                            val m = coverMove(beatMap, clock.ms)
                                            scaleX = scale * m.scale; scaleY = scale * m.scale
                                            rotationZ = m.rotation; translationY = m.lift * liftPx
                                        },
                                    )
                                }
                                Cover(song, 600, Modifier.requiredSize(side).graphicsLayer {
                                    val m = coverMove(beatMap, clock.ms)
                                    scaleX = scale * m.scale; scaleY = scale * m.scale
                                    rotationZ = m.rotation; translationY = m.lift * liftPx
                                }.clickable { mode = 1 }, corner = 22)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                                RollText(song?.title ?: "", Color.White, 23f)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp)
                                .onGloballyPositioned { artistBottom = it.boundsInRoot().bottom.toInt() }
                                .clickable { song?.let { open(Page.ArtistPage(it.albumArtist.ifBlank { it.artist })) } },
                                contentAlignment = Alignment.Center) {
                                ShineText(song?.artist ?: "", Color.White.copy(alpha = 0.9f), 16f)
                            }
                            // test 296 (Jarrod): what "Sort by popularity" found about this song, e.g. its best chart spot and year
                            val story = remember(song?.id, MusicSettings.popularOn) { song?.takeIf { MusicSettings.popularOn }?.let { Popularity.known(ctx, it)?.story() } }
                            if (story != null) Text(story, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 12.dp, end = 12.dp)
                                    .onGloballyPositioned { storyBottom = it.boundsInRoot().bottom.toInt() })
                            else SideEffect { storyBottom = -1 }
                        }
                    }
                }
                1 -> { SmallHeader(song); LyricsView(song, ui, Modifier.weight(1f).clickable { mode = 0 }) }
                else -> { SmallHeader(song); UpNext(ui, lib, Modifier.weight(1f)) }
            }
            // Samsung row: queue on the left, heart in the middle, add to playlist on the right
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).onGloballyPositioned { hintY = it.boundsInRoot().center.y.toInt() }) {
                IconButton({ mode = if (mode == 2) 0 else 2 }, Modifier.align(Alignment.CenterStart)) {
                    Icon(MusicIcons.sQueue, "Up Next", tint = if (mode == 2) Accent else Color.White, modifier = Modifier.size((26 * k).dp))
                }
                song?.let { s ->
                    val fav = s.id in Favourites.ids.value
                    IconButton({ Favourites.toggle(ctx, s.id); hint = if (fav) "Removed from Favourites" else "Added to Favourites" }, Modifier.align(Alignment.Center)) {
                        Icon(if (fav) MusicIcons.sHeartFull else MusicIcons.sHeart, "Favourite", tint = if (fav) Accent else Color.White, modifier = Modifier.size((26 * k).dp))
                    }
                    IconButton({ if (s.durationMs > 0) PlaylistPicker.songs.value = listOf(s.id) }, Modifier.align(Alignment.CenterEnd)) {
                        Icon(MusicIcons.sPlus, "Add to playlist", tint = Color.White, modifier = Modifier.size((26 * k).dp))
                    }
                }
            }
            SeekBar(ui)
            Controls(ui) { hint = it }
        }
        // a little label that says what a button just did (Jarrod: "no one knows what they are")
        val iconHalf = with(density) { (13 * k).dp.toPx() }.toInt()
        val words = if (storyBottom >= 0) storyBottom else artistBottom
        val hintCenter = if (mode == 0 && words >= 0 && hintY > words) (words + hintY - iconHalf) / 2 else hintY
        AnimatedVisibility(hint != null, if (hintY >= 0) Modifier.align(Alignment.TopCenter).offset { IntOffset(0, hintCenter - hintH / 2) }.onSizeChanged { hintH = it.height }
            else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = (85 * k + 140).dp),
            enter = fadeIn(), exit = fadeOut()) {
            Text(shownHint, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Top row: close on the left; Stem indicator (when on), Smart View, DJ toggle, equaliser and more on the right. */
@Composable
private fun NowTopBar(song: Song?, ui: PlayerUi, open: (Page) -> Unit, onClose: () -> Unit, back: Boolean = false, onHint: (String) -> Unit = {}, onSingOn: () -> Unit = {}) {
    val ctx = LocalContext.current
    val djOn = Announcer.enabled.value
    val stemOn = Stems.modeOn.value
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        // test 308 (Jarrod): lyrics and Up next need a way back that doesn't rely on the phone's back gesture
        if (back) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose() }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MusicIcons.arrowBack, "Back", tint = Color.White, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(6.dp)); Text("Back", color = Color.White, fontSize = 17.sp)
        }
        else IconButton(onClose) { Icon(MusicIcons.sDown, "Close", tint = Color.White, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.weight(1f))
        // test 319 (Jarrod): show Stem indicator only while Stem mode is active, with the same Accent highlight as DJ
        if (stemOn) {
            IconButton({ song?.let { open(Page.Stems(it.id)) } }) {
                Text("STEM", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        IconButton({
            runCatching { ctx.startActivity(Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }) { Icon(MusicIcons.sSmartView, "Smart View", tint = Color.White, modifier = Modifier.size(27.dp)) }
        // test 318 (Jarrod): clean borderless DJ icon matching the rest of the top bar (no box around it)
        IconButton({
            val next = !djOn
            if (next && !Announcer.ready(ctx)) {
                onHint("Download Zonos in TTS tile first")
            } else {
                Announcer.set(ctx, "ann_on", next)
                onHint(if (next) "DJ on (Savannah)" else "DJ off")
            }
        }) {
            Text(
                "DJ",
                color = if (djOn) Accent else Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (Stems.singMode.value) {
            IconButton({ Stems.setSingMode(ctx, false); onHint("Sing mode off") }) {
                Icon(MusicIcons.singMic, "Sing mode", tint = Accent, modifier = Modifier.size(25.dp))
            }
        }
        IconButton({ open(Page.Equaliser) }) { Icon(MusicIcons.sEqualizer, "Equaliser", tint = Color.White, modifier = Modifier.size(27.dp)) }
        MoreMenu(song, ui, open, onSingOn)
    }
}

/** Opens the phone's own equaliser (Samsung: SoundAlive / Dolby Atmos) for our music. */
internal fun openEqualiser(ctx: Context) {
    val i = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, MusicState.audioSession)
        .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
        .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { ctx.startActivity(i) }.isSuccess
    if (!ok) runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

internal fun averageColor(b: Bitmap): Color {
    val s = Bitmap.createScaledBitmap(b, 4, 4, true)
    var r = 0; var g = 0; var bl = 0
    for (x in 0 until 4) for (y in 0 until 4) { val p = s.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255 }
    return Color(r / 16, g / 16, bl / 16).copy(alpha = 1f).let { Color(it.red * 0.7f, it.green * 0.7f, it.blue * 0.7f) }
}

@Composable
private fun SmallHeader(song: Song?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(song, 160, Modifier.size(60.dp).shadow(8.dp, RoundedCornerShape(6.dp)), corner = 6)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Now Playing ⋮ menu, Samsung's items plus our extras. */
@Composable
private fun MoreMenu(song: Song?, ui: PlayerUi, open: (Page) -> Unit, onSingOn: () -> Unit = {}) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sleepAt by MusicState.sleepAt.collectAsState()
    val real = song?.takeIf { it.durationMs > 0 }
    Box {
        IconButton({ menu = true }) { Icon(MusicIcons.more, "More", tint = Color.White) }
        DropdownMenu(menu, { menu = false }) {
            real?.let { s ->
                DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = true })
                DropdownMenuItem({ Text("Share") }, { menu = false; SongActions.share(ctx, listOf(s)) })
                DropdownMenuItem({ Text("Track details") }, { menu = false; SongActions.details.value = s })
                // test 267 (Jarrod): playing a song with no cover? add one right here
                DropdownMenuItem({ Text("Metadata / artwork") }, { menu = false; open(Page.FixOne(s.id)) })
                DropdownMenuItem({ Text("Album") }, { menu = false; open(Page.AlbumPage(s.albumId)) })
                DropdownMenuItem({ Text("Artist") }, { menu = false; open(Page.ArtistPage(s.albumArtist.ifBlank { s.artist })) })
                DropdownMenuItem({ Text("☕ Support artist") }, { menu = false; SupportArtist.asking.value = s.albumArtist.ifBlank { s.artist } })
                DropdownMenuItem({ Text("Set as ringtone…") }, { menu = false; open(Page.Ringtone(s.id)) })
                DropdownMenuItem({ Text("Add to playlist") }, { menu = false; PlaylistPicker.songs.value = listOf(s.id) })
                DropdownMenuItem({ Text("Not music, hide it") }, { menu = false; NotMusic.asking.value = s })
                Library.cached?.let { l -> DropdownMenuItem({ Text("📻 Radio from this song") }, { menu = false; Radio.start(ctx, ui, l, s) }) }
                DropdownMenuItem({ Text("Edit tags") }, { menu = false; TagEdit.asking.value = s })
                DropdownMenuItem({ Text("Edit lyrics") }, { menu = false; LyricsEdit.editing.value = s })
                DropdownMenuItem({ Text("🎛️ Stems: vocals, drums, bass" + if (Stems.modeOn.value) " (on)" else "") }, { menu = false; open(Page.Stems(s.id)) })
            }
            DropdownMenuItem({ Text(if (Stems.singMode.value) "🎤 Sing mode (on)" else "🎤 Sing mode") }, {
                menu = false
                if (Stems.singMode.value) {
                    Stems.setSingMode(ctx, false)
                    android.widget.Toast.makeText(ctx, "Sing mode off", android.widget.Toast.LENGTH_SHORT).show()
                } else if (Stems.setSingMode(ctx, true)) {
                    onSingOn()
                    android.widget.Toast.makeText(ctx, "Sing mode on — splitting vocals out…", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    real?.let { open(Page.Stems(it.id)) }
                    android.widget.Toast.makeText(ctx, "Download the Stem Splitter model first to use Sing mode.", android.widget.Toast.LENGTH_LONG).show()
                }
            })
            DropdownMenuItem({ Text("Playback speed" + if (Playback.speed.value != 1f) " (${Playback.speed.value}×)" else "") }, { menu = false; Playback.asking.value = true })
            DropdownMenuItem({ Text(if (sleepAt != 0L) "Sleep timer (on)" else "Sleep timer") }, { menu = false; sleep = true })
            DropdownMenuItem({ Text("🚗 Car mode") }, { menu = false; open(Page.Car) })
            DropdownMenuItem({ Text("Design studio") }, { menu = false; open(Page.Studio) })
            DropdownMenuItem({ Text("Settings") }, { menu = false; open(Page.Settings) })
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
    if (confirmDelete && real != null) DeleteDialog(listOf(real)) { confirmDelete = false }
}

/** "Delete 1 track?" box; Android then asks once more on newer phones. */
@Composable
internal fun DeleteDialog(songs: List<Song>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDone,
        title = { Text(if (songs.size == 1) "Delete this track?" else "Delete ${songs.size} tracks?") },
        text = { Text(if (songs.size == 1) "“${songs[0].title}” will be deleted from your phone." else "These tracks will be deleted from your phone.") },
        confirmButton = { TextButton({ onDone(); SongActions.delete(ctx, songs) }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}

/** Test 317 / 362 (Jarrod): customisable animated seek line (10 styles + 14 colours/gradients), flattens/pauses cleanly when paused. */
@Composable
internal fun WavyProgressLine(
    frac: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    strokeDp: Float = 3.5f,
    ampDp: Float = 3.2f,
    thumbDp: Float = 0f,
) {
    StyledProgressLine(
        frac = frac,
        playing = playing,
        modifier = modifier,
        defaultColor = color,
        trackColor = trackColor,
        strokeDp = strokeDp,
        ampDp = ampDp,
        thumbDp = thumbDp,
    )
}

@Composable
private fun SeekBar(ui: PlayerUi) {
    if (ui.isLiveStream) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            WavyProgressLine(
                frac = 1f,
                playing = ui.playing,
                modifier = Modifier.fillMaxWidth().height(26.dp),
                strokeDp = 3.5f,
                ampDp = 4.2f,
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Spacer(Modifier.width(6.dp))
                Text("LIVE RADIO", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        return
    }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val dur = ui.duration.coerceAtLeast(1)
    val frac = dragFrac ?: (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { ui.seek((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(dur) { detectTapGestures { o -> ui.seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } },
            contentAlignment = Alignment.CenterStart) {
            WavyProgressLine(
                frac = frac,
                playing = ui.playing && dragFrac == null,
                modifier = Modifier.fillMaxWidth().height(26.dp),
                strokeDp = if (dragFrac != null) 5f else 3.5f,
                ampDp = 4.2f,
                thumbDp = if (dragFrac != null) 22f else 18f,
            )
        }
        val shown = ((dragFrac ?: frac) * dur).toLong()
        Row(Modifier.fillMaxWidth()) {
            Text(time(shown), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(time(ui.duration), color = Color.White, fontSize = 14.sp)
        }
        if (ui.isLongForm) {
            val ctx = LocalContext.current
            val spd by Playback.speed.collectAsState()
            val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedButton({ ui.seek((ui.now() - 15_000L).coerceAtLeast(0L)) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("↺ 15s", color = Color.White, fontSize = 13.sp)
                }
                androidx.compose.material3.OutlinedButton({
                    val next = speeds.firstOrNull { it > spd + 0.01f } ?: speeds.first()
                    Playback.setSpeed(ctx, next)
                }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("${if (spd == 1f) "1" else spd}×", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.OutlinedButton({ ui.seek((ui.now() + 30_000L).coerceAtMost(dur)) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("30s ↻", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi, onHint: (String) -> Unit) {
    val c = ui.controller
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // big button sizes shrink a little if they would not fit across the screen
    val k = minOf(MusicSettings.buttonSize, maxWidth.value / (166f * 1.35f))
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Press(MusicIcons.sShuffle, "Shuffle", (28 * k).toInt(), if (ui.shuffle) Accent else Color.White) {
            val on = !ui.shuffle; c?.shuffleModeEnabled = on; onHint(if (on) "Shuffle on" else "Shuffle off")
        }
        // test 317 (Jarrod): tap = previous song, press & hold = smooth gentle rewind
        Press(MusicIcons.sPrevious, "Previous", (34 * k).toInt(),
            onHoldStep = {
                val p = (ui.now() - 1_500L).coerceAtLeast(0L)
                ui.seek(p)
            }) { ui.previous() }
        Press(if (ui.playing) MusicIcons.sPause else MusicIcons.sPlay, "Play or pause", (42 * k).toInt()) { ui.toggle() }
        // test 317 (Jarrod): tap = next song, press & hold = smooth gentle fast-forward
        Press(MusicIcons.sNext, "Next", (34 * k).toInt(),
            onHoldStep = {
                val d = ui.duration.coerceAtLeast(1L)
                val p = (ui.now() + 1_500L).coerceAtMost(d)
                ui.seek(p)
            }) { ui.next() }
        Press(if (ui.repeat == Player.REPEAT_MODE_ONE) MusicIcons.sRepeatOne else MusicIcons.sRepeat, "Repeat", (28 * k).toInt(),
            if (ui.repeat != Player.REPEAT_MODE_OFF) Accent else Color.White) {
            val next = when (ui.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            c?.repeatMode = next
            onHint(when (next) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat this song"; else -> "Repeat off" })
        }
    }
    }
}

@Composable
private fun Press(icon: ImageVector, label: String, size: Int, tint: Color = Color.White, onHoldStep: (() -> Unit)? = null, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) 0.82f else 1f, label = "press")
    val scope = rememberCoroutineScope()
    val holdRef = androidx.compose.runtime.rememberUpdatedState(onHoldStep)
    val clickRef = androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(Modifier.size((size * 1.35f).dp).scale(s).clip(CircleShape).background(if (down) Color.White.copy(alpha = 0.15f) else Color.Transparent)
        .pointerInput(onHoldStep != null) {
            detectTapGestures(onPress = {
                down = true
                if (holdRef.value == null) {
                    val released = tryAwaitRelease()
                    down = false
                    if (released) clickRef.value()
                } else {
                    var scrubbed = false
                    val job = scope.launch {
                        delay(380)
                        scrubbed = true
                        while (true) {
                            holdRef.value?.invoke()
                            delay(180)
                        }
                    }
                    val released = tryAwaitRelease()
                    job.cancel()
                    down = false
                    if (released && !scrubbed) clickRef.value()
                }
            })
        },
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
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
    LaunchedEffect(song?.id, embedded, LyricsEdit.version.intValue) {
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
                if (song != null) TextButton({ LyricsEdit.editing.value = song }) { Text("✏️ Type them myself", color = Color.White) }
            }
        } else {
            val state = rememberLazyListState()
            val cur = l.lineAt(ui.position)
            val viewH = remember { mutableIntStateOf(0) }
            LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem((cur - 1).coerceAtLeast(0), -(viewH.intValue / 6)) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                viewH.intValue = constraints.maxHeight
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp)) {
                    itemsIndexed(l.lines) { i, line ->
                        // test 383 (Jarrod): highlight a 4-line window (1 behind, current, 2 ahead) so slightly off LRC timing is still easy to sing along with
                        val on = !l.synced || (cur >= 0 && i in (cur - 1)..(cur + 2))
                        val a by animateFloatAsState(if (on) 1f else 0.28f, tween(300), label = "line")
                        val s by animateFloatAsState(if (i == cur) 1.03f else 1f, tween(300), label = "lineScale")
                        Text(line.text.ifBlank { "♪" }, color = Color.White, fontSize = if (l.synced) 24.sp else 20.sp, fontWeight = FontWeight.Bold,
                            lineHeight = if (l.synced) 30.sp else 28.sp,
                            modifier = Modifier.fillMaxWidth().alpha(a).graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                                .clickable(enabled = l.synced) { ui.seek(line.timeMs) }.padding(vertical = 8.dp))
                    }
                    item {
                        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Lyrics: ${l.source}", Modifier.weight(1f), color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            if (song != null) TextButton({ LyricsEdit.editing.value = song }) { Text("✏️ Edit", color = Color.White.copy(alpha = 0.7f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNext(ui: PlayerUi, lib: Library?, modifier: Modifier) {
    val c = ui.controller
    Column(modifier.fillMaxWidth()) {
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
