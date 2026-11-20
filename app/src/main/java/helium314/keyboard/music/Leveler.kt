// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Open Keyboard Music (test 236, Jarrod): "Even out volume", like Apple's Sound Check.
 * Every song is brought to the same loudness (about -14 LUFS, what Spotify and YouTube use).
 * Loud songs are turned down with the player's volume; quiet songs are turned up (at most +6 dB) with Android's
 * loudness enhancer, which has a built-in limiter so nothing distorts.
 * Loudness comes from ReplayGain tags when the file has them, otherwise Music measures the song itself (once).
 */
object Leveler {
    const val OFF = 0
    const val SONG = 1
    const val ALBUM = 2
    /** Loudness levels you can pick (Jarrod: "if it's too quiet overall you can bump it up a notch") */
    val levels = listOf("Quiet" to -18f, "Normal" to -14f, "Loud" to -11f, "Louder" to -8f)
    private const val REPLAYGAIN_REF = -18f // ReplayGain 2 reference loudness

    val mode = MutableStateFlow(SONG)
    val level = MutableStateFlow(1)
    /** what the player is doing right now, for the settings screen: song loudness, change in dB */
    val now = MutableStateFlow<Pair<Float, Float>?>(null)
    /** "Measure all my music": done / total, null when not running */
    val scan = MutableStateFlow<Pair<Int, Int>?>(null)
    private var scanJob: Job? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun load(ctx: Context) { mode.value = prefs(ctx).getInt("level_mode", SONG); level.value = prefs(ctx).getInt("level_target", 1).coerceIn(0, levels.size - 1) }
    fun setMode(ctx: Context, m: Int) { mode.value = m; prefs(ctx).edit().putInt("level_mode", m).apply() }
    fun setLevel(ctx: Context, l: Int) { level.value = l; prefs(ctx).edit().putInt("level_target", l).apply() }

    /** Tags from the file: track and album ReplayGain in dB, if present. */
    data class Tags(val track: Float?, val album: Float?)

    fun tags(list: List<Metadata>): Tags {
        var t: Float? = null; var a: Float? = null
        fun db(v: String) = v.trim().removeSuffix("dB").removeSuffix("db").trim().replace(',', '.').toFloatOrNull()
        for (md in list) for (i in 0 until md.length()) {
            val e = md[i]
            val (k, v) = when (e) {
                is VorbisComment -> e.key to e.value
                is TextInformationFrame -> (e.description ?: e.id) to (e.values.firstOrNull() ?: "")
                else -> continue
            }
            when (k.uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> t = db(v) ?: t
                "REPLAYGAIN_ALBUM_GAIN" -> a = db(v) ?: a
                // Opus: R128 gains are in 1/256 dB, relative to -23 LUFS
                "R128_TRACK_GAIN" -> v.trim().toIntOrNull()?.let { t = it / 256f + 5f }
                "R128_ALBUM_GAIN" -> v.trim().toIntOrNull()?.let { a = it / 256f + 5f }
            }
        }
        return Tags(t, a)
    }

    /** The song's loudness in LUFS, if known (tags first, then our own measurement). */
    fun loudness(ctx: Context, song: Song?, tags: Tags?, album: Boolean): Float? {
        if (album) tags?.album?.let { return REPLAYGAIN_REF - it }
        tags?.track?.let { return REPLAYGAIN_REF - it }
        if (song == null) return null
        if (album) {
            // average of the album's songs we've measured
            val lib = Library.cached
            val same = lib?.songs?.filter { it.albumId == song.albumId }.orEmpty()
            val measured = same.mapNotNull { BeatMap.cached(ctx, it.id)?.lufs?.takeIf { l -> !l.isNaN() } }
            if (measured.size >= (same.size + 1) / 2 && measured.isNotEmpty()) return measured.average().toFloat()
        }
        return BeatMap.cached(ctx, song.id)?.lufs?.takeIf { !it.isNaN() }
    }

