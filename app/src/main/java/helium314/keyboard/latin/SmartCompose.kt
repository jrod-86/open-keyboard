// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart compose: when you pause after a word, the on-phone writing model guesses the next few
 * words and shows them in the top bar. Tap to add them. Nothing leaves the phone, and it stays out
 * of passwords, numbers, addresses and anything the app marks as private.
 */
object SmartCompose {
    private const val TAG = "SmartCompose"
    private const val PAUSE_MS = 650L
    private const val SYSTEM = "You are the autocomplete in a phone keyboard. The user is in the middle of typing a " +
        "message. Reply with only the next few words (2 to 6) that would most likely come next. Do not repeat " +
        "what they wrote. No quotes, no explanation."

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var pending: Runnable? = null
    @Volatile private var forText: String? = null
    @Volatile private var suggestion: String? = null

    /** Called whenever the text or cursor changes. */
    fun onTextChanged(ime: LatinIME) {
        pending?.let { main.removeCallbacks(it) }
        job?.cancel() // typing again: stop guessing for the old text
        val r = Runnable { start(ime) }
        pending = r
        main.postDelayed(r, PAUSE_MS)
    }

    private fun allowed(ime: LatinIME): Boolean {
        if (!Plugins.isOn(ime, "compose") || !WritingTools.modelReady(ime)) return false
        val sv = Settings.getValues()
        if (sv.mIncognitoModeEnabled || !sv.mInputAttributes.mShouldShowSuggestions) return false
        val type = ime.currentInputEditorInfo?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> false
            else -> true
        }
    }

    private fun textBefore(ime: LatinIME): String? {
        val ic = ime.currentInputConnection ?: return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        return ic.getTextBeforeCursor(400, 0)?.toString()
    }

    private fun start(ime: LatinIME) {
        if (!allowed(ime)) return
        val before = textBefore(ime) ?: return
        if (before == forText) return // already guessed (or guessing) for this text
        forText = null; suggestion = null
        // only at the end of what's written, right after a finished word
        val after = ime.currentInputConnection?.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (after.isNotEmpty() && after[0] != '\n') return
        if (!before.endsWith(" ") || before.endsWith("  ")) return
        val line = before.substringAfterLast('\n')
        if (line.trim().split(Regex("\\s+")).size < 2) return
        if (SlashCommands.inCommand(before)) return
        forText = before
        job = scope.launch {
            try {
                val out = WritingTools.withEngine(ime) { engine ->
                    engine.startFresh()
                    engine.setSystemPrompt(SYSTEM)
                    val sb = StringBuilder()
                    engine.sendUserPrompt(before.takeLast(300).trimEnd(), 16).collect { sb.append(it) }
                    sb.toString()
                } ?: return@launch
                val guess = tidy(before, out) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (forText == before && textBefore(ime) == before) {
                        suggestion = guess
                        ime.setNeutralSuggestionStrip()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "compose failed", e)
            }
        }
    }

    /** Turns the model's reply into a few clean words, or null if it's not usable. */
    fun tidy(before: String, out: String): String? {
        var t = out.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        t = t.trim('"', '“', '”', '\'', '`')
        val typed = before.trim()
        if (t.startsWith(typed, ignoreCase = true)) t = t.substring(typed.length).trim()
        // the model sometimes repeats the last word or two
        val lastWords = typed.split(Regex("\\s+")).takeLast(2)
        for (n in lastWords.size downTo 1) {
            val tail = lastWords.takeLast(n).joinToString(" ")
            if (t.startsWith("$tail ", ignoreCase = true)) { t = t.substring(tail.length).trim(); break }
        }
        t = t.removePrefix("...").removePrefix("…").trim()
        // stop at the end of the sentence, and keep it short
        Regex("[.!?]").find(t)?.let { t = t.substring(0, it.range.first + 1) }
        t = t.split(Regex("\\s+")).take(6).joinToString(" ")
        if (t.isEmpty() || t.length > 60 || !t.first().isLetterOrDigit()) return null
        // keep lower case mid-sentence
        val endsSentence = typed.lastOrNull()?.let { it in ".!?" } ?: true
        if (!endsSentence && t.first().isUpperCase() && !t.startsWith("I ") && t != "I" && t.getOrNull(1)?.isLowerCase() == true)
            t = t.first().lowercase() + t.substring(1)
        return t
    }

    /** The chip for the top bar, if there's a guess for exactly what's typed now. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        val guess = suggestion ?: return null
        val before = forText ?: return null
        if (textBefore(ime) != before) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = "→  $guess"
        val colors = ime.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionClose.visibility = View.GONE
        textView.setOnClickListener { accept(ime, guess) }
        return binding.root
    }

    private fun accept(ime: LatinIME, guess: String) {
        val ic = ime.currentInputConnection ?: return
        suggestion = null
        ime.mInputLogic.finishInput()
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(if (guess.last() in ".!?") "$guess " else "$guess ", 1)
        ic.endBatchEdit()
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0)
            ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
        KeyboardSwitcher.getInstance().updateShiftState(ime.currentAutoCapsState, ime.currentRecapitalizeState)
        ime.setNeutralSuggestionStrip()
    }
}
