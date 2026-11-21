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
 * all on the phone) writes a line or two about it and the Kokoro voice says it while the music dips. Off by default.
 */
object Announcer {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun on(ctx: Context) = prefs(ctx).getBoolean("ann_on", false)
    fun every(ctx: Context) = prefs(ctx).getInt("ann_every", 3)
    fun voiceId(ctx: Context) = prefs(ctx).getString("ann_voice", "am_michael")!!
    fun style(ctx: Context) = prefs(ctx).getInt("ann_style", 0)
    fun set(ctx: Context, key: String, v: Any) {
        prefs(ctx).edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v.toString()) } }.apply()
        if (key == "ann_on" && v == false) { hush(); scope.launch { runCatching { helium314.keyboard.tts.KokoroTts.release() } } }
    }

    val STYLES = listOf(
        "Friendly" to "a warm, friendly radio DJ",
        "Hyped" to "an excited, high-energy radio DJ",
        "Late night" to "a calm, smooth late-night radio DJ",
        "Funny" to "a cheeky, funny radio DJ who makes one light joke",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Ready(val forId: Long, val audio: FloatArray)
    @Volatile private var ready: Ready? = null
    @Volatile private var making: Long = -1
    @Volatile private var speaking: Job? = null
    @Volatile private var track: AudioTrack? = null
    private var songsSince = 0

    private fun voice(ctx: Context) = helium314.keyboard.tts.KokoroTts.VOICES.firstOrNull { it.id == voiceId(ctx) } ?: helium314.keyboard.tts.KokoroTts.VOICES[0]

    /** Called every second by the music service: makes the next song's line before this one ends. */
    fun tick(ctx: Context, p: Player) {
        if (!on(ctx) || !p.isPlaying || p.duration <= 0) return
        if (songsSince + 1 < every(ctx)) return
        if (p.duration - p.currentPosition > 45_000) return
        val nextIndex = p.nextMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: return
        val lib = Library.cached ?: return
        val next = p.getMediaItemAt(nextIndex).mediaId.toLongOrNull()?.let { lib.byId[it] } ?: return
        if (ready?.forId == next.id || making == next.id) return
        val cur = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { lib.byId[it] }
        if (!helium314.keyboard.tts.KokoroTts.installed(ctx)) return
        making = next.id
        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val line = line(app, cur, next)
                val v = voice(app)
                val audio = helium314.keyboard.tts.KokoroTts.engine(app, v).generate(helium314.keyboard.tts.ChatSpeaker.forSpeech(line), v.sid, 1.0f).samples
                ready = Ready(next.id, audio)
            }.onFailure { android.util.Log.w("OpenWaves", "announcer couldn't make a line", it) }
            making = -1
        }
    }

    /** What the DJ says. The model writes it; if it isn't there (or is slow), a simple line instead. */
    suspend fun line(ctx: Context, cur: Song?, next: Song): String {
        val facts = listOfNotNull(
            next.year.takeIf { it > 0 }?.let { "from $it" },
            Popularity.known(ctx, next)?.story(),
            next.genre.takeIf { it.isNotBlank() }?.let { "genre: $it" },
        ).joinToString("; ")
        val who = STYLES.getOrElse(style(ctx)) { STYLES[0] }.second
        val system = "You are $who on Open Waves, the listener's own music station. Reply with only what you say out loud: one or two short sentences, " +
            "at most 35 words. No emojis, no lists, no stage directions. Only use the facts given; never invent facts about the song or artist."
        val prompt = buildString {
            if (cur != null) append("Just played: \"${cur.title}\" by ${cur.artist}. ")
            append("Up next: \"${next.title}\" by ${next.artist}")
            if (facts.isNotEmpty()) append(" ($facts)")
            append(". Say the link into the next song.")
        }
        val written = runCatching {
            withTimeoutOrNull(40_000) { helium314.keyboard.latin.WriterClient.ask(ctx, system, prompt, 90) }
        }.getOrNull()
            ?.replace(Regex("(?s)<think>.*?</think>"), "")?.replace(Regex("^[\"“]|[\"”]$"), "")?.trim()
            ?.takeIf { it.length in 8..400 }
        return written ?: buildString {
            if (cur != null) append("That was ${cur.title} by ${cur.artist}. ")
            append("Up next, ${next.title} by ${next.artist}.")
        }
    }

    /** Called by the music service when a new song starts. */
    fun songStarted(ctx: Context, id: Long?) {
        if (!on(ctx)) return
        songsSince++
        val r = ready ?: return
        if (r.forId != id) return
        ready = null; songsSince = 0
        say(r.audio)
    }

    /** Plays it now (also the settings' "Hear a sample"). The music dips under the voice. */
    fun say(audio: FloatArray) {
        hush()
        speaking = scope.launch(Dispatchers.IO) {
            val rate = helium314.keyboard.tts.KokoroTts.RATE
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = runCatching { AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build() }.getOrNull() ?: return@launch
            track = t
            try {
                Eq.duck = 0.28f
                kotlinx.coroutines.delay(350)
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
                if (!helium314.keyboard.tts.KokoroTts.installed(app)) return@runCatching "First download the Kokoro voice in the keyboard's TTS tile."
                val next = lib?.uniqueSongs?.filter { it.id != cur?.id }?.randomOrNull() ?: return@runCatching "No songs yet."
                val line = line(app, cur, next)
                val v = voice(app)
                say(helium314.keyboard.tts.KokoroTts.engine(app, v).generate(helium314.keyboard.tts.ChatSpeaker.forSpeech(line), v.sid, 1.0f).samples)
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
    var voice by remember { mutableStateOf(Announcer.voiceId(ctx)) }
    var style by remember { mutableIntStateOf(Announcer.style(ctx)) }
    var sample by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val kokoro = remember { helium314.keyboard.tts.KokoroTts.installed(ctx) }
    @Composable fun chip(label: String, sel: Boolean, click: () -> Unit) =
        FilterChip(sel, click, { Text(label) }, Modifier.padding(end = 6.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.3f)))
    Column {
        Section("AI radio announcer")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📻 DJ between songs", fontSize = 16.sp)
                Text("Your writing model writes a short line about the next song and the Kokoro voice says it while the music dips. All on your phone.",
                    fontSize = 12.sp, color = dim)
                if (!kokoro) Text("Needs the Kokoro voice: download it in the keyboard's TTS tile first.", fontSize = 12.sp, color = Accent)
            }
            Switch(on, { on = it; Announcer.set(ctx, "ann_on", it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (on) {
            Text("How often", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                for ((n, label) in listOf(1 to "Every song", 2 to "Every 2nd", 3 to "Every 3rd", 5 to "Every 5th")) chip(label, every == n) { every = n; Announcer.set(ctx, "ann_every", n) }
            }
            Text("Style", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                Announcer.STYLES.forEachIndexed { i, (label, _) -> chip(label, style == i) { style = i; Announcer.set(ctx, "ann_style", i) } }
            }
            Text("Voice", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                for (v in helium314.keyboard.tts.KokoroTts.VOICES) chip(v.name, voice == v.id) { voice = v.id; Announcer.set(ctx, "ann_voice", v.id) }
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
            Text("The first line can take a little while as the model loads.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = dim)
        }
        Spacer(Modifier.width(1.dp))
    }
}

