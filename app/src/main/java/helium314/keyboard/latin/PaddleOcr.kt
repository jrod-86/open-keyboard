// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Text recognition with PaddleOCR's models (Apache-2.0) on ONNX Runtime (MIT), which voice typing
 * already ships. Much better than Tesseract on photos of screens and signs. Two small models:
 * one finds the lines of text, the other reads each line. They are downloaded once (13 MB).
 */
class PaddleOcr private constructor(dir: File) : Closeable {
    /** A line of text, and each of its words with where it is in the picture. */
    class Line(val text: String, val box: Rect, val confidence: Float, val words: List<Pair<String, Rect>>)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    private val det = env.createSession(File(dir, DET.name).path, options)
    private val rec = env.createSession(File(dir, REC.name).path, options)
    // index 0 is the CTC blank, then the dictionary, then a space
    private val chars = File(dir, DICT.name).readLines().map { it.trimEnd('\n', '\r') } + " "

    override fun close() { det.close(); rec.close(); options.close() }

    fun read(bmp: Bitmap): List<Line> {
        return findLines(bmp, bmp.width, bmp.height).mapNotNull { readLine(bmp, it) }
    }

    /** Runs the detector and returns boxes around lines of text, in picture coordinates. */
    private fun findLines(bmp: Bitmap, W: Int, H: Int): List<Rect> {
        val r = min(1f, 960f / max(W, H))
        val w = max(32, (W * r / 32).roundToInt() * 32)
        val h = max(32, (H * r / 32).roundToInt() * 32)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        // planes in B, G, R order, normalised like PaddleOCR does
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * w * h)
        for (c in 0 until 3) {
            val shift = c * 8 // B, G, R
            for (i in 0 until w * h) input.put(((px[i] shr shift and 255) / 255f - mean[c]) / std[c])
        }
        input.rewind()
        val prob = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        // connected regions of the text map become line boxes
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val boxes = mutableListOf<Rect>()
        var next = 0
        for (start in 0 until w * h) {
            if (prob[start] <= 0.3f || label[start] != 0) continue
            next++
            var sp = 0; stack[sp++] = start; label[start] = next
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var area = 0; var sum = 0f
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                area++; sum += prob[i]
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (label[j] == 0 && prob[j] > 0.3f) { label[j] = next; stack[sp++] = j }
                }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (min(bw, bh) < 3 || sum / area < 0.6f) continue
            // the map hugs the middle of the letters; grow the box back out (PaddleOCR's "unclip")
            val d = area * 1.5f / (2 * (bw + bh))
            boxes += Rect(
                max(0, ((x0 - d) / w * W).toInt()), max(0, ((y0 - d) / h * H).toInt()),
                min(W, ((x1 + 1 + d) / w * W).toInt()), min(H, ((y1 + 1 + d) / h * H).toInt()))
        }
        return boxes
    }

    /** Reads one line, and works out each word's box from where its letters were read. */
    private fun readLine(bmp: Bitmap, box: Rect): Line? {
        val cw = box.width(); val ch = box.height()
        if (cw < 4 || ch < 4) return null
        val iw = min(3200, max(16, (48f * cw / ch).roundToInt()))
        val ih = 48
        val crop = Bitmap.createBitmap(bmp, box.left, box.top, cw, ch)
        val scaled = Bitmap.createScaledBitmap(crop, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)
        if (scaled !== crop) scaled.recycle()
        if (crop !== bmp) crop.recycle()
        val input = FloatBuffer.allocate(3 * iw * ih)
        // planes in B, G, R order
        for (c in 0 until 3) {
            val shift = c * 8
            for (i in 0 until iw * ih) input.put(((px[i] shr shift and 255) / 255f - 0.5f) / 0.5f)
        }
        input.rewind()
        val (steps, classes, probs) = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, ih.toLong(), iw.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { out ->
                val tensor = out[0] as OnnxTensor
                val shape = tensor.info.shape
                val fb = tensor.floatBuffer
                Triple(shape[1].toInt(), shape[2].toInt(), FloatArray(fb.remaining()).also { fb.get(it) })
            }
        }
        // greedy CTC decoding, keeping the time step of each letter
        val text = StringBuilder()
        val at = mutableListOf<Int>()
        var confSum = 0f
        var prev = 0
        for (s in 0 until steps) {
            var best = 0; var bestP = -1f
            for (k in 0 until classes) { val p = probs[s * classes + k]; if (p > bestP) { bestP = p; best = k } }
            if (best != 0 && best != prev && best - 1 < chars.size) {
                text.append(chars[best - 1]); at += s; confSum += bestP
            }
            prev = best
        }
        if (text.isBlank()) return null
        val confidence = confSum / text.length
        fun xAt(step: Float) = box.left + (step / steps * cw).toInt()
        val words = mutableListOf<Pair<String, Rect>>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            var j = i
            while (j < text.length && text[j] != ' ') j++
            val left = if (i == 0) box.left else xAt(at[i] - 0.5f)
            val right = if (j == text.length) box.right else xAt(at[j - 1] + 1.5f)
            words += text.substring(i, j) to Rect(left, box.top, max(left + 1, right), box.bottom)
            i = j
        }
        return Line(text.toString().trim(), box, confidence, words)
    }

    private class Model(val name: String, val url: String, val sha256: String, val size: Long)

    companion object {
        private val DET = Model("det.onnx",
            "https://huggingface.co/SWHL/RapidOCR/resolve/1cfba2e90fc938db55889873735088de210cc173/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
            "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9", 4745517)
        private val REC = Model("rec.onnx",
            "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/english/rec.onnx",
            "4e16deb22c4da6468bdca539b2cd3c8687825538b67109177c47d359ab994cd7", 7830888)
        private val DICT = Model("dict.txt",
            "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/english/dict.txt",
            "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6", 1416)

        /** Opens the models, downloading them first if needed. Null if that isn't possible now. */
        fun open(dir: File, onDownload: () -> Unit): PaddleOcr? = try {
            dir.mkdirs()
            val missing = listOf(DET, REC, DICT).filter { File(dir, it.name).length() != it.size }
            if (missing.isNotEmpty()) onDownload()
            missing.forEach { download(it, dir) }
            PaddleOcr(dir)
        } catch (e: Throwable) {
            null
        }

        private fun download(m: Model, dir: File) {
            val tmp = File(dir, m.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            (URL(m.url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 30000
                inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
                } }
                disconnect()
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != m.sha256 || tmp.length() != m.size) { tmp.delete(); throw IllegalStateException("bad download") }
            tmp.renameTo(File(dir, m.name))
        }
    }
}
