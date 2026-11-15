// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Jarrod's progress map (test 170): while a picture is drawn, a winding track runs from the top left down to a finish
 * line, with a stop for each stage and one for every drawing step. A little animal walks the track and does something
 * at each stop: pops out of a box while the model opens, reads a book while the words are read, paints on an easel for
 * each drawing step, and frames the picture at the end. Each stop has its own label instead of a list under the card.
 */
class JourneyMap(context: Context, private val a1: Int, private val a2: Int, private val textColor: Int, private val cardFill: Int) : View(context) {
    private class Stop(val icon: String, val label: String)

    private var stops = listOf<Stop>()
    private var steps = -1
    private val pts = mutableListOf<PointF>()
    private val dist = mutableListOf<Float>()
    private val track = Path()
    private val done = Path()
    private var measure: PathMeasure? = null

    /** Where the animal is (a stop number, fractions between stops) and where it is heading. */
    private var pos = 0f
    private var target = 0f
    private var lastFrame = 0L
    private var arrivedAt = 0L
    private var opened = true
    private var animal = "🦊"

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    private var anim: ValueAnimator? = null
    var running = false
        set(on) {
            if (field == on) return
            field = on
            if (on) { animal = ANIMALS.random(); pos = 0f; target = 0f; arrivedAt = SystemClock.uptimeMillis() }
            if (on && isAttachedToWindow) startClock() else stopClock()
            visibility = if (on) VISIBLE else GONE
        }

