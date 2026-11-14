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
 * Draws pictures from words on the phone with stable-diffusion.cpp (MIT). Two models, each downloaded once,
 * pinned to a commit and SHA-256 checked:
 * - Quick: SDXS-512 (IDKiro et al., based on DreamShaper / SD 1.5, CreativeML OpenRAIL++-M: use limits apply), one step.
 * - Best quality: Z-Image Turbo (Tongyi-MAI, Apache-2.0) Q3_K GGUF by leejet, with Qwen3-4B-Instruct-2507
 *   (Apache-2.0) Q3_K_M GGUF by unsloth as its text model and the FLUX.1 decoder (Apache-2.0), 4 or 8 steps.
 */
object ImageGen {
    class Part(val name: String, val url: String, val sha: String, val size: Long)
    class Model(val id: String, val label: String, val name: String, val detail: String, val parts: List<Part>, val about: String) {
        val totalSize get() = parts.sumOf { it.size }
    }

    val QUICK = Model("quick", "Quick", "SDXS-512", "fast, 1 step, 680 MB", listOf(Part("sdxs-512-q8_0.gguf",
        "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/3144d898d61492f8382ffcabec055733fc5b2a0e/sdxs-512-tinySDdistilled_Q8_0.gguf",
        "409ab23582ee074c6b9d5395784fc0741b0599fb9d138686c69087c71678eb6a", 682847200L)),
        "The model is SDXS-512 (OpenRAIL++ licence), which asks that it isn't used to harm people, for example no fake pictures of real people meant to deceive.")

    val BEST = Model("best", "Best quality", "Z-Image Turbo", "best pictures, a few minutes, 5.6 GB", listOf(
        Part("z_image_turbo-Q3_K.gguf",
            "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/c61c0e422dc8b541b7548cf33a4ef8302b0f8085/z_image_turbo-Q3_K.gguf",
            "4b44bdaa7814f20d7cf144e3939bd93aa32f50660204dd0c2aea5c5376232980", 3143559104L),
        Part("Qwen3-4B-Instruct-2507-Q3_K_M.gguf",
            "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q3_K_M.gguf",
            "9c6e0763577125a994a9bea0bbd7a737ac4498b8a6a4e0f788727553af1806c9", 2075618400L),
        Part("ae.safetensors",
            "https://huggingface.co/Comfy-Org/z_image_turbo/resolve/6fc90a3b1b653e935a0d175e260736de25b84df5/split_files/vae/ae.safetensors",
            "afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38", 335304388L)),
        "The model is Z-Image Turbo (Apache 2.0 licence, free for any use).")

    val MODELS = listOf(QUICK, BEST)

    /** Told about each finished step while a picture is drawn. */
    @Volatile var progressListener: ((Int, Int) -> Unit)? = null

    @JvmStatic fun onProgress(step: Int, steps: Int) { progressListener?.invoke(step, steps) }

    /** Told when drawing moves to a new stage (conditioning, sampling, decoding…). */
    @Volatile var stageListener: ((String, Long, Long) -> Unit)? = null

    @JvmStatic fun onStage(stage: String, done: Long, total: Long) { stageListener?.invoke(stage, done, total) }

    private var libLoaded = false
    private var ctx = 0L
    private var loadedModel: Model? = null

    private fun dir(c: Context) = File(c.filesDir, "image_maker").apply { mkdirs() }
    private fun file(c: Context, p: Part) = File(dir(c), p.name)

    fun downloaded(c: Context, m: Model) = m.parts.all { file(c, it).length() == it.size }

    /** Downloads what's missing, carrying on from where a cut-off download stopped. */
    fun download(c: Context, m: Model, onProgress: (Float) -> Unit) {
        var done = 0L
        for (p in m.parts) {
            if (file(c, p).length() != p.size) downloadPart(c, p) { have -> onProgress((done + have).toFloat() / m.totalSize) }
            done += p.size
        }
        onProgress(1f)
    }

    private fun downloadPart(c: Context, p: Part, onBytes: (Long) -> Unit) {
        val part = File(dir(c), p.name + ".part")
        var have = part.length()
        val conn = URL(p.url).openConnection() as HttpURLConnection
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
                    if (have - lastShown > 4_000_000) { lastShown = have; onBytes(have) }
                }
            }
        }
        conn.disconnect()
        if (part.length() != p.size) throw IllegalStateException("download incomplete")
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != p.sha) { part.delete(); throw IllegalStateException("bad download") }
        part.renameTo(file(c, p))
    }

    @Synchronized
    private fun ensureLoaded(c: Context, m: Model): Long {
        if (!libLoaded) { System.loadLibrary("sdjni"); libLoaded = true }
        if (loadedModel !== m) close()
        if (ctx == 0L) {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            ctx = if (m === QUICK) nativeLoad(file(c, m.parts[0]).path, threads)
                else nativeLoadParts(file(c, m.parts[0]).path, file(c, m.parts[1]).path, file(c, m.parts[2]).path, threads)
            if (ctx == 0L) throw IllegalStateException("couldn't open the model")
            loadedModel = m
        }
        return ctx
    }

    /** Makes one 512×512 picture. Slow: call off the main thread. */
    fun generate(c: Context, m: Model, prompt: String, seed: Long, steps: Int): Bitmap {
        val h = ensureLoaded(c, m)
        val data = synchronized(this) {
            if (m === QUICK) nativeGenerate(h, prompt, "", 512, 512, 1, 1f, seed)
            else nativeGenerate(h, prompt, "", 512, 512, steps, 1f, seed)
        } ?: throw IllegalStateException("no picture came out")
        fun int(at: Int) = (data[at].toInt() and 255 shl 24) or (data[at + 1].toInt() and 255 shl 16) or
            (data[at + 2].toInt() and 255 shl 8) or (data[at + 3].toInt() and 255)
        val w = int(0); val ht = int(4)
        val px = IntArray(w * ht) { i ->
            val o = 8 + i * 3
            (0xFF shl 24) or (data[o].toInt() and 255 shl 16) or (data[o + 1].toInt() and 255 shl 8) or (data[o + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(px, w, ht, Bitmap.Config.ARGB_8888)
    }

    /** Stops the picture being drawn now; generate() then fails. */
    fun cancel() { val h = ctx; if (h != 0L) nativeCancel(h) }

    @Synchronized
    fun close() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L; loadedModel = null }
    }

    private external fun nativeLoad(model: String, threads: Int): Long
    private external fun nativeLoadParts(diffusion: String, llm: String, vae: String, threads: Int): Long
    private external fun nativeGenerate(ctx: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long): ByteArray?
    private external fun nativeCancel(ctx: Long)
    private external fun nativeFree(ctx: Long)
}
