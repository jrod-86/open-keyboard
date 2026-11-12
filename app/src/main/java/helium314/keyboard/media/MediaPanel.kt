// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.KeyboardMenu
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Gboard-style sticker and GIF panel that replaces the keys until a sticker/GIF is sent or ABC is tapped. */
object MediaPanel {
    private val loader = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val gifBytes = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private var showingGifs = false
    private var generation = 0
    private var lastQuery: String? = null
    /** Sticker source: null for KLIPY, otherwise the name of one of the user's packs. */
    private var packSource: String? = null

    private val categories = listOf(
        "Trending" to null, "Haha" to "haha", "Love" to "love", "Yes" to "yes", "No" to "no", "Thanks" to "thank you",
        "Hi" to "hi", "Bye" to "bye", "Wow" to "wow", "Sad" to "sad", "OMG" to "omg", "Party" to "party",
        "Dance" to "dance", "Hug" to "hug", "Facepalm" to "facepalm", "Sorry" to "sorry", "Good night" to "good night",
    )

    /** Tapping the same button again closes the panel, like Gboard. */
    fun toggle(context: Context, gifs: Boolean) {
        if (KeyboardMenu.currentOverlay is Root && showingGifs == gifs) KeyboardMenu.hide()
        else show(context, gifs)
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun ime(context: Context): InputMethodService? {
        var c: Context? = context
        while (c is ContextWrapper && c !is InputMethodService) c = c.baseContext
        return c as? InputMethodService
    }

    /** Takes the keyboard's size instead of making the keyboard taller. */
    @SuppressLint("ViewConstructor")
    private class Root(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun show(context: Context, gifs: Boolean) {
        showingGifs = gifs
        val gen = ++generation
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        val tile = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.GESTURE_TRAIL)

        val root = Root(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun pill(label: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
            this.text = label
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 6f), dp(context, 14f), dp(context, 6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f).toFloat()
                setColor(if (selected) ColorUtils.setAlphaComponent(accent, 110) else tile)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(context, 4f), dp(context, 6f), dp(context, 4f), dp(context, 6f)) }
        }

        // top row: tabs on the left, ABC on the right
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6f), 0, dp(context, 6f), 0)
            addView(pill("Stickers", !gifs) { show(context, false) })
            addView(pill("GIFs", gifs) { show(context, true) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill("ABC", false) { KeyboardMenu.hide() })
        }
        column.addView(header)

        val grid = GridLayout(context).apply {
            columnCount = if (gifs) 3 else 4
            setPadding(dp(context, 4f), 0, dp(context, 4f), dp(context, 4f))
        }
        val scroll = ScrollView(context).apply { addView(grid) }

        // sticker tab: choose between KLIPY stickers and the user's own packs
        val packs = if (gifs) emptyList() else StickerPacks.packs(context)
        var pack: java.io.File? = null
        if (!gifs) {
            if (packSource != null) pack = packs.firstOrNull { it.name == packSource }
            if (pack == null && (packSource != null || !MediaPrefs.gifsReady(context))) pack = packs.firstOrNull()
            val packRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(context, 4f), 0, dp(context, 4f), 0) }
            packRow.addView(pill("KLIPY", pack == null) { packSource = null; show(context, false) })
            packs.forEach { p -> packRow.addView(pill(p.name, p == pack) { packSource = p.name; show(context, false) }) }
            packRow.addView(pill("+ My packs", false) {
                KeyboardMenu.hide()
                context.startActivity(Intent(context, GifSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            column.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(packRow) })
        }

        if (pack != null) {
            val ime = ime(context)
            StickerPacks.stickers(pack).forEach { file ->
                val view = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = dp(context, 4f); setPadding(p, p, p, p)
                    setOnClickListener {
                        if (ime != null) MediaSender.sendPackSticker(ime, file)
                        KeyboardMenu.hide()
                    }
                }
                val m = dp(context, 3f)
                grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                    .apply { width = 0; height = dp(context, 96f); setMargins(m, m, m, m) })
                loader.execute {
                    val d = runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                            val size = maxOf(info.size.width, info.size.height)
                            if (size > 256) decoder.setTargetSampleSize(size / 256)
                        }
                    }.getOrNull()
                    main.post {
                        if (gen != generation || d == null) return@post
                        view.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    }
                }
            }
            column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (!MediaPrefs.gifsReady(context)) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 24f), dp(context, 8f), dp(context, 24f), dp(context, 8f))
                addView(TextView(context).apply {
                    this.text = "Stickers and GIFs come from the free KLIPY service online, so they only load if you switch them on."
                    setTextColor(text); gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                addView(pill("Set up stickers and GIFs", true) {
                    KeyboardMenu.hide()
                    context.startActivity(Intent(context, GifSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(context, 4f), 0, dp(context, 4f), 0) }
            categories.forEach { (label, query) ->
                chipRow.addView(pill(label, query == lastQuery) { lastQuery = query; show(context, gifs) })
            }
            column.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            })
            val status = TextView(context).apply {
                this.text = "Loading…"; setTextColor(text); gravity = Gravity.CENTER
                setPadding(0, dp(context, 16f), 0, dp(context, 16f))
            }
            column.addView(status)
            column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(footer(context, "Powered by KLIPY", text))
            val query = lastQuery
            val ime = ime(context)
            loader.execute {
                val result = runCatching { Klipy.gifs(context, query, stickers = !gifs) }
                main.post {
                    if (gen != generation) return@post
                    val list = result.getOrNull()
                    if (list == null) {
                        Log.e("MediaPanel", "GIF loading failed", result.exceptionOrNull())
                        status.text = "Couldn't load ${if (gifs) "GIFs" else "stickers"}. Check your internet and your KLIPY key in settings."
                        return@post
                    }
                    if (list.isEmpty()) { status.text = "Nothing found"; return@post }
                    status.visibility = View.GONE
                    list.forEach { gif ->
                        val view = ImageView(context).apply {
                            if (gifs) {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                background = GradientDrawable().apply { cornerRadius = dp(context, 8f).toFloat(); setColor(tile) }
                                clipToOutline = true
                            } else {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                val p = dp(context, 4f); setPadding(p, p, p, p)
                            }
                            setOnClickListener {
                                if (ime != null) MediaSender.sendGif(ime, gif, sticker = !gifs)
                                KeyboardMenu.hide()
                            }
                        }
                        val m = dp(context, 3f)
                        grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                            .apply { width = 0; height = dp(context, 96f); setMargins(m, m, m, m) })
                        loadGif(gif.previewUrl, gen) { view.setImageDrawable(it) }
                    }
                }
            }
        }
        KeyboardMenu.showOverlay(root)
    }

    private fun footer(context: Context, label: String, color: Int) = TextView(context).apply {
        text = label
        setTextColor(ColorUtils.setAlphaComponent(color, 160))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(0, dp(context, 2f), 0, dp(context, 4f))
    }

    private fun loadGif(url: String, gen: Int, onLoaded: (Drawable) -> Unit) {
        loader.execute {
            val drawable = runCatching {
                val bytes = gifBytes.get(url) ?: Klipy.download(url).also { gifBytes.put(url, it) }
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            }.getOrNull() ?: return@execute
            main.post {
                if (gen != generation) return@post
                onLoaded(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
            }
        }
    }
}
