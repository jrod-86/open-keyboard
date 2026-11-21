// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package helium314.keyboard.music

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test 313 (Jarrod: "stem removal with all stem sliders"): splits a song into vocals, drums, bass and other on the phone,
 * with Spleeter 4-stems (Deezer, MIT; ONNX export Best-Practice/spleeter-4stems-onnx, Apache-2.0), run by ONNX Runtime.
 * The four stems are saved as one 8-channel WAV that plays in place of the song, so [StemProcessor] can mix them live
 * with your sliders, always in sync. Export writes your mix, or each stem, to Downloads.
 */
object Stems {
    const val SR = 44100
    private const val N_FFT = 4096
    private const val HOP = 1024
    private const val T = 512
    private const val F = 1024
    private const val BINS = 2049
    private const val PAD = N_FFT - HOP
    val NAMES = listOf("vocals", "drums", "bass", "other")
    val LABELS = listOf("🎤 Vocals", "🥁 Drums", "🎸 Bass", "🎹 Other")

    private const val REPO = "https://huggingface.co/Best-Practice/spleeter-4stems-onnx/resolve/486643021f18a4d057d282fff1d11b89a30e9887/"
    private class ModelFile(val name: String, val size: Long, val sha: String)
    private val FILES = listOf(
        ModelFile("vocals.fp16.onnx", 19714141, "db47148ab1c52709ce694893f532c91abfe3edc4d46238939570e036a22878ca"),
        ModelFile("drums.fp16.onnx", 19714140, "7ae4002e5633634674f74dc3356d5875b0da894d59ce0f60e844bb8f9cb8aa92"),
        ModelFile("bass.fp16.onnx", 19714139, "ba4c4949a27222492cca49859901a873b4b71461dc48c7c5a51f93d31eb11f55"),
        ModelFile("other.fp16.onnx", 19714140, "3cc59116cb7195946ab9596d8ca25984d09c0f8a70db8cf85d063132f97bc61d"),
    )
    val TOTAL_MB = FILES.sumOf { it.size } / 1_000_000

    fun dir(ctx: Context) = File(ctx.filesDir, "stem-models/spleeter4")
    fun installed(ctx: Context) = FILES.all { File(dir(ctx), it.name).length() == it.size }
    fun wav(ctx: Context, id: Long) = File(ctx.filesDir, "stems/$id.wav")
    fun has(ctx: Context, id: Long) = wav(ctx, id).length() > 44

    // ---- what the screen shows ----
    /** 0..1 while downloading or splitting, null when idle */
    val progress = mutableStateOf<Float?>(null)
    val status = mutableStateOf("")
    /** the song being split */
    val working = mutableStateOf<Long?>(null)
    /** the four slider values, 0..1.5 (1 = as recorded) */
    val gains = mutableStateListOf(1f, 1f, 1f, 1f)
    /** what the audio uses (read on the audio thread) */
    @Volatile var live = floatArrayOf(1f, 1f, 1f, 1f)
    /** goes up when stem mode turns on or off, so the screen looks again */
    val changed = mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun setGain(i: Int, v: Float) {
        val g = (Math.round(v.coerceIn(0f, 1.5f) * 20) / 20f)
        gains[i] = g
        live = FloatArray(4) { gains[it] }
    }
    fun setAll(vararg v: Float) { for (i in 0 until 4) gains[i] = v[i]; live = FloatArray(4) { gains[it] } }

    fun cancel() { job?.cancel() }