    private fun startClock() {
        if (anim != null) return
        lastFrame = SystemClock.uptimeMillis()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }
    private fun stopClock() { anim?.cancel(); anim = null }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) startClock() }
    override fun onDetachedFromWindow() { stopClock(); super.onDetachedFromWindow() }

    /**
     * Moves the animal on. [stage] is ImageJob's stage (0 open, 1 read, 2 draw, 3 finish; -1 not started), [step] the
     * drawing steps finished, [n] how many drawing steps there are, [openSkipped] when the model was already open.
     */
    fun update(stage: Int, step: Int, n: Int, openSkipped: Boolean) {
        if (n != steps) { steps = n; build(); layoutTrack() }
        opened = openSkipped
        val t = when {
            stage < 0 -> 0f
            stage == 0 -> 0f
            stage == 1 -> 1f
            stage == 2 -> 2f + step.coerceIn(0, n - 1)
            else -> (n + 2).toFloat()
        }
        if (t != target) { target = t; if (abs(pos - target) > 3f) pos = target - 1f }
    }

    private fun build() {
        val s = mutableListOf(Stop("📦", "Opening"), Stop("📖", "Reading"))
        for (i in 1..steps) s.add(Stop("🎨", if (steps > 1) "Step $i" else "Drawing"))
        s.add(Stop("🖼️", "Finishing"))
        s.add(Stop("🏁", "Finish"))
        stops = s
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); layoutTrack() }

    /** A snake down the square: rows left to right, then right to left, looping round at the edges. */
    private fun layoutTrack() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || stops.isEmpty()) return
        val per = if (stops.size > 12) 4 else 3
        val rows = ceil(stops.size / per.toFloat()).toInt()
        val left = w * 0.14f; val right = w * 0.86f
        val top = h * 0.13f; val bottom = h * 0.84f
        pts.clear()
        for (i in stops.indices) {
            val r = i / per; val c = i % per
            val col = if (r % 2 == 0) c else per - 1 - c
            val x = left + (right - left) * col / (per - 1)
            val y = if (rows == 1) (top + bottom) / 2 else top + (bottom - top) * r / (rows - 1)
            pts.add(PointF(x, y))
        }
        track.reset(); dist.clear()
        track.moveTo(pts[0].x, pts[0].y); dist.add(0f)
        val bend = w * 0.11f
        for (i in 1 until pts.size) {
            val p = pts[i - 1]; val q = pts[i]
            if (abs(p.y - q.y) < 1f) {
                // a gentle wave along a row
                val mx = (p.x + q.x) / 2; val my = p.y + (if (i % 2 == 0) -1 else 1) * h * 0.035f
                track.quadTo(mx, my, q.x, q.y)
            } else {
                val side = if (p.x > w / 2) 1 else -1
                track.cubicTo(p.x + side * bend * 1.6f, p.y, q.x + side * bend * 1.6f, q.y, q.x, q.y)
            }
            dist.add(PathMeasure(track, false).length)
        }
        measure = PathMeasure(track, false)
        val sw = w * 0.022f
        trackPaint.strokeWidth = sw; donePaint.strokeWidth = sw
        trackPaint.pathEffect = DashPathEffect(floatArrayOf(sw * 1.4f, sw * 1.2f), 0f)
        donePaint.shader = LinearGradient(0f, top, 0f, bottom, a1, a2, Shader.TileMode.CLAMP)
        invalidate()
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceAtMost(100) / 1000f
        if (now - lastFrame < 33) return
        lastFrame = now
        if (pos < target) {
            // walk about one stop a second (faster if far behind)
            pos = minOf(target, pos + dt * maxOf(1f, (target - pos) * 0.8f))
            if (pos >= target) arrivedAt = now
        } else if (pos > target) pos = target
        invalidate()
    }

    private fun distAt(f: Float): Float {
        if (dist.isEmpty()) return 0f
        val a = floor(f).toInt().coerceIn(0, dist.size - 1); val b = ceil(f).toInt().coerceIn(0, dist.size - 1)
        return dist[a] + (dist[b] - dist[a]) * (f - floor(f))
    }

    override fun onDraw(c: Canvas) {
        val m = measure ?: return
        val w = width.toFloat()
        val now = SystemClock.uptimeMillis()
        val tsec = now / 1000f
        // the track: dashed ahead, coloured behind
        trackPaint.color = textColor; trackPaint.alpha = 70
        c.drawPath(track, trackPaint)
        done.reset(); m.getSegment(0f, distAt(pos), done, true)
        c.drawPath(done, donePaint)

        // the stops, with their labels
        val r = w * 0.04f
        val cur = target.toInt()
        emoji.textSize = r * 1.15f
        for (i in stops.indices) {
            val p = pts.getOrNull(i) ?: continue
            val passed = i < cur || (i == 0 && opened && cur > 0)
            fill.color = if (passed) a1 else Color.argb(235, Color.red(bg()), Color.green(bg()), Color.blue(bg()))
            c.drawCircle(p.x, p.y, r, fill)
            ring.strokeWidth = r * 0.16f
            ring.color = if (i == cur) a2 else if (passed) a1 else Color.argb(110, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
            c.drawCircle(p.x, p.y, r, ring)
            if (i == cur && running) {
                // a soft pulse round the stop it's working at
                val k = (tsec * 1.2f) % 1f
                ring.color = a2; ring.alpha = ((1f - k) * 160).toInt()
                c.drawCircle(p.x, p.y, r * (1f + k * 0.9f), ring); ring.alpha = 255
            }
            if (passed) { emoji.textSize = r * 1.1f; emoji.color = Color.WHITE; c.drawText("✓", p.x, p.y + r * 0.38f, emoji) }
            else { emoji.textSize = r * 0.95f; emoji.color = Color.BLACK; c.drawText(stops[i].icon, p.x, p.y + r * 0.34f, emoji) }
            label.textSize = w * 0.032f
            label.typeface = if (i == cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.color = if (i == cur) a2 else textColor
            label.alpha = if (i == cur) 255 else if (passed) 170 else 120
            val text = if (i == 0 && opened && cur > 0) "Already open" else stops[i].label
            c.drawText(text, p.x, p.y + r * 2.1f, label)
        }

        // the animal and what it's doing at this stop
        val at = FloatArray(2)
        m.getPosTan(distAt(pos), at, null)
        val size = w * 0.085f
        val walking = pos < target - 0.01f
        val stop = stops.getOrNull(cur)?.icon
        var ax = at[0]; var ay = at[1] - r * 1.25f
        var tilt = 0f; var scale = 1f
        when {
            walking -> { ay -= abs(sin(tsec * 12f)) * size * 0.3f; tilt = sin(tsec * 12f) * 8f }
            stop == "📦" -> {
                // popping in and out of the box
                val k = abs(sin(tsec * 2.4f))
                drawEmoji(c, "📦", ax, ay + size * 0.55f, size * 0.95f, 0f)
                ay -= k * size * 0.45f; scale = 0.75f + k * 0.25f
            }
            stop == "📖" -> {
                tilt = sin(tsec * 1.6f) * 7f
                drawEmoji(c, "📖", ax + size * 0.55f, ay + size * 0.3f, size * 0.7f, sin(tsec * 1.6f) * 4f)
            }
            stop == "🎨" -> {
                // painting on a little easel, the picture filling in with dabs of colour
                val ex = ax + size * 0.85f; val ey = ay - size * 0.1f
                box.set(ex - size * 0.38f, ey - size * 0.42f, ex + size * 0.38f, ey + size * 0.32f)
                fill.color = Color.WHITE; c.drawRoundRect(box, size * 0.06f, size * 0.06f, fill)
                fill.color = Color.rgb(140, 90, 50); fill.strokeWidth = size * 0.05f
                c.drawLine(ex - size * 0.2f, box.bottom, ex - size * 0.3f, ey + size * 0.62f, fill.apply { style = Paint.Style.STROKE })
                c.drawLine(ex + size * 0.2f, box.bottom, ex + size * 0.3f, ey + size * 0.62f, fill)
                fill.style = Paint.Style.FILL
                val dabs = ((now - arrivedAt) / 350L).toInt().coerceAtMost(14)
                val rnd = Random(cur * 31 + 7)
                for (d in 0 until dabs) {
                    fill.color = PAINT[rnd.nextInt(PAINT.size)]
                    c.drawCircle(box.left + box.width() * (0.15f + rnd.nextFloat() * 0.7f), box.top + box.height() * (0.15f + rnd.nextFloat() * 0.7f), size * (0.05f + rnd.nextFloat() * 0.05f), fill)
                }
                drawEmoji(c, "🖌️", ex - size * 0.25f + sin(tsec * 6f) * size * 0.12f, ey + size * 0.05f + sin(tsec * 9f) * size * 0.08f, size * 0.5f, -30f)
                tilt = sin(tsec * 6f) * 5f
            }
            stop == "🖼️" -> {
                drawEmoji(c, "🖼️", ax + size * 0.75f, ay, size * 0.75f, 0f)
                drawEmoji(c, "✨", ax + size * 0.75f + sin(tsec * 3f) * size * 0.3f, ay - size * 0.45f, size * 0.35f, 0f)
                ay -= abs(sin(tsec * 3f)) * size * 0.15f
            }
            stop == "🏁" -> { ay -= abs(sin(tsec * 6f)) * size * 0.4f; drawEmoji(c, "🎉", ax + size * 0.7f, ay - size * 0.2f, size * 0.55f, 0f) }
        }
        drawEmoji(c, animal, ax, ay, size * scale, tilt)
    }

    private fun bg(): Int = cardFill

    private fun drawEmoji(c: Canvas, e: String, x: Float, y: Float, size: Float, rot: Float) {
        emoji.textSize = size; emoji.color = Color.BLACK
        c.save(); c.rotate(rot, x, y)
        c.drawText(e, x, y + size * 0.35f, emoji)
        c.restore()
    }

    companion object {
        private val ANIMALS = listOf("🦊", "🐼", "🐨", "🐱", "🐶", "🐧", "🐰", "🦉", "🐢", "🦘")
        private val PAINT = intArrayOf(Color.rgb(239, 68, 68), Color.rgb(59, 130, 246), Color.rgb(250, 204, 21),
            Color.rgb(34, 197, 94), Color.rgb(168, 85, 247), Color.rgb(249, 115, 22))
    }
}
