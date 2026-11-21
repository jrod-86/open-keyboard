// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Test 313: Now Playing ⋮ > Stems. Split the song, then turn vocals, drums, bass and other up or down while it plays. */
@Composable
internal fun StemsPage(song: Song, ui: PlayerUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var refresh by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val r = refresh + Stems.changed.value + (ui.current?.hashCode() ?: 0)
    val installed = remember(refresh) { Stems.installed(ctx) }
    val split = remember(refresh, song.id) { Stems.has(ctx, song.id) }
    val on = Stems.modeOn.value
    val busy = Stems.progress.value
    var exporting by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val isPlayingThis = ui.current?.mediaId == song.id.toString()

    @Suppress("UNUSED_VARIABLE") val unused = isPlayingThis
    fun turnOn() { Stems.setMode(ctx, true); refresh++ }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Stems", onBack) }
        item {
            Text(song.title, Modifier.padding(horizontal = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(song.artist, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim, maxLines = 1)
            Spacer(Modifier.height(8.dp))
        }
        // 1. the splitter
        if (!installed || !split) item {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.10f)).padding(14.dp)) {
                if (!installed) {
                    Text("Get the stem splitter", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("An AI that hears the singer, drums, bass and everything else apart (Spleeter by Deezer, open source). " +
                        "A one-time ${Stems.TOTAL_MB} MB download. It runs on your phone, nothing is uploaded.", fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                    if (busy == null) Button({ Stems.download(ctx) { refresh++ } }, Modifier.padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Download (${Stems.TOTAL_MB} MB)") }
                } else {
                    Text("Split this song", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Takes about a minute or two for a 4-minute song. You can keep listening while it works. The 5 songs you split last are kept.",
                        fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                    if (busy == null) Button({ Stems.split(ctx, song) { refresh++ }; Stems.setMode(ctx, true) },
                        Modifier.padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("✂️ Split into stems") }
                }
                if (busy != null) {
                    LinearProgressIndicator(progress = { busy }, Modifier.fillMaxWidth().padding(top = 10.dp), color = Accent)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Stems.status.value, Modifier.weight(1f), fontSize = 12.sp, color = dim)
                        TextButton({ Stems.cancel() }) { Text("Stop", color = Accent) }
                    }
                } else if (Stems.status.value.isNotBlank()) Text(Stems.status.value, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Accent)
            }
        }
        if (installed && (split || on)) {
            // 2. stem mode on or off, for everything you play (test 314)
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stem mode (all songs)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (on) "On for every song you play, with these sliders. Each song gets split in the background, the next one while this one plays."
                            else "Off: every song plays as normal", fontSize = 12.sp, color = dim)
                    }
                    Switch(on, { Stems.setMode(ctx, it); refresh++ }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
                if (on && !split) Text(if (Stems.working.value == song.id) "Splitting this song now. It switches over by itself when it's ready."
                    else "This song is waiting its turn to be split. Until then it plays as normal.",
                    Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Accent)
                if (on && busy != null && Stems.working.value != null && Stems.working.value != song.id) {
                    val other = Library.cached?.byId?.get(Stems.working.value!!)
                    Text("Getting “${other?.title ?: "the next song"}” ready… ${(busy * 100).toInt()}%", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
                }
            }
            // 3. quick picks
            item {
                Text("Quick picks", Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 13.sp, color = dim)
                val picks = listOf(
                    "🎤 Karaoke" to floatArrayOf(0f, 1f, 1f, 1f), "🗣️ Vocals only" to floatArrayOf(1f, 0f, 0f, 0f),
                    "🥁 Drums only" to floatArrayOf(0f, 1f, 0f, 0f), "🎸 Bass only" to floatArrayOf(0f, 0f, 1f, 0f),
                    "🚫 No drums" to floatArrayOf(1f, 0f, 1f, 1f), "🎧 No bass" to floatArrayOf(1f, 1f, 0f, 1f),
                    "🎹 Music only" to floatArrayOf(0f, 0f, 0f, 1f), "🔊 Vocals up" to floatArrayOf(1.5f, 0.8f, 0.8f, 0.8f),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(picks) { (label, v) ->
                        FilterChip((0 until 4).all { Stems.gains[it] == v[it] }, { Stems.setAll(*v); if (!on) turnOn() }, { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)))
                    }
                }
            }
            // 4. a slider for each stem
            items(4) { i ->
                val g = Stems.gains[i]
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Stems.LABELS[i], Modifier.weight(1f).padding(start = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (g == 0f) "Off" else "${Math.round(g * 100)}%", fontSize = 15.sp, color = if (g == 1f) dim else Accent, fontWeight = FontWeight.SemiBold)
                        TextButton({ Stems.setGain(i, if (g == 0f) 1f else 0f); if (!on) turnOn() }) { Text(if (g == 0f) "Unmute" else "Mute", color = Accent) }
                        TextButton({ for (k in 0 until 4) Stems.setGain(k, if (k == i) 1f else 0f); if (!on) turnOn() }) { Text("Solo", color = Accent) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton({ Stems.setGain(i, g - 0.05f); if (!on) turnOn() }, enabled = g > 0f) { Text("−", fontSize = 22.sp) }
                        Slider(g, { Stems.setGain(i, it) }, Modifier.weight(1f).padding(horizontal = 4.dp), valueRange = 0f..1.5f, steps = 29,
                            onValueChangeFinished = { if (!on) turnOn() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                        FilledTonalIconButton({ Stems.setGain(i, g + 0.05f); if (!on) turnOn() }, enabled = g < 1.5f) { Text("+", fontSize = 22.sp) }
                    }
                }
            }
            // 5. back to normal, export
            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Button({ Stems.reset(ctx); refresh++; Toast.makeText(ctx, "Back to normal: the original song", Toast.LENGTH_SHORT).show() },
                        Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("↺  Back to normal", fontSize = 16.sp) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ asking = true }, Modifier.fillMaxWidth(), enabled = !exporting) { Text(if (exporting) "Saving…" else "💾 Export", color = Accent) }
                    Text("100% is as recorded. Up to 150% makes a part louder. Your music files are never changed: split copies are kept separately, about 42 MB per minute of song, up to 1.5 GB (${Stems.usedMb(ctx)} MB now).",
                        Modifier.padding(top = 8.dp), fontSize = 12.sp, color = dim)
                    TextButton({ Stems.deleteAll(ctx); refresh++ }) { Text("Delete all split songs", color = dim) }
                }
            }
        }
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false },
        title = { Text("Export") },
        text = { Text("Save your mix (with the sliders as they are now) as one file, or each stem as its own file. They go in Downloads › Open Waves stems as WAV.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                fun go(each: Boolean) {
                    asking = false; exporting = true
                    scope.launch {
                        val msg = runCatching { Stems.export(ctx, song, each) }.getOrElse { "Couldn't save: ${it.message}" }
                        exporting = false
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }
                TextButton({ go(false) }) { Text("My mix (one file)", color = Accent) }
                TextButton({ go(true) }) { Text("Each stem (4 files)", color = Accent) }
                TextButton({ asking = false }) { Text("Cancel") }
            }
        })
    Spacer(Modifier.width(0.dp))
}