    // ---- download ----
    fun download(ctx: Context, then: () -> Unit) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            progress.value = 0f; status.value = "Downloading the stem splitter…"
            try {
                val d = dir(app).apply { mkdirs() }
                val total = FILES.sumOf { it.size }
                var done = FILES.filter { File(d, it.name).length() == it.size }.sumOf { it.size }
                for (f in FILES) {
                    val target = File(d, f.name)
                    if (target.length() == f.size) continue
                    val part = File(d, f.name + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var url = URL(REPO + f.name)
                    var conn: HttpURLConnection
                    var redirects = 0
                    while (true) { // Hugging Face sends you on to its file servers
                        conn = url.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                        val code = conn.responseCode
                        if (code in 300..399 && redirects++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                        if (code != 200) throw IllegalStateException("HTTP $code for ${f.name}")
                        break
                    }
                    try {
                        conn.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                                    progress.value = done.toFloat() / total
                                }
                            }
                        }
                    } finally { conn.disconnect() }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hex != f.sha || part.length() != f.size) { part.delete(); throw IllegalStateException("the download was damaged, try again") }
                    if (!part.renameTo(target)) throw IllegalStateException("couldn't save ${f.name}")
                }
                status.value = ""
                withContext(Dispatchers.Main) { then() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                status.value = "Download stopped"
            } catch (e: Exception) {
                status.value = "Download failed: ${e.message}"
            } finally { progress.value = null }
        }
    }

    // ---- splitting ----
    fun split(ctx: Context, song: Song, then: () -> Unit) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            working.value = song.id; progress.value = 0f; status.value = "Reading the song…"
            val wake = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenWaves:stems").apply { setReferenceCounted(false); acquire(30 * 60_000L) }
            val tmp = File(app.filesDir, "stems/${song.id}.wav.part")
            try {
                val (l, r) = decode(app, song.uri)
                if (l.size < SR) throw IllegalStateException("the song is too short")
                if (l.size > SR * 60 * 15) throw IllegalStateException("songs up to 15 minutes only")
                tmp.parentFile?.mkdirs()
                separate(app, l, r, tmp) { p -> progress.value = p; status.value = "Splitting into stems… ${(p * 100).toInt()}%" }
                if (!tmp.renameTo(wav(app, song.id))) throw IllegalStateException("couldn't save the stems")
                tidy(app, song.id)
                status.value = ""
                withContext(Dispatchers.Main) { then() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                status.value = "Stopped"
            } catch (e: OutOfMemoryError) {
                status.value = "Not enough memory on the phone for this song"
            } catch (e: Exception) {
                android.util.Log.w("OpenWaves", "stem split failed", e)
                status.value = "Couldn't split this song: ${e.message}"
            } finally {
                tmp.delete(); progress.value = null; working.value = null
                runCatching { wake.release() }
            }
        }
    }

    /** Keeps the 5 newest split songs (stems take about 42 MB for each minute of song). */
    private fun tidy(ctx: Context, keep: Long) {
        val files = File(ctx.filesDir, "stems").listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(5).forEach { if (it.name != "$keep.wav") it.delete() }
    }
    fun deleteAll(ctx: Context) { File(ctx.filesDir, "stems").deleteRecursively() }
    fun usedMb(ctx: Context) = (File(ctx.filesDir, "stems").listFiles()?.sumOf { it.length() } ?: 0L) / 1_000_000

    /** The whole song as 44.1 kHz stereo floats. */
    private suspend fun decode(ctx: Context, uri: Uri): Pair<FloatArray, FloatArray> {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("no sound in this file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec = c
            c.configure(fmt, null, null, 0); c.start()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var cap = maxOf(SR * 10, ((durUs / 1_000_000.0 + 2) * rate).toInt())
            var left = FloatArray(cap); var right = FloatArray(cap); var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val frames = info.size / ((if (isFloat) 4 else 2) * channels)
                    if (n + frames > cap) {
                        cap = maxOf(cap * 3 / 2, n + frames + SR)
                        left = left.copyOf(cap); right = right.copyOf(cap)
                    }
                    if (isFloat) {
                        val fb = buf.asFloatBuffer()
                        for (f in 0 until frames) { left[n] = fb.get(f * channels); right[n] = fb.get(f * channels + if (channels > 1) 1 else 0); n++ }
                    } else {
                        val sb = buf.asShortBuffer()
                        val tmp = ShortArray(frames * channels); sb.get(tmp)
                        for (f in 0 until frames) { left[n] = tmp[f * channels] / 32768f; right[n] = tmp[f * channels + if (channels > 1) 1 else 0] / 32768f; n++ }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (rate == SR) return left.copyOf(n) to right.copyOf(n)
            // other sample rates (48 kHz…): the model is made for 44.1 kHz
            val outN = (n.toLong() * SR / rate).toInt()
            val l2 = FloatArray(outN); val r2 = FloatArray(outN)
            val step = rate.toDouble() / SR
            for (i in 0 until outN) {
                val pos = i * step; val k = pos.toInt(); val fr = (pos - k).toFloat()
                val k1 = minOf(k + 1, n - 1)
                l2[i] = left[k] + (left[k1] - left[k]) * fr; r2[i] = right[k] + (right[k1] - right[k]) * fr
            }
            return l2 to r2
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }

    /** STFT (4096, hop 1024, periodic Hann) → the four Spleeter models → soft masks → inverse STFT, a block of 512 frames at a time. */
    private suspend fun separate(ctx: Context, l: FloatArray, r: FloatArray, out: File, progress: (Float) -> Unit) {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) }
        val sessions = NAMES.map { env.createSession(File(dir(ctx), "$it.fp16.onnx").path, opts) }
        try {
            StemDsp.separateWith(l, r, out, progress) { mag ->
                sessions.map { s ->
                    ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(mag), longArrayOf(2, 1, T.toLong(), F.toLong())).use { x ->
                        s.run(mapOf("x" to x)).use { res ->
                            val y = (res[0] as ai.onnxruntime.OnnxTensor).floatBuffer
                            FloatArray(y.remaining()).also { y.get(it) }
                        }
                    }
                }
            }
        } finally { sessions.forEach { runCatching { it.close() } } }
    }

    // ---- playing the stems ----
    private fun player() = PlayerWidget.player

    /** True when the song playing now is the split version. */
    fun playingStems(id: Long): Boolean {
        val p = player() ?: return false
        val item = p.currentMediaItem ?: return false
        return item.mediaId == id.toString() && item.localConfiguration?.uri?.path?.contains("/stems/") == true
    }

    /** Swaps the song in the queue for its split version (or back), keeping the spot in the song. */
    fun use(ctx: Context, song: Song, on: Boolean) {
        val p = player() ?: return
        if (on && !has(ctx, song.id)) return
        val idx = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == song.id.toString() } ?: return
        val base = song.toMediaItem()
        val item = if (on) base.buildUpon().setUri(Uri.fromFile(wav(ctx, song.id))).setMimeType(MimeTypes.AUDIO_WAV).build() else base
        val current = idx == p.currentMediaItemIndex
        val pos = p.currentPosition
        val playing = p.playWhenReady
        p.replaceMediaItem(idx, item)
        if (current) { p.seekTo(idx, pos); p.playWhenReady = playing }
        changed.value++
    }

    /** "Back to normal": every stem at 100% and the original file again. */
    fun reset(ctx: Context, song: Song) {
        setAll(1f, 1f, 1f, 1f)
        if (playingStems(song.id)) use(ctx, song, false)
    }

    /** Saves your mix (or each stem on its own) as WAV files in Downloads/Open Waves stems. Returns what was saved. */
    suspend fun export(ctx: Context, song: Song, each: Boolean): String = withContext(Dispatchers.IO) {
        val src = wav(ctx, song.id)
        if (!src.exists()) throw IllegalStateException("split the song first")
        val g = live.copyOf()
        val base = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val names = if (each) NAMES.map { "$base ($it).wav" } else listOf("$base (my mix).wav")
        val outs = names.map { openOut(ctx, it) }
        try {
            val frames = (src.length() - 44) / 16
            outs.forEach { StemDsp.writeWavHeader(it.first, 2, frames * 4) }
            src.inputStream().buffered(1 shl 16).use { input ->
                input.skip(44)
                val inBuf = ByteArray(16 * 4096)
                val outBufs = Array(outs.size) { ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN) }
                while (true) {
                    var got = 0
                    while (got < inBuf.size) { val k = input.read(inBuf, got, inBuf.size - got); if (k < 0) break; got += k }
                    if (got < 16) break
                    val bb = ByteBuffer.wrap(inBuf, 0, got).order(ByteOrder.LITTLE_ENDIAN)
                    outBufs.forEach { it.clear() }
                    for (f in 0 until got / 16) {
                        val x = ShortArray(8) { bb.getShort(f * 16 + it * 2) }
                        if (each) for (s in 0 until 4) { outBufs[s].putShort(x[s * 2]); outBufs[s].putShort(x[s * 2 + 1]) }
                        else {
                            var a = 0f; var b = 0f
                            for (s in 0 until 4) { a += x[s * 2] * g[s]; b += x[s * 2 + 1] * g[s] }
                            outBufs[0].putShort(a.toInt().coerceIn(-32768, 32767).toShort()); outBufs[0].putShort(b.toInt().coerceIn(-32768, 32767).toShort())
                        }
                    }
                    outs.forEachIndexed { i, o -> o.first.write(outBufs[i].array(), 0, outBufs[i].position()) }
                    if (got < inBuf.size) break
                }
            }
        } finally { outs.forEach { runCatching { it.first.close() }; it.second() } }
        if (each) "4 stems saved in Downloads › Open Waves stems" else "“${names[0]}” saved in Downloads › Open Waves stems"
    }

    /** A new file in Downloads/Open Waves stems; the second part finishes it (makes it show up). */
    private fun openOut(ctx: Context, name: String): Pair<OutputStream, () -> Unit> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Open Waves stems"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("couldn't make $name")
            val os = BufferedOutputStream(ctx.contentResolver.openOutputStream(uri)!!, 1 shl 16)
            return os to { runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } }
        }
        val d = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Open Waves stems").apply { mkdirs() }
        return BufferedOutputStream(File(d, name).outputStream(), 1 shl 16) to {}
    }
}


