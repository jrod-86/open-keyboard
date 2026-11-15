// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Your own Zonos voices, made on the phone from a short clip ("Make a voice").
 *
 * A clip is turned into reference codes, the same thing the built-in voices are, by ZONOS3-Mini's reference encoder
 * and SSL teacher. Zyphra ships those as 5.5 GB of full-precision files; they are shrunk to q8_0 with Zonos's own
 * quantize-cli (1.57 GB, cloned voices still match the f32 ones and speech stays clear) and kept, in pieces under
 * GitHub's file limit, on this repo's voice-clone-models branch at a fixed commit, checked by SHA-256 once joined.
 * The encoding runs in libzonos3_server.so's "encode-reference" mode, one clip at a time, with the speech engine
 * stopped so both never sit in memory together.
 */
object VoiceClones {
    private const val TAG = "VoiceClones"
    const val RATE = 44100
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 20 // one 20 s encoder lane; longer takes twice as long

    private const val REPO = "https://raw.githubusercontent.com/jrod-86/open-keyboard/80ba83aa1285ec24ca1bf92c1e5c2ffa1322faba/"
    private const val PIECE = 94371840L

    private class ModelFile(val name: String, val size: Long, val sha: String) {
        val pieces get() = ((size + PIECE - 1) / PIECE).toInt()
    }

