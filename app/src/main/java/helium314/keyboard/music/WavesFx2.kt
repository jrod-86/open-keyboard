// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FX themes 9 to 30 (test 245, many from Jarrod's inspiration clips).
 * "Field" themes work out a small picture pixel by pixel (90 x 160, about 30 times a second) and stretch it smoothly
 * over the screen; the others draw shapes.
 */
internal object Fx2 {
    // fx list index -> what it is
    const val WARP = 8; const val LEAVES = 9; const val LASER = 10; const val STAR_BLOOM = 11; const val RIPPLES = 12
    const val HYPNO = 13; const val RSPIRAL = 14; const val NEON_RINGS = 15; const val KALEIDO = 16; const val GLASS = 17
    const val SLIME = 18; const val STAR_TUNNEL = 19; const val ZEBRA = 20; const val MANDEL = 21; const val PLASMA = 22
    const val LIQUID = 23; const val WARP_SPEED = 24; const val GALAXY = 25; const val SNOW = 26; const val CONFETTI = 27
    const val EQUALIZER = 28; const val FIRE = 29

    private val fieldKinds = setOf(WARP, STAR_BLOOM, RIPPLES, HYPNO, RSPIRAL, NEON_RINGS, KALEIDO, GLASS, SLIME, ZEBRA, MANDEL, PLASMA, LIQUID)
    fun isField(kind: Int) = kind in fieldKinds

    const val FW = 90
    const val FH = 160
    private const val TAU = (2 * PI).toFloat()

