// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* Test 311, Jarrod's "do them all": star ratings, smart playlists with rules, playback speed, remembering your spot in
   long tracks, and playing by itself when headphones or the car connect. */

// ---------------- star ratings ----------------
object Ratings {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private var loaded = false
    val version = mutableIntStateOf(0)
    /** the song the "Rate" box is open for */
    val asking = mutableStateOf<Song?>(null)
    private fun file(ctx: Context) = File(ctx.filesDir, "music/ratings.json")
    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching { val j = JSONObject(file(ctx).readText()); j.keys().forEach { map[it.toLong()] = j.getInt(it) } }
    }
    fun of(ctx: Context, id: Long): Int { load(ctx); return map[id] ?: 0 }
    fun all(ctx: Context): Map<Long, Int> { load(ctx); return map }
    @Synchronized fun set(ctx: Context, id: Long, stars: Int) {
        load(ctx)
        if (stars <= 0) map.remove(id) else map[id] = stars.coerceIn(1, 5)
        val j = JSONObject(); map.forEach { (k, v) -> j.put(k.toString(), v) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
        version.intValue++
    }
    /** for backups */
    internal fun replace(ctx: Context, j: JSONObject) { map.clear(); loaded = true; j.keys().forEach { map[it.toLong()] = j.getInt(it) }; set(ctx, -1, 0) }
}

@Composable
internal fun RateDialog() {
    val ctx = LocalContext.current
    val s = Ratings.asking.value ?: return
    var stars by remember(s.id) { mutableIntStateOf(Ratings.of(ctx, s.id)) }
    AlertDialog(onDismissRequest = { Ratings.asking.value = null },
        title = { Text("Rate “${s.title}”") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (i in 1..5) Text(if (i <= stars) "★" else "☆", fontSize = 40.sp, color = if (i <= stars) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.clickable { stars = if (stars == i) i - 1 else i }.padding(4.dp))
            }
        },
        confirmButton = { TextButton({ Ratings.set(ctx, s.id, stars); Ratings.asking.value = null }) { Text("Save", color = Accent) } },
        dismissButton = { TextButton({ Ratings.asking.value = null }) { Text("Cancel") } })
}

// ---------------- smart playlists ----------------
data class SmartList(
    val id: Long, val name: String, val minStars: Int = 0, val minPlays: Int = 0, val maxPlays: Int = -1, val notPlayedDays: Int = 0,
    val addedDays: Int = 0, val genre: String = "", val yearFrom: Int = 0, val yearTo: Int = 0, val favsOnly: Boolean = false,
    val sort: String = "most", val limit: Int = 100,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("stars", minStars).put("minPlays", minPlays).put("maxPlays", maxPlays)
        .put("notPlayed", notPlayedDays).put("added", addedDays).put("genre", genre).put("y1", yearFrom).put("y2", yearTo).put("favs", favsOnly)
        .put("sort", sort).put("limit", limit)
    fun summary(): String = listOfNotNull(
        if (minStars > 0) "$minStars★ or more" else null,
        if (favsOnly) "favourites" else null,
        if (minPlays > 0) "played $minPlays+ times" else null,
        if (maxPlays >= 0) "played at most $maxPlays times" else null,
        if (notPlayedDays > 0) "not played for $notPlayedDays days" else null,
        if (addedDays > 0) "added in the last $addedDays days" else null,
        genre.takeIf { it.isNotBlank() }?.let { "genre “$it”" },
        if (yearFrom > 0 || yearTo > 0) "from ${if (yearFrom > 0) yearFrom else "any"} to ${if (yearTo > 0) yearTo else "now"}" else null,
    ).joinToString(", ").ifEmpty { "every song" }
    companion object {
        fun from(o: JSONObject) = SmartList(o.getLong("id"), o.optString("name", "Smart playlist"), o.optInt("stars"), o.optInt("minPlays"), o.optInt("maxPlays", -1),
            o.optInt("notPlayed"), o.optInt("added"), o.optString("genre"), o.optInt("y1"), o.optInt("y2"), o.optBoolean("favs"), o.optString("sort", "most"), o.optInt("limit", 100))
    }
}

