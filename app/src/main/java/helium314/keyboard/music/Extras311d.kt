// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package helium314.keyboard.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

// ---------------- tag editor ----------------
/**
 * Test 311 (Jarrod, "everything the best players have"): change a song's name, artist, album, album artist, genre,
 * year, track number and composer, written into the music file itself (copied out, edited, checked, copied back).
 */
object TagEdit {
    val asking = mutableStateOf<Song?>(null)
    /** set by the activity: Android's "Allow Open Waves to modify this file?" */
    var askWrite: ((android.content.IntentSender) -> Unit)? = null
    /** set by the activity: reload the library */
    var afterSave: (() -> Unit)? = null
    private var waiting: Pair<Song, Map<FieldKey, String>>? = null

    val FIELDS = listOf(
        FieldKey.TITLE to "Song name", FieldKey.ARTIST to "Artist", FieldKey.ALBUM to "Album", FieldKey.ALBUM_ARTIST to "Album artist",
        FieldKey.GENRE to "Genre", FieldKey.YEAR to "Year", FieldKey.TRACK to "Track number", FieldKey.COMPOSER to "Composer",
    )

    /** What's in the file now (falls back to what the library knows). */
    suspend fun read(ctx: Context, s: Song): Map<FieldKey, String> = withContext(Dispatchers.IO) {
        val known = mapOf(FieldKey.TITLE to s.title, FieldKey.ARTIST to s.artist, FieldKey.ALBUM to s.album, FieldKey.ALBUM_ARTIST to s.albumArtist,
            FieldKey.GENRE to s.genre, FieldKey.YEAR to (if (s.year > 0) s.year.toString() else ""), FieldKey.TRACK to (if (s.track > 0) s.track.toString() else ""),
            FieldKey.COMPOSER to "")
        val tag = runCatching { File(s.path).takeIf { it.canRead() }?.let { AudioFileIO.read(it).tag } }.getOrNull()
        if (tag == null) return@withContext known
        FIELDS.associate { (k, _) -> k to (runCatching { tag.getFirst(k) }.getOrNull()?.takeIf { it.isNotBlank() } ?: known[k].orEmpty()) }
    }

    fun save(ctx: Context, s: Song, values: Map<FieldKey, String>) {
        if (Build.VERSION.SDK_INT >= 30 && !FileFixer.canManageMedia(ctx)) {
            val ask = askWrite
            if (ask != null && runCatching { waiting = s to values; ask(MediaStore.createWriteRequest(ctx.contentResolver, listOf(s.uri)).intentSender) }.isSuccess) return
        }
        writeInBackground(ctx, s, values)
    }

    fun writeAnswered(ctx: Context, ok: Boolean) {
        val w = waiting ?: return
        waiting = null
        if (ok) writeInBackground(ctx, w.first, w.second) else Toast.makeText(ctx, "Not saved: Android didn't allow the change", Toast.LENGTH_SHORT).show()
    }

