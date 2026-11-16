// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Chat's avatar: the character's picture (Jarrod's 10 pictures, test 209) in a glowing rounded box.
 *
 * Test 210 (Jarrod: "lip sync like before" and "the background behind them should move"):
 * - The mouth opens with how loud the voice is at the moment you hear it ([level]): the part of the face below the
 *   mouth (chin, beard, lower lip) drops and the gap shows the inside of the mouth. Line-smile robots get an open
 *   glowing mouth drawn instead.
 * - The background moves: the picture is split into the character (a soft oval) and what's behind it; the back sways
 *   and breathes, with the character's own particles drifting through it (falling code, stars, embers, leaves…).
 */
class TalkingFace(context: Context, private val a1: Int, private val a2: Int, private val card: Int, private val level: () -> Float) : View(context) {

    private enum class Bits { CODE, STARS, EMBERS, FIREFLIES, SPARKS, PIXELS, BUBBLES, NEON, MAGIC }

    /**
     * Where things are in each picture, as fractions of its size: the mouth line (x, y), mouth width, and the oval
     * that holds the character. [shape]: draw an open mouth (robots whose mouth is just a line) instead of dropping the jaw.
     */
    private class Look(val mx: Float, val my: Float, val mw: Float, val fx: Float, val fy: Float, val rx: Float, val ry: Float,
                       val bits: Bits, val colors: IntArray, val shape: Boolean = false, val jaw: Float = 1.7f)

