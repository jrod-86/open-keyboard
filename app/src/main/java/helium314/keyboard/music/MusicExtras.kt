// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
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
    /** test 312 (Jarrod: "they are mostly all too bright"): how much darker themes are drawn, 0 = as made, 0.9 = very dark */
    var themeDark by mutableFloatStateOf(0f)
    /** Design studio (test 264, Jarrod): the mini player's own background. -3 song colour (as before), -2 same as the app,
     *  -1 plain black, 0.. colour theme, 100+ FX theme */
    var miniTheme by mutableIntStateOf(-3)
    /** how the mini player's cover moves to the beat, index into [coverEffects] (0 = still) */
    var miniCover by mutableIntStateOf(0)
    /** Floating player over other apps (test 277, Jarrod): on/off, style (index into [floatStyles]), its own theme
     *  (same choices as [miniTheme]), size, and how its cover moves to the beat */
    val floatStyles = listOf("Bubble", "Pill", "Card", "Vinyl", "Beat bars")
    var floatOn by mutableStateOf(false)
    var floatStyle by mutableIntStateOf(1)
    var floatTheme by mutableIntStateOf(-3)
    var floatSize by mutableFloatStateOf(1f)
    var floatCover by mutableIntStateOf(1)
    /** test 298 (Jarrod): all the popularity things (Popular tab, Sort by popularity, chart line on Now Playing) on or off */
    var popularOn by mutableStateOf(true)
    /** test 299 (Jarrod): songs blend into each other like a DJ mix, off by default; [blendSec] = how long the overlap is */
    var blendOn by mutableStateOf(false)
    var blendSec by mutableFloatStateOf(6f)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        val p = prefs(ctx)
        effectsOn = p.getBoolean("fx_on", true); cover = p.getInt("fx_cover", 1); background = p.getInt("fx_bg2", 0); strength = p.getFloat("fx_strength", 1f)
        coverSize = p.getFloat("look_cover3", 0.5f); buttonSize = p.getFloat("look_buttons", 1f)
        miniSize = p.getFloat("look_mini", 1.25f); blurBackground = p.getBoolean("look_blur", false); theme = p.getInt("look_theme", -1)
        themeDark = p.getFloat("look_theme_dark", 0f)
        miniTheme = p.getInt("mini_theme", -3); miniCover = p.getInt("mini_cover", 0)
        floatOn = p.getBoolean("float_on", false); floatStyle = p.getInt("float_style", 1); floatTheme = p.getInt("float_theme", -3)
        floatSize = p.getFloat("float_size", 1f); floatCover = p.getInt("float_cover", 1)
        popularOn = p.getBoolean("pop_on", true)
        blendOn = p.getBoolean("blend_on", false); blendSec = p.getFloat("blend_sec", 6f)
        if (background == 2) background = 0 // Particles removed (test 240, Jarrod didn't like it)
    }
    fun save(ctx: Context) {
        prefs(ctx).edit().putBoolean("fx_on", effectsOn).putInt("fx_cover", cover).putInt("fx_bg2", background).putFloat("fx_strength", strength)
            .putFloat("look_cover3", coverSize).putFloat("look_buttons", buttonSize)
            .putFloat("look_mini", miniSize).putBoolean("look_blur", blurBackground).putInt("look_theme", theme).putFloat("look_theme_dark", themeDark)
            .putInt("mini_theme", miniTheme).putInt("mini_cover", miniCover)
            .putBoolean("float_on", floatOn).putInt("float_style", floatStyle).putInt("float_theme", floatTheme)
            .putFloat("float_size", floatSize).putInt("float_cover", floatCover).putBoolean("pop_on", popularOn).putBoolean("blend_on", blendOn).putFloat("blend_sec", blendSec).apply()
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
fun rememberBeatMap(song: Song?, on: Boolean = MusicSettings.effectsOn): BeatMap? {
    val ctx = LocalContext.current
    var map by remember(song?.id) { mutableStateOf<BeatMap?>(null) }
    LaunchedEffect(song?.id, on) {
        if (song == null || song.durationMs <= 0 || !on) return@LaunchedEffect
        map = withContext(Dispatchers.IO) { BeatMap.cached(ctx, song.id) } ?: BeatMap.analyze(ctx, song)
    }
    return map
}

/** How much the cover moves right now: scale, sideways tilt, up-down, glow (0..1). */
data class CoverMove(val scale: Float, val rotation: Float, val lift: Float, val glow: Float)

fun coverMove(map: BeatMap?, ms: Long, effect: Int = if (MusicSettings.effectsOn) MusicSettings.cover else 0): CoverMove {
    if (map == null || effect == 0) return CoverMove(1f, 0f, 0f, 0f)
    val k = MusicSettings.strength
    return moveFor(effect, map.pulse(ms) * k, map.bass(ms) * k, map.lastBeat(ms))
}

/** The cover move for an effect, from the beat's pulse [p] and bass [b] (already times strength). */
fun moveFor(effect: Int, p: Float, b: Float, beat: Int): CoverMove {
    return when (effect) {
        1 -> CoverMove(1f + 0.055f * p + 0.025f * b, 0f, 0f, 0f)
        2 -> CoverMove(1f + 0.02f * p, 0f, -1f * p, 0f)
        3 -> CoverMove(1f + 0.015f * p, 0f, 0f, (0.35f * b + 0.65f * p).coerceIn(0f, 1f))
        4 -> CoverMove(1f + 0.03f * p, (if (beat % 2 == 0) 1 else -1) * 3.5f * p, 0f, 0f)
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
internal fun SettingsPage(open: (Page) -> Unit, onBack: () -> Unit, onLibraryChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    fun changed() = MusicSettings.save(ctx)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item { Header("Settings", onBack) }
        item { Section("Sound and driving") }
        item {
            Row(Modifier.fillMaxWidth().clickable { open(Page.Equaliser) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎚️ Equaliser", fontSize = 17.sp)
                    Text("10 bands, bass boost, wide stereo and presets, per device", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { open(Page.Car) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🚗 Car mode", fontSize = 17.sp)
                    Text("Huge buttons, swipe to skip, screen stays on", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
        item { PlaybackSettings() }
        item { AnnouncerSettings(null) }
        item { VolumeSettings() }
        item { BlendSettings() }
        item {
            val hz by Tuning.hz.collectAsState()
            Section("Tuning")
            Text("Standard music is tuned to A = 440 Hz. Some people prefer 432 Hz and say it sounds warmer and calmer. Changes the pitch only, not the speed.",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tuning.choices.forEach { v -> Chip(if (v == 440f) "440 Hz (standard)" else "${v.toInt()} Hz", hz == v) { Tuning.set(ctx, v) } }
            }
        }
        item { Section("Look") }
        item {
            // test 264 (Jarrod): themes, sizes and beat effects all live together in the Design studio
            Row(Modifier.fillMaxWidth().clickable { open(Page.Studio) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎨 Design studio", fontSize = 17.sp)
                    Text("Themes, mini player look, sizes and beat effects, all in one place", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
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
        item { HiddenSongsRow(onLibraryChanged) }
        item { PopularitySettings() }
        item { ScrobbleSettings() }
        item { BackupSettings() }
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
internal fun Section(t: String) {
    // test 312 (Jarrod: "the headings are in too small of font"): a big heading with an icon and a coloured bar
    val icon = SECTION_ICONS.entries.firstOrNull { t.startsWith(it.key) }?.value ?: "•"
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
        .background(Accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        Spacer(Modifier.width(10.dp))
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(6.dp))
}

private val SECTION_ICONS = linkedMapOf(
    "Playback" to "▶️", "Backup" to "💾", "Scrobbling" to "📡", "AI radio" to "📻", "Tuning" to "🎼", "Look" to "🎨",
    "Your music" to "🎵", "Volume" to "🔉", "Popularity" to "📈", "Song blending" to "🔀", "App background" to "🖼️",
    "Mini player" to "⬇️", "Floating player" to "🫧", "Album cover" to "💿", "Now Playing" to "🎶", "Beat effects" to "💥",
    "Sound" to "🎚️", "Driving" to "🚗",
)

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
        .background(if (on) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Fix song info
// ----------------------------------------------------------------------------------------------------------------

/** Test 268 (Jarrod): says whether a match came from the song's sound (exact) or from its name (a guess). */
@Composable
private fun ViaBadge(via: String) {
    val (label, col) = when (via) {
        "sound" -> "🎵 Matched by sound" to Color(0xFF2ECC71)
        "name" -> "🔤 Matched by name" to Color(0xFFFFB74D)
        "artist" -> "🎤 Artist's albums" to Color(0xFF64B5F6)
        else -> return
    }
    Text(label, Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp, color = col, maxLines = 1)
}

private class FixRow(val song: Song, problems: List<String>) {
    var problems by mutableStateOf(problems)
    var matches by mutableStateOf<List<Match>?>(null)
    var pick by mutableIntStateOf(0)
    var cover by mutableStateOf<String?>(null) // downloaded cover for the picked match
    var coverTried by mutableStateOf(false)
    var selected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null) // e.g. "Not in the music database" (test 248)
    var artistPicks by mutableStateOf(false) // matches are an artist's albums, only their covers are used (test 258)
    /** test 268 (Jarrod): how the matches were found: "sound" (AcoustID fingerprint), "name" or "artist" */
    var via by mutableStateOf("")
    val match: Match? get() = matches?.let { if (artistPicks && pick < 0) it.firstOrNull() else it.getOrNull(pick) }
}

@Composable
internal fun FixerPage(lib: Library, onBack: () -> Unit, only: Long? = null, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    // test 252 (Jarrod): find covers only, leave names alone. Test 267: [only] = one song from Now Playing's
    // "Add artwork" menu, straight into artwork mode for that song.
    var artOnly by remember { mutableStateOf(only != null) }
    var rows by remember { mutableStateOf<List<FixRow>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FixRow?>(null) }
    var applied by remember { mutableIntStateOf(0) }

    var checking by remember { mutableStateOf("") }
    // Test 251 (Jarrod): each match can be saved on its own. Saved rows leave the list straight away; the library
    // reloads when you leave the page, so other songs' look-ups aren't lost.
    var dirty by remember { mutableStateOf(false) }
    // Test 256 (Jarrod): after saving, offer to write the fix permanently into the music file, only if you agree
    var pendingWrite by remember { mutableStateOf<List<FileFixer.Item>?>(null) }
    var writing by remember { mutableStateOf<List<FileFixer.Item>?>(null) }
    var writeNote by remember { mutableStateOf("") }
    fun doWrite(items: List<FileFixer.Item>) {
        scope.launch {
            writeNote = "Writing ${items.size} file" + (if (items.size == 1) "" else "s") + "…"
            val (ok, failed) = FileFixer.writeAll(ctx, items)
            writeNote = "Changed $ok music file" + (if (ok == 1) "" else "s") + "." +
                (if (failed.isNotEmpty()) " Couldn't change: " + failed.take(3).joinToString(", ") + (if (failed.size > 3) "…" else "") else "")
            dirty = true
        }
    }
    val writeAsk = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val items = writing; writing = null
        if (items != null && res.resultCode == android.app.Activity.RESULT_OK) doWrite(items)
        else if (items != null) writeNote = "Files not changed (Android permission not given). The fixes are still saved in Open Waves."
    }
    fun startWrite(items: List<FileFixer.Item>) {
        val pi = runCatching { FileFixer.writeRequest(ctx, items) }.getOrNull()
        if (pi == null) { doWrite(items); return }
        writing = items
        writeAsk.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
    }
    // Test 254 (Jarrod): put your own picture on a song when no artwork is found
    var picking by remember { mutableStateOf<FixRow?>(null) }
    val picker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        val r = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { SongFixer.ownCover(ctx, r.song.id, uri) }
            if (path == null) { android.widget.Toast.makeText(ctx, "Couldn't use that picture", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
            withContext(Dispatchers.IO) { SongFixer.save(ctx, mapOf(r.song.id to SongFix(r.song.title, r.song.artist, r.song.album, r.song.year, path))) }
            applied += 1
            dirty = true
            rows = rows?.filter { it.song.id != r.song.id }
            pendingWrite = listOf(FileFixer.Item(r.song, "", "", "", 0, path))
        }
    }
    DisposableEffect(Unit) { onDispose { FixPreview.stop(); if (dirty) { Covers.clear(); onChanged() } } }
    // test 272 (Jarrod): you know the right name but the database doesn't have the song: save what you typed
    fun saveTyped(r: FixRow, title: String, artist: String) {
        val t = title.trim(); val a = artist.trim().ifBlank { r.song.artist }
        if (t.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { SongFixer.save(ctx, mapOf(r.song.id to SongFix(t, a, r.song.album, r.song.year, r.song.artFile))) }
            applied += 1
            dirty = true
            rows = rows?.filter { it.song.id != r.song.id }
            // only the name and artist go into the file; album, year and picture stay as they are
            pendingWrite = listOf(FileFixer.Item(r.song, t, a, "", 0, null))
        }
    }

    fun saveRows(chosen: List<FixRow>) {
        if (chosen.isEmpty()) return
        scope.launch {
            val out = HashMap<Long, SongFix>()
            withContext(Dispatchers.IO) {
                chosen.forEach { r ->
                    val m = r.match ?: return@forEach
                    if (artOnly || r.artistPicks) {
                        // artwork only: keep the song's own name, artist and album
                        val cover = r.cover ?: return@forEach
                        out[r.song.id] = SongFix(r.song.title, r.song.artist, r.song.album, r.song.year, SongFixer.keepCover(ctx, r.song.id, cover))
                        return@forEach
                    }
                    val art = r.cover?.let { SongFixer.keepCover(ctx, r.song.id, it) } ?: r.song.artFile
                    out[r.song.id] = SongFix(m.title, m.artist, m.album, m.year, art)
                }
                SongFixer.save(ctx, out)
            }
            applied += out.size
            dirty = true
            rows = rows?.filter { it.song.id !in out }
            pendingWrite = chosen.filter { it.song.id in out }.map { r ->
                val f = out[r.song.id]!!
                val cover = f.art.takeIf { it.isNotEmpty() }
                if (artOnly || r.artistPicks) FileFixer.Item(r.song, "", "", "", 0, cover)
                else FileFixer.Item(r.song, f.title, f.artist, f.album, f.year, cover)
            }.takeIf { it.isNotEmpty() }
        }
    }
    LaunchedEffect(lib, showAll, artOnly) {
        // Test 247 (Jarrod: "loading for ages"): songs with missing names show straight away, then artwork is checked
        // 8 songs at a time in chunks, adding rows as it goes. Songs known to have artwork are remembered, so the
        // next visit is quick.
        rows = null
        if (only != null) {
            val s1 = lib.byId[only]
            rows = listOfNotNull(s1?.let { FixRow(it, listOf("Artwork")) })
            return@LaunchedEffect
        }
        val skipped = SongFixer.skipped(ctx)
        val hasArt = withContext(Dispatchers.IO) { ArtCheck.load(ctx) }
        val songs = lib.songs.filter { (artOnly || showAll || !it.fixed) && (showAll || it.id !in skipped) }
        val byId = HashMap<Long, FixRow>()
        val first = ArrayList<FixRow>()
        val toCheck = ArrayList<Song>()
        for (s in songs) {
            val p = SongFixer.problems(s)
            if (!artOnly && (p.isNotEmpty() || showAll)) FixRow(s, p).also { first += it; byId[s.id] = it }
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
        r.artistPicks = false
        // Test 258 (Jarrod): song name left empty in Search… = show this artist's album covers to pick from
        if (title != null && title.isBlank() && !artist.isNullOrBlank()) {
            r.matches = try { SongFixer.artistAlbums(artist) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            r.pick = -1; r.cover = null; r.coverTried = false; r.selected = false
            r.artistPicks = !r.matches.isNullOrEmpty(); r.via = "artist"
            if (!r.artistPicks) r.note = if (SongFixer.lastFailed) "The music database didn't answer. Tap Search… to try again."
                else "No albums found for \"$artist\". Check the spelling."
            r.busy = false
            return
        }
        // Test 248 (Jarrod): the musical fingerprint first, it finds the exact song. Search… (with a title) uses names.
        if (title == null && AcoustId.usable(ctx)) {
            val res = try { AcoustId.lookUp(ctx, r.song) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AcoustId.Result.Failed("Sound matching failed") }
            when (res) {
                is AcoustId.Result.Found -> {
                    r.matches = res.matches; r.pick = 0; r.via = "sound"
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
        // test 254 (Jarrod: "after you change the name nothing happens"): say clearly what was searched
        if (title != null && r.matches.isNullOrEmpty() && r.note == null)
            r.note = "Nothing found for \"$t\"" + (if (a.isNotBlank()) " by $a" else "") + ". Try other words, or just the song name."
        r.pick = 0; r.via = "name"
        r.selected = r.match?.confident(r.song) == true
        r.busy = false
        // the cover comes from a different, slower site: fetch it alongside the next lookup instead of waiting
        scope.launch { loadCover(ctx, r) }
    }

    // test 267: one song from Now Playing: start looking straight away
    val firstRow = rows?.firstOrNull()
    LaunchedEffect(firstRow) { if (only != null && firstRow != null && firstRow.matches == null) lookUp(firstRow) }

    // test 271 (Jarrod): look-ahead. The top 5 songs (Jarrod, test 272: was 3) of the list are always being looked up in the background, one at a
    // time (the music sites allow about one question a second), so the next song is ready when you get to it. Saving,
    // skipping or hiding a song moves the next one up, and that one starts straight away. On by default.
    val prefs = remember { ctx.getSharedPreferences("music", Context.MODE_PRIVATE) }
    var ahead by remember { mutableStateOf(prefs.getBoolean("fix_ahead", true)) }
    LaunchedEffect(ahead, only) {
        if (!ahead || only != null) return@LaunchedEffect
        while (true) {
            val next = if (job?.isActive == true) null else rows?.take(5)?.firstOrNull { it.matches == null && !it.busy }
            if (next != null) lookUp(next) else kotlinx.coroutines.delay(400)
        }
    }

    // test 276 (Jarrod): the top part scrolls away with the songs, so the whole screen is for the list
    val list = rows
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column {
        Header(if (only != null) "Add artwork" else "Fix song info", onBack)
        // test 269 (Jarrod): the explanation folds away so the songs get the room; tap to read it
        var info by remember { mutableStateOf(false) }
        var keyText by remember { mutableStateOf("") }
        var hasKey by remember { mutableStateOf(AcoustId.available(ctx)) }
        Row(Modifier.clickable { info = !info }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ⓘ  How this works", fontSize = 14.sp, color = Accent)
            Text(if (info) "  ▴" else "  ▾", fontSize = 14.sp, color = Accent)
        }
        if (info) {
            if (only != null) Text("Open Waves is looking for this song's cover. Pick one, use Search… (try \"Search by artist\" to pick any of their album covers), or use your own picture.",
                Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            else Text("Songs below are missing a name, artist, album or artwork. Tap Find info to recognise each song by its sound (AcoustID) and look it up on MusicBrainz, a free music database, " +
                "then tick the ones you want. When you save, you choose whether to change the music file too.",
                Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Text("Can't find it? Tap ⧉ to copy the song name and artist, or 🔍 to search Google Images. Save a picture you like, then use Pick picture.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Text("Tap ▶ on a song's cover to listen to it (from about a third of the way in) and check the match. Tap again to stop.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Text("Each match says how it was found: 🎵 by sound is the exact song, 🔤 by name is a best guess worth checking.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            // Test 248: the AcoustID key can be pasted here, so fingerprints work without a new build
            if (hasKey && !AcoustId.keyRejected) TextButton({ hasKey = false; keyText = AcoustId.key(ctx) }, Modifier.padding(horizontal = 4.dp)) {
                Text("Change AcoustID key", color = Accent, fontSize = 13.sp)
            }
        }
        // a key that stopped working always shows, so you notice
        if (hasKey && AcoustId.keyRejected) TextButton({ hasKey = false; keyText = AcoustId.key(ctx) }, Modifier.padding(horizontal = 4.dp)) {
            Text("AcoustID key didn't work. Change key", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }
        if (!hasKey) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(keyText, { keyText = it }, Modifier.weight(1f), label = { Text("AcoustID key (for sound matching)") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ AcoustId.setKey(ctx, keyText.trim()); AcoustId.keyRejected = false; hasKey = AcoustId.available(ctx) }, enabled = keyText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Save") }
        }
        if (only == null) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Needs fixing", !showAll && !artOnly) { showAll = false; artOnly = false }
            Chip("Artwork only", artOnly) { artOnly = true; showAll = false }
            Chip("All songs", showAll) { showAll = true; artOnly = false }
        }
        if (only == null) Row(Modifier.fillMaxWidth().clickable { ahead = !ahead; prefs.edit().putBoolean("fix_ahead", ahead).apply() }
            .padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get the next 5 songs ready", fontSize = 14.sp)
                Text("Looks them up while you check the one on top", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(ahead, { ahead = it; prefs.edit().putBoolean("fix_ahead", it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (only == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job?.isActive == true) OutlinedButton({ job?.cancel() }) { Text("Stop") }
            else Button({
                val todo = list.orEmpty().filter { it.matches == null }.take(if (showAll) 50 else 500)
                job = scope.launch {
                    todo.forEachIndexed { i, r -> progress = "Looking up ${i + 1} of ${todo.size}…"; if (r.matches == null && !r.busy) lookUp(r) }
                    progress = ""
                }
            }, enabled = !list.isNullOrEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Find info") }
            val chosen = list.orEmpty().count { it.selected && it.match != null }
            Button({
                saveRows(list.orEmpty().filter { it.selected && it.match != null })
            }, enabled = chosen > 0, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Use ticked ($chosen)") }
        }
        if (checking.isNotEmpty() && !list.isNullOrEmpty()) Text(checking, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp)
        if (progress.isNotEmpty()) Text(progress, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp)
        if (writeNote.isNotEmpty()) Text(writeNote, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = Accent)
        if (applied > 0) Text("Saved $applied song" + (if (applied == 1) "" else "s") + ". Your library updates when you leave this page.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = Accent)
        } }
        when {
            list == null -> item { Text("Checking your songs…", Modifier.padding(16.dp)) }
            list.isEmpty() && checking.isNotEmpty() -> item { Text(checking, Modifier.padding(16.dp)) }
            list.isEmpty() && only != null -> item { Column(Modifier.padding(16.dp)) {
                Text(if (applied > 0) "Artwork saved. ✓" else "Song not found.", fontSize = 17.sp)
                TextButton(onBack) { Text("Back to the song", color = Accent) }
            } }
            list.isEmpty() -> item { Text("All your songs have their info and artwork. 🎉", Modifier.padding(16.dp)) }
            else -> {
                items(list, key = { it.song.id }) { r ->
                    FixRowView(r, onOther = {
                        val n = r.matches?.size ?: 0
                        if (n > 1) { r.pick = (r.pick + 1) % n; r.cover = null; r.coverTried = false; scope.launch { loadCover(ctx, r) } }
                    }, onSearch = { editing = r }, onSkip = {
                        SongFixer.skip(ctx, r.song.id, true); rows = rows?.minus(r)
                    }, onUndo = {
                        SongFixer.undo(ctx, r.song.id); Covers.clear(); onChanged()
                    }, onFind = { scope.launch { lookUp(r) } }, onSave = { saveRows(listOf(r)) }, artOnly = artOnly, onHide = {
                        HiddenSongs.hide(ctx, r.song); dirty = true; rows = rows?.minus(r)
                        android.widget.Toast.makeText(ctx, "Hidden. Bring it back in Settings, Hidden songs.", android.widget.Toast.LENGTH_SHORT).show()
                    }, onPick = {
                        picking = r
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }

    pendingWrite?.let { items ->
        val n = items.size
        AlertDialog(onDismissRequest = { pendingWrite = null },
            title = { Text("Change the music file" + (if (n == 1) "" else "s") + " too?") },
            text = {
                Column {
                    Text("Saved in Open Waves. Do you also want to write " + (if (n == 1) "this fix" else "these $n fixes") +
                        " permanently into the music file" + (if (n == 1) "" else "s") + "? Every app will then see the new " +
                        (if (items.all { it.title.isBlank() }) "artwork" else "name, artist, album and artwork") +
                        ". This changes the file itself and can't be undone.")
                    if (android.os.Build.VERSION.SDK_INT >= 31 && !FileFixer.canManageMedia(ctx)) {
                        Spacer(Modifier.height(8.dp))
                        Text("Android will ask you to allow it each time. To stop that, allow Open Waves to manage media:",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        TextButton({ runCatching { ctx.startActivity(FileFixer.manageMediaIntent(ctx)) } }) { Text("Allow manage media", color = Accent) }
                    }
                }
            },
            confirmButton = { TextButton({ pendingWrite = null; startWrite(items) }) { Text("Yes, change " + (if (n == 1) "the file" else "$n files"), color = Accent) } },
            dismissButton = { TextButton({ pendingWrite = null }) { Text("No, only in Open Waves") } })
    }

    editing?.let { r ->
        val g = remember(r) { SongFixer.guess(r.song) }
        var t by remember(r) { mutableStateOf(g.first) }
        var a by remember(r) { mutableStateOf(g.second) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Search or fix the name") },
            text = {
                Column {
                    Text(r.song.path.substringAfterLast('/'), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(t, { t = it }, label = { Text("Song name") }, singleLine = true)
                    OutlinedTextField(a, { a = it }, label = { Text("Artist (if you know it)") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    // Test 259 (Jarrod): two clear choices instead of clearing the song name by hand
                    Button({ editing = null; scope.launch { lookUp(r, t, a) } }, enabled = t.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Search by title and artist")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton({ editing = null; scope.launch { lookUp(r, "", a) } }, enabled = a.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Search by artist (pick any album cover)", color = if (a.isNotBlank()) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton({ editing = null; saveTyped(r, t, a) }, enabled = t.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Save as typed (no search)", color = if (t.isNotBlank()) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                    Text("Use this when you already know the right song name and artist.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

private suspend fun loadCover(ctx: Context, r: FixRow) {
    val m = r.match ?: return
    r.coverTried = false
    r.cover = SongFixer.fetchCover(ctx, r.song.id, m)
    r.coverTried = true
}

/**
 * Test 273 (Jarrod): listen to a song in Fix song info to check a match. Plays from about a third of the way in
 * (the part people recognise), one song at a time. Asks for audio focus so the main player pauses meanwhile.
 */
object FixPreview {
    var playingId by mutableLongStateOf(-1L)
        private set
    private var mp: android.media.MediaPlayer? = null
    private var focus: android.media.AudioFocusRequest? = null
    private var am: android.media.AudioManager? = null

    fun toggle(ctx: Context, song: Song) {
        if (playingId == song.id) { stop(); return }
        stop()
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if (it == android.media.AudioManager.AUDIOFOCUS_LOSS) stop() }.build()
        focus?.let { am?.requestAudioFocus(it) }
        playingId = song.id
        mp = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(ctx, song.uri)
                setOnPreparedListener { p -> if (song.durationMs > 60_000) p.seekTo((song.durationMs / 3).toInt()); p.start() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepareAsync()
            }
        }.getOrElse { stop(); null }
    }

    fun stop() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        focus?.let { am?.abandonAudioFocusRequest(it) }
        focus = null
        playingId = -1L
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** A small text button without the big built-in padding of TextButton (test 270). */
@Composable
private fun LinkButton(label: String, color: Color, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp), color = color, fontSize = 14.sp)
}

@Composable
private fun FixRowView(r: FixRow, onOther: () -> Unit, onSearch: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit, onFind: () -> Unit, onSave: () -> Unit, artOnly: Boolean = false, onHide: () -> Unit = {}, onPick: () -> Unit = {}) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    // test 270 (Jarrod: "very big space between the lines"): tighter rows and compact link buttons
    // test 275 (Jarrod: "barely enough room to read one box"): tight line height for every line in the row,
    // and no big 48 dp touch frame around the tick box
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Em)),
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.clickable { onPick() }, verticalAlignment = Alignment.CenterVertically) { // tap the song to pick a picture
            // test 273 (Jarrod): play button on the cover, to listen and check a match is the right song
            val ctx = LocalContext.current
            val playing = FixPreview.playingId == r.song.id
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).clickable { FixPreview.toggle(ctx, r.song) }, contentAlignment = Alignment.Center) {
                Cover(r.song, 160, Modifier.size(48.dp), corner = 6)
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(if (playing) MusicIcons.pause else MusicIcons.play, if (playing) "Stop" else "Listen", tint = if (playing) Accent else Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.song.title, maxLines = 1, fontSize = 15.sp)
                Text("${r.song.artist} · ${r.song.album}", maxLines = 1, fontSize = 12.sp, color = dim)
                if (r.problems.isNotEmpty()) Text(r.problems.joinToString(" · "), fontSize = 12.sp, color = Accent)
                else if (r.song.fixed) Text("Fixed", fontSize = 12.sp, color = Accent)
            }
            // test 274 (Jarrod): if all else fails, copy the name or search Google Images, then use Pick picture
            val words = remember(r.song) { SongFixer.guess(r.song).let { (t, a) -> listOf(t, a).filter { it.isNotBlank() }.joinToString(" ") } }
            SmallIcon(MusicIcons.copy, "Copy song name and artist") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Song", words))
                android.widget.Toast.makeText(ctx, "Copied: $words", android.widget.Toast.LENGTH_SHORT).show()
            }
            SmallIcon(MusicIcons.search, "Search Google Images") {
                val url = "https://www.google.com/search?tbm=isch&q=" + java.net.URLEncoder.encode("$words album cover", "UTF-8")
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        val m = r.match
        when {
            r.busy -> Text("Looking…", Modifier.padding(top = 6.dp, start = 60.dp), fontSize = 13.sp, color = dim)
            r.matches == null -> FlowRow(Modifier.padding(start = 54.dp, top = 2.dp)) {
                LinkButton("Find info", Accent, onFind)
                LinkButton("Search…", Accent, onSearch)
                LinkButton("Pick picture", Accent, onPick)
                if (r.song.fixed) LinkButton("Undo fix", Accent, onUndo)
                else LinkButton("Skip", dim, onSkip)
                LinkButton("Not music, hide", dim, onHide)
            }
            m == null -> Column(Modifier.padding(start = 60.dp)) { // message on its own line (test 257: it was squeezed into a column of letters)
                Text(r.note ?: "No match found.", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = dim)
                FlowRow(Modifier.padding(start = 0.dp)) {
                    LinkButton("Search…", Accent, onSearch)
                    LinkButton("Pick picture", Accent, onPick)
                    LinkButton("Skip", dim, onSkip)
                    LinkButton("Not music, hide", dim, onHide)
                }
            }
            r.artistPicks -> ArtistCoverPicker(r, artOnly, onSave, onSearch, onPick, onSkip)
            else -> {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = if (r.selected) 0.12f else 0.05f)).clickable { r.selected = !r.selected }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.selected, { r.selected = it }, Modifier.padding(end = 6.dp), colors = CheckboxDefaults.colors(checkedColor = Accent))
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
                        Text("Match ${m.score}%$len", fontSize = 11.sp, color = dim, maxLines = 1)
                        ViaBadge(r.via)
                    }
                }
                FlowRow(Modifier.padding(start = 40.dp, top = 4.dp), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                    Button(onSave, Modifier.height(34.dp), enabled = !artOnly || r.cover != null, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        Text(if (artOnly) "Save artwork" else "Save")
                    }
                    if ((r.matches?.size ?: 0) > 1) LinkButton("Other (${r.pick + 1}/${r.matches!!.size})", Accent, onOther)
                    LinkButton("Search…", Accent, onSearch)
                    LinkButton("Pick picture", Accent, onPick)
                    LinkButton("Skip", dim, onSkip)
                    LinkButton("Not music, hide", dim, onHide)
                }
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

/** Settings, Your music: songs hidden as "not music", with a way to bring each back (test 255). */
@Composable
private fun HiddenSongsRow(onLibraryChanged: () -> Unit) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(HiddenSongs.all(ctx)) }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { hidden = HiddenSongs.all(ctx); open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hidden songs (${hidden.size})", fontSize = 17.sp)
            Text("Recordings and other sounds you said aren't music", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text("Hidden songs") },
        text = {
            if (hidden.isEmpty()) Text("Nothing hidden. Playing something that isn't music? Now Playing ⋮ > Not music, hide it.")
            else LazyColumn(Modifier.height(360.dp)) {
                items(hidden.entries.toList(), key = { it.key }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2)
                        TextButton({ HiddenSongs.show(ctx, id); hidden = HiddenSongs.all(ctx); onLibraryChanged() }) { Text("Show again", color = Accent) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("Done", color = Accent) } })
}

/** Test 258 (Jarrod): an artist's album covers in a row; tap one, then Save artwork. */
@Composable
private fun ArtistCoverPicker(r: FixRow, artOnly: Boolean, onSave: () -> Unit, onSearch: () -> Unit, onPick: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val list = r.matches.orEmpty()
    // album covers that exist; albums without art are dropped as their thumbnails fail
    val thumbs = remember(list) { mutableStateMapOf<String, Bitmap?>() }
    LaunchedEffect(list) {
        val sem = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            list.forEach { m -> launch { sem.withPermit { thumbs[m.groupId] = SongFixer.thumb(ctx, m.groupId) } } }
        }
    }
    Column(Modifier.padding(top = 6.dp)) {
        val shown = list.withIndex().filter { thumbs[it.value.groupId] != null || !thumbs.containsKey(it.value.groupId) }
        Text(if (thumbs.size < list.size) "Loading ${list.firstOrNull()?.artist ?: ""} covers… tap one to pick it"
            else "${shown.size} covers by ${list.firstOrNull()?.artist ?: ""}. Tap one to pick it.", fontSize = 13.sp, color = dim)
        androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.value.groupId }) { (i, m) ->
                val bmp = thumbs[m.groupId]
                val on = r.pick == i
                Column(Modifier.width(92.dp).clickable {
                    r.pick = i; r.cover = null; r.coverTried = false; r.selected = true
                    scope.launch { r.cover = SongFixer.fetchCover(ctx, r.song.id, m); r.coverTried = true }
                }) {
                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E))
                        .then(if (on) Modifier.border(3.dp, Accent, RoundedCornerShape(8.dp)) else Modifier), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp.asImageBitmap(), m.album, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("…", color = Color.White.copy(alpha = 0.6f))
                    }
                    Text(m.album + (if (m.year > 0) " · ${m.year}" else ""), fontSize = 11.sp, maxLines = 2, color = dim)
                }
            }
        }
        FlowRow {
            Button(onSave, enabled = r.pick >= 0 && r.cover != null, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(if (r.pick >= 0 && r.cover == null && !r.coverTried) "Getting cover…" else "Save artwork")
            }
            LinkButton("Search…", Accent, onSearch)
            LinkButton("Pick picture", Accent, onPick)
            LinkButton("Skip", dim, onSkip)
        }
    }
}

/** Test 298 (Jarrod): turn the popularity things off, or remove everything they looked up. */
@Composable
private fun PopularitySettings() {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var confirm by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }
    Column {
        Section("Popularity")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Popularity features", fontSize = 17.sp)
                Text("The Popular tab, Sort by popularity in playlists, and chart facts on the playing screen.", fontSize = 13.sp, color = dim)
            }
            Switch(MusicSettings.popularOn, {
                MusicSettings.popularOn = it; MusicSettings.save(ctx)
                if (!it && PopScan.running.value) PopScan.pause(ctx)
            }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        TextButton({ confirm = true }, Modifier.padding(horizontal = 8.dp)) { Text("Delete all popularity info", color = Accent) }
        if (removed) Text("Removed. Your songs and playlists are untouched.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete all popularity info?") },
        text = { Text("Removes every play count and chart spot that was looked up, so the Popular tab starts again from nothing. Your songs, playlists and playlist order stay as they are.") },
        confirmButton = { TextButton({ if (PopScan.running.value) PopScan.pause(ctx); Popularity.clear(ctx); confirm = false; removed = true }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/** Test 299 (Jarrod): "Blend songs" on/off, and how long the overlap is. */
@Composable
private fun BlendSettings() {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column {
        Section("Song blending")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Blend songs together", fontSize = 17.sp)
                Text("The end of each song fades into the start of the next, like a DJ mix, with no gap.", fontSize = 13.sp, color = dim)
            }
            Switch(MusicSettings.blendOn, { MusicSettings.blendOn = it; MusicSettings.save(ctx) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
        if (MusicSettings.blendOn) {
            Text("Blend length: ${MusicSettings.blendSec.toInt()} seconds", Modifier.padding(start = 16.dp, top = 4.dp), fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.Slider(MusicSettings.blendSec, { MusicSettings.blendSec = it }, Modifier.padding(horizontal = 16.dp),
                valueRange = 2f..12f, steps = 9, onValueChangeFinished = { MusicSettings.save(ctx) },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            Text("Albums meant to play without gaps and the last song of a queue aren't blended. Repeat one song turns it off.",
                Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
        }
    }
}
