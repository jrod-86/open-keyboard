// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import helium314.keyboard.latin.utils.prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Open Keyboard: Samsung Keys Cafe style key effects, chosen in the Design studio.
 * Three kinds can be mixed: colour around the pressed key, colour inside it, and the key itself moving.
 * Everything is drawn in code (no images), so it is all our own GPL work.
 */
object KeyEffects {
    const val PREF_AROUND = "open_keyboard_fx_around"
    const val PREF_COLOR = "open_keyboard_fx_color"
    const val PREF_MOTION = "open_keyboard_fx_motion"

    val around = listOf("none" to "None", "glow" to "Glow", "splash" to "Splash", "corners" to "Corner bloom",
        "halo" to "Halo", "bubbles" to "Bubbles", "paws" to "Paw prints", "petals" to "Petals")
    val colors = listOf("none" to "None", "watercolour" to "Watercolour", "flash" to "Colour flash", "ring" to "Glow ring",
        "paw" to "Paw print", "gradient" to "Gradient", "rainbow" to "Rainbow", "drop" to "Ink drop", "heart" to "Heart")
    val motions = listOf("none" to "None", "press" to "Press", "pop" to "Pop", "wobble" to "Wobble",
        "bounce" to "Bounce", "jelly" to "Jelly", "tilt" to "Tilt")

    /** Soft colours the effects pick from, a different one on each key press. */
    private val palette = intArrayOf(0xFFFF8FAB.toInt(), 0xFFFFB86B.toInt(), 0xFFFFE066.toInt(), 0xFF8EE3A1.toInt(),
        0xFF7FD8E8.toInt(), 0xFF8AB4F8.toInt(), 0xFFB39DFF.toInt(), 0xFFFF9EE6.toInt(), 0xFF66E0C2.toInt())

    const val DURATION = 600L
    private const val MOTION_DURATION = 340L

    class Anim(val key: Any, val start: Long, val seed: Int, val around: String, val color: String, val motion: String)

    private val anims = ArrayList<Anim>()

    fun start(ctx: Context, key: Any) {
        val p = ctx.prefs()
        val a = p.getString(PREF_AROUND, "none") ?: "none"
        val c = p.getString(PREF_COLOR, "none") ?: "none"
        val m = p.getString(PREF_MOTION, "none") ?: "none"
        if (a == "none" && c == "none" && m == "none") return
        anims.removeAll { it.key === key }
        if (anims.size > 12) anims.removeAt(0)
        anims.add(Anim(key, SystemClock.uptimeMillis(), Random.nextInt(), a, c, m))
    }

    /** Drops finished effects; true while any are still playing. */
    fun active(now: Long = SystemClock.uptimeMillis()): Boolean {
        if (anims.isEmpty()) return false
        anims.removeAll { now - it.start > DURATION }
        return anims.isNotEmpty()
    }

    fun all(): List<Anim> = anims

    fun find(key: Any): Anim? = anims.firstOrNull { it.key === key }

    fun progress(a: Anim, now: Long = SystemClock.uptimeMillis()) = ((now - a.start).toFloat() / DURATION).coerceIn(0f, 1f)

