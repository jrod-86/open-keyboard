// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Tap to pick" for the sticker maker: MobileSAM (Apache-2.0, github.com/ChaoningZhang/MobileSAM) with the
 * Segment Anything mask decoder (Apache-2.0, Meta). ONNX exports by Viet-Anh Nguyen (AnyLabeling), Apache-2.0,
 * downloaded once (about 11 MB) from Hugging Face at a fixed commit and checked against its SHA-256.
 */
class Sam(dir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession = env.createSession(File(dir, ENCODER).path, OrtSession.SessionOptions())
    private val decoder: OrtSession = env.createSession(File(dir, DECODER).path, OrtSession.SessionOptions())

    /** The picture's embedding (1×256×64×64). Slow part: once per photo. */
    fun embed(photo: Bitmap): FloatArray {
        val s = SIZE.toFloat() / max(photo.width, photo.height)
        val w = (photo.width * s).roundToInt().coerceAtLeast(1); val h = (photo.height * s).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val input = FloatBuffer.allocate(w * h * 3)
        for (p in px) { input.put((p shr 16 and 255).toFloat()); input.put((p shr 8 and 255).toFloat()); input.put((p and 255).toFloat()) }
        input.rewind()
        return OnnxTensor.createTensor(env, input, longArrayOf(h.toLong(), w.toLong(), 3)).use { t ->
            encoder.run(mapOf("input_image" to t)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    /** Which pixels of the photo belong to the thing at these points (photo pixel coordinates). */
    fun mask(embedding: FloatArray, photo: Bitmap, points: List<FloatArray>): BooleanArray {
        val s = SIZE.toFloat() / max(photo.width, photo.height)
        val n = points.size + 1
        val coords = FloatBuffer.allocate(n * 2)
        points.forEach { coords.put(it[0] * s); coords.put(it[1] * s) }
        coords.put(0f); coords.put(0f) // padding point, as the decoder expects without a box
        coords.rewind()
        val labels = FloatBuffer.wrap(FloatArray(n) { if (it < points.size) 1f else -1f })
        val inputs = mapOf(
            "image_embeddings" to OnnxTensor.createTensor(env, FloatBuffer.wrap(embedding), longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, coords, longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, labels, longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(photo.height.toFloat(), photo.width.toFloat())), longArrayOf(2)),
        )
        try {
            return decoder.run(inputs).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                val total = photo.width * photo.height
                // with several masks, use the first one (the decoder here gives one)
                BooleanArray(total) { fb.get(it) > 0f }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        encoder.close(); decoder.close()
    }

    companion object {
        private const val SIZE = 1024
        private const val ENCODER = "mobile_sam.encoder.quant.onnx"
        private const val DECODER = "sam_vit_h_4b8939.decoder.quant.onnx"
        private const val ENCODER_SIZE = 11018056L
        private const val DECODER_SIZE = 8742607L
        private const val URL_ZIP = "https://huggingface.co/vietanhdev/segment-anything-onnx-models/resolve/9effc01a9e135621d710d49159f1ffb0b6f724dc/mobile_sam_20230629_quant.zip"
        private const val ZIP_SHA = "8a8ee531538541a55fa6fa7efebae121ef33e5c323b3998b48ea9383b37d90aa"

        fun downloaded(dir: File) = File(dir, ENCODER).length() == ENCODER_SIZE && File(dir, DECODER).length() == DECODER_SIZE

        fun download(dir: File) {
            dir.mkdirs()
            val tmp = File(dir, "sam.zip.part")
            val digest = MessageDigest.getInstance("SHA-256")
            (URL(URL_ZIP).openConnection() as HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 30000
                inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = input.read(buf); if (r < 0) break; digest.update(buf, 0, r); out.write(buf, 0, r) }
                } }
                disconnect()
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != ZIP_SHA) { tmp.delete(); throw IllegalStateException("bad download") }
            ZipInputStream(tmp.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = File(e.name).name
                    if (name != ENCODER && name != DECODER) continue
                    File(dir, name).outputStream().use { zip.copyTo(it) }
                }
            }
            tmp.delete()
            if (!downloaded(dir)) throw IllegalStateException("bad download")
        }
    }
}
