// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudnessTest {
    private fun measure(amp: Double, rate: Int): Float {
        val k = KWeight(rate, 1)
        val slices = ArrayList<Double>(); var sum = 0.0; var n = 0
        for (i in 0 until rate * 5) {
            val y = k.step(0, (amp * sin(2 * PI * 1000 * i / rate)).toFloat())
            sum += y * y
            if (++n >= rate / 10) { slices += sum / n; sum = 0.0; n = 0 }
        }
        return BeatMap.integrated(slices)
    }

    // ITU-R BS.1770: a full-scale 1 kHz sine in one channel reads -3.01 LUFS
    @Test fun sine() {
        assertEquals(-3.01f, measure(1.0, 48000), 0.15f)
        assertEquals(-9.03f, measure(0.5, 44100), 0.15f)
    }

    @Test fun gain() {
        Leveler.level.value = 1 // Normal, -14 LUFS
        assertEquals(-6f, Leveler.gainFor(-8f), 0.01f)
        assertEquals(6f, Leveler.gainFor(-25f), 0.01f) // quiet songs: at most +6 dB
        Leveler.level.value = 3 // Louder, -8 LUFS
        assertEquals(9f, Leveler.gainFor(-25f), 0.01f)
    }
}
