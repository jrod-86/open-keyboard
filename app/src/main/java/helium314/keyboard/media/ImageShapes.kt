// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Picture shapes (aspect ratios). Every shape keeps about the same number of pixels as a 512 x 512 square, so a
 * tall or wide picture takes about as long as a square one. Sides are multiples of 64, which the models need.
 */
object ImageShapes {
    class Shape(val icon: String, val name: String, val wr: Int, val hr: Int, val use: String)

    val PRESETS = listOf(
        Shape("⬛", "Square", 1, 1, "1:1 · posts, profile pictures"),
        Shape("📱", "Phone", 9, 16, "9:16 · wallpapers, stories, reels"),
        Shape("🖥️", "Wide", 16, 9, "16:9 · screens, YouTube, desktop"),
        Shape("🖼️", "Portrait", 3, 4, "3:4 · portraits"),
        Shape("🏞️", "Landscape", 4, 3, "4:3 · classic landscape"),
        Shape("📷", "Photo tall", 2, 3, "2:3 · photo prints, posters"),
        Shape("📸", "Photo wide", 3, 2, "3:2 · camera photos"),
        Shape("📄", "Card", 4, 5, "4:5 · cards, Instagram portrait"),
        Shape("🎬", "Cinema", 21, 9, "21:9 · ultra-wide, banners"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE)

    /** The chosen shape; index PRESETS.size means the custom one. */
    fun index(c: Context) = prefs(c).getInt("shape", 0).coerceIn(0, PRESETS.size)
    fun custom(c: Context) = prefs(c).getInt("shape_w", 5) to prefs(c).getInt("shape_h", 4)
    fun set(c: Context, i: Int) = prefs(c).edit().putInt("shape", i).apply()
    fun setCustom(c: Context, w: Int, h: Int) = prefs(c).edit().putInt("shape", PRESETS.size).putInt("shape_w", w).putInt("shape_h", h).apply()

    fun ratio(c: Context): Pair<Int, Int> = if (index(c) == PRESETS.size) custom(c) else PRESETS[index(c)].let { it.wr to it.hr }
    fun label(c: Context): String = if (index(c) == PRESETS.size) custom(c).let { "✏️ Custom ${it.first}:${it.second}" }
        else PRESETS[index(c)].let { "${it.icon} ${it.name}" }

    /** Pixel size for a ratio: about 512 x 512 worth of pixels, sides multiples of 64 between 256 and 1024. */
    fun size(wr: Int, hr: Int): Pair<Int, Int> {
        val area = 512.0 * 512.0
        val w = sqrt(area * wr / hr)
        val h = area / w
        fun fit(v: Double) = ((v / 64).roundToInt() * 64).coerceIn(256, 1024)
        return fit(w) to fit(h)
    }
    fun size(c: Context) = ratio(c).let { size(it.first, it.second) }
}
