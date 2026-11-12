// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Open Keyboard: key press sound packs, made from simple sound waves in code (no recordings),
 * so they are all ours to share. "system" uses the phone's own key click.
 */
object KeySounds {
    const val PREF = "open_keyboard_key_sound"
    enum class Kind { STANDARD, DELETE, SPACE, ENTER }

    val packs = listOf(
        "system" to "Phone's own click",
        "soft" to "Soft tap",
        "typewriter" to "Typewriter",
        "bubble" to "Bubble pop",
        "retro" to "Retro game",
        "wood" to "Wooden block",
        "piano" to "Piano notes",
    )

    private const val RATE = 44100
    private val player = Executors.newSingleThreadExecutor()
    private var loadedPack: String? = null
    private val tracks = HashMap<String, List<AudioTrack>>()
    private val nextIndex = HashMap<String, Int>()

    fun pack(ctx: Context) = ctx.prefs().getString(PREF, "system") ?: "system"

    /** Plays [kind] in the chosen pack. Returns false for the phone's own sound. */
    fun play(ctx: Context, code: Kind, volume: Float, packName: String = pack(ctx)): Boolean {
        if (packName == "system") return false
        val vol = if (volume < 0) 0.6f else volume.coerceIn(0f, 1f)
        player.execute {
            runCatching {
                if (loadedPack != packName) load(packName)
                val list = tracks[code.name] ?: return@runCatching
                val i = (nextIndex[code.name] ?: 0)
                val t = if (packName == "piano" && code == Kind.STANDARD) list[Random.nextInt(list.size)] else list[i % list.size]
                nextIndex[code.name] = i + 1
                t.setVolume(vol)
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.reloadStaticData()
                t.play()
            }
        }
        return true
    }

    private fun load(packName: String) {
        tracks.values.flatten().forEach { runCatching { it.release() } }
        tracks.clear()
        Kind.entries.forEach { kind ->
            val sounds = if (packName == "piano" && kind == Kind.STANDARD) piano.map { tone(it, 0.18, 9.0, harmonics = true) }
                else List(3) { synth(packName, kind) } // three copies so fast typing doesn't cut sounds off
            tracks[kind.name] = sounds.map { toTrack(it) }
        }
        loadedPack = packName
    }

    private val piano = listOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5)

    private fun synth(pack: String, kind: Kind): FloatArray = when (pack) {
        "soft" -> noise(when (kind) { Kind.SPACE -> 0.03; Kind.ENTER -> 0.05; else -> 0.022 }, 0.35, if (kind == Kind.SPACE) 0.25 else 0.6)
        "typewriter" -> when (kind) {
            Kind.ENTER -> mix(noise(0.03, 0.8, 0.9), tone(2093.0, 0.45, 6.0, harmonics = true, gain = 0.5))
            Kind.SPACE -> mix(noise(0.05, 0.6, 0.3), tone(110.0, 0.06, 40.0, gain = 0.6))
            else -> mix(noise(0.025, 0.9, 0.95), tone(180.0, 0.04, 60.0, gain = 0.5))
        }
        "bubble" -> when (kind) {
            Kind.DELETE -> sweep(900.0, 450.0, 0.06)
            Kind.SPACE -> sweep(350.0, 600.0, 0.07)
            Kind.ENTER -> mix(sweep(500.0, 1000.0, 0.07), sweep(800.0, 1500.0, 0.09, gain = 0.6))
            else -> sweep(600.0, 1150.0, 0.05)
        }
        "retro" -> when (kind) {
            Kind.DELETE -> square(440.0, 0.05)
            Kind.SPACE -> square(330.0, 0.04)
            Kind.ENTER -> mix(square(660.0, 0.06), square(990.0, 0.12, delay = 0.06))
            else -> square(880.0, 0.035)
        }
        "wood" -> when (kind) {
            Kind.DELETE -> mix(tone(260.0, 0.08, 45.0), noise(0.008, 0.5, 0.6))
            Kind.SPACE -> mix(tone(180.0, 0.1, 35.0), noise(0.01, 0.4, 0.4))
            Kind.ENTER -> mix(tone(520.0, 0.12, 30.0), tone(390.0, 0.14, 30.0, delay = 0.07))
            else -> mix(tone(420.0, 0.07, 55.0), noise(0.006, 0.5, 0.7))
        }
        else -> when (kind) { // piano: other keys
            Kind.DELETE -> tone(392.0, 0.2, 9.0, harmonics = true)
            Kind.SPACE -> tone(261.63, 0.2, 9.0, harmonics = true)
            Kind.ENTER -> mix(tone(523.25, 0.4, 5.0, harmonics = true), tone(659.25, 0.4, 5.0, harmonics = true), tone(783.99, 0.4, 5.0, harmonics = true))
            else -> tone(523.25, 0.18, 9.0, harmonics = true)
        }
    }

    private fun n(sec: Double) = (sec * RATE).toInt().coerceAtLeast(1)

    private fun tone(freq: Double, sec: Double, decay: Double, harmonics: Boolean = false, gain: Double = 1.0, delay: Double = 0.0): FloatArray {
        val off = n(delay); val out = FloatArray(off + n(sec))
        for (i in 0 until n(sec)) {
            val t = i.toDouble() / RATE
            var v = sin(2 * PI * freq * t)
            if (harmonics) v = 0.7 * v + 0.2 * sin(4 * PI * freq * t) + 0.1 * sin(6 * PI * freq * t)
            val attack = (t / 0.003).coerceAtMost(1.0)
            out[off + i] = (v * exp(-decay * t) * attack * gain * 0.8).toFloat()
        }
        return out
    }

    private fun sweep(from: Double, to: Double, sec: Double, gain: Double = 1.0): FloatArray {
        val out = FloatArray(n(sec)); var phase = 0.0
        for (i in out.indices) {
            val p = i.toDouble() / out.size
            phase += 2 * PI * (from + (to - from) * p) / RATE
            out[i] = (sin(phase) * sin(PI * p) * gain * 0.7).toFloat()
        }
        return out
    }

    private fun square(freq: Double, sec: Double, delay: Double = 0.0): FloatArray {
        val off = n(delay); val out = FloatArray(off + n(sec))
        for (i in 0 until n(sec)) {
            val t = i.toDouble() / RATE
            val v = if (sin(2 * PI * freq * t) >= 0) 1.0 else -1.0
            out[off + i] = (v * 0.25 * (1 - i.toDouble() / n(sec))).toFloat()
        }
        return out
    }

    /** A short burst of noise; [brightness] 0..1 controls how sharp it sounds. */
    private fun noise(sec: Double, gain: Double, brightness: Double): FloatArray {
        val out = FloatArray(n(sec)); var last = 0.0; val rnd = Random(7)
        for (i in out.indices) {
            val white = rnd.nextDouble() * 2 - 1
            last += (white - last) * brightness
            out[i] = (last * gain * exp(-i.toDouble() / out.size * 5)).toFloat()
        }
        return out
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        parts.forEach { p -> p.forEachIndexed { i, v -> out[i] += v } }
        val peak = out.maxOf { kotlin.math.abs(it) }
        if (peak > 0.95f) for (i in out.indices) out[i] = out[i] / peak * 0.95f
        return out
    }

    private fun toTrack(samples: FloatArray): AudioTrack {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }
}
