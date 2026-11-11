// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Bell-like chimes for the mic, made in code (no sound files to licence):
 * a rising two-note chime when listening starts, a falling one when it stops.
 */
class VoiceChimes {
    private val rate = 44100
    private val onTrack = makeTrack(listOf(784.0 to 0.0, 1175.0 to 0.09)) // G5 then D6
    private val offTrack = makeTrack(listOf(1175.0 to 0.0, 784.0 to 0.09)) // D6 then G5

    /** How long the start chime lasts, so recording can begin after it. */
    val onLengthMs = 260L

    fun play(on: Boolean) {
        val t = (if (on) onTrack else offTrack) ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() {
        onTrack?.release()
        offTrack?.release()
    }

    /** Each note: frequency and start time in seconds. A soft overtone makes it sound like a bell. */
    private fun makeTrack(notes: List<Pair<Double, Double>>): AudioTrack? = runCatching {
        val length = (rate * 0.26).toInt()
        val samples = FloatArray(length)
        for ((freq, start) in notes) {
            val first = (start * rate).toInt()
            for (i in first until length) {
                val t = (i - first).toDouble() / rate
                val attack = minOf(1.0, t / 0.004)
                val env = attack * exp(-t * 14)
                val v = sin(2 * PI * freq * t) + 0.35 * sin(2 * PI * freq * 2.0 * t) + 0.12 * sin(2 * PI * freq * 3.0 * t)
                samples[i] += (v * env).toFloat()
            }
        }
        val peak = samples.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val pcm = ShortArray(length) { (samples[it] / peak * 0.9f * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT) // follows the media volume, so it's audible
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(length * 2)
            .build()
        track.write(pcm, 0, length)
        track
    }.getOrNull()
}
