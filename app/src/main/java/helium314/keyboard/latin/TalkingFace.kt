// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Chat's talking face, in a rounded box with its own background. The mouth opens with how loud the voice is at the
 * moment you hear it ([level]), the eyes blink, and while the AI is writing it looks up, thinking. [style] picks the
 * look (Jarrod, test 200): the cartoon face, a Matrix face made of falling green code, a retro robot screen, or neon.
 */
class TalkingFace(context: Context, private val a1: Int, private val a2: Int, private val card: Int, private val level: () -> Float) : View(context) {
    enum class Style(val label: String) { CARTOON("😊 Cartoon"), MATRIX("💚 Matrix"), ROBOT("🤖 Robot screen"), NEON("🌆 Neon") }

    var style = Style.CARTOON
        set(v) { field = v; invalidate() }
    /** True while the answer is being written. */
    var thinking = false
    /** Mischievous personalities get raised, angled eyebrows. */
    var cheeky = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val box = RectF()
    private val clip = Path()
    private var mouth = 0f
    private var nextBlink = System.currentTimeMillis() + 2500
    private var blinking = false

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        // the box: a square in the middle (Jarrod: "a box around the head with a little background")
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f
        box.set(left, 0f, left + side, side)
        val corner = side * 0.16f
        clip.reset(); clip.addRoundRect(box, corner, corner, Path.Direction.CW)

        val target = level()
        mouth += (target - mouth) * (if (target > mouth) 0.7f else 0.5f)
        if (now > nextBlink + 140) nextBlink = now + 2200 + (Math.random() * 3000).toLong()
        blinking = now in nextBlink..(nextBlink + 140)

        canvas.save()
        canvas.clipPath(clip)
        when (style) {
            Style.CARTOON -> cartoon(canvas, now)
            Style.MATRIX -> matrix(canvas, now)
            Style.ROBOT -> robot(canvas, now)
            Style.NEON -> neon(canvas, now)
        }
        canvas.restore()

