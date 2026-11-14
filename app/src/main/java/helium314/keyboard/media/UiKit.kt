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
    val PURPLE = Color.rgb(142, 84, 233)

    fun night(ctx: Context) = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    fun cardColor(ctx: Context) = if (night(ctx)) Color.rgb(36, 37, 42) else Color.rgb(242, 243, 247)
    fun lineColor(ctx: Context) = if (night(ctx)) Color.rgb(64, 66, 74) else Color.rgb(216, 218, 226)
    fun card(ctx: Context, radiusDp: Float = 16f) = rounded(ctx, radiusDp, cardColor(ctx), lineColor(ctx))
    fun shine(ctx: Context, radiusDp: Float) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PURPLE, ACCENT)).apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
    }

    /** A big title painted purple to blue. */
    fun shinyTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 6f), 0, 0)
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (v as TextView).paint.shader = android.graphics.LinearGradient(0f, 0f, v.width.toFloat(), 0f, PURPLE, ACCENT, android.graphics.Shader.TileMode.CLAMP)
        }
    }

    /** The main action: purple-to-blue, bold. */
    fun glow(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD; stateListAnimator = null
        background = shine(ctx, 26f)
        setOnClickListener { onClick() }
    }

    /** A quieter button on a card. */
    fun chip(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); stateListAnimator = null
        background = card(ctx, 22f); setPadding(dp(ctx, 6f), 0, dp(ctx, 6f), 0)
        setOnClickListener { onClick() }
    }

    /** Shows a [chip] as chosen (shiny) or not. */
    fun setChosen(b: TextView, on: Boolean, normalText: Int) {
        b.background = if (on) shine(b.context, 22f) else card(b.context, 22f)
        b.setTextColor(if (on) Color.WHITE else normalText)
    }

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
        background = card(ctx, 14f)
    }
}
