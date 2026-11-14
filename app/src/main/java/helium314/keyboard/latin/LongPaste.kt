// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.Log

/**
 * Pasting a lot of text (pages from a document). The keyboard has no limit of its own, but many
 * chat apps cut a message off at a few thousand characters. When that happens we learn that app's
 * limit, paste a first part that ends at a sentence or line break, and offer "Paste part 2 of N"
 * in the top bar for after you send.
 */
object LongPaste {
    private const val TAG = "LongPaste"
    /** Text longer than this is checked; shorter text is pasted as usual. */
    const val CHECK_FROM = 1500
    /** Big text goes to the app in pieces of this size, so the system doesn't refuse the transfer. */
    private const val PIECE = 8000

    private var parts: List<String> = emptyList()
    private var next = 0
    private var pkg: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("open_keyboard_long_paste", Context.MODE_PRIVATE)
    private fun knownLimit(ctx: Context, p: String?) = if (p == null) 0 else prefs(ctx).getInt(p, 0)

    /** Returns true when it handled the paste. */
    fun paste(ime: LatinIME, text: String): Boolean {
        if (text.length < CHECK_FROM) return false
        val ic = ime.currentInputConnection ?: return false
        val app = ime.currentInputEditorInfo?.packageName
        clear()
        ime.mInputLogic.finishInput()
        val limit = knownLimit(ime, app)
        if (limit in 1 until text.length) {
            startParts(app, split(text, limit))
            commit(ime, parts[0]); next = 1
            ime.setNeutralSuggestionStrip()
            return true
        }
        val before = fieldLength(ime)
        commit(ime, text)
        val after = fieldLength(ime)
        if (before >= 0 && after >= 0) {
            val accepted = after - before
            if (accepted in 1 until text.length) {
                // the app cut it off: remember its limit, and redo it as tidy parts
                Log.i(TAG, "app $app took $accepted of ${text.length} characters")
                if (app != null) prefs(ime).edit().putInt(app, accepted).apply()
                ic.beginBatchEdit()
                ic.deleteSurroundingText(accepted, 0)
                ic.endBatchEdit()
                startParts(app, split(text, accepted))
                commit(ime, parts[0]); next = 1
            }
        }
        ime.setNeutralSuggestionStrip()
        return true
    }

    private fun startParts(app: String?, list: List<String>) { parts = list; next = 0; pkg = app }

    fun clear() { parts = emptyList(); next = 0; pkg = null }

    private fun fieldLength(ime: LatinIME): Int {
        val ex = ime.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        return ex.text?.length ?: -1
    }

    private fun commit(ime: LatinIME, text: String) {
        val ic = ime.currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + PIECE, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            ic.commitText(text.substring(i, end), 1)
            i = end
        }
        ic.endBatchEdit()
        val ex = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0)
            ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
        KeyboardSwitcher.getInstance().updateShiftState(ime.currentAutoCapsState, ime.currentRecapitalizeState)
    }

    /** Cuts text into parts of at most [size], ending at a paragraph, line, sentence or word when possible. */
    fun split(text: String, size: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > size) {
            val window = rest.substring(0, size)
            val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'),
                    maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? ")),
                    window.lastIndexOf(' '))
                .firstOrNull { it > size / 2 }?.let { it + 1 } ?: size
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** "Paste part 2 of 5" in the top bar while parts are left, in the app they belong to. */
    fun chip(ime: LatinIME, parent: ViewGroup): View? {
        if (next <= 0 || next >= parts.size) return null
        if (ime.currentInputEditorInfo?.packageName != pkg) return null
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val colors = ime.mSettings.current.mColors
        binding.clipboardSuggestionText.text = "⧉  Paste part ${next + 1} of ${parts.size}"
        binding.clipboardSuggestionText.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.clipboardSuggestionText.setOnClickListener {
            val part = parts.getOrNull(next) ?: return@setOnClickListener
            next++
            commit(ime, part)
            if (next >= parts.size) clear()
            ime.setNeutralSuggestionStrip()
        }
        binding.clipboardSuggestionClose.setOnClickListener { clear(); ime.setNeutralSuggestionStrip() }
        return binding.root
    }
}