    private val FILES = listOf(
        ModelFile("zac-encoder-q8.gguf", 1157362016, "00e9f85964d52c39b30e32e2b0a59f1e437cb0a269cedfb931f0509a755e54a1"),
        ModelFile("ssl-teacher-q8.gguf", 415189376, "67496bda496f9866ff2c9460667887eb07a9afc34146cd170c027d58de00d6e7"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }

    fun dir(ctx: Context) = File(ctx.filesDir, "tts-models/zonos3-clone")
    fun installed(ctx: Context) = FILES.all { File(dir(ctx), it.name).length() == it.size }
    fun missingBytes(ctx: Context) = FILES.filter { File(dir(ctx), it.name).length() != it.size }.sumOf { it.size }
    fun deleteModels(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Downloads the pieces in order into one .part file (resuming where it stopped), then checks the whole file. */
    fun download(ctx: Context, cancel: () -> Boolean, onProgress: (Float) -> Unit) {
        val d = dir(ctx).apply { mkdirs() }
        var done = FILES.filter { File(d, it.name).length() == it.size }.sumOf { it.size }
        for (f in FILES) {
            val target = File(d, f.name)
            if (target.length() == f.size) continue
            val part = File(d, f.name + ".part")
            if (part.length() > f.size) part.delete()
            done += part.length()
            for (i in 0 until f.pieces) {
                val start = i * PIECE
                val end = minOf(f.size, start + PIECE)
                if (part.length() >= end) continue
                val have = part.length() - start // > 0 when this piece was half done
                val conn = URL(REPO + f.name + ".%02d".format(i)).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                    if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                    val code = conn.responseCode
                    val skip = if (code == 200) have else 0L // server ignored the range
                    if (code != 200 && code != 206) throw IllegalStateException("HTTP $code for ${f.name} piece $i")
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(part, true).use { out ->
                            var toSkip = skip
                            val buf = ByteArray(1 shl 16)
                            var last = 0L
                            while (true) {
                                if (cancel()) throw InterruptedException()
                                val n = input.read(buf)
                                if (n < 0) break
                                var off = 0
                                if (toSkip > 0) { val s = minOf(toSkip, n.toLong()).toInt(); toSkip -= s; off = s }
                                if (n - off > 0) { out.write(buf, off, n - off); done += n - off }
                                if (done - last > 1 shl 20) { last = done; onProgress(done.toFloat() / TOTAL_BYTES) }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                if (part.length() != end) { part.delete(); throw IllegalStateException("short piece $i of ${f.name}") }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { if (cancel()) throw InterruptedException(); val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != f.sha) { part.delete(); throw IllegalStateException("bad download: ${f.name}") }
            if (!part.renameTo(target)) throw IllegalStateException("could not save ${f.name}")
        }
        onProgress(1f)
    }

    // ---- your voices ----

    private fun voicesDir(ctx: Context) = File(ctx.filesDir, "tts-voices").apply { mkdirs() }

    /** Your voices, oldest first, as Zonos voices whose reference codes live in a file. */
    fun list(ctx: Context): List<ZonosTts.Voice> =
        (voicesDir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                o.getLong("made") to ZonosTts.Voice(f.name.removeSuffix(".json"), o.getString("name"), true, "🎤", "Your voice", file = f)
            }.getOrNull()
        }.sortedBy { it.first }.map { it.second }

    fun save(ctx: Context, name: String, codes: JSONArray, seconds: Float): ZonosTts.Voice {
        val id = "c" + java.lang.Long.toHexString(System.currentTimeMillis())
        val f = File(voicesDir(ctx), "$id.json")
        f.writeText(JSONObject().put("name", name).put("made", System.currentTimeMillis()).put("clip_seconds", seconds.toDouble())
            .put("permission", "the maker ticked that this is their voice or they have permission").put("codes", codes).toString())
        return ZonosTts.Voice(id, name, true, "🎤", "Your voice", file = f)
    }

    fun rename(v: ZonosTts.Voice, name: String) {
        val f = v.file ?: return
        val o = JSONObject(f.readText()).put("name", name)
        f.writeText(o.toString())
    }

    fun delete(ctx: Context, v: ZonosTts.Voice) { v.file?.delete(); ZonosTts.forget(v) }

    // ---- making one ----

    /** Turns [pcm] (44.1 kHz mono) into reference codes, [frames][10]. Takes about a minute on a phone. */
    fun encode(ctx: Context, pcm: FloatArray): JSONArray {
        ZonosTts.stop() // the speech engine and the encoder don't both fit comfortably
        val work = File(ctx.cacheDir, "zonos3-clone").apply { deleteRecursively(); mkdirs() }
        val input = File(work, "clip.npy"); val output = File(work, "codes.npy")
        writeNpyF32(input, pcm)
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libzonos3_server.so")
        val cmd = listOf(exe.path, "encode-reference", File(dir(ctx), "zac-encoder-q8.gguf").path,
            File(dir(ctx), "ssl-teacher-q8.gguf").path, "--audio", input.path, output.path, "--cpu")
        val t0 = System.currentTimeMillis()
        val p = ProcessBuilder(cmd).directory(work).redirectErrorStream(true).apply {
            environment()["HOME"] = work.path; environment()["TMPDIR"] = work.path
            environment()["ZAC_THREADS"] = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).toString()
        }.start()
        val log = StringBuilder()
        val reader = Thread({ runCatching { p.inputStream.bufferedReader().forEachLine { if (!it.startsWith("repack:")) { Log.i(TAG, "encoder: $it"); log.appendLine(it) } } } }, "zonos3-encode-log")
        reader.isDaemon = true; reader.start()
        if (!p.waitFor(15, TimeUnit.MINUTES)) { p.destroy(); throw IllegalStateException("encoder took too long") }
        reader.join(2000)
        if (p.exitValue() != 0 || !output.isFile) throw IllegalStateException("encoder failed: ${log.takeLast(400)}")
        Log.i(TAG, "encoded ${pcm.size / RATE.toFloat()} s in ${System.currentTimeMillis() - t0} ms")
        val codes = readNpyI32(output)
        work.deleteRecursively()
        return codes
    }

    private fun writeNpyF32(f: File, a: FloatArray) {
        var header = "{'descr': '<f4', 'fortran_order': False, 'shape': (${a.size},), }"
        while ((10 + header.length + 1) % 64 != 0) header += " "
        header += "\n"
        f.outputStream().buffered().use { out ->
            out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(), 1, 0))
            out.write(header.length and 0xff); out.write(header.length shr 8)
            out.write(header.toByteArray())
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asFloatBuffer().put(a)
            out.write(b.array())
        }
    }

    /** Reads a C-order int32 [frames, codebooks] .npy into rows. */
    private fun readNpyI32(f: File): JSONArray {
        val bytes = f.readBytes()
        val major = bytes[6].toInt()
        val hlen = if (major == 1) (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
        else ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val start = if (major == 1) 10 + hlen else 12 + hlen
        val header = String(bytes, start - hlen, hlen)
        require("<i4" in header) { "codes are not int32: $header" }
        val shape = Regex("\\((\\d+),\\s*(\\d+)\\)").find(header) ?: throw IllegalStateException("bad codes shape: $header")
        val frames = shape.groupValues[1].toInt(); val cb = shape.groupValues[2].toInt()
        val ints = ByteBuffer.wrap(bytes, start, frames * cb * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val rows = JSONArray()
        for (i in 0 until frames) { val r = JSONArray(); repeat(cb) { r.put(ints.get()) }; rows.put(r) }
        return rows
    }

    // ---- clips: decode, mono, 44.1 kHz, tidy ----

    /** Decodes a picked sound or video file to 44.1 kHz mono (first [MAX_SECONDS] of speech). */
    fun decode(ctx: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("no sound in that file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val out = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val limit = { (MAX_SECONDS + 15).toLong() * rate } // a bit extra, silence gets trimmed
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0 || out.size > limit()) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        if (float) { val fb = b.asFloatBuffer(); while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; out.add(s / channels) } }
                        else { val sb = b.asShortBuffer(); while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; out.add(s / channels) } }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            return tidy(resample(out.toArray(), rate, RATE))
        } finally { ex.release() }
    }

