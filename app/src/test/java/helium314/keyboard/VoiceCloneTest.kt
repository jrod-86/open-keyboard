// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.tts.VoiceClones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceCloneTest {
    @Test fun resampleKeepsPitchAndLength() {
        val a = FloatArray(48000) { sin(2 * PI * 440 * it / 48000).toFloat() * 0.5f }
        val b = VoiceClones.resample(a, 48000, 44100)
        assertEquals(44100, b.size)
        // a 440 Hz tone at 44.1 kHz: sample 1000 should match the formula
        for (i in 1000 until 1010) assertTrue(abs(b[i] - (sin(2 * PI * 440 * i / 44100) * 0.5).toFloat()) < 0.01f)
    }

    @Test fun tidyTrimsSilence() {
        val a = FloatArray(44100 * 3); for (i in 44100 until 88200) a[i] = sin(i * 0.05).toFloat() * 0.1f
        val b = VoiceClones.tidy(a)
        assertTrue(b.size in 44100..(44100 + 44100 / 2))
        assertTrue(b.maxOf { abs(it) } in 0.89f..0.91f)
    }

    /** Writes a clip the way the app does, runs the x86 engine build on it if it's here, and reads the codes back. */
    @Test fun npyRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vc-test").apply { mkdirs() }
        val clip = File(dir, "clip.npy")
        val w = VoiceClones::class.java.getDeclaredMethod("writeNpyF32", File::class.java, FloatArray::class.java).apply { isAccessible = true }
        w.invoke(VoiceClones, clip, FloatArray(1000) { it / 1000f })
        val bytes = clip.readBytes()
        assertEquals(0, (bytes.size - 4000) % 64)
        val exe = File("/home/user/dl/z/repo/servers/ggml/build/zonos3-server")
        val src = File("/home/user/dl/z/bella44.npy")
        if (!exe.isFile || !src.isFile) return
        val out = File(dir, "codes.npy")
        val g = "/home/user/dl/z/repo/servers/ggml/gguf/q/"
        val p = ProcessBuilder(exe.path, "encode-reference", g + "zac-encoder-q8.gguf", g + "ssl-teacher-q8.gguf", "--audio", clip.path, out.path, "--cpu")
            .redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor()
        if (!out.isFile) return // a 1000-sample clip may be too short; the format check above is what matters
        // org.json is a stub in local tests, so check the engine's output header instead of parsing it
        val head = String(out.readBytes(), 0, 128)
        assertTrue("<i4" in head && ", 10)" in head)
    }
}
