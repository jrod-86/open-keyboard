// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Open Waves themes (test 239, Jarrod): 20 colourful themes, plus FX themes that move.
 * Theme number: -1 = plain (black / white), 0..19 = colour themes, 100+ = FX (animated) themes.
 */
class WavesTheme(val name: String, val colors: List<Color>, val accent: Color)

object WavesThemes {
    private fun c(v: Long) = Color(v)
    val colour = listOf(
        WavesTheme("Sunset", listOf(c(0xFFFF512F), c(0xFFDD2476), c(0xFF6A3093)), c(0xFFFF8A65)),
        WavesTheme("Ocean", listOf(c(0xFF1A2980), c(0xFF26D0CE), c(0xFF0F2027)), c(0xFF26D0CE)),
        WavesTheme("Tropical", listOf(c(0xFFF7971E), c(0xFFFFD200), c(0xFF21D4FD)), c(0xFFFFD200)),
        WavesTheme("Candy", listOf(c(0xFFFF9A9E), c(0xFFA18CD1), c(0xFF5B3A8E)), c(0xFFFF9A9E)),
        WavesTheme("Neon", listOf(c(0xFF00F5A0), c(0xFF00D9F5), c(0xFF7B2FF7)), c(0xFF00F5A0)),
        WavesTheme("Galaxy", listOf(c(0xFF0F0C29), c(0xFF302B63), c(0xFF6A5ACD)), c(0xFF9D8CFF)),
        WavesTheme("Lava", listOf(c(0xFFF12711), c(0xFFF5AF19), c(0xFF3A0000)), c(0xFFF5AF19)),
        WavesTheme("Forest", listOf(c(0xFF134E5E), c(0xFF71B280), c(0xFF0B3D2E)), c(0xFF8FD694)),
        WavesTheme("Rainbow", listOf(c(0xFFFF0080), c(0xFFFF8C00), c(0xFF40E0D0)), c(0xFFFF4FA3)),
        WavesTheme("Cotton candy", listOf(c(0xFF89F7FE), c(0xFF66A6FF), c(0xFFF093FB)), c(0xFFF093FB)),
        WavesTheme("Royal", listOf(c(0xFF141E30), c(0xFF243B55), c(0xFFD4AF37)), c(0xFFE6C55A)),
        WavesTheme("Cherry", listOf(c(0xFFEB3349), c(0xFFF45C43), c(0xFF2B0A0F)), c(0xFFFF6B6B)),
        WavesTheme("Lime", listOf(c(0xFFA8E063), c(0xFF56AB2F), c(0xFF0B3D0B)), c(0xFFB5F26B)),
        WavesTheme("Deep sea", listOf(c(0xFF000428), c(0xFF004E92), c(0xFF00C6FF)), c(0xFF00C6FF)),
        WavesTheme("Bubblegum", listOf(c(0xFFFF6FD8), c(0xFF3813C2), c(0xFF120036)), c(0xFFFF6FD8)),
        WavesTheme("Desert", listOf(c(0xFFEDC9AF), c(0xFFC06C84), c(0xFF6C5B7B)), c(0xFFF0B49E)),
        WavesTheme("Electric", listOf(c(0xFF4776E6), c(0xFF8E54E9), c(0xFFFF00CC)), c(0xFFB57BFF)),
        WavesTheme("Mint", listOf(c(0xFF00B09B), c(0xFF96C93D), c(0xFF053B2E)), c(0xFF5EE6C4)),
        WavesTheme("Fire and ice", listOf(c(0xFFFF416C), c(0xFF4E4376), c(0xFF2B5876)), c(0xFFFF416C)),
        WavesTheme("Gold", listOf(c(0xFFF7971E), c(0xFFB8860B), c(0xFF2C1A00)), c(0xFFFFC04D)),
        WavesTheme("Peacock", listOf(c(0xFF0F4C5C), c(0xFF1B998B), c(0xFFE6AF2E)), c(0xFF2EC4B6)),
        WavesTheme("Berry smoothie", listOf(c(0xFF8E2DE2), c(0xFFFF6B9D), c(0xFF4A0E4E)), c(0xFFFF6B9D)),
        WavesTheme("Aqua lagoon", listOf(c(0xFF00C9A7), c(0xFF0081A7), c(0xFF003049)), c(0xFF00E0C0)),
        WavesTheme("Autumn", listOf(c(0xFFD1495B), c(0xFFEDAE49), c(0xFF3D2C2E)), c(0xFFEDAE49)),
        WavesTheme("Sakura", listOf(c(0xFFFFB7C5), c(0xFFE07A9F), c(0xFF3B1F2B)), c(0xFFFF9EB8)),
        WavesTheme("Ice", listOf(c(0xFFE0F7FF), c(0xFF7FC8F8), c(0xFF1B3A5B)), c(0xFF7FC8F8)),
        WavesTheme("Watermelon", listOf(c(0xFFFF4E6A), c(0xFF7BD389), c(0xFF1C3D2A)), c(0xFFFF5C7A)),
        WavesTheme("Coffee", listOf(c(0xFFC8A27A), c(0xFF6F4E37), c(0xFF1E140F)), c(0xFFE0B88A)),
        WavesTheme("Ultraviolet", listOf(c(0xFF5F0A87), c(0xFFA4508B), c(0xFF1B0034)), c(0xFFC77DFF)),
    )
    /** animated themes: name, colours, accent */
    val fx = listOf(
        WavesTheme("Aurora", listOf(c(0xFF00FFA3), c(0xFF7B2FF7), c(0xFF00B4D8)), c(0xFF00FFA3)),
        WavesTheme("Lava lamp", listOf(c(0xFFFF5E00), c(0xFFFF0054), c(0xFFFFBD00)), c(0xFFFF7A3D)),
        WavesTheme("Starfield", listOf(c(0xFF0B1026), c(0xFF2B2F77), c(0xFFFFFFFF)), c(0xFF8EA6FF)),
        WavesTheme("Rainbow flow", listOf(c(0xFFFF0000), c(0xFF00FF00), c(0xFF0000FF)), c(0xFFFF4FA3)),
        WavesTheme("Ocean waves", listOf(c(0xFF001F3F), c(0xFF0074D9), c(0xFF7FDBFF)), c(0xFF7FDBFF)),
        WavesTheme("Disco", listOf(c(0xFFFF2D95), c(0xFF00E5FF), c(0xFFFFEA00)), c(0xFFFF2D95)),
        WavesTheme("Fireflies", listOf(c(0xFF0A1A0A), c(0xFF1F3B1F), c(0xFFFFF176)), c(0xFFFFF176)),
        WavesTheme("Neon grid", listOf(c(0xFF12002B), c(0xFFFF00E6), c(0xFF00F0FF)), c(0xFFFF00E6)),
        WavesTheme("Fractal warp", listOf(c(0xFF00E5FF), c(0xFFFF00C8), c(0xFF120030)), c(0xFF00E5FF)),
        WavesTheme("Leaf vortex", listOf(c(0xFF3CFF6B), c(0xFFFFD23C), c(0xFF051A0A)), c(0xFF7CFF6B)),
        WavesTheme("Laser show", listOf(c(0xFF00FF66), c(0xFF00331A), c(0xFF000000)), c(0xFF39FF14)),
        WavesTheme("Star bloom", listOf(c(0xFFFF6EC7), c(0xFF7A5CFF), c(0xFF0A0020)), c(0xFFFF6EC7)),
        WavesTheme("Fractal ripples", listOf(c(0xFFFF8A00), c(0xFF00C2FF), c(0xFF100018)), c(0xFFFFA040)),
        WavesTheme("Hypnosis", listOf(c(0xFFFFFFFF), c(0xFF000000), c(0xFF7A00FF)), c(0xFFB57BFF)),
        WavesTheme("Rainbow spiral", listOf(c(0xFFFF0000), c(0xFF00FF00), c(0xFF0000FF)), c(0xFFFF4FA3)),
        WavesTheme("Neon rings", listOf(c(0xFFFF00E6), c(0xFF00F0FF), c(0xFF0A0018)), c(0xFF00F0FF)),
        WavesTheme("Kaleidoscope", listOf(c(0xFFFFC400), c(0xFFFF2D95), c(0xFF00B4D8)), c(0xFFFFC400)),
        WavesTheme("Stained glass", listOf(c(0xFFE63946), c(0xFF457B9D), c(0xFFF1C40F)), c(0xFFF1C40F)),
        WavesTheme("Slime portal", listOf(c(0xFF7CFF00), c(0xFF00FFB3), c(0xFF0A1A00)), c(0xFF7CFF00)),
        WavesTheme("Star tunnel", listOf(c(0xFF8EA6FF), c(0xFFFFFFFF), c(0xFF02021A)), c(0xFF8EA6FF)),
        WavesTheme("Zebra swirl", listOf(c(0xFFFFFFFF), c(0xFF000000), c(0xFF808080)), c(0xFFDDDDDD)),
        WavesTheme("Fractal zoom", listOf(c(0xFF00D4FF), c(0xFFFF6A00), c(0xFF000010)), c(0xFF00D4FF)),
        WavesTheme("Plasma", listOf(c(0xFFFF2D95), c(0xFF00E5FF), c(0xFFFFEA00)), c(0xFFFF2D95)),
        WavesTheme("Liquid", listOf(c(0xFF6A5CFF), c(0xFF00E0FF), c(0xFF050520)), c(0xFF8F84FF)),
        WavesTheme("Warp speed", listOf(c(0xFF9FD8FF), c(0xFFFFFFFF), c(0xFF000008)), c(0xFF9FD8FF)),
        WavesTheme("Galaxy", listOf(c(0xFFB388FF), c(0xFFFF80AB), c(0xFF05001A)), c(0xFFB388FF)),
        WavesTheme("Snowfall", listOf(c(0xFFFFFFFF), c(0xFF9EC9FF), c(0xFF0B1A33)), c(0xFFBFE0FF)),
        WavesTheme("Confetti", listOf(c(0xFFFF3D7F), c(0xFFFFD93D), c(0xFF6BCB77)), c(0xFFFFD93D)),
        WavesTheme("Equalizer", listOf(c(0xFF00F5A0), c(0xFFFF00E6), c(0xFF05050F)), c(0xFF00F5A0)),
        WavesTheme("Fire", listOf(c(0xFFFF4500), c(0xFFFFC300), c(0xFF1A0000)), c(0xFFFF7A00)),
    )
    fun of(id: Int): WavesTheme? = when { id in colour.indices -> colour[id]; id - 100 in fx.indices -> fx[id - 100]; else -> null }
    fun isFx(id: Int) = id >= 100
}