/** The stem maths without Android, so it can be tested off the phone. */
internal object StemDsp {
    const val SR = 44100
    const val N_FFT = 4096
    const val HOP = 1024
    const val T = 512
    const val F = 1024
    const val BINS = 2049
    const val PAD = N_FFT - HOP

    /** The maths, with the model passed in (so it can be tested off the phone). [model] gets [2,1,512,1024] magnitudes, gives 4 of the same. */
    internal suspend fun separateWith(l: FloatArray, r: FloatArray, out: File, progress: (Float) -> Unit, model: (FloatArray) -> List<FloatArray>) {
        val len = l.size
        val ch = arrayOf(l, r)
        val n = (PAD + len + HOP - 1) / HOP
        val w = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
        val fft = Fft(N_FFT)
        val accLen = T * HOP + N_FFT
        val acc = Array(4) { Array(2) { FloatArray(accLen) } }
        val re = Array(2) { FloatArray(T * BINS) }; val im = Array(2) { FloatArray(T * BINS) }
        val mag = FloatArray(2 * T * F)
        val bufRe = FloatArray(N_FFT); val bufIm = FloatArray(N_FFT)
        val endT = (n - 1) * HOP + N_FFT
        val stream = BufferedOutputStream(out.outputStream(), 1 shl 16)
        stream.use { os ->
            writeWavHeader(os, 8, 0)
            var written = 0L
            val row = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            var b0 = 0
            while (b0 < n) {
                coroutineContext.ensureActive()
                val b1 = minOf(n, b0 + T); val nf = b1 - b0
                // 1. spectrum of each frame
                java.util.Arrays.fill(mag, 0f)
                for (c in 0..1) for (f in 0 until nf) {
                    val start = (b0 + f) * HOP - PAD // in song samples
                    for (k in 0 until N_FFT) { val t = start + k; bufRe[k] = if (t in 0 until len) ch[c][t] * w[k] else 0f; bufIm[k] = 0f }
                    fft.run(bufRe, bufIm, false)
                    val o = f * BINS
                    System.arraycopy(bufRe, 0, re[c], o, BINS); System.arraycopy(bufIm, 0, im[c], o, BINS)
                    val mo = (c * T + f) * F
                    for (k in 0 until F) mag[mo + k] = kotlin.math.sqrt(bufRe[k] * bufRe[k] + bufIm[k] * bufIm[k])
                }
                // 2. what the four models hear
                val est = model(mag)
                // 3. soft masks, put back on the song's own spectrum, and back to sound
                val m = Array(4) { FloatArray(BINS) }
                for (c in 0..1) for (f in 0 until nf) {
                    val mo = (c * T + f) * F
                    val sums = FloatArray(4)
                    for (k in 0 until F) {
                        var tot = 1e-10f
                        for (s in 0 until 4) { val e = est[s][mo + k]; tot += e * e }
                        for (s in 0 until 4) { val e = est[s][mo + k]; val v = (e * e + 1e-10f / 4) / tot; m[s][k] = v; sums[s] += v }
                    }
                    for (s in 0 until 4) { val mean = sums[s] / F; for (k in F until BINS) m[s][k] = mean }
                    val o = f * BINS
                    for (s in 0 until 4) {
                        val ms = m[s]
                        for (k in 0 until BINS) { bufRe[k] = re[c][o + k] * ms[k]; bufIm[k] = im[c][o + k] * ms[k] }
                        for (k in 1 until N_FFT / 2) { bufRe[N_FFT - k] = bufRe[k]; bufIm[N_FFT - k] = -bufIm[k] }
                        fft.run(bufRe, bufIm, true)
                        val a = acc[s][c]; val at = f * HOP
                        for (k in 0 until N_FFT) a[at + k] += bufRe[k] * w[k]
                    }
                }
                // 4. samples no later frame touches are done: write them
                val doneTo = if (b1 == n) endT else b1 * HOP
                for (t in b0 * HOP until doneTo) {
                    val tOut = t - PAD
                    if (tOut < 0 || tOut >= len) continue
                    var ws = 0f
                    var i = maxOf(0, Math.floorDiv(t - N_FFT, HOP) + 1)
                    while (i <= minOf(n - 1, t / HOP)) { val wv = w[t - i * HOP]; ws += wv * wv; i++ }
                    val k = t - b0 * HOP
                    row.clear()
                    for (s in 0 until 4) for (c in 0..1) {
                        val v = if (ws > 1e-8f) acc[s][c][k] / ws else 0f
                        row.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    }
                    os.write(row.array(), 0, 16); written += 16
                }
                // 5. carry the overlap into the next block
                if (b1 < n) for (s in 0 until 4) for (c in 0..1) {
                    val a = acc[s][c]
                    System.arraycopy(a, nf * HOP, a, 0, PAD)
                    java.util.Arrays.fill(a, PAD, accLen, 0f)
                }
                progress(b1.toFloat() / n)
                b0 = b1
            }
            os.flush()
            fixWavSizes(out, written)
        }
    }

