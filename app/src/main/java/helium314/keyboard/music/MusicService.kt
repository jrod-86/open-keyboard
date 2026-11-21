// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaLibraryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Things the player screen and the service share. Both run in the ":music" process. */
object MusicState {
    /** song id to lyrics found inside the music file */
    val embeddedLyrics = MutableStateFlow<Pair<Long, String>?>(null)
    /** when the sleep timer stops the music (System.currentTimeMillis), 0 = off; -1 = at the end of this song */
    val sleepAt = MutableStateFlow(0L)
    /** the player's audio session, for the phone's equaliser */
    @Volatile var audioSession = 0
    /** Open Waves is on screen (the floating player hides then) */
    @Volatile var appVisible = false
    /** the floating player was dragged away to close it; it comes back next time Open Waves is opened */
    @Volatile var floatClosed = false
}

/**
 * Open Keyboard Music (test 229): plays music in the background, with lock screen, notification,
 * headphone and Bluetooth controls (Media3). Lives in its own ":music" process so it never slows typing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicService : MediaLibraryService() {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private var leveler: LevelApplier? = null
    private var floating: FloatingPlayer? = null
    private var blender: Crossfader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() { save(); handler.postDelayed(this, 15_000) }
    }
    private val sleepTick = object : Runnable {
        override fun run() {
            // test 311: ListenBrainz scrobbling counts listening time
            session?.player?.let { p -> p.currentMediaItem?.mediaId?.toLongOrNull()?.let { id -> Scrobble.tick(this@MusicService, id, p.isPlaying, p.duration) } }
            val at = MusicState.sleepAt.value
            if (at > 0 && System.currentTimeMillis() >= at) {
                session?.player?.pause(); MusicState.sleepAt.value = 0
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Eq.load(this)
        val player = ExoPlayer.Builder(this, Eq.renderers(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true) // headphones unplugged: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        MusicState.audioSession = player.audioSessionId
        runCatching { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).registerAudioDeviceCallback(devices, handler) }
        var counted = -1L
        // Most played / Recently played (test 238): count a song once each time it starts playing
        fun count() {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
            if (player.isPlaying && id != counted) { counted = id; PlayStats.record(this, id) }
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { MusicState.audioSession = audioSessionId }
            override fun onTracksChanged(tracks: Tracks) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                val metas = tracks.groups.flatMap { g -> (0 until g.length).mapNotNull { g.getTrackFormat(it).metadata } }
                Lyrics.fromMetadata(metas)?.let { MusicState.embeddedLyrics.value = id to it }
                leveler?.let { it.tags = Leveler.tags(metas); it.apply() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (MusicState.sleepAt.value == -1L && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.pause(); MusicState.sleepAt.value = 0
                }
                save()
                leveler?.let { it.tags = null; it.apply(); it.prepareNext() }
                counted = -1L; count()
                Radio.topUp(this@MusicService, player)
                PlayerWidget.update(this@MusicService)
                // test 311: long tracks carry on from where you stopped
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    val id = mediaItem?.mediaId?.toLongOrNull()
                    val spot = id?.let { Playback.spotOf(this@MusicService, it) } ?: 0L
                    if (spot > 0 && player.currentPosition < 5_000) player.seekTo(spot)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) save() else count(); PlayerWidget.update(this@MusicService) }
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // test 266: real covers and shuffle/repeat buttons for the phone's own pop-up player (see SessionLook.kt)
        // test 311: a library session, so Android Auto and the car can browse your music
        val s = MediaLibraryService.MediaLibrarySession.Builder(this, player, CarLibrary.callback(this)).setSessionActivity(open)
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(CoverBitmapLoader(this)))
            .setMediaButtonPreferences(SessionButtons.buttons(player))
            .build()
        session = s
        PlayerWidget.player = player; PlayerWidget.update(this)
        player.addListener(object : Player.Listener {
            // shuffle or repeat changed in the app: update the buttons on the phone's player too
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { s.setMediaButtonPreferences(SessionButtons.buttons(player)) }
            override fun onRepeatModeChanged(repeatMode: Int) { s.setMediaButtonPreferences(SessionButtons.buttons(player)) }
        })
        Leveler.load(this)
        leveler = LevelApplier(this, player).also { player.addListener(it.listener) }
        Tuning.load(this)
        scope.launch { Tuning.hz.collect { Tuning.apply(player) } }
        Playback.load(this)
        scope.launch { Playback.speed.collect { Tuning.apply(player) } }
        scope.launch { Library.load(this@MusicService); kotlinx.coroutines.flow.combine(Leveler.mode, Leveler.level) { a, b -> a to b }.collect { leveler?.apply() } }
        restore(player)
        MusicSettings.load(this)
        floating = FloatingPlayer(this, player).also { it.start() }
        blender = Crossfader(this, player) { leveler }.also { it.start() }
        handler.postDelayed(saveTick, 15_000)
        handler.post(sleepTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from recents while paused: stop completely; while playing: keep playing like Apple Music
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) { save(); stopSelf() }
    }

    private val startedAt = System.currentTimeMillis()
    // test 311: the equaliser follows you from speaker to headphones to the car
    private val devices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
            Eq.deviceChanged(this@MusicService)
            // test 311: headphones, earbuds or the car just connected (the first call is only the list of what's already there)
            if (System.currentTimeMillis() - startedAt < 3000 || !Playback.autoPlay(this@MusicService)) return
            val heard = added.orEmpty().any { it.type in listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET) || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) }
            val p = session?.player ?: return
            if (heard && !p.isPlaying && p.mediaItemCount > 0) handler.postDelayed({ if (!p.isPlaying) { p.prepare(); p.play() } }, 1500)
        }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { Eq.deviceChanged(this@MusicService) }
    }

    override fun onDestroy() {
        runCatching { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(devices) }
        save()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        floating?.stop(); floating = null
        blender?.release(); blender = null
        leveler?.release(); leveler = null
        PlayerWidget.player = null; PlayerWidget.update(this)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("music", Context.MODE_PRIVATE)

    private fun save() {
        val p = session?.player ?: return
        p.currentMediaItem?.mediaId?.toLongOrNull()?.let { if (p.duration > 0) Playback.saveSpot(this, it, p.currentPosition, p.duration) }
        if (p.mediaItemCount == 0) return
        val ids = (0 until p.mediaItemCount).joinToString(",") { p.getMediaItemAt(it).mediaId }
        prefs().edit()
            .putString("queue", ids)
            .putInt("index", p.currentMediaItemIndex)
            .putLong("pos", p.currentPosition)
            .putBoolean("shuffle", p.shuffleModeEnabled)
            .putInt("repeat", p.repeatMode)
            .apply()
    }

    /** Puts back the last queue and spot in the song (paused), so Play carries on where you were. */
    private fun restore(player: ExoPlayer) {
        val p = prefs()
        val ids = p.getString("queue", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
        if (ids.isEmpty()) return
        scope.launch {
            val lib = Library.load(this@MusicService)
            if (player.mediaItemCount > 0) return@launch // something else started playing meanwhile
            val items = ids.mapNotNull { lib.byId[it]?.toMediaItem() }
            if (items.isEmpty()) return@launch
            val index = p.getInt("index", 0).coerceIn(0, items.size - 1)
            player.setMediaItems(items, index, p.getLong("pos", 0))
            player.shuffleModeEnabled = p.getBoolean("shuffle", false)
            player.repeatMode = p.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.prepare()
        }
    }
}
