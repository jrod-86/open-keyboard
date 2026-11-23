// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Open Waves test 305 (Jarrod: "let's build it all"): the AI music listener. CLAP (LAION's larger_clap_music_and_speech,
 * Apache-2.0, int8 ONNX from Xenova, 78 MB) hears three 10-second parts of each song on the phone and says how close the
 * song sounds to descriptions like "calm relaxing music" or "aggressive heavy metal music". The descriptions were turned
 * into numbers once, ahead of time (assets/music/clap_text.json), so only the small sound half of the model is needed.
 */
object Clap {
    private const val URL_ = "https://huggingface.co/Xenova/larger_clap_music_and_speech/resolve/e9fd5ac1dbf3280936a7fc3ec8a020453ff184db/onnx/audio_model_quantized.onnx"
    private const val SIZE = 78155433L
    private const val SHA = "021dd4fb962b4ed20cc3a6730b09e0ccf9f9c49931047032118a3b64828513a6"
    const val MB = 78

    /** download progress 0..1, or null when not downloading */
    val downloading = mutableStateOf<Float?>(null)
    val error = mutableStateOf("")

    private fun dir(ctx: Context) = File(ctx.filesDir, "models/clap")
    private fun model(ctx: Context) = File(dir(ctx), "audio_model_quantized.onnx")
    /** set when the model can't run on this phone, so Moods carries on without it */
    @Volatile var broken = false
    fun ready(ctx: Context) = !broken && model(ctx).length() == SIZE

    fun remove(ctx: Context) { close(); dir(ctx).deleteRecursively(); (scores ?: ConcurrentHashMap()).clear(); scores = null; File(ctx.filesDir, "music/clap.json").delete() }