    private val rainbow = IntArray(256) { android.graphics.Color.HSVToColor(floatArrayOf(it * 360f / 256f, 0.9f, 1f)) }
    private fun rb(f: Float): Int = rainbow[((f - floor(f)) * 255.99f).toInt().coerceIn(0, 255)]
    private fun fract(f: Float) = f - floor(f)
    private fun rgb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or ((r * 255).toInt().coerceIn(0, 255) shl 16) or ((g * 255).toInt().coerceIn(0, 255) shl 8) or (b * 255).toInt().coerceIn(0, 255)
    private fun scale(c: Int, k: Float) = rgb(((c shr 16) and 255) / 255f * k, ((c shr 8) and 255) / 255f * k, (c and 255) / 255f * k)
    private fun mix(c1: Int, c2: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        return rgb((((c1 shr 16) and 255) * (1 - k) + ((c2 shr 16) and 255) * k) / 255f,
            (((c1 shr 8) and 255) * (1 - k) + ((c2 shr 8) and 255) * k) / 255f, ((c1 and 255) * (1 - k) + (c2 and 255) * k) / 255f)
    }
    private fun ramp(f: Float, vararg cs: Int): Int {
        val x = f.coerceIn(0f, 0.9999f) * (cs.size - 1)
        val i = x.toInt()
        return mix(cs[i], cs[i + 1], x - i)
    }
    private val pastel = intArrayOf(0xFFF6E7A1.toInt(), 0xFFF4B6C2.toInt(), 0xFFB8C5F2.toInt(), 0xFFFFD39B.toInt(), 0xFFC9E4B7.toInt(), 0xFFE7B7F0.toInt(), 0xFFF7C59F.toInt())
    private fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return h xor (h ushr 16) }

    /** one pixel of a field theme; u across (-1..1), v down (about -1.8..1.8) */
    private fun field(kind: Int, u: Float, v: Float, t: Float): Int {
        val r = sqrt(u * u + v * v)
        val a = atan2(v, u)
        return when (kind) {
            WARP -> { // spiral warp in cyan, green and yellow with beaded dark arms (inspiration: fractal warpstorm)
                val s = a + 2.2f * ln(r + 0.001f) - t * 0.5f
                val edge = abs(sin(s * 2f))
                if (edge < 0.28f) {
                    val d = sin(r * 70f - s * 24f) * sin(s * 22f)
                    if (d > 0.25f) 0xFF3FD9C4.toInt() else 0xFF06100F.toInt()
                } else {
                    val b = sin(s * 4f + 3f * sin(r * 10f - t * 0.7f)) * 0.5f + 0.5f
                    val warm = (sin(t * 0.2f) * 0.5f + 0.5f)
                    ramp(b, 0xFF00C8FF.toInt(), 0xFF22F5C8.toInt(), 0xFF8CFF3F.toInt(), mix(0xFFF9F871.toInt(), 0xFFFF6A2B.toInt(), warm))
                }
            }
            STAR_BLOOM -> { // soft eight-point star breathing out from the centre
                val x = r * 2.2f + 0.25f * cos(8f * a + t * 0.3f) * (0.3f + r) - t * 0.35f
                scale(rb(x * 0.45f), 0.62f + 0.38f * cos(x * TAU))
            }
            RIPPLES -> { // rainbow ripples with dark contour lines
                val du = u - 0.3f; val dv = v + 0.4f
                val z = sin(u * 5f + t * 0.6f) + sin(v * 4f - t * 0.5f) + sin((u + v) * 3.5f + t * 0.4f) + sin(sqrt(du * du + dv * dv) * 9f - t * 0.9f)
                val f = z * 1.4f
                if (fract(f * 2f) < 0.16f) 0xFF000000.toInt() else rb(f * 0.25f + t * 0.03f)
            }
            HYPNO -> { // black and white jagged rings pouring outwards
                val jag = 0.035f * (sin(a * 48f + t * 1.5f) + 0.6f * sin(a * 17f - t)) * (0.3f + r)
                if (sin((r + jag) * 38f - t * 5f) > 0f) 0xFFF2F2F2.toInt() else 0xFF000000.toInt()
            }
            RSPIRAL -> { // rainbow dotted spiral turning inwards
                val arm = sin(a * 12f + ln(r + 0.001f) * 14f - t * 3f)
                val dots = sin(ln(r + 0.001f) * 40f - t * 3f) * 0.5f + 0.5f
                val b = max(0f, arm).pow(0.8f) * min(1f, r * 4f) * (0.55f + 0.45f * dots)
                scale(rb(a / TAU + r * 0.6f - t * 0.05f), b)
            }
            NEON_RINGS -> { // neon rings with a second set rolling round inside
                val cx = 0.32f * cos(t * 0.8f); val cy = 0.32f * sin(t * 0.8f)
                val d2 = sqrt((u - cx) * (u - cx) + (v - cy) * (v - cy))
                val x = if (r < 0.62f) d2 * 1.4f else r
                val ring = fract(x * 2.5f - t * 0.25f)
                scale(rb(ring * 0.9f + 0.95f), 0.25f + 0.75f * abs(sin(ring * PI.toFloat())))
            }
            KALEIDO -> { // magenta kaleidoscope, twelve mirrored slices
                val n = TAU / 12f
                val aa = abs(fract((a + t * 0.1f) / n) * n - n / 2f)
                val x = r * cos(aa); val y = r * sin(aa)
                val p = sin(x * 10f - t) + cos(y * 14f + t * 0.7f) + sin((x + y) * 8f + t * 0.3f)
                if (fract(r * 3f - t * 0.2f) < 0.035f) 0xFF120010.toInt()
                else android.graphics.Color.HSVToColor(floatArrayOf((290f + 90f * fract(p * 0.3f + r * 0.4f + t * 0.04f)) % 360f, 0.85f, 0.75f + 0.25f * sin(p)))
            }
            GLASS -> { // pastel stained glass kaleidoscope
                val n = TAU / 16f
                val aa = abs(fract((a - t * 0.06f) / n) * n - n / 2f)
                val x = r * cos(aa) * 7f + t * 0.4f; val y = r * sin(aa) * 7f
                val gx = floor(x).toInt(); val gy = floor(y + 0.5f * sin(x * 0.7f)).toInt()
                val fx = fract(x); val fy = fract(y + 0.5f * sin(x * 0.7f))
                if (fx < 0.07f || fy < 0.07f) 0xFFD9D4E8.toInt() else pastel[(hash(gx, gy) and 0x7fffffff) % pastel.size]
            }
            SLIME -> { // green slime tunnel with holes and a glowing blue middle
                val z = 0.35f / (r + 0.02f) + t * 1.2f
                val h = sin(z * 2.5f) * sin(a * 9f + z * 0.4f)
                val col = if (h > 0.35f) mix(0xFF020A14.toInt(), 0xFF0E3B5A.toInt(), (h - 0.35f) * 2f)
                    else scale(0xFF5BD12E.toInt(), 0.55f + 0.45f * cos(z * 2.5f) * 0.5f + 0.25f)
                if (r < 0.16f) mix(col, mix(0xFFFFFFFF.toInt(), 0xFF3AD8FF.toInt(), r / 0.16f), 1f - r / 0.16f) else col
            }
            ZEBRA -> { // black and white fractal swirl with neon edges
                val s = a + 3f * ln(r + 0.001f) - t * 0.4f
                val b = sin(s * 6f + sin(r * 8f + t * 0.3f) * 1.5f)
                when { abs(b) < 0.14f -> rb(fract(s * 0.3f + 0.6f)); b > 0 -> 0xFFEDEDED.toInt(); else -> 0xFF050505.toInt() }
            }
            MANDEL -> { // endless fractal zoom: red sea with rainbow bubbles
                val zoom = 1.6f * exp(-(t * 0.12f) % 8f)
                val cr = -0.743643887 + u * zoom; val ci = 0.131825904 + v * zoom
                var zr = 0.0; var zi = 0.0; var i = 0
                while (i < 60 && zr * zr + zi * zi < 4.0) { val nz = zr * zr - zi * zi + cr; zi = 2 * zr * zi + ci; zr = nz; i++ }
                if (i >= 60) 0xFFE00000.toInt() else rb(i / 24f + t * 0.05f)
            }
            PLASMA -> {
                val p = sin(u * 4f + t) + sin(v * 3f - t * 0.8f) + sin((u + v) * 3f + t * 0.6f) + sin(sqrt(u * u * 9f + v * v * 9f) - t)
                rb(p * 0.18f + t * 0.03f)
            }
            else -> { // LIQUID: soft colour blobs flowing into each other
                var rr = 0f; var gg = 0f; var bb = 0f; var ws = 0f
                for (k in 0 until 4) {
                    val px = 0.7f * sin(t * (0.3f + k * 0.11f) + k * 1.7f); val py = 1.3f * cos(t * (0.23f + k * 0.07f) + k * 2.3f)
                    val wgt = 1f / ((u - px) * (u - px) + (v - py) * (v - py) + 0.05f)
                    val c = liquid[k]
                    rr += ((c shr 16) and 255) * wgt; gg += ((c shr 8) and 255) * wgt; bb += (c and 255) * wgt; ws += wgt
                }
                rgb(rr / ws / 255f, gg / ws / 255f, bb / ws / 255f)
            }
        }
    }
    private val liquid = intArrayOf(0xFFFF7EB3.toInt(), 0xFF7AFCFF.toInt(), 0xFFFEFF9C.toInt(), 0xFF9D7BFF.toInt())

    class FieldBuf(val step: Float = 0.033f) { val bmp: Bitmap = Bitmap.createBitmap(FW, FH, Bitmap.Config.ARGB_8888); val px = IntArray(FW * FH); var at = -1f
        val img = bmp.asImageBitmap() }

    fun DrawScope.drawField(kind: Int, t: Float, buf: FieldBuf) {
        if (buf.at < 0 || abs(t - buf.at) >= buf.step) { // about 30 pictures a second is plenty for a background
            buf.at = t
            val asp = FH.toFloat() / FW
            var k = 0
            for (y in 0 until FH) {
                val v = ((y + 0.5f) / FH * 2f - 1f) * asp
                for (x in 0 until FW) buf.px[k++] = field(kind, (x + 0.5f) / FW * 2f - 1f, v, t)
            }
            buf.bmp.setPixels(buf.px, 0, FW, 0, 0, FW, FH)
        }
        // stretch to fill (cropping the sides or top so it never looks squashed)
        val sc = max(size.width / FW, size.height / FH)
        val dw = (FW * sc).toInt(); val dh = (FH * sc).toInt()
        drawImage(buf.img, IntOffset.Zero, IntSize(FW, FH), IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()), IntSize(dw, dh),
            filterQuality = FilterQuality.Low)
    }

    private val leaf: Path by lazy {
        Path().apply {
            for (k in -3..3) {
                val th = k * 0.42f; val len = 1f - abs(k) * 0.17f; val wd = 0.13f * len
                val tx = sin(th) * len; val ty = -cos(th) * len
                val px = cos(th) * wd; val py = sin(th) * wd
                moveTo(0f, 0f)
                quadraticTo(tx * 0.5f + px, ty * 0.5f + py, tx, ty)
                quadraticTo(tx * 0.5f - px, ty * 0.5f - py, 0f, 0f)
            }
        }
    }

    fun DrawScope.drawShapes(kind: Int, t: Float, stars: List<FloatArray>) {
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val big = max(w, h)
        when (kind) {
            LEAVES -> { // rainbow leaves spiralling into a tunnel
                drawRect(Color.Black)
                val rings = 18
                for (j in rings - 1 downTo 0) {
                    val z = fract((j + t * 0.6f) / rings)
                    val rad = z.pow(2.2f) * big * 0.9f
                    val n = 16
                    val leafSize = 3f + rad * 0.16f
                    val col = Color.hsv(((j * 23f + t * 40f) % 360f + 360f) % 360f, 0.85f, 1f).copy(alpha = (z * 1.6f).coerceAtMost(1f))
                    for (i in 0 until n) {
                        val ang = i * TAU / n + j * 0.33f + t * 0.4f
                        val x = cx + cos(ang) * rad; val y = cy + sin(ang) * rad
                        withTransform({ translate(x, y); rotate(ang * 180f / PI.toFloat() + 90f, Offset.Zero); scale(leafSize, leafSize, Offset.Zero) }) { drawPath(leaf, col) }
                    }
                }
            }
            LASER -> { // green laser cone in fog over a dance floor
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0B3D12), Color(0xFF021006))))
                val ox = cx; val oy = h * 0.1f
                val floorY = h * 0.72f
                val rad = w * 0.42f * (0.15f + 0.85f * abs(sin(t * 0.35f)))
                val th = t * 0.8f
                val pts = List(4) { k -> Offset(cx + rad * cos(th + k * PI.toFloat() / 2), floorY + rad * 0.32f * sin(th + k * PI.toFloat() / 2)) }
                val cone = Path().apply { moveTo(ox, oy); pts.forEach { lineTo(it.x, it.y) }; close() }
                drawPath(cone, Brush.verticalGradient(listOf(Color(0x8836FF5A), Color(0x2236FF5A)), oy, floorY))
                pts.forEach { drawLine(Color(0xCC6BFF7E), Offset(ox, oy), it, 2.5f) }
                for (k in 0 until 4) drawLine(Color(0xFF8BFF9A), pts[k], pts[(k + 1) % 4], 4f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFB7FFC2), Color(0x0036FF5A)), Offset(ox, oy), w * 0.35f), w * 0.35f, Offset(ox, oy))
                stars.take(60).forEach { s -> // glitter on the floor
                    val x = s[0] * w; val y = floorY - h * 0.05f + s[1] * h * 0.2f
                    drawCircle(Color(0xFF9CFFAA).copy(alpha = ((sin(t * 5f + s[3] * 30f) + 1f) / 2f) * 0.8f), 1.5f * s[2], Offset(x, y))
                }
                drawRect(Brush.verticalGradient(listOf(Color(0x00103018), Color(0x5520FF40), Color(0x00103018)), h * 0.2f, h * 0.9f)) // fog
            }
            STAR_TUNNEL -> { // glowing star outlines flying towards you
                drawRect(Color(0xFF02060A))
                for (k in 0 until 12) {
                    val z = fract((k + t * 0.5f) / 12f)
                    val s = z * z * big * 1.1f
                    val rot = t * 10f + k * 7f
                    val col = Color(0xFF5FF5E8).copy(alpha = z.coerceAtMost(1f))
                    rotate(rot, Offset(cx, cy)) {
                        for (flip in 0..1) {
                            val p = Path().apply {
                                for (i in 0..2) {
                                    val an = (i * 120f + flip * 60f - 90f) * PI.toFloat() / 180f
                                    val x = cx + cos(an) * s; val y = cy + sin(an) * s
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                                close()
                            }
                            drawPath(p, col, style = Stroke(width = 2f + z * 12f))
                        }
                    }
                }
            }
            WARP_SPEED -> { // stars streaking past
                drawRect(Color(0xFF000308))
                stars.forEach { s ->
                    val z = fract(s[3] - t * 0.25f * (0.5f + s[2] / 3f))
                    val ang = s[0] * TAU; val r0 = (1f - z).pow(3f) * big * 0.7f; val r1 = (1f - min(1f, z + 0.05f)).pow(3f) * big * 0.7f
                    drawLine(Color.White.copy(alpha = (1f - z)), Offset(cx + cos(ang) * r1, cy + sin(ang) * r1), Offset(cx + cos(ang) * r0, cy + sin(ang) * r0), 1f + (1f - z) * 3f)
                }
            }
            GALAXY -> { // spiral galaxy slowly turning
                drawRect(Color(0xFF03010A))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9C4), Color(0x667A4BFF), Color(0x00000000)), Offset(cx, cy), w * 0.45f), w * 0.45f, Offset(cx, cy))
                for (i in 0 until 400) {
                    val arm = i % 2
                    val d = (i / 400f)
                    val ang = d * 9f + arm * PI.toFloat() + t * 0.15f + sin(i * 12.9898f) * 0.35f
                    val rad = d * w * 0.75f
                    val x = cx + cos(ang) * rad; val y = cy + sin(ang) * rad * 0.62f
                    val col = if (i % 3 == 0) Color(0xFFB9A4FF) else if (i % 3 == 1) Color(0xFF8FE3FF) else Color.White
                    drawCircle(col.copy(alpha = 0.4f + 0.6f * abs(sin(i * 7.1f + t))), 1f + (1f - d) * 2.5f, Offset(x, y))
                }
            }
            SNOW -> { // calm snowfall on a winter night
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1A33), Color(0xFF2A3E66))))
                stars.forEach { s ->
                    val y = fract(s[1] + t * 0.03f * s[2]) * h; val x = (s[0] * w + sin(t * 0.8f + s[3] * 10f) * 20f)
                    drawCircle(Color.White.copy(alpha = 0.5f + 0.15f * s[2]), s[2] * 2.2f, Offset(x, y))
                }
            }
            CONFETTI -> { // party confetti tumbling down
                drawRect(Brush.verticalGradient(listOf(Color(0xFF1B0B3A), Color(0xFF3A0B2E))))
                stars.forEachIndexed { i, s ->
                    val y = fract(s[1] + t * 0.05f * (0.6f + s[2] / 3f)) * h; val x = s[0] * w + sin(t * 2f + i) * 15f
                    val col = Color.hsv((i * 47f) % 360f, 0.8f, 1f)
                    rotate(t * 120f * (0.5f + s[3]) + i * 30f, Offset(x, y)) { drawRect(col, Offset(x - 6f, y - 3f), Size(12f, 6f * abs(cos(t * 3f + i)) + 1f)) }
                }
            }
            EQUALIZER -> { // bouncing music bars with a mirror below
                drawRect(Color(0xFF07060D))
                val n = 24; val bw = w / n
                for (i in 0 until n) {
                    val lvl = (0.25f + 0.25f * sin(t * 3f + i * 0.5f) + 0.25f * sin(t * 5.3f + i * 1.3f) + 0.2f * sin(t * 8.1f + i * 0.2f)).coerceIn(0.05f, 1f)
                    val bh = lvl * h * 0.45f
                    val col = Color.hsv((i * 360f / n + t * 20f) % 360f, 0.8f, 1f)
                    drawRect(col, Offset(i * bw + 3f, h * 0.6f - bh), Size(bw - 6f, bh))
                    drawRect(Brush.verticalGradient(listOf(col.copy(alpha = 0.35f), Color.Transparent), h * 0.6f, h * 0.6f + bh * 0.6f), Offset(i * bw + 3f, h * 0.6f), Size(bw - 6f, bh * 0.6f))
                }
            }
            else -> { // FIRE: flames rising from the bottom
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0200), Color(0xFF2A0800))))
                stars.forEach { s ->
                    val life = fract(s[3] + t * 0.35f * (0.6f + s[2] / 4f))
                    val x = s[0] * w + sin(t * 3f + s[1] * 20f) * 25f * life
                    val y = h * (1.05f - life * 0.75f)
                    val rad = (1f - life) * w * 0.12f * (0.6f + s[2] / 3f)
                    val col = when { life < 0.3f -> Color(0xFFFFF176); life < 0.6f -> Color(0xFFFF9800); else -> Color(0xFFE53935) }
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.7f * (1f - life)), Color.Transparent), Offset(x, y), max(1f, rad)), max(1f, rad), Offset(x, y))
                }
            }
        }
    }
}
