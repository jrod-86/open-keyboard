// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text shortcuts: type a shortcut such as "@@" or "omw" and press space, and it is replaced with
 * the full text. Pressing delete straight away puts the shortcut back.
 */
object TextShortcuts {
    const val PREF = "open_keyboard_text_shortcuts"

    data class Shortcut(val trigger: String, val text: String)

    private val examples = listOf(
        Shortcut("omw", "On my way!"),
        Shortcut("brb", "Be right back"),
    )

    @Volatile private var cache: List<Shortcut>? = null

    fun load(ctx: Context): List<Shortcut> {
        cache?.let { return it }
        val raw = ctx.prefs().getString(PREF, null)
        val list = if (raw == null) examples else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Shortcut(o.getString("t"), o.getString("x")) } }
        }.getOrDefault(examples)
        cache = list
        return list
    }

    fun save(ctx: Context, list: List<Shortcut>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.trigger).put("x", it.text)) }
        ctx.prefs().edit().putString(PREF, a.toString()).apply()
        cache = list
    }

    /** The last expansion, so delete right after it can undo it. */
    private var last: Pair<String, String>? = null

    /**
     * Called before a space is typed. If the text before the cursor ends with a shortcut, replaces it
     * with its text plus a space and returns true.
     */
    fun expandOnSpace(ctx: Context, ic: InputConnection?, finishComposing: () -> Unit, resync: (Int, Int) -> Unit): Boolean {
        last = null
        ic ?: return false
        if (!Plugins.isOn(ctx, "shortcuts")) return false
        val shortcuts = load(ctx)
        if (shortcuts.isEmpty()) return false
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return false
        val token = before.takeLastWhile { !it.isWhitespace() }
        if (token.isEmpty()) return false
        val match = shortcuts.firstOrNull { it.trigger.equals(token, ignoreCase = true) } ?: return false
        finishComposing()
        val replacement = match.text + " "
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(token.length, 0)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()
        last = token to replacement
        resyncFrom(ic, resync)
        return true
    }

    /** Called before delete. Undoes an expansion that was just made. */
    fun undoOnDelete(ic: InputConnection?, resync: (Int, Int) -> Unit): Boolean {
        val (token, replacement) = last ?: return false
        last = null
        ic ?: return false
        if (ic.getTextBeforeCursor(replacement.length, 0)?.toString() != replacement) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(replacement.length, 0)
        ic.commitText(token, 1)
        ic.endBatchEdit()
        resyncFrom(ic, resync)
        return true
    }

    /** Any other key: an undo is no longer offered. */
    fun forget() { last = null }

    private fun resyncFrom(ic: InputConnection, resync: (Int, Int) -> Unit) {
        val ex = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (ex != null && ex.selectionStart >= 0) resync(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
    }
}