    /** Trims silence at both ends, keeps up to [MAX_SECONDS], and brings the level to a steady peak. */
    fun tidy(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val win = RATE / 50 // 20 ms
        var peak = 0f; for (x in a) peak = maxOf(peak, abs(x))
        val gate = peak * 0.05f
        fun loud(i: Int): Boolean { var s = 0f; for (j in i until minOf(a.size, i + win)) s = maxOf(s, abs(a[j])); return s > gate }
        var start = 0; while (start < a.size && !loud(start)) start += win
        var end = a.size; while (end > start && !loud(maxOf(0, end - win))) end -= win
        start = maxOf(0, start - RATE / 5); end = minOf(a.size, end + RATE / 5)
        end = minOf(end, start + MAX_SECONDS * RATE)
        if (end <= start) return FloatArray(0)
        val g = if (peak > 0f) 0.9f / peak else 1f
        return FloatArray(end - start) { a[start + it] * g }
    }

    /** Windowed-sinc resampling (good enough for a voice reference). */
    fun resample(a: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || a.isEmpty()) return a
        val ratio = to.toDouble() / from
        val n = (a.size * ratio).toInt()
        val cutoff = minOf(1.0, ratio) * 0.95
        val half = 16
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i / ratio
            val c = floor(t).toInt()
            var s = 0.0; var w = 0.0
            for (k in c - half + 1..c + half) {
                if (k < 0 || k >= a.size) continue
                val x = t - k
                val sinc = if (abs(x) < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
                val win = 0.5 + 0.5 * cos(PI * x / half)
                val h = sinc * win
                s += a[k] * h; w += h
            }
            out[i] = if (w != 0.0) (s / w).toFloat() else 0f
        }
        return out
    }

    /** How loud a clip is overall (0..1), to warn about clips that are mostly silence. */
    fun rms(a: FloatArray): Float { var s = 0.0; for (x in a) s += x * x; return if (a.isEmpty()) 0f else sqrt(s / a.size).toFloat() }

    class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(x: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = x }
        fun toArray() = data.copyOf(size)
    }
}
