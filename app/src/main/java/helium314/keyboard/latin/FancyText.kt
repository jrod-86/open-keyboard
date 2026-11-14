// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Settings

/**
 * "Fancy text": pick a style, then type as normal and every letter comes out in it (𝐛𝐨𝐥𝐝, 𝓼𝓬𝓻𝓲𝓹𝓽, ⓑⓤⓑⓑⓛⓔ…).
 * The styles are plain Unicode characters, so they work in any app that shows text. A chip in the top
 * bar shows the style is on; ✕ turns it off. It turns off by itself in another app.
 */
object FancyText {
    class Style(val name: String, val map: (Int) -> String)

    private fun alpha(upper: Int, lower: Int, digits: Int = -1, except: Map<Char, Int> = emptyMap()): (Int) -> String = { cp ->
        val c = cp.toChar()
        when {
            cp < 0x10000 && except.containsKey(c) -> String(Character.toChars(except[c]!!))
            cp in 'A'.code..'Z'.code -> String(Character.toChars(upper + cp - 'A'.code))
            cp in 'a'.code..'z'.code -> String(Character.toChars(lower + cp - 'a'.code))
            digits > 0 && cp in '0'.code..'9'.code -> String(Character.toChars(digits + cp - '0'.code))
            else -> String(Character.toChars(cp))
        }
    }
    private fun combine(mark: Char): (Int) -> String = { cp ->
        val s = String(Character.toChars(cp))
        if (Character.isLetterOrDigit(cp)) s + mark else s
    }
    private fun table(upper: String?, lower: String): (Int) -> String = { cp ->
        when (cp) {
            in 'a'.code..'z'.code -> lower.codePointAtIndex(cp - 'a'.code)
            in 'A'.code..'Z'.code -> (upper ?: lower).codePointAtIndex(cp - 'A'.code)
            else -> String(Character.toChars(cp))
        }
    }
    private fun String.codePointAtIndex(i: Int): String {
        val off = offsetByCodePoints(0, i)
        return String(Character.toChars(codePointAt(off)))
    }

