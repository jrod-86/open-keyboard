// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The Image maker's gallery: every finished picture is kept in the app's own storage (never shared, never uploaded),
 * with the words that made it. How many are kept is the person's choice (see [LIMITS]); past that, the oldest go.
 */
object Gallery {
    /** Choices for how many pictures to keep. 1 = only the latest (each new one replaces it); 0 = no limit. */
    val LIMITS = listOf(1 to "Only the latest (each new picture replaces it)", 25 to "25 pictures", 50 to "50 pictures",
        100 to "100 pictures", 250 to "250 pictures", 0 to "No limit")

    private fun prefs(c: Context) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE)
    fun limit(c: Context) = prefs(c).getInt("gallery_limit", 100)
    fun setLimit(c: Context, n: Int) { prefs(c).edit().putInt("gallery_limit", n).apply(); trim(c) }
    fun limitLabel(c: Context) = LIMITS.firstOrNull { it.first == limit(c) }?.second ?: "${limit(c)} pictures"

    private fun dir(c: Context) = File(c.filesDir, "image_maker_gallery").apply { mkdirs() }

    class Item(val file: File) {
        val words: String get() = runCatching { File(file.path.removeSuffix(".png") + ".txt").readText() }.getOrDefault("")
        fun thumb(size: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= size) s *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
        fun full(): Bitmap? = BitmapFactory.decodeFile(file.path)
        fun delete() { file.delete(); File(file.path.removeSuffix(".png") + ".txt").delete() }
    }

    /** Newest first. */
    fun items(c: Context) = (dir(c).listFiles { f -> f.name.endsWith(".png") } ?: emptyArray())
        .sortedByDescending { it.name }.map { Item(it) }

    fun add(c: Context, bmp: Bitmap, words: String) {
        runCatching {
            val name = "%013d".format(System.currentTimeMillis())
            val f = File(dir(c), "$name.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir(c), "$name.txt").writeText(words)
            trim(c)
        }
    }

    private fun trim(c: Context) {
        val n = limit(c)
        if (n <= 0) return
        items(c).drop(n).forEach { it.delete() }
    }
}