    private fun writeInBackground(ctx: Context, s: Song, values: Map<FieldKey, String>) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val err = runCatching { write(app, s, values) }.exceptionOrNull()
            if (err == null) {
                // the phone re-reads the file in a while: show the new info straight away
                val old = SongFixer.all(app)[s.id]
                SongFixer.save(app, mapOf(s.id to SongFix(values[FieldKey.TITLE].orEmpty(), values[FieldKey.ARTIST].orEmpty(), values[FieldKey.ALBUM].orEmpty(),
                    values[FieldKey.YEAR]?.take(4)?.toIntOrNull() ?: 0, old?.art.orEmpty())))
                Library.load(app, fresh = true)
            } else android.util.Log.w("OpenWaves", "tag edit failed for ${s.path}", err)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (err == null) "Saved into the file" else "Couldn't change this file", Toast.LENGTH_SHORT).show()
                if (err == null) afterSave?.invoke()
            }
        }
    }

    private fun write(ctx: Context, s: Song, values: Map<FieldKey, String>) {
        val ext = s.path.substringAfterLast('.', "mp3").lowercase()
        val tmp = File(ctx.cacheDir, "tagedit_${s.id}.$ext")
        try {
            ctx.contentResolver.openInputStream(s.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val size = tmp.length()
            TagWriter // sets jaudiotagger up for Android
            val af = AudioFileIO.read(tmp)
            val tag = af.tagOrCreateAndSetDefault
            for ((k, v) in values) {
                val t = v.trim()
                if (t.isEmpty()) runCatching { tag.deleteField(k) } else tag.setField(k, t)
            }
            af.commit()
            AudioFileIO.read(tmp)
            if (tmp.length() < size / 2) throw IllegalStateException("edited file looks too small")
            ctx.contentResolver.openOutputStream(s.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (s.path.isNotEmpty()) MediaScannerConnection.scanFile(ctx, arrayOf(s.path), null, null)
        } finally { tmp.delete() }
    }
}

@Composable
internal fun TagEditDialog() {
    val ctx = LocalContext.current
    val s = TagEdit.asking.value ?: return
    val values = remember(s.id) { mutableStateOf<Map<FieldKey, String>?>(null) }
    LaunchedEffect(s.id) { values.value = TagEdit.read(ctx, s) }
    AlertDialog(onDismissRequest = { TagEdit.asking.value = null },
        title = { Text("Edit tags") },
        text = {
            val v = values.value
            if (v == null) Text("Reading the file…")
            else Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                for ((k, label) in TagEdit.FIELDS) {
                    val number = k == FieldKey.YEAR || k == FieldKey.TRACK
                    OutlinedTextField(v[k].orEmpty(), { t -> values.value = v + (k to t) }, label = { Text(label) }, singleLine = true,
                        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Saved into the music file, so every app sees it. A blank box clears that tag.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = { TextButton({ values.value?.let { TagEdit.save(ctx, s, it) }; TagEdit.asking.value = null }, enabled = values.value != null) { Text("Save") } },
        dismissButton = { TextButton({ TagEdit.asking.value = null }) { Text("Cancel") } })
}

// ---------------- lyrics editor ----------------
/** Test 311: type or fix a song's lyrics. Lines that start with a time like [01:23.45] scroll along with the song. */
object LyricsEdit {
    val editing = mutableStateOf<Song?>(null)
    /** goes up after a save, so the lyrics screen loads them again */
    val version = mutableIntStateOf(0)

    fun asText(l: Lyrics): String = l.lines.joinToString("\n") { line ->
        if (!l.synced) line.text else {
            val t = line.timeMs; val m = t / 60_000; val sec = (t / 1000) % 60; val cs = (t / 10) % 100
            "[%02d:%02d.%02d] %s".format(m, sec, cs, line.text)
        }
    }
}

@Composable
internal fun LyricsEditDialog(ui: PlayerUi) {
    val ctx = LocalContext.current
    val s = LyricsEdit.editing.value ?: return
    var text by remember(s.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id) {
        text = Lyrics.local(ctx, s)?.let(LyricsEdit::asText)
            ?: MusicState.embeddedLyrics.value?.takeIf { it.first == s.id }?.second?.let { Lyrics.parse(it, "")?.let(LyricsEdit::asText) ?: it }
            ?: ""
    }
    fun close() { LyricsEdit.editing.value = null }
    // test 387 (Jarrod): full-screen IME-aware dialog so the text box shrinks cleanly above the keyboard
    // and the lines being edited (plus Save/Cancel) are always clearly visible while typing.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = ::close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(::close) { Text("Cancel") }
                    Text(
                        s.title,
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton({
                        text?.let { t ->
                            if (t.isBlank()) runCatching { File(ctx.filesDir, "music/lyrics/${s.id}.lrc").delete() } else Lyrics.save(ctx, s, t.trim())
                            LyricsEdit.version.intValue++
                        }
                        close()
                    }, enabled = text != null) { Text("Save", color = Accent, fontWeight = FontWeight.Bold) }
                }
                val t = text
                if (t == null) {
                    Text("Loading…", Modifier.padding(16.dp))
                } else {
                    OutlinedTextField(
                        t,
                        { text = it },
                        Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp),
                        placeholder = { Text("Type or paste the lyrics, one line each") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            val pos = ui.position
                            val lines = t.split('\n').toMutableList()
                            val i = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("[") }
                            if (i >= 0) {
                                val m = pos / 60_000; val sec = (pos / 1000) % 60; val cs = (pos / 10) % 100
                                lines[i] = "[%02d:%02d.%02d] %s".format(m, sec, cs, lines[i].trim())
                                text = lines.joinToString("\n")
                            }
                        }) { Text("⏱ Time next line") }
                        Text(
                            "Tap ⏱ as each line starts",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- AI radio announcer ----------------
/**
 * Test 311 (Jarrod's idea): like a radio station, a DJ talks into the next song. The writing model (in Chat's process,
 * all on the phone) writes a line or two about it and the Zonos voice says it while the music dips. Off by default.
 */
object Announcer {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    val enabled = androidx.compose.runtime.mutableStateOf(false)
    fun load(ctx: Context) { enabled.value = prefs(ctx).getBoolean("ann_on", false) }
    fun on(ctx: Context): Boolean {
        val v = prefs(ctx).getBoolean("ann_on", false)
        if (enabled.value != v) enabled.value = v
        return v
    }
    fun every(ctx: Context) = prefs(ctx).getInt("ann_every", 3)
    fun style(ctx: Context) = prefs(ctx).getInt("ann_style", 0)
    /** test 330 (Jarrod): 0 = Short (~6-8 s), 1 = Medium (~12-15 s), 2 = Long (~18-22 s) */
    fun length(ctx: Context) = prefs(ctx).getInt("ann_len", 1).coerceIn(0, 2)
    /** test 355 (Jarrod): 0 = Savannah (Zonos · richest), 1 = Heart (Kokoro · low battery) */
    fun voiceEngine(ctx: Context) = prefs(ctx).getInt("ann_voice_eng", 0).coerceIn(0, 1)
    /** test 341 (Jarrod): battery warning dialog when enabling DJ in Settings (dismissible with "Don't show again") */
    fun showBatteryWarn(ctx: Context) = !prefs(ctx).getBoolean("ann_no_batt_warn", false)
    fun hideBatteryWarn(ctx: Context) { prefs(ctx).edit().putBoolean("ann_no_batt_warn", true).apply() }
    /** test 327 (Jarrod): 0 = Instant (built-in radio lines), 1 = Quick AI (1.1 GB), 2 = Better AI (2.5 GB) */
    fun aiMode(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.contains("ann_ai_mode")) p.getInt("ann_ai_mode", 0).coerceIn(0, 2)
        else if (p.getBoolean("ann_ai2", false)) 1 else 0
    }
    fun set(ctx: Context, key: String, v: Any) {
        prefs(ctx).edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v.toString()) } }.apply()
        failedFor = -1
        spokenFor = -1
        ready = null
        makingJob?.cancel(); makingJob = null; making = -1
        if (key == "ann_on" && v is Boolean) {
            enabled.value = v
            if (v) songsSince = 1000
            else {
                hush()
                idleUnloadJob?.cancel()
                scope.launch {
                    ttsLock.withLock {
                        runCatching { helium314.keyboard.tts.ZonosTts.stop() }
                        runCatching { helium314.keyboard.tts.KokoroTts.release() }
                    }
                }
            }
        } else {
            if (key == "ann_voice_eng") {
                idleUnloadJob?.cancel()
                scope.launch {
                    ttsLock.withLock {
                        runCatching { helium314.keyboard.tts.ZonosTts.stop() }
                        runCatching { helium314.keyboard.tts.KokoroTts.release() }
                    }
                }
            }
            if (enabled.value) {
                // Changing length, style, voice, AI mode, or frequency takes effect on the very next song right away
                songsSince = 1000
            }
        }
    }

    val STYLES = listOf(
        "Friendly" to "a warm, friendly daytime radio DJ (casual, welcoming, like 'Hope you're enjoying the music!')",
        "Hyped" to "an excited, high-energy party radio DJ (punchy, upbeat, like 'Turn it up, here comes a banger!')",
        "Late night" to "a smooth, calm late-night radio DJ (relaxed, laid-back, like 'Settle in and unwind with this one...')",
        "Funny" to "a cheeky, witty radio DJ who cracks one quick light-hearted joke about the music or radio",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Ready(val forId: Long, val audio: FloatArray, val rate: Int)
    @Volatile private var ready: Ready? = null
    @Volatile private var making: Long = -1
    @Volatile private var makingJob: Job? = null
    @Volatile private var speaking: Job? = null
    @Volatile private var idleUnloadJob: Job? = null
    @Volatile private var track: AudioTrack? = null
    // test 316: the first song after turning it on gets a DJ line too
    private var songsSince = 1000
    /** test 324: song id we already started announcing so every==1 doesn't immediately re-generate it 1 s later */
    @Volatile private var spokenFor: Long = -1
    /** a song the line couldn't be made for: not tried again (test 316: it kept retrying, the phone got hot) */
    @Volatile private var failedFor: Long = -1
    /** fade-out ramp duration before Savannah begins speaking */
    private const val FADE_OUT_MS = 700L

    // test 317/355 (Jarrod): Savannah (Zonos) or Heart (Kokoro · low battery)
    private fun zonosVoice() = helium314.keyboard.tts.ZonosTts.VOICES.firstOrNull { it.id == "savannah" }
        ?: helium314.keyboard.tts.ZonosTts.VOICES[0]
    private fun kokoroHeart() = helium314.keyboard.tts.KokoroTts.VOICES.firstOrNull { it.id == "af_heart" }
        ?: helium314.keyboard.tts.KokoroTts.VOICES[0]
    fun zonosReady(ctx: Context) = helium314.keyboard.tts.ZonosTts.installed(ctx) && helium314.keyboard.tts.ZonosTts.supported(ctx)
    fun kokoroReady(ctx: Context) = helium314.keyboard.tts.KokoroTts.installed(ctx)
    fun ready(ctx: Context) = if (voiceEngine(ctx) == 1) kokoroReady(ctx) else zonosReady(ctx)

    /** Keeps the active TTS engine warm between songs during playback (avoids reloading from flash on 3-8 min songs), unloading after 10 min idle. */
    private fun bumpIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            kotlinx.coroutines.delay(10 * 60_000L)
            ttsLock.withLock {
                runCatching { helium314.keyboard.tts.ZonosTts.stop() }
                runCatching { helium314.keyboard.tts.KokoroTts.release() }
            }
        }
    }

    private val ttsLock = kotlinx.coroutines.sync.Mutex()
    /** Synthesises the line using the selected engine (Savannah 44.1 kHz or Kokoro Heart 24 kHz) with broadcast RMS loudness. */
    private suspend fun speech(ctx: Context, text: String, cancel: () -> Boolean = { !on(ctx) }): Pair<FloatArray, Int> = ttsLock.withLock {
        idleUnloadJob?.cancel()
        val useKokoro = voiceEngine(ctx) == 1
        val cleanText = helium314.keyboard.tts.ChatSpeaker.forSpeech(text)
        // test 360 (Jarrod): pin thread to lowest priority right before TTS so ONNX/Zonos worker threads never compete with music audio
        val prevPrio = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrDefault(0)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST) }
        val (out, rate) = try {
            if (useKokoro) {
                val k = kokoroHeart()
                val prepared = helium314.keyboard.tts.Pronounce.prepare(cleanText, helium314.keyboard.tts.KokoroTts.knownWords(ctx, k))
                // test 359/360 (Jarrod): 0.94x speed + 2-thread cap so music playback always keeps full CPU priority
                val samples = helium314.keyboard.tts.KokoroTts.engine(ctx, k, maxThreads = 2).generate(prepared, k.sid, 0.94f).samples
                warmBroadcastVoice(samples, helium314.keyboard.tts.KokoroTts.RATE) to helium314.keyboard.tts.KokoroTts.RATE
            } else {
                val parts = ArrayList<FloatArray>()
                helium314.keyboard.tts.ZonosTts.speak(ctx, cleanText, zonosVoice(), null, cancel) { parts += it }
                val buf = FloatArray(parts.sumOf { it.size }); var i = 0
                for (p in parts) { System.arraycopy(p, 0, buf, i, p.size); i += p.size }
                buf to helium314.keyboard.tts.ZonosTts.RATE
            }
        } finally {
            runCatching { android.os.Process.setThreadPriority(prevPrio) }
        }
        bumpIdleUnload()
        // test 323 (Jarrod): broadcast-style speech loudness (~20% louder than the music before it, like FM radio)
        // Normalise active speech RMS to ~0.28 (~ -11 dBFS, ~20% above -14 LUFS music) with smooth soft-limiting so it never clips
        var sumSq = 0.0; var active = 0
        for (v in out) { if (kotlin.math.abs(v) > 0.01f) { sumSq += v * v; active++ } }
        val rms = if (active > 0) kotlin.math.sqrt(sumSq / active).toFloat() else 0.1f
        val gain = (0.28f / rms.coerceAtLeast(0.03f)).coerceIn(1.2f, 4.5f)
        val drive = if (useKokoro) 1.05f else 1.15f
        val norm = kotlin.math.tanh(drive.toDouble()).toFloat()
        for (k in out.indices) out[k] = (kotlin.math.tanh((out[k] * gain * drive).toDouble()).toFloat() / norm).coerceIn(-0.98f, 0.98f)
        out to rate
    }

    /**
     * test 359 / 361 (Jarrod): Studio FM radio voice chain for Kokoro Heart (~15 ms in pure Kotlin, zero battery impact).
     * Balanced for crisp, open vocal clarity + subtle broadcast warmth (no muffling):
     * 1) 85 Hz HPF  2) +1.6 dB @ 160 Hz subtle warmth  3) +1.8 dB @ 2.8 kHz vocal clarity/presence
     * 4) -2.0 dB @ 5.8 kHz narrow de-esser  5) +1.0 dB @ 8.5 kHz open air  6) gentle compressor  7) tiny ~3.5% booth ambience.
     */
    private fun warmBroadcastVoice(samples: FloatArray, rate: Int): FloatArray {
        if (samples.size < 64) return samples
        val sr = rate.toDouble()
        fun applyBiquad(buf: FloatArray, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
            val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0; val na1 = a1 / a0; val na2 = a2 / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in buf.indices) {
                val x0 = buf[i].toDouble()
                val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
                buf[i] = y0.toFloat()
            }
        }
        // 1) 85 Hz high-pass (cleans DC/rumble)
        run {
            val w0 = 2.0 * Math.PI * 85.0 / sr; val c = kotlin.math.cos(w0); val alpha = kotlin.math.sin(w0) / (2.0 * 0.707)
            applyBiquad(samples, (1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
        // 2) Subtle low-shelf warmth (+1.6 dB @ 160 Hz — light broadcast body without muddiness)
        run {
            val a = Math.pow(10.0, 1.6 / 40.0); val w0 = 2.0 * Math.PI * 160.0 / sr; val c = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / 0.8 - 1.0) + 2.0)
            val sq = 2.0 * kotlin.math.sqrt(a) * alpha
            applyBiquad(samples, a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq),
                (a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq)
        }
        // 3) Vocal clarity / presence lift (+1.8 dB @ 2800 Hz, Q = 1.2 — crisp, intelligible speech over road noise)
        run {
            val a = Math.pow(10.0, 1.8 / 40.0); val w0 = 2.0 * Math.PI * 2800.0 / sr; val c = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / (2.0 * 1.2)
            applyBiquad(samples, 1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }
        // 4) Surgical narrow de-esser (-2.0 dB @ 5800 Hz, Q = 2.2 — tames harsh 's' spikes without dulling the voice)
        run {
            val a = Math.pow(10.0, -2.0 / 40.0); val w0 = 2.0 * Math.PI * 5800.0 / sr; val c = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / (2.0 * 2.2)
            applyBiquad(samples, 1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }
        // 5) Gentle studio air (+1.0 dB high-shelf @ 8500 Hz — open, clear condenser-mic sparkle)
        run {
            val a = Math.pow(10.0, 1.0 / 40.0); val w0 = 2.0 * Math.PI * 8500.0 / sr; val c = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / 0.7 - 1.0) + 2.0)
            val sq = 2.0 * kotlin.math.sqrt(a) * alpha
            applyBiquad(samples, a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq),
                (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq)
        }
        // 6) Gentle broadcast compressor (evens out quiet words & loud peaks smoothly)
        val atk = kotlin.math.exp(-1.0 / (0.008 * sr)).toFloat()
        val rel = kotlin.math.exp(-1.0 / (0.120 * sr)).toFloat()
        var env = 0f
        for (i in samples.indices) {
            val x = samples[i]; val ax = kotlin.math.abs(x)
            env = if (ax > env) atk * env + (1f - atk) * ax else rel * env + (1f - rel) * ax
            if (env > 0.16f) {
                val over = env / 0.16f
                val g = Math.pow(over.toDouble(), (1.0 / 2.4) - 1.0).toFloat()
                samples[i] = x * g
            }
        }
        // 7) Subtle close-mic studio ambience (15 ms + 26 ms damped taps, ~3.5% wet)
        val d1 = (rate * 0.015).toInt().coerceAtLeast(1)
        val d2 = (rate * 0.026).toInt().coerceAtLeast(1)
        val dry = samples.copyOf()
        var lp = 0f
        for (i in samples.indices) {
            val t1 = if (i >= d1) dry[i - d1] else 0f
            val t2 = if (i >= d2) dry[i - d2] else 0f
            val refl = t1 * 0.022f + t2 * 0.014f
            lp += 0.55f * (refl - lp)
            samples[i] = dry[i] * 0.98f + lp
        }
        return samples
    }

    /**
     * Called every second by the music service. Test 321: strictly one prep job at a time (never piles up
     * concurrent Zonos/Qwen runs that cause lag and OOM kills), and waits if Stems is actively splitting.
     */
    fun tick(ctx: Context, p: Player) {
        if (!on(ctx) || !p.isPlaying || p.duration <= 0) return
        if (songsSince + 1 < every(ctx)) return
        val nextIndex = p.nextMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: return
        val lib = Library.cached ?: return
        val next = p.getMediaItemAt(nextIndex).mediaId.toLongOrNull()?.let { lib.byId[it] } ?: return
        val cur = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { lib.byId[it] }
        val left = p.duration - p.currentPosition
        val r = ready
        // 2a. if a clip finished synthesizing just after the song it introduces started, play it over the intro instead of dropping it
        if (r != null && cur != null && r.forId == cur.id && p.currentPosition < 12_000L && speaking?.isActive != true) {
            ready = null; songsSince = 0; spokenFor = cur.id
            android.util.Log.w("OpenWaves", "announcer: late-ready intro into ${cur.title} (pos=${p.currentPosition}ms)")
            say(r.audio, r.rate)
            return
        }
        // 2b. ready and near the end: start at 65% of clip duration (+ fade-out + crossfade offset) before song end
        // so ~65% plays over the finishing song's outro and ~35% lands over the new song's intro ("hitting the post")
        if (r != null && r.forId == next.id) {
            val clipMs = (r.audio.size * 1000L) / r.rate
            val blendOffset = if (MusicSettings.blendOn) (MusicSettings.blendSec * 750L).toLong() else 0L
            val startBeforeEndMs = ((clipMs * 65L) / 100L + FADE_OUT_MS + blendOffset).coerceIn(4_000L, 26_000L)
            if (left <= startBeforeEndMs && speaking?.isActive != true) {
                ready = null; songsSince = 0; spokenFor = next.id
                android.util.Log.w("OpenWaves", "announcer: talking into ${next.title} (clip=${clipMs}ms, rate=${r.rate}, blendOff=${blendOffset}ms, 65/35 trigger=${startBeforeEndMs}ms)")
                say(r.audio, r.rate)
            }
            return
        }
        // 1. strictly one prep job at a time; never re-generate a song we already announced; never overlap with stem splitting
        if (spokenFor == next.id) return
        if (makingJob?.isActive == true) {
            // keep finishing if it's for the song that just started (<12s in); cancel only if the user skipped elsewhere
            val forJustStarted = cur != null && making == cur.id && p.currentPosition < 12_000L
            if (making != next.id && !forJustStarted) { makingJob?.cancel(); makingJob = null; making = -1 }
            return
        }
        if (Stems.progress.value != null) return
        if (speaking?.isActive == true) return
        // test 354 (Jarrod): let the new track & Crossfader handoff settle cleanly for 6 s before spawning Zonos TTS
        if (p.currentPosition < 6_000L && left > 45_000L) return
        if (making == next.id || failedFor == next.id || !ready(ctx)) return
        making = next.id
        val targetId = next.id
        val app = ctx.applicationContext
        makingJob = scope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val t0 = System.currentTimeMillis()
            try {
                val line = line(app, cur, next)
                if (making != targetId) return@launch
                android.util.Log.w("OpenWaves", "announcer: line ready in ${System.currentTimeMillis() - t0} ms: $line")
                val (audio, rate) = speech(app, line) { !on(app) || making != targetId }
                if (making != targetId) return@launch
                if (audio.isEmpty()) throw IllegalStateException("no sound")
                ready = Ready(targetId, audio, rate)
                android.util.Log.w("OpenWaves", "announcer: voice ready in ${System.currentTimeMillis() - t0} ms (${(audio.size * 100L) / rate} cs, rate=$rate)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failedFor = targetId
                android.util.Log.w("OpenWaves", "announcer couldn't make a line", e)
            } finally {
                if (making == targetId) making = -1
            }
        }
    }

    private val rng = java.util.Random()
    private val recentPicks = ArrayDeque<Int>()
    private val recentMiddles = ArrayDeque<String>()
    private val recentIntros = ArrayDeque<Int>()
    private val recentAngles = ArrayDeque<Int>()
    private val recentLines = ArrayDeque<String>()
    private var sinceStationId = 2

    private fun pickFresh(size: Int, history: ArrayDeque<Int>): Int {
        if (size <= 1) return 0
        val keep = (size * 3 / 4).coerceIn(1, 18)
        var idx = rng.nextInt(size)
        for (tries in 0 until 20) {
            if (idx !in history) break
            idx = rng.nextInt(size)
        }
        history.addLast(idx)
        while (history.size > keep) history.removeFirst()
        return idx
    }

    private fun pickFreshTagged(items: List<Pair<String, String>>, history: ArrayDeque<String>): String {
        if (items.size <= 1) return items.firstOrNull()?.second.orEmpty()
        val keep = (items.size * 3 / 4).coerceIn(1, 14)
        var pick = items[rng.nextInt(items.size)]
        for (tries in 0 until 20) {
            if (pick.first !in history) break
            pick = items[rng.nextInt(items.size)]
        }
        history.addLast(pick.first)
        while (history.size > keep) history.removeFirst()
        return pick.second
    }

    /** What the DJ says. Uses Instant built-in lines, Quick AI (1.1 GB), or Better AI (2.5 GB) with accuracy checks. */
    suspend fun line(ctx: Context, cur: Song?, next: Song): String {
        val mode = aiMode(ctx)
        val cleanGenre = next.genre.split(',', '/', ';').map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(2).joinToString(", ")
        val pop = Popularity.known(ctx, next)
        val histChart = pop?.let {
            val spots = listOfNotNull(
                it.us.takeIf { n -> n > 0 }?.let { n -> "#$n in the US" },
                it.au.takeIf { n -> n > 0 }?.let { n -> "#$n in Australia" },
                it.uk.takeIf { n -> n > 0 }?.let { n -> "#$n in the UK" },
            )
            if (spots.isEmpty()) null
            else {
                val yr = it.year.takeIf { y -> y > 0 } ?: next.year.takeIf { y -> y > 0 }
                val whenStr = if (yr != null) " back in $yr" else " when it was originally released"
                "historical chart peak (past only): originally reached ${spots.joinToString(" and ")}$whenStr"
            }
        }
        val facts = listOfNotNull(
            next.year.takeIf { it > 0 }?.let { "released in $it" },
            histChart,
            cleanGenre.takeIf { it.isNotBlank() }?.let { "genre: $it" },
        ).joinToString("; ")
        val styleIdx = style(ctx)
        val lenIdx = length(ctx)
        val who = STYLES.getOrElse(styleIdx) { STYLES[0] }.second
        // Drop the station name ("You're listening to Open Waves") naturally every ~3-5 songs, never back-to-back
        val mentionStation = (sinceStationId >= 3 && rng.nextInt(3) == 0) || sinceStationId >= 5
        sinceStationId = if (mentionStation) 0 else sinceStationId + 1

        val stationRule = if (mentionStation)
            "Naturally mention the station name 'Open Waves' once (e.g. 'You're listening to Open Waves')."
        else
            "Do NOT say 'Open Waves' this time."

        val shortExamples = when (styleIdx) {
            1 -> listOf(
                "Keep the energy loud — up next, Thunderstruck by AC/DC!",
                "Turn this one right up, here comes Africa by Toto!",
                "No slowing down now, dropping Billie Jean by Michael Jackson!",
                "Big tune incoming — get ready for Dreams by Fleetwood Mac!",
            )
            2 -> listOf(
                "Easy does it tonight — drifting into Dreams by Fleetwood Mac.",
                "Kick back and unwind with Hotel California by Eagles.",
                "Keeping the night smooth, coming up is Africa by Toto.",
                "Take a breather and enjoy this one, Dreams by Fleetwood Mac.",
            )
            3 -> listOf(
                "Zero commercials, just great taste — up next, Africa by Toto!",
                "If you don't sing along, I'm judging you — here's Dreams by Fleetwood Mac!",
                "No skip button allowed on this one, here comes Thunderstruck by AC/DC!",
                "Scientifically proven to lift your mood — Billie Jean by Michael Jackson!",
            )
            else -> listOf(
                "Hope your day's going great — up next, Dreams by Fleetwood Mac.",
                "Loving this playlist flow! Rolling straight into Africa by Toto.",
                "Here's a classic you can't skip, Hotel California by Eagles.",
                "Keeping the good tunes moving with Billie Jean by Michael Jackson!",
            )
        }
        val medExamples = when (styleIdx) {
            1 -> listOf(
                "Keeping the energy sky high — crank it up because here comes Thunderstruck by AC/DC!",
                "No slowing down now! Get ready to turn the volume up for Africa by Toto!",
                "That last one hit hard, and we're keeping the momentum rolling with Thunderstruck by AC/DC!",
            )
            2 -> listOf(
                "Easy does it tonight. Let that last track drift away as we ease into Dreams by Fleetwood Mac.",
                "Kick back, breathe out, and let this next one wash over you — here's Dreams by Fleetwood Mac.",
                "Keeping the night smooth and unhurried. Rolling gently into Hotel California by Eagles.",
            )
            3 -> listOf(
                "Zero commercials, zero traffic updates — just great taste. Coming up, Africa by Toto!",
                "If you don't hum along to this next one, I'm quietly judging you. Here's Dreams by Fleetwood Mac!",
                "Your streaming algorithm could never put together a run like this. Up next, Africa by Toto!",
            )
            else -> listOf(
                "Hope your day's treating you well! Rolling smoothly into a great one — here's Dreams by Fleetwood Mac.",
                "Nothing beats your own music flowing track to track. Coming up next, Africa by Toto!",
                "Loving this mix so far! Keeping the good vibes moving with Hotel California by Eagles.",
            )
        }
        val longExamples = when (styleIdx) {
            1 -> listOf(
                "Oh yeah, we are not slowing down for a second! Hope you've got the volume cranked up because this next track is an absolute classic. Get ready to turn it up for Thunderstruck by AC/DC!",
                "What a run of tunes we've got going right now! If you're driving, working, or just vibing out, this next one's going to lift the roof right off. Dropping Thunderstruck by AC/DC!",
            )
            2 -> listOf(
                "Hope your night is treating you right out there. Take a deep breath, kick back, and let the late-night vibe carry you along. Drifting smoothly into our next track, here's Dreams by Fleetwood Mac.",
                "There's nothing better than letting the night slow down with the right soundtrack. Letting that last one fade gently into the quiet, here comes Hotel California by Eagles.",
            )
            3 -> listOf(
                "Zero commercials, zero traffic updates, and zero weird algorithm guesses — just your own great taste right here. If you don't sing along to this next one, I'm definitely judging you. Coming up, Africa by Toto!",
                "Still no ads, still no weather report, and honestly you're better off without me reading the news anyway. Let's stick to what matters — up next, Dreams by Fleetwood Mac!",
            )
            else -> listOf(
                "Hope you're having an awesome day and enjoying the tunes! Nothing beats your own playlist rolling smoothly from one great track to the next. Coming up right now, here's Dreams by Fleetwood Mac.",
                "So good to have you along for the ride today! That last track set the tone nicely, and we've got another beauty lined up right behind it. Here comes Africa by Toto.",
            )
        }

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "late night"
        }
        val angles = listOf(
            "Open with a quick $timeOfDay radio check-in (e.g. driving, working, or cruising through the $timeOfDay).",
            "Playfully contrast the style or mood of the previous track with the next one.",
            "Give a short, genuine reaction to the song that just wrapped up before introducing the next.",
            "Tease the next artist's signature sound or vibe first, then land on the song title.",
            "Drop a quick, dry one-liner or playful dare (like trying not to tap the steering wheel) before cueing the track.",
            "Start with a crisp rhetorical question to the listener, then roll into the song.",
            "Keep it ultra-casual and conversational, like chatting live from the studio booth.",
            "Give a nostalgic throwback nod to the release era/year (if given) before introducing the song.",
            "Build a smooth, rhythmic bridge straight from the previous title into the next title.",
            "Give a quick shout-out to how well this playlist is flowing right now.",
            "Start straight with the energy, beat, or hook of the upcoming song — punchy, no preamble.",
            "Share a brief, witty observation about the song's title or theme before naming the artist.",
            "Use a classic FM-radio segue feel — warm, confident, and effortless.",
            "Pretend you just spotted this track coming up on the deck and got genuinely stoked for it.",
            "Keep the opener super unexpected and fresh — start mid-thought or with a fun exclamation.",
            "Give a light, understated nod to the listener's taste before letting the next track take over.",
        )
        val angle = angles[pickFresh(angles.size, recentAngles)]

        val (lenSpec, maxTok, maxChars, example, extraRule) = when (lenIdx) {
            0 -> {
                val ex = shortExamples[rng.nextInt(shortExamples.size)]
                val exWithStation = if (mentionStation) "You're listening to Open Waves — ${ex.replaceFirstChar { it.lowercase() }}" else ex
                listOf(
                    "ONE brief, punchy radio-DJ sentence (10 to 16 words total, ~5 to 7 seconds spoken)",
                    48, 165, exWithStation,
                    "Include a very brief piece of radio DJ commentary matching your style before naming the next song and artist — never just read the song title alone. Mention ONLY this single song and artist (never mention the previous song or any other song)."
                )
            }
            2 -> {
                val ex = longExamples[rng.nextInt(longExamples.size)]
                listOf(
                    "2 to 3 lively sentences (34 to 46 words total, ~15 to 18 seconds spoken)",
                    115, 360, ex,
                    "$angle Mention the song that just finished if given, then smoothly introduce the exact next song title and artist."
                )
            }
            else -> {
                val ex = medExamples[rng.nextInt(medExamples.size)]
                listOf(
                    "1 to 2 natural sentences (18 to 28 words total, ~8 to 11 seconds spoken)",
                    68, 225, ex,
                    "$angle Smoothly introduce the exact next song title and artist."
                )
            }
        }
        val avoidRecent = if (recentLines.isEmpty()) "" else
            " Recent lines you ALREADY said (DO NOT reuse their opening, structure, jokes, or phrasing): " +
            recentLines.joinToString(" | ") { "\"${it.take(85)}\"" } + "."
        // Only show a verbatim example to Quick AI (1.5B); Better AI (4B) copies verbatim examples word-for-word!
        val exampleHint = if (mode == 2) "" else " Example: \"${example as String}\""
        val system = "You are $who. Current local time is $timeOfDay. Reply with ONLY what you say out loud: ${lenSpec as String}. " +
            "${extraRule as String} $stationRule " +
            "Be original every single time — avoid clichés like 'switch gears', 'dive into', 'pure vibes', 'no commercials', 'no ads', or 'no weather report'.$avoidRecent " +
            "If you mention a chart peak or release year, ALWAYS refer to it in the past tense as historical (from when it originally came out). " +
            "NEVER call any song 'latest', 'brand new', 'new hit', 'new single', 'just released', 'currently charting', or 'back in the charts'. " +
            "Never invent another song or artist that wasn't given to you. " +
            "No markdown asterisks, no emojis, no URLs, no websites, no links, no stage directions. Never invent facts.$exampleHint"
        val prompt = buildString {
            if (cur != null && lenIdx > 0) append("Just played: \"${cur.title}\" by ${cur.artist}. ")
            append("Next song: \"${next.title}\" by ${next.artist}")
            if (facts.isNotEmpty() && lenIdx > 0) append(" ($facts)")
            append(if (lenIdx == 0) ". Give a brief 1-sentence radio DJ link with a tiny bit of commentary into the next song." else ". Say a fresh radio DJ link into the next song.")
        }
        val modelId = when (mode) {
            1 -> helium314.keyboard.latin.WritingTools.QUICK.id
            2 -> helium314.keyboard.latin.WritingTools.BETTER.id
            else -> null
        }
        val titleWord = next.title.split(Regex("[^A-Za-z0-9]+")).firstOrNull { it.length >= 3 }?.lowercase()
        val artistWord = next.artist.split(Regex("[^A-Za-z0-9]+")).firstOrNull { it.length >= 3 }?.lowercase()
        val written = runCatching {
            if (modelId == null) null
            else withTimeoutOrNull(45_000) { helium314.keyboard.latin.WriterClient.ask(ctx, system, prompt, maxTok as Int, modelId = modelId) }
        }.getOrNull()
            ?.replace(Regex("(?s)<think>.*?</think>"), "")?.replace("*", "")?.trim()
            ?.let { raw ->
                // Strip wrapping quotes ONLY if the entire response is quoted (never strip the opening quote of a song title!)
                if (raw.length >= 2 && ((raw.startsWith('"') && raw.endsWith('"')) || (raw.startsWith('“') && raw.endsWith('”'))))
                    raw.substring(1, raw.length - 1).trim()
                else raw
            }
            ?.let { raw ->
                // if maxTok cut mid-sentence, trim cleanly back to the last sentence ending
                if (raw.isNotEmpty() && raw.last() in ".!?") raw
                else {
                    val cut = raw.lastIndexOfAny(charArrayOf('.', '!', '?'))
                    if (cut >= 18) raw.substring(0, cut + 1).trim() else raw
                }
            }
            ?.takeIf { out ->
                val low = out.lowercase()
                val words = out.split(Regex("\\s+")).size
                val byCount = Regex("\\bby\\s+").findAll(low).count()
                // Reject hallucinated second songs on Short mode (e.g. "Song A by X. Next up, Song B by Y.")
                val hallucinatedSecondSong = (lenIdx == 0 && byCount > 1) ||
                    (lenIdx > 0 && cur == null && byCount > 1) ||
                    (lenIdx > 0 && byCount > 2)
                val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val fakeNew = (next.year <= 0 || next.year < thisYear) && listOf(
                    "latest hit", "latest single", "latest track", "latest release", "latest song",
                    "brand new", "new hit", "new single", "just released", "just dropped",
                    "back in the charts", "currently charting", "climbing the charts", "topping the charts"
                ).any { it in low }
                out.length in 18..(maxChars as Int) && words >= 7 && !hallucinatedSecondSong && !fakeNew &&
                    "http" !in low && ".fm" !in low && ".com" !in low && "check the link" !in low &&
                    ((titleWord == null || titleWord in low) || (artistWord == null || artistWord in low))
            }
        if (written != null) {
            recentLines.addLast(written)
            while (recentLines.size > 4) recentLines.removeFirst()
            return written
        }
        // test 350 (Jarrod): 5-hour zero-repeat persistent Fisher-Yates shuffle-bag engine for Instant mode & AI fallback
        return DjScriptBank.build(ctx, styleIdx, lenIdx, cur, next, mentionStation, timeOfDay)
    }

    private fun builtInLine(ctx: Context, styleIdx: Int, lenIdx: Int, cur: Song?, next: Song, mentionStation: Boolean, timeOfDay: String): String {
        val t = next.title
        val a = next.artist
        val yr = if (next.year > 0) " from ${next.year}" else ""
        val prevA = cur?.artist?.takeIf { it.isNotBlank() && it != "Unknown artist" }
        val prevT = cur?.title?.takeIf { it.isNotBlank() }
        val pop = Popularity.known(ctx, next)
        val chartFact = pop?.let {
            val spot = listOfNotNull(
                it.au.takeIf { n -> n > 0 }?.let { n -> "number $n in Australia" },
                it.us.takeIf { n -> n > 0 }?.let { n -> "number $n in the US" },
                it.uk.takeIf { n -> n > 0 }?.let { n -> "number $n in the UK" },
            ).firstOrNull()
            val y = it.year.takeIf { v -> v > 0 } ?: next.year.takeIf { v -> v > 0 }
            when {
                spot != null && y != null -> "That one originally reached $spot back in $y."
                spot != null -> "That one originally hit $spot when it came out."
                else -> null
            }
        }
        val stSentence = if (mentionStation) listOf(
            "You're listening to Open Waves.",
            "Right here on Open Waves.",
            "This is Open Waves.",
            "Welcome back to Open Waves.",
        )[rng.nextInt(4)] else ""
        val stPrefix = if (stSentence.isNotEmpty()) "$stSentence " else ""

        // 1. Short (~10-16 words / ~5-7 s): brief style-matched commentary + next song only
        if (lenIdx == 0) {
            val shortPool = when (styleIdx) {
                1 -> listOf(
                    "Keep the energy loud — up next, $t by $a!",
                    "Turn the volume right up, here comes $t by $a!",
                    "No slowing down now — dropping $t by $a!",
                    "Big tune incoming, get ready for $t by $a!",
                    "Hands up for this one — here's $a with $t!",
                    "Wake up the speakers — rolling straight into $t by $a!",
                    "Momentum's sky high right now, cueing up $t by $a!",
                    "Time to crank the bass — coming up, $t by $a!",
                    "Oh yeah, this one always hits — $t by $a!",
                    "Let's keep the pace flying with $a and $t!",
                    "Don't touch that dial — here comes $t by $a!",
                    "Here's a proper crowd-pleaser — $t by $a!",
                )
                2 -> listOf(
                    "Easy does it this $timeOfDay — drifting into $t by $a.",
                    "Kick back and unwind with $t by $a.",
                    "Keeping things smooth and unhurried — up next, $t by $a.",
                    "Take a breather and enjoy this one, $t by $a.",
                    "Dim the lights and let this one carry you — $t by $a.",
                    "Smooth, mellow, and right on cue — here's $a with $t.",
                    "Just good music, no rush — rolling into $t by $a.",
                    "Easing gently into our next track, $t by $a.",
                    "Sink into the groove with $a and $t.",
                    "Quiet moments deserve a great soundtrack — here's $t by $a.",
                    "Letting the melody take over now with $t by $a.",
                    "Soft, steady, and easy on the ears — $t by $a.",
                )
                3 -> listOf(
                    "Zero commercials, just great taste — up next, $t by $a!",
                    "If you don't hum along, I'm quietly judging you — here's $t by $a!",
                    "No skip button allowed on this one — here comes $t by $a!",
                    "Scientifically proven to lift your mood, probably — $t by $a!",
                    "Your algorithm could never pick this well — up next, $t by $a!",
                    "Officially the best excuse to turn it up — $a with $t!",
                    "Your playlist is doing all the heavy lifting today — $t by $a!",
                    "No traffic report, no weather, just $t by $a!",
                    "Not taking requests, I'm already playing $t by $a!",
                    "Good news: you have great taste. Proof: $t by $a!",
                    "Try not to tap the steering wheel to $t by $a!",
                    "Zero awkward morning-show banter, just $t by $a!",
                )
                else -> listOf(
                    "Hope your $timeOfDay is going great — up next, $t by $a.",
                    "Loving how this playlist flows — rolling into $t by $a.",
                    "Here's a great one coming up now — $t by $a.",
                    "Can't go wrong with this track — next up, $a with $t.",
                    "Keeping the good tunes moving with $t by $a.",
                    "Always a pleasure spinning this one — here's $t by $a.",
                    "Hope this next track puts a smile on your face — $t by $a.",
                    "Great song on deck right now — this is $t by $a.",
                    "Smoothly into the next tune, cueing up $t by $a.",
                    "Right on cue for your $timeOfDay — over to $a with $t.",
                    "Nothing beats your own music — bringing in $t by $a.",
                    "One great track right into the next — $t by $a.",
                )
            }
            return stPrefix + shortPool[pickFresh(shortPool.size, recentPicks)]
        }

        // 2. Composable building blocks for Medium (~22-32 words / ~10-13 s) and Long (~38-52 words / ~15-20 s)
        val openers = when (styleIdx) {
            1 -> listOf(
                "Oh yeah, we are not slowing down for a second this $timeOfDay!",
                "Hope you've got the volume cranked up because this mix is on fire!",
                "What a run of tunes we've got rolling right now!",
                "Keeping the energy sky high for your $timeOfDay drive!",
                "If you're cruising, working, or just vibing out, turn those speakers up!",
                "No brakes on this playlist — every track is hitting just right!",
                "Hands on the wheel and volume on high, because we're keeping the party moving!",
                "That bassline energy isn't going anywhere anytime soon!",
                "Big soundtrack energy coming through loud and clear this $timeOfDay!",
                "Every single track on this list has been an absolute winner so far!",
                "Let's keep the adrenaline pumping with another massive favourite!",
                "You've built a seriously unstoppable playlist here!",
                "Speakers warmed up and ready? Because we're only getting louder from here!",
                "Zero dull moments on this deck — pure momentum track after track!",
                "If you needed a boost this $timeOfDay, this playlist has you completely covered!",
                "Nothing wakes up the cabin like a back-to-back run of heavy hitters!",
                "We're firing on all cylinders right now — don't even think about turning it down!",
                "This is the kind of tracklist that makes the miles fly right by!",
                "Energy levels are through the roof today and we're keeping it right there!",
                "No fillers, no slowdowns — just wall-to-wall anthems all $timeOfDay!",
            )
            2 -> listOf(
                "Hope this $timeOfDay is treating you gently out there.",
                "Take a deep breath, kick back, and let the music carry you along.",
                "There's nothing better than letting the pace slow down with the right soundtrack.",
                "Easy listening all the way — no rush, no noise, just great songs.",
                "Keeping the mood smooth, warm, and effortless for you.",
                "Let everything else fade into the background for a few minutes.",
                "Just good music flowing quietly from one favourite to the next.",
                "Sometimes all you need is a steady groove and a little peace and quiet.",
                "Settling right into the pocket with another laid-back classic.",
                "Low-key, unhurried, and exactly the right energy for this $timeOfDay.",
                "Letting the rhythm breathe and taking our time with every track.",
                "Smooth sailing all the way through your collection.",
                "No hurry at all — just soft lights, clear roads, and good melodies.",
                "Let your shoulders drop and enjoy a few calm minutes with this next one.",
                "The kind of effortless flow that makes you forget to check the clock.",
                "Keeping the atmosphere relaxed and easy on the ears.",
                "Every song has its own space to shine when the mix moves this smoothly.",
                "A quiet little pocket of great music right in the middle of your $timeOfDay.",
                "Warm tones, steady beat, and zero distractions.",
                "Just leaning back and letting your library do its thing.",
            )
            3 -> listOf(
                "Still zero commercials, zero traffic updates, and zero awkward radio jingles.",
                "Your streaming algorithm could spend a decade trying and still never build a run like this.",
                "I'd read you the weather forecast, but honestly you're way better off just listening to the music.",
                "Good news: your music taste is officially undefeated this $timeOfDay.",
                "No callers, no prize giveaways, and no annoying airhorns — just your own bangers.",
                "Scientifically proven to make any $timeOfDay at least forty percent better — probably.",
                "Your playlist is doing all the heavy lifting right now and I'm just here to take the credit.",
                "Fair warning: if you don't at least mouth the chorus on this next one, I am quietly judging you.",
                "No, I'm not taking requests — mostly because you already picked a better song than I could.",
                "Best radio station on your phone, and the studio budget is literally zero dollars.",
                "Try not to tap the steering wheel or nod your head on this next track — impossible challenge.",
                "Who needs a morning show crew laughing at their own jokes when you've got tunes like these?",
                "Quick station announcement: there is no announcement, I just really wanted to play this next song.",
                "If anyone in traffic is wondering why you're grinning, blame this playlist.",
                "I checked the rules and it's actually illegal to listen to this next one at low volume.",
                "Not a single sponsored mattress ad in sight — just wall-to-wall favourites.",
                "Honestly, whoever put this music library together deserves a raise.",
                "No fake radio drama, no prank calls — just pure, unfiltered good taste.",
                "Consider this your official permission slip to turn the stereo up two more clicks.",
                "Breaking news: this next song is still way better than anything on regular FM radio.",
            )
            else -> listOf(
                "Hope you're having an awesome $timeOfDay and enjoying the tunes!",
                "So good to have you along for the ride this $timeOfDay.",
                "Nothing beats your own music collection rolling smoothly from one great song to the next.",
                "Loving the flow of this playlist — every track has been landing right on the mark.",
                "Wherever you're headed this $timeOfDay, you've got a cracking soundtrack for it.",
                "Thanks for hanging out and letting the good songs roll.",
                "Always a pleasure spinning through your favourites with you.",
                "That's the beauty of your own library — zero filler, just the songs you actually love.",
                "Keeping the good company and great melodies going strong this $timeOfDay.",
                "One great track wraps up and another favourite is already lined up on the deck.",
                "Hope the road ahead is smooth and the speakers are sounding great.",
                "You really can't beat a solid run of classics to keep the $timeOfDay moving nicely.",
                "Great mix of tracks coming through today — really enjoying this session.",
                "Just the right soundtrack to make a busy $timeOfDay feel a whole lot easier.",
                "Every time you think this playlist can't top the last song, another gem shows up.",
                "Glad to be riding shotgun with your music this $timeOfDay.",
                "Nothing fancy, just great songs back to back the way radio should be.",
                "Here's to a smooth $timeOfDay and plenty more classics waiting in the queue.",
                "It's always a good time when your favourite artists keep showing up one after another.",
                "Keeping the tunes fresh and the vibes steady right through the $timeOfDay.",
            )
        }

        val genreTag = next.genre.split(',', '/', ';').firstOrNull { it.isNotBlank() }?.trim()
        val middles = buildList<Pair<String, String>> {
            if (prevA != null && prevT != null) {
                add("prev1" to "That was $prevT by $prevA setting the tone nicely.")
                add("prev2" to "We just wrapped up $prevA with $prevT, and the next pick fits right in.")
                add("prev3" to "Letting $prevT by $prevA fade out smoothly behind us.")
                add("prev4" to "$prevA did a great job with $prevT, and we're keeping that momentum alive.")
                add("prev5" to "Coming off $prevT by $prevA, we've got another killer lined up.")
                add("prev6" to "Nice finish there from $prevA — now let's keep the flow moving.")
            } else if (prevA != null) {
                add("preva1" to "That last one from $prevA set the vibe nicely.")
                add("preva2" to "Letting $prevA fade out smoothly as we cue up the next deck.")
            }
            if (chartFact != null) {
                add("chart1" to chartFact)
                add("chart2" to "Fun bit of history on this next one: ${chartFact.replaceFirstChar { it.lowercase() }}")
            }
            if (next.year > 0) {
                add("yr1" to "We're taking a quick trip back to ${next.year} for this next one.")
                add("yr2" to "This one's been a favourite ever since it first came out in ${next.year}.")
                add("yr3" to "Throwing it back to ${next.year} with a track that still holds up today.")
                add("yr4" to "Straight out of ${next.year}, and it still sounds just as sharp.")
            }
            if (genreTag != null && genreTag.length in 3..22) {
                add("gen1" to "A proper slice of $genreTag ready to roll next.")
                add("gen2" to "Keeping that $genreTag energy alive on the deck.")
            }
            add("gen_a" to "It's the kind of track you hear the first two seconds of and immediately recognise.")
            add("gen_b" to "Always hits just as good as the very first time you heard it.")
            add("gen_c" to "Definitely one of those songs that deserves a little extra volume.")
            add("gen_d" to "Whether you're singing along or just cruising, this one never misses.")
            add("gen_e" to "One of those unmistakable riffs that grabs your attention straight away.")
            add("gen_f" to "Hard to beat a track with this much character.")
            add("gen_g" to "Always a welcome surprise when this one pops up in the queue.")
            add("gen_h" to "A real standout in your collection — glad it landed in this mix.")
        }

        val opener = openers[pickFresh(openers.size, recentPicks)]
        val middle = if (lenIdx == 2) pickFreshTagged(middles, recentMiddles) else ""
        // Never repeat the release year if the middle beat already mentioned it
        val yrInMiddle = next.year > 0 && next.year.toString() in middle
        val yrTail = if (yrInMiddle) "" else yr

        val intros = when (styleIdx) {
            1 -> listOf(
                "Get ready to turn it up for $t by $a$yrTail!",
                "Dropping $t by $a$yrTail right now — let's go!",
                "Crank the speakers, here comes $t by $a$yrTail!",
                "Coming in hot right now, it's $a with $t$yrTail!",
                "No holding back — here's $t by $a$yrTail!",
                "Turning it all the way up for $t by $a$yrTail!",
                "Hands up for $a and $t$yrTail!",
                "Letting $t by $a$yrTail rip right now!",
                "Brace yourself — next up is $t by $a$yrTail!",
                "Bringing the roof down with $a and $t$yrTail!",
                "Volume knob to the right for $t by $a$yrTail!",
                "Hitting the gas with $t by $a$yrTail!",
            )
            2 -> listOf(
                "Drifting smoothly into our next track, here's $t by $a$yrTail.",
                "Easing right into $t by $a$yrTail.",
                "Rolling gently into the next one — $a with $t$yrTail.",
                "Settle in as we bring up $t by $a$yrTail.",
                "Letting this next one take over — here's $t by $a$yrTail.",
                "Floating quietly into $t by $a$yrTail.",
                "Taking it nice and easy with $a and $t$yrTail.",
                "Gliding straight into $t by $a$yrTail.",
                "Here's $t by $a$yrTail to keep the mood right.",
                "Letting $a take the wheel now with $t$yrTail.",
                "Slowing down for a classic — $t by $a$yrTail.",
                "Softly bringing in $t by $a$yrTail.",
            )
            3 -> listOf(
                "Coming up right now, $t by $a$yrTail!",
                "Up next on your unofficial favourite station, $t by $a$yrTail.",
                "Cueing up $a with $t$yrTail — enjoy!",
                "Here comes $t by $a$yrTail, no skipping allowed.",
                "Rolling straight into $t by $a$yrTail!",
                "Over to $a and $t$yrTail.",
                "Handing the mic straight to $a with $t$yrTail.",
                "Next on the deck: $t by $a$yrTail — you're welcome.",
                "Let's see if you still remember every word to $t by $a$yrTail.",
                "Spinning up $t by $a$yrTail right now!",
                "Time for $a to steal the show with $t$yrTail.",
                "Dropping $t by $a$yrTail — go ahead and sing along.",
            )
            else -> listOf(
                "Coming up right now, here's $t by $a$yrTail.",
                "Rolling smoothly into $t by $a$yrTail.",
                "Next up on the deck, it's $a with $t$yrTail.",
                "Cueing up a great one for you — $t by $a$yrTail.",
                "Here comes $t by $a$yrTail.",
                "Let's jump right into $t by $a$yrTail.",
                "Bringing in $a with $t$yrTail.",
                "Up next for your $timeOfDay, $t by $a$yrTail.",
                "Keeping things moving nicely with $t by $a$yrTail.",
                "Sliding straight into $a and $t$yrTail.",
                "Here's a favourite — $t by $a$yrTail.",
                "Over to $a now with $t$yrTail.",
            )
        }

        val intro = intros[pickFresh(intros.size, recentIntros)]
        return if (lenIdx == 2) {
            // Long (~38-52 words / ~15-20 s): vary sentence structure & station ID placement so cadence stays fresh
            val parts = when (rng.nextInt(3)) {
                0 -> listOf(stSentence, opener, middle, intro)
                1 -> listOf(opener, stSentence, middle, intro)
                else -> listOf(middle, opener, stSentence, intro)
            }
            parts.filter { it.isNotBlank() }.joinToString(" ")
        } else {
            // Medium (~22-32 words / ~10-13 s): opener + intro (occasionally station ID in the middle)
            val parts = if (rng.nextBoolean()) listOf(stSentence, opener, intro) else listOf(opener, stSentence, intro)
            parts.filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    /** Called by the music service when a new song starts. */
    fun songStarted(ctx: Context, id: Long?) {
        if (id == spokenFor) spokenFor = -1
        if (!on(ctx)) return
        songsSince++
        // skipped before the DJ could talk: that line is old now
        ready?.let { if (it.forId == id && speaking?.isActive != true) ready = null }
    }

    /** Smoothly ramps Eq.duck from its current value to [target] over [ms] ms. */
    private suspend fun fadeDuck(target: Float, ms: Long) {
        val start = Eq.duck
        val steps = (ms / 40L).toInt().coerceAtLeast(1)
        for (s in 1..steps) {
            Eq.duck = start + (target - start) * (s.toFloat() / steps)
            kotlinx.coroutines.delay(40)
        }
        Eq.duck = target
    }

    /** Plays it now (also the settings' "Hear a sample"). Smoothly fades the music down under the DJ voice, then fades back up. */
    fun say(audio: FloatArray, rate: Int = helium314.keyboard.tts.ZonosTts.RATE) {
        hush()
        speaking = scope.launch(Dispatchers.IO) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = runCatching { AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build() }.getOrNull() ?: return@launch
            track = t
            try {
                // test 319 (Jarrod): smooth fade-out down to 10% so Savannah is crystal clear over the music
                fadeDuck(0.10f, 700)
                t.play()
                var i = 0
                val chunk = rate / 10
                while (i < audio.size && kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != false && track === t) {
                    val n = minOf(chunk, audio.size - i)
                    if (t.write(audio, i, n, AudioTrack.WRITE_BLOCKING) < 0) break
                    i += n
                }
                // let the last bit play out
                val end = System.currentTimeMillis() + 1500
                while (track === t && t.playbackHeadPosition < audio.size && System.currentTimeMillis() < end) kotlinx.coroutines.delay(50)
                // test 317: smooth fade-in into the next song
                if (track === t) fadeDuck(1f, 1000)
            } catch (_: Exception) {
            } finally {
                Eq.duck = 1f
                runCatching { t.stop(); t.release() }
                if (track === t) track = null
            }
        }
    }

    /** Stop talking (paused, skipped, or turned off). */
    fun hush() {
        val t = track; track = null
        if (t != null) runCatching { t.pause(); t.flush() }
        speaking?.cancel(); speaking = null
        Eq.duck = 1f
    }

    fun sample(ctx: Context, lib: Library?, cur: Song?, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            val msg = runCatching {
                if (!ready(app)) return@runCatching if (voiceEngine(app) == 1) "First download the Heart (Kokoro) DJ voice above." else "First download the Savannah (Zonos) DJ voice above."
                val next = lib?.uniqueSongs?.filter { it.id != cur?.id }?.randomOrNull() ?: return@runCatching "No songs yet."
                val line = line(app, cur, next)
                val (audio, rate) = speech(app, line)
                say(audio, rate)
                "“$line”"
            }.getOrElse { "Couldn't make a sample: ${it.message}" }
            withContext(Dispatchers.Main) { done(msg) }
        }
    }
}