    private val looks = mapOf(
        "cipher" to Look(0.51f, 0.632f, 0.15f, 0.5f, 0.5f, 0.33f, 0.46f, Bits.CODE, intArrayOf(0xFF39FF6A.toInt(), 0xFF9CFFB0.toInt())),
        "bit" to Look(0.50f, 0.585f, 0.16f, 0.5f, 0.5f, 0.33f, 0.36f, Bits.PIXELS, intArrayOf(0xFF22F3F0.toInt(), 0xFFFF3B5C.toInt()), shape = true),
        "robo" to Look(0.495f, 0.575f, 0.11f, 0.5f, 0.47f, 0.33f, 0.40f, Bits.BUBBLES, intArrayOf(0xFF4FC3FF.toInt(), 0xFFFFFFFF.toInt()), shape = true),
        "wizard" to Look(0.50f, 0.525f, 0.09f, 0.5f, 0.5f, 0.34f, 0.44f, Bits.MAGIC, intArrayOf(0xFFB388FF.toInt(), 0xFF64D8FF.toInt()), jaw = 3.2f),
        "holo" to Look(0.50f, 0.60f, 0.11f, 0.5f, 0.47f, 0.31f, 0.34f, Bits.BUBBLES, intArrayOf(0xFF40C4FF.toInt(), 0xFF80FFFF.toInt()), shape = true),
        "stella" to Look(0.51f, 0.615f, 0.11f, 0.5f, 0.55f, 0.27f, 0.36f, Bits.STARS, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE1A8FF.toInt())),
        "oakley" to Look(0.50f, 0.57f, 0.10f, 0.5f, 0.5f, 0.33f, 0.43f, Bits.FIREFLIES, intArrayOf(0xFFD4FF5A.toInt(), 0xFF8BE04E.toInt()), jaw = 3.0f),
        "sprocket" to Look(0.50f, 0.605f, 0.13f, 0.5f, 0.5f, 0.32f, 0.40f, Bits.SPARKS, intArrayOf(0xFFFFB347.toInt(), 0xFFFFE08A.toInt())),
        "zap" to Look(0.50f, 0.605f, 0.12f, 0.5f, 0.52f, 0.31f, 0.32f, Bits.NEON, intArrayOf(0xFFFF4FD8.toInt(), 0xFF3CF0FF.toInt())),
        "blaze" to Look(0.54f, 0.645f, 0.18f, 0.52f, 0.56f, 0.32f, 0.37f, Bits.EMBERS, intArrayOf(0xFFFFA726.toInt(), 0xFFFFE082.toInt())),
    )

    private var look: Look? = null
    private var picture: Bitmap? = null
    private var front: Bitmap? = null
    private var shader: BitmapShader? = null
    private var inside = Color.BLACK // colour inside the open mouth
    private var lineColor = Color.WHITE // a line-smile robot's glow colour
    private var shownId: String? = null

    /** Shows character [id]'s picture ([res]). */
    fun show(id: String, res: Int) {
        if (id == shownId) return
        shownId = id
        val pic = runCatching { BitmapFactory.decodeResource(resources, res, BitmapFactory.Options().apply { inScaled = false }) }.getOrNull()
        picture = pic; look = looks[id]
        front = null; shader = null
        val l = look
        if (pic != null && l != null) {
            front = cutOut(pic, l)
            shader = BitmapShader(pic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            inside = mouthColor(pic, l)
            lineColor = brightest(pic, l)
        }
        particles.clear()
        invalidate()
    }

    /** True while the answer is being written. */
    var thinking = false
    /**
     * Holds still while the AI loads (test 202 logs): every animation frame also makes the message box work out
     * where its cursor is for the keyboard, which needs a little memory, and while 1-3 GB of model arrives the phone
     * makes the screen wait for every such bit. That held up the screen, and the keyboard waiting on it.
     */
    var paused = false
        set(v) { field = v; if (!v) invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val oval = RectF()
    private val clip = Path()
    /** Soft oval for the dropping jaw: solid in the middle, see-through at the edge (a unit circle, scaled when used). */
    private val jawMask = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val m = Matrix()
    private val spin = Matrix()
    private var sweep: SweepGradient? = null
    private var sweepFor = 0f
    private var talk = 0f
    private var lastFrame = 0L

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val side = minOf(width, height).toFloat()
        val pad = side * 0.05f // room for the glow
        val left = (width - side) / 2f
        box.set(left + pad, pad, left + side - pad, side - pad)
        val corner = box.width() * 0.16f

        val target = level()
        talk += (target - talk) * (if (target > talk) 0.7f else 0.5f)

        glowFrame(canvas, now, pad, corner)

        canvas.save()
        clip.reset(); clip.addRoundRect(box, corner, corner, Path.Direction.CW)
        canvas.clipPath(clip)
        val pic = picture
        val l = look
        if (pic == null) {
            fill.color = card; canvas.drawRect(box, fill)
        } else {
            // work in the picture's own pixels from here on
            val s = box.width() / pic.width
            canvas.translate(box.left, box.top)
            canvas.scale(s, s)
            val w = pic.width.toFloat(); val h = pic.height.toFloat()
            val fr = front
            if (l == null || fr == null) {
                canvas.drawBitmap(pic, 0f, 0f, p)
            } else {
                // the background: sways and breathes behind the character
                canvas.save()
                val sway = (sin(now / 2600.0) * 2.2).toFloat()
                val zoom = 1.07f + 0.025f * sin(now / 3100.0).toFloat()
                canvas.rotate(sway, w / 2, h / 2)
                canvas.scale(zoom, zoom, w / 2, h / 2)
                canvas.drawBitmap(pic, 0f, 0f, p)
                canvas.restore()
                particles(canvas, l, w, h, dt, now)
                // the character, still, with a slight float, and its mouth
                val bob = (sin(now / 1100.0) * h * 0.006).toFloat() - h * 0.008f * talk
                canvas.translate(0f, bob)
                canvas.drawBitmap(fr, 0f, 0f, p)
                mouth(canvas, l, w, h)
            }
        }
        canvas.restore()
        ring.shader = null; ring.color = Color.WHITE; ring.alpha = (40 + 120 * talk).toInt().coerceIn(0, 255); ring.strokeWidth = pad * 0.18f
        canvas.drawRoundRect(box, corner, corner, ring)

        // smooth while talking or thinking; 30 frames a second is plenty for the drifting background
        val busy = target > 0f || talk > 0.01f || thinking
        if (isShown && !paused) { if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(33) }
    }

    private fun glowFrame(canvas: Canvas, now: Long, pad: Float, corner: Float) {
        if (sweep == null || sweepFor != box.centerX() + box.centerY() * 7) {
            sweep = SweepGradient(box.centerX(), box.centerY(), intArrayOf(a1, a2, Color.WHITE, a1), null)
            sweepFor = box.centerX() + box.centerY() * 7
        }
        spin.setRotate(if (thinking) (now % 1600) / 1600f * 360f else (now % 12000) / 12000f * 360f, box.centerX(), box.centerY())
        sweep!!.setLocalMatrix(spin)
        ring.shader = sweep
        val glow = if (thinking) 0.75f else 0.35f + 0.65f * talk
        for (i in 3 downTo 1) {
            ring.strokeWidth = pad * (0.35f + 0.45f * i * glow)
            ring.alpha = (60 * glow / i + 20).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(box, corner, corner, ring)
        }
    }

    /** Opens the mouth by [talk] (0..1). */
    private fun mouth(canvas: Canvas, l: Look, w: Float, h: Float) {
        val open = talk.coerceIn(0f, 1f)
        if (open < 0.03f) return
        val mx = l.mx * w; val my = l.my * h; val mw = l.mw * w
        if (l.shape) {
            // a glowing open mouth over the robot's smile line
            val oh = mw * (0.12f + 0.55f * open)
            oval.set(mx - mw * 0.5f, my - oh * 0.35f, mx + mw * 0.5f, my + oh * 0.65f)
            fill.shader = null; fill.color = inside
            canvas.drawRoundRect(oval, oh * 0.5f, oh * 0.5f, fill)
            line.color = lineColor; line.strokeWidth = mw * 0.09f
            canvas.drawRoundRect(oval, oh * 0.5f, oh * 0.5f, line)
            return
        }
        val drop = mw * 0.42f * open
        val jawW = mw * 1.9f
        val jawH = mw * l.jaw
        // the gap: inside of the mouth
        fill.shader = null; fill.color = inside
        oval.set(mx - mw * 0.46f, my - drop * 0.15f, mx + mw * 0.46f, my + drop * 1.15f)
        canvas.drawOval(oval, fill)
        // everything below the mouth line drops by [drop], fading out softly towards the sides and the chin
        val top = my + drop
        oval.set(mx - jawW / 2, top, mx + jawW / 2, top + jawH)
        canvas.saveLayer(oval, null)
        m.setTranslate(0f, drop)
        shader!!.setLocalMatrix(m)
        fill.shader = shader
        canvas.drawRect(oval, fill)
        jawMask.setLocalMatrix(m.apply { setScale(jawW / 2, jawH); postTranslate(mx, top) })
        fade.shader = jawMask
        canvas.drawRect(oval, fade)
        canvas.restore()
        fill.shader = null
        // a dark rim where the lips part, so the opening reads as a mouth
        fill.color = (inside and 0x00FFFFFF) or (0x99 shl 24)
        oval.set(mx - mw * 0.48f, my + drop - mw * 0.03f, mx + mw * 0.48f, my + drop + mw * 0.03f)
        canvas.drawOval(oval, fill)
    }

    // ---- what moves in the background ----

    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var size: Float, var life: Float, var color: Int, var glyph: Char)
    private val particles = ArrayList<Bit>()
    private val glyphs = "アイウエオカキクケコサシスセソ0123456789".toCharArray()

    private fun particles(canvas: Canvas, l: Look, w: Float, h: Float, dt: Float, now: Long) {
        val want = when (l.bits) { Bits.CODE -> 46; Bits.STARS -> 40; else -> 28 }
        while (particles.size < want) particles.add(spawn(l, w, h, particles.size < want / 2 && particles.isEmpty().not()))
        val busy = 1f + 1.5f * talk // things hurry a little while it talks
        val it = particles.iterator()
        val fresh = ArrayList<Bit>()
        while (it.hasNext()) {
            val b = it.next()
            b.x += b.vx * dt * busy; b.y += b.vy * dt * busy; b.life -= dt
            if (b.life <= 0f || b.y < -0.1f * h || b.y > 1.1f * h || b.x < -0.1f * w || b.x > 1.1f * w) { it.remove(); fresh.add(spawn(l, w, h, false)); continue }
            val fade = minOf(1f, b.life / 0.8f)
            when (l.bits) {
                Bits.CODE -> {
                    glyphPaint.color = b.color; glyphPaint.alpha = (200 * fade).toInt(); glyphPaint.textSize = b.size
                    if (Random.nextFloat() < 0.04f) b.glyph = glyphs[Random.nextInt(glyphs.size)]
                    canvas.drawText(charArrayOf(b.glyph), 0, 1, b.x, b.y, glyphPaint)
                }
                Bits.STARS, Bits.MAGIC -> {
                    val tw = 0.55f + 0.45f * sin(now / 260.0 + b.size * 10).toFloat()
                    fill.color = b.color; fill.alpha = (230 * fade * tw).toInt().coerceIn(0, 255)
                    canvas.drawCircle(b.x, b.y, b.size * 0.35f, fill)
                    line.color = b.color; line.alpha = fill.alpha / 2; line.strokeWidth = b.size * 0.12f
                    canvas.drawLine(b.x - b.size, b.y, b.x + b.size, b.y, line); canvas.drawLine(b.x, b.y - b.size, b.x, b.y + b.size, line)
                }
                Bits.PIXELS -> {
                    fill.color = b.color; fill.alpha = (200 * fade).toInt()
                    canvas.drawRect(b.x, b.y, b.x + b.size, b.y + b.size, fill)
                }
                Bits.NEON -> {
                    line.color = b.color; line.alpha = (210 * fade).toInt(); line.strokeWidth = b.size * 0.3f
                    canvas.drawLine(b.x, b.y, b.x - b.vx * 0.12f, b.y, line)
                }
                else -> {
                    val flicker = if (l.bits == Bits.FIREFLIES) 0.5f + 0.5f * sin(now / 300.0 + b.size * 7).toFloat() else 1f
                    fill.color = b.color; fill.alpha = (210 * fade * flicker).toInt().coerceIn(0, 255)
                    canvas.drawCircle(b.x, b.y, b.size * 0.5f, fill)
                    fill.alpha = (60 * fade * flicker).toInt().coerceIn(0, 255)
                    canvas.drawCircle(b.x, b.y, b.size * 1.2f, fill)
                }
            }
        }
        particles.addAll(fresh)
    }

    private fun spawn(l: Look, w: Float, h: Float, anywhere: Boolean): Bit {
        val r = Random
        val c = l.colors[r.nextInt(l.colors.size)]
        val x = r.nextFloat() * w
        val life = 3f + r.nextFloat() * 4f
        return when (l.bits) {
            Bits.CODE -> Bit((r.nextInt(24) + 0.5f) * w / 24, if (anywhere) r.nextFloat() * h else -r.nextFloat() * h * 0.3f, 0f,
                h * (0.12f + r.nextFloat() * 0.18f), w * 0.035f, 9f, c, glyphs[r.nextInt(glyphs.size)])
            Bits.STARS, Bits.MAGIC -> Bit(x, r.nextFloat() * h, w * (r.nextFloat() - 0.5f) * 0.03f, -h * r.nextFloat() * 0.02f,
                w * (0.008f + r.nextFloat() * 0.016f), life, c, ' ')
            Bits.EMBERS, Bits.SPARKS -> Bit(x, if (anywhere) r.nextFloat() * h else h * (1f + r.nextFloat() * 0.05f), w * (r.nextFloat() - 0.5f) * 0.05f,
                -h * (0.06f + r.nextFloat() * 0.1f), w * (0.008f + r.nextFloat() * 0.012f), life + 4f, c, ' ')
            Bits.FIREFLIES -> Bit(x, r.nextFloat() * h, w * (r.nextFloat() - 0.5f) * 0.05f, h * (r.nextFloat() - 0.6f) * 0.04f,
                w * (0.008f + r.nextFloat() * 0.01f), life, c, ' ')
            Bits.PIXELS -> Bit(x, if (anywhere) r.nextFloat() * h else h * 1.02f, 0f, -h * (0.03f + r.nextFloat() * 0.05f),
                w * (0.01f + r.nextFloat() * 0.012f), life + 6f, c, ' ')
            Bits.NEON -> Bit(if (anywhere) x else -0.05f * w, r.nextFloat() * h, w * (0.15f + r.nextFloat() * 0.25f), 0f,
                w * (0.01f + r.nextFloat() * 0.01f), life, c, ' ')
            Bits.BUBBLES -> Bit(x, if (anywhere) r.nextFloat() * h else h * 1.02f, w * (r.nextFloat() - 0.5f) * 0.02f, -h * (0.03f + r.nextFloat() * 0.05f),
                w * (0.006f + r.nextFloat() * 0.012f), life + 6f, c, ' ')
        }
    }

    // ---- reading the picture ----

    /** The character on its own: the picture inside a soft-edged oval, so the background can move behind it. */
    private fun cutOut(pic: Bitmap, l: Look): Bitmap {
        val w = pic.width.toFloat(); val h = pic.height.toFloat()
        val out = Bitmap.createBitmap(pic.width, pic.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(pic, 0f, 0f, null)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
        val gm = Matrix().apply { setScale(l.rx * w, l.ry * h); postTranslate(l.fx * w, l.fy * h) }
        g.setLocalMatrix(gm)
        mask.shader = g
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, h, mask)
        return out
    }

    /** The darkest colour around the mouth, for the inside of it. */
    private fun mouthColor(pic: Bitmap, l: Look): Int {
        var best = Color.BLACK; var bestL = Float.MAX_VALUE
        val cx = (l.mx * pic.width).toInt(); val cy = (l.my * pic.height).toInt(); val r = (l.mw * pic.width / 2).toInt()
        for (dx in -r..r step 2) for (dy in -r / 3..r / 3 step 2) {
            val x = (cx + dx).coerceIn(0, pic.width - 1); val y = (cy + dy).coerceIn(0, pic.height - 1)
            val c = pic.getPixel(x, y)
            val lum = Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f
            if (lum < bestL) { bestL = lum; best = c }
        }
        // a little darker still
        return Color.rgb((Color.red(best) * 0.6f).toInt(), (Color.green(best) * 0.6f).toInt(), (Color.blue(best) * 0.6f).toInt())
    }

    /** The glow colour of a robot's smile line. */
    private fun brightest(pic: Bitmap, l: Look): Int {
        var best = Color.WHITE; var bestS = -1f
        val cx = (l.mx * pic.width).toInt(); val cy = (l.my * pic.height).toInt(); val r = (l.mw * pic.width / 2).toInt()
        val hsv = FloatArray(3)
        for (dx in -r..r step 2) for (dy in -r / 2..r / 2 step 2) {
            val c = pic.getPixel((cx + dx).coerceIn(0, pic.width - 1), (cy + dy).coerceIn(0, pic.height - 1))
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2] + hsv[2] * 0.3f
            if (score > bestS) { bestS = score; best = c }
        }
        return best
    }

}
