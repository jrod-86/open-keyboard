// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Open Waves ringtone maker (test 239, Jarrod): pick the exact part of a song, like trimming audio in a video editor,
 * then it is cut into its own small file (the song itself is never changed) and set as ringtone, notification or alarm.
 */
object RingtoneMaker {
    const val MIN_MS = 3_000L
    const val MAX_MS = 60_000L
    val kinds = listOf("Ringtone" to RingtoneManager.TYPE_RINGTONE, "Notification" to RingtoneManager.TYPE_NOTIFICATION, "Alarm" to RingtoneManager.TYPE_ALARM)

    /** The loudest stretch of [lenMs] (usually the chorus), from the song's loudness map. */
    fun bestPart(levels: FloatArray, durMs: Long, lenMs: Long): Long {
        val step = BeatMap.STEP_MS
        val win = (lenMs / step).toInt().coerceAtLeast(1)
        if (levels.size <= win) return 0L
        // skip the first and last 10 % (intros and fade-outs)
        val from = levels.size / 10; val to = (levels.size * 9 / 10 - win).coerceAtLeast(from)
        var sum = 0.0
        for (i in from until from + win) sum += levels[i]
        var best = sum; var at = from
        for (i in from + 1..to) { sum += levels[i + win - 1] - levels[i - 1]; if (sum > best) { best = sum; at = i } }
        return (at.toLong() * step).coerceIn(0L, (durMs - lenMs).coerceAtLeast(0L))
    }

    /** Cuts [startMs]..[endMs] out of the song into an .m4a, saves it in the phone's Ringtones (or Notifications / Alarms) folder, and sets it. */
    suspend fun make(ctx: Context, song: Song, startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean, type: Int): Boolean = withContext(Dispatchers.Default) {
        val tmp = File(ctx.cacheDir, "music/ringtone.m4a").apply { parentFile?.mkdirs(); delete() }
        val pcm = decodeRange(ctx, song.uri, startMs, endMs) ?: return@withContext false
        applyFades(pcm, fadeIn, fadeOut)
        if (!encode(pcm, tmp)) return@withContext false
        val uri = saveToPhone(ctx, tmp, song, type) ?: return@withContext false
        tmp.delete()
        runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, type, uri) }.isSuccess
    }

    class Pcm(val data: ShortArray, val rate: Int, val channels: Int)

    internal suspend fun decodeRange(ctx: Context, uri: Uri, startMs: Long, endMs: Long): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec = c
            c.configure(fmt, null, null, 0); c.start()
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val out = ArrayList<ShortArray>()
            var total = 0
            val startUs = startMs * 1000; val endUs = endMs * 1000
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val sb = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        sb.position(info.offset / 2); sb.limit((info.offset + info.size) / 2)
                        val frames = sb.remaining() / channels
                        // keep only the frames inside the chosen part
                        val t0 = info.presentationTimeUs
                        val first = if (t0 >= startUs) 0 else ((startUs - t0) * rate / 1_000_000).toInt().coerceAtMost(frames)
                        val last = if (t0 >= endUs) 0 else (((endUs - t0) * rate / 1_000_000).toInt()).coerceIn(0, frames)
                        if (last > first) {
                            val outCh = channels.coerceAtMost(2)
                            val a = ShortArray((last - first) * outCh)
                            var k = 0
                            for (f in first until last) for (ch in 0 until outCh) a[k++] = sb.get((f * channels) + ch)
                            out += a; total += a.size
                        }
                        if (t0 >= endUs) outDone = true
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            val all = ShortArray(total); var p = 0
            out.forEach { System.arraycopy(it, 0, all, p, it.size); p += it.size }
            return if (total > 0) Pcm(all, rate, channels.coerceAtMost(2)) else null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }

    private fun applyFades(p: Pcm, fadeIn: Boolean, fadeOut: Boolean) {
        val frames = p.data.size / p.channels
        val len = (p.rate * 1.5).toInt().coerceAtMost(frames / 3)
        if (len <= 0) return
        for (f in 0 until len) {
            val g = f.toFloat() / len
            for (ch in 0 until p.channels) {
                if (fadeIn) { val i = f * p.channels + ch; p.data[i] = (p.data[i] * g).toInt().toShort() }
                if (fadeOut) { val i = (frames - 1 - f) * p.channels + ch; p.data[i] = (p.data[i] * g).toInt().toShort() }
            }
        }
    }

    private suspend fun encode(p: Pcm, file: File): Boolean {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, p.rate, p.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val c = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return false
        val mux = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); c.start()
            val bytes = ByteBuffer.allocate(p.data.size * 2).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(p.data)
            val raw = bytes.array()
            var pos = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val frameBytes = 2 * p.channels
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.capacity() / frameBytes * frameBytes, raw.size - pos)
                        val pts = (pos / frameBytes).toLong() * 1_000_000 / p.rate
                        if (n <= 0) { c.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { buf.put(raw, pos, n); pos += n; c.queueInputBuffer(i, 0, n, pts, 0) }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(c.outputFormat); mux.start(); started = true
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!
                    if (info.size > 0 && started && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        mux.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            return started
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { c.stop() }; runCatching { c.release() }
            runCatching { if (started) mux.stop() }; runCatching { mux.release() }
        }
    }

    private fun saveToPhone(ctx: Context, file: File, song: Song, type: Int): Uri? = runCatching {
        val folder = when (type) {
            RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
            RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
            else -> Environment.DIRECTORY_RINGTONES
        }
        val name = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60).ifBlank { "Ringtone" }
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name (Open Waves).m4a")
            put(MediaStore.Audio.Media.TITLE, "$name (ringtone)")
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/")
            v.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "").apply { mkdirs() }
            val dest = File(dir, "$name (Open Waves).m4a")
            file.copyTo(dest, overwrite = true)
            @Suppress("DEPRECATION") v.put(MediaStore.Audio.Media.DATA, dest.path)
            ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
        }
    }.getOrNull()
}

