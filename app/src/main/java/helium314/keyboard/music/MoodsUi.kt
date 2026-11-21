// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Moods tab shows, kept while you swipe to other tabs. */
private object MoodState {
    var mix by mutableStateOf<List<Song>?>(null)
    var title by mutableStateOf("")
    var moodId by mutableStateOf<String?>(null)
    var lift by mutableFloatStateOf(0f)
    var genre by mutableStateOf("")
    var popularFirst by mutableStateOf(false)
    var feeling by mutableStateOf("")
    var last: MoodView? = null
}

private class MoodView(val lib: Library, val version: Int, val placed: List<Placed>, val left: Int, val genres: List<String>)

/**
 * Open Waves test 299 (Jarrod): the Moods tab. Tell it how you feel or tap a mood, nudge it calmer or livelier,
 * add a genre if you like, and it plays a mix from your own music that flows from song to song.
 */
@Composable
internal fun MoodsTab(lib: Library, ui: PlayerUi, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val version = MoodScan.version.intValue + PopScan.version.intValue
    val running = MoodScan.running.value
    val view by produceState(MoodState.last?.takeIf { it.lib === lib && it.version == version }, lib, version) {
        if (value?.lib === lib && value?.version == version) return@produceState
        value = withContext(Dispatchers.Default) {
            val songs = lib.uniqueSongs
            val placed = Moods.place(ctx, songs)
            val counts = placed.groupingBy { it.group }.eachCount()
            MoodView(lib, version, placed, Moods.left(ctx, songs), Moods.groups.filter { (counts[it] ?: 0) >= 5 })
        }.also { MoodState.last = it }
    }
    var thinking by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf("") }
    val placed = view?.placed.orEmpty()
    val ready = placed.size >= 20

    fun play(ask: Moods.Ask, title: String) {
        val songs = Moods.mix(ctx, placed, ask)
        if (songs.isEmpty()) { Toast.makeText(ctx, "No songs fit that yet", Toast.LENGTH_SHORT).show(); return }
        MoodState.mix = songs; MoodState.title = title; MoodState.moodId = ask.mood?.id; saved = ""
        ui.play(songs, 0)
    }
    fun current(mood: Moods.Ask? = null) = Moods.Ask(mood?.mood ?: MoodState.moodId?.let(Moods::byId), MoodState.lift, MoodState.genre, MoodState.popularFirst && MusicSettings.popularOn)
    fun feel() {
        val text = MoodState.feeling.trim()
        if (text.isEmpty() || !ready) return
        focus.clearFocus()
        thinking = true
        scope.launch {
            val ask = Moods.understand(ctx, text)
            MoodState.lift = ask.lift
            if (ask.genre.isNotEmpty()) MoodState.genre = ask.genre
            thinking = false
            play(Moods.Ask(ask.mood, ask.lift, ask.genre.ifEmpty { MoodState.genre }, MoodState.popularFirst && MusicSettings.popularOn), ask.title.ifBlank { "Your mix" })
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = TabScroll.moods, contentPadding = PaddingValues(bottom = 16.dp)) {
        // 1. getting the songs ready
        val left = view?.left ?: 0
        if (view != null && (left > 0 || running)) item {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.10f)).padding(14.dp)) {
                val total = MoodScan.total.intValue.takeIf { it > 0 } ?: lib.uniqueSongs.size
                if (running) {
                    val done = MoodScan.done.intValue
                    Text("Listening to your songs: $done of $total", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    LinearProgressIndicator(progress = { done.toFloat() / maxOf(total, 1) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Accent)
                    Text(MoodScan.status.value.ifBlank { "About ${maxOf(1, (total - done) * 3 / 120)} min left. Moods work as soon as 20 songs are ready." }, fontSize = 12.sp, color = dim)
                    OutlinedButton({ MoodScan.pause(ctx) }, Modifier.padding(top = 8.dp)) { Text("Pause", color = Accent) }
                } else {
                    val started = placed.isNotEmpty()
                    Text(if (started) "$left songs still to get ready" else "Get your music ready for Moods", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Open Waves listens to each song once, on your phone (tempo, energy, how bright or mellow). About ${maxOf(1, left * 3 / 120)} min, " +
                        "in the background. Pause any time, nothing is lost.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                    MoodScan.status.value.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Accent) }
                    var charging by remember { mutableStateOf(MoodScan.chargingOnly(ctx)) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ MoodScan.start(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (started) "Resume" else "Start") }
                        Spacer(Modifier.weight(1f))
                        Text("Only while charging", fontSize = 13.sp)
                        Switch(charging, { charging = it; MoodScan.setChargingOnly(ctx, it) }, Modifier.padding(start = 6.dp), colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                    }
                }
            }
        }
        if (view != null && !ready) item {
            Text("Moods start working once 20 songs are ready (${placed.size} so far).", Modifier.padding(16.dp), color = dim, fontSize = 14.sp)
        }
        if (ready) {
            // 2. how are you feeling?
            item {
                Text("How are you feeling?", Modifier.padding(start = 16.dp, top = 10.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(MoodState.feeling, { MoodState.feeling = it }, Modifier.weight(1f), placeholder = { Text("e.g. tired, want something chill but not sleepy (type or use the keyboard's mic)", fontSize = 13.sp) },
                        maxLines = 3, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { feel() }))
                    Spacer(Modifier.width(8.dp))
                    Button({ feel() }, enabled = !thinking && MoodState.feeling.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (thinking) "…" else "Go") }
                }
                if (thinking) Text("Working out the right music…", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Accent)
            }
            // 3. the mix you're playing
            MoodState.mix?.let { mix ->
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(MoodState.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${mix.size} songs · ${Moods.liftLabel(MoodState.lift)}" + (if (MoodState.genre.isNotEmpty()) " · ${MoodState.genre}" else ""), fontSize = 12.sp, color = dim)
                        }
                        androidx.compose.material3.TextButton({ MoodState.mix = null }) { Text("✕", color = dim) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ ui.play(mix, 0) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Play") }
                        OutlinedButton({ ui.play(mix, 0, true) }) { Text("Shuffle", color = Accent) }
                        OutlinedButton({ Playlists.create(ctx, MoodState.title, mix.map { it.id }); saved = "Saved as playlist “${MoodState.title}”" }) { Text("Save", color = Accent) }
                    }
                    if (saved.isNotEmpty()) Text(saved, Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Accent)
                    Text("Tap ✕ on a song that doesn't fit. It won't come back in this mood, and songs like it drop down.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 11.sp, color = dim)
                }
                itemsIndexed(mix.take(60), key = { i, s -> "mix/${s.id}/$i" }) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SongRow(s, ui, lib, open) { ui.play(mix, i) } }
                        IconButton({
                            MoodState.moodId?.let { Moods.remove(ctx, it, s.id) }
                            MoodState.mix = mix.filterIndexed { k, _ -> k != i }
                        }) { Text("✕", color = dim, fontSize = 16.sp) }
                    }
                }
            }
            // 4. the moods
            item {
                val (label, mood) = Moods.suggestion(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                Text("Moods", Modifier.padding(start = 16.dp, top = 16.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MoodTile(label, mood.color, "Suggested for now", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 76.dp)) {
                    play(current(Moods.Ask(mood)), label.substringAfter(' '))
                }
            }
            items(Moods.all.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { m ->
                        MoodTile("${m.emoji} ${m.name}", m.color, null, Modifier.weight(1f).heightIn(min = 76.dp)) { play(current(Moods.Ask(m)), m.name + if (MoodState.genre.isNotEmpty()) " · ${MoodState.genre}" else "") }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // 5. calmer or livelier
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("😌 Calm", fontSize = 13.sp, color = dim)
                    Slider(MoodState.lift, { MoodState.lift = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = -1f..1f,
                        onValueChangeFinished = { MoodState.moodId?.let { id -> if (MoodState.mix != null) play(current(Moods.Ask(Moods.byId(id))), MoodState.title) } },
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                    Text("Hype 🔥", fontSize = 13.sp, color = dim)
                }
                Text(Moods.liftLabel(MoodState.lift), Modifier.fillMaxWidth().padding(horizontal = 16.dp), fontSize = 12.sp, color = Accent,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (MusicSettings.popularOn) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Popular in this mood", fontSize = 15.sp)
                        Text("Best known songs first (uses the Popular tab's numbers)", fontSize = 12.sp, color = dim)
                    }
                    Switch(MoodState.popularFirst, { MoodState.popularFirst = it }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
            }
            // 6. genres
            val genres = view?.genres.orEmpty()
            if (genres.isNotEmpty()) item {
                Text("Genres", Modifier.padding(start = 16.dp, top = 12.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(if (MoodState.genre.isEmpty()) "Tap one to mix it with a mood, or play it on its own" else "Moods now play ${MoodState.genre} only. Tap again to clear.",
                    Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { g ->
                        FilterChip(MoodState.genre == g, { MoodState.genre = if (MoodState.genre == g) "" else g }, { Text(g) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
                if (MoodState.genre.isNotEmpty()) OutlinedButton({ play(Moods.Ask(null, MoodState.lift, MoodState.genre), MoodState.genre) }, Modifier.padding(horizontal = 12.dp)) {
                    Text("Play all ${MoodState.genre}", color = Accent)
                }
            } else if (placed.isNotEmpty()) item {
                Text("Genres show here once songs have genre info (from your music files, or the Popular tab's lookup).",
                    Modifier.padding(16.dp), fontSize = 12.sp, color = dim)
            }
        }
    }
}

@Composable
private fun MoodTile(label: String, color: Long, sub: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Color(color)
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f)))).clickable { onClick() }.padding(14.dp),
        contentAlignment = Alignment.CenterStart) {
        Column {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}
