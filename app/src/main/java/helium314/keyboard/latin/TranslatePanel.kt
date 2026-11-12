// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.ModelRetention
import io.github.yinvoker.foxlet.Threading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Offline translation of what you wrote, using Mozilla's translation models through Foxlet
 * (Bergamot engine). A language is downloaded once the first time it is used; after that it works
 * without internet and the text never leaves the phone.
 */
object TranslatePanel {
    private const val TAG = "TranslatePanel"
    private const val PREF_RECENT = "open_keyboard_translate_recent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientLock = Mutex()
    private var client: Foxlet? = null
    private val main = Handler(Looper.getMainLooper())

    /** The text before the last translation, so it can be undone. */
    private var undoText: String? = null
    private var undoTranslated: String? = null

    private suspend fun foxlet(ctx: Context): Foxlet = clientLock.withLock {
        client ?: Foxlet.create(ctx.applicationContext) {
            translation {
                threading = Threading.Fixed(2)
                retention = ModelRetention.Idle(60.seconds)
            }
        }.also { client = it }
    }

    // Mozilla model pairs shipped in Foxlet's catalog, most used first
    private val order = listOf(
        "es", "fr", "de", "it", "pt", "zh-Hans", "zh-Hant", "ja", "ko", "vi", "th", "id", "hi", "ar", "ru", "uk", "nl",
        "pl", "tr", "el", "sv", "da", "nb", "fi", "cs", "sk", "hu", "ro", "bg", "hr", "sr", "bs", "sl", "lt", "lv", "et",
        "ca", "gl", "eu", "ms", "fa", "he", "ur", "bn", "ta", "te", "kn", "ml", "mr", "gu", "is", "af", "az", "sq",
    )
    private val fromEnglish = order - setOf("sq")
    private val intoEnglish = order - setOf("az")

