// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.Plugins
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Learns your fingers: remembers where you really tap each letter (say a little low and to the
 * left of "e") and moves that key's target to match. It only learns from words you typed without
 * fixing them, so mistakes don't teach it, and it never moves a target more than 30% of a key.
 * Everything stays on the phone.
 */
object FingerModel {
    private const val PREFS = "finger_model"
    private const val MIN_SAMPLES = 15
    private const val MAX_SHIFT = 0.3f

    private class Stat(var dx: Float = 0f, var dy: Float = 0f, var n: Int = 0)

    private val stats = HashMap<Int, Stat>()
    private val word = ArrayList<Triple<Int, Float, Float>>()
    private var deleted = false
    private var ime: WeakReference<LatinIME>? = null
    private var loaded = false
    private var unsaved = 0
    private val main = Handler(Looper.getMainLooper())

    fun init(latinIme: LatinIME) { ime = WeakReference(latinIme) }

    private fun on() = Plugins.isOn("fingers")

    /** Every key press goes through here. */
    fun onKey(key: Key, code: Int, x: Int, y: Int) {
        if (!on()) return
        when {
            code == KeyCode.DELETE -> deleted = true
            Character.isLetter(code) && key.width > 0 && key.height > 0 -> word += Triple(
                Character.toLowerCase(code),
                (x - key.x - key.width / 2f) / key.width,
                (y - key.y - key.height / 2f) / key.height)
            code > 0 -> wordEnd() // space, enter, punctuation; shift and other function keys don't end a word
        }
    }

    private fun wordEnd() {
        if (word.size >= 2 && !deleted) {
            val taps = ArrayList(word)
            val letters = taps.joinToString("") { String(Character.toChars(it.first)) }
            // learn only if the word went in as typed (autocorrect didn't change it)
            main.post {
                val before = ime?.get()?.currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return@post
                val last = before.trimEnd { !it.isLetter() }.takeLastWhile { it.isLetter() }.lowercase()
                if (last == letters) learn(taps)
            }
        }
        word.clear()
        deleted = false
    }

    private fun learn(taps: List<Triple<Int, Float, Float>>) {
        load()
        for ((c, dx, dy) in taps) {
            if (abs(dx) > 0.6f || abs(dy) > 0.6f) continue // a stretch, not a habit
            val s = stats.getOrPut(c) { Stat() }
            s.n++
            val a = if (s.n < 50) 1f / s.n else 0.02f // average at first, then follow slow changes
            s.dx += (dx - s.dx) * a
            s.dy += (dy - s.dy) * a
        }
        if (++unsaved >= 10) save()
    }

    /** How far (in pixels, squared) a touch is from where you usually tap this key. */
    @JvmStatic
    fun score(key: Key, x: Int, y: Int): Float {
        val s = stats[Character.toLowerCase(key.code)]?.takeIf { it.n >= MIN_SAMPLES }
        val ox = (s?.dx ?: 0f).coerceIn(-MAX_SHIFT, MAX_SHIFT) * key.width
        val oy = (s?.dy ?: 0f).coerceIn(-MAX_SHIFT, MAX_SHIFT) * key.height
        val cx = key.x + key.width / 2f + ox
        val cy = key.y + key.height / 2f + oy
        return (x - cx) * (x - cx) + (y - cy) * (y - cy)
    }

    private fun knows(key: Key) = (stats[Character.toLowerCase(key.code)]?.n ?: 0) >= MIN_SAMPLES

    /** The key you most likely meant, given how you tap. Only ever picks a close neighbour. */
    @JvmStatic
    fun adjust(keyboard: Keyboard, hit: Key, x: Int, y: Int): Key {
        if (!on() || !Character.isLetter(hit.code)) return hit
        load()
        if (stats.isEmpty()) return hit
        var best = hit
        var bestScore = score(hit, x, y)
        for (key in keyboard.getNearestKeys(x, y)) {
            if (key === hit || !Character.isLetter(key.code)) continue
            if (!knows(key) && !knows(hit)) continue
            val half = key.width / 2
            if (key.squaredDistanceToEdge(x, y) > half * half) continue
            val sc = score(key, x, y)
            if (sc < bestScore * 0.85f) { best = key; bestScore = sc }
        }
        return best
    }

    private fun prefs(): android.content.SharedPreferences? =
        ime?.get()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        if (loaded) return
        val p = prefs() ?: return
        loaded = true
        runCatching {
            val o = JSONObject(p.getString("stats", "{}")!!)
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                stats[k.toInt()] = Stat(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getInt(2))
            }
        }
    }

    private fun save() {
        unsaved = 0
        val o = JSONObject()
        stats.forEach { (c, s) -> o.put(c.toString(), org.json.JSONArray().put(s.dx.toDouble()).put(s.dy.toDouble()).put(s.n)) }
        prefs()?.edit()?.putString("stats", o.toString())?.apply()
    }

    /** How many taps it has learned from, for the settings screen. */
    fun learnedTaps(ctx: Context): Int {
        if (stats.isEmpty()) runCatching {
            val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("stats", "{}")!!)
            return o.keys().asSequence().sumOf { o.getJSONArray(it).getInt(2) }
        }
        return stats.values.sumOf { it.n }
    }

    fun reset(ctx: Context) {
        stats.clear(); word.clear(); unsaved = 0
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
