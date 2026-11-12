// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.latin.utils.prefs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Open Keyboard: little effects that pop out of a key when you press it (hearts, stars, paw prints...),
 * chosen in the Design studio. Drawn on the same layer as the gesture trail, above the keys.
 */
class TypingEffectsPreview(private val context: Context) : AbstractDrawingPreview() {
    companion object {
        const val PREF = "open_keyboard_typing_effect"
        /** id to (name, symbols). Symbols are emoji drawn as text; empty means a drawn shape. */
        val effects = listOf(
            "none" to ("None" to listOf()),
            "hearts" to ("Hearts" to listOf("❤️", "💕", "💗")),
            "stars" to ("Stars" to listOf("⭐", "🌟", "✨")),
            "paws" to ("Paw prints" to listOf("🐾")),
            "sparkles" to ("Sparkles" to listOf("✨")),
            "leaves" to ("Leaves" to listOf("🍃", "🌿", "🍀")),
            "flowers" to ("Flowers" to listOf("🌸", "🌼", "🌷")),
            "snow" to ("Snowflakes" to listOf("❄️")),
            "fire" to ("Fire" to listOf("🔥")),
            "music" to ("Music notes" to listOf("🎵", "🎶")),
            "bubbles" to ("Bubbles" to listOf()),
            "confetti" to ("Confetti" to listOf()),
            "ripple" to ("Ripple" to listOf()),
        )
        private val confettiColors = intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFD23F.toInt(), 0xFF2EC4B6.toInt(), 0xFF9B5DE5.toInt(), 0xFF00BBF9.toInt())
    }

    private class Particle(
        val x0: Float, val y0: Float, val vx: Float, val vy: Float, val spin: Float,
        val symbol: String?, val color: Int, val size: Float, val start: Long, val life: Long,
    )

    private val particles = ArrayList<Particle>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density
    var accentColor = Color.WHITE

    fun effect(): String = context.prefs().getString(PREF, "none") ?: "none"

    /** Starts the chosen effect at the middle of a key ([x], [y] in keyboard view coordinates). */
    fun spawn(x: Float, y: Float) {
        val id = effect()
        if (id == "none") return
        val symbols = effects.firstOrNull { it.first == id }?.second?.second ?: return
        val now = SystemClock.uptimeMillis()
        if (particles.size > 60) particles.subList(0, particles.size - 60).clear()
        when (id) {
            "ripple" -> particles.add(Particle(x, y, 0f, 0f, 0f, null, accentColor, 34 * density, now, 450))
            else -> {
                val count = when (id) { "confetti" -> 8; "bubbles" -> 4; "paws" -> 2; else -> 3 }
                repeat(count) {
                    val angle = Math.toRadians(-90.0 + Random.nextDouble(-60.0, 60.0))
                    val speed = (if (id == "paws") 70f else 130f + Random.nextFloat() * 160f) * density
                    particles.add(Particle(
                        x, y, (cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat(),
                        Random.nextFloat() * 240f - 120f,
                        if (symbols.isEmpty()) null else symbols[Random.nextInt(symbols.size)],
                        if (id == "confetti") confettiColors[Random.nextInt(confettiColors.size)] else accentColor,
                        (if (id == "bubbles") 8f + Random.nextFloat() * 8f else 16f + Random.nextFloat() * 8f) * density,
                        now + it * 25L, if (id == "paws") 900 else 750,
                    ))
                }
            }
        }
        invalidateDrawingView()
    }

    override fun drawPreview(canvas: Canvas) {
        if (particles.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val id = effect()
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val age = now - p.start
            if (age < 0) continue
            if (age > p.life) { iter.remove(); continue }
            val t = age / 1000f
            val progress = age.toFloat() / p.life
            val alpha = ((if (progress < 0.6f) 1f else 1f - (progress - 0.6f) / 0.4f) * 255).toInt().coerceIn(0, 255)
            if (id == "ripple" || (p.symbol == null && p.vx == 0f && p.vy == 0f)) {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = 3 * density * (1 - progress)
                shapePaint.color = p.color
                shapePaint.alpha = ((1 - progress) * 200).toInt()
                canvas.drawCircle(p.x0, p.y0, p.size * (0.3f + progress), shapePaint)
                continue
            }
            val gravity = if (id == "bubbles" || id == "fire" || id == "music") -60f * density else 380f * density
            val x = p.x0 + p.vx * t
            val y = p.y0 + p.vy * t + 0.5f * gravity * t * t
            val scale = if (progress < 0.15f) 0.5f + progress / 0.15f * 0.5f else 1f
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(p.spin * t)
            canvas.scale(scale, scale)
            when {
                p.symbol != null -> {
                    textPaint.textSize = p.size
                    textPaint.alpha = alpha
                    canvas.drawText(p.symbol, 0f, p.size / 3, textPaint)
                }
                id == "bubbles" -> {
                    shapePaint.style = Paint.Style.STROKE; shapePaint.strokeWidth = 2 * density
                    shapePaint.color = Color.WHITE; shapePaint.alpha = alpha
                    canvas.drawCircle(0f, 0f, p.size, shapePaint)
                    shapePaint.style = Paint.Style.FILL; shapePaint.alpha = alpha / 3
                    canvas.drawCircle(-p.size / 3, -p.size / 3, p.size / 4, shapePaint)
                }
                else -> { // confetti
                    shapePaint.style = Paint.Style.FILL; shapePaint.color = p.color; shapePaint.alpha = alpha
                    canvas.drawRect(-p.size / 3, -p.size / 6, p.size / 3, p.size / 6, shapePaint)
                }
            }
            canvas.restore()
        }
        if (particles.isNotEmpty()) invalidateDrawingView()
    }

    override fun onDeallocateMemory() { particles.clear() }

    override fun setPreviewPosition(tracker: PointerTracker) {}
}