/** Test 315: the colour controls (contrast, colour, warmth, hue, shadows) as one colour matrix over the theme. */
internal fun themeMatrix(): androidx.compose.ui.graphics.ColorMatrix? {
    val c = MusicSettings.themeContrast; val sat = MusicSettings.themeSat; val warm = MusicSettings.themeWarm
    val hue = MusicSettings.themeHue; val sh = MusicSettings.themeShadow
    if (c == 1f && sat == 1f && warm == 0f && hue == 0f && sh == 0f) return null
    val m = androidx.compose.ui.graphics.ColorMatrix()
    m.setToSaturation(sat)
    if (hue != 0f) {
        val a = Math.toRadians(hue.toDouble()); val cs = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        m.timesAssign(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
            0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
            0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
            0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
    // warmth: more red and less blue (or the other way)
    val r = 1f + warm * 0.25f; val b = 1f - warm * 0.25f
    // contrast round the middle grey, shadows lift (+) or deepen (−) the dark parts
    val off = (1f - c) * 127.5f + sh * 40f
    m.timesAssign(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        c * r, 0f, 0f, 0f, off,
        0f, c, 0f, 0f, off,
        0f, 0f, c * b, 0f, off,
        0f, 0f, 0f, 1f, 0f)))
    return m
}

@Composable
private fun themeGrade(modifier: Modifier): Modifier {
    val m = themeMatrix() ?: return modifier
    val paint = remember(m) { androidx.compose.ui.graphics.Paint().apply { colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(m) } }
    return modifier.drawWithContent {
        drawIntoCanvas { cv -> cv.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint); drawContent(); cv.restore() }
    }
}

