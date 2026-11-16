// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.sin

/**
 * Chat's avatar: the character's picture in a rounded box (Jarrod's 10 pictures, test 209, replaced the drawn faces).
 * While the voice talks the picture bounces and its glowing frame pulses with how loud the voice is at the moment you
 * hear it ([level]); while the AI is writing a light runs round the frame; otherwise it floats gently.
 */
class TalkingFace(context: Context, private val a1: Int, private val a2: Int, private val card: Int, private val level: () -> Float) : View(context) {
    /** The character's picture (a drawable resource). */
    var avatar = 0
        set(v) {
            if (v == field) return
            field = v
            picture = if (v == 0) null else runCatching { BitmapFactory.decodeResource(resources, v) }.getOrNull()
            invalidate()
        }
    private var picture: Bitmap? = null

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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val dst = RectF()
    private val clip = Path()
    private val spin = Matrix()
    private var sweep: SweepGradient? = null
    private var sweepFor = 0f
    private var talk = 0f

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val side = minOf(width, height).toFloat()
        val pad = side * 0.05f // room for the glow
        val left = (width - side) / 2f
        box.set(left + pad, pad, left + side - pad, side - pad)
        val corner = box.width() * 0.16f

        val target = level()
        talk += (target - talk) * (if (target > talk) 0.6f else 0.35f)

        // glowing frame: brighter and wider as the voice gets louder
        if (sweep == null || sweepFor != box.centerX() + box.centerY()) {
            sweep = SweepGradient(box.centerX(), box.centerY(), intArrayOf(a1, a2, Color.WHITE, a1), null)
            sweepFor = box.centerX() + box.centerY()
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

        // the picture: floats when idle, bounces with the voice
        val float = (sin(now / 900.0) * box.height() * 0.012).toFloat()
        val scale = 1f + 0.07f * talk
        val bob = -box.height() * 0.03f * talk + float
        canvas.save()
        clip.reset(); clip.addRoundRect(box, corner, corner, Path.Direction.CW)
        canvas.clipPath(clip)
        val pic = picture
        if (pic != null) {
            val w = box.width() * scale; val h = box.height() * scale
            dst.set(box.centerX() - w / 2, box.centerY() - h / 2 + bob, box.centerX() + w / 2, box.centerY() + h / 2 + bob)
            canvas.drawBitmap(pic, null, dst, p)
        } else {
            p.color = card
            canvas.drawRect(box, p)
        }
        canvas.restore()
        ring.shader = null; ring.color = Color.WHITE; ring.alpha = (40 + 120 * talk).toInt(); ring.strokeWidth = pad * 0.18f
        canvas.drawRoundRect(box, corner, corner, ring)

        val busy = target > 0f || talk > 0.01f || thinking
        if (isShown && !paused) { if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(40) }
    }
}