/** The trimming screen: whole-song waveform with two handles, fine nudges, length presets, preview and save. */
@Composable
internal fun RingtonePage(song: Song, ui: PlayerUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dur = song.durationMs.coerceAtLeast(RingtoneMaker.MIN_MS)
    var map by remember { mutableStateOf<BeatMap?>(null) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(minOf(20_000L, dur)) }
    var fadeIn by remember { mutableStateOf(false) }
    var fadeOut by remember { mutableStateOf(true) }
    var kind by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var head by remember { mutableLongStateOf(-1L) }
    var dragging by remember { mutableIntStateOf(0) } // 1 start, 2 end, 3 whole part
    var dragFrom by remember { mutableFloatStateOf(0f) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stopPreview() { player[0]?.let { runCatching { it.stop() }; it.release() }; player[0] = null; playing = false; head = -1 }
    DisposableEffect(Unit) { onDispose { stopPreview() } }
    BackHandler { stopPreview(); onBack() }

    LaunchedEffect(song.id) {
        val m = withContext(Dispatchers.IO) { BeatMap.cached(ctx, song.id) } ?: BeatMap.analyze(ctx, song)
        map = m
        // start on the best-sounding part (usually the chorus) instead of the beginning
        if (m != null && m.level.isNotEmpty()) { start = RingtoneMaker.bestPart(m.level, dur, end - start); end = (start + 20_000L).coerceAtMost(dur) }
    }
    // preview: plays only the chosen part, with the fades you'll hear
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val mp = player[0] ?: return@LaunchedEffect
        while (playing && player[0] === mp) {
            val t = runCatching { mp.currentPosition.toLong() }.getOrDefault(start)
            head = t
            val g = minOf(if (fadeIn) ((t - start) / 1500f) else 1f, if (fadeOut) ((end - t) / 1500f) else 1f).coerceIn(0f, 1f)
            runCatching { mp.setVolume(g, g) }
            if (t >= end) { stopPreview(); break }
            delay(30)
        }
    }
    fun preview() {
        if (playing) { stopPreview(); return }
        ui.controller?.pause()
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(ctx, song.uri); mp.prepare(); mp.seekTo(start.toInt())
            if (fadeIn) mp.setVolume(0f, 0f)
            mp.start()
        }.onFailure { mp.release(); return }
        player[0] = mp; playing = true
    }
    fun setPart(s: Long, e: Long) {
        val len = (e - s).coerceIn(RingtoneMaker.MIN_MS, RingtoneMaker.MAX_MS)
        val ns = s.coerceIn(0L, (dur - RingtoneMaker.MIN_MS).coerceAtLeast(0L))
        start = ns; end = (ns + len).coerceAtMost(dur)
        if (playing) stopPreview()
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ stopPreview(); onBack() }) { Icon(MusicIcons.arrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Make a ringtone", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${song.title} · ${song.artist}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Drag the two handles to choose the part you want. Drag the middle to move it.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            // ---- the whole song ----
            val m = map
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E))) {
                if (m == null) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text("Reading the song…", color = Color.White.copy(alpha = 0.7f))
                }
                val grab = with(density) { 28.dp.toPx() }
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(dur) {
                        detectTapGestures { o ->
                            // tap: move the part so it starts there
                            val t = (o.x / size.width * dur).roundToLong(); setPart(t, t + (end - start))
                        }
                    }
                    .pointerInput(dur) {
                        detectDragGestures(onDragStart = { o ->
                            val w = size.width.toFloat()
                            val sx = start.toFloat() / dur * w; val ex = end.toFloat() / dur * w
                            dragging = when {
                                abs(o.x - sx) <= grab && abs(o.x - sx) <= abs(o.x - ex) -> 1
                                abs(o.x - ex) <= grab -> 2
                                o.x in sx..ex -> 3
                                else -> 0
                            }
                            dragFrom = o.x
                            if (playing) stopPreview()
                        }, onDragEnd = { dragging = 0 }, onDragCancel = { dragging = 0 }) { change, d ->
                            val msPerPx = dur.toFloat() / size.width
                            val dm = (d.x * msPerPx).roundToLong()
                            when (dragging) {
                                1 -> start = (start + dm).coerceIn(maxOf(0L, end - RingtoneMaker.MAX_MS), end - RingtoneMaker.MIN_MS)
                                2 -> end = (end + dm).coerceIn(start + RingtoneMaker.MIN_MS, minOf(dur, start + RingtoneMaker.MAX_MS))
                                3 -> { val len = end - start; val ns = (start + dm).coerceIn(0L, dur - len); start = ns; end = ns + len }
                            }
                            change.consume()
                        }
                    }) {
                    val w = size.width; val h = size.height
                    val sx = start.toFloat() / dur * w; val ex = end.toFloat() / dur * w
                    if (m != null && m.level.isNotEmpty()) {
                        val bars = (w / 6f).toInt().coerceAtLeast(10)
                        val per = m.level.size.toFloat() / bars
                        val top = m.level.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
                        for (b in 0 until bars) {
                            var v = 0f
                            val from = (b * per).toInt(); val to = ((b + 1) * per).toInt().coerceAtMost(m.level.size).coerceAtLeast(from + 1)
                            for (i in from until to.coerceAtMost(m.level.size)) v = maxOf(v, m.level[i])
                            val bh = (v / top).coerceIn(0.04f, 1f) * h * 0.8f
                            val x = b * w / bars + 1.5f
                            val inside = x in sx..ex
                            drawRoundRect(if (inside) Accent else Color.White.copy(alpha = 0.28f), Offset(x, (h - bh) / 2), Size(3f * density.density / 1.2f, bh), CornerRadius(3f))
                        }
                    }
                    // chosen part
                    drawRect(Accent.copy(alpha = 0.16f), Offset(sx, 0f), Size(ex - sx, h))
                    listOf(sx, ex).forEach { x ->
                        drawRect(Color.White, Offset(x - 2f, 0f), Size(4f, h))
                        drawCircle(Color.White, 9.dp.toPx(), Offset(x, h - 12.dp.toPx()))
                        drawCircle(Accent, 5.dp.toPx(), Offset(x, h - 12.dp.toPx()))
                    }
                    if (head >= 0) { val hx = head.toFloat() / dur * w; drawRect(Color(0xFFFFEA00), Offset(hx - 1.5f, 0f), Size(3f, h)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("0:00", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(time(dur), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            // ---- exact times with fine nudges ----
            Spacer(Modifier.height(12.dp))
            NudgeRow("Start", start, { setPart(start + it, end) })
            NudgeRow("End", end, { val e = (end + it).coerceIn(start + RingtoneMaker.MIN_MS, minOf(dur, start + RingtoneMaker.MAX_MS)); end = e; if (playing) stopPreview() })
            Text("Length: ${"%.1f".format((end - start) / 1000f)} seconds", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 20, 30, 40).forEach { s ->
                    FilterChip(end - start == s * 1000L, { setPart(start, start + s * 1000L) }, { Text("$s s") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, labelColor = Color.White))
                }
            }
            OutlinedButton({ m?.let { setPart(RingtoneMaker.bestPart(it.level, dur, end - start), RingtoneMaker.bestPart(it.level, dur, end - start) + (end - start)) } },
                Modifier.padding(top = 6.dp), enabled = m != null) { Text("✨ Find the best part (chorus)", color = Color.White) }
            // ---- preview ----
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White).pointerInput(Unit) { detectTapGestures { preview() } }, contentAlignment = Alignment.Center) {
                    Icon(if (playing) MusicIcons.pause else MusicIcons.play, "Play the part", tint = Color.Black, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(if (playing) "Playing ${time((head - start).coerceAtLeast(0))} of ${time(end - start)}" else "Listen to the part you picked",
                    color = Color.White.copy(alpha = 0.8f))
            }
            // ---- fades ----
            SwitchRow("Fade in", fadeIn) { fadeIn = it }
            SwitchRow("Fade out", fadeOut) { fadeOut = it }
            // ---- use as ----
            Text("Use it as", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RingtoneMaker.kinds.forEachIndexed { i, (label, _) ->
                    FilterChip(kind == i, { kind = i }, { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, labelColor = Color.White))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Button({
            if (!Settings.System.canWrite(ctx)) {
                Toast.makeText(ctx, "Allow Open Waves to change system settings, then press Save again", Toast.LENGTH_LONG).show()
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return@Button
            }
            stopPreview(); saving = true
            val (label, type) = RingtoneMaker.kinds[kind]
            scope.launch {
                val ok = RingtoneMaker.make(ctx, song, start, end, fadeIn, fadeOut, type)
                saving = false
                Toast.makeText(ctx, if (ok) "Done! It's now your ${label.lowercase()}" else "Couldn't make the ringtone from this song", Toast.LENGTH_LONG).show()
                if (ok) onBack()
            }
        }, Modifier.fillMaxWidth().padding(16.dp).height(54.dp), enabled = !saving && map != null,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            if (saving) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Cutting…") }
            else Text("Save and set as ${RingtoneMaker.kinds[kind].first.lowercase()}", fontSize = 17.sp)
        }
    }
}

@Composable
private fun NudgeRow(label: String, ms: Long, nudge: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.width(52.dp))
        Text("%d:%04.1f".format(ms / 60000, (ms % 60000) / 1000f), color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        listOf(-1000L to "−1s", -100L to "−0.1", 100L to "+0.1", 1000L to "+1s").forEach { (d, t) ->
            Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2E))
                .pointerInput(d) { detectTapGestures { nudge(d) } }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(t, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Suppress("unused") private val unusedBaos = ByteArrayOutputStream::class
@Suppress("unused") @Composable private fun unusedTheme() = MaterialTheme.colorScheme