        // smooth while talking, thinking or animating its background; idle cartoon only needs to blink
        // every style has a moving background; 30 fps is plenty when nothing is talking
        val busy = target > 0f || mouth > 0.01f || thinking
        if (isShown) { if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(33) }
    }

    // ---------- 😊 cartoon ----------

    private fun cartoon(canvas: Canvas, now: Long) {
        val cx = box.centerX(); val cy = box.centerY()
        // soft glow behind the head
        shaders()
        p.shader = glowShader
        canvas.drawRect(box, p); p.shader = null
        // slowly floating bubbles in the background
        for (i in 0 until 9) {
            val speed = 9000.0 + i * 1700
            val ph = ((now % speed.toLong()) / speed + i * 0.37) % 1.0
            val bx = box.left + box.width() * ((i * 0.29f + 0.08f * sin(now / 1500.0 + i).toFloat()) % 1f)
            val by = box.bottom - box.height() * ph.toFloat() * 1.2f + box.height() * 0.1f
            p.color = if (i % 2 == 0) a1 else a2
            p.alpha = 45
            canvas.drawCircle(bx, by, box.width() * (0.03f + 0.02f * (i % 3)), p)
        }
        p.alpha = 255
        val r = box.width() * 0.36f
        p.shader = headShader
        canvas.drawCircle(cx, cy, r, p); p.shader = null

        val ink = Color.rgb(35, 30, 45)
        val lookUp = if (thinking) r * 0.08f else 0f
        val drift = if (thinking) (sin(now / 450.0) * r * 0.06).toFloat() else 0f
        val eyeY = cy - r * 0.18f - lookUp
        val eyeW = r * 0.13f
        val eyeH = if (blinking) r * 0.025f else r * 0.19f
        for (s in SIDES) {
            val ex = cx + s * r * 0.33f + drift
            p.color = ink; rect.set(ex - eyeW, eyeY - eyeH, ex + eyeW, eyeY + eyeH); canvas.drawOval(rect, p)
            if (!blinking) { p.color = Color.WHITE; canvas.drawCircle(ex + eyeW * 0.35f, eyeY - eyeH * 0.4f, eyeW * 0.35f, p) }
            p.color = Color.argb(70, 255, 120, 150); canvas.drawCircle(cx + s * r * 0.55f, cy + r * 0.18f, r * 0.13f, p)
            if (cheeky) {
                stroke.color = ink; stroke.strokeWidth = r * 0.06f
                canvas.drawLine(ex - eyeW * 1.3f, eyeY - r * 0.27f - s * r * 0.06f, ex + eyeW * 1.3f, eyeY - r * 0.27f + s * r * 0.06f, stroke)
            }
        }
        val my = cy + r * 0.38f
        if (mouth < 0.06f) {
            stroke.color = ink; stroke.strokeWidth = r * 0.06f
            rect.set(cx - r * 0.22f, my - r * 0.18f, cx + r * 0.22f, my + r * 0.08f)
            when {
                thinking -> canvas.drawLine(cx - r * 0.12f, my, cx + r * 0.12f, my, stroke)
                cheeky -> canvas.drawArc(rect.apply { offset(r * 0.06f, 0f) }, 10f, 110f, false, stroke) // smirk
                else -> canvas.drawArc(rect, 20f, 140f, false, stroke)
            }
        } else {
            val w = r * (0.2f + 0.1f * mouth); val h = r * (0.05f + 0.3f * mouth)
            p.color = Color.rgb(120, 30, 60); rect.set(cx - w, my - h * 0.5f, cx + w, my + h * 0.5f); canvas.drawOval(rect, p)
            if (h > r * 0.12f) { p.color = Color.rgb(240, 110, 130); rect.set(cx - w * 0.55f, my + h * 0.05f, cx + w * 0.55f, my + h * 0.5f); canvas.drawOval(rect, p) }
        }
    }

    // ---------- 💚 Matrix: a face made of falling green code ----------

    private val glyphs = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン0123456789Z:.=*+<>".toCharArray()
    private var cols = 0
    private var rows = 0
    private var drops = FloatArray(0)
    private var speeds = FloatArray(0)
    private var cells = CharArray(0)
    private var lastFrame = 0L
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }

    private fun matrix(canvas: Canvas, now: Long) {
        p.color = Color.BLACK; canvas.drawRect(box, p)
        val n = 30
        val cell = box.width() / n
        if (cols != n || cells.isEmpty()) {
            cols = n; rows = n
            drops = FloatArray(cols) { Random.nextFloat() * rows }
            speeds = FloatArray(cols) { 6f + Random.nextFloat() * 10f }
            cells = CharArray(cols * rows) { glyphs.random() }
        }
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        for (c in 0 until cols) {
            drops[c] += speeds[c] * dt
            if (drops[c] > rows + 12) { drops[c] = -Random.nextFloat() * 10f; speeds[c] = 6f + Random.nextFloat() * 10f }
        }
        // a few characters change every frame, like the film
        repeat(12) { cells[Random.nextInt(cells.size)] = glyphs.random() }
        mono.textSize = cell * 0.95f
        val talk = mouth
        for (c in 0 until cols) {
            val x = box.left + (c + 0.5f) * cell
            val nx = (c + 0.5f) / cols * 2f - 1f
            for (r in 0 until rows) {
                val ny = (r + 0.5f) / rows * 2f - 1f
                // the rain: bright head of each drop with a fading tail
                val d = drops[c] - r
                var rain = if (d in 0f..12f) (1f - d / 12f) else 0f
                if (d in 0f..1f) rain = 1.3f
                // the face lights up the code inside it
                val f = matrixFace(nx, ny, talk, now)
                val bright = (rain * 0.55f + f).coerceIn(0f, 1.3f)
                if (bright < 0.06f) continue
                val g = (bright.coerceAtMost(1f) * 255).toInt()
                mono.color = if (bright > 1.05f) Color.rgb(200, 255, 210) else Color.argb(g, 40, 255, 90)
                canvas.drawText(cells, c * rows + r, 1, x, box.top + (r + 0.85f) * cell, mono)
            }
        }
    }

    /** How bright the face is at (x, y), both -1..1: a head shape with dark eyes, brows and an opening mouth. */
    private fun matrixFace(x: Float, y: Float, talk: Float, now: Long): Float {
        val hx = x / 0.58f; val hy = (y + 0.02f) / 0.82f
        val head = hx * hx + hy * hy
        if (head > 1f) return 0f
        var v = 0.75f - head * 0.35f // brighter towards the middle, like a lit face
        val eyeY = -0.18f - (if (thinking) 0.05f else 0f)
        for (s in SIDES) {
            val ex = (x - s * 0.23f) / 0.12f
            val ey = (y - eyeY) / (if (blinking) 0.015f else 0.065f)
            if (ex * ex + ey * ey < 1f) return 0.05f
            // brows (angled when cheeky)
            val by = eyeY - 0.13f + (if (cheeky) s * (x - s * 0.23f) * 0.6f else 0f)
            if (abs(y - by) < 0.025f && abs(x - s * 0.23f) < 0.14f) return 1.1f
        }
        // nose shadow
        if (abs(x) < 0.03f && y > -0.08f && y < 0.12f) v *= 0.45f
        // mouth: a line that opens into a dark gap while talking
        val my = 0.33f
        val mw = 0.2f + 0.06f * talk
        val mh = 0.012f + 0.12f * talk
        val mx = x / mw; val myy = (y - my) / mh
        if (mx * mx + myy * myy < 1f) return if (talk > 0.06f) 0.03f else 1.1f
        if (talk <= 0.06f && abs(y - my) < 0.02f && abs(x) < 0.18f) return 1.1f
        // a slow shimmer so it feels alive
        return v * (0.85f + 0.15f * sin(now / 300.0 + y * 6).toFloat())
    }

    // ---------- 🤖 retro robot screen ----------

    private fun robot(canvas: Canvas, now: Long) {
        val bg = Color.rgb(16, 20, 30)
        p.color = bg; canvas.drawRect(box, p)
        val w = box.width()
        val screenCol = Color.rgb(90, 220, 255)
        // scan lines
        p.color = Color.argb(28, 90, 220, 255)
        var y = box.top + ((now / 40) % 6).toFloat()
        while (y < box.bottom) { canvas.drawRect(box.left, y, box.right, y + 1.5f, p); y += 6f }
        val cx = box.centerX(); val cy = box.centerY()
        // antenna light blinks
        p.color = if ((now / 600) % 2 == 0L) Color.rgb(255, 80, 120) else Color.rgb(120, 40, 60)
        canvas.drawCircle(cx, box.top + w * 0.09f, w * 0.035f, p)
        stroke.color = screenCol; stroke.strokeWidth = w * 0.02f
        canvas.drawLine(cx, box.top + w * 0.12f, cx, box.top + w * 0.2f, stroke)
        // head frame
        rect.set(box.left + w * 0.16f, box.top + w * 0.2f, box.right - w * 0.16f, box.bottom - w * 0.12f)
        canvas.drawRoundRect(rect, w * 0.08f, w * 0.08f, stroke)
        // eyes: square LEDs; thinking = scanning dots
        p.color = screenCol
        val eyeY = cy - w * 0.08f
        for (s in SIDES) {
            val ex = cx + s * w * 0.15f
            if (thinking) {
                val k = ((now / 200) % 3).toInt()
                for (i in 0..2) { p.alpha = if (i == k) 255 else 70; canvas.drawCircle(ex - w * 0.04f + i * w * 0.04f, eyeY, w * 0.014f, p) }
                p.alpha = 255
            } else {
                val h = if (blinking) w * 0.01f else w * 0.06f
                canvas.drawRect(ex - w * 0.05f, eyeY - h, ex + w * 0.05f, eyeY + h, p)
            }
            if (cheeky) { stroke.strokeWidth = w * 0.015f; canvas.drawLine(ex - w * 0.06f, eyeY - w * 0.1f - s * w * 0.02f, ex + w * 0.06f, eyeY - w * 0.1f + s * w * 0.02f, stroke) }
        }
        // mouth: an equaliser that jumps with the voice
        val bars = 9
        val bw = w * 0.04f
        val my = cy + w * 0.17f
        for (i in 0 until bars) {
            val wob = 0.55f + 0.45f * sin(now / 70.0 + i * 1.7).toFloat()
            val h = w * (0.008f + 0.09f * mouth * wob)
            val x = cx + (i - bars / 2) * bw * 1.4f
            canvas.drawRect(x - bw / 2, my - h, x + bw / 2, my + h, p)
        }
    }

    // ---------- 🌆 neon outline on a synthwave grid ----------

    private fun neon(canvas: Canvas, now: Long) {
        shaders()
        p.shader = neonShader
        canvas.drawRect(box, p); p.shader = null
        val w = box.width(); val cx = box.centerX(); val cy = box.centerY()
        // sun and moving floor grid
        p.color = Color.argb(90, 255, 120, 60); canvas.drawCircle(cx, box.top + w * 0.62f, w * 0.3f, p)
        val horizon = box.top + w * 0.7f
        p.color = Color.rgb(25, 6, 45); canvas.drawRect(box.left, horizon, box.right, box.bottom, p)
        stroke.color = Color.argb(150, 255, 60, 200); stroke.strokeWidth = 2f
        val off = (now % 1000) / 1000f
        for (i in 0..6) { val f = ((i + off) / 7f); val yy = horizon + (box.bottom - horizon) * f * f; canvas.drawLine(box.left, yy, box.right, yy, stroke) }
        for (i in -6..6) canvas.drawLine(cx + i * w * 0.03f, horizon, cx + i * w * 0.22f, box.bottom, stroke)
        // the face: glowing lines, drawn twice for the glow
        for (pass in 0..1) {
            stroke.color = if (pass == 0) Color.argb(90, 0, 240, 255) else Color.rgb(160, 250, 255)
            stroke.strokeWidth = if (pass == 0) w * 0.04f else w * 0.013f
            val r = w * 0.3f
            rect.set(cx - r * 0.85f, cy - r * 1.05f, cx + r * 0.85f, cy + r * 0.95f)
            canvas.drawOval(rect, stroke)
            val eyeY = cy - r * 0.2f - (if (thinking) r * 0.08f else 0f)
            for (s in SIDES) {
                val ex = cx + s * r * 0.35f
                if (blinking) canvas.drawLine(ex - r * 0.14f, eyeY, ex + r * 0.14f, eyeY, stroke)
                else { rect.set(ex - r * 0.15f, eyeY - r * 0.09f, ex + r * 0.15f, eyeY + r * 0.09f); canvas.drawOval(rect, stroke) }
                if (cheeky) canvas.drawLine(ex - r * 0.18f, eyeY - r * 0.25f - s * r * 0.08f, ex + r * 0.18f, eyeY - r * 0.25f + s * r * 0.08f, stroke)
            }
            val my = cy + r * 0.45f
            val mw = r * (0.28f + 0.08f * mouth); val mh = r * (0.02f + 0.32f * mouth)
            if (mouth < 0.06f) { rect.set(cx - mw, my - r * 0.18f, cx + mw, my + r * 0.06f); canvas.drawArc(rect, 20f, 140f, false, stroke) }
            else { rect.set(cx - mw, my - mh / 2, cx + mw, my + mh / 2); canvas.drawOval(rect, stroke) }
        }
    }

    // Shaders are made once per size, not every frame: each new one is a native allocation, and while the chat model
    // loads those make the screen wait for the memory clean-up (test 200 logs: 7.5 s freeze).
    private var glowShader: Shader? = null
    private var headShader: Shader? = null
    private var neonShader: Shader? = null
    private var shadersFor = 0f

    private fun shaders() {
        val key = box.left * 7919f + box.width()
        if (key == shadersFor && glowShader != null) return
        shadersFor = key
        val cx = box.centerX(); val cy = box.centerY(); val r = box.width() * 0.36f
        glowShader = RadialGradient(cx, cy, box.width() * 0.7f, intArrayOf(blend(card, a1, 0.35f), card), null, Shader.TileMode.CLAMP)
        headShader = LinearGradient(cx - r, cy - r, cx + r, cy + r, a1, a2, Shader.TileMode.CLAMP)
        neonShader = LinearGradient(0f, box.top, 0f, box.bottom, Color.rgb(20, 6, 40), Color.rgb(60, 10, 70), Shader.TileMode.CLAMP)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    private companion object { val SIDES = floatArrayOf(-1f, 1f) }
}
