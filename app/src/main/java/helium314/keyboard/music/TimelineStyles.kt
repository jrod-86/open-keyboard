// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Open Waves test 362 (Jarrod): Customisable Now Playing & Mini Player timeline styles + colour/animation studio.
 * 10 styles (Wavy, Classic, Musical notes, Sound bars, Heartbeat, Neon laser, Bouncing dots, Lightning, LED segments, Double wave)
 * and 14 colour modes (Match theme, 9 solid swatches, 4 animated flowing gradients).
 */
object TimelineStyles {
    val styles = listOf(
        "Wavy",
        "Classic",
        "Musical notes",
        "Sound bars",
        "Heartbeat",
        "Neon laser",
        "Bouncing dots",
        "Lightning",
        "LED segments",
        "Double wave",
    )

    data class ColorChoice(val name: String, val solid: Color?, val gradient: List<Color>?)

    val colors = listOf(
        ColorChoice("Match theme", null, null),
        ColorChoice("White", Color(0xFFFFFFFF), null),
        ColorChoice("Cyan", Color(0xFF00E5FF), null),
        ColorChoice("Blue", Color(0xFF3D7CFF), null),
        ColorChoice("Violet", Color(0xFFB366FF), null),
        ColorChoice("Pink", Color(0xFFFF4081), null),
        ColorChoice("Red", Color(0xFFFF3B30), null),
        ColorChoice("Orange", Color(0xFFFF8A00), null),
        ColorChoice("Gold", Color(0xFFFFD600), null),
        ColorChoice("Green", Color(0xFF00E676), null),
        ColorChoice("🌈 Rainbow", null, listOf(
            Color(0xFFFF3B6B), Color(0xFFFF9F1C), Color(0xFFFFE600), Color(0xFF00E676),
            Color(0xFF00E5FF), Color(0xFF536DFE), Color(0xFFD500F9), Color(0xFFFF3B6B),
        )),
        ColorChoice("🌅 Sunset", null, listOf(
            Color(0xFFFF2E63), Color(0xFFFF6B35), Color(0xFFFFD166), Color(0xFFFF4D8D), Color(0xFFFF2E63),
        )),
        ColorChoice("🌊 Ocean", null, listOf(
            Color(0xFF1A73E8), Color(0xFF00B4D8), Color(0xFF48CAE4), Color(0xFF00F5D4), Color(0xFF1A73E8),
        )),
        ColorChoice("⚡ Neon", null, listOf(
            Color(0xFF9D4EDD), Color(0xFFFF007F), Color(0xFF00F5FF), Color(0xFF7B2CBF), Color(0xFF9D4EDD),
        )),
    )

    fun brushFor(colorIdx: Int, fallback: Color, phase: Float, width: Float): Brush {
        val c = colors.getOrElse(colorIdx) { colors[0] }
        val grad = c.gradient
        if (grad != null && width > 1f) {
            val shift = ((phase * 0.14f) % 1f + 1f) % 1f
            val n = grad.size - 1
            val shifted = List(grad.size) { i ->
                val f = ((i.toFloat() / n) + shift) % 1f
                samplePalette(grad, f)
            }
            return Brush.horizontalGradient(shifted, startX = 0f, endX = width)
        }
        val solid = c.solid ?: fallback
        return Brush.horizontalGradient(listOf(solid, solid))
    }

    fun accentColorAt(colorIdx: Int, fallback: Color, frac: Float, phase: Float): Color {
        val c = colors.getOrElse(colorIdx) { colors[0] }
        val grad = c.gradient
        if (grad != null) {
            val shift = ((phase * 0.14f) % 1f + 1f) % 1f
            return samplePalette(grad, (frac + shift) % 1f)
        }
        return c.solid ?: fallback
    }

    private fun samplePalette(palette: List<Color>, t: Float): Color {
        val n = palette.size - 1
        if (n <= 0) return palette.firstOrNull() ?: Color.White
        val scaled = t.coerceIn(0f, 0.9999f) * n
        val i = scaled.toInt().coerceIn(0, n - 1)
        val f = scaled - i
        val a = palette[i]; val b = palette[i + 1]
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f,
        )
    }
}

