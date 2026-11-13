// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.SettingsDestination

/**
 * "Language" tile: your keyboard languages as cards, tap one to switch to it.
 * "+ Add language" opens the languages screen to add more (Khmer, French, …).
 */
object LanguagePanel {
    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    @SuppressLint("ViewConstructor")
    private class Root(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Short name of the language the keyboard is on now, for the tile label. */
    fun currentName(): String? = runCatching {
        RichInputMethodManager.getInstance().currentSubtype.rawSubtype.displayName()
    }.getOrNull()

    fun show(context: Context) {
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        val tile = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.GESTURE_TRAIL)
        val root = Root(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8f), 0, 0)
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun pill(s: String, highlight: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f).toFloat()
                setColor(if (highlight) ColorUtils.setAlphaComponent(accent, 110) else tile)
            }
            setOnClickListener { onClick() }
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(context, 12f), 0, dp(context, 12f), 0) }
        top.addView(TextView(context).apply {
            this.text = "Keyboard language"
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("+ Add language", true) { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.Languages) })
        top.addView(pill("✕") { KeyboardMenu.hide() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)

        val current = runCatching { RichInputMethodManager.getInstance().currentSubtype.rawSubtype }.getOrNull()
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size < 2) column.addView(TextView(context).apply {
            this.text = "Only one language so far. Tap \"+ Add language\" to add another, then switch here with one tap."
            setTextColor(text)
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), 0)
        })
        val pager = KeyboardMenu.TilePager(context, dp(context, 8f), dp(context, 4f), dp(context, 58f), columns = 3).apply {
            dotColor = text
        }
        subtypes.forEach { subtype ->
            val on = subtype == current
            val card = TextView(context).apply {
                this.text = (if (on) "✓ " else "") + subtype.displayName()
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 16f).toFloat()
                    setColor(if (on) ColorUtils.setAlphaComponent(accent, 110) else tile)
                }
                setOnClickListener {
                    KeyboardMenu.hide()
                    if (!on) ime(context)?.switchToSubtype(subtype)
                }
                setOnLongClickListener { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.Languages); true }
            }
            pager.addView(card)
        }
        column.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        KeyboardMenu.showOverlay(root)
    }
}
