// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** Music's own settings (Jarrod, test 234): beat effects. */
object MusicSettings {
    val coverEffects = listOf("Still", "Pulse", "Bounce", "Glow", "Wobble")
    val backgroundEffects = listOf("Classic", "Beat rings", "Particles", "Bars", "Disco lights")
    var effectsOn by mutableStateOf(true)
    var cover by mutableIntStateOf(1)
    var background by mutableIntStateOf(1)
    var strength by mutableFloatStateOf(1f)
    /** Customise (test 236, Jarrod): cover size (share of the screen width) and play button size */
    var coverSize by mutableFloatStateOf(0.5f)
    var buttonSize by mutableFloatStateOf(1f)
    /** mini player size (test 238, Jarrod: "way too small") */
    var miniSize by mutableFloatStateOf(1.25f)
    /** Apple-style blurred moving cover behind Now Playing (off = Samsung look) */
    var blurBackground by mutableStateOf(false)
    /** Themes (test 239): -1 plain, 0..19 colour, 100+ FX (see WavesThemes) */
    var theme by mutableIntStateOf(-1)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        val p = prefs(ctx)
        effectsOn = p.getBoolean("fx_on", true); cover = p.getInt("fx_cover", 1); background = p.getInt("fx_bg2", 0); strength = p.getFloat("fx_strength", 1f)
        coverSize = p.getFloat("look_cover3", 0.5f); buttonSize = p.getFloat("look_buttons", 1f)
        miniSize = p.getFloat("look_mini", 1.25f); blurBackground = p.getBoolean("look_blur", false); theme = p.getInt("look_theme", -1)
        if (background == 2) background = 0 // Particles removed (test 240, Jarrod didn't like it)
    }
    fun save(ctx: Context) {
        prefs(ctx).edit().putBoolean("fx_on", effectsOn).putInt("fx_cover", cover).putInt("fx_bg2", background).putFloat("fx_strength", strength)
            .putFloat("look_cover3", coverSize).putFloat("look_buttons", buttonSize)
            .putFloat("look_mini", miniSize).putBoolean("look_blur", blurBackground).putInt("look_theme", theme).apply()
    }
}

/** The song's position, updated every frame while beat effects show. Read it only while drawing, so only drawing repeats. */
class BeatClock { var ms by mutableLongStateOf(0L) }

@Composable
fun rememberBeatClock(ui: PlayerUi, running: Boolean): BeatClock {
    val clock = remember { BeatClock() }
    LaunchedEffect(running) {
        while (running) { withFrameNanos { clock.ms = ui.now() } }
    }
    return clock
}

/** Loads (or works out, once) the beats of the song that's playing. */
@Composable
fun rememberBeatMap(song: Song?): BeatMap? {
    val ctx = LocalContext.current
    var map by remember(song?.id) { mutableStateOf<BeatMap?>(null) }
    LaunchedEffect(song?.id, MusicSettings.effectsOn) {
        if (song == null || song.durationMs <= 0 || !MusicSettings.effectsOn) return@LaunchedEffect
        map = withContext(Dispatchers.IO) { BeatMap.cached(ctx, song.id) } ?: BeatMap.analyze(ctx, song)
    }
    return map
}

/** How much the cover moves right now: scale, sideways tilt, up-down, glow (0..1). */
data class CoverMove(val scale: Float, val rotation: Float, val lift: Float, val glow: Float)

fun coverMove(map: BeatMap?, ms: Long): CoverMove {
    if (map == null || !MusicSettings.effectsOn) return CoverMove(1f, 0f, 0f, 0f)
    val k = MusicSettings.strength
    val p = map.pulse(ms) * k
    val b = map.bass(ms) * k
    return when (MusicSettings.cover) {
        1 -> CoverMove(1f + 0.055f * p + 0.025f * b, 0f, 0f, 0f)
        2 -> CoverMove(1f + 0.02f * p, 0f, -1f * p, 0f)
        3 -> CoverMove(1f + 0.015f * p, 0f, 0f, (0.35f * b + 0.65f * p).coerceIn(0f, 1f))
        4 -> CoverMove(1f + 0.03f * p, (if (map.lastBeat(ms) % 2 == 0) 1 else -1) * 3.5f * p, 0f, 0f)
        else -> CoverMove(1f, 0f, 0f, 0f)
    }
}

private class Spark(val x: Float, val speed: Float, val size: Float, val phase: Float)