/** The theme's background, drawn behind the whole app (and Now Playing). [dim] darkens it so text stays readable. */
@Composable
internal fun ThemeBackground(id: Int, modifier: Modifier = Modifier, dim: Float = 0.35f, preview: Boolean = false) {
    val t = WavesThemes.of(id) ?: return
    @Suppress("NAME_SHADOWING") val dim = dim + (1f - dim) * MusicSettings.themeDark
    val graded = themeGrade(modifier)
    @Suppress("NAME_SHADOWING") val modifier = graded
    if (!WavesThemes.isFx(id)) {
        Box(modifier.fillMaxSize().background(Brush.linearGradient(t.colors, start = Offset.Zero, end = Offset(900f, 2200f)))
            .background(Color.Black.copy(alpha = dim)))
        return
    }
    val anim = rememberInfiniteTransition(label = "fx")
    val p by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "p")
    val q by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(5_000), RepeatMode.Reverse), label = "q")
    val stars = remember { val r = java.util.Random(11); List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 2.2f, r.nextFloat()) } }
    val tsec by anim.animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t")
    val kind = id - 100
    val buf = remember(id) { if (Fx2.isField(kind)) Fx2.FieldBuf(if (preview) 0.12f else 0.033f) else null }
    Canvas(modifier.fillMaxSize()) {
        when {
            kind < 8 -> drawFx(kind, t.colors, p, q, stars)
            buf != null -> with(Fx2) { drawField(kind, tsec, buf) }
            else -> with(Fx2) { drawShapes(kind, tsec, stars) }
        }
        drawRect(Color.Black.copy(alpha = dim))
    }
}

