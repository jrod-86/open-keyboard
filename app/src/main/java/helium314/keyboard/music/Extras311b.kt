// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/* Test 311 part 3: listening history and "Your year in music", backup and restore to a file, and scrobbling to ListenBrainz. */

// ---------------- listening history ----------------
object History {
    private fun file(ctx: Context) = File(ctx.filesDir, "music/history.txt")
    /** one line per play: time (ms) and song id */
    @Synchronized fun add(ctx: Context, id: Long) {
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.appendText("${System.currentTimeMillis()},$id\n") }
    }
    fun read(ctx: Context): List<Pair<Long, Long>> = runCatching {
        file(ctx).readLines().mapNotNull { l -> val p = l.split(','); if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toLongOrNull() ?: return@mapNotNull null) else null }
    }.getOrDefault(emptyList())
}

class YearStats(
    val label: String, val plays: Int, val minutes: Long, val songs: List<Pair<Song, Int>>, val artists: List<Pair<String, Int>>,
    val genres: List<Pair<String, Int>>, val months: IntArray, val newFound: Int, val fromHistory: Boolean, val topDay: String?,
)

private fun yearStats(ctx: Context, lib: Library, year: Int?): YearStats {
    val cal = Calendar.getInstance()
    val history = History.read(ctx)
    val plays: List<Long> // song ids, one per play
    val months = IntArray(12)
    var fromHistory = true
    var topDay: String? = null
    if (history.isNotEmpty()) {
        val picked = history.filter { (t, _) -> cal.timeInMillis = t; year == null || cal.get(Calendar.YEAR) == year }
        picked.forEach { (t, _) -> cal.timeInMillis = t; months[cal.get(Calendar.MONTH)]++ }
        plays = picked.map { it.second }
        val days = picked.groupingBy { (t, _) -> cal.timeInMillis = t; cal.get(Calendar.DAY_OF_WEEK) }.eachCount()
        topDay = days.maxByOrNull { it.value }?.key?.let { java.text.DateFormatSymbols.getInstance().weekdays[it] }
    } else {
        // no history yet (it starts with this version): use the all-time play counts
        fromHistory = false
        plays = PlayStats.snapshot().flatMap { (id, v) -> List(v.first) { id } }
    }
    val counts = plays.groupingBy { it }.eachCount()
    val songs = counts.entries.mapNotNull { (id, n) -> lib.byId[id]?.let { it to n } }.sortedByDescending { it.second }
    val minutes = songs.sumOf { (s, n) -> s.durationMs * n } / 60_000
    val artists = songs.groupBy { AlbumRules.mainArtist(it.first.artist) }.map { (_, l) -> l.first().first.artist.substringBefore(" feat").trim() to l.sumOf { it.second } }
        .sortedByDescending { it.second }
    val genreOf = Moods.genres(ctx, songs.map { it.first })
    val genres = songs.mapNotNull { (s, n) -> genreOf[s.id]?.let { it to n } }.groupBy { it.first }.map { (g, l) -> g to l.sumOf { it.second } }.sortedByDescending { it.second }
    val newFound = if (year == null) lib.songs.size else lib.songs.count { cal.timeInMillis = it.dateAdded * 1000; cal.get(Calendar.YEAR) == year }
    return YearStats(year?.toString() ?: "All time", plays.size, minutes, songs.take(10), artists.take(10), genres.take(5), months, newFound, fromHistory, topDay)
}

