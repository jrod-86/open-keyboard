// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context
import android.graphics.Color

/** The Image maker's 🎨 colour themes: two accent colours for the gradients, and the page, card, line and text colours. */
class ImageTheme(val name: String, val a1: Int, val a2: Int, val bg: Int, val card: Int, val line: Int, val text: Int) {
    val dark get() = Color.luminance(bg) < 0.4f
}

object ImageThemes {
    private fun c(hex: String) = Color.parseColor(hex)
    private fun dark(name: String, a1: String, a2: String, bg: String = "#16171B", card: String = "#24252A", line: String = "#40424A") =
        ImageTheme(name, c(a1), c(a2), c(bg), c(card), c(line), c("#ECEDF2"))
    private fun light(name: String, a1: String, a2: String, bg: String = "#FFFFFF", card: String = "#F2F3F7", line: String = "#D8DAE2") =
        ImageTheme(name, c(a1), c(a2), c(bg), c(card), c(line), c("#1D1E22"))

    /** Index 0 follows the phone's light or dark mode. */
    val ALL = listOf(
        ImageTheme("Phone default", 0, 0, 0, 0, 0, 0),
        dark("Galaxy", "#8E54E9", "#4285F4"),
        dark("Midnight", "#6366F1", "#22D3EE", "#0B1020", "#161D33", "#2A3352"),
        dark("Aurora", "#34D399", "#A855F7", "#0E1716", "#1A2725", "#2E423F"),
        dark("Sunset", "#F97316", "#EC4899", "#1A1214", "#2A1D21", "#45303A"),
        dark("Ocean", "#06B6D4", "#3B82F6", "#0A1418", "#14232A", "#25404B"),
        dark("Forest", "#22C55E", "#84CC16", "#0F1510", "#1B241C", "#2F3E31"),
        dark("Ember", "#EF4444", "#F59E0B", "#170F0E", "#271A18", "#42302C"),
        dark("Neon", "#F0ABFC", "#22D3EE", "#09090B", "#18181B", "#3F3F46"),
        dark("Royal", "#A78BFA", "#FBBF24", "#140F22", "#221A36", "#3A2F55"),
        dark("Mono night", "#E5E7EB", "#9CA3AF", "#111111", "#1E1E1E", "#3A3A3A"),
        light("Daylight", "#8E54E9", "#4285F4"),
        light("Candy", "#EC4899", "#A855F7", "#FFF5FA", "#FCE7F3", "#F5C2DD"),
        light("Mint", "#10B981", "#06B6D4", "#F3FBF8", "#E1F5EE", "#BFE6D8"),
        light("Peach", "#FB923C", "#F43F5E", "#FFF8F3", "#FFEDE0", "#F9CDB0"),
        light("Sky", "#0EA5E9", "#6366F1", "#F5FAFF", "#E3F0FD", "#BFD8F2"),
        light("Lemon", "#EAB308", "#F97316", "#FFFDF2", "#FEF6D3", "#F1DF95"),
        light("Lavender", "#8B5CF6", "#EC4899", "#FAF7FF", "#EFE8FD", "#D5C6F5"),
        light("Sand", "#B45309", "#D97706", "#FBF7F0", "#F2EADB", "#DCCBAE"),
        light("Paper", "#374151", "#6B7280", "#FAFAF9", "#F0EFEC", "#D6D3CE"),
    )

    fun index(c: Context) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE).getInt("theme", 0).coerceIn(0, ALL.size - 1)
    fun set(c: Context, i: Int) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE).edit().putInt("theme", i).apply()

    /** The theme to use now, with "Phone default" resolved to the original purple-and-blue look in light or dark. */
    fun current(c: Context, night: Boolean): ImageTheme {
        val t = ALL[index(c)]
        if (t.a1 != 0) return t
        return if (night) ImageTheme("Phone default", c("#8E54E9"), c("#4285F4"), c("#121316"), c("#24252A"), c("#40424A"), c("#ECEDF2"))
        else ImageTheme("Phone default", c("#8E54E9"), c("#4285F4"), c("#FAFAFC"), c("#F2F3F7"), c("#D8DAE2"), c("#1D1E22"))
    }
}

