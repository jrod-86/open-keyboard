// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StemsTest {
    /** a stand-in for the four models: each "hears" a different part of the spectrum */
    private fun fake(mag: FloatArray): List<FloatArray> = List(4) { s ->
        FloatArray(mag.size) { i -> val k = i % 1024; mag[i] * (1f + s * k / 256f) * (if (s == 0) 2f else 1f) + s * 0.01f }
    }

    @Test fun stemsAddUpToTheSong() = runBlocking {
        val r = java.util.Random(3)
        val len = 44100 * 3 + 777
        val l = FloatArray(len) { (r.nextGaussian() * 0.15).toFloat() }
        val rr = FloatArray(len) { (kotlin.math.sin(it * 0.05) * 0.3 + r.nextGaussian() * 0.05).toFloat() }
        val out = File.createTempFile("stems", ".wav")
        StemDsp.separateWith(l, rr, out, {}) { fake(it) }
        // for checking against the model card's Python
        System.getenv("STEMS_DUMP")?.let { d ->
            File(d, "in.raw").writeBytes(ByteBuffer.allocate(len * 8).order(ByteOrder.LITTLE_ENDIAN).apply { for (i in 0 until len) { putFloat(l[i]); putFloat(rr[i]) } }.array())
            out.copyTo(File(d, "out.wav"), true)
        }
        val b = ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, b.getShort(22).toInt())
        val frames = (out.length() - 44) / 16
        assertEquals(len.toLong(), frames)
        var err = 0.0; var pow = 0.0
        for (f in 0 until len) {
            var a = 0f; var c = 0f
            for (s in 0 until 4) { a += b.getShort(44 + f * 16 + s * 4) / 32767f; c += b.getShort(44 + f * 16 + s * 4 + 2) / 32767f }
            err += (a - l[f]) * (a - l[f]) + (c - rr[f]) * (c - rr[f]); pow += l[f] * l[f] + rr[f] * rr[f]
        }
        val db = 10 * kotlin.math.log10(err / pow)
        assertTrue("sum of stems vs song: $db dB", db < -55)
        out.delete()
        Unit
    }

    @Test fun fftRoundTrip() {
        val f = Fft(16)
        val re = FloatArray(16) { it.toFloat() }; val im = FloatArray(16)
        f.run(re, im, false); f.run(re, im, true)
        for (i in 0 until 16) assertEquals(i.toFloat(), re[i], 1e-4f)
    }
}
