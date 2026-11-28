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

/* Test 311, Jarrod's "do them all": smart playlists with rules, playback speed, remembering your spot in
   long tracks, and playing by itself when headphones or the car connect. */

// ---------------- smart playlists ----------------
data class SmartList(
    val id: Long, val name: String, val minPlays: Int = 0, val maxPlays: Int = -1, val notPlayedDays: Int = 0,
    val addedDays: Int = 0, val genre: String = "", val yearFrom: Int = 0, val yearTo: Int = 0, val favsOnly: Boolean = false,
    val sort: String = "random", val limit: Int = 60,
    val chart: String = "", val decades: List<String> = emptyList(), val genres: List<String> = emptyList(),
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("minPlays", minPlays).put("maxPlays", maxPlays)
        .put("notPlayed", notPlayedDays).put("added", addedDays).put("genre", genre).put("y1", yearFrom).put("y2", yearTo).put("favs", favsOnly)
        .put("sort", sort).put("limit", limit)
        .put("chart", chart).put("decades", JSONArray(decades)).put("genres", JSONArray(genres))
    fun summary(): String {
        val allGenres = (genres + listOfNotNull(genre.takeIf { it.isNotBlank() })).distinct()
        return listOfNotNull(
            chart.takeIf { it.isNotBlank() },
            decades.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            allGenres.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            if (favsOnly) "favourites" else null,
            if (minPlays > 0) "played $minPlays+ times" else null,
            if (maxPlays >= 0) "played at most $maxPlays times" else null,
            if (notPlayedDays > 0) "not played for ${notPlayedDays}d" else null,
            if (addedDays > 0) "added in last ${addedDays}d" else null,
            if (yearFrom > 0 || yearTo > 0) "from ${if (yearFrom > 0) yearFrom else "any"} to ${if (yearTo > 0) yearTo else "now"}" else null,
        ).joinToString(" · ").ifEmpty { "every song" }
    }
    companion object {
        private fun strList(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        fun from(o: JSONObject) = SmartList(
            o.getLong("id"), o.optString("name", "Smart playlist"), o.optInt("minPlays"), o.optInt("maxPlays", -1),
            o.optInt("notPlayed"), o.optInt("added"), o.optString("genre"), o.optInt("y1"), o.optInt("y2"), o.optBoolean("favs"),
            o.optString("sort", "popular").let { if (it == "rating") "popular" else it }, o.optInt("limit", 100),
            o.optString("chart", ""), strList(o.optJSONArray("decades")), strList(o.optJSONArray("genres")),
        )
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
        val wantedGenres = (s.genres + listOfNotNull(s.genre.takeIf { it.isNotBlank() })).map { it.trim().lowercase() }.distinct()
        val genreMap = if (wantedGenres.isNotEmpty()) Moods.genres(ctx, lib.uniqueSongs) else emptyMap()
        val needPop = s.chart.isNotEmpty() || s.decades.isNotEmpty() || s.sort == "popular" || s.sort == "oldest" || s.sort == "newest"
        val popMap = if (needPop) lib.uniqueSongs.associate { it.id to Popularity.known(ctx, it)?.takeIf { p -> p.found } } else emptyMap()
        val list = lib.uniqueSongs.filter { song ->
            val pop = popMap[song.id]
            val best = pop?.let { p -> listOf(p.us, p.au, p.uk).filter { it > 0 }.minOrNull() ?: 0 } ?: 0
            val year = pop?.year?.takeIf { it > 0 } ?: song.year
            val chartOk = when (s.chart) {
                "Chart hits" -> best > 0
                "#1s" -> best == 1
                "Top 20s" -> best in 1..20
                "Top 20–50" -> best in 20..50
                "Hidden gems" -> pop != null && pop.plays > 0
                else -> true
            }
            val decadeOk = s.decades.isEmpty() || s.decades.any { d ->
                if (d == "Before 1970s") year in 1..1969
                else d.removeSuffix("s").toIntOrNull()?.let { dec -> year / 10 * 10 == dec } ?: false
            }
            val sg = genreMap[song.id].orEmpty().lowercase()
            val rg = song.genre.lowercase()
            val genreOk = wantedGenres.isEmpty() || wantedGenres.any { g -> sg == g || sg.contains(g) || rg.contains(g) }
            chartOk && decadeOk && genreOk &&
                (!s.favsOnly || song.id in Favourites.ids.value) &&
                (s.minPlays == 0 || PlayStats.count(song.id) >= s.minPlays) &&
                (s.maxPlays < 0 || PlayStats.count(song.id) <= s.maxPlays) &&
                (s.notPlayedDays == 0 || now - PlayStats.lastPlayed(song.id) > s.notPlayedDays * day) &&
                (s.addedDays == 0 || now - song.dateAdded * 1000 < s.addedDays * day) &&
                (s.yearFrom == 0 || year >= s.yearFrom) && (s.yearTo == 0 || (year in 1..s.yearTo))
        }
        val sorted = when (s.sort) {
            "popular" -> if (s.chart == "Hidden gems") list.sortedBy { popMap[it.id]?.plays ?: Long.MAX_VALUE }
                else list.sortedWith(compareBy<Song>(
                    { if ((popMap[it.id]?.plays ?: 0L) > 0L) 0 else 1 },
                    { -(popMap[it.id]?.plays ?: 0L) },
                    { popMap[it.id]?.let { p -> listOf(p.us, p.au, p.uk).filter { c -> c > 0 }.minOrNull() } ?: Int.MAX_VALUE },
                ))
            "most" -> list.sortedByDescending { PlayStats.count(it.id) }
            "least" -> list.sortedBy { PlayStats.count(it.id) }
            "newest" -> list.sortedWith(compareByDescending<Song> { popMap[it.id]?.year?.takeIf { y -> y > 0 } ?: it.year }.thenByDescending { it.dateAdded })
            "oldest" -> list.sortedWith(compareBy<Song> { (popMap[it.id]?.year?.takeIf { y -> y > 0 } ?: it.year).let { y -> if (y > 0) y else Int.MAX_VALUE } }.thenBy { it.dateAdded })
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
    val v = PlayStats.version.value
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
    val lib = Library.cached
    var s by remember(start.id) {
        val mergedGenres = (start.genres + listOfNotNull(start.genre.takeIf { it.isNotBlank() })).distinct()
        mutableStateOf(start.copy(genres = mergedGenres, genre = ""))
    }
    var moreOpen by remember(start.id) { mutableStateOf(start.minPlays > 0 || start.notPlayedDays > 0 || start.addedDays > 0) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f))
    val availGenres = remember(lib) {
        lib?.let {
            Moods.genres(ctx, it.uniqueSongs).values.filter { g -> g.isNotEmpty() }
                .groupingBy { g -> g }.eachCount().entries.sortedByDescending { e -> e.value }.map { e -> e.key }
        }.orEmpty()
    }
    val availDecades = remember(lib) {
        if (lib == null) listOf("Before 1970s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s")
        else {
            val yrs = lib.uniqueSongs.map { song -> Popularity.known(ctx, song)?.year?.takeIf { it > 0 } ?: song.year }.filter { it > 0 }
            val pre = if (yrs.any { it < 1970 }) listOf("Before 1970s") else emptyList()
            pre + yrs.map { it / 10 * 10 }.filter { it >= 1970 }.distinct().sorted().map { "${it}s" }
        }
    }
    val matchCount = remember(s, lib) { lib?.let { SmartLists.songs(ctx, it, s.copy(limit = 0)).size } }
    val chartChoices = listOf("" to "Any", "Chart hits" to "Chart hits", "#1s" to "#1s", "Top 20s" to "Top 20s", "Top 20–50" to "Top 20–50", "Hidden gems" to "Hidden gems")
    val limitChoices = listOf(15 to "15", 30 to "30", 60 to "60", 100 to "100", 250 to "250", 0 to "All")
    val sortChoices = listOf("popular" to "Popular", "random" to "Mixed up", "most" to "Most played", "least" to "Least played", "newest" to "Newest", "oldest" to "Oldest", "az" to "A–Z")

    @Composable fun num(label: String, value: Int, range: IntRange, step: Int, show: (Int) -> String, set: (Int) -> Unit) {
        Text("$label: ${show(value)}", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Slider(value.toFloat(), { set(((it / step).toInt() * step).coerceIn(range)) }, valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
    }

    AlertDialog(onDismissRequest = { SmartLists.editing.value = null },
        title = { Text(if (SmartLists.all.value.any { it.id == start.id }) "Edit smart playlist" else "New smart playlist") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(s.name, { s = s.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (matchCount != null) {
                    Text("⚡ $matchCount song" + (if (matchCount == 1) "" else "s") + " match" + (if (s.limit in 1 until matchCount) " (capped at ${s.limit})" else ""),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text("Updates by itself as you play and add songs.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                }

                Text("Chart & popularity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chartChoices.forEach { (k, label) ->
                        FilterChip(s.chart == k, { s = s.copy(chart = k) }, { Text(label) }, colors = chipColors)
                    }
                }

                if (availDecades.isNotEmpty()) {
                    Text("Decades", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(s.decades.isEmpty(), { s = s.copy(decades = emptyList(), yearFrom = 0, yearTo = 0) }, { Text("Any") }, colors = chipColors)
                        availDecades.forEach { d ->
                            val picked = d in s.decades
                            val label = if (d.length == 5 && d[0].isDigit()) "'" + d.substring(2) else d
                            FilterChip(picked, {
                                val next = if (picked) s.decades - d else s.decades + d
                                s = s.copy(decades = next, yearFrom = 0, yearTo = 0)
                            }, { Text(label) }, colors = chipColors)
                        }
                    }
                }

                if (availGenres.isNotEmpty()) {
                    Text("Genres", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(s.genres.isEmpty(), { s = s.copy(genres = emptyList()) }, { Text("All genres") }, colors = chipColors)
                        availGenres.forEach { g ->
                            val picked = g in s.genres
                            FilterChip(picked, {
                                s = s.copy(genres = if (picked) s.genres - g else s.genres + g)
                            }, { Text(g) }, colors = chipColors)
                        }
                    }
                }

                Text("Order", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sortChoices.forEach { (k, n) ->
                        FilterChip(s.sort == k, { s = s.copy(sort = k) }, { Text(n) }, colors = chipColors)
                    }
                }

                Text("How many songs", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    limitChoices.forEach { (n, label) ->
                        FilterChip(s.limit == n, { s = s.copy(limit = n) }, { Text(label) }, colors = chipColors)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Favourites only", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(s.favsOnly, { s = s.copy(favsOnly = it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }

                TextButton({ moreOpen = !moreOpen }, modifier = Modifier.padding(top = 2.dp)) {
                    Text(if (moreOpen) "Hide extra rules ▴" else "Play history & extra rules ▾", color = Accent, fontSize = 13.sp)
                }
                if (moreOpen) {
                    num("Played at least", s.minPlays, 0..50, 1, { if (it == 0) "any" else "$it times" }) { s = s.copy(minPlays = it) }
                    num("Not played for", s.notPlayedDays, 0..365, 5, { if (it == 0) "any" else "$it days" }) { s = s.copy(notPlayedDays = it) }
                    num("Added in the last", s.addedDays, 0..365, 5, { if (it == 0) "any time" else "$it days" }) { s = s.copy(addedDays = it) }
                }
            }
        },
        confirmButton = {
            TextButton({
                val autoName = s.name.trim().ifBlank { s.summary().replaceFirstChar { it.uppercase() }.take(40) }
                SmartLists.put(ctx, s.copy(name = autoName))
                SmartLists.editing.value = null
            }) { Text("Save", color = Accent) }
        },
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
                // test 312 (Jarrod): a slider in 5% steps with − and + buttons
                Spacer(Modifier.height(10.dp))
                fun step(v: Float) = Playback.setSpeed(ctx, (Math.round(v.coerceIn(0.5f, 2f) * 20) / 20f))
                Text(if (cur == 1f) "Normal (100%)" else "${Math.round(cur * 100)}%", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilledTonalIconButton({ step(cur - 0.05f) }, enabled = cur > 0.5f) { Text("−", fontSize = 22.sp) }
                    Slider(cur, { step(it) }, Modifier.weight(1f).padding(horizontal = 4.dp), valueRange = 0.5f..2f, steps = 29,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                    androidx.compose.material3.FilledTonalIconButton({ step(cur + 0.05f) }, enabled = cur < 2f) { Text("+", fontSize = 22.sp) }
                }
                if (cur != 1f) TextButton({ Playback.setSpeed(ctx, 1f) }, Modifier.align(Alignment.CenterHorizontally)) { Text("↺ Back to normal", color = Accent) }
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
            Text(if (Playback.speed.value == 1f) "Normal" else "${Math.round(Playback.speed.value * 100)}%", color = Accent, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(1.dp))
    }
}