/**
 * Effects drawn over the moving background, in time with the music. [coverCenterY] is where the cover sits (0..1 of height).
 */
@Composable
fun BeatBackground(map: BeatMap?, clock: BeatClock, tint: Color, coverCenterY: Float) {
    if (map == null || !MusicSettings.effectsOn) return
    val sparks = remember { val r = java.util.Random(7); List(70) { Spark(r.nextFloat(), 0.4f + r.nextFloat(), 1.5f + r.nextFloat() * 4f, r.nextFloat() * 1000f) } }
    val disco = remember { listOf(Color(0xFFFF2D95), Color(0xFF00E5FF), Color(0xFFFFEA00), Color(0xFF7C4DFF), Color(0xFF00E676), Color(0xFFFF6D00)) }
    Canvas(Modifier.fillMaxSize()) {
        val ms = clock.ms
        val k = MusicSettings.strength
        val p = map.pulse(ms) * k
        val bass = map.bass(ms)
        val level = map.level(ms)
        val cx = size.width / 2; val cy = size.height * coverCenterY
        // every style: a soft flash of light on each beat
        drawRect(Color.White.copy(alpha = (0.07f * p).coerceIn(0f, 0.15f)))
        when (MusicSettings.background) {
            1 -> { // rings that spread out from the cover on every beat
                val i = map.lastBeat(ms)
                for (n in 0 until 5) {
                    val b = i - n; if (b < 0) break
                    val age = (ms - map.beats[b]) / 1000f
                    if (age > 2.2f) continue
                    val r = size.width * (0.42f + age * 0.55f)
                    val a = ((1f - age / 2.2f) * 0.45f * k).coerceIn(0f, 0.6f)
                    drawCircle(Color.White.copy(alpha = a), r, Offset(cx, cy), style = Stroke(width = (6f - age * 2f).coerceAtLeast(1f).dp.toPx()))
                }
            }
            2 -> { // floating sparks that grow and brighten with the bass
                val t = ms / 1000f
                sparks.forEach { s ->
                    val y = (1.1f - ((t * 0.05f * s.speed * (1f + level)) + s.phase) % 1.2f) * size.height
                    val x = (s.x + 0.03f * sin(t * s.speed + s.phase)) * size.width
                    val rad = (s.size * (1f + 1.6f * bass * k)).dp.toPx()
                    drawCircle(Color.White.copy(alpha = (0.25f + 0.6f * bass * k).coerceIn(0f, 0.9f)), rad, Offset(x, y))
                }
            }
            3 -> { // bars along the bottom, like a music meter
                val n = 28
                val w = size.width / n
                val t = ms / 1000f
                for (j in 0 until n) {
                    val wobble = 0.55f + 0.45f * sin(t * (2.5f + j * 0.37f) + j * 1.7f)
                    val energy = if (j < n / 3) bass else level
                    val h = size.height * 0.28f * (0.08f + energy * wobble * k + 0.25f * p * (if (j < n / 3) 1f else 0.4f)).coerceIn(0.03f, 1f)
                    drawRoundRect(tint.copy(alpha = 0.55f).compositeWhite(0.35f), Offset(j * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(w * 0.35f))
                }
            }
            4 -> { // coloured spotlights that swing and change colour on the beat
                val i = map.lastBeat(ms).coerceAtLeast(0)
                val t = ms / 1000f
                for (j in 0 until 3) {
                    val c = disco[(i + j * 2) % disco.size]
                    val ang = sin(t * (0.6f + j * 0.25f) + j * 2f) * 0.6f
                    val ox = size.width * (0.15f + 0.35f * j)
                    val px = ox + sin(ang.toDouble()).toFloat() * size.height * 0.7f
                    val py = cos(ang.toDouble()).toFloat() * size.height * 0.7f
                    val rad = size.width * (0.45f + 0.25f * bass)
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = (0.18f + 0.45f * p).coerceIn(0f, 0.7f)), Color.Transparent), Offset(px, py), rad), rad, Offset(px, py))
                }
            }
        }
    }
}

