// SPDX-License-Identifier: GPL-3.0-only
// Kotlin port of Chromaprint 1.5.1 (https://github.com/acoustid/chromaprint, LGPL-2.1-or-later, by Lukáš Lalinský),
// including its resampler taken from FFmpeg (LGPL-2.1-or-later). Used here under the GPL-3.0 as the LGPL allows.
package helium314.keyboard.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Test 248 (Jarrod): a song's "musical fingerprint", the same one AcoustID uses, so a song can be found by how it
 * sounds instead of by its name. Pure maths, no Android, so it is unit-tested against the real libchromaprint.
 */
object Chromaprint {
    const val SAMPLE_RATE = 11025
    private const val FRAME = 4096
    private const val STEP = FRAME / 3 // 1365: chromaprint's frame overlap is FRAME - FRAME / 3
    private const val ALGORITHM = 1 // CHROMAPRINT_ALGORITHM_TEST2, the default

    private class Classifier(val type: Int, val y: Int, val height: Int, val width: Int, val t0: Double, val t1: Double, val t2: Double)

    private val classifiers = arrayOf(
        Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )
    private val chromaCoefficients = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)
    private const val MAX_FILTER_WIDTH = 16

    /** Mixes interleaved 16-bit audio to mono the way chromaprint does (integer average). */
    fun toMono(data: ShortArray, channels: Int): ShortArray {
        if (channels == 1) return data
        val n = data.size / channels
        return ShortArray(n) { f ->
            var sum = 0
            for (c in 0 until channels) sum += data[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** The raw fingerprint: one 32-bit number for every ~0.124 s of audio. */
    fun raw(mono: ShortArray, rate: Int): IntArray {
        val audio = if (rate == SAMPLE_RATE) mono else resample(mono, rate)
        val window = DoubleArray(FRAME) { (0.54 - 0.46 * cos(it * 2.0 * PI / (FRAME - 1))) / Short.MAX_VALUE }
        // chroma band of each FFT bin, between 28 Hz and 3520 Hz
        val minIndex = max(1, (FRAME * 28.0 / SAMPLE_RATE).roundToInt())
        val maxIndex = min(FRAME / 2, (FRAME * 3520.0 / SAMPLE_RATE).roundToInt())
        val notes = IntArray(FRAME)
        for (i in minIndex until maxIndex) {
            val freq = i.toDouble() * SAMPLE_RATE / FRAME
            val octave = ln(freq / (440.0 / 16.0)) / ln(2.0)
            notes[i] = (12 * (octave - floor(octave))).toInt()
        }
        val re = DoubleArray(FRAME); val im = DoubleArray(FRAME)
        val history = ArrayList<DoubleArray>() // last 5 chroma vectors
        val rows = ArrayList<DoubleArray>() // integral image rows (cumulative)
        val out = ArrayList<Int>()
        var pos = 0
        while (pos + FRAME <= audio.size) {
            for (i in 0 until FRAME) { re[i] = audio[pos + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val chroma = DoubleArray(12)
            for (i in minIndex until maxIndex) chroma[notes[i]] += re[i] * re[i] + im[i] * im[i]
            history += chroma
            if (history.size > 5) history.removeAt(0)
            if (history.size == 5) {
                val f = DoubleArray(12)
                for (b in 0 until 12) for (j in 0 until 5) f[b] += history[j][b] * chromaCoefficients[j]
                var norm = 0.0
                for (v in f) norm += v * v
                norm = if (norm > 0) sqrt(norm) else 0.0
                if (norm < 0.01) f.fill(0.0) else for (b in 0 until 12) f[b] /= norm
                // integral image row: running sum along the bands, plus the row before
                val row = DoubleArray(12)
                var acc = 0.0
                for (b in 0 until 12) { acc += f[b]; row[b] = acc }
                rows.lastOrNull()?.let { prev -> for (b in 0 until 12) row[b] += prev[b] }
                rows += row
                if (rows.size >= MAX_FILTER_WIDTH) out += subFingerprint(rows, rows.size - MAX_FILTER_WIDTH)
            }
            pos += STEP
        }
        return out.toIntArray()
    }

    private fun area(rows: List<DoubleArray>, r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val row2 = rows[r2 - 1]
        if (r1 == 0) return if (c1 == 0) row2[c2 - 1] else row2[c2 - 1] - row2[c1 - 1]
        val row1 = rows[r1 - 1]
        return if (c1 == 0) row2[c2 - 1] - row1[c2 - 1] else row2[c2 - 1] - row1[c2 - 1] - row2[c1 - 1] + row1[c1 - 1]
    }

    private fun subLog(a: Double, b: Double) = ln((1.0 + a) / (1.0 + b))

    private fun filter(rows: List<DoubleArray>, c: Classifier, x: Int): Double {
        val y = c.y; val w = c.width; val h = c.height
        return when (c.type) {
            0 -> subLog(area(rows, x, y, x + w, y + h), 0.0)
            1 -> { val h2 = h / 2; subLog(area(rows, x, y + h2, x + w, y + h), area(rows, x, y, x + w, y + h2)) }
            2 -> { val w2 = w / 2; subLog(area(rows, x + w2, y, x + w, y + h), area(rows, x, y, x + w2, y + h)) }
            3 -> {
                val w2 = w / 2; val h2 = h / 2
                subLog(area(rows, x, y + h2, x + w2, y + h) + area(rows, x + w2, y, x + w, y + h2),
                    area(rows, x, y, x + w2, y + h2) + area(rows, x + w2, y + h2, x + w, y + h))
            }
            4 -> {
                val h3 = h / 3
                subLog(area(rows, x, y + h3, x + w, y + 2 * h3), area(rows, x, y, x + w, y + h3) + area(rows, x, y + 2 * h3, x + w, y + h))
            }
            else -> {
                val w3 = w / 3
                subLog(area(rows, x + w3, y, x + 2 * w3, y + h), area(rows, x, y, x + w3, y + h) + area(rows, x + 2 * w3, y, x + w, y + h))
            }
        }
    }

    private val gray = intArrayOf(0, 1, 3, 2)

    private fun subFingerprint(rows: List<DoubleArray>, offset: Int): Int {
        var bits = 0
        for (c in classifiers) {
            val v = filter(rows, c, offset)
            val q = if (v < c.t1) (if (v < c.t0) 0 else 1) else (if (v < c.t2) 2 else 3)
            bits = (bits shl 2) or gray[q]
        }
        return bits
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ---- FFmpeg's av_resample (resample2.c), as chromaprint sets it up: 16 taps, 256 phases, cutoff 0.8 ----
    private fun bessel(x0: Double): Double {
        var v = 1.0; var last = 0.0; var t = 1.0; var i = 1
        val x = x0 * x0 / 4
        while (v != last) { last = v; t *= x / (i * i); v += t; i++ }
        return v
    }

    fun resample(src: ShortArray, inRate: Int): ShortArray {
        val outRate = SAMPLE_RATE
        val phaseShift = 8; val phaseCount = 1 shl phaseShift; val phaseMask = phaseCount - 1
        val factor = min(outRate * 0.8 / inRate, 1.0)
        val len = max(ceil(16 / factor).toInt(), 1)
        val bank = IntArray(len * (phaseCount + 1))
        val center = (len - 1) / 2
        val tab = DoubleArray(len)
        for (ph in 0 until phaseCount) {
            var norm = 0.0
            for (i in 0 until len) {
                var x = PI * ((i - center).toDouble() - ph.toDouble() / phaseCount) * factor
                var y = if (x == 0.0) 1.0 else sin(x) / x
                val w = 2.0 * x / (factor * len * PI)
                y *= bessel(9 * sqrt(max(1 - w * w, 0.0)))
                tab[i] = y; norm += y
            }
            for (i in 0 until len) bank[ph * len + i] = Math.rint((tab[i] * (1 shl 15) / norm).toFloat().toDouble()).toInt().coerceIn(-32768, 32767) // lrintf
        }
        val srcIncr = outRate
        val dstIncrTotal = inRate * phaseCount
        val dstIncrFrac = dstIncrTotal % srcIncr
        val dstIncr = dstIncrTotal / srcIncr
        var index = -phaseCount * ((len - 1) / 2)
        var frac = 0
        val out = ShortArray((src.size.toLong() * outRate / inRate + 16).toInt())
        var n = 0
        while (n < out.size) {
            val f = len * (index and phaseMask)
            val si = index shr phaseShift
            var v = 0L
            if (si < 0) {
                for (i in 0 until len) v += src[abs(si + i) % src.size] * bank[f + i]
            } else if (si + len > src.size) {
                break
            } else {
                for (i in 0 until len) v += src[si + i] * bank[f + i]
            }
            v = (v + (1 shl 14)) shr 15
            out[n++] = v.coerceIn(-32768, 32767).toShort()
            frac += dstIncrFrac
            index += dstIncr
            if (frac >= srcIncr) { frac -= srcIncr; index++ }
        }
        return out.copyOf(n)
    }

    // ---- compressed form that AcoustID takes ----
    fun encode(fp: IntArray): String {
        val normal = ArrayList<Int>(); val exceptional = ArrayList<Int>()
        for (i in fp.indices) {
            var x = if (i == 0) fp[0] else fp[i] xor fp[i - 1]
            var bit = 1; var lastBit = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val value = bit - lastBit
                    if (value >= 7) { normal += 7; exceptional += value - 7 } else normal += value
                    lastBit = bit
                }
                x = x ushr 1; bit++
            }
            normal += 0
        }
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(ALGORITHM); bytes.write((fp.size shr 16) and 255); bytes.write((fp.size shr 8) and 255); bytes.write(fp.size and 255)
        pack(normal, 3, bytes); pack(exceptional, 5, bytes)
        return base64(bytes.toByteArray())
    }

    private fun pack(values: List<Int>, bits: Int, out: java.io.ByteArrayOutputStream) {
        var acc = 0L; var n = 0
        for (v in values) {
            acc = acc or ((v.toLong() and ((1L shl bits) - 1)) shl n); n += bits
            while (n >= 8) { out.write((acc and 255).toInt()); acc = acc ushr 8; n -= 8 }
        }
        if (n > 0) out.write((acc and 255).toInt())
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun base64(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (b.size - i >= 3) {
            val s0 = b[i].toInt() and 255; val s1 = b[i + 1].toInt() and 255; val s2 = b[i + 2].toInt() and 255
            sb.append(B64[s0 shr 2]).append(B64[((s0 shl 4) or (s1 shr 4)) and 63]).append(B64[((s1 shl 2) or (s2 shr 6)) and 63]).append(B64[s2 and 63])
            i += 3
        }
        when (b.size - i) {
            2 -> { val s0 = b[i].toInt() and 255; val s1 = b[i + 1].toInt() and 255
                sb.append(B64[s0 shr 2]).append(B64[((s0 shl 4) or (s1 shr 4)) and 63]).append(B64[(s1 shl 2) and 63]) }
            1 -> { val s0 = b[i].toInt() and 255; sb.append(B64[s0 shr 2]).append(B64[(s0 shl 4) and 63]) }
        }
        return sb.toString()
    }
}
