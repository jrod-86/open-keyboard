// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.sqrt

/**
 * Open Keyboard Music (test 234, Jarrod): the beat map of one song, worked out on the phone.
 * [level] and [bass] are loudness (0..1) for every [STEP_MS] slice of the song; [beats] are the beat times in ms.
 * Made once per song by reading the whole file (a couple of seconds), then kept, so effects line up exactly
 * with what you hear (no lag), whatever the speaker or Bluetooth delay.
 */
class BeatMap(val level: FloatArray, val bass: FloatArray, val beats: LongArray, val lufs: Float = Float.NaN,
    /** test 299 (Moods): share of treble (above ~2.5 kHz), share of bass (below ~150 Hz), and zero crossings per second.
     *  NaN in beat maps made before test 299. */
    val bright: Float = Float.NaN, val bassShare: Float = Float.NaN, val zcr: Float = Float.NaN) {
    private fun at(arr: FloatArray, ms: Long): Float {
        if (arr.isEmpty()) return 0f
        val f = ms.toFloat() / STEP_MS
        val i = f.toInt().coerceIn(0, arr.size - 1)
        val j = (i + 1).coerceAtMost(arr.size - 1)
        val t = (f - i).coerceIn(0f, 1f)
        return arr[i] * (1 - t) + arr[j] * t
    }
    fun level(ms: Long) = at(level, ms)
    fun bass(ms: Long) = at(bass, ms)

    /** Index of the last beat at or before [ms], or -1. */
    fun lastBeat(ms: Long): Int {
        var lo = 0; var hi = beats.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (beats[m] <= ms) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    /** 1 right on a beat, fading to 0 over [decayMs]. */
    fun pulse(ms: Long, decayMs: Float = 220f): Float {
        val i = lastBeat(ms)
        if (i < 0) return 0f
        return exp(-(ms - beats[i]) / decayMs).coerceIn(0f, 1f)
    }

    companion object {
        const val STEP_MS = 40
        private const val VERSION = 3

        private fun file(ctx: Context, id: Long) = File(ctx.cacheDir, "music/beats/$id.v$VERSION")

        fun cached(ctx: Context, id: Long): BeatMap? = runCatching {
            val f = file(ctx, id)
            if (!f.exists()) return null
            DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt(); val lv = FloatArray(n) { s.readFloat() }; val bs = FloatArray(n) { s.readFloat() }
                val b = s.readInt(); val beats = LongArray(b) { s.readLong() }
                val lufs = s.readFloat()
                if (s.available() >= 12) BeatMap(lv, bs, beats, lufs, s.readFloat(), s.readFloat(), s.readFloat()) else BeatMap(lv, bs, beats, lufs)
            }
        }.getOrNull()

        private fun save(ctx: Context, id: Long, m: BeatMap) = runCatching {
            val f = file(ctx, id); f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { s ->
                s.writeInt(m.level.size); m.level.forEach { s.writeFloat(it) }; m.bass.forEach { s.writeFloat(it) }
                s.writeInt(m.beats.size); m.beats.forEach { s.writeLong(it) }
                s.writeFloat(m.lufs)
                s.writeFloat(m.bright); s.writeFloat(m.bassShare); s.writeFloat(m.zcr)
            }
        }

        /** Reads the song and finds its beats. Slow-ish (1 to 3 seconds), so it runs in the background, once per song. */
        private val locks = HashMap<Long, Mutex>()

        /** Works out the song's beats and loudness once (the player and the screen may both ask at the same time). */
        suspend fun analyze(ctx: Context, song: Song, needFeel: Boolean = false): BeatMap? {
            val lock = synchronized(locks) { locks.getOrPut(song.id) { Mutex() } }
            return try { lock.withLock { cached(ctx, song.id)?.takeIf { !needFeel || !it.bright.isNaN() } ?: decode(ctx, song) } } finally { synchronized(locks) { if (!lock.isLocked) locks.remove(song.id) } }
        }

        private suspend fun decode(ctx: Context, song: Song): BeatMap? = withContext(Dispatchers.Default) {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(ctx, song.uri, null)
                val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return@withContext null
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val c = MediaCodec.createDecoderByType(mime); codec = c
                c.configure(fmt, null, null, 0); c.start()

                val levels = ArrayList<Float>(8000); val basses = ArrayList<Float>(8000)
                var sliceLen = rate * STEP_MS / 1000
                var sumSq = 0.0; var bassSq = 0.0; var count = 0
                var lp1 = 0f; var lp2 = 0f
                // test 299: treble and zero crossings for Moods (how bright or mellow a song sounds)
                var lt1 = 0f; var lt2 = 0f; var lastV = 0f
                var allSq = 0.0; var allBass = 0.0; var allTreble = 0.0; var crossings = 0L; var frames = 0L
                var alphaT = 1f - exp(-2f * Math.PI.toFloat() * 2500f / rate)
                var alpha = 1f - exp(-2f * Math.PI.toFloat() * 150f / rate) // low-pass around 150 Hz: kick drum and bass
                // loudness the way radio and streaming services measure it (EBU R128 / ITU-R BS.1770), per 100 ms slice
                var kw = KWeight(rate, channels)
                val loud = ArrayList<Double>(8000)
                var kSum = 0.0; var kCount = 0; var kSlice = rate / 10
                val info = MediaCodec.BufferInfo()
                var pcm = ShortArray(16384)
                var inDone = false; var outDone = false
                while (!outDone) {
                    coroutineContext.ensureActive()
                    if (!inDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = c.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sliceLen = rate * STEP_MS / 1000
                        alpha = 1f - exp(-2f * Math.PI.toFloat() * 150f / rate)
                        alphaT = 1f - exp(-2f * Math.PI.toFloat() * 2500f / rate)
                        kw = KWeight(rate, channels); kSlice = rate / 10
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val sb = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            sb.position(info.offset / 2); sb.limit((info.offset + info.size) / 2)
                            // test 310 (Jarrod: Moods took hours): copy the whole block out at once; reading the
                            // buffer one sample at a time was most of the time
                            val nShorts = sb.remaining()
                            if (pcm.size < nShorts) pcm = ShortArray(nShorts)
                            sb.get(pcm, 0, nShorts)
                            var p = 0
                            while (p + channels <= nShorts) {
                                var v = 0f
                                for (ch in 0 until channels) {
                                    val x = pcm[p++] / 32768f
                                    v += x
                                    val y = kw.step(ch, x)
                                    kSum += y * y
                                }
                                v /= channels
                                if (++kCount >= kSlice) { loud += kSum / kCount; kSum = 0.0; kCount = 0 }
                                lp1 += alpha * (v - lp1); lp2 += alpha * (lp1 - lp2)
                                sumSq += v * v; bassSq += lp2 * lp2; count++
                                lt1 += alphaT * (v - lt1); lt2 += alphaT * (lt1 - lt2)
                                val hi = v - lt2
                                allSq += v * v; allBass += lp2 * lp2; allTreble += hi * hi; frames++
                                if ((v >= 0f) != (lastV >= 0f)) crossings++
                                lastV = v
                                if (count >= sliceLen) {
                                    levels += sqrt(sumSq / count).toFloat(); basses += sqrt(bassSq / count).toFloat()
                                    sumSq = 0.0; bassSq = 0.0; count = 0
                                }
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
                val total = allSq.coerceAtLeast(1e-9)
                val map = build(levels.toFloatArray(), basses.toFloatArray(), integrated(loud),
                    (allTreble / total).toFloat(), (allBass / total).toFloat(), if (frames > 0) crossings * rate.toFloat() / frames else Float.NaN)
                save(ctx, song.id, map)
                map
            } catch (e: Exception) {
                null
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
            }
        }

        /** Scales loudness to 0..1 and picks beats: sudden jumps in bass (or overall loudness) above the recent average. */
        /** Gated loudness of the whole song in LUFS, from 100 ms slices (400 ms blocks, 75% overlap). */
        fun integrated(slices: List<Double>): Float {
            if (slices.size < 4) return Float.NaN
            val blocks = DoubleArray(slices.size - 3) { i -> (slices[i] + slices[i + 1] + slices[i + 2] + slices[i + 3]) / 4 }
            fun lufs(ms: Double) = -0.691 + 10 * log10(ms)
            val abs = blocks.filter { it > 0 && lufs(it) > -70 }
            if (abs.isEmpty()) return Float.NaN
            val relGate = lufs(abs.average()) - 10
            val gated = abs.filter { lufs(it) > relGate }
            return lufs(gated.ifEmpty { abs }.average()).toFloat()
        }

        private fun build(level: FloatArray, bass: FloatArray, lufs: Float, bright: Float = Float.NaN, bassShare: Float = Float.NaN, zcr: Float = Float.NaN): BeatMap {
            fun norm(a: FloatArray): FloatArray {
                if (a.isEmpty()) return a
                val top = a.sorted()[(a.size * 0.98).toInt().coerceAtMost(a.size - 1)].coerceAtLeast(1e-4f)
                return FloatArray(a.size) { (a[it] / top).coerceIn(0f, 1f) }
            }
            val lv = norm(level); val bs = norm(bass)
            // onset strength: how much louder this slice is than the last one (bass counts most)
            val flux = FloatArray(lv.size) { i ->
                if (i == 0) 0f else (bs[i] - bs[i - 1]).coerceAtLeast(0f) * 0.7f + (lv[i] - lv[i - 1]).coerceAtLeast(0f) * 0.3f
            }
            val beats = ArrayList<Long>()
            val win = 1000 / STEP_MS // compare with the last ~1 second, and the next ~0.5 second
            val minGap = 280 / STEP_MS
            var last = -minGap
            for (i in 1 until flux.size - 1) {
                if (flux[i] < flux[i - 1] || flux[i] < flux[i + 1]) continue // only peaks
                var s = 0f; var n = 0
                for (j in (i - win).coerceAtLeast(0)..(i + win / 2).coerceAtMost(flux.size - 1)) { s += flux[j]; n++ }
                val mean = s / n
                if (flux[i] > mean * 1.6f + 0.02f && i - last >= minGap) { beats += i.toLong() * STEP_MS; last = i }
            }
            return BeatMap(lv, bs, beats.toLongArray(), lufs, bright, bassShare, zcr)
        }
    }
}

/** The "K" filter from ITU-R BS.1770 (a gentle treble lift plus a bass cut), worked out for any sample rate. */
class KWeight(rate: Int, channels: Int) {
    private val b = DoubleArray(6); private val a = DoubleArray(4) // two filters: b0 b1 b2, a1 a2
    private val z = Array(channels) { DoubleArray(4) }
    init {
        val fs = rate.toDouble()
        // stage 1: high shelf (same maths as libebur128; gives exactly the ITU numbers at 48 kHz)
        run {
            val g = 3.999843853973347; val q = 0.7071752369554196; val fc = 1681.974450955533
            val k = tan(Math.PI * fc / fs); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            b[0] = (vh + vb * k / q + k * k) / a0; b[1] = 2 * (k * k - vh) / a0; b[2] = (vh - vb * k / q + k * k) / a0
            a[0] = 2 * (k * k - 1) / a0; a[1] = (1 - k / q + k * k) / a0
        }
        // stage 2: high pass
        run {
            val q = 0.5003270373238773; val fc = 38.13547087602444
            val k = tan(Math.PI * fc / fs); val a0 = 1 + k / q + k * k
            b[3] = 1.0; b[4] = -2.0; b[5] = 1.0
            a[2] = 2 * (k * k - 1) / a0; a[3] = (1 - k / q + k * k) / a0
        }
    }
    fun step(ch: Int, x: Float): Double {
        val s = z[ch]
        val y1 = b[0] * x + s[0]; s[0] = b[1] * x - a[0] * y1 + s[1]; s[1] = b[2] * x - a[1] * y1
        val y2 = b[3] * y1 + s[2]; s[2] = b[4] * y1 - a[2] * y2 + s[3]; s[3] = b[5] * y1 - a[3] * y2
        return y2
    }
}