    /** Downloads the model once (78 MB). True when it's ready. */
    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (ready(ctx)) return@withContext true
        error.value = ""
        downloading.value = 0f
        try {
            val part = File(dir(ctx).apply { mkdirs() }, "model.part")
            val have = part.length().takeIf { it in 1 until SIZE } ?: 0L.also { part.delete() }
            val c = URL(URL_).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "OpenKeyboard-Music/1.0")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val append = have > 0 && c.responseCode == 206
            if (c.responseCode !in listOf(200, 206)) throw IllegalStateException("download failed (${c.responseCode})")
            var got = if (append) have else 0L
            c.inputStream.use { inp ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        downloading.value = got.toFloat() / SIZE
                    }
                }
            }
            c.disconnect()
            if (part.length() != SIZE || sha(part) != SHA) { part.delete(); throw IllegalStateException("the download was damaged, please try again") }
            part.renameTo(model(ctx))
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error.value = e.message ?: "download failed"
            false
        } finally { downloading.value = null }
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- the descriptions, as numbers ----
    class Text(val moods: Map<String, FloatArray>, val genres: Map<String, FloatArray>)
    @Volatile private var text: Text? = null
    fun text(ctx: Context): Text = text ?: run {
        val j = JSONObject(ctx.assets.open("music/clap_text.json").bufferedReader().readText())
        fun read(o: JSONObject) = o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }
        Text(read(j.getJSONObject("moods")), read(j.getJSONObject("genres")))
    }.also { text = it }

    // ---- what each song scored, saved in files/music/clap.json: mood scores then genre scores ----
    @Volatile private var scores: ConcurrentHashMap<Long, FloatArray>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "music/clap.json")
    fun scores(ctx: Context): Map<Long, FloatArray> {
        scores?.let { return it }
        val m = ConcurrentHashMap<Long, FloatArray>()
        runCatching {
            val j = JSONObject(file(ctx).readText())
            j.keys().forEach { k -> val a = j.getJSONArray(k); m[k.toLong()] = FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        }
        return m.also { scores = it }
    }
    // test 310 (Jarrod: the AI listener was slow): writing the whole file after every song got slow with thousands of
    // songs, so it's written at most every 10 seconds, and once more when the scan stops ([flush])
    @Volatile private var lastSave = 0L
    @Volatile private var unsaved = false
    private fun save(ctx: Context) {
        unsaved = true
        if (System.currentTimeMillis() - lastSave < 10_000) return
        flush(ctx)
    }
    @Synchronized fun flush(ctx: Context) {
        if (!unsaved) return
        unsaved = false; lastSave = System.currentTimeMillis()
        val m = scores ?: return
        val j = JSONObject()
        m.forEach { (id, a) -> j.put(id.toString(), JSONArray(a.map { (it * 10000).toInt() / 10000.0 })) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
    }
    fun moodNames(ctx: Context) = text(ctx).moods.keys.toList()
    fun genreNames(ctx: Context) = text(ctx).genres.keys.toList()

    // ---- running the model ----
    // test 320: guard native OrtSession open/run/close so onDestroy/close never destroys the C++ mutex while a worker is inside run()
    private val sessionLock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var mel: ClapMel? = null

    fun close() = synchronized(sessionLock) { runCatching { session?.close() }; session = null }

    private fun openLocked(ctx: Context): Pair<OrtEnvironment, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val s = session ?: run {
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            e.createSession(model(ctx).path, opts).also { session = it }
        }
        return e to s
    }

    private fun melOf(ctx: Context): ClapMel = synchronized(sessionLock) {
        mel ?: run {
            val b = ctx.assets.open("music/clap_mel.bin").readBytes()
            val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            ClapMel(FloatArray(fb.remaining()).also { fb.get(it) })
        }.also { mel = it }
    }

    /** Listens to the song (three 10 s parts) and keeps its scores. False if the file couldn't be read. */
    suspend fun listen(ctx: Context, song: Song): Boolean = withContext(Dispatchers.Default) {
        val t = text(ctx)
        val dur = song.durationMs
        val starts = if (dur <= 14_000) listOf(0L) else listOf(0.3, 0.55, 0.8).map { ((dur - 10_000) * it).toLong() }
        try { synchronized(sessionLock) { openLocked(ctx) } } catch (e: Exception) {
            // the model couldn't start on this phone: say so once, and carry on without it
            error.value = "The AI listener couldn't start: ${e.message?.take(120)}"
            broken = true
            return@withContext false
        }
        val sum = FloatArray(512)
        var n = 0
        for (s in starts) {
            coroutineContext.ensureActive()
            val wave = clip(ctx, song, s, 10_000) ?: continue
            val f = melOf(ctx).features(wave)
            val e = synchronized(sessionLock) {
                val (ortEnv, sess) = openLocked(ctx)
                OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(f), longArrayOf(1, 1, ClapMel.FRAMES.toLong(), ClapMel.MELS.toLong())).use { inp ->
                    sess.run(mapOf(sess.inputNames.first() to inp)).use { out -> (out[0].value as Array<FloatArray>)[0] }
                }
            }
            val norm = sqrt(e.fold(0f) { a, v -> a + v * v }).coerceAtLeast(1e-6f)
            for (i in sum.indices) sum[i] += e[i] / norm
            n++
        }
        val all = scores(ctx) as ConcurrentHashMap
        if (n == 0) { all[song.id] = FloatArray(0); save(ctx); return@withContext false }
        val norm = sqrt(sum.fold(0f) { a, v -> a + v * v }).coerceAtLeast(1e-6f)
        for (i in sum.indices) sum[i] /= norm
        fun cos(v: FloatArray) = v.indices.fold(0f) { a, i -> a + v[i] * sum[i] }
        all[song.id] = (t.moods.values.map(::cos) + t.genres.values.map(::cos)).toFloatArray()
        save(ctx)
        true
    }

    /** [lenMs] of the song from [startMs], as mono 48 kHz. */
    private fun clip(ctx: Context, song: Song, startMs: Long, lenMs: Long): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, song.uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec = c
            c.configure(fmt, null, null, 0); c.start()
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val startUs = startMs * 1000; val endUs = (startMs + lenMs) * 1000
            var buf = FloatArray((rate * lenMs / 1000).toInt() + 4096); var len = 0
            val info = MediaCodec.BufferInfo()
            var pcm = ShortArray(16384)
            var inDone = false; var done = false; var spins = 0
            while (!done && spins < 20000) {
                spins++
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0 && info.presentationTimeUs + 200_000 >= startUs) {
                        val sb = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        sb.position(info.offset / 2); sb.limit((info.offset + info.size) / 2)
                        val nShorts = sb.remaining()
                        if (pcm.size < nShorts) pcm = ShortArray(nShorts)
                        sb.get(pcm, 0, nShorts)
                        var p = 0
                        var t = info.presentationTimeUs
                        val step = 1_000_000.0 / rate
                        var k = 0
                        while (p + channels <= nShorts) {
                            var v = 0f
                            for (ch in 0 until channels) v += pcm[p++] / 32768f
                            val at = t + (k++ * step).toLong()
                            if (at >= startUs && at < endUs) { if (len == buf.size) buf = buf.copyOf(len * 2); buf[len++] = v / channels }
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs) done = true
                }
            }
            if (len < rate) return null
            val out = buf
            // to 48 kHz (straight-line in between; fine for a 50 Hz to 14 kHz picture)
            if (rate == ClapMel.RATE) return out.copyOf(len)
            val ratio = rate.toDouble() / ClapMel.RATE
            val n = ((len - 1) / ratio).toInt()
            return FloatArray(n) { i -> val p = i * ratio; val a = p.toInt(); val f = (p - a).toFloat(); val x0 = out[a]; val x1 = out[minOf(a + 1, len - 1)]; x0 + (x1 - x0) * f }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }
}
