// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Small shared look for Open Keyboard's own screens (Image maker, Sticker maker). */
object UiKit {
    val ACCENT = Color.rgb(66, 133, 244)

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun rounded(ctx: Context, radiusDp: Float, fill: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat(); setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, 1f), stroke)
    }

    fun title(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 8f), 0, dp(ctx, 4f))
    }

    fun hint(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.75f
        setPadding(0, 0, 0, dp(ctx, 12f))
    }

    fun primary(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(ctx, 24f, ACCENT); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun outline(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; setTextColor(ACCENT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(ctx, 22f, Color.TRANSPARENT, ACCENT); stateListAnimator = null
        setPadding(dp(ctx, 8f), 0, dp(ctx, 8f), 0)
        setOnClickListener { onClick() }
    }

    fun field(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint = hint; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val p = dp(ctx, 14f); setPadding(p, p * 3 / 4, p, p * 3 / 4)
        background = rounded(ctx, 14f, Color.argb(28, 128, 128, 128), Color.argb(90, 128, 128, 128))
    }
}
