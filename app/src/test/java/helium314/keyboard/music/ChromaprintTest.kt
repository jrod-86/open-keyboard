// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Checks the Kotlin fingerprint against libchromaprint 1.5.1's output for the same audio (test 248). */
class ChromaprintTest {
    private fun check(name: String) {
        val pcmBytes = javaClass.getResourceAsStream("/chromaprint/$name.pcm")!!.readBytes()
        val (head, encoded, rawLine) = javaClass.getResourceAsStream("/chromaprint/$name.txt")!!.bufferedReader().readLines()
        val (rate, channels) = head.split(' ').map { it.toInt() }
        val sb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(sb.remaining()).also { sb.get(it) }
        val raw = Chromaprint.raw(Chromaprint.toMono(pcm, channels), rate)
        val expected = rawLine.trim().split(' ').map { it.toLong().toInt() }
        var bitErrors = 0
        for (i in 0 until minOf(raw.size, expected.size)) bitErrors += Integer.bitCount(raw[i] xor expected[i])
        println("$name: ${raw.size} vs ${expected.size} items, $bitErrors bit errors")
        assertEquals(expected.toList(), raw.toList())
        assertEquals(encoded, Chromaprint.encode(expected.toIntArray()))
    }

    @Test fun resampledMono() = check("a44k_mono")
    @Test fun stereo11k() = check("b11k_stereo")
}
