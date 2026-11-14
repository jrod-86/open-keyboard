// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.provider.UserDictionary

/**
 * Open Keyboard: "autocorrect remembers words you put back" was tried in tests 113-115 and dropped.
 * This only removes the words it saved, from its own list and from the personal dictionary.
 */
object AutocorrectMemory {
    private const val PREF = "open_keyboard_keep_words"

    fun removeSavedWords(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val words = prefs.all.keys.toList()
        if (words.isEmpty()) return
        prefs.edit().clear().apply()
        Thread {
            for (w in words) runCatching {
                ctx.contentResolver.delete(UserDictionary.Words.CONTENT_URI, "LOWER(${UserDictionary.Words.WORD})=?", arrayOf(w))
            }
        }.start()
    }
}