private fun DrawScope.blob(color: Color, x: Float, y: Float, r: Float) =
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset(x, y), r), r, Offset(x, y))

private fun DrawScope.drawFx(kind: Int, cs: List<Color>, p: Float, q: Float, stars: List<FloatArray>) {
    val w = size.width; val h = size.height
    val a = p * 2 * PI.toFloat()
    when (kind) {
        0 -> { // Aurora: soft curtains of light drifting
            drawRect(Color(0xFF020617))
            for (i in 0 until 3) {
                val x = w * (0.5f + 0.4f * sin(a + i * 2.1f)); val y = h * (0.3f + 0.25f * cos(a * 0.7f + i))
                blob(cs[i].copy(alpha = 0.55f), x, y, w * (0.8f + 0.2f * q))
            }
        }
        1 -> { // Lava lamp: big warm blobs rising and falling
            drawRect(Color(0xFF1A0500))
            for (i in 0 until 5) {
                val y = h * (1.1f - ((p + i * 0.21f) % 1f) * 1.2f)
                val x = w * (0.2f + 0.6f * ((i * 0.37f) % 1f)) + 40f * sin(a * 2 + i)
                blob(cs[i % 3].copy(alpha = 0.75f), x, y, w * (0.32f + 0.08f * sin(a + i)))
            }
        }
        2 -> { // Starfield: twinkling stars slowly drifting
            drawRect(Brush.verticalGradient(listOf(cs[0], cs[1])))
            stars.forEach { s ->
                val x = (s[0] * w + p * w * 0.15f * s[2]) % w; val y = s[1] * h
                val tw = 0.4f + 0.6f * ((sin(a * 6 + s[3] * 20f) + 1f) / 2f)
                drawCircle(Color.White.copy(alpha = tw), s[2] * 1.6f, Offset(x, y))
            }
        }
        3 -> { // Rainbow flow: colours turning round
            val hues = List(7) { Color.hsv(((p * 360f) + it * 60f) % 360f, 0.85f, 0.9f) }
            rotate(p * 360f) { drawRect(Brush.sweepGradient(hues, Offset(w / 2, h / 2)), topLeft = Offset(-w, -h), size = androidx.compose.ui.geometry.Size(w * 3, h * 3)) }
        }
        4 -> { // Ocean waves: layered moving waves
            drawRect(Brush.verticalGradient(listOf(cs[0], Color(0xFF000814))))
            for (layer in 0 until 4) {
                val path = Path()
                val base = h * (0.45f + layer * 0.14f)
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w + 20f) {
                    path.lineTo(x, base + 30f * sin(x / w * 2 * PI.toFloat() * (1.5f + layer * 0.4f) + a * (1 + layer * 0.5f)))
                    x += 20f
                }
                path.lineTo(w, h); path.close()
                drawPath(path, (if (layer % 2 == 0) cs[1] else cs[2]).copy(alpha = 0.22f + layer * 0.08f))
            }
        }
        5 -> { // Disco: coloured beams sweeping from the top
            drawRect(Color(0xFF0A0012))
            for (i in 0 until 6) {
                val ang = sin(a * 2 + i * 1.3f) * 35f
                rotate(ang, Offset(w * (i + 0.5f) / 6f, 0f)) {
                    drawRect(Brush.verticalGradient(listOf(cs[i % 3].copy(alpha = 0.55f), Color.Transparent)),
                        topLeft = Offset(w * (i + 0.5f) / 6f - 30f, 0f), size = androidx.compose.ui.geometry.Size(60f, h))
                }
            }
            blob(cs[(p * 30).toInt() % 3].copy(alpha = 0.25f * q), w / 2, h * 0.4f, w)
        }
        6 -> { // Fireflies: glowing dots floating
            drawRect(Brush.verticalGradient(listOf(cs[0], cs[1])))
            stars.take(40).forEachIndexed { i, s ->
                val x = w * (s[0] + 0.05f * sin(a * 3 + i)); val y = h * ((s[1] - p * 0.3f * s[2] + 2f) % 1f)
                val glow = (sin(a * 8 + s[3] * 30f) + 1f) / 2f
                blob(cs[2].copy(alpha = 0.7f * glow), x, y, 14f + 10f * s[2])
            }
        }
        else -> { // Neon grid: retro grid moving towards you
            drawRect(Brush.verticalGradient(listOf(cs[0], Color(0xFF2A0045))))
            blob(cs[1].copy(alpha = 0.45f), w / 2, h * 0.45f, w * 0.6f)
            val horizon = h * 0.55f
            for (i in -10..10) drawLine(cs[2].copy(alpha = 0.6f), Offset(w / 2 + i * w * 0.04f, horizon), Offset(w / 2 + i * w * 0.4f, h), 2f)
            for (j in 0 until 12) {
                val f = ((j + p * 4f) % 12f) / 12f
                val y = horizon + (h - horizon) * f * f
                drawLine(cs[1].copy(alpha = 0.3f + 0.6f * f), Offset(0f, y), Offset(w, y), 2f)
            }
        }
    }
}

