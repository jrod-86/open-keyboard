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
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Open Keyboard Music (test 234, Jarrod): the beat map of one song, worked out on the phone.
 * [level] and [bass] are loudness (0..1) for every [STEP_MS] slice of the song; [beats] are the beat times in ms.
 * Made once per song by reading the whole file (a couple of seconds), then kept, so effects line up exactly
 * with what you hear (no lag), whatever the speaker or Bluetooth delay.
 */
class BeatMap(val level: FloatArray, val bass: FloatArray, val beats: LongArray) {
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
        private const val VERSION = 2

        private fun file(ctx: Context, id: Long) = File(ctx.cacheDir, "music/beats/$id.v$VERSION")

        fun cached(ctx: Context, id: Long): BeatMap? = runCatching {
            val f = file(ctx, id)
            if (!f.exists()) return null
            DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt(); val lv = FloatArray(n) { s.readFloat() }; val bs = FloatArray(n) { s.readFloat() }
                val b = s.readInt(); BeatMap(lv, bs, LongArray(b) { s.readLong() })
            }
        }.getOrNull()

        private fun save(ctx: Context, id: Long, m: BeatMap) = runCatching {
            val f = file(ctx, id); f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { s ->
                s.writeInt(m.level.size); m.level.forEach { s.writeFloat(it) }; m.bass.forEach { s.writeFloat(it) }
                s.writeInt(m.beats.size); m.beats.forEach { s.writeLong(it) }
            }
        }

        /** Reads the song and finds its beats. Slow-ish (1 to 3 seconds), so it runs in the background, once per song. */
        suspend fun analyze(ctx: Context, song: Song): BeatMap? = withContext(Dispatchers.Default) {
            cached(ctx, song.id)?.let { return@withContext it }
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
                var alpha = 1f - exp(-2f * Math.PI.toFloat() * 150f / rate) // low-pass around 150 Hz: kick drum and bass
                val info = MediaCodec.BufferInfo()
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
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val sb = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            sb.position(info.offset / 2); sb.limit((info.offset + info.size) / 2)
                            while (sb.remaining() >= channels) {
                                var v = 0f
                                for (ch in 0 until channels) v += sb.get()
                                v /= channels * 32768f
                                lp1 += alpha * (v - lp1); lp2 += alpha * (lp1 - lp2)
                                sumSq += v * v; bassSq += lp2 * lp2; count++
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
                val map = build(levels.toFloatArray(), basses.toFloatArray())
                save(ctx, song.id, map)
                map
            } catch (e: Exception) {
                null
            } finally {
                runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
            }
        }

        /** Scales loudness to 0..1 and picks beats: sudden jumps in bass (or overall loudness) above the recent average. */
        private fun build(level: FloatArray, bass: FloatArray): BeatMap {
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
            return BeatMap(lv, bs, beats.toLongArray())
        }
    }
}