private fun Color.compositeWhite(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

// ----------------------------------------------------------------------------------------------------------------
// Settings page
// ----------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPage(open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun changed() = MusicSettings.save(ctx)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item { Header("Settings", onBack) }
        item { VolumeSettings() }
        item {
            val hz by Tuning.hz.collectAsState()
            Section("Tuning")
            Text("Standard music is tuned to A = 440 Hz. Some people prefer 432 Hz and say it sounds warmer and calmer. Changes the pitch only, not the speed.",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tuning.choices.forEach { v -> Chip(if (v == 440f) "440 Hz (standard)" else "${v.toInt()} Hz", hz == v) { Tuning.set(ctx, v) } }
            }
        }
        item { Section("Themes") }
        item {
            Row(Modifier.fillMaxWidth().clickable { open(Page.Themes) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Themes", fontWeight = FontWeight.SemiBold)
                    Text("20 colour themes and ✨ FX themes that move. Now: " + (WavesThemes.of(MusicSettings.theme)?.name ?: "Plain"), fontSize = 13.sp)
                }
                Text("›", fontSize = 24.sp)
            }
        }
        item { Section("Customise") }
        item {
            Text("Album cover size", Modifier.padding(start = 16.dp, top = 10.dp), fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Small", fontSize = 13.sp)
                Slider(MusicSettings.coverSize, { MusicSettings.coverSize = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0.35f..1.0f,
                    onValueChangeFinished = { changed() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("Big", fontSize = 13.sp)
            }
            Text("Play buttons size", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Small", fontSize = 13.sp)
                Slider(MusicSettings.buttonSize, { MusicSettings.buttonSize = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0.7f..1.5f,
                    onValueChangeFinished = { changed() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("Big", fontSize = 13.sp)
            }
            Text("Mini player size (bottom bar)", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Small", fontSize = 13.sp)
                Slider(MusicSettings.miniSize, { MusicSettings.miniSize = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 1.0f..2.0f,
                    onValueChangeFinished = { changed() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("Big", fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().clickable { MusicSettings.blurBackground = !MusicSettings.blurBackground; changed() }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blurred moving cover background", fontWeight = FontWeight.SemiBold)
                    Text("Off gives the plain Samsung-style look", fontSize = 13.sp)
                }
                Switch(MusicSettings.blurBackground, { MusicSettings.blurBackground = it; changed() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
            // live preview of the sizes
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                Box(Modifier.size((90 * MusicSettings.coverSize).dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Accent, Color(0xFF7C4DFF)))))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(MusicIcons.previous to 22, MusicIcons.play to 32, MusicIcons.next to 22).forEach { (ic, sz) ->
                        Icon(ic, null, tint = Color.White, modifier = Modifier.padding(horizontal = 4.dp).size((sz * MusicSettings.buttonSize).dp))
                    }
                }
            }
            TextButton({ MusicSettings.coverSize = 0.5f; MusicSettings.buttonSize = 1f; MusicSettings.miniSize = 1.25f; changed() }, Modifier.padding(start = 8.dp)) { Text("Reset sizes", color = Accent) }
        }
        item { Section("Beat effects") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Move to the music", fontSize = 17.sp)
                    Text("The cover and background move to the beat. Music works out the beats on your phone.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(MusicSettings.effectsOn, { MusicSettings.effectsOn = it; changed() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
        if (MusicSettings.effectsOn) {
            item {
                Text("Album cover", Modifier.padding(start = 16.dp, top = 10.dp), fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSettings.coverEffects.forEachIndexed { i, name -> Chip(name, MusicSettings.cover == i) { MusicSettings.cover = i; changed() } }
                }
            }
            item {
                Text("Background", Modifier.padding(start = 16.dp, top = 14.dp), fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSettings.backgroundEffects.forEachIndexed { i, name -> if (i != 2) Chip(name, MusicSettings.background == i) { MusicSettings.background = i; changed() } }
                }
            }
            item {
                Text("Strength", Modifier.padding(start = 16.dp, top = 14.dp), fontWeight = FontWeight.SemiBold)
                Slider(MusicSettings.strength, { MusicSettings.strength = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0.3f..1.8f,
                    onValueChangeFinished = { changed() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            }
        }
        item { Section("Your music") }
        item {
            Row(Modifier.fillMaxWidth().clickable { open(Page.Fixer) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fix song info", fontSize = 17.sp)
                    Text("Find missing names, albums and artwork, and choose which to use", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
        item {
            TextButton({ addHomeShortcut(ctx) }, Modifier.padding(8.dp)) { Text("Add Music to Home screen", color = Accent) }
        }
    }
}

/** "Even out volume" (test 236). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeSettings() {
    val ctx = LocalContext.current
    val mode by Leveler.mode.collectAsState()
    val level by Leveler.level.collectAsState()
    val now by Leveler.now.collectAsState()
    val scan by Leveler.scan.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column {
        Section("Volume")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Even out volume", fontSize = 17.sp)
                Text("Plays every song at the same loudness, so a quiet song isn't followed by a blast.", fontSize = 13.sp, color = dim)
            }
            Switch(mode != Leveler.OFF, { Leveler.setMode(ctx, if (it) Leveler.SONG else Leveler.OFF) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (mode != Leveler.OFF) {
            Text("How loud", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Leveler.levels.forEachIndexed { i, (name, _) -> Chip(name, level == i) { Leveler.setLevel(ctx, i) } }
            }
            Text("Even out by", Modifier.padding(start = 16.dp, top = 12.dp), fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Song", mode == Leveler.SONG) { Leveler.setMode(ctx, Leveler.SONG) }
                Chip("Album", mode == Leveler.ALBUM) { Leveler.setMode(ctx, Leveler.ALBUM) }
            }
            Text(if (mode == Leveler.ALBUM) "Album keeps quiet and loud songs of the same album as the artist made them." else "Song makes every song the same loudness.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = dim)
            now?.let { (lufs, db) ->
                Text((if (lufs.isNaN()) "This song: no change" else "This song: %.0f LUFS, %s%.1f dB".format(lufs, if (db >= 0) "+" else "", db)),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = Accent)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val sc = scan
                    Text(if (sc != null) "Measuring ${sc.first} of ${sc.second}…" else "Songs are measured the first time they play.",
                        fontSize = 13.sp, color = dim)
                }
                if (scan != null) TextButton({ Leveler.stopMeasuring() }) { Text("Stop", color = Accent) }
                else TextButton({ Leveler.measureAll(ctx) }) { Text("Measure all now", color = Accent) }
            }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
        .background(if (on) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Fix song info
// ----------------------------------------------------------------------------------------------------------------

private class FixRow(val song: Song, problems: List<String>) {
    var problems by mutableStateOf(problems)
    var matches by mutableStateOf<List<Match>?>(null)
    var pick by mutableIntStateOf(0)
    var cover by mutableStateOf<String?>(null) // downloaded cover for the picked match
    var coverTried by mutableStateOf(false)
    var selected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null) // e.g. "Not in the music database" (test 248)
    val match: Match? get() = matches?.getOrNull(pick)
}

@Composable
internal fun FixerPage(lib: Library, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<FixRow>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FixRow?>(null) }
    var applied by remember { mutableIntStateOf(0) }

    var checking by remember { mutableStateOf("") }
    LaunchedEffect(lib, showAll) {
        // Test 247 (Jarrod: "loading for ages"): songs with missing names show straight away, then artwork is checked
        // 8 songs at a time in chunks, adding rows as it goes. Songs known to have artwork are remembered, so the
        // next visit is quick.
        rows = null
        val skipped = SongFixer.skipped(ctx)
        val hasArt = withContext(Dispatchers.IO) { ArtCheck.load(ctx) }
        val songs = lib.songs.filter { !(it.fixed && !showAll) && (showAll || it.id !in skipped) }
        val byId = HashMap<Long, FixRow>()
        val first = ArrayList<FixRow>()
        val toCheck = ArrayList<Song>()
        for (s in songs) {
            val p = SongFixer.problems(s)
            if (p.isNotEmpty() || showAll) FixRow(s, p).also { first += it; byId[s.id] = it }
            if (s.artFile.isEmpty() && s.id !in hasArt) toCheck += s
        }
        rows = first
        val found = HashSet<Long>()
        val sem = kotlinx.coroutines.sync.Semaphore(8)
        toCheck.chunked(48).forEachIndexed { ci, chunk ->
            checking = "Checking artwork ${minOf((ci + 1) * 48, toCheck.size)} of ${toCheck.size}…"
            val results = kotlinx.coroutines.coroutineScope {
                chunk.map { s -> async(Dispatchers.IO) { sem.withPermit { s to Covers.has(ctx, s) } } }.awaitAll()
            }
            val added = ArrayList<FixRow>()
            for ((s, has) in results) {
                if (has) { found += s.id; continue }
                val r = byId[s.id]
                if (r != null) r.problems = r.problems + "No artwork"
                else FixRow(s, listOf("No artwork")).also { added += it; byId[s.id] = it }
            }
            if (added.isNotEmpty()) rows = rows.orEmpty() + added
        }
        checking = ""
        if (found.isNotEmpty()) withContext(Dispatchers.IO) { ArtCheck.add(ctx, found) }
    }

    suspend fun lookUp(r: FixRow, title: String? = null, artist: String? = null) {
        r.busy = true
        r.note = null
        // Test 248 (Jarrod): the musical fingerprint first, it finds the exact song. Search… (with a title) uses names.
        if (title == null && AcoustId.available(ctx)) {
            val res = try { AcoustId.lookUp(ctx, r.song) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AcoustId.Result.Failed("Sound matching failed") }
            when (res) {
                is AcoustId.Result.Found -> {
                    r.matches = res.matches; r.pick = 0
                    r.selected = r.match?.confident(r.song) == true
                    r.busy = false
                    scope.launch { loadCover(ctx, r) }
                    return
                }
                AcoustId.Result.NotInDatabase -> {
                    r.matches = emptyList(); r.note = "Not in the music database. Use Search… to fix it by hand."
                    r.busy = false
                    return
                }
                is AcoustId.Result.Failed -> r.note = res.why + ", so it searched by name."
            }
        }
        val (t, a) = if (title != null) title to (artist ?: "") else SongFixer.guess(r.song)
        // never let a bad search result crash the app (test 249)
        r.matches = try { SongFixer.search(r.song, t, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { r.note = "Search failed. Try again."; emptyList() }
        r.pick = 0
        r.selected = r.match?.confident(r.song) == true
        r.busy = false
        // the cover comes from a different, slower site: fetch it alongside the next lookup instead of waiting
        scope.launch { loadCover(ctx, r) }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Fix song info", onBack)
        Text("Songs below are missing a name, artist, album or artwork. Tap Find info to recognise each song by its sound (AcoustID) and look it up on MusicBrainz, a free music database, " +
            "then tick the ones you want. Your music files aren't changed: Music keeps the fixes.",
            Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        // Test 248: the AcoustID key can be pasted here, so fingerprints work without a new build
        var keyText by remember { mutableStateOf("") }
        var hasKey by remember { mutableStateOf(AcoustId.available(ctx)) }
        if (!hasKey) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(keyText, { keyText = it }, Modifier.weight(1f), label = { Text("AcoustID key (for sound matching)") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ AcoustId.setKey(ctx, keyText); hasKey = AcoustId.available(ctx) }, enabled = keyText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Save") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Needs fixing", !showAll) { showAll = false }
            Chip("All songs", showAll) { showAll = true }
        }
        val list = rows
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job?.isActive == true) OutlinedButton({ job?.cancel() }) { Text("Stop") }
            else Button({
                val todo = list.orEmpty().filter { it.matches == null }.take(if (showAll) 50 else 500)
                job = scope.launch {
                    todo.forEachIndexed { i, r -> progress = "Looking up ${i + 1} of ${todo.size}…"; lookUp(r) }
                    progress = ""
                }
            }, enabled = !list.isNullOrEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Find info") }
            val chosen = list.orEmpty().count { it.selected && it.match != null }
            Button({
                scope.launch {
                    val out = HashMap<Long, SongFix>()
                    list.orEmpty().filter { it.selected && it.match != null }.forEach { r ->
                        val m = r.match!!
                        val art = r.cover?.let { SongFixer.keepCover(ctx, r.song.id, it) } ?: r.song.artFile
                        out[r.song.id] = SongFix(m.title, m.artist, m.album, m.year, art)
                    }
                    withContext(Dispatchers.IO) { SongFixer.save(ctx, out) }
                    applied += out.size
                    Covers.clear()
                    onChanged()
                }
            }, enabled = chosen > 0, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Use ticked ($chosen)") }
        }
        if (checking.isNotEmpty() && !list.isNullOrEmpty()) Text(checking, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp)
        if (progress.isNotEmpty()) Text(progress, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp)
        if (applied > 0) Text("Updated $applied songs.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = Accent)
        when {
            list == null -> Text("Checking your songs…", Modifier.padding(16.dp))
            list.isEmpty() && checking.isNotEmpty() -> Text(checking, Modifier.padding(16.dp))
            list.isEmpty() -> Text("All your songs have their info and artwork. 🎉", Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.song.id }) { r ->
                    FixRowView(r, onOther = {
                        val n = r.matches?.size ?: 0
                        if (n > 1) { r.pick = (r.pick + 1) % n; r.cover = null; r.coverTried = false; scope.launch { loadCover(ctx, r) } }
                    }, onSearch = { editing = r }, onSkip = {
                        SongFixer.skip(ctx, r.song.id, true); rows = rows?.minus(r)
                    }, onUndo = {
                        SongFixer.undo(ctx, r.song.id); Covers.clear(); onChanged()
                    }, onFind = { scope.launch { lookUp(r) } })
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }

    editing?.let { r ->
        val g = remember(r) { SongFixer.guess(r.song) }
        var t by remember(r) { mutableStateOf(g.first) }
        var a by remember(r) { mutableStateOf(g.second) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Search for this song") },
            text = {
                Column {
                    Text(r.song.path.substringAfterLast('/'), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(t, { t = it }, label = { Text("Song name") }, singleLine = true)
                    OutlinedTextField(a, { a = it }, label = { Text("Artist (if you know it)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ editing = null; scope.launch { lookUp(r, t, a) } }) { Text("Search", color = Accent) } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

private suspend fun loadCover(ctx: Context, r: FixRow) {
    val m = r.match ?: return
    r.coverTried = false
    r.cover = SongFixer.fetchCover(ctx, r.song.id, m)
    r.coverTried = true
}

@Composable
private fun FixRowView(r: FixRow, onOther: () -> Unit, onSearch: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit, onFind: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(r.song, 160, Modifier.size(48.dp), corner = 6)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.song.title, maxLines = 1, fontSize = 15.sp)
                Text("${r.song.artist} · ${r.song.album}", maxLines = 1, fontSize = 12.sp, color = dim)
                if (r.problems.isNotEmpty()) Text(r.problems.joinToString(" · "), fontSize = 12.sp, color = Accent)
                else if (r.song.fixed) Text("Fixed", fontSize = 12.sp, color = Accent)
            }
        }
        val m = r.match
        when {
            r.busy -> Text("Looking…", Modifier.padding(top = 6.dp, start = 60.dp), fontSize = 13.sp, color = dim)
            r.matches == null -> Row(Modifier.padding(start = 52.dp)) {
                TextButton(onFind) { Text("Find info", color = Accent) }
                TextButton(onSearch) { Text("Search…", color = Accent) }
                if (r.song.fixed) TextButton(onUndo) { Text("Undo fix", color = Accent) }
                else TextButton(onSkip) { Text("Skip", color = dim) }
            }
            m == null -> Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.note ?: "No match found.", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = dim)
                TextButton(onSearch) { Text("Search…", color = Accent) }
                TextButton(onSkip) { Text("Skip", color = dim) }
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = if (r.selected) 0.12f else 0.05f)).clickable { r.selected = !r.selected }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.selected, { r.selected = it }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
                        val path = r.cover
                        val bmp = remember(path) { path?.let { runCatching { BitmapFactory.decodeFile(it)?.let { b -> Bitmap.createScaledBitmap(b, 144, 144, true) } }.getOrNull() } }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(if (r.coverTried) "No art" else "…", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, maxLines = 1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(m.artist, maxLines = 1, fontSize = 13.sp)
                        Text(m.album + (if (m.year > 0) " · ${m.year}" else ""), maxLines = 1, fontSize = 12.sp, color = dim)
                        val len = if (m.lengthMs > 0) " · ${time(m.lengthMs)} (yours ${time(r.song.durationMs)})" else ""
                        Text("Match ${m.score}%$len", fontSize = 11.sp, color = dim)
                    }
                }
                Row(Modifier.padding(start = 40.dp)) {
                    if ((r.matches?.size ?: 0) > 1) TextButton(onOther) { Text("Other match (${r.pick + 1}/${r.matches!!.size})", color = Accent) }
                    TextButton(onSearch) { Text("Search…", color = Accent) }
                    TextButton(onSkip) { Text("Skip", color = dim) }
                }
            }
        }
    }
}


/** Songs already found to have artwork, so Fix song info doesn't read every file again (test 247). */
internal object ArtCheck {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "music/has_art.txt")
    fun load(ctx: Context): Set<Long> = runCatching { file(ctx).readLines().mapNotNull { it.toLongOrNull() }.toHashSet() }.getOrDefault(emptySet())
    fun add(ctx: Context, ids: Set<Long>) {
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText((load(ctx) + ids).joinToString("\n")) }
    }
}
