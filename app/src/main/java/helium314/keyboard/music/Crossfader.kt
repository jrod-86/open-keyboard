// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Open Waves test 299 (Jarrod): "Blend songs together". A few seconds before a song ends, a second player starts the
 * next song quietly and the two cross over (the old one fades down as the new one comes up). At the end, the main
 * player jumps to the same spot in the new song and takes over, so the lock screen, notification and everything else
 * carry on as normal.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Crossfader(private val ctx: Context, private val main: ExoPlayer, private val leveler: () -> LevelApplier?) {
    private val handler = Handler(Looper.getMainLooper())
    private var helper: ExoPlayer? = null
    private enum class Stage { IDLE, BLEND, HANDOFF }
    private var stage = Stage.IDLE
    private var fromIndex = -1
    private var nextIndex = -1
    private var nextId = ""
    private var nextVol = 1f
    private var handoffAt = 0L
    private var rampFrom = 0L

    private val tick = object : Runnable {
        override fun run() { runCatching { step() }; handler.postDelayed(this, 50) }
    }

    fun start() { handler.post(tick) }
    fun release() { handler.removeCallbacks(tick); cancel(); helper?.release(); helper = null }

    private fun helper(): ExoPlayer = helper ?: ExoPlayer.Builder(ctx, Eq.renderers(ctx))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build().also { helper = it }

    private fun blendMs() = (MusicSettings.blendSec * 1000).toLong().coerceIn(2000, 12000)

    private fun step() {
        if (!MusicSettings.blendOn) { if (stage != Stage.IDLE) cancel(); return }
        when (stage) {
            Stage.IDLE -> {
                if (!main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
                if (MusicState.sleepAt.value == -1L) return // sleep timer "end of this song"
                val dur = main.duration; val pos = main.currentPosition
                val blend = blendMs()
                if (dur == C.TIME_UNSET || dur < blend * 3) return
                if (dur - pos > blend || dur - pos < 500) return
                // albums made to play without gaps (live albums, DJ mixes): the songs already run into each other
                val cur = main.currentMediaItem?.mediaId?.toLongOrNull()?.let { Library.cached?.byId?.get(it) }
                nextIndex = main.nextMediaItemIndex
                val item = main.getMediaItemAt(nextIndex)
                val next = item.mediaId.toLongOrNull()?.let { Library.cached?.byId?.get(it) }
                if (cur != null && next != null && cur.albumId == next.albumId && next.track == cur.track + 1 && cur.album != "Unknown album") return
                fromIndex = main.currentMediaItemIndex
                nextId = item.mediaId
                nextVol = leveler()?.volumeFor(next) ?: 1f
                val h = helper()
                h.playbackParameters = main.playbackParameters
                h.setMediaItem(item); h.volume = 0f; h.prepare(); h.play()
                stage = Stage.BLEND
            }
            Stage.BLEND -> {
                val h = helper ?: return cancel()
                if (main.currentMediaItemIndex != fromIndex) {
                    // the song ended by itself before we took over: take over now; anything else (you skipped): stop blending
                    if (main.currentMediaItem?.mediaId == nextId) handoff() else cancel()
                    return
                }
                if (!main.playWhenReady) { if (h.isPlaying) h.pause(); return } else if (!h.playWhenReady) h.play()
                val remaining = (main.duration - main.currentPosition).coerceAtLeast(0)
                val t = (1f - remaining.toFloat() / blendMs()).coerceIn(0f, 1f)
                leveler()?.fade = cos(t * PI / 2).toFloat()
                h.volume = nextVol * sin(t * PI / 2).toFloat()
                if (remaining <= 250) handoff()
            }
            Stage.HANDOFF -> {
                val h = helper ?: return cancel()
                val now = System.currentTimeMillis()
                val onNext = main.currentMediaItem?.mediaId == nextId
                if (!onNext && now - handoffAt > 500) return cancel() // you skipped somewhere else meanwhile
                if (rampFrom == 0L) {
                    if (onNext && main.isPlaying && main.playbackState == Player.STATE_READY) rampFrom = now
                    else if (now - handoffAt > 1500) { leveler()?.fade = 1f; rampFrom = now } // never sit muted
                    return
                }
                val k = ((now - rampFrom) / 250f).coerceIn(0f, 1f)
                leveler()?.fade = k
                h.volume = nextVol * (1f - k)
                if (k >= 1f) { h.stop(); h.clearMediaItems(); stage = Stage.IDLE }
            }
        }
    }

    private fun handoff() {
        val h = helper ?: return cancel()
        val target = h.currentPosition + 60 // the main player starts a moment later
        leveler()?.fade = 0f
        h.volume = nextVol
        if (main.currentMediaItem?.mediaId == nextId) main.seekTo(target) else main.seekTo(nextIndex, target)
        handoffAt = System.currentTimeMillis(); rampFrom = 0L
        stage = Stage.HANDOFF
    }

    private fun cancel() {
        helper?.let { runCatching { it.stop(); it.clearMediaItems() } }
        leveler()?.fade = 1f
        stage = Stage.IDLE
    }
}
