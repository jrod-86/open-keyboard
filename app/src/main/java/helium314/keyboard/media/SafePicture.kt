// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Image maker safeguard: looks at each finished picture on the phone and holds back explicit ones.
 * Model: AdamCodd/vit-base-nsfw-detector (Apache-2.0), 8-bit ONNX, downloaded with the image models (see [ImageGen.SAFETY]).
 */
object SafePicture {
    private const val LIMIT = 0.4f // a bit stricter than the model's own 0.5
    private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    /** Chance (0..1) that [bmp] is explicit. Throws if the check can't run, so a picture is never shown unchecked. */
    @Synchronized
    fun nsfw(file: File, bmp: Bitmap): Float {
        val s = session ?: env.createSession(file.path, OrtSession.SessionOptions()).also { session = it }
        val size = 384
        val small = Bitmap.createScaledBitmap(bmp, size, size, true)
        val px = IntArray(size * size).also { small.getPixels(it, 0, size, 0, 0, size, size) }
        val input = FloatBuffer.allocate(3 * size * size)
        // ViT input: channels first, scaled to -1..1
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (p in px) input.put(((p shr shift) and 255) / 127.5f - 1f)
        }
        input.rewind()
        val logits = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            s.run(mapOf("pixel_values" to t)).use { out -> (out[0] as OnnxTensor).floatBuffer.let { floatArrayOf(it[0], it[1]) } }
        }
        val p = 1f / (1f + exp(logits[0] - logits[1]))
        Log.i("ImageMaker", "picture check: %.3f".format(p))
        return p
    }

    fun blocked(file: File, bmp: Bitmap) = nsfw(file, bmp) >= LIMIT

    @Synchronized
    fun close() { session?.close(); session = null }
}
