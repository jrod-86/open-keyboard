// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Draws pictures from words on the phone with stable-diffusion.cpp (MIT) and SDXS-512 (IDKiro et al., based on
 * DreamShaper / Stable Diffusion 1.5, CreativeML OpenRAIL++-M licence: use limits apply, see the model card),
 * one step per picture. The model (683 MB GGUF, Q8_0 by concedo) is downloaded once, pinned and SHA-256 checked.
 */
object ImageGen {
    private const val MODEL_URL = "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/3144d898d61492f8382ffcabec055733fc5b2a0e/sdxs-512-tinySDdistilled_Q8_0.gguf"
    private const val MODEL_SHA = "409ab23582ee074c6b9d5395784fc0741b0599fb9d138686c69087c71678eb6a"
    const val MODEL_SIZE = 682847200L

    private var loaded = false
    private var ctx = 0L

    private fun dir(c: Context) = File(c.filesDir, "image_maker").apply { mkdirs() }
    private fun modelFile(c: Context) = File(dir(c), "sdxs-512-q8_0.gguf")

    fun downloaded(c: Context) = modelFile(c).length() == MODEL_SIZE

    fun deleteModel(c: Context) { close(); dir(c).deleteRecursively() }

    /** Downloads the model, carrying on from where a cut-off download stopped. */
    fun download(c: Context, onProgress: (Float) -> Unit) {
        val part = File(dir(c), "model.part")
        var have = part.length()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val code = conn.responseCode
        if (code == 200) have = 0 else if (code != 206) throw IllegalStateException("download failed ($code)")
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(have); out.seek(have)
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var lastShown = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); have += n
                    if (have - lastShown > 4_000_000) { lastShown = have; onProgress(have.toFloat() / MODEL_SIZE) }
                }
            }
        }
        conn.disconnect()
        if (part.length() != MODEL_SIZE) throw IllegalStateException("download incomplete")
        onProgress(1f)
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != MODEL_SHA) { part.delete(); throw IllegalStateException("bad download") }
        part.renameTo(modelFile(c))
    }

    @Synchronized
    private fun ensureLoaded(c: Context): Long {
        if (!loaded) { System.loadLibrary("sdjni"); loaded = true }
        if (ctx == 0L) {
            val cores = Runtime.getRuntime().availableProcessors()
            ctx = nativeLoad(modelFile(c).path, (cores - 2).coerceIn(2, 6))
            if (ctx == 0L) throw IllegalStateException("couldn't open the model")
        }
        return ctx
    }

    /** Makes one 512×512 picture. Slow: call off the main thread. */
    fun generate(c: Context, prompt: String, seed: Long): Bitmap {
        val h = ensureLoaded(c)
        val data = synchronized(this) { nativeGenerate(h, prompt, "", 512, 512, 1, 1f, seed) }
            ?: throw IllegalStateException("no picture came out")
        fun int(at: Int) = (data[at].toInt() and 255 shl 24) or (data[at + 1].toInt() and 255 shl 16) or
            (data[at + 2].toInt() and 255 shl 8) or (data[at + 3].toInt() and 255)
        val w = int(0); val ht = int(4)
        val px = IntArray(w * ht) { i ->
            val o = 8 + i * 3
            (0xFF shl 24) or (data[o].toInt() and 255 shl 16) or (data[o + 1].toInt() and 255 shl 8) or (data[o + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(px, w, ht, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun close() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L }
    }

    private external fun nativeLoad(model: String, threads: Int): Long
    private external fun nativeGenerate(ctx: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long): ByteArray?
    private external fun nativeCancel(ctx: Long)
    private external fun nativeFree(ctx: Long)
}
