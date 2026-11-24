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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
    AlertDialog(onDismissRequest = ::close,
        title = { Text("Lyrics: ${s.title}") },
        text = {
            Column {
                val t = text
                if (t == null) Text("Loading…")
                else {
                    OutlinedTextField(t, { text = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp), placeholder = { Text("Type or paste the lyrics, one line each") })
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // stamps the song's current time on the line where the text cursor would be: simplest is the next untimed line
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
                    }
                    Text("Play the song and tap ⏱ as each line starts to make the lyrics scroll in time.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton({
                text?.let { t ->
                    if (t.isBlank()) runCatching { File(ctx.filesDir, "music/lyrics/${s.id}.lrc").delete() } else Lyrics.save(ctx, s, t.trim())
                    LyricsEdit.version.intValue++
                }
                close()
            }, enabled = text != null) { Text("Save") }
        },
        dismissButton = { TextButton(::close) { Text("Cancel") } })
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
    /** test 327 (Jarrod): 0 = Instant (built-in radio lines), 1 = Quick AI (1.1 GB), 2 = Better AI (2.5 GB) */
    fun aiMode(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.contains("ann_ai_mode")) p.getInt("ann_ai_mode", 0).coerceIn(0, 2)
        else if (p.getBoolean("ann_ai2", false)) 1 else 0
    }
    fun set(ctx: Context, key: String, v: Any) {
        prefs(ctx).edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v.toString()) } }.apply()
        failedFor = -1
        ready = null
        makingJob?.cancel(); makingJob = null; making = -1
        if (key == "ann_on" && v is Boolean) {
            enabled.value = v
            if (v) songsSince = 1000
            else { hush(); scope.launch { runCatching { helium314.keyboard.tts.ZonosTts.stop() } } }
        }
    }

    val STYLES = listOf(
        "Friendly" to "a warm, friendly daytime radio DJ (casual, welcoming, like 'Hope you're enjoying the music!')",
        "Hyped" to "an excited, high-energy party radio DJ (punchy, upbeat, like 'Turn it up, here comes a banger!')",
        "Late night" to "a smooth, calm late-night radio DJ (relaxed, laid-back, like 'Settle in and unwind with this one...')",
        "Funny" to "a cheeky, witty radio DJ who cracks one quick light-hearted joke about the music or radio",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Ready(val forId: Long, val audio: FloatArray)
    @Volatile private var ready: Ready? = null
    @Volatile private var making: Long = -1
    @Volatile private var makingJob: Job? = null
    @Volatile private var speaking: Job? = null
    @Volatile private var track: AudioTrack? = null
    // test 316: the first song after turning it on gets a DJ line too
    private var songsSince = 1000
    /** test 324: song id we already started announcing so every==1 doesn't immediately re-generate it 1 s later */
    @Volatile private var spokenFor: Long = -1
    /** a song the line couldn't be made for: not tried again (test 316: it kept retrying, the phone got hot) */
    @Volatile private var failedFor: Long = -1
    /** fade-out ramp duration before Savannah begins speaking */
    private const val FADE_OUT_MS = 700L

    // test 317 (Jarrod): Savannah only — she sounds like a proper radio DJ
    private fun voice() = helium314.keyboard.tts.ZonosTts.VOICES.firstOrNull { it.id == "savannah" }
        ?: helium314.keyboard.tts.ZonosTts.VOICES[0]
    fun ready(ctx: Context) = helium314.keyboard.tts.ZonosTts.installed(ctx) && helium314.keyboard.tts.ZonosTts.supported(ctx)
    private val ttsLock = kotlinx.coroutines.sync.Mutex()
    /** The whole line as sound (44.1 kHz). Serialised so background prep and "Hear a sample" never overlap on ZonosTts. */
    private suspend fun speech(ctx: Context, text: String, cancel: () -> Boolean = { !on(ctx) }): FloatArray = ttsLock.withLock {
        val parts = ArrayList<FloatArray>()
        // test 316: the voice engine is only running while it makes the line, then it gives its memory back
        try { helium314.keyboard.tts.ZonosTts.speak(ctx, helium314.keyboard.tts.ChatSpeaker.forSpeech(text), voice(), null, cancel) { parts += it } }
        finally { helium314.keyboard.tts.ZonosTts.stop() }
        val out = FloatArray(parts.sumOf { it.size }); var i = 0
        for (p in parts) { System.arraycopy(p, 0, out, i, p.size); i += p.size }
        // test 323 (Jarrod): broadcast-style speech loudness (~20% louder than the music before it, like FM radio)
        // Normalise active speech RMS to ~0.28 (~ -11 dBFS, ~20% above -14 LUFS music) with smooth soft-limiting so it never clips
        var sumSq = 0.0; var active = 0
        for (v in out) { if (kotlin.math.abs(v) > 0.01f) { sumSq += v * v; active++ } }
        val rms = if (active > 0) kotlin.math.sqrt(sumSq / active).toFloat() else 0.1f
        val gain = (0.28f / rms.coerceAtLeast(0.03f)).coerceIn(1.2f, 4.5f)
        val norm = kotlin.math.tanh(1.15).toFloat()
        for (k in out.indices) out[k] = (kotlin.math.tanh((out[k] * gain * 1.15f).toDouble()).toFloat() / norm).coerceIn(-0.98f, 0.98f)
        out
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
            say(r.audio)
            return
        }
        // 2b. ready and near the end: start at 65% of clip duration (+ fade-out + crossfade offset) before song end
        // so ~65% plays over the finishing song's outro and ~35% lands over the new song's intro ("hitting the post")
        if (r != null && r.forId == next.id) {
            val clipMs = (r.audio.size * 1000L) / helium314.keyboard.tts.ZonosTts.RATE
            val blendOffset = if (MusicSettings.blendOn) (MusicSettings.blendSec * 750L).toLong() else 0L
            val startBeforeEndMs = ((clipMs * 65L) / 100L + FADE_OUT_MS + blendOffset).coerceIn(4_000L, 26_000L)
            if (left <= startBeforeEndMs && speaking?.isActive != true) {
                ready = null; songsSince = 0; spokenFor = next.id
                android.util.Log.w("OpenWaves", "announcer: talking into ${next.title} (clip=${clipMs}ms, blendOff=${blendOffset}ms, 65/35 trigger=${startBeforeEndMs}ms)")
                say(r.audio)
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
                val audio = speech(app, line) { !on(app) || making != targetId }
                if (making != targetId) return@launch
                if (audio.isEmpty()) throw IllegalStateException("no sound")
                ready = Ready(targetId, audio)
                android.util.Log.w("OpenWaves", "announcer: voice ready in ${System.currentTimeMillis() - t0} ms (${audio.size / 441} cs)")
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

    /** What the DJ says. Uses Instant built-in lines, Quick AI (1.1 GB), or Better AI (2.5 GB) with accuracy checks. */
    suspend fun line(ctx: Context, cur: Song?, next: Song): String {
        val mode = aiMode(ctx)
        val cleanGenre = next.genre.split(',', '/', ';').map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(2).joinToString(", ")
        val facts = listOfNotNull(
            next.year.takeIf { it > 0 }?.let { "from $it" },
            Popularity.known(ctx, next)?.story(),
            cleanGenre.takeIf { it.isNotBlank() }?.let { "genre: $it" },
        ).joinToString("; ")
        val styleIdx = style(ctx)
        val who = STYLES.getOrElse(styleIdx) { STYLES[0] }.second
        val example = when (styleIdx) {
            1 -> "Oh yeah, Open Waves, we are not slowing down for a second today! Hope you've got the volume cranked up because this next track is an absolute classic. Get ready to turn it up for Thunderstruck by AC/DC!"
            2 -> "Hope your night is treating you right out there on Open Waves. Take a deep breath, kick back, and let the late-night vibe carry you along. Drifting smoothly into our next track, here's Dreams by Fleetwood Mac."
            3 -> "Zero commercials, zero traffic updates, and zero weird algorithm guesses — just your own great taste right here on Open Waves. If you don't sing along to this next one, I'm definitely judging you. Coming up, Africa by Toto!"
            else -> "Hope you're having an awesome day and enjoying the tunes here on Open Waves! Nothing beats your own playlist rolling smoothly from one great track to the next. Coming up right now, here's Dreams by Fleetwood Mac."
        }
        val lenIdx = length(ctx)
        val (lenSpec, maxTok, maxChars) = when (lenIdx) {
            0 -> Triple("1 short, punchy sentence (10 to 16 words total, about 5 to 7 seconds spoken)", 45, 160)
            2 -> Triple("2 to 3 lively sentences (34 to 46 words total, about 15 to 18 seconds spoken)", 115, 360)
            else -> Triple("1 to 2 natural, lively sentences (20 to 30 words total, about 9 to 12 seconds spoken)", 75, 250)
        }
        val system = "You are $who on Open Waves. Reply with ONLY what you say out loud: $lenSpec. " +
            "Sound like a real radio DJ — open with banter matching your style (don't just read the song name!), mention the song that just finished if given, then smoothly introduce the exact next song title and artist. " +
            "No emojis, no URLs, no websites, no links, no stage directions. Never invent facts. " +
            "Example: \"$example\""
        val prompt = buildString {
            if (cur != null) append("Just played: \"${cur.title}\" by ${cur.artist}. ")
            append("Next song: \"${next.title}\" by ${next.artist}")
            if (facts.isNotEmpty()) append(" ($facts)")
            append(". Say a lively radio DJ link into the next song.")
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
            else withTimeoutOrNull(45_000) { helium314.keyboard.latin.WriterClient.ask(ctx, system, prompt, maxTok, modelId = modelId) }
        }.getOrNull()
            ?.replace(Regex("(?s)<think>.*?</think>"), "")?.replace(Regex("^[\"“]|[\"”]$"), "")?.trim()
            ?.let { raw ->
                // if maxTok cut mid-sentence, trim cleanly back to the last sentence ending
                if (raw.lastOrNull() in charArrayOf('.', '!', '?')) raw
                else {
                    val cut = raw.lastIndexOfAny(charArrayOf('.', '!', '?'))
                    if (cut >= 25) raw.substring(0, cut + 1).trim() else raw
                }
            }
            ?.takeIf { out ->
                val low = out.lowercase()
                out.length in 10..maxChars &&
                    "http" !in low && ".fm" !in low && ".com" !in low && "check the link" !in low &&
                    ((titleWord == null || titleWord in low) || (artistWord == null || artistWord in low))
            }
        if (written != null) return written
        // test 325 (Jarrod): distinct, brief real-radio DJ lines for each of the 4 styles
        return builtInLine(style(ctx), cur, next)
    }

    private var lastPick = -1
    private fun builtInLine(styleIdx: Int, cur: Song?, next: Song): String {
        val t = next.title
        val a = next.artist
        val yr = if (next.year > 0) " from ${next.year}" else ""
        val prev = cur?.artist?.takeIf { it.isNotBlank() && it != "Unknown artist" }
        val pool = when (styleIdx) {
            1 -> listOf( // Hyped
                "Turn it up! Dropping $t by $a$yr right now!",
                "Hands up, Open Waves — here comes $t by $a!",
                "No slowing down! Crank the volume for $t by $a$yr!",
                if (prev != null) "From $prev straight into a banger — $t by $a!" else "Let's keep the energy flying! Next up, $t by $a!",
                "Big tune incoming! Get ready for $t by $a$yr!",
                "Hope you're ready for this one — $t by $a, let's go!",
            )
            2 -> listOf( // Late night
                "Easy listening on Open Waves. Drifting into $t by $a$yr.",
                "Settle in and unwind. Up next, $t by $a.",
                "Keeping the night smooth for you. Here's $t by $a$yr.",
                "Late night vibes rolling on with $t by $a.",
                if (prev != null) "Letting $prev fade out gently. Next up, $t by $a." else "Take a breather and enjoy this one — $t by $a.",
                "Just good music, no rush. Here's $t by $a$yr.",
            )
            3 -> listOf( // Funny
                "Zero ads, zero traffic reports, just $t by $a. You're welcome.",
                "Your streaming algorithm could never pick this well. Up next, $t by $a$yr.",
                "Scientifically proven to improve your mood — probably. Here's $t by $a.",
                "If you don't sing along to $t by $a, I'm judging you.",
                "No, I'm not taking requests, I'm already playing $t by $a$yr.",
                if (prev != null) "$prev was great, don't get me wrong, but wait till you hear $t by $a." else "Best radio station on your phone. Here's $t by $a.",
            )
            else -> listOf( // Friendly
                "Hope you're enjoying the music! Coming up next, $t by $a$yr.",
                "Good to have you tuned in to Open Waves. Here's $t by $a.",
                "Hope your day's going great! Rolling into $t by $a$yr.",
                if (prev != null) "Loving the vibe — that was $prev, and next up is $t by $a." else "Nothing beats your own playlist. Up next, $t by $a.",
                "Thanks for hanging out on Open Waves. Here's a good one, $t by $a$yr.",
                "Keeping the good tunes coming with $t by $a.",
            )
        }
        val r = java.util.Random()
        var idx = r.nextInt(pool.size)
        if (idx == lastPick && pool.size > 1) idx = (idx + 1) % pool.size
        lastPick = idx
        return pool[idx]
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

    /** Plays it now (also the settings' "Hear a sample"). Smoothly fades the music down under Savannah, then fades back up. */
    fun say(audio: FloatArray) {
        hush()
        speaking = scope.launch(Dispatchers.IO) {
            val rate = helium314.keyboard.tts.ZonosTts.RATE
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
                if (!ready(app)) return@runCatching "First download the Zonos voices in the keyboard's TTS tile (✨ Zonos)."
                val next = lib?.uniqueSongs?.filter { it.id != cur?.id }?.randomOrNull() ?: return@runCatching "No songs yet."
                val line = line(app, cur, next)
                say(speech(app, line))
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
    var every by remember { mutableIntStateOf(Announcer.every(ctx)) }
    var style by remember { mutableIntStateOf(Announcer.style(ctx)) }
    var msgLen by remember { mutableIntStateOf(Announcer.length(ctx)) }
    var sample by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val dlScope = androidx.compose.runtime.rememberCoroutineScope()
    var zonosReady by remember { mutableStateOf(Announcer.ready(ctx)) }
    var zonosDl by remember { mutableStateOf<Float?>(null) }
    var zonosErr by remember { mutableStateOf<String?>(null) }
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
                Text("Your writing model writes a short line about the next song and Savannah says it while the music ducks underneath. All on your phone.",
                    fontSize = 12.sp, color = dim)
            }
            Switch(on, { on = it; Announcer.set(ctx, "ann_on", it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        // test 319 (Jarrod): direct download buttons right here when Zonos voice or writing model isn't downloaded yet
        if (!zonosReady && helium314.keyboard.tts.ZonosTts.supported(ctx)) {
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
                                withContext(kotlinx.coroutines.Dispatchers.Main) { zonosReady = Announcer.ready(ctx); zonosDl = null }
                            }.onFailure { e ->
                                withContext(kotlinx.coroutines.Dispatchers.Main) { zonosErr = "Download failed: ${e.message}"; zonosDl = null }
                            }
                        }
                    }, Modifier.padding(top = 4.dp)) { Text("Download DJ voice (Savannah)", color = Accent) }
                }
            }
        }
        if (on) {
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
                1 -> "Fast, light AI banter with accuracy checks (falls back to built-in lines if needed)."
                2 -> "Smartest, warmest AI banter — best on phones with 8 GB+ RAM."
                else -> "Built-in real-radio lines: instant, 0 MB extra RAM, coolest on battery."
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
            Text("Voice: Savannah (Zonos)", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 12.sp, color = dim)
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