/**
 * Shared animated progress bar used on Now Playing, Mini Player, and Design Studio preview.
 * Runs at a battery-friendly ~30 fps only while playing (and completely still when paused).
 */
@Composable
internal fun StyledProgressLine(
    frac: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    defaultColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    strokeDp: Float = 3.5f,
    ampDp: Float = 3.5f,
    styleIdx: Int = MusicSettings.timelineStyle,
    colorIdx: Int = MusicSettings.timelineColor,
) {
    val activeAmp by animateFloatAsState(if (playing) 1f else 0f, tween(350), label = "tlAmp")
    val isGrad = TimelineStyles.colors.getOrElse(colorIdx) { TimelineStyles.colors[0] }.gradient != null
    val needsAnim = playing && (styleIdx != 1 || isGrad)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(needsAnim) {
        if (!needsAnim) return@LaunchedEffect
        val t0 = System.nanoTime()
        val p0 = phase
        while (true) {
            phase = p0 + ((System.nanoTime() - t0) / 1e9f) * 5.2f
            delay(33) // ~30 fps cap
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val stroke = strokeDp.dp.toPx()
        val splitX = w * frac.coerceIn(0f, 1f)
        val brush = TimelineStyles.brushFor(colorIdx, defaultColor, phase, w.coerceAtLeast(1f))
        val amp = (ampDp.dp.toPx()).coerceAtMost(h * 0.42f) * activeAmp

        // Unplayed track (most styles use a clean rounded line)
        if (splitX < w && styleIdx != 8) {
            drawLine(
                color = trackColor,
                start = Offset(splitX, cy),
                end = Offset(w, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        if (splitX <= 1f && styleIdx != 8) return@Canvas

        when (styleIdx) {
            // 0: Wavy (flowing sine wave)
            0 -> {
                if (amp < 0.25f) {
                    drawLine(brush, Offset(0f, cy), Offset(splitX, cy), stroke, StrokeCap.Round)
                } else {
                    val path = Path()
                    val wavelength = 24.dp.toPx()
                    val step = 3.dp.toPx().coerceAtLeast(2f)
                    val fadeLen = 8.dp.toPx()
                    var x = 0f; var first = true
                    while (x <= splitX) {
                        val ef = minOf(1f, x / fadeLen, (splitX - x) / fadeLen).coerceAtLeast(0f)
                        val y = cy + sin((x / wavelength) * (2.0 * PI) - phase).toFloat() * amp * ef
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        x += step
                    }
                    path.lineTo(splitX, cy)
                    drawPath(path, brush, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            // 1: Classic (straight bar)
            1 -> {
                drawLine(brush, Offset(0f, cy), Offset(splitX, cy), stroke, StrokeCap.Round)
            }

            // 2: Musical notes (connected notes dancing/jumping along the played line!)
            2 -> {
                // Connecting flowing line underneath the notes
                drawLine(brush, Offset(0f, cy), Offset(splitX, cy), (stroke * 0.65f).coerceAtLeast(1.5f), StrokeCap.Round)
                val noteSpacing = (if (h >= 16.dp.toPx()) 22.dp else 15.dp).toPx()
                val headR = (h * 0.16f).coerceIn(1.8.dp.toPx(), 3.6.dp.toPx())
                val stemH = (h * 0.42f).coerceIn(4.dp.toPx(), 9.5.dp.toPx())
                val bounceAmp = (h * 0.22f) * activeAmp
                val stemW = (stroke * 0.55f).coerceAtLeast(1.3.dp.toPx())
                var idx = 0
                var x = noteSpacing * 0.55f
                while (x < splitX - headR * 1.4f) {
                    val bounce = abs(sin(phase * 1.15f - idx * 0.9f)) * bounceAmp
                    val ny = cy + headR * 0.7f - bounce
                    val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, x / w.coerceAtLeast(1f), phase)
                    val paired = (idx % 3 == 1) && (x + noteSpacing * 0.52f < splitX - headR * 1.4f)
                    if (paired) {
                        // Beamed eighth notes (♫) jumping together!
                        val x2 = x + noteSpacing * 0.48f
                        val b2 = abs(sin(phase * 1.15f - (idx + 0.5f) * 0.9f)) * bounceAmp
                        val ny2 = cy + headR * 0.7f - b2
                        drawOval(c, Offset(x - headR * 1.15f, ny - headR * 0.85f), Size(headR * 2.3f, headR * 1.7f))
                        drawOval(c, Offset(x2 - headR * 1.15f, ny2 - headR * 0.85f), Size(headR * 2.3f, headR * 1.7f))
                        val top1 = Offset(x + headR * 0.9f, (ny - stemH).coerceAtLeast(1f))
                        val top2 = Offset(x2 + headR * 0.9f, (ny2 - stemH).coerceAtLeast(1f))
                        drawLine(c, Offset(x + headR * 0.9f, ny), top1, stemW, StrokeCap.Round)
                        drawLine(c, Offset(x2 + headR * 0.9f, ny2), top2, stemW, StrokeCap.Round)
                        drawLine(c, top1, top2, stemW * 1.45f, StrokeCap.Round)
                        x += noteSpacing * 1.15f
                    } else {
                        // Single eighth note (♪)
                        drawOval(c, Offset(x - headR * 1.15f, ny - headR * 0.85f), Size(headR * 2.3f, headR * 1.7f))
                        val top = Offset(x + headR * 0.9f, (ny - stemH).coerceAtLeast(1f))
                        drawLine(c, Offset(x + headR * 0.9f, ny), top, stemW, StrokeCap.Round)
                        val flag = Path().apply {
                            moveTo(top.x, top.y)
                            quadraticTo(top.x + headR * 2.1f, top.y + stemH * 0.28f, top.x + headR * 1.3f, top.y + stemH * 0.62f)
                        }
                        drawPath(flag, c, style = Stroke(stemW, cap = StrokeCap.Round))
                        x += noteSpacing
                    }
                    idx++
                }
            }

            // 3: Sound bars (mini vertical EQ bars pulsing along the played section)
            3 -> {
                val barW = (if (h >= 16.dp.toPx()) 3.dp else 2.2.dp).toPx()
                val gap = (if (h >= 16.dp.toPx()) 2.5.dp else 2.dp).toPx()
                val step = barW + gap
                val maxH = (h * 0.88f).coerceAtLeast(stroke * 1.6f)
                val minH = (stroke * 0.85f).coerceAtLeast(2f)
                var i = 0
                var x = barW / 2f
                while (x <= splitX) {
                    val v1 = 0.5f + 0.5f * sin(phase * 1.5f + i * 0.72f)
                    val v2 = 0.5f + 0.5f * cos(phase * 0.9f - i * 1.31f)
                    val lvl = (0.28f + 0.72f * (v1 * 0.6f + v2 * 0.4f)) * activeAmp + (1f - activeAmp) * 0.35f
                    val bh = (minH + (maxH - minH) * lvl).coerceAtMost(h)
                    val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, x / w.coerceAtLeast(1f), phase)
                    drawLine(c, Offset(x, cy - bh / 2f), Offset(x, cy + bh / 2f), barW, StrokeCap.Round)
                    x += step
                    i++
                }
            }

            // 4: Heartbeat (ECG pulse traveling down the played bar)
            4 -> {
                val path = Path()
                val period = 46.dp.toPx()
                val step = 2.dp.toPx().coerceAtLeast(1.5f)
                val peak = (h * 0.44f) * (0.25f + 0.75f * activeAmp)
                var x = 0f; var first = true
                while (x <= splitX) {
                    val u = (((x - phase * 10.dp.toPx()) % period) + period) % period / period
                    val ecg = when {
                        u in 0.10f..0.18f -> -sin((u - 0.10f) / 0.08f * PI).toFloat() * 0.25f
                        u in 0.24f..0.29f -> sin((u - 0.24f) / 0.05f * PI).toFloat() * 0.35f
                        u in 0.29f..0.37f -> -sin((u - 0.29f) / 0.08f * PI).toFloat() * 1.0f
                        u in 0.37f..0.43f -> sin((u - 0.37f) / 0.06f * PI).toFloat() * 0.55f
                        u in 0.52f..0.64f -> -sin((u - 0.52f) / 0.12f * PI).toFloat() * 0.30f
                        else -> 0f
                    }
                    val ef = minOf(1f, x / 6.dp.toPx(), (splitX - x) / 6.dp.toPx()).coerceAtLeast(0f)
                    val y = cy + ecg * peak * ef
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    x += step
                }
                path.lineTo(splitX, cy)
                drawPath(path, brush, style = Stroke(stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // 5: Neon laser (bright glowing beam with soft pulsing halo)
            5 -> {
                val glow = 0.85f + 0.15f * sin(phase * 1.4f) * activeAmp
                val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, frac, phase)
                drawLine(c.copy(alpha = 0.22f * glow), Offset(0f, cy), Offset(splitX, cy), stroke * 3.4f, StrokeCap.Round)
                drawLine(c.copy(alpha = 0.45f * glow), Offset(0f, cy), Offset(splitX, cy), stroke * 1.9f, StrokeCap.Round)
                drawLine(brush, Offset(0f, cy), Offset(splitX, cy), stroke, StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.75f), Offset(0f, cy), Offset(splitX, cy), stroke * 0.38f, StrokeCap.Round)
            }

            // 6: Bouncing dots (glowing beads rippling in a wave)
            6 -> {
                val r = (stroke * 0.72f).coerceIn(2.dp.toPx(), 3.6.dp.toPx())
                val step = r * 3.1f
                var i = 0
                var x = r
                while (x <= splitX) {
                    val y = cy + sin(phase * 1.3f - i * 0.6f) * amp
                    val scale = 0.85f + 0.25f * (0.5f + 0.5f * cos(phase * 1.3f - i * 0.6f)) * activeAmp
                    val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, x / w.coerceAtLeast(1f), phase)
                    drawCircle(c, r * scale, Offset(x, y))
                    x += step
                    i++
                }
            }

            // 7: Lightning (crackling electric zig-zag bolt)
            7 -> {
                val path = Path()
                val seg = (if (h >= 16.dp.toPx()) 9.dp else 7.dp).toPx()
                val jag = (h * 0.36f) * (0.3f + 0.7f * activeAmp)
                val jitterStep = (phase * 2.2f).toInt()
                var x = 0f; var i = 0
                path.moveTo(0f, cy)
                while (x + seg < splitX) {
                    x += seg; i++
                    val sign = if (i % 2 == 0) 1f else -1f
                    val hash = sin((i * 12.9898 + jitterStep * 78.233)).toFloat()
                    val offset = sign * (0.45f + 0.55f * abs(hash)) * jag
                    path.lineTo(x, cy + offset)
                }
                path.lineTo(splitX, cy)
                val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, frac, phase)
                drawPath(path, c.copy(alpha = 0.32f), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Miter))
                drawPath(path, brush, style = Stroke(stroke * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Miter))
            }

            // 8: LED segments (retro segmented LED blocks across the full bar)
            8 -> {
                val segW = (if (h >= 16.dp.toPx()) 7.dp else 5.dp).toPx()
                val gap = (if (h >= 16.dp.toPx()) 3.dp else 2.2.dp).toPx()
                val bh = (stroke * 1.75f).coerceAtMost(h * 0.72f)
                val step = segW + gap
                var x = 0f
                val pulseIdx = ((phase * 3.5f) % 100f)
                var i = 0
                while (x + segW <= w) {
                    val played = x + segW * 0.5f <= splitX
                    if (played) {
                        val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, x / w.coerceAtLeast(1f), phase)
                        val shimmer = if (activeAmp > 0.1f) (0.82f + 0.18f * sin(pulseIdx * 0.45f - i * 0.5f)) else 1f
                        drawRoundRect(c.copy(alpha = shimmer), Offset(x, cy - bh / 2f), Size(segW, bh), CornerRadius(bh / 3f, bh / 3f))
                    } else {
                        drawRoundRect(trackColor, Offset(x, cy - bh / 2f), Size(segW, bh), CornerRadius(bh / 3f, bh / 3f))
                    }
                    x += step; i++
                }
            }

            // 9: Double wave (DNA intertwined ribbons)
            else -> {
                val wavelength = 28.dp.toPx()
                val step = 3.dp.toPx().coerceAtLeast(2f)
                val fadeLen = 8.dp.toPx()
                val p1 = Path(); val p2 = Path()
                var x = 0f; var first = true
                while (x <= splitX) {
                    val ef = minOf(1f, x / fadeLen, (splitX - x) / fadeLen).coerceAtLeast(0f)
                    val s = sin((x / wavelength) * (2.0 * PI) - phase).toFloat() * amp * ef
                    if (first) {
                        p1.moveTo(x, cy + s); p2.moveTo(x, cy - s); first = false
                    } else {
                        p1.lineTo(x, cy + s); p2.lineTo(x, cy - s)
                    }
                    x += step
                }
                p1.lineTo(splitX, cy); p2.lineTo(splitX, cy)
                val c = TimelineStyles.accentColorAt(colorIdx, defaultColor, frac, phase)
                drawPath(p2, c.copy(alpha = 0.48f), style = Stroke(stroke * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p1, brush, style = Stroke(stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Design Studio section for picking the timeline style & colour with a live animated preview. */
@Composable
internal fun TimelineStudioSection(onChanged: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val styleIdx = MusicSettings.timelineStyle
    val colorIdx = MusicSettings.timelineColor

    Column(Modifier.fillMaxWidth()) {
        // Live animated preview card
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF14151B))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${TimelineStyles.styles.getOrElse(styleIdx) { "Wavy" }} · ${TimelineStyles.colors.getOrElse(colorIdx) { TimelineStyles.colors[0] }.name}",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Live preview", color = Accent, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.CenterStart) {
                    val previewFrac = 0.66f
                    StyledProgressLine(
                        frac = previewFrac,
                        playing = true,
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                        defaultColor = Color.White,
                        strokeDp = 3.5f,
                        ampDp = 4.2f,
                        styleIdx = styleIdx,
                        colorIdx = colorIdx,
                    )
                    val thumb = 16.dp
                    val thumbColor = TimelineStyles.accentColorAt(colorIdx, Color.White, previewFrac, 0f)
                    Box(Modifier.offset(x = (maxWidth - thumb) * previewFrac).size(thumb).clip(CircleShape).background(thumbColor))
                }
            }
        }

        Text("Timeline style", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
        Text("Applies to both Now Playing and the Mini Player.", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(TimelineStyles.styles) { i, name ->
                FilterChip(
                    selected = styleIdx == i,
                    onClick = { MusicSettings.timelineStyle = i; onChanged() },
                    label = { Text(name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)),
                )
            }
        }

        Text("Timeline colour", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
        Text("Pick a solid colour or a moving animated gradient.", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = dim)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(TimelineStyles.colors) { i, choice ->
                val sel = colorIdx == i
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) Accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .border(if (sel) 1.5.dp else 1.dp, if (sel) Accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .clickable { MusicSettings.timelineColor = i; onChanged() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dotMod = Modifier.size(14.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    when {
                        choice.gradient != null -> Box(dotMod.background(Brush.horizontalGradient(choice.gradient)))
                        choice.solid != null -> Box(dotMod.background(choice.solid))
                        else -> Box(dotMod.background(Accent))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(choice.name, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
