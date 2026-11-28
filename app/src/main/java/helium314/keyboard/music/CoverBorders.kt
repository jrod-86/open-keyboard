// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test 388 (Jarrod): 5 animated perimeter border effects around the Now Playing album cover.
 * 0 = Off, 1 = Neon flow, 2 = Comet chase, 3 = Electric pulse, 4 = Sound bars, 5 = Orbiting sparkles.
 */
object CoverBorders {
    val names = listOf("Off", "Neon flow", "Comet chase", "Electric pulse", "Sound bars", "Orbiting sparkles")

    private val neonColors = listOf(
        Color(0xFFFF2E93),
        Color(0xFFFFB800),
        Color(0xFF00F5A0),
        Color(0xFF00D9F5),
        Color(0xFF8B5CF6),
        Color(0xFFFF2E93),
    )

    /** Returns (point, outwardUnitNormal) at normalized distance u in [0, 1) around a rounded rect. */
    private fun perimeterPoint(uRaw: Float, left: Float, top: Float, w: Float, h: Float, rRaw: Float): Pair<Offset, Offset> {
        val r = rRaw.coerceIn(0f, minOf(w, h) * 0.5f)
        val straightW = (w - 2f * r).coerceAtLeast(0f)
        val straightH = (h - 2f * r).coerceAtLeast(0f)
        val arcLen = (0.5 * PI * r).toFloat()
        val total = 2f * straightW + 2f * straightH + 4f * arcLen
        if (total <= 0f) return Offset(left, top) to Offset(0f, -1f)
        var d = ((uRaw % 1f) + 1f) % 1f * total

        // 1. Top edge (left -> right)
        if (d < straightW) return Offset(left + r + d, top) to Offset(0f, -1f)
        d -= straightW
        // 2. Top-right corner (-90° -> 0°)
        if (d < arcLen) {
            val a = -PI / 2.0 + (d / arcLen) * (PI / 2.0)
            val cx = left + w - r; val cy = top + r
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
            return Offset(cx + nx * r, cy + ny * r) to Offset(nx, ny)
        }
        d -= arcLen
        // 3. Right edge (top -> bottom)
        if (d < straightH) return Offset(left + w, top + r + d) to Offset(1f, 0f)
        d -= straightH
        // 4. Bottom-right corner (0° -> 90°)
        if (d < arcLen) {
            val a = (d / arcLen) * (PI / 2.0)
            val cx = left + w - r; val cy = top + h - r
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
            return Offset(cx + nx * r, cy + ny * r) to Offset(nx, ny)
        }
        d -= arcLen
        // 5. Bottom edge (right -> left)
        if (d < straightW) return Offset(left + w - r - d, top + h) to Offset(0f, 1f)
        d -= straightW
        // 6. Bottom-left corner (90° -> 180°)
        if (d < arcLen) {
            val a = PI / 2.0 + (d / arcLen) * (PI / 2.0)
            val cx = left + r; val cy = top + h - r
            val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
            return Offset(cx + nx * r, cy + ny * r) to Offset(nx, ny)
        }
        d -= arcLen
        // 7. Left edge (bottom -> top)
        if (d < straightH) return Offset(left, top + h - r - d) to Offset(-1f, 0f)
        d -= straightH
        // 8. Top-left corner (180° -> 270°)
        val a = PI + (d / arcLen).coerceIn(0f, 1f) * (PI / 2.0)
        val cx = left + r; val cy = top + r
        val nx = cos(a).toFloat(); val ny = sin(a).toFloat()
        return Offset(cx + nx * r, cy + ny * r) to Offset(nx, ny)
    }

