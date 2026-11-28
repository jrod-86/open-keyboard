// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Test 295: the "Sort by popularity" preview. Looks the songs up, shows the new order with play counts and chart spots
 * as they come in, and only changes the playlist when you press Apply.
 */
@Composable
internal fun PopularitySort(songs: List<Song>, onApply: (List<Song>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pops by remember { mutableStateOf<Map<String, Pop>>(emptyMap()) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(1) }
    var finished by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ok = Popularity.lookUp(ctx, songs) { d, t, r -> done = d; total = maxOf(t, 1); pops = r }
        offline = !ok
        finished = true
    }
    val ordered = if (finished) Popularity.order(songs, pops) else songs
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val notFound = if (finished) songs.count { pops[Popularity.key(it)]?.found != true } else 0

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
            Text("Sort by popularity", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !finished -> "Looking up songs… ${minOf(done, total)} of $total" + if (total > 1) " (about ${maxOf(1, (total - done))} s left)" else ""
                    offline -> "Couldn't reach the music sites. Check your internet and try again."
                    notFound > 0 -> "Most played first, from ListenBrainz. $notFound not found, kept at the bottom."
                    else -> "Most played first, from ListenBrainz. Chart spots from Wikipedia."
                },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = if (offline) Accent else dim,
            )
            if (!finished) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Accent)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(ordered, key = { i, s -> "${s.id}/$i" }) { i, s ->
                    val p = pops[Popularity.key(s)]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (finished && p?.found == true) "${i + 1}" else "", Modifier.width(30.dp), fontSize = 14.sp, color = Accent, fontWeight = FontWeight.Bold)
                        Cover(s, 120, Modifier.size(44.dp), corner = 6)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            when {
                                p == null -> Text(if (finished) "not found" else "…", fontSize = 12.sp, color = dim)
                                !p.found -> Text("not found", fontSize = 12.sp, color = dim)
                                else -> {
                                    if (p.plays > 0) Text(Popularity.plays(p.plays), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    p.peaks().takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = Accent) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClose, Modifier.weight(1f)) { Text("Cancel", color = Accent) }
                Button({ onApply(ordered) }, Modifier.weight(1f), enabled = finished && !offline && notFound < songs.size,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Apply") }
            }
        }
    }
}

/** One entry on the Popular tab. */
private class PopView(val lib: Library, val version: Int, val ranked: List<Ranked>, val genres: List<String>, val left: Int, val unique: Int) {
    companion object { @Volatile var last: PopView? = null }
}

private class Ranked(val song: Song, val pop: Pop, val best: Int, val year: Int, val badge: String?, val genre: String)

private val popFilters = listOf("All", "Chart hits", "#1s", "Top 20s", "Top 20–50", "Hidden gems")
private val popLengths = listOf(15 to "Short · 15", 30 to "Medium · 30", 60 to "Long · 60", -1 to "All")
private val popSorts = listOf("random" to "Mixed up", "popular" to "Popular", "newest" to "Newest", "oldest" to "Oldest", "az" to "A–Z")