    val styles = listOf(
        Style("Bold", alpha(0x1D400, 0x1D41A, 0x1D7CE)),
        Style("Italic", alpha(0x1D434, 0x1D44E, except = mapOf('h' to 0x210E))),
        Style("Bold italic", alpha(0x1D468, 0x1D482, 0x1D7CE)),
        Style("Script", alpha(0x1D4D0, 0x1D4EA, 0x1D7CE)),
        Style("Gothic", alpha(0x1D56C, 0x1D586, 0x1D7CE)),
        Style("Outline", alpha(0x1D538, 0x1D552, 0x1D7D8, mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115,
            'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124))),
        Style("Sans bold", alpha(0x1D5D4, 0x1D5EE, 0x1D7EC)),
        Style("Typewriter", alpha(0x1D670, 0x1D68A, 0x1D7F6)),
        Style("Bubbles", alpha(0x24B6, 0x24D0, -1, mapOf('0' to 0x24EA, '1' to 0x2460, '2' to 0x2461, '3' to 0x2462,
            '4' to 0x2463, '5' to 0x2464, '6' to 0x2465, '7' to 0x2466, '8' to 0x2467, '9' to 0x2468))),
        Style("Squares", alpha(0x1F130, 0x1F130)),
        Style("Wide", alpha(0xFF21, 0xFF41, 0xFF10)),
        Style("Small caps", table(null, "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ")),
        Style("Strike", combine('̶')),
        Style("Underline", combine('̲')),
    )

    fun styled(style: Style, text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) { val cp = text.codePointAt(i); sb.append(style.map(cp)); i += Character.charCount(cp) }
        return sb.toString()
    }

    @Volatile var style: Style? = null; private set
    private var stylePkg: String? = null

    /** A new app: fancy text stays where it was turned on. */
    fun onStartInput(pkg: String?) { if (style != null && pkg != stylePkg) style = null }

    /**
     * Called for every key. Returns true when it typed the key itself (a letter or digit in the chosen style).
     * [resync] tells the keyboard where the cursor is now, so its own idea of the text stays right.
     */
    fun handle(ic: InputConnection?, codePoint: Int, isDelete: Boolean, finishComposing: () -> Unit, resync: (Int, Int) -> Unit): Boolean {
        val s = style ?: return false
        ic ?: return false
        if (isDelete) {
            // a letter with a line through/under it is two characters: delete both together
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
            if (before.length < 2 || (before[1] != '̶' && before[1] != '̲')) return false
            ic.deleteSurroundingText(2, 0)
            resyncFrom(ic, resync)
            return true
        }
        if (codePoint <= 0) return false
        val out = s.map(codePoint)
        if (out == String(Character.toChars(codePoint))) return false // spaces, punctuation: typed as usual
        finishComposing()
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(out, 1)
        ic.endBatchEdit()
        resyncFrom(ic, resync)
        return true
    }

    private fun resyncFrom(ic: InputConnection, resync: (Int, Int) -> Unit) {
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0) resync(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
    }

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun turn(context: Context, s: Style?) {
        val ime = ime(context)
        style = s
        stylePkg = ime?.currentInputEditorInfo?.packageName
        KeyboardMenu.hide()
        ime?.setNeutralSuggestionStrip()
        android.widget.Toast.makeText(context, if (s == null) "Normal text" else styled(s, s.name) + " on. Open Fancy text again to stop",
            android.widget.Toast.LENGTH_SHORT).show()
    }
    private const val SHOW_CHIP = false

    /** The chip in the top bar while a style is on: tap it to pick another, ✕ to stop. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        if (!SHOW_CHIP) return null // Jarrod: the chip covering the top bar was annoying; a short message instead
        val s = style ?: return null
        if (ime.currentInputEditorInfo?.packageName != stylePkg) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val colors = ime.mSettings.current.mColors
        binding.clipboardSuggestionText.text = styled(s, "Fancy") + "  " + s.name + " is on"
        binding.clipboardSuggestionText.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionText.setOnClickListener { show(ime) }
        binding.clipboardSuggestionClose.setOnClickListener { turn(ime, null) }
        return binding.root
    }

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
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(context, 8f), 0, 0) }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun bg(on: Boolean, r: Float) = GradientDrawable().apply {
            cornerRadius = dp(context, r).toFloat()
            setColor(if (on) ColorUtils.setAlphaComponent(accent, 110) else tile)
        }
        fun pill(s: String, on: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f))
            background = bg(on, 18f)
            setOnClickListener { onClick() }
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(context, 12f), 0, dp(context, 12f), 0) }
        top.addView(TextView(context).apply {
            this.text = context.getString(R.string.ok_tile_fancy_text)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (style != null) top.addView(pill("Normal text", true) { turn(context, null) })
        top.addView(pill("✕") { KeyboardMenu.hide() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)
        column.addView(TextView(context).apply {
            this.text = "Pick a style, then type as normal. Come back here and tap Normal text to stop."
            setTextColor(ColorUtils.setAlphaComponent(text, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(context, 14f), dp(context, 4f), dp(context, 14f), dp(context, 6f))
        })

        val grid = GridLayout(context).apply { columnCount = 3; setPadding(dp(context, 6f), 0, dp(context, 6f), dp(context, 8f)) }
        styles.forEach { s ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 4f), dp(context, 8f), dp(context, 4f), dp(context, 8f))
                background = bg(style === s, 14f)
                addView(TextView(context).apply {
                    this.text = styled(s, "Hello")
                    setTextColor(text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    this.text = s.name
                    setTextColor(ColorUtils.setAlphaComponent(text, 170))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                })
                setOnClickListener { turn(context, s) }
            }
            val m = dp(context, 4f)
            grid.addView(card, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; setMargins(m, m, m, m) })
        }
        column.addView(ScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        KeyboardMenu.showOverlay(root)
    }
}
