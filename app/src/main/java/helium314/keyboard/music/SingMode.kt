// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test 384 (Jarrod): HQ Sing mode powered by UVR-MDX-NET-Inst_HQ_3.onnx (64 MB, MIT, Ultimate Vocal Remover),
 * the same full-bandwidth 44.1 kHz karaoke instrumental model used in KaraokeSesh/Phrasync.
 * Runs locally via ONNX Runtime; keeps the 4-slider Stem Splitter (Spleeter) completely separate.
 */
object SingMode {
    private const val MODEL_NAME = "UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_URL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/$MODEL_NAME"
    const val MODEL_BYTES = 66_759_214L
    const val MODEL_MB = 64
    private const val MAX_CACHED = 5

    val on = mutableStateOf(false)
    val progress = mutableStateOf<Float?>(null)
    val status = mutableStateOf("")
    val working = mutableStateOf<Long?>(null)
    val changed = mutableIntStateOf(0)
    val askingDownload = mutableStateOf(false)
    var onReadyForLyrics: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val waiting = java.util.ArrayDeque<Long>()
    private val main = Handler(Looper.getMainLooper())
    private var swapping = false

    fun modelDir(ctx: Context) = File(ctx.filesDir, "sing-model")
    fun modelFile(ctx: Context) = File(modelDir(ctx), MODEL_NAME)
    fun installed(ctx: Context) = modelFile(ctx).let { it.isFile && it.length() == MODEL_BYTES }

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "sing")
    fun wav(ctx: Context, id: Long) = File(cacheDir(ctx), "$id.wav")
    fun has(ctx: Context, id: Long) = wav(ctx, id).let { it.isFile && it.length() > 44 }

    private fun player() = PlayerWidget.player

    fun cancel() {
        job?.cancel()
        progress.value = null
        working.value = null
        status.value = ""
    }

    /** Turns HQ Sing mode on or off. Returns false if the model isn't downloaded yet (and opens the download prompt). */
    fun setOn(ctx: Context, enable: Boolean): Boolean {
        val app = ctx.applicationContext
        if (enable) {
            if (!installed(app)) {
                askingDownload.value = true
                return false
            }
            if (Stems.modeOn.value) Stems.setMode(app, false)
            on.value = true
            songChanged(app)
            return true
        }
        on.value = false
        synchronized(waiting) { waiting.clear() }
        job?.cancel()
        progress.value = null
        working.value = null
        status.value = ""
        main.post { swapAll(app) }
        return true
    }

    fun songChanged(ctx: Context) {
        if (!on.value || !installed(ctx)) return
        val p = player() ?: return
        val lib = Library.cached ?: return
        val ids = listOfNotNull(p.currentMediaItemIndex, p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET })
            .mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (working.value != null && working.value !in ids) job?.cancel()
        ids.reversed().forEach { id ->
            if (!has(ctx, id) && working.value != id) synchronized(waiting) {
                waiting.remove(id)
                waiting.addFirst(id)
                while (waiting.size > 3) waiting.removeLast()
            }
        }
        swapAll(ctx)
        work(ctx, lib)
    }

    private fun work(ctx: Context, lib: Library) {
        if (job?.isActive == true) return
        val next = synchronized(waiting) { waiting.pollFirst() } ?: return
        val song = lib.byId[next] ?: return work(ctx, lib)
        if (has(ctx, next)) return work(ctx, lib)
        separateSong(ctx, song) {
            main.post { swapAll(ctx) }
            if (on.value) work(ctx, lib)
        }
    }

    fun download(ctx: Context, then: () -> Unit = {}) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            progress.value = 0f
            status.value = "Downloading HQ vocal remover ($MODEL_MB MB)…"
            try {
                val dir = modelDir(app).apply { mkdirs() }
                val target = modelFile(app)
                if (target.length() != MODEL_BYTES) {
                    val part = File(dir, "$MODEL_NAME.part")
                    var url = URL(MODEL_URL)
                    var conn: HttpURLConnection
                    var redirects = 0
                    while (true) {
                        conn = url.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 60_000
                        val code = conn.responseCode
                        if (code in 300..399 && redirects++ < 8) {
                            url = URL(url, conn.getHeaderField("Location"))
                            conn.disconnect()
                            continue
                        }
                        if (code != 200) throw IllegalStateException("HTTP $code")
                        break
                    }
                    try {
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
                        var done = 0L
                        conn.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                                    status.value = "Downloading HQ vocal remover (${done / (1024 * 1024)} / $MODEL_MB MB)…"
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (part.length() != MODEL_BYTES) {
                        part.delete()
                        throw IllegalStateException("Download incomplete, please try again")
                    }
                    if (!part.renameTo(target)) throw IllegalStateException("Couldn't save model file")
                }
                progress.value = null
                status.value = ""
                main.post { then() }
            } catch (_: CancellationException) {
                progress.value = null
                status.value = ""
            } catch (e: Exception) {
                progress.value = null
                status.value = "Download failed: ${e.message ?: "check your connection"}"
            }
        }
    }

    private fun separateSong(ctx: Context, song: Song, then: () -> Unit) {
        val app = ctx.applicationContext
        job = scope.launch {
            working.value = song.id
            progress.value = 0f
            status.value = "Reading “${song.title}”…"
            try {
                val (l, r) = Stems.decode(app, song.uri)
                coroutineContext.ensureActive()
                val dir = cacheDir(app).apply { mkdirs() }
                val tmp = File(dir, "${song.id}.part")
                val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 6)
                var provider = "CPU"
                val session = runCatching {
                    val o = ai.onnxruntime.OrtSession.SessionOptions().apply {
                        setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(threads)
                        addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
                    }
                    env.createSession(modelFile(app).path, o).also { provider = "NNAPI-FP16" }
                }.getOrElse {
                    runCatching {
                        val o = ai.onnxruntime.OrtSession.SessionOptions().apply {
                            setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            setIntraOpNumThreads(threads)
                            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                        }
                        env.createSession(modelFile(app).path, o).also { provider = "XNNPACK" }
                    }.getOrElse {
                        val o = ai.onnxruntime.OrtSession.SessionOptions().apply {
                            setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            setIntraOpNumThreads(threads)
                        }
                        env.createSession(modelFile(app).path, o)
                    }
                }
                android.util.Log.w("OpenWaves", "sing: loaded UVR-MDX-NET-Inst_HQ_3 with $provider ($threads threads)")
                val t0 = System.currentTimeMillis()
                session.use { s ->
                    val inputName = s.inputNames.first()
                    SingDsp.separateToWav(l, r, tmp, { frac, step, total ->
                        progress.value = frac
                        val elapsed = System.currentTimeMillis() - t0
                        val remSec = if (step > 0 && step < total) ((elapsed.toDouble() / step) * (total - step) / 1000.0).toInt() else 0
                        val eta = when {
                            remSec >= 60 -> " · ~${remSec / 60}m ${remSec % 60}s left"
                            remSec > 0 -> " · ~${remSec}s left"
                            else -> ""
                        }
                        status.value = "Removing vocals ($step/$total)$eta"
                        if (step == 1 || step == total) android.util.Log.w("OpenWaves", "sing: $provider chunk $step/$total elapsed=${elapsed}ms")
                    }) { inputTensorData ->
                        ai.onnxruntime.OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(inputTensorData),
                            longArrayOf(1L, 4L, SingDsp.DIM_F.toLong(), SingDsp.DIM_T.toLong()),
                        ).use { tensor ->
                            s.run(mapOf(inputName to tensor)).use { res ->
                                val fb = (res[0] as ai.onnxruntime.OnnxTensor).floatBuffer
                                FloatArray(fb.remaining()).also { fb.get(it) }
                            }
                        }
                    }
                }
                val dst = wav(app, song.id)
                dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("Couldn't save instrumental")
                trimCache(app)
                working.value = null
                progress.value = null
                status.value = ""
                changed.intValue++
                then()
            } catch (_: CancellationException) {
                File(cacheDir(app), "${song.id}.part").delete()
                working.value = null
                progress.value = null
                status.value = ""
            } catch (e: Exception) {
                File(cacheDir(app), "${song.id}.part").delete()
                working.value = null
                progress.value = null
                status.value = "Sing mode error: ${e.message ?: "couldn't process song"}"
            }
        }
    }

    private fun trimCache(ctx: Context) {
        val files = cacheDir(ctx).listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED).forEach { it.delete() }
    }

    fun swapAll(ctx: Context) {
        if (swapping) return
        val p = player() ?: return
        val lib = Library.cached ?: return
        swapping = true
        try {
            val active = on.value
            val range = if (active) maxOf(0, p.currentMediaItemIndex - 1) until minOf(p.mediaItemCount, p.currentMediaItemIndex + 6)
            else 0 until p.mediaItemCount
            for (i in range) {
                val item = p.getMediaItemAt(i)
                val id = item.mediaId.toLongOrNull() ?: continue
                val isSing = item.localConfiguration?.uri?.path?.contains("/sing/") == true
                if (active && !isSing && has(ctx, id)) lib.byId[id]?.let { swapOne(ctx, p, it, true, i) }
                else if (!active && isSing) lib.byId[id]?.let { swapOne(ctx, p, it, false, i) }
            }
        } finally {
            swapping = false
        }
    }

    private fun swapOne(ctx: Context, p: androidx.media3.common.Player, song: Song, useSing: Boolean, idx: Int) {
        val base = song.toMediaItem()
        val item = if (useSing) base.buildUpon().setUri(Uri.fromFile(wav(ctx, song.id))).setMimeType(MimeTypes.AUDIO_WAV).build() else base
        val current = idx == p.currentMediaItemIndex
        val pos = p.currentPosition
        val playing = p.playWhenReady
        p.replaceMediaItem(idx, item)
        if (current) {
            p.seekTo(idx, pos)
            p.playWhenReady = playing
        }
        changed.intValue++
    }
}

