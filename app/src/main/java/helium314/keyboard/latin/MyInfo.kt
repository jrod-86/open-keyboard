// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsDestination

/**
 * "My info": your saved details (email, phone, address, names…) one tap away. It uses the same list
 * as text shortcuts, so everything saved works both ways: tap it here, or type its shortcut.
 * When a box asks for an email, phone number, address or name, the matching saved one is offered
 * in the top bar.
 */
object MyInfo {
    private enum class Kind { EMAIL, PHONE, ADDRESS, NAME }

    private val emailRe = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val phoneRe = Regex("^\\+?[\\d ()-]{6,20}$")

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    // ---- the panel ----

    @SuppressLint("ViewConstructor")
    private class Root(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

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
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        }
        root.addView(ScrollView(context).apply { addView(column) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            this.text = "My info"
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("+ Add / edit", true) { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.TextShortcuts) })
        top.addView(pill("✕") { KeyboardMenu.hide() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)

        val items = TextShortcuts.load(context)
        if (items.isEmpty()) column.addView(TextView(context).apply {
            this.text = "Nothing saved yet. Tap \"+ Add / edit\" and save things like your email, phone number and address."
            setTextColor(text)
            setPadding(0, dp(context, 12f), 0, 0)
        })
        items.forEach { item ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14f), dp(context, 10f), dp(context, 14f), dp(context, 10f))
                background = GradientDrawable().apply { cornerRadius = dp(context, 16f).toFloat(); setColor(tile) }
                isClickable = true
                setOnClickListener { KeyboardMenu.hide(); type(context, item.text) }
                setOnLongClickListener { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.TextShortcuts); true }
            }
            card.addView(TextView(context).apply {
                this.text = item.trigger
                setTextColor(ColorUtils.setAlphaComponent(text, 170))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            card.addView(TextView(context).apply {
                this.text = item.text
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 8f)
            })
        }
        column.addView(TextView(context).apply {
            this.text = "Tap one to type it. Each one also works as a text shortcut: type its name and press space."
            setTextColor(ColorUtils.setAlphaComponent(text, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(context, 12f), 0, 0)
        })
        KeyboardMenu.showOverlay(root)
    }

    private fun type(context: Context, value: String) {
        val ime = ime(context) ?: return
        val ic = ime.currentInputConnection ?: return
        ime.mInputLogic.finishInput()
        ic.beginBatchEdit()
        ic.finishComposingText()
        // a space before it when it follows a word
        val before = ic.getTextBeforeCursor(1, 0)
        val lead = if (!before.isNullOrEmpty() && !before[0].isWhitespace()) " " else ""
        ic.commitText(lead + value, 1)
        ic.endBatchEdit()
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0)
            ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
        KeyboardSwitcher.getInstance().updateShiftState(ime.currentAutoCapsState, ime.currentRecapitalizeState)
    }

    // ---- offering the right detail for the box ----

    private fun kindOf(ei: EditorInfo?): Kind? {
        ei ?: return null
        val cls = ei.inputType and InputType.TYPE_MASK_CLASS
        val variation = ei.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_PHONE) return Kind.PHONE
        if (cls == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return Kind.EMAIL
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> return Kind.ADDRESS
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> return Kind.NAME
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> return null
            }
        }
        // web pages often use plain boxes; their hint says what they want
        val hint = (ei.hintText?.toString().orEmpty() + " " + (ei.label?.toString().orEmpty())).lowercase()
        return when {
            "email" in hint || "e-mail" in hint -> Kind.EMAIL
            "phone" in hint || "mobile" in hint -> Kind.PHONE
            "address" in hint -> Kind.ADDRESS
            "name" in hint && "user" !in hint -> Kind.NAME
            else -> null
        }
    }

    private fun match(ctx: Context, kind: Kind): String? {
        val items = TextShortcuts.load(ctx)
        fun named(vararg words: String) = items.firstOrNull { s -> words.any { it in s.trigger.lowercase() } }?.text
        return when (kind) {
            Kind.EMAIL -> items.firstOrNull { emailRe.matches(it.text.trim()) }?.text?.trim()
            Kind.PHONE -> items.firstOrNull { phoneRe.matches(it.text.trim()) && it.text.count(Char::isDigit) >= 6 }?.text?.trim()
            Kind.ADDRESS -> named("address", "addr", "home")
            Kind.NAME -> named("name", "fullname")
        }
    }

    /** A chip for the top bar when the box is empty and asks for something saved here. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        if (!Plugins.isOn(ime, "shortcuts")) return null
        val kind = kindOf(ime.currentInputEditorInfo) ?: return null
        val ic = ime.currentInputConnection ?: return null
        if (!ic.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return null
        val value = match(ime, kind) ?: return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val textView = binding.clipboardSuggestionText
        val icon = when (kind) { Kind.EMAIL -> "✉"; Kind.PHONE -> "☎"; Kind.ADDRESS -> "⌂"; Kind.NAME -> "☺" }
        textView.text = "$icon  ${value.lineSequence().first()}"
        val colors = ime.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionClose.visibility = View.GONE
        textView.setOnClickListener { type(ime, value); ime.setNeutralSuggestionStrip() }
        return binding.root
    }
}
