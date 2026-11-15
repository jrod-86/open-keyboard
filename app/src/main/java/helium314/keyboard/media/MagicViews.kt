// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/* Image maker eye candy: while a picture is being drawn, the card's frame turns into a spinning rainbow glow and the
 * picture area fills with drifting colour clouds and twinkling sparkles, "magic about to happen". Only animates while
 * drawing (and on screen), at a modest frame rate, so it barely touches the graphics chip the picture needs. */

private val RAINBOW = intArrayOf(
    Color.rgb(168, 85, 247), Color.rgb(59, 130, 246), Color.rgb(34, 211, 238), Color.rgb(52, 211, 153),
    Color.rgb(250, 204, 21), Color.rgb(244, 114, 182), Color.rgb(168, 85, 247),
)

/** One shared clock (0..1, looping) for both effects, stepped ~30 times a second. */
private class MagicClock(private val onTick: (Float) -> Unit) {
    private var anim: ValueAnimator? = null
    private var lastFrame = 0L
    fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                if (now - lastFrame < 33_000_000L) return@addUpdateListener
                lastFrame = now
                onTick(it.animatedValue as Float)
            }
            start()
        }
    }
    fun stop() { anim?.cancel(); anim = null }
}

/** The picture card: a thin purple-to-blue frame normally, a spinning rainbow glow while [magic] is on. */
class GlowCard(context: Context, private val fill: Int, private val corner: Float, private val stroke: Float) : LinearLayout(context) {
    private var t = 0f
    private val clock = MagicClock { t = it; invalidate() }
    private val rect = RectF()
    private val m = Matrix()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private var sweep: SweepGradient? = null

    var magic = false
        set(on) {
            if (field == on) return
            field = on
            if (on && isAttachedToWindow) clock.start() else clock.stop()
            invalidate()
        }

    init { setWillNotDraw(false) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sweep = SweepGradient(w / 2f, h / 2f, RAINBOW, null)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (magic) clock.start() }
    override fun onDetachedFromWindow() { clock.stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke * 3
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(rect, corner, corner, inside)
        val s = sweep ?: return super.onDraw(canvas)
        if (magic) {
            m.setRotate(t * 360f, width / 2f, height / 2f)
            s.setLocalMatrix(m)
            // a soft glow (wide faint strokes) under a crisp bright line, all rainbow, breathing a little
            val breathe = 0.7f + 0.3f * sin(t * 4 * PI).toFloat()
            glow.shader = s
            for ((wide, alpha) in listOf(5.5f to 40, 3.5f to 70, 2.4f to 110)) {
                glow.strokeWidth = stroke * wide; glow.alpha = (alpha * breathe).toInt()
                canvas.drawRoundRect(rect, corner, corner, glow)
            }
            line.shader = s; line.strokeWidth = stroke * 1.6f; line.alpha = 255
            canvas.drawRoundRect(rect, corner, corner, line)
        } else {
            m.setRotate(-45f, width / 2f, height / 2f)
            s.setLocalMatrix(m)
            line.shader = s; line.strokeWidth = stroke; line.alpha = 200
            canvas.drawRoundRect(rect, corner, corner, line)
        }
        super.onDraw(canvas)
    }
}

/** A square box (as wide as it can be) for the picture and the magic, so the preview is big. */
class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/** Drifting colour clouds, a slowly turning ring of light and twinkling sparkles, shown where the picture will appear. */
class MagicView(context: Context) : android.view.View(context) {
    private var t = 0f
    private val clock = MagicClock { t = it; invalidate() }
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val box = RectF()
    private val m = Matrix()
    private var ringShader: SweepGradient? = null

    private class Spark(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float, val tint: Int)
    private val sparks = List(26) {
        Spark(Random.nextFloat(), Random.nextFloat(), 0.4f + Random.nextFloat() * 0.8f, Random.nextFloat(), 1f + Random.nextInt(3), RAINBOW[Random.nextInt(RAINBOW.size - 1)])
    }

    var running = false
        set(on) {
            if (field == on) return
            field = on
            if (on && isAttachedToWindow) clock.start() else clock.stop()
            visibility = if (on) VISIBLE else GONE
        }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) clock.start() }
    override fun onDetachedFromWindow() { clock.stop(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ringShader = SweepGradient(w / 2f, h / 2f, RAINBOW, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2; val cy = h / 2
        val a = (t * 2 * PI).toFloat()
        val r = minOf(w, h)

        // three soft colour clouds circling the middle
        for (i in 0 until 3) {
            val ang = a + i * 2.094f
            val x = cx + cos(ang) * r * 0.18f
            val y = cy + sin(ang * 1.3f) * r * 0.18f
            val c = RAINBOW[(i * 2) % (RAINBOW.size - 1)]
            cloud.shader = RadialGradient(x, y, r * 0.42f, Color.argb(110, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r * 0.42f, cloud)
        }

        // a turning ring of rainbow light, pulsing in size
        ringShader?.let {
            m.setRotate(t * 360f * 2, cx, cy); it.setLocalMatrix(m)
            ring.shader = it
            val rr = r * (0.22f + 0.025f * sin(a * 3))
            ring.strokeWidth = r * 0.012f
            ring.alpha = 220
            box.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(box, 0f, 360f, false, ring)
            ring.alpha = 120
            ring.strokeWidth = r * 0.006f
            val rr2 = rr * 1.35f
            box.set(cx - rr2, cy - rr2, cx + rr2, cy + rr2)
            canvas.drawArc(box, t * -720f, 250f, false, ring)
        }

        // twinkling four-point sparkles drifting gently upwards
        for (s in sparks) {
            val tw = sin(((t * s.speed + s.phase) * 2 * PI)).toFloat()
            if (tw <= 0f) continue
            val y = ((s.y - t * 0.15f * s.speed) % 1f + 1f) % 1f
            val x = s.x + 0.015f * sin(a * s.speed + s.phase * 6f)
            val size = r * 0.03f * s.size * tw
            star.color = if (s.size > 0.9f) Color.WHITE else s.tint
            star.alpha = (255 * tw).toInt()
            drawSparkle(canvas, x * w, y * h, size)
        }
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, s: Float) {
        val k = s * 0.22f
        path.reset()
        path.moveTo(x, y - s); path.quadTo(x + k, y - k, x + s, y); path.quadTo(x + k, y + k, x, y + s)
        path.quadTo(x - k, y + k, x - s, y); path.quadTo(x - k, y - k, x, y - s); path.close()
        c.drawPath(path, star)
    }
}
