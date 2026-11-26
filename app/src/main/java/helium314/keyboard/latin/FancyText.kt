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
    /** [reverse]: each letter goes in front of the last one (upside down, mirror), so the word reads the right way round. */
    class Style(val name: String, val reverse: Boolean = false, val map: (Int) -> String)

    private fun cps(vararg c: Int) = String(c, 0, c.size)
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
    /** Adds marks over/under/through each letter and digit. */
    private fun combine(vararg marks: Char): (Int) -> String = { cp ->
        val s = String(Character.toChars(cp))
        if (Character.isLetterOrDigit(cp)) s + String(marks) else s
    }
    /** Puts something before and/or after each letter and digit, after first styling it with [inner]. */
    private fun wrap(before: String, after: String, inner: ((Int) -> String)? = null): (Int) -> String = { cp ->
        val s = inner?.invoke(cp) ?: String(Character.toChars(cp))
        if (cp < 0x10000 && Character.isLetterOrDigit(cp)) before + s + after else s
    }
    /** Letters from a 26-character lookalike table; [digits] optional; [extra] for punctuation. */
    private fun table(upper: String?, lower: String, digits: String? = null, extra: Map<Char, String> = emptyMap()): (Int) -> String = { cp ->
        when {
            cp in 'a'.code..'z'.code -> lower.codePointAtIndex(cp - 'a'.code)
            cp in 'A'.code..'Z'.code -> (upper ?: lower).codePointAtIndex(cp - 'A'.code)
            digits != null && cp in '0'.code..'9'.code -> digits.codePointAtIndex(cp - '0'.code)
            cp < 0x10000 && extra.containsKey(cp.toChar()) -> extra[cp.toChar()]!!
            else -> String(Character.toChars(cp))
        }
    }
    private fun String.codePointAtIndex(i: Int): String {
        val off = offsetByCodePoints(0, i)
        return String(Character.toChars(codePointAt(off)))
    }
    private val zalgoRandom = java.util.Random()
    private val glitch: (Int) -> String = { cp ->
        val s = StringBuilder(String(Character.toChars(cp)))
        if (Character.isLetterOrDigit(cp)) repeat(3 + zalgoRandom.nextInt(4)) { s.append((0x300 + zalgoRandom.nextInt(0x70)).toChar()) }
        s.toString()
    }

    private val boldDigits = 0x1D7CE
    private val upsideExtra = mapOf('?' to "¿", '!' to "¡", '.' to "˙", ',' to "'", '\'' to ",", '(' to ")", ')' to "(", '&' to "⅋", '_' to "‾")

    // Most popular first (order from the big fancy-text sites and what people actually pick).
    val styles = listOf(
        Style("Bold", map = alpha(0x1D400, 0x1D41A, boldDigits)),
        Style("Italic", map = alpha(0x1D434, 0x1D44E, except = mapOf('h' to 0x210E))),
        Style("Bold italic", map = alpha(0x1D468, 0x1D482, boldDigits)),
        Style("Script", map = alpha(0x1D4D0, 0x1D4EA, boldDigits)),
        Style("Cursive", map = alpha(0x1D49C, 0x1D4B6, except = mapOf('B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B,
            'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B, 'e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134))),
        Style("Gothic", map = alpha(0x1D56C, 0x1D586, boldDigits)),
        Style("Old English", map = alpha(0x1D504, 0x1D51E, except = mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128))),
        Style("Outline", map = alpha(0x1D538, 0x1D552, 0x1D7D8, mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115,
            'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124))),
        Style("Bubbles", map = alpha(0x24B6, 0x24D0, -1, mapOf('0' to 0x24EA, '1' to 0x2460, '2' to 0x2461, '3' to 0x2462,
            '4' to 0x2463, '5' to 0x2464, '6' to 0x2465, '7' to 0x2466, '8' to 0x2467, '9' to 0x2468))),
        Style("Dark bubbles", map = alpha(0x1F150, 0x1F150, -1, mapOf('0' to 0x24FF, '1' to 0x2776, '2' to 0x2777, '3' to 0x2778,
            '4' to 0x2779, '5' to 0x277A, '6' to 0x277B, '7' to 0x277C, '8' to 0x277D, '9' to 0x277E))),
        Style("Small caps", map = table(null, "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ")),
        Style("Tiny", map = table("ᴬᴮᶜᴰᴱᶠᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᵠᴿˢᵀᵁⱽᵂˣʸᶻ", "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖᑫʳˢᵗᵘᵛʷˣʸᶻ", "⁰¹²³⁴⁵⁶⁷⁸⁹")),
        Style("Upside down", reverse = true, map = table("∀ꓭƆꓷƎℲ⅁HIſꓘ˥WNOԀΌꓤSꓕՈΛMX⅄Z", "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz",
            "0ƖᄅƐㄣϛ9ㄥ86", upsideExtra)),
        Style("Wide", map = alpha(0xFF21, 0xFF41, 0xFF10)),
        Style("Squares", map = alpha(0x1F130, 0x1F130)),
        Style("Dark squares", map = alpha(0x1F170, 0x1F170)),
        Style("Strike", map = combine('̶')),
        Style("Underline", map = combine('̲')),
        Style("Typewriter", map = alpha(0x1D670, 0x1D68A, 0x1D7F6)),
        Style("Glitch", map = glitch),
        Style("Sans", map = alpha(0x1D5A0, 0x1D5BA, 0x1D7E2)),
        Style("Sans bold", map = alpha(0x1D5D4, 0x1D5EE, 0x1D7EC)),
        Style("Sans italic", map = alpha(0x1D608, 0x1D622)),
        Style("Sans bold italic", map = alpha(0x1D63C, 0x1D656, 0x1D7EC)),
        Style("Mirror", reverse = true, map = table("AᙠƆᗡƎᖷᎮHIႱᐴ⅃MИOꟼỌЯƧTUVWXYƸ", "ɒdɔbɘʇϱʜiįʞlmnoqpɿƨɈυvwxγz")),
        Style("Asian style", map = table(null, "卂乃匚ᗪ乇千Ꮆ卄丨ﾌҜㄥ爪几ㄖ卩Ɋ尺丂ㄒㄩᐯ山乂ㄚ乙")),
        Style("Curly", map = table(null, "αႦƈԃҽϝɠԋιʝƙʅɱɳσρϙɾʂƚυʋɯxყȥ")),
        Style("Faux Russian", map = table("ДБCDΣFGНIJКLMИФPQЯSГЦVЩЖЧZ", "аъсↁэfБЂіјкlмиорqѓѕтцvшхЎz")),
        Style("Ancient", map = table(null, "ልጌርዕቿቻኗዘጎጋጕረጠክዐየዒዪነፕሁሀሠሸሃጊ")),
        Style("Money", map = table(null, "₳฿₵ĐɆ₣₲ⱧłJ₭Ⱡ₥₦Ø₱QⱤ₴₮ɄV₩ӾɎⱫ")),
        Style("Rock dots", map = table("ÄḄĊḊËḞĠḦÏJḲḶṀṄÖṖQṚṠṪÜṾẄẌŸŻ", "äḅċḋëḟġḧïjḳḷṁṅöṗqṛṡẗüṿẅẍÿż")),
        Style("Hearts", map = wrap("", "♥")),
        Style("Stars", map = wrap("", "★")),
        Style("Sparkles", map = wrap("", "✧")),
        Style("Brackets", map = wrap("【", "】")),
        Style("Corners", map = wrap("『", "』")),
        Style("Flags", map = alpha(0x1F1E6, 0x1F1E6).let { f -> { cp: Int -> val o = f(cp); if (Character.isLetter(cp) && cp < 0x80) o + "​" else o } }),
        Style("Brackets small", map = alpha(0x249C, 0x249C, -1, mapOf('1' to 0x2474, '2' to 0x2475, '3' to 0x2476, '4' to 0x2477,
            '5' to 0x2478, '6' to 0x2479, '7' to 0x247A, '8' to 0x247B, '9' to 0x247C))),
        Style("Double underline", map = combine('̳')),
        Style("Overline", map = combine('̅')),
        Style("Framed", map = combine('̲', '̅')),
        Style("Slash", map = combine('̸')),
        Style("Wavy strike", map = combine('̴')),
        Style("Crossed", map = combine('̽')),
        Style("Boxed", map = combine('⃞')),
        Style("Wavy underline", map = combine('̰')),
        Style("Dotted underline", map = combine('̤')),
        Style("Sparkle below", map = combine('͙')),
        Style("Arrows below", map = combine('͎')),
        Style("Spaced", map = wrap("", " ")),
    )

    fun styled(style: Style, text: String): String {
        val parts = ArrayList<String>()
        var i = 0
        while (i < text.length) { val cp = text.codePointAt(i); parts.add(style.map(cp)); i += Character.charCount(cp) }
        if (style.reverse) parts.reverse()
        return parts.joinToString("")
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
        if (!Plugins.isOn("app_fancy_text")) { style = null; return false } // switched off in Plugins (test 218)
        ic ?: return false
        if (isDelete) {
            if (s.reverse) {
                // upside down / mirror: the last letter typed is just after the cursor
                val after = ic.getTextAfterCursor(2, 0)?.toString()
                if (after.isNullOrEmpty()) return false
                ic.deleteSurroundingTextInCodePoints(0, 1)
                resyncFrom(ic, resync)
                return true
            }
            val n = styledLengthBeforeCursor(s, ic.getTextBeforeCursor(16, 0)?.toString() ?: return false)
            if (n <= 0) return false
            ic.deleteSurroundingText(n, 0)
            resyncFrom(ic, resync)
            return true
        }
        if (codePoint <= 0) return false
        val out = s.map(codePoint)
        val plain = String(Character.toChars(codePoint))
        if (out == plain && !(s.reverse && codePoint >= 0x20)) return false // spaces, punctuation: typed as usual
        finishComposing()
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(out, if (s.reverse) 0 else 1)
        ic.endBatchEdit()
        resyncFrom(ic, resync)
        return true
    }

    /**
     * How many chars one styled letter takes at the end of [before], when it's more than a single character
     * (a letter with marks through/under it, a letter in brackets, a flag letter…). 0: let the normal delete do it.
     */
    private fun styledLengthBeforeCursor(s: Style, before: String): Int {
        if (before.isEmpty()) return 0
        // letter + combining marks (strike, underline, glitch…): delete the marks and the letter together
        var i = before.length
        while (i > 0 && isMark(before.codePointBefore(i))) i -= Character.charCount(before.codePointBefore(i))
        if (i < before.length && i > 0) return before.length - (i - Character.charCount(before.codePointBefore(i)))
        // decorations around the letter (【a】, a♥, flags…): match what this style would type
        var best = 0
        for (c in ('a'..'z') + ('A'..'Z') + ('0'..'9')) {
            val o = s.map(c.code)
            if (o.length > best && o.codePointCount(0, o.length) > 1 && before.endsWith(o)) best = o.length
        }
        return best
    }
    private fun isMark(cp: Int) = Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() }

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
                    ellipsize = android.text.TextUtils.TruncateAt.END
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
