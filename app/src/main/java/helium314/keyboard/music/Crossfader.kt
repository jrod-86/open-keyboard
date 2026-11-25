// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Open Waves test 299 / 356 (Jarrod): "Blend songs together" with true dual-deck (A/B) swapping.
 * A few seconds before a song ends on the active deck, the standby deck starts the next song cleanly from 0:00
 * and the two cross over. When the old song finishes fading out, the standby deck becomes the active deck
 * directly — zero mid-song seek, zero second-player handoff, zero audio glitch.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Crossfader(
    private val activeProvider: () -> ExoPlayer,
    private val standbyProvider: () -> ExoPlayer,
    private val musicAttrs: AudioAttributes,
    private val leveler: () -> LevelApplier?,
    private val onSwap: (newActive: ExoPlayer, oldActive: ExoPlayer) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private enum class Stage { IDLE, BLEND }
    private var stage = Stage.IDLE
    private var fromIndex = -1
    private var nextIndex = -1
    private var nextId = ""
    private var nextVol = 1f

    private val tick = object : Runnable {
        override fun run() {
            val nextMs = runCatching { step() }.getOrDefault(500L)
            handler.postDelayed(this, nextMs)
        }
    }

    fun start() { handler.post(tick) }
    fun release() { handler.removeCallbacks(tick); cancel() }

    private fun blendMs() = (MusicSettings.blendSec * 1000).toLong().coerceIn(2000, 12000)

    private fun step(): Long {
        if (!MusicSettings.blendOn) { if (stage != Stage.IDLE) cancel(); return 2000L }
        val active = activeProvider()
        val standby = standbyProvider()
        when (stage) {
            Stage.IDLE -> {
                if (!active.isPlaying || active.repeatMode == Player.REPEAT_MODE_ONE || !active.hasNextMediaItem()) return 2000L
                if (MusicState.sleepAt.value == -1L) return 1000L // sleep timer "end of this song"
                val dur = active.duration; val pos = active.currentPosition
                val blend = blendMs()
                if (dur == C.TIME_UNSET || dur < blend * 3) return 1000L
                val rem = dur - pos
                if (rem > blend + 3000) return 1000L
                if (rem > blend || rem < 500) return 100L
                // albums made to play without gaps (live albums, DJ mixes): the songs already run into each other
                val cur = active.currentMediaItem?.mediaId?.toLongOrNull()?.let { Library.cached?.byId?.get(it) }
                nextIndex = active.nextMediaItemIndex
                if (nextIndex == C.INDEX_UNSET) return 1000L
                val item = active.getMediaItemAt(nextIndex)
                val next = item.mediaId.toLongOrNull()?.let { Library.cached?.byId?.get(it) }
                if (cur != null && next != null && cur.albumId == next.albumId && next.track == cur.track + 1 && cur.album != "Unknown album") return 1000L
                fromIndex = active.currentMediaItemIndex
                nextId = item.mediaId
                nextVol = leveler()?.volumeFor(next) ?: 1f
                standby.setAudioAttributes(musicAttrs, false)
                standby.playbackParameters = active.playbackParameters
                standby.repeatMode = active.repeatMode
                standby.setMediaItem(item)
                standby.volume = 0f
                standby.prepare()
                standby.play()
                stage = Stage.BLEND
                return 50L
            }
            Stage.BLEND -> {
                if (active.currentMediaItemIndex != fromIndex) {
                    // the song ended by itself right as we finished blending: complete swap; anything else (you skipped): cancel
                    if (active.currentMediaItem?.mediaId == nextId) {
                        completeSwap(active, standby)
                        return 1000L
                    } else {
                        cancel()
                        return 500L
                    }
                }
                if (!active.playWhenReady) { if (standby.isPlaying) standby.pause(); return 250L }
                else if (!standby.playWhenReady) standby.play()
                val remaining = (active.duration - active.currentPosition).coerceAtLeast(0)
                val t = (1f - remaining.toFloat() / blendMs()).coerceIn(0f, 1f)
                leveler()?.fade = cos(t * PI / 2).toFloat()
                standby.volume = nextVol * sin(t * PI / 2).toFloat()
                if (remaining <= 200L) {
                    completeSwap(active, standby)
                    return 1000L
                }
                return 50L
            }
        }
    }

    /**
     * test 356 (Jarrod): True dual-deck swap. Standby is already playing the new song cleanly from 0:00,
     * so we never seek or touch its AudioTrack — we simply graft the rest of the queue around it, preserve
     * exact shuffle order, stop the faded-out old deck, and hand MediaSession to the new deck.
     */
    private fun completeSwap(active: ExoPlayer, standby: ExoPlayer) {
        val count = active.mediaItemCount
        if (count > 0) {
            val resolvedIdx = (0 until count).firstOrNull { active.getMediaItemAt(it).mediaId == nextId }
                ?: nextIndex.coerceIn(0, count - 1)
            val before = (0 until resolvedIdx).map { active.getMediaItemAt(it) }
            val after = ((resolvedIdx + 1) until count).map { active.getMediaItemAt(it) }
            if (before.isNotEmpty()) standby.addMediaItems(0, before)
            if (after.isNotEmpty()) standby.addMediaItems(after)
            val tl = active.currentTimeline
            if (active.shuffleModeEnabled && !tl.isEmpty) {
                val order = IntArray(count)
                var n = 0
                var idx = tl.getFirstWindowIndex(true)
                while (idx != C.INDEX_UNSET && n < count) {
                    order[n++] = idx
                    idx = tl.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
                }
                if (n == count) {
                    standby.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
                }
                standby.shuffleModeEnabled = true
            } else {
                standby.shuffleModeEnabled = false
            }
            standby.repeatMode = active.repeatMode
        }
        active.setAudioAttributes(musicAttrs, false)
        active.stop()
        active.clearMediaItems()
        active.volume = 1f

        standby.volume = nextVol
        standby.setAudioAttributes(musicAttrs, true)
        stage = Stage.IDLE
        android.util.Log.w("OpenWaves", "crossfade: seamless dual-deck swap into ${standby.currentMediaItem?.mediaMetadata?.title}")
        onSwap(standby, active)
    }

    private fun cancel() {
        runCatching {
            val standby = standbyProvider()
            standby.stop()
            standby.clearMediaItems()
        }
        leveler()?.fade = 1f
        stage = Stage.IDLE
    }
}