/** A tiny drawing of the Local Image screen in a theme's colours, for the 🎨 theme picker tiles. */
class ThemePreview(context: Context, private val t: ImageTheme, private val chosen: Boolean) : android.view.View(context) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 5 / 4)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 20f
        p.shader = null; p.style = android.graphics.Paint.Style.FILL
        // page
        p.color = t.bg; r.set(0f, 0f, w, h); c.drawRoundRect(r, u * 1.6f, u * 1.6f, p)
        // title, in the two accent colours
        p.shader = android.graphics.LinearGradient(u * 2, 0f, u * 12, 0f, t.a1, t.a2, android.graphics.Shader.TileMode.CLAMP)
        r.set(u * 2, u * 2, u * 12, u * 3.6f); c.drawRoundRect(r, u, u, p)
        p.shader = null
        // two dropdown cards
        for (i in 0..1) {
            p.color = t.card; r.set(u * 2 + i * u * 8.25f, u * 5, u * 9.75f + i * u * 8.25f, u * 8); c.drawRoundRect(r, u, u, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = u * 0.2f; p.color = t.line; c.drawRoundRect(r, u, u, p)
            p.style = android.graphics.Paint.Style.FILL
            p.color = t.text; p.alpha = 150; r.set(r.left + u, r.top + u * 1.1f, r.left + u * 5, r.top + u * 1.9f); c.drawRoundRect(r, u * 0.4f, u * 0.4f, p); p.alpha = 255
        }
        // prompt box with a line of text
        p.color = t.card; r.set(u * 2, u * 9, u * 18, u * 12); c.drawRoundRect(r, u, u, p)
        p.color = t.text; p.alpha = 120; r.set(u * 3, u * 10.1f, u * 12, u * 10.9f); c.drawRoundRect(r, u * 0.4f, u * 0.4f, p); p.alpha = 255
        // Generate button
        p.shader = android.graphics.LinearGradient(u * 2, 0f, u * 18, 0f, t.a1, t.a2, android.graphics.Shader.TileMode.CLAMP)
        r.set(u * 2, u * 13, u * 18, u * 15.2f); c.drawRoundRect(r, u * 1.1f, u * 1.1f, p)
        // picture card with a gradient frame
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = u * 0.35f
        r.set(u * 2, u * 16.2f, u * 18, u * 23.5f); c.drawRoundRect(r, u * 1.2f, u * 1.2f, p)
        p.shader = null; p.style = android.graphics.Paint.Style.FILL
        p.color = t.card; r.inset(u * 0.35f, u * 0.35f); c.drawRoundRect(r, u, u, p)
        // little "picture": sun and hill, kept inside the card
        c.save(); c.clipRect(r)
        p.color = t.a2; p.alpha = 160; c.drawCircle(u * 12.5f, u * 18.6f, u * 1.1f, p)
        p.color = t.a1; p.alpha = 170; c.drawOval(u * 4f, u * 20.3f, u * 16f, u * 25.5f, p); p.alpha = 255
        c.restore()
        // chosen: a bold ring and a tick
        if (chosen) {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = u * 0.7f; p.color = t.a1
            r.set(u * 0.35f, u * 0.35f, w - u * 0.35f, h - u * 0.35f); c.drawRoundRect(r, u * 1.6f, u * 1.6f, p)
            p.style = android.graphics.Paint.Style.FILL; c.drawCircle(w - u * 2.6f, u * 2.8f, u * 1.6f, p)
            p.color = android.graphics.Color.WHITE; p.textSize = u * 2.2f; p.textAlign = android.graphics.Paint.Align.CENTER
            c.drawText("✓", w - u * 2.6f, u * 3.6f, p)
        } else {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = u * 0.2f; p.color = t.line
            r.set(0f, 0f, w, h); r.inset(u * 0.1f, u * 0.1f); c.drawRoundRect(r, u * 1.6f, u * 1.6f, p)
        }
    }
}
