// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.voice.VoicePrefs
import helium314.keyboard.voice.VoiceSpelling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI writer: writes a new message from a request ("write a happy birthday message to my wife"),
 * on the phone with the Writing help model. Three ways in:
 * - Talk box ([AiWriterActivity]): say or type the request, check the result, tap Insert.
 * - Writer mode: while it's on, the text in the message box is the request and Enter swaps it for the message.
 * - Just ask: say "write a … message" while voice typing, or type /write and the request.
 */
object AiWriter {
    private const val TAG = "AiWriter"
    enum class Mode { BOX, WRITER }
    private const val PREF_MODE = "open_keyboard_ai_writer_mode"
    /** Put on the talk box's text field, so the keyboard starts the mic when it opens. */
    const val MIC_OPTION = "io.github.jrod86.openkeyboard.aiwriter.mic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    /** Writer mode is switched on (until switched off again). */
    var writerOn = false
        private set
    @Volatile var busy = false
        private set
    private var job: kotlinx.coroutines.Job? = null

    /** Stops writing (✕ on the chip, or Writing help switched off) and takes the "Writing your message…" chip away. */
    fun cancel(ime: LatinIME) {
        job?.cancel(); job = null
        busy = false
        ime.setNeutralSuggestionStrip()
    }
    private var lastRequest: String? = null
    private var lastResult: String? = null
    private var autoMic = false

    /** Colour the ✨ button and the keyboard's top line take while writer mode is on. */
    const val ON_COLOR = 0xFFB388FF.toInt()
    const val TILE_ID = "Writing help" // its tile lights up while writer mode is on
    private const val GLOW_TAG = "ai-writer-glow"

