// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.utils.Log

/**
 * Open Keyboard: autocorrect learns "no". If it changes a word and you undo it (delete straight
 * after it, or delete it and type your word again), your word is remembered and never changed again.
 * Remembered words also go into your personal dictionary, so they show up as suggestions.
 */
object AutocorrectMemory {
    private const val TAG = "AutocorrectMemory"
    private const val RETYPE_WINDOW_MS = 3 * 60_000L
    private const val PREF = "open_keyboard_keep_words"

    private data class Change(val typed: String, val corrected: String, val at: Long)
    private val recent = ArrayDeque<Change>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(word: String) = word.lowercase()

    fun isKept(ctx: Context, typed: String) = prefs(ctx).getBoolean(key(typed), false)

    /**
     * Called before autocorrect replaces [typed] with [corrected]. True means leave the word as typed:
     * it's a kept word, or the same change was just made and you typed your word again.
     */
    fun keepTyped(ctx: Context, typed: String, corrected: String): Boolean {
        if (typed.isBlank()) return false
        if (isKept(ctx, typed)) return true
        val now = System.currentTimeMillis()
        recent.removeAll { now - it.at > RETYPE_WINDOW_MS }
        if (recent.any { key(it.typed) == key(typed) && it.corrected == corrected }) {
            keep(ctx, typed, "typed again after it was changed to \"$corrected\"")
            return true
        }
        return false
    }

    /** Autocorrect changed [typed] into [corrected]. */
    fun changed(typed: String, corrected: String) {
        recent.addLast(Change(typed, corrected, System.currentTimeMillis()))
        while (recent.size > 30) recent.removeFirst()
    }

    /** Delete right after a correction put the typed word back. */
    fun reverted(ctx: Context, typed: String, committed: String) {
        if (typed.isBlank() || typed.length < 2) return
        // only autocorrect's own changes count (not a suggestion you picked yourself)
        if (recent.none { it.typed == typed && it.corrected == committed }) return
        keep(ctx, typed, "correction undone with delete")
    }

    /** The word just kept, shown once in the top bar with an Undo. */
    private var notice: String? = null
    private var noticeAt = 0L

    private fun keep(ctx: Context, word: String, why: String) {
        Log.i(TAG, "keeping \"$word\": $why")
        notice = word; noticeAt = System.currentTimeMillis()
        prefs(ctx).edit().putBoolean(key(word), true).apply()
        recent.removeAll { key(it.typed) == key(word) }
        Thread {
            // some phones have no personal dictionary provider; the keep list above still works
            runCatching { UserDictionary.Words.addWord(ctx, word, 250, null, null) }
        }.start()
    }

    fun forgetAll(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** Autocorrect may change this word again. */
    fun forget(ctx: Context, word: String) {
        prefs(ctx).edit().remove(key(word)).apply()
        Thread {
            runCatching { ctx.contentResolver.delete(UserDictionary.Words.CONTENT_URI, "${UserDictionary.Words.WORD}=?", arrayOf(word)) }
        }.start()
    }

    /** "✓ jrod won't be autocorrected again · Undo", for a few seconds after a word is kept. */
    fun chip(ime: LatinIME, parent: android.view.ViewGroup): android.view.View? {
        val word = notice ?: return null
        if (System.currentTimeMillis() - noticeAt > 12_000) { notice = null; return null }
        if (ime.mInputLogic.mWordComposer.isComposingWord) return null // typing: word suggestions come first
        val binding = helium314.keyboard.latin.databinding.ClipboardSuggestionBinding.inflate(android.view.LayoutInflater.from(ime), parent, false)
        val colors = ime.mSettings.current.mColors
        val label = binding.clipboardSuggestionText
        label.text = "✓  \"$word\" won't be autocorrected again  ·  Undo"
        label.setTextColor(colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT))
        colors.setBackground(binding.root, helium314.keyboard.latin.common.ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        label.setOnClickListener {
            forget(ime, word)
            notice = null
            android.widget.Toast.makeText(ime, "OK, autocorrect can change \"$word\" again", android.widget.Toast.LENGTH_SHORT).show()
            ime.setNeutralSuggestionStrip()
        }
        binding.clipboardSuggestionClose.setOnClickListener { notice = null; ime.setNeutralSuggestionStrip() }
        return binding.root
    }
}