/**
 * Exact UVR-MDX-NET-Inst_HQ_3 STFT / iSTFT / overlap-add engine (matching KaraokeSesh's stems.py).
 * N_FFT = 6144 (= 3 * 2048), HOP = 1024, DIM_F = 3072, DIM_T = 256, COMPENSATE = 1.022.
 */
internal object SingDsp {
    const val SR = 44100
    const val N_FFT = 6144
    const val HOP = 1024
    const val DIM_F = 3072
    const val DIM_T = 256
    const val COMPENSATE = 1.022f
    const val CHUNK = HOP * (DIM_T - 1) // 261,120 samples (~5.92 s)
    const val TRIM = N_FFT / 2          // 3,072 samples
    const val GEN = CHUNK - 2 * TRIM    // 254,976 samples (~5.78 s)

    private val win = FloatArray(N_FFT) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / N_FFT)).toFloat() }
    private val winSq = FloatArray(N_FFT) { i -> win[i] * win[i] }

    // Precomputed 2048-point radix-2 twiddles & bit-reversal table
    private const val N2 = 2048
    private val bitRev = IntArray(N2).also { rev ->
        var j = 0
        for (i in 1 until N2) {
            var bit = N2 shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            rev[i] = j
        }
    }
    private val w1Re = FloatArray(N2) { k -> cos(-2.0 * PI * k / N_FFT).toFloat() }
    private val w1Im = FloatArray(N2) { k -> sin(-2.0 * PI * k / N_FFT).toFloat() }
    private val w2Re = FloatArray(N2) { k -> cos(-4.0 * PI * k / N_FFT).toFloat() }
    private val w2Im = FloatArray(N2) { k -> sin(-4.0 * PI * k / N_FFT).toFloat() }
    private const val C3 = -0.5f
    private const val S3 = -0.8660254f // -sqrt(3)/2

    private fun fft2048(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until N2) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= N2) {
            val half = len shr 1
            val ang = sign * 2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < N2) {
                var wr = 1f; var wi = 0f
                for (j in 0 until half) {
                    val uRe = re[i + j]; val uIm = im[i + j]
                    val vRe = re[i + j + half] * wr - im[i + j + half] * wi
                    val vIm = re[i + j + half] * wi + im[i + j + half] * wr
                    re[i + j] = uRe + vRe; im[i + j] = uIm + vIm
                    re[i + j + half] = uRe - vRe; im[i + j + half] = uIm - vIm
                    val nextWr = wr * wLenRe - wi * wLenIm
                    wi = wr * wLenIm + wi * wLenRe
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Exact 6144-point (3 × 2048) mixed-radix FFT / iFFT. */
    private fun fft6144(
        re: FloatArray, im: FloatArray, inverse: Boolean,
        r0: FloatArray, i0: FloatArray, r1: FloatArray, i1: FloatArray, r2: FloatArray, i2: FloatArray,
    ) {
        for (k in 0 until N2) {
            val idx = k * 3
            r0[k] = re[idx]; i0[k] = im[idx]
            r1[k] = re[idx + 1]; i1[k] = im[idx + 1]
            r2[k] = re[idx + 2]; i2[k] = im[idx + 2]
        }
        fft2048(r0, i0, inverse)
        fft2048(r1, i1, inverse)
        fft2048(r2, i2, inverse)
        val s3 = if (inverse) -S3 else S3
        val scale = if (inverse) 1f / N_FFT else 1f
        for (k in 0 until N2) {
            val aRe = r0[k]; val aIm = i0[k]
            val c1 = w1Re[k]; val s1 = if (inverse) -w1Im[k] else w1Im[k]
            val bRe = r1[k] * c1 - i1[k] * s1
            val bIm = r1[k] * s1 + i1[k] * c1
            val c2 = w2Re[k]; val s2 = if (inverse) -w2Im[k] else w2Im[k]
            val dRe = r2[k] * c2 - i2[k] * s2
            val dIm = r2[k] * s2 + i2[k] * c2

            val sumRe = bRe + dRe; val sumIm = bIm + dIm
            val difRe = bRe - dRe; val difIm = bIm - dIm

            re[k] = (aRe + sumRe) * scale
            im[k] = (aIm + sumIm) * scale

            val tRe = aRe + C3 * sumRe
            val tIm = aIm + C3 * sumIm
            val uRe = -s3 * difIm
            val uIm = s3 * difRe
            re[k + N2] = (tRe + uRe) * scale
            im[k + N2] = (tIm + uIm) * scale
            re[k + 2 * N2] = (tRe - uRe) * scale
            im[k + 2 * N2] = (tIm - uIm) * scale
        }
    }

    private fun reflectIdx(idx: Int, len: Int): Int {
        var i = idx
        while (i < 0 || i >= len) {
            i = if (i < 0) -i else 2 * len - 2 - i
        }
        return i
    }

    suspend fun separateToWav(
        left: FloatArray,
        right: FloatArray,
        outFile: File,
        onProgress: (Float, Int, Int) -> Unit,
        runModel: (FloatArray) -> FloatArray,
    ) = withContext(Dispatchers.Default) {
        val length = left.size
        val pad = GEN - (length % GEN)
        val totalPadded = length + pad
        val steps = totalPadded / GEN

        val re = FloatArray(N_FFT); val im = FloatArray(N_FFT)
        val r0 = FloatArray(N2); val i0 = FloatArray(N2)
        val r1 = FloatArray(N2); val i1 = FloatArray(N2)
        val r2 = FloatArray(N2); val i2 = FloatArray(N2)

        val chunkL = FloatArray(CHUNK)
        val chunkR = FloatArray(CHUNK)
        val tensorIn = FloatArray(4 * DIM_F * DIM_T)
        val istftTotal = N_FFT + HOP * (DIM_T - 1)
        val outL = FloatArray(istftTotal)
        val outR = FloatArray(istftTotal)
        val norm = FloatArray(istftTotal)
        val pcmBuf = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)

        BufferedOutputStream(FileOutputStream(outFile), 1 shl 16).use { out ->
            StemDsp.writeWavHeader(out, 2, length.toLong() * 4L)
            var written = 0
            for (step in 0 until steps) {
                coroutineContext.ensureActive()
                val start = step * GEN - TRIM
                for (i in 0 until CHUNK) {
                    val src = start + i
                    if (src in 0 until length) {
                        chunkL[i] = left[src]
                        chunkR[i] = right[src]
                    } else {
                        chunkL[i] = 0f
                        chunkR[i] = 0f
                    }
                }
                // Centered reflect-padded STFT for Left (ch 0,1) and Right (ch 2,3)
                for (ch in 0..1) {
                    val src = if (ch == 0) chunkL else chunkR
                    val baseRe = (ch * 2) * DIM_F * DIM_T
                    val baseIm = (ch * 2 + 1) * DIM_F * DIM_T
                    for (t in 0 until DIM_T) {
                        val centerStart = t * HOP - TRIM
                        for (n in 0 until N_FFT) {
                            re[n] = src[reflectIdx(centerStart + n, CHUNK)] * win[n]
                            im[n] = 0f
                        }
                        fft6144(re, im, false, r0, i0, r1, i1, r2, i2)
                        for (f in 0 until DIM_F) {
                            val idx = f * DIM_T + t
                            tensorIn[baseRe + idx] = re[f]
                            tensorIn[baseIm + idx] = im[f]
                        }
                    }
                }

                coroutineContext.ensureActive()
                val pred = runModel(tensorIn)

                // Inverse STFT overlap-add
                outL.fill(0f); outR.fill(0f); norm.fill(0f)
                for (t in 0 until DIM_T) {
                    val off = t * HOP
                    for (n in 0 until N_FFT) norm[off + n] += winSq[n]
                }
                for (ch in 0..1) {
                    val dst = if (ch == 0) outL else outR
                    val baseRe = (ch * 2) * DIM_F * DIM_T
                    val baseIm = (ch * 2 + 1) * DIM_F * DIM_T
                    for (t in 0 until DIM_T) {
                        re[0] = pred[baseRe + t]; im[0] = pred[baseIm + t]
                        for (f in 1 until DIM_F) {
                            val idx = f * DIM_T + t
                            val rv = pred[baseRe + idx]
                            val iv = pred[baseIm + idx]
                            re[f] = rv; im[f] = iv
                            re[N_FFT - f] = rv; im[N_FFT - f] = -iv
                        }
                        re[DIM_F] = 0f; im[DIM_F] = 0f
                        fft6144(re, im, true, r0, i0, r1, i1, r2, i2)
                        val off = t * HOP
                        for (n in 0 until N_FFT) dst[off + n] += re[n] * win[n]
                    }
                }
                // Write trimmed valid region [TRIM .. CHUNK - TRIM) from [N_FFT/2 .. N_FFT/2 + CHUNK)
                val validStart = TRIM + TRIM // N_FFT/2 (iSTFT center offset) + TRIM (chunk trim)
                val toWrite = minOf(GEN, length - written)
                var pos = 0
                while (pos < toWrite) {
                    pcmBuf.clear()
                    val batch = minOf(4096, toWrite - pos)
                    for (j in 0 until batch) {
                        val k = validStart + pos + j
                        val invNorm = 1f / maxOf(norm[k], 1e-8f)
                        val sl = (outL[k] * invNorm * COMPENSATE).coerceIn(-1f, 1f)
                        val sr = (outR[k] * invNorm * COMPENSATE).coerceIn(-1f, 1f)
                        pcmBuf.putShort((sl * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                        pcmBuf.putShort((sr * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    }
                    out.write(pcmBuf.array(), 0, batch * 4)
                    pos += batch
                }
                written += toWrite
                onProgress((step + 1).toFloat() / steps, step + 1, steps)
            }
        }
    }
}

/** Live Sing mode banner on Now Playing: shows download/processing progress or active HQ vocal remover status. */
@Composable
internal fun SingNowBanner(song: Song?) {
    val ctx = LocalContext.current
    val active = SingMode.on.value
    val p = SingMode.progress.value
    val st = SingMode.status.value
    if (!active && p == null && st.isBlank()) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.ifBlank { "Removing vocals…" }, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1)
                TextButton({ SingMode.cancel() }) { Text("Stop", color = Accent, fontSize = 12.sp) }
            }
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = Accent)
        } else if (st.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st, Modifier.weight(1f), color = Accent, fontSize = 12.sp, maxLines = 2)
                TextButton({ SingMode.status.value = "" }) { Text("OK", color = Color.White, fontSize = 12.sp) }
            }
        } else if (active && song != null) {
            val ready = SingMode.has(ctx, song.id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ready) "Sing mode · HQ vocal remover on" else "Sing mode · waiting to process…",
                    Modifier.weight(1f), color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
                TextButton({ SingMode.setOn(ctx, false) }) { Text("Turn off", color = Accent, fontSize = 12.sp) }
            }
        }
    }
}

/** One-time download prompt when Sing mode is tapped before UVR-MDX-NET-Inst_HQ_3 is installed. */
@Composable
internal fun SingDownloadDialog() {
    val ctx = LocalContext.current
    if (!SingMode.askingDownload.value) return
    AlertDialog(
        onDismissRequest = { SingMode.askingDownload.value = false },
        title = { Text("Download HQ vocal remover?") },
        text = {
            Text(
                "Sing mode uses UVR-MDX-NET-Inst_HQ_3 (a studio-quality 44.1 kHz vocal remover, open source). " +
                    "One-time ${SingMode.MODEL_MB} MB download — runs 100% on your phone and keeps the instrumental full quality."
            )
        },
        confirmButton = {
            TextButton({
                SingMode.askingDownload.value = false
                SingMode.download(ctx) {
                    if (SingMode.setOn(ctx, true)) SingMode.onReadyForLyrics?.invoke()
                }
            }) { Text("Download (${SingMode.MODEL_MB} MB)", color = Accent) }
        },
        dismissButton = {
            TextButton({ SingMode.askingDownload.value = false }) { Text("Cancel") }
        },
    )
}
