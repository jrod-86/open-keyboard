// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.sin

/**
 * Chat's little face: a round cartoon face in the screen's theme colours. The mouth opens with how loud the voice is
 * at the moment you hear it ([level]), the eyes blink now and then, and while the AI is writing it looks up, thinking.
 */
class TalkingFace(context: Context, private val a1: Int, private val a2: Int, private val level: () -> Float) : View(context) {
    /** True while the answer is being written. */
    var thinking = false

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 30, 45) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 120, 150) }
    private val mouthInside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 30, 60) }
    private val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 110, 130) }
    private val smile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 30, 45); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var mouth = 0f
    private var nextBlink = System.currentTimeMillis() + 2500
    private var gradientFor = 0

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val size = minOf(width, height).toFloat()
        val r = size / 2f * 0.94f
        val cx = width / 2f
        val cy = height / 2f
        if (gradientFor != width * 31 + height) {
            face.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, a1, a2, Shader.TileMode.CLAMP)
            gradientFor = width * 31 + height
        }
        canvas.drawCircle(cx, cy, r, face)

        // the mouth eases towards the voice's loudness, so it doesn't flicker
        val target = level()
        mouth += (target - mouth) * (if (target > mouth) 0.6f else 0.35f)

        // eyes: blink for 140 ms every few seconds; look up while thinking
        if (now > nextBlink + 140) nextBlink = now + 2200 + (Math.random() * 3000).toLong()
        val blinking = now in nextBlink..(nextBlink + 140)
        val lookUp = if (thinking) r * 0.08f else 0f
        val drift = if (thinking) (sin(now / 450.0) * r * 0.06).toFloat() else 0f
        val eyeY = cy - r * 0.18f - lookUp
        val eyeDx = r * 0.33f
        val eyeW = r * 0.13f
        val eyeH = if (blinking) r * 0.025f else r * 0.19f
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * eyeDx + drift
            rect.set(ex - eyeW, eyeY - eyeH, ex + eyeW, eyeY + eyeH)
            canvas.drawOval(rect, ink)
            if (!blinking) canvas.drawCircle(ex + eyeW * 0.35f, eyeY - eyeH * 0.4f, eyeW * 0.35f, shine)
            canvas.drawCircle(cx + side * r * 0.55f, cy + r * 0.18f, r * 0.13f, cheek)
        }

        // mouth: a smile when quiet, an open oval when talking
        val my = cy + r * 0.38f
        if (mouth < 0.06f) {
            smile.strokeWidth = r * 0.06f
            rect.set(cx - r * 0.22f, my - r * 0.18f, cx + r * 0.22f, my + r * 0.08f)
            if (thinking) canvas.drawLine(cx - r * 0.12f, my, cx + r * 0.12f, my, smile)
            else canvas.drawArc(rect, 20f, 140f, false, smile)
        } else {
            val w = r * (0.2f + 0.1f * mouth)
            val h = r * (0.05f + 0.3f * mouth)
            rect.set(cx - w, my - h * 0.5f, cx + w, my + h * 0.5f)
            canvas.drawOval(rect, mouthInside)
            if (h > r * 0.12f) {
                rect.set(cx - w * 0.55f, my + h * 0.05f, cx + w * 0.55f, my + h * 0.5f)
                canvas.drawOval(rect, tongue)
            }
        }
        // smooth while talking or thinking; idle it only needs to blink
        if (isShown) { if (target > 0f || mouth > 0.01f || thinking) postInvalidateOnAnimation() else postInvalidateDelayed(60) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }
}