/** Settings > Themes: tap one to use it straight away. */
@Composable
internal fun ThemesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun pick(id: Int) { MusicSettings.theme = id; MusicSettings.save(ctx) }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        item(span = { GridItemSpan(3) }) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(MusicIcons.arrowBack, "Back") }
                Text("Themes", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        // test 312: brightness; test 315: full colour controls, all in small steps with − and +
        item(span = { GridItemSpan(3) }) { ThemeAdjustCard() }
        item(span = { GridItemSpan(3) }) { Text("Colour themes", Modifier.padding(6.dp), fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
        item { ThemeTile("Plain", MusicSettings.theme == -1, { Box(Modifier.fillMaxSize().background(Color.Black)) }) { pick(-1) } }
        items(WavesThemes.colour.size) { i ->
            ThemeTile(WavesThemes.colour[i].name, MusicSettings.theme == i, { ThemeBackground(i, dim = 0.1f) }) { pick(i) }
        }
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.padding(6.dp, top = 18.dp)) {
                Text("✨ FX themes", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("These ones move. They use a little more battery.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        items(WavesThemes.fx.size) { i ->
            ThemeTile(WavesThemes.fx[i].name, MusicSettings.theme == 100 + i, { ThemeBackground(100 + i, dim = 0.05f, preview = true) }) { pick(100 + i) }
        }
    }
}

@Composable
private fun ThemeTile(name: String, on: Boolean, preview: @Composable () -> Unit, onClick: () -> Unit) {
    Column(Modifier.padding(5.dp).clip(RoundedCornerShape(14.dp)).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(14.dp))
            .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))) {
            preview()
            if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(name, Modifier.padding(top = 4.dp, bottom = 2.dp), fontSize = 13.sp, maxLines = 1)
    }
}

@Suppress("unused") private val unusedArr = Arrangement.Center

