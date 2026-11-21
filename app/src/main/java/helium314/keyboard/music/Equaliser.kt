// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package helium314.keyboard.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** One equaliser setting: 10 band gains in dB, bass boost 0..1, stereo width 0..2 (1 = normal). */
data class EqSet(val on: Boolean = false, val gains: List<Float> = List(10) { 0f }, val bass: Float = 0f, val width: Float = 1f, val preset: String = "Flat") {
    fun json() = JSONObject().put("on", on).put("g", JSONArray(gains.map { it.toDouble() })).put("b", bass.toDouble()).put("w", width.toDouble()).put("p", preset)
    companion object {
        fun from(o: JSONObject) = EqSet(o.optBoolean("on"), o.optJSONArray("g")?.let { a -> List(10) { a.optDouble(it, 0.0).toFloat() } } ?: List(10) { 0f },
            o.optDouble("b", 0.0).toFloat(), o.optDouble("w", 1.0).toFloat(), o.optString("p", "Flat"))
    }
}

/**
 * Test 311 (Jarrod: "a real built-in equaliser"): 10 bands, bass boost, wide stereo and presets, done by Open Waves itself
 * (not the phone's equaliser), so it works the same on every phone. Can keep separate settings for each thing you listen
 * on (phone speaker, wired headphones, each Bluetooth device such as earbuds or the car); it switches by itself.
 */
object Eq {
    /** test 311: Sing mode, takes the voice in the middle out so you can sing along (bass and drums mostly stay) */
    @Volatile var sing = false
    /** test 311: how loud the music is (1 = normal), lowered while the radio announcer talks */
    @Volatile var duck = 1f
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val PRESETS: List<Pair<String, List<Float>>> = listOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass reducer" to listOf(-6f, -5f, -3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Rock" to listOf(5f, 4f, 2f, 0f, -1f, 0f, 2f, 3f, 4f, 4f),
        "Pop" to listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, 0f, -1f),
        "Dance" to listOf(6f, 5f, 2f, 0f, 0f, -1f, 0f, 2f, 3f, 3f),
        "Hip-hop" to listOf(6f, 5f, 3f, 1f, -1f, -1f, 1f, 0f, 1f, 2f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 2f, 4f, 5f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Classical" to listOf(4f, 3f, 2f, 1f, 0f, 0f, 0f, 1f, 2f, 3f),
        "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f),
        "Loudness" to listOf(5f, 3f, 0f, 0f, -1f, 0f, 0f, 0f, 3f, 5f),
        "Late night" to listOf(-3f, -2f, -1f, 0f, 1f, 1f, 1f, 0f, -2f, -4f),
        "Small speaker" to listOf(-4f, -2f, 1f, 2f, 2f, 2f, 2f, 2f, 1f, 0f),
    )

    /** what the audio uses right now (read on the audio thread) */
    @Volatile var live = EqSet(); private set
    @Volatile var version = 0; private set
    /** the same for the screen */
    val state = mutableStateOf(EqSet())
    val deviceLabel = mutableStateOf("Phone speaker")
    val perDevice = mutableStateOf(true)
    private var key = "speaker"
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    private fun all(ctx: Context): JSONObject = runCatching { JSONObject(prefs(ctx).getString("eq_profiles", "{}")!!) }.getOrDefault(JSONObject())

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        perDevice.value = prefs(ctx).getBoolean("eq_per_device", true)
        deviceChanged(ctx)
    }

    /** Called when headphones, Bluetooth or the car connect or go away. */
    @Synchronized fun deviceChanged(ctx: Context) {
        val (k, label) = currentDevice(ctx)
        key = k; deviceLabel.value = label
        val o = all(ctx)
        val use = if (perDevice.value) o.optJSONObject(k) ?: o.optJSONObject("all") else o.optJSONObject("all")
        set(use?.let { EqSet.from(it) } ?: EqSet(), null)
    }

    fun setPerDevice(ctx: Context, on: Boolean) {
        perDevice.value = on
        prefs(ctx).edit().putBoolean("eq_per_device", on).apply()
        val o = all(ctx); o.put(if (on) key else "all", state.value.json()); prefs(ctx).edit().putString("eq_profiles", o.toString()).apply()
    }

    @Synchronized fun set(s: EqSet, ctx: Context?) {
        state.value = s; live = s; version++
        if (ctx != null) {
            val o = all(ctx); o.put(if (perDevice.value) key else "all", s.json())
            prefs(ctx).edit().putString("eq_profiles", o.toString()).apply()
        }
    }

    private fun currentDevice(ctx: Context): Pair<String, String> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }?.let {
            val name = it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth"
            return "bt:$name" to "Bluetooth · $name"
        }
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?.let { return "wired" to "Headphones" }
        return "speaker" to "Phone speaker"
    }

    /** ExoPlayer with the equaliser in its sound chain. */
    fun renderers(ctx: Context) = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(EqProcessor()))
                .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
    }

    /** Gain in dB of the whole setting at [f] Hz (for the curve on screen). */
    fun response(s: EqSet, f: Float, rate: Float = 48000f): Float {
        val filters = EqProcessor.design(s, rate)
        val w = 2 * PI * f / rate
        var db = 0.0
        for (c in filters) {
            val (b0, b1, b2, a1, a2) = c
            // |H(e^jw)|
            val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
            val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
            val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
            db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return (db + EqProcessor.preampDb(s)).toFloat()
    }
}