    private fun DrawScope.drawBorder(style: Int, t: Float, pulse: Float, pad: Float, cornerPx: Float, accent: Color) {
        val left = pad
        val top = pad
        val w = (size.width - pad * 2f).coerceAtLeast(1f)
        val h = (size.height - pad * 2f).coerceAtLeast(1f)
        val r = cornerPx.coerceAtMost(minOf(w, h) * 0.45f)

        when (style) {
            1 -> { // Neon flow: rotating liquid rainbow sweep with breathing outer glow
                val brush = Brush.sweepGradient(neonColors, center = Offset(size.width / 2f, size.height / 2f))
                val glowW = (9f + 5f * pulse).dp.toPx()
                val coreW = (3.2f + 1.2f * pulse).dp.toPx()
                rotate(degrees = (t * 72f) % 360f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r, r),
                        alpha = 0.35f + 0.25f * pulse,
                        style = Stroke(width = glowW),
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r, r),
                        alpha = 0.95f,
                        style = Stroke(width = coreW),
                    )
                }
            }

            2 -> { // Comet chase: two luminous comets racing in opposite directions with fading trails
                // Subtle base frame
                drawRoundRect(
                    color = accent.copy(alpha = 0.18f),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                val headA = (t * 0.26f) % 1f
                val headB = (1f - ((t * 0.26f + 0.5f) % 1f)) % 1f
                val trailSteps = 26
                val trailSpan = 0.24f
                fun drawComet(head: Float, dir: Float, headCol: Color, tailCol: Color) {
                    var prev = perimeterPoint(head, left, top, w, h, r).first
                    for (i in 1..trailSteps) {
                        val frac = i.toFloat() / trailSteps
                        val u = head - dir * frac * trailSpan
                        val pt = perimeterPoint(u, left, top, w, h, r).first
                        val alpha = (1f - frac) * (1f - frac)
                        val stroke = (4.8f * (1f - frac * 0.7f) + 1.5f * pulse).dp.toPx()
                        val col = lerpColor(headCol, tailCol, frac)
                        drawLine(col.copy(alpha = alpha * 0.32f), prev, pt, strokeWidth = stroke * 2.3f, cap = StrokeCap.Round)
                        drawLine(col.copy(alpha = alpha * 0.95f), prev, pt, strokeWidth = stroke, cap = StrokeCap.Round)
                        prev = pt
                    }
                    val hp = perimeterPoint(head, left, top, w, h, r).first
                    drawCircle(headCol.copy(alpha = 0.35f), radius = (9f + 3f * pulse).dp.toPx(), center = hp)
                    drawCircle(Color.White.copy(alpha = 0.95f), radius = (3.4f + 1.2f * pulse).dp.toPx(), center = hp)
                }
                drawComet(headA, 1f, Color(0xFF00F0FF), Color(0xFF7C4DFF))
                drawComet(headB, -1f, Color(0xFFFF3CAC), Color(0xFFFFB800))
            }

            3 -> { // Electric pulse: crackling neon lightning arcs dancing around the 4 edges
                val arcs = 4
                val segs = 22
                val amp = (3.5f + 4.5f * pulse).dp.toPx()
                for (a in 0 until arcs) {
                    val startU = (t * 0.18f + a * 0.25f) % 1f
                    val span = 0.18f + 0.04f * sin(t * 5f + a)
                    val path = Path()
                    val color = if (a % 2 == 0) Color(0xFF00E5FF) else Color(0xFFC084FC)
                    for (s in 0..segs) {
                        val frac = s.toFloat() / segs
                        val u = startU + frac * span
                        val (pt, n) = perimeterPoint(u, left, top, w, h, r)
                        val env = sin(frac * PI).toFloat()
                        val jitter = sin(t * 28f + s * 4.3f + a * 9.1f) * 0.65f + cos(t * 19f - s * 7.1f + a * 3.2f) * 0.35f
                        val off = jitter * amp * env
                        val p = Offset(pt.x + n.x * off, pt.y + n.y * off)
                        if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, color.copy(alpha = 0.32f + 0.2f * pulse), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, color.copy(alpha = 0.85f), style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            4 -> { // Sound bars: 48 mini equaliser bars radiating outward around the perimeter
                val count = 48
                val maxBar = (11f + 5f * pulse).dp.toPx()
                val minBar = 2.5.dp.toPx()
                val barW = 2.6.dp.toPx()
                for (i in 0 until count) {
                    val u = i.toFloat() / count
                    val (pt, n) = perimeterPoint(u, left, top, w, h, r)
                    val wave = 0.5f + 0.5f * sin(u * 2f * PI.toFloat() * 4f - t * 6.5f)
                    val wave2 = 0.5f + 0.5f * cos(u * 2f * PI.toFloat() * 7f + t * 4.2f)
                    val level = (0.25f + 0.5f * wave * wave2 + 0.45f * pulse).coerceIn(0.15f, 1.15f)
                    val len = minBar + (maxBar - minBar) * level
                    val colIdx = ((u * (neonColors.size - 1) + t * 0.25f) % (neonColors.size - 1) + (neonColors.size - 1)) % (neonColors.size - 1)
                    val ci = colIdx.toInt()
                    val col = lerpColor(neonColors[ci], neonColors[ci + 1], colIdx - ci)
                    val p0 = Offset(pt.x + n.x * 1.5.dp.toPx(), pt.y + n.y * 1.5.dp.toPx())
                    val p1 = Offset(pt.x + n.x * (1.5.dp.toPx() + len), pt.y + n.y * (1.5.dp.toPx() + len))
                    drawLine(col.copy(alpha = 0.35f), p0, p1, strokeWidth = barW * 2f, cap = StrokeCap.Round)
                    drawLine(col.copy(alpha = 0.95f), p0, p1, strokeWidth = barW, cap = StrokeCap.Round)
                }
            }

            5 -> { // Orbiting sparkles: twinkling starlight & glowing orbs drifting around the cover
                val sparkleCols = listOf(Color(0xFFFFFFFF), Color(0xFFFFE066), Color(0xFF00F0FF), Color(0xFFFF6EC7), Color(0xFFA78BFA))
                val count = 18
                for (i in 0 until count) {
                    val speed = 0.055f + (i % 4) * 0.018f
                    val dir = if (i % 3 == 0) -1f else 1f
                    val u = (i.toFloat() / count + dir * t * speed)
                    val (pt, n) = perimeterPoint(u, left, top, w, h, r)
                    val bob = (sin(t * 3.2f + i * 1.9f) * 3.5f + 2f + 2.5f * pulse).dp.toPx()
                    val center = Offset(pt.x + n.x * bob, pt.y + n.y * bob)
                    val twinkle = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 5.5f + i * 2.7f)))
                    val col = sparkleCols[i % sparkleCols.size]
                    val rad = (2.6f + (i % 3) * 1.1f + 1.4f * pulse).dp.toPx() * (0.65f + 0.35f * twinkle)
                    drawCircle(col.copy(alpha = 0.28f * twinkle), radius = rad * 2.4f, center = center)
                    // 4-point star cross
                    val arm = rad * 1.7f
                    drawLine(col.copy(alpha = 0.95f * twinkle), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(col.copy(alpha = 0.95f * twinkle), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = 0.95f * twinkle), radius = rad * 0.45f, center = center)
                }
            }
        }
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f,
        )
    }

    /** Renders the chosen perimeter border animation around a [side]×[side] album cover. */
    @Composable
    fun Overlay(
        style: Int,
        side: Dp,
        corner: Dp = 22.dp,
        playing: Boolean = true,
        beatPulse: Float = 0f,
        modifier: Modifier = Modifier,
    ) {
        if (style !in 1..5) return
        val accent = Accent
        var t by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(style, playing) {
            while (playing) {
                t += 0.033f
                delay(33)
            }
        }
        val pad = 16.dp
        Canvas(modifier.size(side + pad * 2)) {
            drawBorder(style, t, beatPulse, pad.toPx(), corner.toPx(), accent)
        }
    }
}

/** Live preview card for Album Cover Border Animation inside Design studio. */
@Composable
internal fun CoverBorderPreview(style: Int) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(style) {
        if (style == 0) return@LaunchedEffect
        var phase = 0f
        while (true) {
            phase = (phase + 0.033f) % 0.55f
            pulse = kotlin.math.exp((-phase * 7.5f).toDouble()).toFloat()
            delay(33)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
            CoverBorders.Overlay(style = style, side = 56.dp, corner = 12.dp, playing = true, beatPulse = pulse)
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Accent, Color(0xFF7C4DFF)))))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            if (style == 0) "No border animation" else CoverBorders.names.getOrElse(style) { "" } + " preview",
            color = dim,
            fontSize = 13.sp,
        )
    }
}