    // ---------------- drawing, shared by the keyboard and the Design studio demo tiles ----------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    private fun color(seed: Int, i: Int = 0) = palette[Math.floorMod(seed + i * 3, palette.size)]
    private fun rnd(seed: Int, i: Int) = Random(seed * 31 + i).nextFloat()
    /** fade in fast, fade out slowly */
    private fun env(t: Float) = if (t < 0.12f) t / 0.12f else ((1f - t) / 0.88f).coerceAtLeast(0f)
    private fun ease(t: Float) = 1f - (1f - t) * (1f - t)
    private fun alpha(c: Int, a: Float) = Color.argb((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun reset() {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL
    }

    /** Colour that spills out around the key [r]. Drawn behind the keys. */
    fun drawAround(c: Canvas, id: String, r: RectF, t: Float, seed: Int, density: Float) {
        if (id == "none" || t >= 1f) return
        reset()
        val e = env(t); val g = ease(t)
        val radius = min(r.width(), r.height()) * 0.22f
        when (id) {
            "glow" -> {
                paint.maskFilter = BlurMaskFilter(10 * density, BlurMaskFilter.Blur.NORMAL)
                paint.color = alpha(color(seed), 0.9f * e)
                val grow = (3 + 7 * g) * density
                tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
                c.drawRoundRect(tmp, radius + grow, radius + grow, paint)
            }
            "splash" -> {
                paint.maskFilter = BlurMaskFilter(3 * density, BlurMaskFilter.Blur.NORMAL)
                for (i in 0 until 6) {
                    val (px, py) = edgePoint(r, rnd(seed, i))
                    paint.color = alpha(color(seed, i), 0.85f * e)
                    c.drawCircle(px, py, (5 + 9 * rnd(seed, i + 9)) * density * g, paint)
                }
            }
            "corners" -> {
                paint.maskFilter = BlurMaskFilter(8 * density, BlurMaskFilter.Blur.NORMAL)
                val size = min(r.width(), r.height()) * (0.35f + 0.3f * g)
                paint.color = alpha(color(seed), 0.85f * e)
                c.drawCircle(r.right - size * 0.2f, r.top + size * 0.2f, size, paint)
                paint.color = alpha(color(seed, 2), 0.85f * e)
                c.drawCircle(r.left + size * 0.2f, r.bottom - size * 0.2f, size, paint)
            }
            "halo" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * density
                paint.color = alpha(color(seed), e)
                val grow = 14 * density * g
                tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
                c.drawRoundRect(tmp, radius + grow, radius + grow, paint)
            }
            "bubbles" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * density
                for (i in 0 until 7) {
                    val ang = rnd(seed, i) * 2 * PI
                    val dist = (min(r.width(), r.height()) * 0.55f + 22 * density * g)
                    paint.color = alpha(color(seed, i), e)
                    c.drawCircle(r.centerX() + (cos(ang) * dist * 1.2f).toFloat(), r.centerY() + (sin(ang) * dist).toFloat(),
                        (3 + 4 * rnd(seed, i + 20)) * density, paint)
                }
            }
            "paws" -> {
                val s = min(r.width(), r.height()) * 0.55f
                paint.color = alpha(color(seed), e)
                drawPaw(c, r.right - s * 0.1f, r.top + s * 0.1f - 6 * density * g, s, -25f)
                paint.color = alpha(color(seed, 4), e * 0.9f)
                drawPaw(c, r.left + s * 0.1f, r.bottom - s * 0.1f + 6 * density * g, s * 0.8f, 20f)
            }
            "petals" -> {
                for (i in 0 until 5) {
                    val ang = (i / 5f + rnd(seed, 0)) * 2 * PI
                    val dist = min(r.width(), r.height()) * 0.45f + 16 * density * g
                    val px = r.centerX() + (cos(ang) * dist * 1.3f).toFloat(); val py = r.centerY() + (sin(ang) * dist).toFloat()
                    paint.color = alpha(color(seed, i), 0.9f * e)
                    c.save(); c.rotate((ang * 180 / PI).toFloat() + 360 * t, px, py)
                    tmp.set(px - 7 * density, py - 3.5f * density, px + 7 * density, py + 3.5f * density)
                    c.drawOval(tmp, paint); c.restore()
                }
            }
        }
        paint.maskFilter = null
    }

    /** a point on the key's outline; f goes once around */
    private fun edgePoint(r: RectF, f: Float): Pair<Float, Float> {
        val per = 2 * (r.width() + r.height()); var d = f * per
        if (d < r.width()) return r.left + d to r.top
        d -= r.width(); if (d < r.height()) return r.right to r.top + d
        d -= r.height(); if (d < r.width()) return r.right - d to r.bottom
        d -= r.width(); return r.left to r.bottom - d
    }