@Composable
private fun ThemeAdjustCard() {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    fun save() = MusicSettings.save(ctx)
    @Composable
    fun row(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, show: String, set: (Float) -> Unit) {
        fun put(v: Float) { set((Math.round(v.coerceIn(range.start, range.endInclusive) / step) * step)); save() }
        Text("$label: $show", Modifier.padding(top = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.FilledTonalIconButton({ put(value - step) }, enabled = value > range.start + step / 2) { Text("−", fontSize = 20.sp) }
            androidx.compose.material3.Slider(value, { put(it) }, Modifier.weight(1f).padding(horizontal = 4.dp), valueRange = range,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            androidx.compose.material3.FilledTonalIconButton({ put(value + step) }, enabled = value < range.endInclusive - step / 2) { Text("+", fontSize = 20.sp) }
        }
    }
    fun pct(v: Float) = "${Math.round(v * 100)}%"
    fun signed(v: Float) = (if (v > 0) "+" else "") + Math.round(v * 100) + "%"
    Column(Modifier.fillMaxWidth().padding(6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(12.dp)) {
        Text("🎛️ Theme colours", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Tune any theme how you like, moving ones too. Tap a look to start, then fine-tune.", fontSize = 12.sp, color = dim)
        // ready-made looks: brightness, contrast, colour, warmth, hue, shadows
        val looks = listOf(
            "🌑 Moody dark" to floatArrayOf(0.45f, 1.15f, 1.1f, 0f, 0f, -0.3f),
            "🌈 Vivid" to floatArrayOf(0f, 1.15f, 1.5f, 0f, 0f, 0f),
            "🍬 Pastel" to floatArrayOf(0f, 0.8f, 0.6f, 0f, 0f, 0.3f),
            "🌅 Warm" to floatArrayOf(0.1f, 1f, 1.1f, 0.6f, 0f, 0f),
            "🧊 Cool" to floatArrayOf(0.1f, 1f, 1f, -0.6f, 0f, 0f),
            "🖤 Mono" to floatArrayOf(0.2f, 1.2f, 0f, 0f, 0f, 0f),
            "🔮 Shift" to floatArrayOf(0f, 1f, 1.1f, 0f, 120f, 0f),
        )
        androidx.compose.foundation.lazy.LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(looks.size) { i ->
                val (name, v) = looks[i]
                androidx.compose.material3.FilterChip(false, {
                    MusicSettings.themeDark = v[0]; MusicSettings.themeContrast = v[1]; MusicSettings.themeSat = v[2]
                    MusicSettings.themeWarm = v[3]; MusicSettings.themeHue = v[4]; MusicSettings.themeShadow = v[5]; save()
                }, { Text(name) })
            }
        }
        val bright = 1f - MusicSettings.themeDark
        row("☀️ Brightness", bright, 0.1f..1f, 0.05f, pct(bright)) { MusicSettings.themeDark = (1f - it).coerceIn(0f, 0.9f) }
        row("◐ Contrast", MusicSettings.themeContrast, 0.5f..1.5f, 0.05f, pct(MusicSettings.themeContrast)) { MusicSettings.themeContrast = it }
        row("🎨 Colour strength", MusicSettings.themeSat, 0f..2f, 0.05f, pct(MusicSettings.themeSat)) { MusicSettings.themeSat = it }
        row("🔥 Warmth", MusicSettings.themeWarm, -1f..1f, 0.05f, if (MusicSettings.themeWarm == 0f) "neutral" else if (MusicSettings.themeWarm > 0) "warmer ${signed(MusicSettings.themeWarm)}" else "cooler ${signed(MusicSettings.themeWarm)}") { MusicSettings.themeWarm = it }
        row("🌈 Hue shift", MusicSettings.themeHue, -180f..180f, 5f, "${Math.round(MusicSettings.themeHue)}°") { MusicSettings.themeHue = it }
        row("🌓 Shadows", MusicSettings.themeShadow, -1f..1f, 0.05f, if (MusicSettings.themeShadow == 0f) "as made" else if (MusicSettings.themeShadow > 0) "lifted ${signed(MusicSettings.themeShadow)}" else "deeper ${signed(MusicSettings.themeShadow)}") { MusicSettings.themeShadow = it }
        Text("Tip: for darker colours but the same brightness, turn up Colour strength and set Shadows to deeper.", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = dim)
        androidx.compose.material3.TextButton({
            MusicSettings.themeDark = 0f; MusicSettings.themeContrast = 1f; MusicSettings.themeSat = 1f; MusicSettings.themeWarm = 0f
            MusicSettings.themeHue = 0f; MusicSettings.themeShadow = 0f; save()
        }) { Text("↺ Back to normal", color = Accent) }
    }
}
