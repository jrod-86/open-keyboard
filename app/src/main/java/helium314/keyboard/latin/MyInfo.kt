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
            this.text = context.getString(R.string.ok_tile_my_info)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill(context.getString(R.string.ok_tile_add_edit), true) { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.TextShortcuts) })
        top.addView(pill("✕") { KeyboardMenu.hide() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)

        val items = TextShortcuts.load(context)
        if (items.isEmpty()) column.addView(TextView(context).apply {
            this.text = "Nothing saved yet. Tap \"+ Add / edit\" and save things like your email, phone number and address."
            setTextColor(text)
            setPadding(dp(context, 12f), dp(context, 12f), dp(context, 12f), 0)
        })
        // small cards, 3 across, swipe for more (like the grid menu)
        val pager = KeyboardMenu.TilePager(context, dp(context, 8f), dp(context, 4f), dp(context, 58f), columns = 3).apply {
            dotColor = text
        }
        items.forEach { item ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply { cornerRadius = dp(context, 16f).toFloat(); setColor(tile) }
                isClickable = true
                contentDescription = item.text
                setOnClickListener { KeyboardMenu.hide(); type(context, item.text) }
                setOnLongClickListener { KeyboardMenu.hide(); ime(context)?.launchSettings(SettingsDestination.TextShortcuts); true }
            }
            card.addView(TextView(context).apply {
                this.text = item.trigger
                setTextColor(ColorUtils.setAlphaComponent(text, 170))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(TextView(context).apply {
                this.text = item.text
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            pager.addView(card)
        }
        column.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    /** Replaces the typed start with the whole saved text, then a space. */
    private fun complete(ime: LatinIME, typed: String, full: String) {
        val ic = ime.currentInputConnection ?: return
        ime.mInputLogic.finishInput()
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (ic.getTextBeforeCursor(typed.length, 0)?.toString().equals(typed, ignoreCase = true)) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$full ", 1)
        ic.endBatchEdit()
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0)
            ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
        KeyboardSwitcher.getInstance().updateShiftState(ime.currentAutoCapsState, ime.currentRecapitalizeState)
        ime.setNeutralSuggestionStrip()
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

    private fun isPassword(ei: EditorInfo?): Boolean {
        ei ?: return false
        val v = ei.inputType and InputType.TYPE_MASK_VARIATION
        val cls = ei.inputType and InputType.TYPE_MASK_CLASS
        return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** Saved text that starts with what's being typed (at least 3 letters), e.g. "jro" → your email. */
    private fun completion(ctx: Context, before: String): Pair<String, String>? {
        val token = before.takeLastWhile { !it.isWhitespace() }
        if (token.length < 3) return null
        val item = TextShortcuts.load(ctx).firstOrNull { val t = it.text.trim(); t.length > token.length && t.startsWith(token, ignoreCase = true) }
            ?: return null
        return token to item.text.trim()
    }

    /** A chip for the top bar: the start of something saved here was typed, or the empty box asks for it. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        if (!Plugins.isOn(ime, "shortcuts")) return null
        val ei = ime.currentInputEditorInfo
        if (isPassword(ei)) return null
        val ic = ime.currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (before.isNotEmpty() && (after.isEmpty() || after[0].isWhitespace())) {
            val (typed, full) = completion(ime, before) ?: return null
            return makeChip(ime, parent, "★  ${full.lineSequence().first()}") { complete(ime, typed, full) }
        }
        val kind = kindOf(ei) ?: return null
        if (before.isNotEmpty() || after.isNotEmpty()) return null
        val value = match(ime, kind) ?: return null
        val icon = when (kind) { Kind.EMAIL -> "✉"; Kind.PHONE -> "☎"; Kind.ADDRESS -> "⌂"; Kind.NAME -> "☺" }
        return makeChip(ime, parent, "$icon  ${value.lineSequence().first()}") { type(ime, value); ime.setNeutralSuggestionStrip() }
    }

    private fun makeChip(ime: LatinIME, parent: ViewGroup, label: String, onTap: () -> Unit): View {
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = label
        val colors = ime.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionClose.visibility = View.GONE
        textView.setOnClickListener { onTap() }
        return binding.root
    }
}