    /** Colour inside the key [r], drawn over the key's background and under its letter. */
    fun drawInside(c: Canvas, id: String, r: RectF, t: Float, seed: Int, density: Float, tx: Float = r.centerX(), ty: Float = r.centerY()) {
        if (id == "none" || t >= 1f) return
        reset()
        val e = env(t); val g = ease(t)
        val radius = min(r.width(), r.height()) * 0.22f
        c.save()
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(path)
        when (id) {
            "watercolour" -> for (i in 0 until 3) {
                val cx = r.left + r.width() * (0.2f + 0.6f * rnd(seed, i)); val cy = r.top + r.height() * (0.2f + 0.6f * rnd(seed, i + 5))
                val rad = r.width() * (0.35f + 0.25f * rnd(seed, i + 7)) * (0.5f + 0.5f * g)
                paint.shader = RadialGradient(cx, cy, rad, alpha(color(seed, i), 0.8f * e), alpha(color(seed, i), 0f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rad, paint)
            }
            "flash" -> { paint.color = alpha(color(seed), 0.75f * e); c.drawRect(r, paint) }
            "ring" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f * density
                paint.maskFilter = BlurMaskFilter(2 * density, BlurMaskFilter.Blur.NORMAL)
                paint.color = alpha(color(seed), e)
                val inset = 2.5f * density
                tmp.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
                c.drawRoundRect(tmp, radius, radius, paint)
            }
            "paw" -> {
                paint.color = alpha(color(seed), 0.75f * e)
                val s = min(r.width(), r.height()) * 0.7f
                drawPaw(c, r.left + r.width() * 0.3f, r.top + r.height() * 0.55f - 4 * density * g, s * (0.8f + 0.2f * g), -15f)
            }
            "gradient" -> {
                paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, alpha(color(seed), 0.8f * e), alpha(color(seed, 2), 0.8f * e), Shader.TileMode.CLAMP)
                c.drawRect(r, paint)
            }
            "rainbow" -> {
                val cols = intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFD93D.toInt(), 0xFF6BCB77.toInt(), 0xFF4D96FF.toInt(), 0xFFB983FF.toInt(), 0xFFFF6B6B.toInt())
                paint.shader = SweepGradient(r.centerX(), r.centerY(), cols.map { alpha(it, 0.6f * e) }.toIntArray(), null)
                c.save(); c.rotate(360 * t, r.centerX(), r.centerY()); c.drawCircle(r.centerX(), r.centerY(), hypot(r.width(), r.height()), paint); c.restore()
            }
            "drop" -> {
                paint.color = alpha(color(seed), 0.7f * e)
                c.drawCircle(tx, ty, hypot(r.width(), r.height()) * g, paint)
            }
            "heart" -> {
                paint.color = alpha(color(seed), 0.8f * e)
                drawHeart(c, r.centerX(), r.centerY(), min(r.width(), r.height()) * (0.4f + 0.5f * g))
            }
        }
        paint.shader = null; paint.maskFilter = null
        c.restore()
    }

    /** Moves the key: call before drawing it (inside a save/restore) with the key's own [r]. */
    fun applyMotion(c: Canvas, id: String, r: RectF, elapsedMs: Long) {
        if (id == "none") return
        val t = (elapsedMs.toFloat() / MOTION_DURATION).coerceIn(0f, 1f)
        if (t >= 1f) return
        val cx = r.centerX(); val cy = r.centerY(); val damp = 1f - t
        when (id) {
            "press" -> { val s = 1f - 0.16f * sin(PI * t).toFloat(); c.scale(s, s, cx, cy) }
            "pop" -> { val s = 1f + 0.18f * sin(PI * t).toFloat(); c.scale(s, s, cx, cy) }
            "wobble" -> c.rotate(12f * sin(t * 3 * PI).toFloat() * damp, cx, cy)
            "bounce" -> c.translate(0f, -r.height() * 0.28f * abs(sin(t * 2 * PI)).toFloat() * damp)
            "jelly" -> { val k = 0.16f * sin(t * 3 * PI).toFloat() * damp; c.scale(1f + k, 1f - k, cx, cy) }
            "tilt" -> c.rotate(-10f * sin(PI * t).toFloat(), cx, r.bottom)
        }
    }

    private fun drawPaw(c: Canvas, cx: Float, cy: Float, size: Float, rotation: Float) {
        c.save(); c.rotate(rotation, cx, cy)
        tmp.set(cx - size * 0.24f, cy - size * 0.02f, cx + size * 0.24f, cy + size * 0.34f)
        c.drawOval(tmp, paint)
        val toes = floatArrayOf(-0.3f, -0.08f, -0.11f, -0.26f, 0.11f, -0.26f, 0.3f, -0.08f)
        for (i in 0 until 4) c.drawCircle(cx + toes[i * 2] * size, cy + toes[i * 2 + 1] * size, size * 0.1f, paint)
        c.restore()
    }

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, size: Float) {
        path.reset()
        val s = size / 2
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.6f, cy - s * 0.2f, cx - s * 0.6f, cy - s * 1.3f, cx, cy - s * 0.45f)
        path.cubicTo(cx + s * 0.6f, cy - s * 1.3f, cx + s * 1.6f, cy - s * 0.2f, cx, cy + s * 0.9f)
        path.close()
        c.drawPath(path, paint)
    }
}