@Composable
internal fun YearPage(lib: Library, ui: PlayerUi, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    var year by remember { mutableStateOf<Int?>(thisYear) }
    val stats by produceState<YearStats?>(null, year, lib) { value = withContext(Dispatchers.Default) { yearStats(ctx, lib, year) } }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Your year in music", onBack) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<Int?>(thisYear, thisYear - 1, null)) { y ->
                    FilterChip(year == y, { year = y }, { Text(y?.toString() ?: "All time") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                }
            }
        }
        val s = stats
        if (s == null) { item { Text("Adding it up…", Modifier.padding(16.dp)) }; return@LazyColumn }
        if (!s.fromHistory) item { Text("Your full listening history starts with this version, so for now these are all-time play counts.", Modifier.padding(16.dp), fontSize = 13.sp, color = dim) }
        item {
            // the big card
            Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF7B2FF7), Color(0xFFF107A3), Color(0xFFFF8A00)))).padding(22.dp)) {
                Text(s.label, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                Text("${"%,d".format(s.minutes)} minutes", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("of music · ${"%,d".format(s.plays)} plays", color = Color.White, fontSize = 15.sp)
                s.artists.firstOrNull()?.let { Text("Your top artist: ${it.first}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
                s.songs.firstOrNull()?.let { Text("Your song: “${it.first.title}” (${it.second} plays)", color = Color.White, fontSize = 16.sp) }
                s.genres.firstOrNull()?.let { Text("Your sound: ${it.first}", color = Color.White, fontSize = 16.sp) }
                s.topDay?.let { Text("You listen most on ${it}s", color = Color.White, fontSize = 16.sp) }
                Text("New songs added: ${s.newFound}", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (s.fromHistory && s.months.any { it > 0 }) item {
            Text("Month by month", Modifier.padding(horizontal = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val max = s.months.maxOrNull()!!.coerceAtLeast(1)
            val names = java.text.DateFormatSymbols.getInstance().shortMonths
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(16.dp)) {
                val w = size.width / 12
                for (m in 0 until 12) {
                    val h = size.height * s.months[m] / max
                    drawRoundRect(Accent, Offset(m * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(6f, 6f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { for (m in 0 until 12) Text(names[m].take(1), Modifier.weight(1f), fontSize = 11.sp, color = dim) }
        }
        if (s.songs.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Top songs", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                OutlinedButton({ ui.play(s.songs.map { it.first }, 0) }) { Text("Play", color = Accent) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ Playlists.create(ctx, "Top songs ${s.label}", s.songs.map { it.first.id }); Toast.makeText(ctx, "Saved as a playlist", Toast.LENGTH_SHORT).show() }) { Text("Save", color = Accent) }
            }
        }
        items(s.songs.size) { i ->
            val (song, n) = s.songs[i]
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(28.dp), color = Accent, fontWeight = FontWeight.Bold)
                Cover(song, 160, Modifier.size(44.dp), corner = 6)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("$n plays", fontSize = 13.sp, color = dim)
            }
        }
        if (s.artists.isNotEmpty()) item { Text("Top artists", Modifier.padding(start = 16.dp, top = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(s.artists.size) { i ->
            val (a, n) = s.artists[i]
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(28.dp), color = Accent, fontWeight = FontWeight.Bold)
                Text(a, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$n plays", fontSize = 13.sp, color = dim)
            }
        }
        if (s.genres.isNotEmpty()) item {
            Text("Top sounds: " + s.genres.joinToString(", ") { it.first }, Modifier.padding(16.dp), fontSize = 15.sp)
        }
    }
}

// ---------------- backup and restore ----------------
object Backup {
    /** Everything you've made in Open Waves, in one .zip: playlists, ratings, plays, favourites, Moods, fixes, settings. */
    suspend fun write(ctx: Context, to: Uri): String = withContext(Dispatchers.IO) {
        val lib = Library.load(ctx)
        val dir = File(ctx.filesDir, "music")
        ctx.contentResolver.openOutputStream(to)!!.use { os ->
            ZipOutputStream(os).use { z ->
                // the songs by id, so a new phone (where every song gets a new id) can find them again
                val songs = JSONArray()
                lib.songs.forEach { s -> songs.put(JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("d", s.durationMs).put("f", s.path.substringAfterLast('/'))) }
                z.putNextEntry(ZipEntry("songs.json")); z.write(songs.toString().toByteArray()); z.closeEntry()
                val prefs = JSONObject()
                ctx.getSharedPreferences("music", Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    prefs.put(k, when (v) {
                        is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                        is Boolean -> JSONObject().put("b", v)
                        is Int -> JSONObject().put("i", v)
                        is Long -> JSONObject().put("l", v)
                        is Float -> JSONObject().put("f", v.toDouble())
                        else -> JSONObject().put("s", v.toString())
                    })
                }
                z.putNextEntry(ZipEntry("prefs.json")); z.write(prefs.toString().toByteArray()); z.closeEntry()
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("music/" + f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        "Saved: ${lib.songs.size} songs' playlists, plays, ratings and settings"
    }

    suspend fun read(ctx: Context, from: Uri): String = withContext(Dispatchers.IO) {
        val lib = Library.load(ctx, fresh = true)
        val entries = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(from)!!.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory && !e.name.contains("..")) entries[e.name] = z.readBytes() }
            }
        }
        val old = JSONArray(String(entries["songs.json"] ?: return@withContext "That isn't an Open Waves backup"))
        // old id -> this phone's id: same file name and length first, then the same title and artist
        val byFile = lib.songs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val byName = lib.songs.groupBy { AlbumRules.titleKey(it.title) + "|" + AlbumRules.artistKey(it.artist) }
        val map = HashMap<Long, Long>()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            val d = o.optLong("d")
            val hit = byFile[o.optString("f").lowercase()]?.minByOrNull { kotlin.math.abs(it.durationMs - d) }?.takeIf { kotlin.math.abs(it.durationMs - d) < 3000 }
                ?: byName[AlbumRules.titleKey(o.optString("t")) + "|" + AlbumRules.artistKey(o.optString("a"))]?.firstOrNull()
            if (hit != null) map[o.getLong("id")] = hit.id
        }
        fun id(s: String): String? = s.toLongOrNull()?.let { map[it]?.toString() }
        fun remapKeys(j: JSONObject): JSONObject { val o = JSONObject(); j.keys().forEach { k -> val n = if (k.toLongOrNull() != null) id(k) else k; if (n != null) o.put(n, j.get(k)) }; return o }
        val dir = File(ctx.filesDir, "music")
        val keyed = setOf("stats.json", "ratings.json", "hidden.json", "hidden_paths.json", "feel.json", "clap.json", "fixes.json")
        for ((name, bytes) in entries) {
            if (!name.startsWith("music/")) continue
            val rel = name.removePrefix("music/")
            var outRel = rel
            var data = bytes
            when {
                rel in keyed -> data = runCatching {
                    var t = remapKeys(JSONObject(String(bytes))).toString()
                    if (rel == "fixes.json") map.forEach { (a, b) -> t = t.replace("/art/$a.jpg", "/art/$b.jpg") }
                    t.toByteArray()
                }.getOrDefault(bytes)
                rel == "playlists.json" -> data = runCatching {
                    val a = JSONArray(String(bytes))
                    for (i in 0 until a.length()) { val p = a.getJSONObject(i); val s = p.getJSONArray("songs"); p.put("songs", JSONArray((0 until s.length()).mapNotNull { map[s.getLong(it)] })) }
                    a.toString().toByteArray()
                }.getOrDefault(bytes)
                rel == "history.txt" -> data = String(bytes).lines().mapNotNull { l -> val p = l.split(','); if (p.size == 2) map[p[1].toLongOrNull() ?: -1]?.let { "${p[0]},$it" } else null }
                    .joinToString("\n", postfix = "\n").toByteArray()
                rel.startsWith("lyrics/") || rel.startsWith("art/") -> {
                    val base = rel.substringAfter('/').substringBefore('.'); val ext = rel.substringAfterLast('.')
                    outRel = rel.substringBefore('/') + "/" + (id(base) ?: continue) + "." + ext
                }
                rel == "mark_checked.txt" -> continue // looked at again on this phone
            }
            File(dir, outRel).apply { parentFile?.mkdirs() }.writeBytes(data)
        }
        // settings: favourites, the queue, remembered spots and removed mood songs have song ids in them
        entries["prefs.json"]?.let { b ->
            val e = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit()
            val j = JSONObject(String(b))
            j.keys().forEach { k ->
                val v = j.getJSONObject(k)
                when {
                    v.has("set") -> { val a = v.getJSONArray("set"); val list = (0 until a.length()).map { a.getString(it) }
                        e.putStringSet(k, if (k == "favs" || k.startsWith("mood_out_")) list.mapNotNull(::id).toSet() else list.toSet()) }
                    v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
                    v.has("i") -> e.putInt(k, v.getInt("i"))
                    v.has("l") -> e.putLong(k, v.getLong("l"))
                    v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
                    k == "queue" -> e.putString(k, v.getString("s").split(',').mapNotNull(::id).joinToString(","))
                    k == "spots" -> e.putString(k, runCatching { remapKeys(JSONObject(v.getString("s"))).toString() }.getOrDefault("{}"))
                    else -> e.putString(k, v.getString("s"))
                }
            }
            e.commit()
        }
        "Restored ${map.size} of ${old.length()} songs' info. Open Waves will now restart."
    }
}

@Composable
internal fun BackupSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { msg = runCatching { Backup.write(ctx, uri) }.getOrElse { "Couldn't save: ${it.message}" } }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching { Backup.read(ctx, uri) }.getOrElse { "Couldn't restore: ${it.message}" }
            if (msg.startsWith("Restored")) {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                kotlinx.coroutines.delay(1500)
                // start fresh so every list reloads what was restored
                val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ctx.startActivity(android.content.Intent(ctx, MusicActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                if (i != null) Unit
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
    Column {
        Section("Backup")
        Text("Saves your playlists, smart playlists, ratings, favourites, plays and history, Moods, song fixes, lyrics, equaliser and settings into one file. " +
            "Restore it on a new phone: songs are matched by file name and length, or name and artist.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ save.launch("OpenWaves-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip") },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Back up") }
            OutlinedButton({ load.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore", color = Accent) }
        }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = Accent)
    }
}

// ---------------- scrobbling (ListenBrainz) ----------------
/**
 * Sends what you listen to to ListenBrainz (free and open, run by the MetaBrainz charity), for your own stats and
 * recommendations there. Off until you add your token. A song counts after half of it or 4 minutes, like Last.fm.
 * Listens made offline wait in a file and go when you're back online.
 */
object Scrobble {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun token(ctx: Context) = prefs(ctx).getString("lb_token", "") ?: ""
    fun on(ctx: Context) = prefs(ctx).getBoolean("lb_on", false) && token(ctx).isNotBlank()
    fun set(ctx: Context, on: Boolean, token: String) = prefs(ctx).edit().putBoolean("lb_on", on).putString("lb_token", token.trim()).apply()
    private fun queue(ctx: Context) = File(ctx.filesDir, "music/scrobble_queue.txt")

    private var curId = -1L; private var heardMs = 0L; private var sent = false; private var startedAt = 0L

    /** called every second by the player */
    fun tick(ctx: Context, id: Long, playing: Boolean, durMs: Long) {
        if (id != curId) { curId = id; heardMs = 0; sent = false; startedAt = System.currentTimeMillis() / 1000 }
        if (!playing || sent || !on(ctx)) return
        heardMs += 1000
        if (durMs >= 30_000 && heardMs >= minOf(durMs / 2, 240_000L)) {
            sent = true
            val s = Library.cached?.byId?.get(id) ?: return
            val listen = JSONObject().put("listened_at", startedAt).put("track_metadata", JSONObject().put("artist_name", s.artist).put("track_name", s.title)
                .put("release_name", s.album).put("additional_info", JSONObject().put("duration_ms", s.durationMs).put("media_player", "Open Waves")))
            runCatching { queue(ctx).apply { parentFile?.mkdirs() }.appendText(listen.toString() + "\n") }
            Thread { flush(ctx) }.start()
        }
    }

    @Synchronized fun flush(ctx: Context): Boolean {
        val f = queue(ctx)
        val lines = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.isEmpty()) return true
        val payload = JSONArray(lines.take(100).map { JSONObject(it) })
        val body = JSONObject().put("listen_type", if (payload.length() == 1) "single" else "import").put("payload", payload).toString()
        val ok = runCatching {
            val c = URL("https://api.listenbrainz.org/1/submit-listens").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("Authorization", "Token " + token(ctx)); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            try { c.responseCode == 200 } finally { c.disconnect() }
        }.getOrDefault(false)
        if (ok) f.writeText(lines.drop(100).joinToString("\n", postfix = if (lines.size > 100) "\n" else ""))
        return ok
    }

    fun check(ctx: Context, token: String): Boolean = runCatching {
        val c = URL("https://api.listenbrainz.org/1/validate-token").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Token " + token.trim()); c.connectTimeout = 8000; c.readTimeout = 10000
        try { c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("valid") } finally { c.disconnect() }
    }.getOrDefault(false)
}

@Composable
internal fun ScrobbleSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Scrobble.on(ctx)) }
    var token by remember { mutableStateOf(Scrobble.token(ctx)) }
    var msg by remember { mutableStateOf("") }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column {
        Section("Scrobbling")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send my listens to ListenBrainz", fontSize = 16.sp)
                Text("Free and open (like Last.fm). Builds your listening stats on listenbrainz.org. Sends the song name, artist and album only.", fontSize = 12.sp, color = dim)
            }
            Switch(on, { on = it; Scrobble.set(ctx, it, token) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (on) {
            OutlinedTextField(token, { token = it; Scrobble.set(ctx, on, it) }, label = { Text("Your ListenBrainz user token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Text("Find it at listenbrainz.org > your profile > Settings > User token.", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
            Row(Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextButton({ scope.launch { msg = if (withContext(Dispatchers.IO) { Scrobble.check(ctx, token) }) "✓ Token works" else "That token didn't work (or no internet)" } }) {
                    Text("Check token", color = Accent)
                }
            }
            if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = Accent)
        }
        Box(Modifier.height(4.dp))
    }
}