/** The equaliser itself: a chain of filters on the 16-bit sound, then wide stereo and a soft limiter so boosts never crackle. */
class EqProcessor : BaseAudioProcessor() {
    private var rate = 44100; private var ch = 2
    private var ver = -1
    private var coefs = emptyList<DoubleArray>()
    private var z = Array(2) { DoubleArray(0) }
    private var pre = 1.0
    private var width = 1f
    private var on = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate; ch = inputAudioFormat.channelCount; ver = -1
        return inputAudioFormat
    }

    override fun onFlush() { ver = -1 }

    private fun refresh() {
        val s = Eq.live
        ver = Eq.version
        on = s.on
        coefs = design(s, rate.toFloat()).map { doubleArrayOf(it.b0, it.b1, it.b2, it.a1, it.a2) }
        z = Array(ch) { DoubleArray(coefs.size * 2) }
        pre = 10.0.pow(preampDb(s) / 20.0)
        width = if (ch == 2) s.width else 1f
    }

    private var gain = 1.0
    // Sing mode: the middle's low end (bass, kick) is kept so the song doesn't go thin
    private var lpA = 0.0; private var lpB = 0.0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (ver != Eq.version) refresh()
        val sing = Eq.sing && ch == 2
        val duckTo = Eq.duck.toDouble().coerceIn(0.0, 1.0)
        if (!on && !sing && duckTo >= 0.999 && gain >= 0.999) { gain = 1.0; out.put(inputBuffer); out.flip(); return }
        val start = inputBuffer.position()
        val frames = n / (2 * ch)
        val cs = if (on) coefs else emptyList()
        val p = if (on) pre else 1.0
        val samples = DoubleArray(ch)
        val glide = 1.0 / (rate * 0.25) // about a quarter second to duck or come back
        val lpK = 1.0 - kotlin.math.exp(-2.0 * PI * 160.0 / rate)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = inputBuffer.getShort(start + (f * ch + c) * 2) / 32768.0
                val st = z[c]
                for (k in cs.indices) {
                    val q = cs[k]; val i = k * 2
                    val y = q[0] * x + st[i]
                    st[i] = q[1] * x - q[3] * y + st[i + 1]
                    st[i + 1] = q[2] * x - q[4] * y
                    x = y
                }
                samples[c] = x * p
            }
            if (ch == 2 && on && width != 1f) {
                val mid = (samples[0] + samples[1]) / 2; val side = (samples[0] - samples[1]) / 2 * width
                samples[0] = mid + side; samples[1] = mid - side
            }
            if (sing) {
                val mid = (samples[0] + samples[1]) / 2; val side = (samples[0] - samples[1]) / 2
                lpA += lpK * (mid - lpA); lpB += lpK * (lpA - lpB)
                samples[0] = lpB + side * 1.4; samples[1] = lpB - side * 1.4
            }
            if (gain != duckTo) gain = if (gain < duckTo) minOf(duckTo, gain + glide) else maxOf(duckTo, gain - glide)
            for (c in 0 until ch) {
                var v = samples[c] * gain
                // soft limiter above 0.9 so a big boost bends instead of clipping
                if (v > 0.9) v = 0.9 + 0.1 * tanh((v - 0.9) / 0.1) else if (v < -0.9) v = -0.9 - 0.1 * tanh((-v - 0.9) / 0.1)
                out.putShort((v * 32767).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    companion object {
        /** Lowers everything by about the biggest boost, so boosting doesn't make it clip. */
        fun preampDb(s: EqSet): Double {
            val peak = maxOf(0f, (s.gains.maxOrNull() ?: 0f), s.bass * 10f + (s.gains.take(2).maxOrNull() ?: 0f))
            return -peak * 0.8
        }

        fun design(s: EqSet, rate: Float): List<Biquad> {
            val list = ArrayList<Biquad>()
            for (i in 0 until 10) {
                val g = s.gains.getOrElse(i) { 0f }
                val f = Eq.FREQS[i]
                if (kotlin.math.abs(g) < 0.05f || f > rate * 0.45f) continue
                list += peaking(f.toDouble(), g.toDouble(), 1.41, rate.toDouble())
            }
            if (s.bass > 0.01f) list += lowShelf(90.0, s.bass * 10.0, rate.toDouble())
            return list
        }

        private fun peaking(f: Double, g: Double, q: Double, fs: Double): Biquad {
            val a = 10.0.pow(g / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
            val a0 = 1 + al / a
            return Biquad((1 + al * a) / a0, (-2 * c) / a0, (1 - al * a) / a0, (-2 * c) / a0, (1 - al / a) / a0)
        }

        private fun lowShelf(f: Double, g: Double, fs: Double): Biquad {
            val a = 10.0.pow(g / 40); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
            val a0 = (a + 1) + (a - 1) * c + sa
            return Biquad(a * ((a + 1) - (a - 1) * c + sa) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0, a * ((a + 1) - (a - 1) * c - sa) / a0,
                -2 * ((a - 1) + (a + 1) * c) / a0, ((a + 1) + (a - 1) * c - sa) / a0)
        }
    }
}

@Composable
internal fun EqualiserPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Eq.load(ctx)
    val s by Eq.state
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    fun change(n: EqSet) = Eq.set(n, ctx)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Equaliser", onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s.on) "On · ${s.preset}" else "Off", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (Eq.perDevice.value) "Settings for: ${Eq.deviceLabel.value}" else "Same settings for everything", fontSize = 13.sp, color = dim)
                }
                Switch(s.on, { change(s.copy(on = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
        item {
            // the curve: what the settings do from deep bass (left) to treble (right)
            val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))) {
                val h = size.height; val w = size.width
                drawLine(line, Offset(0f, h / 2), Offset(w, h / 2))
                val path = Path()
                for (x in 0..80) {
                    val f = 20f * (1000f).pow(x / 80f) // 20 Hz .. 20 kHz
                    val db = Eq.response(s, f).coerceIn(-15f, 15f)
                    val px = w * x / 80f; val py = h / 2 - db / 15f * (h / 2 - 6f)
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, if (s.on) Accent else line, style = Stroke(width = 3.dp.toPx()))
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Eq.PRESETS) { (name, g) ->
                    FilterChip(s.preset == name, { change(s.copy(on = true, gains = g, preset = name)) }, { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                }
            }
        }
        items(10) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Eq.LABELS[i] + " Hz", Modifier.width(64.dp), fontSize = 13.sp, color = dim)
                Slider(s.gains[i], { v -> change(s.copy(on = true, gains = s.gains.toMutableList().also { it[i] = (v * 2).toInt() / 2f }, preset = "Custom")) },
                    Modifier.weight(1f), valueRange = -12f..12f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("%+.1f".format(s.gains[i]), Modifier.width(48.dp), fontSize = 13.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Bass boost: ${(s.bass * 100).toInt()}%", fontSize = 15.sp)
                Slider(s.bass, { change(s.copy(on = true, bass = it)) }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("Wide stereo: ${(s.width * 100).toInt()}%  (100% = as recorded)", fontSize = 15.sp)
                Slider(s.width, { change(s.copy(on = true, width = (it * 20).toInt() / 20f)) }, valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Separate settings for each device", fontSize = 15.sp)
                    Text("Phone speaker, headphones, and each Bluetooth device (earbuds, car) remember their own. Switches by itself.", fontSize = 12.sp, color = dim)
                }
                Switch(Eq.perDevice.value, { Eq.setPerDevice(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ change(EqSet(on = s.on)) }) { Text("Reset", color = Accent) }
                TextButton({ openEqualiser(ctx) }) { Text("Phone's own equaliser", color = Accent) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
