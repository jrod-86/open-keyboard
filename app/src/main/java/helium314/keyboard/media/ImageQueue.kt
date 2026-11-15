// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture queue ("overnight run"): pictures waiting to be drawn one after another, each with the settings it was
 * added with. Kept in preferences so it survives the screen closing. [ImageJob] starts the next one when one finishes.
 */
object ImageQueue {
    class Item(val words: String, val prompt: String, val model: String, val steps: Int, val w: Int, val h: Int) {
        fun json() = JSONObject().put("words", words).put("prompt", prompt).put("model", model).put("steps", steps).put("w", w).put("h", h)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE)

    @Synchronized fun items(c: Context): List<Item> = runCatching {
        val a = JSONArray(prefs(c).getString("queue", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Item(o.getString("words"), o.getString("prompt"), o.getString("model"), o.getInt("steps"), o.getInt("w"), o.getInt("h"))
        } }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(c: Context, list: List<Item>) =
        prefs(c).edit().putString("queue", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    fun add(c: Context, item: Item) = save(c, items(c) + item)
    fun removeAt(c: Context, i: Int) = save(c, items(c).filterIndexed { k, _ -> k != i })
    fun clear(c: Context) = save(c, emptyList())

    /** True while a queue run is going (cleared by Stop, or when the queue is empty). */
    @Volatile var active = false
    /** Pictures finished in this run, and how many the run had in total. */
    @Volatile var done = 0
    @Volatile var total = 0

    @Synchronized fun takeNext(c: Context): Item? {
        val list = items(c)
        if (list.isEmpty()) return null
        save(c, list.drop(1))
        return list.first()
    }
}
