// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package helium314.keyboard.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import helium314.keyboard.latin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/* Test 311 part 4: the home screen player widget, Android Auto (the car's screen can browse and play), and car mode. */

// ---------------- home screen player widget ----------------
class PlayerWidget : android.appwidget.AppWidgetProvider() {
    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            update(ctx)
            val app = ctx.applicationContext
            val pending = goAsync()
            android.os.Handler(android.os.Looper.getMainLooper()).apply {
                postDelayed({ update(app) }, 2_000L)
                postDelayed({ update(app); runCatching { pending.finish() } }, 5_000L)
            }
        }
    }
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) { update(ctx) }
    override fun onAppWidgetOptionsChanged(ctx: Context, awm: AppWidgetManager, id: Int, newOptions: Bundle) { update(ctx) }

    companion object {
        /** the player, while Open Waves' music service runs */
        @Volatile var player: Player? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var lastKey = ""

        private fun button(ctx: Context, code: Int): PendingIntent {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(ctx, androidx.media3.session.MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Redraws every Open Waves player widget with the song playing now. */
        fun update(ctx: Context) {
            val app = ctx.applicationContext
            val awm = AppWidgetManager.getInstance(app)
            val ids = runCatching { awm.getAppWidgetIds(ComponentName(app, PlayerWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val p = player
            val id = p?.currentMediaItem?.mediaId?.toLongOrNull()
            val playing = p?.isPlaying == true
            scope.launch {
                val lib = Library.cached ?: runCatching { Library.load(app) }.getOrNull()
                val song = id?.let { lib?.byId?.get(it) }
                    ?: app.getSharedPreferences("music", Context.MODE_PRIVATE).getString("queue", null)?.split(',')?.getOrNull(
                        app.getSharedPreferences("music", Context.MODE_PRIVATE).getInt("index", 0))?.toLongOrNull()?.let { lib?.byId?.get(it) }
                val art = song?.let { Covers.load(app, it, 300) }
                val key = "${song?.id}/$playing/${art != null}"
                fun views(layout: Int) = RemoteViews(app.packageName, layout).apply {
                    setTextViewText(R.id.ow_w_title, song?.title ?: "Open Waves")
                    setTextViewText(R.id.ow_w_artist, song?.artist ?: "Tap play to carry on")
                    if (art != null) setImageViewBitmap(R.id.ow_w_cover, art) else setImageViewResource(R.id.ow_w_cover, R.drawable.open_waves_widget_icon)
                    setImageViewResource(R.id.ow_w_play, if (playing) R.drawable.ow_w_pause else R.drawable.ow_w_play)
                    setOnClickPendingIntent(R.id.ow_w_play, button(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    setOnClickPendingIntent(R.id.ow_w_next, button(app, KeyEvent.KEYCODE_MEDIA_NEXT))
                    setOnClickPendingIntent(R.id.ow_w_prev, button(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    setOnClickPendingIntent(R.id.ow_w_cover, PendingIntent.getActivity(app, 8, Intent(app, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                val rv = if (android.os.Build.VERSION.SDK_INT >= 31)
                    RemoteViews(mapOf(SizeF(180f, 40f) to views(R.layout.ow_player_widget), SizeF(180f, 200f) to views(R.layout.ow_player_widget_tall)))
                else views(R.layout.ow_player_widget)
                runCatching { awm.updateAppWidget(ids, rv) }
                lastKey = key
            }
        }
    }
}

// ---------------- Android Auto: browse your music on the car's screen ----------------
object CarLibrary {
    private const val ROOT = "root"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun folder(id: String, title: String, sub: String? = null) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    private fun children(ctx: Context, lib: Library, parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(folder("cat:playlists", "Playlists"), folder("cat:favs", "Favourites"), folder("cat:most", "Most played"),
            folder("cat:added", "Recently added"), folder("cat:albums", "Albums"), folder("cat:artists", "Artists"), folder("cat:all", "All songs"))
        parent == "cat:playlists" -> Playlists.all.value.map { folder("pl:${it.id}", it.name, "${it.songs.size} songs") } +
            SmartLists.all.value.map { folder("smart:${it.id}", "⚡ " + it.name) }
        parent == "cat:favs" -> lib.songs.filter { it.id in Favourites.ids.value }.map { it.toMediaItem() }
        parent == "cat:most" -> PlayStats.mostPlayed(lib, 100).map { it.toMediaItem() }
        parent == "cat:added" -> PlayStats.recentlyAdded(lib, 100).map { it.toMediaItem() }
        parent == "cat:albums" -> lib.realAlbums.map { folder("al:${it.id}", it.title, it.artist) }
        parent == "cat:artists" -> lib.artists.map { folder("ar:${it.name}", it.name) }
        parent == "cat:all" -> lib.songs.take(1000).map { it.toMediaItem() }
        parent.startsWith("pl:") -> Playlists.get(parent.removePrefix("pl:").toLongOrNull() ?: -1)?.songs?.mapNotNull { lib.byId[it]?.toMediaItem() } ?: emptyList()
        parent.startsWith("smart:") -> SmartLists.all.value.find { it.id == parent.removePrefix("smart:").toLongOrNull() }?.let { SmartLists.songs(ctx, lib, it) }?.map { it.toMediaItem() } ?: emptyList()
        parent.startsWith("al:") -> lib.albums.find { it.id == parent.removePrefix("al:").toLongOrNull() }?.songs?.map { it.toMediaItem() } ?: emptyList()
        parent.startsWith("ar:") -> lib.artists.find { it.name == parent.removePrefix("ar:") }?.songs?.map { it.toMediaItem() } ?: emptyList()
        else -> emptyList()
    }

    private suspend fun ready(ctx: Context): Library {
        Favourites.load(ctx); Playlists.load(ctx); SmartLists.load(ctx); PlayStats.load(ctx)
        return Library.load(ctx)
    }

    fun callback(ctx: Context): MediaLibraryService.MediaLibrarySession.Callback {
        val base = SessionButtons.callback()
        return object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) = base.onConnect(session, controller)
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> =
                base.onCustomCommand(session, controller, customCommand, args)

            override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                          params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Open Waves"), params))

            override fun onGetChildren(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                       page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                val all = children(ctx, ready(ctx), parentId)
                val from = (page * pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
            }

            override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = later {
                val lib = ready(ctx)
                mediaId.toLongOrNull()?.let { lib.byId[it] }?.let { LibraryResult.ofItem(it.toMediaItem(), null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }

            override fun onSearch(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                  params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                session.notifySearchResultChanged(browser, query, 50, params)
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                           page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                val q = query.lowercase()
                val hits = ready(ctx).songs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }.take(50)
                LibraryResult.ofItemList(ImmutableList.copyOf(hits.map { it.toMediaItem() }), params)
            }

            // the car (and voice assistants) send only song ids: fill in the files to play
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = later {
                val lib = ready(ctx)
                mediaItems.map { m -> if (m.localConfiguration != null) m else m.mediaId.toLongOrNull()?.let { lib.byId[it] }?.toMediaItem() ?: m }.toMutableList()
            }

            // Play pressed on the car, a widget or headphones while nothing is loaded: carry on with the last queue
            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = later {
                val lib = ready(ctx)
                val p = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
                val items = p.getString("queue", null)?.split(',')?.mapNotNull { it.toLongOrNull()?.let { id -> lib.byId[id]?.toMediaItem() } }.orEmpty()
                    .ifEmpty { lib.songs.take(50).map { it.toMediaItem() } }
                MediaSession.MediaItemsWithStartPosition(items, p.getInt("index", 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)), p.getLong("pos", 0))
            }
        }
    }
}

// ---------------- car mode ----------------
/** Big buttons for driving: huge play, next and previous, swipe the screen to skip, and the screen stays on. */
@Composable
internal fun CarModePage(ui: PlayerUi, lib: Library, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val song = songOf(ui, lib)
    var dragged = 0f
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(20.dp)
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
                if (dragged < -150) ui.next() else if (dragged > 150) ui.previous()
            }) { _, d -> dragged += d }
        }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("✕  Exit car mode", Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(12.dp), color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text("📻 Radio", Modifier.clip(RoundedCornerShape(12.dp)).clickable { song?.let { Radio.start(ctx, ui, lib, it) } }.padding(12.dp), color = Accent, fontSize = 20.sp)
        }
        Spacer(Modifier.weight(0.4f))
        Cover(song, 400, Modifier.size(170.dp), corner = 20)
        Spacer(Modifier.height(18.dp))
        Text(song?.title ?: "Nothing playing", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(song?.artist ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            // test 317 (Jarrod): scale buttons to fit screen width so right-hand Next is never squished
            val side = minOf(maxWidth * 0.27f, 104.dp)
            val center = minOf(maxWidth * 0.36f, 138.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                @Composable fun big(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, bg: Color, onClick: () -> Unit) =
                    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
                        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f))
                    }
                big(MusicIcons.sPrevious, "Previous", side, Color.White.copy(alpha = 0.12f)) { ui.previous() }
                big(if (ui.playing) MusicIcons.sPause else MusicIcons.sPlay, "Play", center, Accent) { ui.toggle() }
                big(MusicIcons.sNext, "Next", side, Color.White.copy(alpha = 0.12f)) { ui.next() }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Swipe left or right anywhere to skip", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
    }
}
