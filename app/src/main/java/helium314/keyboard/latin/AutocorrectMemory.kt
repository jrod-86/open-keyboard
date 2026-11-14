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

    private fun keep(ctx: Context, word: String, why: String) {
        Log.i(TAG, "keeping \"$word\": $why")
        prefs(ctx).edit().putBoolean(key(word), true).apply()
        recent.removeAll { key(it.typed) == key(word) }
        Thread {
            // some phones have no personal dictionary provider; the keep list above still works
            runCatching { UserDictionary.Words.addWord(ctx, word, 250, null, null) }
        }.start()
    }

    fun forgetAll(ctx: Context) = prefs(ctx).edit().clear().apply()
}