    /**
     * Shows that writer mode is on: the ✨ button on the top bar lights up, and a glowing line runs
     * along the top of the keyboard (for people who don't keep the button on the bar).
     */
    fun showIndicators() {
        val wrapper = helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().wrapperView ?: return
        val on = writerOn
        (wrapper.rootView?.findViewWithTag<View>("item:$TILE_ID") as? android.widget.ImageView)?.let { b ->
            if (on) b.setColorFilter(ON_COLOR)
            else { b.clearColorFilter(); helium314.keyboard.latin.settings.Settings.getValues().mColors.setColor(b, ColorType.TOOL_BAR_KEY) }
        }
        val glow = wrapper.findViewWithTag<View>(GLOW_TAG)
        if (!on) { glow?.let { wrapper.removeView(it) }; return }
        if (glow != null) { glow.bringToFront(); return }
        val ctx = wrapper.context
        val h = (3 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val line = View(ctx).apply {
            tag = GLOW_TAG
            isClickable = false
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x007C4DFF, ON_COLOR, 0xFFFF80AB.toInt(), ON_COLOR, 0x007C4DFF))
        }
        wrapper.addView(line, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, android.view.Gravity.TOP))
    }

    fun mode(ctx: Context): Mode =
        if (ctx.getSharedPreferences("open_keyboard_llm", Context.MODE_PRIVATE).getString(PREF_MODE, "box") == "writer") Mode.WRITER else Mode.BOX
    fun setMode(ctx: Context, m: Mode) {
        ctx.getSharedPreferences("open_keyboard_llm", Context.MODE_PRIVATE).edit().putString(PREF_MODE, if (m == Mode.WRITER) "writer" else "box").apply()
        if (m == Mode.BOX) { writerOn = false; main.post { showIndicators() } }
    }

    fun buttonLabel(ctx: Context) = when {
        mode(ctx) == Mode.BOX -> "Write for me"
        writerOn -> "Turn writer mode off"
        else -> "Turn writer mode on"
    }

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }
    private fun toast(ctx: Context, s: String, long: Boolean = false) =
        main.post { Toast.makeText(ctx, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }

    /** The ✨ button: opens the talk box, or switches writer mode on or off. */
    fun start(context: Context) {
        if (!WritingTools.anyDownloaded(context)) {
            toast(context, "Download a writing model first", true)
            WritingTools.show(context)
            return
        }
        KeyboardMenu.hide()
        when (mode(context)) {
            Mode.BOX -> {
                autoMic = true
                runCatching {
                    context.startActivity(Intent(context, AiWriterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { autoMic = false; toast(context, "The talk box couldn't open") }
            }
            Mode.WRITER -> {
                writerOn = !writerOn
                lastResult = null
                toast(context, if (writerOn) "✨ Writer mode on: type or say what you want, then press Enter" else "Writer mode off", writerOn)
                ime(context)?.setNeutralSuggestionStrip()
                showIndicators()
            }
        }
    }

    /** Called when the keyboard opens on a field: true once, for the talk box, to start the mic. */
    fun takeAutoMic(editorInfo: EditorInfo?): Boolean {
        if (!autoMic || editorInfo?.privateImeOptions != MIC_OPTION) return false
        autoMic = false
        return true
    }

    private fun system(ctx: Context): String {
        val region = VoicePrefs.region(ctx)
        return "You are a writing assistant built into a phone keyboard. The user asks you to write something " +
            "(a message, a text, a reply, an email, a post, a card) and you write it for them, in their voice, ready to send. " +
            "Reply with only the text itself: no title, no quotes around it, no notes, no choices, no placeholders like [Name]. " +
            "Keep it natural and warm, and the right length for a phone message unless they ask for something longer. " +
            "Write in the language of the request" + (if (region.britishOur) ", using ${region.title} spelling" else "") + "."
    }

    /** Small changes the talk box offers after a first version. */
    enum class Tweak(val label: String, val instruction: String) {
        AGAIN("Try again", "Write a different version of it, with new wording."),
        SHORTER("Shorter", "Make it shorter."),
        LONGER("Longer", "Make it longer, with a bit more detail and feeling."),
        WARMER("Warmer", "Make it warmer and more loving."),
        FUNNIER("Funnier", "Make it funnier and more playful."),
    }

    private fun prompt(request: String, previous: String?, tweak: Tweak?): String =
        if (previous == null || tweak == null) "Write this: $request"
        else "The request was: $request\n\nYou wrote:\n$previous\n\n${tweak.instruction} Reply with only the new text."

    private fun tidy(ctx: Context, out: String): String {
        var t = out.trim()
        // drop a lead-in line like "Here's a birthday message:"
        val firstLine = t.substringBefore('\n')
        if (t.contains('\n') && firstLine.trimEnd().endsWith(':') && firstLine.length < 80) t = t.substringAfter('\n').trim()
        if (t.length > 1 && (t.first() == '"' && t.last() == '"' || t.first() == '“' && t.last() == '”')) t = t.substring(1, t.length - 1).trim()
        return VoiceSpelling.apply(t, VoicePrefs.region(ctx))
    }

    /** Writes the text for [request]; [onPiece] gets the text so far while it is being written. */
    suspend fun write(ctx: Context, request: String, previous: String? = null, tweak: Tweak? = null, onPiece: (String) -> Unit = {}): String? {
        val out = WritingTools.generate(ctx, system(ctx), prompt(request, previous, tweak), 400, onPiece) ?: return null
        return tidy(ctx, out).takeIf { it.isNotBlank() }
    }

    /** Runs [request] in the background and hands the text to [apply] on the main thread. */
    private fun launch(ime: LatinIME, request: String, previous: String? = null, tweak: Tweak? = null, apply: (String) -> Unit) {
        if (busy) { toast(ime, "Still writing, one moment"); return }
        if (!WritingTools.anyDownloaded(ime)) { toast(ime, "Download a writing model in Writing help first", true); return }
        busy = true
        ime.setNeutralSuggestionStrip()
        toast(ime, "✨ Writing…")
        job = scope.launch {
            // Open Keyboard (test 222): never more than 3 minutes, so the chip can't hang around forever
            val out = try { kotlinx.coroutines.withTimeoutOrNull(180_000) { write(ime, request, previous, tweak) } } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "AI writer failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                job = null
                busy = false
                if (out == null) toast(ime, "Couldn't write that, please try again")
                else { lastRequest = request; apply(out) }
                ime.setNeutralSuggestionStrip()
            }
        }
    }

    private fun resync(ime: LatinIME) {
        val ex = ime.currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        if (ex.selectionStart >= 0) ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
    }

    // ---------------- writer mode ----------------

    private fun fieldText(ime: LatinIME): Pair<String, String>? {
        val ic = ime.currentInputConnection ?: return null
        ic.finishComposingText()
        return (ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()) to (ic.getTextAfterCursor(8000, 0)?.toString().orEmpty())
    }

    private fun writerActive(ime: LatinIME) = writerOn && Plugins.isOn(ime, "writing") &&
        ime.currentInputEditorInfo?.packageName != ime.packageName

    /** Enter in writer mode: the request in the box becomes the message. False = Enter works as usual (e.g. send). */
    fun onEnter(ime: LatinIME): Boolean {
        if (!writerActive(ime)) return false
        if (busy) return true
        ime.mInputLogic.finishInput()
        val (before, after) = fieldText(ime) ?: return false
        val text = before + after
        if (text.isBlank()) return false
        if (text.trim() == lastResult?.trim()) return false // already written: Enter sends it
        rewriteField(ime, before, after, text.trim(), null, null)
        return true
    }

    private fun rewriteField(ime: LatinIME, before: String, after: String, request: String, previous: String?, tweak: Tweak?) {
        launch(ime, request, previous, tweak) { result ->
            val ic = ime.currentInputConnection ?: return@launch
            val (nowBefore, nowAfter) = fieldText(ime) ?: return@launch
            if (nowBefore != before || nowAfter != after) { toast(ime, "The text changed, so it wasn't replaced"); return@launch }
            ic.beginBatchEdit()
            ic.deleteSurroundingText(before.length, after.length)
            ic.commitText(result, 1)
            ic.endBatchEdit()
            lastResult = result
            WritingTools.rememberForUndo(before + after, result)
            resync(ime)
        }
    }

    /** The writer mode / busy chip for the suggestion bar. Only while there's text, so the top bar stays otherwise. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        val text: String
        var onTap: (() -> Unit)? = null
        // Writing help switched off while it was writing: stop, and no chip
        if (busy && !Plugins.isOn(ime, "writing")) { cancel(ime); return null }
        if (busy) text = "✨  Writing your message…  (✕ to stop)"
        else {
            if (!writerActive(ime)) return null
            val before = ime.mInputLogic.mConnection.getTextBeforeCursor(200, 0)?.toString().orEmpty()
            if (before.isBlank()) return null
            val result = lastResult
            if (result != null && result.trim().endsWith(before.trim().takeLast(60))) {
                text = "✨  Enter sends it  ·  tap here to try again"
                onTap = {
                    val (b, a) = fieldText(ime) ?: ("" to "")
                    if ((b + a).trim() == result.trim()) rewriteField(ime, b, a, lastRequest ?: result, result, Tweak.AGAIN)
                }
            } else {
                text = "✨  Writer mode: press Enter to write it"
                onTap = { onEnter(ime) }
            }
        }
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val colors = ime.mSettings.current.mColors
        binding.clipboardSuggestionText.text = text
        binding.clipboardSuggestionText.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        onTap?.let { tap -> binding.clipboardSuggestionText.setOnClickListener { tap() } }
        if (busy) binding.clipboardSuggestionClose.setOnClickListener { cancel(ime); toast(ime, "Stopped writing") }
        else binding.clipboardSuggestionClose.setOnClickListener {
            writerOn = false; lastResult = null
            toast(ime, "Writer mode off")
            ime.setNeutralSuggestionStrip()
            showIndicators()
        }
        return binding.root
    }

    // ---------------- just ask ----------------

    private val things = "message|messages|text|note|email|e-mail|reply|response|poem|post|caption|card|letter|speech|story|toast|wish|wishes|" +
        "apology|invite|invitation|bio|joke|song|tweet|review|thank you|thank-you|greeting|quote|description|paragraph|list|limerick|rhyme"
    private val voiceAsk = Regex("^(?:hey keyboard[,.]?\\s+|keyboard[,.]?\\s+)?(?:can you |could you |please )?(?:write|draft|compose)\\s+(?:me\\s+|us\\s+)?" +
        "(?:a|an|some|the)\\b.*\\b(?:$things)\\b.*", RegexOption.IGNORE_CASE)

    /**
     * A sentence said while voice typing. If it asks for something to be written
     * ("write a happy birthday message to my wife"), the AI writes it in and this returns true.
     */
    fun fromVoice(service: InputMethodService, sentence: String): Boolean {
        val ime = service as? LatinIME ?: return false
        if (!Plugins.isOn(ime, "writing") || !helium314.keyboard.voice.VoiceCommands.isOn("vc_write") || !WritingTools.anyDownloaded(ime)) return false
        // the talk box and writer mode take the words as they are
        if (ime.currentInputEditorInfo?.packageName == ime.packageName || writerActive(ime)) return false
        val request = sentence.trim()
        if (!voiceAsk.matches(request)) return false
        Log.i(TAG, "voice request for the AI writer")
        launch(ime, request) { result ->
            val ic = ime.currentInputConnection ?: return@launch
            val before = ic.getTextBeforeCursor(1, 0)
            val lead = if (!before.isNullOrEmpty() && !before[0].isWhitespace()) " " else ""
            ic.commitText(lead + result, 1)
            WritingTools.rememberForUndo("", lead + result)
            resync(ime)
        }
        return true
    }

    /** "/write …": the command is swapped for the written text. */
    fun fromSlash(ime: LatinIME, command: String, request: String) {
        launch(ime, request) { result ->
            val ic = ime.currentInputConnection ?: return@launch
            ime.mInputLogic.finishInput()
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(command.length, 0)?.toString()
            if (before != command) { toast(ime, "The text changed, so it wasn't replaced"); return@launch }
            ic.beginBatchEdit()
            ic.deleteSurroundingText(command.length, 0)
            ic.commitText(result, 1)
            ic.endBatchEdit()
            WritingTools.rememberForUndo(command, result)
            resync(ime)
        }
    }
}