    private fun name(tag: String) = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)
        .replace("Norwegian Bokmål", "Norwegian")

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
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
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        }
        val scroll = ScrollView(context).apply { addView(column) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun label(s: String) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(context, 8f), 0, dp(context, 4f))
        }
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
        fun row(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val mainColumn = column
        fun flow(items: List<TextView>, column: LinearLayout = mainColumn) {
            // simple wrapping: rows of 3
            items.chunked(3).forEach { chunk ->
                val r = row()
                chunk.forEach { v ->
                    r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(context, 3f), dp(context, 3f), dp(context, 3f), dp(context, 3f))
                    })
                }
                repeat(3 - chunk.size) { r.addView(TextView(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                column.addView(r)
            }
        }

        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            this.text = "Translate what you wrote"
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (undoText != null) top.addView(pill("Undo") { KeyboardMenu.hide(); undo(context) })
        top.addView(pill("✕") { KeyboardMenu.hide() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)

        val recent = context.prefs().getString(PREF_RECENT, "")!!.split(",").filter { it.isNotBlank() }
        fun ordered(list: List<String>) = recent.filter { it in list } + list.filter { it !in recent }.sortedBy { name(it) }

        // A-Z strip to find a language quickly
        val langs = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val letters = (fromEnglish + intoEnglish).map { name(it).first().uppercaseChar() }.toSortedSet()
        val strip = row()
        var chosen: Char? = null
        val letterViews = mutableListOf<Pair<Char?, TextView>>()
        fun render() {
            langs.removeAllViews()
            fun keep(tag: String) = chosen == null || name(tag).first().uppercaseChar() == chosen
            langs.addView(label("From English into"))
            flow(ordered(fromEnglish).filter(::keep).map { tag -> pill(name(tag), tag in recent) { KeyboardMenu.hide(); translate(context, "en", tag) } }, langs)
            langs.addView(label("Into English from"))
            flow(ordered(intoEnglish).filter(::keep).map { tag -> pill(name(tag), tag in recent) { KeyboardMenu.hide(); translate(context, tag, "en") } }, langs)
            letterViews.forEach { (c, v) ->
                (v.background as? GradientDrawable)?.setColor(if (c == chosen) ColorUtils.setAlphaComponent(accent, 110) else tile)
            }
        }
        (listOf<Char?>(null) + letters).forEach { c ->
            val v = pill(c?.toString() ?: "All") { chosen = c; render() }.apply {
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
            }
            letterViews += c to v
            strip.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(context, 2f), dp(context, 4f), dp(context, 2f), dp(context, 4f))
            })
        }
        column.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        column.addView(langs)
        render()
        column.addView(label("The first time you use a language it downloads once (about 20–50 MB). After that it works offline, and your text never leaves the phone."))

        KeyboardMenu.showOverlay(root)
    }

    private fun remember(ctx: Context, tag: String) {
        val recent = ctx.prefs().getString(PREF_RECENT, "")!!.split(",").filter { it.isNotBlank() && it != tag }
        ctx.prefs().edit().putString(PREF_RECENT, (listOf(tag) + recent).take(4).joinToString(",")).apply()
    }

    private fun toast(ctx: Context, s: String) = main.post { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }

    private fun translate(context: Context, from: String, to: String) {
        val ime = ime(context) ?: return
        val ic = ime.currentInputConnection ?: return
        ic.finishComposingText()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before = ic.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(20000, 0)?.toString().orEmpty()
        val source = selected.ifEmpty { before + after }
        if (source.isBlank()) { toast(context, "Write something first, then translate it"); return }
        remember(context, if (from == "en") to else from)
        toast(context, "Translating…")
        scope.launch {
            try {
                val fox = foxlet(context)
                var announced = false
                val model = fox.models.prepare(LanguagePair(from, to)) { p ->
                    if (!announced && p.stage == io.github.yinvoker.foxlet.DownloadStage.Downloading) {
                        announced = true
                        toast(context, "Downloading ${name(if (from == "en") to else from)} (once only)…")
                    }
                }
                val result = fox.translator.translate(source, model)
                withContext(Dispatchers.Main) { replace(ime, selected.isNotEmpty(), before, after, source, result) }
            } catch (e: io.github.yinvoker.foxlet.UnsupportedLanguagePairException) {
                toast(context, "That language isn't available yet")
            } catch (e: io.github.yinvoker.foxlet.NetworkException) {
                toast(context, "Needs internet once to download this language")
            } catch (e: Throwable) {
                Log.w(TAG, "translation failed", e)
                toast(context, if (e is UnsatisfiedLinkError) "Translation needs a 64-bit phone" else "Translation didn't work: ${e.message}")
            }
        }
    }

    private fun replace(ime: LatinIME, selectionOnly: Boolean, before: String, after: String, source: String, result: String) {
        val ic = ime.currentInputConnection ?: return
        // only replace if the text hasn't changed meanwhile
        if (!selectionOnly) {
            val nowBefore = ic.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
            val nowAfter = ic.getTextAfterCursor(20000, 0)?.toString().orEmpty()
            if (nowBefore != before || nowAfter != after) { Toast.makeText(ime, "Text changed, not replaced", Toast.LENGTH_SHORT).show(); return }
        }
        ic.beginBatchEdit()
        if (selectionOnly) ic.commitText(result, 1)
        else { ic.deleteSurroundingText(before.length, after.length); ic.commitText(result, 1) }
        ic.endBatchEdit()
        undoText = source
        undoTranslated = result
        resync(ime)
    }

    private fun undo(context: Context) {
        val ime = ime(context) ?: return
        val ic = ime.currentInputConnection ?: return
        val original = undoText ?: return
        val translated = undoTranslated ?: return
        val before = ic.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
        if (!before.endsWith(translated)) { Toast.makeText(ime, "Can't undo now", Toast.LENGTH_SHORT).show(); return }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(translated.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        undoText = null; undoTranslated = null
        resync(ime)
    }

    private fun resync(ime: LatinIME) {
        val ex = ime.currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        if (ex.selectionStart >= 0) ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
    }
}