    fun writeWavHeader(os: OutputStream, channels: Int, dataBytes: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(SR)
        b.putInt(SR * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        os.write(b.array())
    }

    fun fixWavSizes(f: File, dataBytes: Long) {
        RandomAccessFile(f, "rw").use { raf ->
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4); raf.write(b.putInt((36 + dataBytes).toInt()).array())
            b.clear(); raf.seek(40); raf.write(b.putInt(dataBytes.toInt()).array())
        }
    }

}

/** Plain radix-2 FFT (in place). Inverse divides by n. */
internal class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun run(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var size = 2
        while (size <= n) {
            val half = size / 2; val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosT[k]; val s = if (inverse) sinT[k] else -sinT[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s; val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) { val f = 1f / n; for (i in 0 until n) { re[i] *= f; im[i] *= f } }
    }
}

/**
 * Mixes the 8-channel stem file (vocals, drums, bass, other, each stereo) down to stereo with the sliders, live.
 * Does nothing for normal songs (only switches on for 8-channel sound).
 */
class StemProcessor : BaseAudioProcessor() {
    private val g = FloatArray(4) { 1f }
    private var glide = 1f / (44100 * 0.04f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 8) return AudioProcessor.AudioFormat.NOT_SET
        glide = 1f / (inputAudioFormat.sampleRate * 0.04f)
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val frames = n / 16
        val out = replaceOutputBuffer(frames * 4)
        val start = inputBuffer.position()
        val target = Stems.live
        for (f in 0 until frames) {
            var a = 0f; var b = 0f
            for (s in 0 until 4) {
                val t = target[s]
                if (g[s] != t) g[s] = if (g[s] < t) minOf(t, g[s] + glide) else maxOf(t, g[s] - glide)
                a += inputBuffer.getShort(start + f * 16 + s * 4) * g[s]
                b += inputBuffer.getShort(start + f * 16 + s * 4 + 2) * g[s]
            }
            out.putShort(a.toInt().coerceIn(-32768, 32767).toShort()); out.putShort(b.toInt().coerceIn(-32768, 32767).toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