object SmartLists {
    val all = mutableStateOf<List<SmartList>>(emptyList())
    /** the rules box: a list to edit, or a new one */
    val editing = mutableStateOf<SmartList?>(null)
    private fun file(ctx: Context) = File(ctx.filesDir, "music/smart_playlists.json")
    fun load(ctx: Context) {
        all.value = runCatching { val a = JSONArray(file(ctx).readText()); List(a.length()) { SmartList.from(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    }
    private fun save(ctx: Context) { runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(JSONArray(all.value.map { it.json() }).toString()) } }
    fun put(ctx: Context, s: SmartList) { all.value = all.value.filter { it.id != s.id } + s; save(ctx) }
    fun delete(ctx: Context, id: Long) { all.value = all.value.filter { it.id != id }; save(ctx) }
    internal fun replace(ctx: Context, a: JSONArray) { all.value = List(a.length()) { SmartList.from(a.getJSONObject(it)) }; save(ctx) }

    fun songs(ctx: Context, lib: Library, s: SmartList): List<Song> {
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val ratings = Ratings.all(ctx)
        val genres = if (s.genre.isNotBlank()) Moods.genres(ctx, lib.songs) else emptyMap()
        val g = s.genre.trim().lowercase()
        val list = lib.uniqueSongs.filter { song ->
            (s.minStars == 0 || (ratings[song.id] ?: 0) >= s.minStars) &&
                (!s.favsOnly || song.id in Favourites.ids.value) &&
                (s.minPlays == 0 || PlayStats.count(song.id) >= s.minPlays) &&
                (s.maxPlays < 0 || PlayStats.count(song.id) <= s.maxPlays) &&
                (s.notPlayedDays == 0 || now - PlayStats.lastPlayed(song.id) > s.notPlayedDays * day) &&
                (s.addedDays == 0 || now - song.dateAdded * 1000 < s.addedDays * day) &&
                (g.isEmpty() || song.genre.lowercase().contains(g) || (genres[song.id] ?: "").lowercase().contains(g)) &&
                (s.yearFrom == 0 || song.year >= s.yearFrom) && (s.yearTo == 0 || (song.year in 1..s.yearTo))
        }
        val sorted = when (s.sort) {
            "most" -> list.sortedByDescending { PlayStats.count(it.id) }
            "least" -> list.sortedBy { PlayStats.count(it.id) }
            "rating" -> list.sortedByDescending { ratings[it.id] ?: 0 }
            "newest" -> list.sortedByDescending { it.dateAdded }
            "az" -> list.sortedBy { it.title.lowercase() }
            else -> list.shuffled(java.util.Random(now / day)) // "random": a new mix each day
        }
        return if (s.limit > 0) sorted.take(s.limit) else sorted
    }
}

@Composable
internal fun SmartListPage(id: Long, lib: Library, ui: PlayerUi, open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = SmartLists.all.value.find { it.id == id } ?: run { onBack(); return }
    val v = PlayStats.version.value + Ratings.version.intValue
    val songs = remember(s, lib, v) { SmartLists.songs(ctx, lib, s) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(s.name, onBack) }
        item {
            Text("⚡ " + s.summary(), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton({ SmartLists.editing.value = s }) { Text("Edit rules", color = Accent) }
                TextButton({ SmartLists.delete(ctx, s.id); onBack() }) { Text("Delete", color = Accent) }
                TextButton({ Playlists.create(ctx, s.name + " (saved)", songs.map { it.id }) }) { Text("Save as playlist", color = Accent) }
            }
        }
        if (songs.isEmpty()) item { Text("No songs match these rules yet.", Modifier.padding(16.dp)) }
        else item { PlayShuffle(songs, ui) }
        itemsIndexed(songs, key = { _, x -> x.id }) { i, x -> SongRow(x, ui, lib, open) { ui.play(songs, i) } }
    }
}

@Composable
internal fun SmartListDialog() {
    val ctx = LocalContext.current
    val start = SmartLists.editing.value ?: return
    var s by remember(start.id) { mutableStateOf(start) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    @Composable fun num(label: String, value: Int, range: IntRange, step: Int, show: (Int) -> String, set: (Int) -> Unit) {
        Text("$label: ${show(value)}", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Slider(value.toFloat(), { set(((it / step).toInt() * step).coerceIn(range)) }, valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
    }
    AlertDialog(onDismissRequest = { SmartLists.editing.value = null },
        title = { Text(if (SmartLists.all.value.any { it.id == start.id }) "Edit smart playlist" else "New smart playlist") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(s.name, { s = s.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Updates by itself as you play, rate and add songs.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                num("Stars", s.minStars, 0..5, 1, { if (it == 0) "any" else "$it★ or more" }) { s = s.copy(minStars = it) }
                num("Played at least", s.minPlays, 0..50, 1, { if (it == 0) "any" else "$it times" }) { s = s.copy(minPlays = it) }
                num("Not played for", s.notPlayedDays, 0..365, 5, { if (it == 0) "any" else "$it days" }) { s = s.copy(notPlayedDays = it) }
                num("Added in the last", s.addedDays, 0..365, 5, { if (it == 0) "any time" else "$it days" }) { s = s.copy(addedDays = it) }
                num("From year", s.yearFrom, 0..2030, 1, { if (it < 1950) "any" else "$it" }) { s = s.copy(yearFrom = if (it < 1950) 0 else it) }
                num("To year", s.yearTo, 0..2030, 1, { if (it < 1950) "any" else "$it" }) { s = s.copy(yearTo = if (it < 1950) 0 else it) }
                OutlinedTextField(s.genre, { s = s.copy(genre = it) }, label = { Text("Genre (optional, e.g. rock)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Favourites only", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(s.favsOnly, { s = s.copy(favsOnly = it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
                Text("Order", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("most" to "Most played", "least" to "Least played", "rating" to "Top rated", "newest" to "Newest", "az" to "A–Z", "random" to "Random").forEach { (k, n) ->
                        FilterChip(s.sort == k, { s = s.copy(sort = k) }, { Text(n) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
                num("Up to", s.limit, 0..500, 25, { if (it == 0) "every match" else "$it songs" }) { s = s.copy(limit = it) }
            }
        },
        confirmButton = { TextButton({ SmartLists.put(ctx, s.copy(name = s.name.ifBlank { "Smart playlist" })); SmartLists.editing.value = null }) { Text("Save", color = Accent) } },
        dismissButton = { TextButton({ SmartLists.editing.value = null }) { Text("Cancel") } })
}

// ---------------- playback: speed, remember my spot, play on connect ----------------
object Playback {
    val speed = MutableStateFlow(1f)
    val speeds = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
    val asking = mutableStateOf(false)
    /** songs this long or longer get their spot remembered */
    const val LONG_MS = 20 * 60_000L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun load(ctx: Context) { speed.value = prefs(ctx).getFloat("speed", 1f) }
    fun setSpeed(ctx: Context, v: Float) { speed.value = v; prefs(ctx).edit().putFloat("speed", v).apply() }
    fun rememberOn(ctx: Context) = prefs(ctx).getBoolean("remember_spot", true)
    fun setRememberOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("remember_spot", on).apply()
    fun autoPlay(ctx: Context) = prefs(ctx).getBoolean("auto_play_connect", false)
    fun setAutoPlay(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_play_connect", on).apply()

    private fun spots(ctx: Context) = runCatching { JSONObject(prefs(ctx).getString("spots", "{}")!!) }.getOrDefault(JSONObject())
    fun saveSpot(ctx: Context, id: Long, pos: Long, dur: Long) {
        if (dur < LONG_MS || !rememberOn(ctx)) return
        val j = spots(ctx)
        if (pos > dur - 30_000) j.remove(id.toString()) else if (pos >= 30_000) j.put(id.toString(), pos) else return
        // keep it small: the 50 newest
        val keys = j.keys().asSequence().toList()
        if (keys.size > 50) keys.take(keys.size - 50).forEach { j.remove(it) }
        prefs(ctx).edit().putString("spots", j.toString()).apply()
    }
    fun spotOf(ctx: Context, id: Long): Long = if (rememberOn(ctx)) spots(ctx).optLong(id.toString(), 0) else 0
    internal fun allSpots(ctx: Context) = spots(ctx)
}

@Composable
internal fun SpeedDialog() {
    val ctx = LocalContext.current
    if (!Playback.asking.value) return
    val cur = Playback.speed.value
    AlertDialog(onDismissRequest = { Playback.asking.value = false },
        title = { Text("Playback speed") },
        text = {
            Column {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Playback.speeds.forEach { v ->
                        FilterChip(v == cur, { Playback.setSpeed(ctx, v) }, { Text(if (v == 1f) "Normal" else "${v}×") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Faster or slower without the voices going high or low. Handy for podcasts, audiobooks and practice.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = { TextButton({ Playback.asking.value = false }) { Text("Done", color = Accent) } })
}

@Composable
internal fun PlaybackSettings() {
    val ctx = LocalContext.current
    var remember by remember { mutableStateOf(Playback.rememberOn(ctx)) }
    var auto by remember { mutableStateOf(Playback.autoPlay(ctx)) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column {
        Section("Playback")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Play when headphones or the car connect", fontSize = 16.sp)
                Text("Starts where you left off when wired or Bluetooth headphones, earbuds or your car connect. It already pauses when they disconnect.",
                    fontSize = 12.sp, color = dim)
            }
            Switch(auto, { auto = it; Playback.setAutoPlay(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember my spot in long tracks", fontSize = 16.sp)
                Text("Audiobooks, podcasts and DJ mixes (20 minutes or longer) carry on from where you stopped.", fontSize = 12.sp, color = dim)
            }
            Switch(remember, { remember = it; Playback.setRememberOn(ctx, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        Row(Modifier.fillMaxWidth().clickable { Playback.asking.value = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Playback speed", Modifier.weight(1f), fontSize = 16.sp)
            Text(if (Playback.speed.value == 1f) "Normal" else "${Playback.speed.value}×", color = Accent, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(1.dp))
    }
}