    /** Turn down as much as needed; turn up at most 6 dB (9 dB for the louder levels), the limiter keeps it clean. */
    fun gainFor(lufs: Float?): Float {
        if (lufs == null) return 0f
        val target = levels[level.value.coerceIn(0, levels.size - 1)].second
        return (target - lufs).coerceIn(-20f, if (level.value >= 2) 9f else 6f)
    }

    /** Works out the song's loudness if we don't know it yet (a second or two), in the background. */
    fun measure(ctx: Context, song: Song, then: () -> Unit) {
        scope.launch { BeatMap.analyze(ctx, song); withContext(Dispatchers.Main) { then() } }
    }

    fun measureAll(ctx: Context) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            val lib = Library.load(ctx)
            val todo = lib.songs.filter { BeatMap.cached(ctx, it.id) == null }
            var done = 0
            scan.value = 0 to todo.size
            for (s in todo) { BeatMap.analyze(ctx, s); done++; scan.value = done to todo.size }
            scan.value = null
        }
    }
    fun stopMeasuring() { scanJob?.cancel(); scan.value = null }
}

/**
 * Tuning (test 237, Jarrod): music is tuned to A = 440 Hz; some people prefer 432 Hz (or 444 Hz).
 * Shifts the pitch only; the speed stays the same.
 */
object Tuning {
    val choices = listOf(440f, 432f, 444f)
    val hz = MutableStateFlow(440f)
    fun load(ctx: Context) { hz.value = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getFloat("tuning_hz", 440f) }
    fun set(ctx: Context, v: Float) { hz.value = v; ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putFloat("tuning_hz", v).apply() }
    fun apply(player: Player) { player.playbackParameters = androidx.media3.common.PlaybackParameters(1f, hz.value / 440f) }
}

/** Applies the volume change to the player. Lives in the music service. */
class LevelApplier(private val ctx: Context, private val player: ExoPlayer) {
    private var enhancer: LoudnessEnhancer? = null
    private var session = 0
    var tags: Leveler.Tags? = null

    fun apply() {
        val mode = Leveler.mode.value
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val song = id?.let { Library.cached?.byId?.get(it) }
        if (mode == Leveler.OFF || id == null) { set(0f); Leveler.now.value = null; return }
        val lufs = Leveler.loudness(ctx, song, tags, mode == Leveler.ALBUM)
        if (lufs == null && song != null && tags?.track == null) {
            // not measured yet: measure now, then set the level (keep the current one meanwhile)
            Leveler.measure(ctx, song) { if (player.currentMediaItem?.mediaId == id.toString()) apply() }
            return
        }
        val g = Leveler.gainFor(lufs)
        set(g)
        Leveler.now.value = (lufs ?: Float.NaN) to g
    }

    /** Measure the next song in the queue ahead of time, so it starts at the right level. */
    fun prepareNext() {
        if (Leveler.mode.value == Leveler.OFF) return
        val i = player.nextMediaItemIndex
        if (i == androidx.media3.common.C.INDEX_UNSET) return
        val s = player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { Library.cached?.byId?.get(it) } ?: return
        if (BeatMap.cached(ctx, s.id) == null) Leveler.measure(ctx, s) {}
    }

    private fun set(db: Float) {
        player.volume = if (db < 0) 10f.pow(db / 20f) else 1f
        val boost = if (db > 0) (db * 100).toInt() else 0
        runCatching {
            val sid = player.audioSessionId
            if (enhancer == null || sid != session) {
                enhancer?.release(); enhancer = null
                if (boost == 0) return@runCatching
                enhancer = LoudnessEnhancer(sid); session = sid
            }
            enhancer?.setTargetGain(boost)
            enhancer?.enabled = boost > 0
        }
    }

    fun release() { runCatching { enhancer?.release() }; enhancer = null }

    val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { enhancer?.release(); enhancer = null; apply() }
    }
}