@Composable
internal fun AnnouncerSettings(ui: PlayerUi?) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Announcer.on(ctx)) }
    var askBatteryWarn by remember { mutableStateOf(false) }
    var dontShowAgain by remember { mutableStateOf(false) }
    var every by remember { mutableIntStateOf(Announcer.every(ctx)) }
    var style by remember { mutableIntStateOf(Announcer.style(ctx)) }
    var msgLen by remember { mutableIntStateOf(Announcer.length(ctx)) }
    var sample by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val dlScope = androidx.compose.runtime.rememberCoroutineScope()
    var voiceEng by remember { mutableIntStateOf(Announcer.voiceEngine(ctx)) }
    var zonosReady by remember { mutableStateOf(Announcer.zonosReady(ctx)) }
    var zonosDl by remember { mutableStateOf<Float?>(null) }
    var zonosErr by remember { mutableStateOf<String?>(null) }
    var kokoroReady by remember { mutableStateOf(Announcer.kokoroReady(ctx)) }
    var kokoroDl by remember { mutableStateOf<Float?>(null) }
    var kokoroErr by remember { mutableStateOf<String?>(null) }
    var aiMode by remember { mutableIntStateOf(Announcer.aiMode(ctx)) }
    var quickReady by remember { mutableStateOf(helium314.keyboard.latin.WritingTools.isReady(ctx, helium314.keyboard.latin.WritingTools.QUICK)) }
    var betterReady by remember { mutableStateOf(helium314.keyboard.latin.WritingTools.isReady(ctx, helium314.keyboard.latin.WritingTools.BETTER)) }
    var quickRunning by remember { mutableStateOf(helium314.keyboard.latin.WritingTools.downloadRunning(ctx, helium314.keyboard.latin.WritingTools.QUICK)) }
    var betterRunning by remember { mutableStateOf(helium314.keyboard.latin.WritingTools.downloadRunning(ctx, helium314.keyboard.latin.WritingTools.BETTER)) }
    @Composable fun chip(label: String, sel: Boolean, click: () -> Unit) =
        FilterChip(sel, click, { Text(label) }, Modifier.padding(end = 6.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.3f)))
    Column {
        Section("AI radio announcer")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📻 DJ between songs", fontSize = 16.sp)
                Text("A DJ introduces the next song while the music ducks underneath. All on your phone.",
                    fontSize = 12.sp, color = dim)
            }
            Switch(on, { wantOn ->
                if (wantOn && Announcer.showBatteryWarn(ctx)) {
                    dontShowAgain = false
                    askBatteryWarn = true
                } else {
                    on = wantOn
                    Announcer.set(ctx, "ann_on", wantOn)
                }
            }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (askBatteryWarn) {
            AlertDialog(
                onDismissRequest = { askBatteryWarn = false },
                title = { Text("DJ uses extra battery") },
                text = {
                    Column {
                        Text("Generating the DJ's voice and AI lines right on your phone uses noticeably more battery — even when plugged into a car charger.\n\nTip: Using 'Heart (Kokoro)' voice, 'Every 3rd', or 'Short' saves a lot of battery.")
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                            Text("Don't show this again", fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton({
                        if (dontShowAgain) Announcer.hideBatteryWarn(ctx)
                        askBatteryWarn = false
                        on = true
                        Announcer.set(ctx, "ann_on", true)
                    }) { Text("Turn on", color = Accent) }
                },
                dismissButton = { TextButton({ askBatteryWarn = false }) { Text("Cancel") } }
            )
        }
        if (on) {
            Text("DJ voice", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                chip("Savannah (Zonos)", voiceEng == 0) { voiceEng = 0; Announcer.set(ctx, "ann_voice_eng", 0) }
                chip("Heart (Kokoro · Low battery)", voiceEng == 1) { voiceEng = 1; Announcer.set(ctx, "ann_voice_eng", 1) }
            }
            Text(
                if (voiceEng == 1) "🔋 Low battery · Lightweight Kokoro Heart voice (~155 MB) — ~5× faster and ~80% less battery."
                else "🔋 Higher battery · Richest natural Zonos DJ voice (~1.1 GB).",
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = dim
            )
            // test 319/355 (Jarrod): direct download button for whichever DJ voice is selected
            if (voiceEng == 0 && !zonosReady && helium314.keyboard.tts.ZonosTts.supported(ctx)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Needs the Savannah DJ voice (~1.1 GB, one-time download).", fontSize = 12.sp, color = Accent)
                    if (zonosDl != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { zonosDl ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Accent)
                        Text("Downloading Savannah voice… ${((zonosDl ?: 0f) * 100).toInt()}%", fontSize = 12.sp, color = dim)
                    } else {
                        zonosErr?.let { Text(it, fontSize = 12.sp, color = Accent) }
                        OutlinedButton({
                            zonosDl = 0f; zonosErr = null
                            dlScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    helium314.keyboard.tts.ZonosTts.download(ctx, { false }) { p -> zonosDl = p }
                                }.onSuccess {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { zonosReady = Announcer.zonosReady(ctx); zonosDl = null }
                                }.onFailure { e ->
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { zonosErr = "Download failed: ${e.message}"; zonosDl = null }
                                }
                            }
                        }, Modifier.padding(top = 4.dp)) { Text("Download DJ voice (Savannah)", color = Accent) }
                    }
                }
            } else if (voiceEng == 1 && !kokoroReady) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Needs the Heart DJ voice (~155 MB, one-time download).", fontSize = 12.sp, color = Accent)
                    if (kokoroDl != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { kokoroDl ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Accent)
                        Text("Downloading Heart voice… ${((kokoroDl ?: 0f) * 100).toInt()}%", fontSize = 12.sp, color = dim)
                    } else {
                        kokoroErr?.let { Text(it, fontSize = 12.sp, color = Accent) }
                        OutlinedButton({
                            kokoroDl = 0f; kokoroErr = null
                            dlScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    helium314.keyboard.tts.KokoroTts.download(ctx, { false }) { p -> kokoroDl = p }
                                }.onSuccess {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { kokoroReady = Announcer.kokoroReady(ctx); kokoroDl = null }
                                }.onFailure { e ->
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { kokoroErr = "Download failed: ${e.message}"; kokoroDl = null }
                                }
                            }
                        }, Modifier.padding(top = 4.dp)) { Text("Download DJ voice (Heart · 155 MB)", color = Accent) }
                    }
                }
            }
            Text("How often", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                for ((n, label) in listOf(1 to "Every song", 2 to "Every 2nd", 3 to "Every 3rd", 5 to "Every 5th")) chip(label, every == n) { every = n; Announcer.set(ctx, "ann_every", n) }
            }
            Text("DJ writer", Modifier.padding(start = 16.dp, top = 6.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                chip("Instant", aiMode == 0) { aiMode = 0; Announcer.set(ctx, "ann_ai_mode", 0) }
                chip("Quick AI (1.1 GB)", aiMode == 1) { aiMode = 1; Announcer.set(ctx, "ann_ai_mode", 1) }
                chip("Better AI (2.5 GB)", aiMode == 2) { aiMode = 2; Announcer.set(ctx, "ann_ai_mode", 2) }
            }
            Text(when (aiMode) {
                1 -> "🔋 Medium battery · Small on-phone AI writes custom lines fast (~5–10 s) with accuracy checks."
                2 -> "🔋 High battery · Large on-phone AI writes the smartest, funniest DJ banter — best with 'Every 2nd' or 'Every 3rd' song (8 GB+ RAM)."
                else -> "🔋 Low battery · Built-in radio engine (1,000+ varied lines, 0 MB extra AI RAM, 100% accurate facts)."
            }, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = dim)
            if (aiMode == 1 && !quickReady) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (quickRunning) Text("Downloading Quick AI (1.1 GB)… check your notifications.", fontSize = 12.sp, color = Accent)
                    else OutlinedButton({
                        helium314.keyboard.latin.WritingTools.startDownload(ctx, helium314.keyboard.latin.WritingTools.QUICK)
                        quickRunning = true
                    }) { Text("Download Quick AI (1.1 GB)", color = Accent) }
                }
            } else if (aiMode == 2 && !betterReady) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (betterRunning) Text("Downloading Better AI (2.5 GB)… check your notifications.", fontSize = 12.sp, color = Accent)
                    else OutlinedButton({
                        helium314.keyboard.latin.WritingTools.startDownload(ctx, helium314.keyboard.latin.WritingTools.BETTER)
                        betterRunning = true
                    }) { Text("Download Better AI (2.5 GB)", color = Accent) }
                }
            }
            Text("Style", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                Announcer.STYLES.forEachIndexed { i, (label, _) -> chip(label, style == i) { style = i; Announcer.set(ctx, "ann_style", i) } }
            }
            Text("Message length", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                listOf("Short", "Medium", "Long").forEachIndexed { i, label ->
                    chip(label, msgLen == i) { msgLen = i; Announcer.set(ctx, "ann_len", i) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({
                    making = true; sample = null
                    val lib = Library.cached
                    val cur = ui?.controller?.currentMediaItem?.mediaId?.toLongOrNull()?.let { lib?.byId?.get(it) }
                    Announcer.sample(ctx, lib, cur) { sample = it; making = false }
                }, enabled = !making) { Text(if (making) "Writing…" else "Hear a sample") }
                Spacer(Modifier.width(8.dp))
            }
            sample?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim) }
            Text("The DJ talks over the last few seconds of a song and the fade into the next one. Each line is made while the song before it plays.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = dim)
        }
        Spacer(Modifier.width(1.dp))
    }
}

