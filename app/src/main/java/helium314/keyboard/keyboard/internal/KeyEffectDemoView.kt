// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/** A single demo key for the Design studio that keeps playing one key effect, like the Keys Cafe tiles. */
class KeyEffectDemoView(context: Context) : View(context) {
    var kind = "around" // around, color or motion
    var effectId = "none"
    var keyColor = 0xFFF2F4F7.toInt()
    var textColor = 0xFF3355AA.toInt()
    private val start = SystemClock.uptimeMillis() - (Math.random() * 900).toLong()
    private val density = resources.displayMetrics.density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val r = RectF()
    private var seed = 0
    private var lastCycle = -1L

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) * 0.56f
        r.set(width / 2f - size / 2, height / 2f - size / 2, width / 2f + size / 2, height / 2f + size / 2)
        val cycleLen = 1500L
        val elapsed = SystemClock.uptimeMillis() - start
        val cycle = elapsed / cycleLen
        if (cycle != lastCycle) { lastCycle = cycle; seed = (Math.random() * 100000).toInt() }
        val inCycle = elapsed % cycleLen
        val t = (inCycle.toFloat() / KeyEffects.DURATION).coerceAtMost(1f)
        val e = KeyEffects
        if (kind == "around") e.drawAround(canvas, effectId, r, t, seed, density)
        canvas.save()
        if (kind == "motion") e.applyMotion(canvas, effectId, r, inCycle)
        keyPaint.color = keyColor
        val radius = size * 0.22f
        canvas.drawRoundRect(r, radius, radius, keyPaint)
        if (kind == "color") e.drawInside(canvas, effectId, r, t, seed, density)
        textPaint.color = textColor
        textPaint.textSize = size * 0.38f
        canvas.drawText("A", r.centerX(), r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        canvas.restore()
        if (effectId != "none") postInvalidateOnAnimation()
    }
}