/**
 * Test 297 (Jarrod): the Popular tab. Your whole library, most played in the world first. The lookup runs in the
 * background in chunks and can be paused and resumed without losing progress. Only this tab uses the popularity order.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PopularTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val version = PopScan.version.intValue
    val running = PopScan.running.value
    // keep re-sorting while the lookup runs
    LaunchedEffect(running) { while (PopScan.running.value) { kotlinx.coroutines.delay(15_000); PopScan.version.intValue++ } }
    val songs = lib.uniqueSongs
    // test 298 (Jarrod: swiping between tabs got glitchy): everything is worked out away from the screen, and kept,
    // so swiping onto this tab again shows the last result straight away instead of working it all out again
    val view by androidx.compose.runtime.produceState(PopView.last?.takeIf { it.lib === lib && it.version == version }, lib, version) {
        if (value?.lib === lib && value?.version == version) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            PlayStats.load(ctx)
            val unique = songs.distinctBy { Popularity.key(it) }.size
            val left = PopScan.left(ctx, songs)
            val found = songs.mapNotNull { s -> Popularity.known(ctx, s)?.takeIf { it.found }?.let { s to it } }.distinctBy { Popularity.key(it.first) }
            val genreMap = Moods.genres(ctx, found.map { it.first })
            val worldSorted = found.map { it.second.plays }.filter { it > 0 }.sorted()
            val median = worldSorted.getOrElse(worldSorted.size / 2) { 0L }
            val top10 = worldSorted.getOrElse((worldSorted.size * 0.9).toInt()) { Long.MAX_VALUE }
            val rankedList = found.map { (s, p) ->
                val best = listOf(p.us, p.au, p.uk).filter { it > 0 }.minOrNull() ?: 0
                val mine = PlayStats.count(s.id)
                val badge = when {
                    mine >= 5 && p.plays in 1 until median -> "❤️ Your hidden gem: you love it, few people know it"
                    p.plays >= top10 && worldSorted.size >= 20 && mine <= 1 -> "🌍 A big hit you hardly play"
                    else -> null
                }
                Ranked(s, p, best, if (p.year > 0) p.year else s.year, badge, genreMap[s.id].orEmpty())
            }.sortedWith(compareBy<Ranked>({ if (it.pop.plays > 0) 0 else 1 }, { -it.pop.plays }, { if (it.best > 0) it.best else Int.MAX_VALUE }))
            val genres = rankedList.map { it.genre }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.map { it.key }
            PopView(lib, version, rankedList, genres, left, unique)
        }.also { PopView.last = it }
    }
    val ranked = view?.ranked
    val genres = view?.genres.orEmpty()
    val prefs = remember { ctx.getSharedPreferences("music", android.content.Context.MODE_PRIVATE) }
    var filter by remember { mutableStateOf("All") }
    var genreFilter by remember { mutableStateOf("") }
    var popLen by remember { mutableStateOf(prefs.getInt("pop_len", 30)) }
    var popSort by remember { mutableStateOf(prefs.getString("pop_sort", "random") ?: "random") }
    var mixSeed by remember { mutableStateOf(System.nanoTime()) }
    val list = ranked.orEmpty()
    val hasPre70 = remember(list) { list.any { it.year in 1..1969 } }
    val decades = remember(list) { list.map { it.year / 10 * 10 }.filter { it >= 1970 }.distinct().sorted() }
    val filtered = remember(list, filter, genreFilter) {
        val byChart = when (filter) {
            "Chart hits" -> list.filter { it.best > 0 }
            "#1s" -> list.filter { it.best == 1 }
            "Top 20s" -> list.filter { it.best in 1..20 }
            "Top 20–50" -> list.filter { it.best in 20..50 }
            "Hidden gems" -> list.filter { it.pop.plays > 0 }.sortedBy { it.pop.plays }
            "Before 1970s" -> list.filter { it.year in 1..1969 }
            else -> filter.removeSuffix("s").toIntOrNull()?.let { d -> list.filter { it.year / 10 * 10 == d } } ?: list
        }
        if (genreFilter.isEmpty()) byChart else byChart.filter { it.genre == genreFilter }
    }
    fun buildMix(len: Int, sort: String, seed: Long): List<Ranked> {
        val picked = if (len > 0 && filtered.size > len) filtered.shuffled(java.util.Random(seed)).take(len)
            else filtered.shuffled(java.util.Random(seed))
        return when (sort) {
            "popular" -> if (filter == "Hidden gems") picked.sortedBy { it.pop.plays }
                else picked.sortedWith(compareBy<Ranked>({ if (it.pop.plays > 0) 0 else 1 }, { -it.pop.plays }, { if (it.best > 0) it.best else Int.MAX_VALUE }))
            "newest" -> picked.sortedWith(compareByDescending<Ranked> { it.year }.thenByDescending { it.song.dateAdded })
            "oldest" -> picked.sortedWith(compareBy<Ranked> { if (it.year > 0) it.year else Int.MAX_VALUE }.thenBy { it.song.dateAdded })
            "az" -> picked.sortedBy { it.song.title.lowercase() }
            else -> picked
        }
    }
    val shown = remember(filtered, popLen, popSort, mixSeed) { buildMix(popLen, popSort, mixSeed) }
    val left = view?.left ?: 0
    val unique = view?.unique ?: 0
    var wifiOnly by remember { mutableStateOf(PopScan.wifiOnly(ctx)) }
    var saved by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), state = TabScroll.popular) {
        // the lookup card: start, progress with Pause, or resume
        if (view != null && (left > 0 || running)) item {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(Accent.copy(alpha = 0.10f)).padding(14.dp)) {
                val total = PopScan.total.intValue.takeIf { it > 0 } ?: songs.size
                if (running) {
                    val done = PopScan.done.intValue
                    val mins = maxOf(1, (total - done) * 11 / 600)
                    Text("Looking up $done of $total songs", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    LinearProgressIndicator(progress = { done.toFloat() / maxOf(total, 1) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
                    Text(PopScan.status.value.ifBlank { "About $mins min left. Runs in the background, the list fills in as it goes." }, fontSize = 12.sp, color = dim)
                    OutlinedButton({ PopScan.pause(ctx) }, Modifier.padding(top = 8.dp)) { Text("Pause", color = Accent) }
                } else {
                    val started = list.isNotEmpty() || left < unique
                    val mins = maxOf(1, left * 11 / 600)
                    Text(if (started) "$left songs still to look up" else "Rank your whole library by popularity", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text((if (started) "Carries on where it stopped. " else "Play counts from ListenBrainz and chart spots from Wikipedia. ") +
                        "About $mins min, in the background. You can pause any time and nothing is lost.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                    PopScan.status.value.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Accent, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ PopScan.start(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (started) "Resume" else "Start") }
                        Spacer(Modifier.weight(1f))
                        Text("Wi-Fi only", fontSize = 13.sp)
                        androidx.compose.material3.Switch(wifiOnly, { wifiOnly = it; PopScan.setWifiOnly(ctx, it) }, Modifier.padding(start = 6.dp),
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent))
                    }
                }
            }
        }
        if (list.isNotEmpty()) {
            // test 357 (Jarrod): transparent filter bar matching the rest of the app + Genres row right beneath it
            item(key = "filters") {
                Column(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(popFilters + (if (hasPre70) listOf("Before 1970s") else emptyList()) + decades.map { "${it}s" }) { f ->
                            androidx.compose.material3.FilterChip(f == filter, { filter = f; mixSeed = System.nanoTime(); saved = "" }, { Text(if (f.length == 5 && f[0].isDigit()) "'" + f.substring(2) else f) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                        }
                    }
                    if (genres.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                androidx.compose.material3.FilterChip(genreFilter.isEmpty(), { genreFilter = ""; mixSeed = System.nanoTime(); saved = "" }, { Text("All genres") },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                            }
                            items(genres) { g ->
                                androidx.compose.material3.FilterChip(genreFilter == g, { genreFilter = if (genreFilter == g) "" else g; mixSeed = System.nanoTime(); saved = "" }, { Text(g) },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                            }
                        }
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({ ui.play(shown.map { it.song }, 0) }, enabled = shown.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Play")
                    }
                    OutlinedButton({
                        val s = System.nanoTime(); mixSeed = s
                        val fresh = buildMix(popLen, popSort, s)
                        ui.play(fresh.map { it.song }, 0)
                    }, enabled = filtered.isNotEmpty()) { Text("Shuffle", color = Accent) }
                    OutlinedButton({
                        val label = when {
                            genreFilter.isEmpty() -> filter
                            filter == "All" -> genreFilter
                            else -> "$filter · $genreFilter"
                        }
                        val name = "Popular: $label"
                        Playlists.create(ctx, name, shown.map { it.song.id })
                        saved = "Saved as playlist “$name”"
                    }, enabled = shown.isNotEmpty()) { Text("Save", color = Accent) }
                    OutlinedButton({
                        val label = when {
                            genreFilter.isEmpty() -> if (filter == "All") "Popular mix" else filter
                            filter == "All" -> genreFilter
                            else -> "$filter · $genreFilter"
                        }
                        val chart = if (filter in listOf("Chart hits", "#1s", "Top 20s", "Top 20–50", "Hidden gems")) filter else ""
                        val decs = if (filter == "Before 1970s" || (filter.endsWith("s") && filter.removeSuffix("s").toIntOrNull() != null)) listOf(filter) else emptyList()
                        val gens = if (genreFilter.isNotEmpty()) listOf(genreFilter) else emptyList()
                        SmartLists.editing.value = SmartList(
                            id = System.currentTimeMillis(),
                            name = label,
                            chart = chart,
                            decades = decs,
                            genres = gens,
                            sort = popSort,
                            limit = if (popLen > 0) popLen else 0,
                        )
                    }, enabled = filtered.isNotEmpty()) { Text("⚡ Smart", color = Accent) }
                }
                if (saved.isNotEmpty()) Text(saved, Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Accent)
                Text("How long", Modifier.padding(start = 16.dp, top = 6.dp), fontSize = 13.sp, color = dim)
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(popLengths) { (n, label) ->
                        androidx.compose.material3.FilterChip(popLen == n, {
                            popLen = n; prefs.edit().putInt("pop_len", n).apply()
                            val s = System.nanoTime(); mixSeed = s
                            val fresh = buildMix(n, popSort, s)
                            if (fresh.isNotEmpty()) ui.play(fresh.map { it.song }, 0)
                        }, { Text(if (n == -1) "All · ${filtered.size}" else label) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
                Text("Sort by", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 13.sp, color = dim)
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(popSorts) { (id, label) ->
                        androidx.compose.material3.FilterChip(popSort == id, {
                            popSort = id; prefs.edit().putString("pop_sort", id).apply()
                            val s = if (id == "random") System.nanoTime() else mixSeed
                            mixSeed = s
                            val fresh = buildMix(popLen, id, s)
                            if (fresh.isNotEmpty()) ui.play(fresh.map { it.song }, 0)
                        }, { Text(label) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
            }
            if (shown.isEmpty()) item { Text("No songs here yet.", Modifier.padding(16.dp), color = dim) }
            itemsIndexed(shown, key = { _, r -> r.song.id }) { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.padding(start = 10.dp).width(30.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = if (i < 3 && filter != "Hidden gems") Accent else dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.offset(x = (-10).dp)) { SongRow(r.song, ui, lib, open) { ui.play(shown.map { it.song }, i) } }
                        val line = listOfNotNull(r.pop.story(), r.badge).joinToString("\n")
                        if (line.isNotEmpty()) Text(line, Modifier.padding(start = 66.dp, end = 16.dp, bottom = 6.dp).offset(y = (-6).dp),
                            fontSize = 12.sp, color = dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else if (ranked != null && !running && left == 0) item { Text("None of your songs were found online.", Modifier.padding(16.dp), color = dim) }
        val notFound = unique - left - list.size
        if (list.isNotEmpty() && notFound > 0) item {
            Text("$notFound songs weren't found online, so they aren't ranked.", Modifier.padding(16.dp), fontSize = 12.sp, color = dim)
        }
    }
}
