// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
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
            columnCount = if (gifs) 3 else 5
            setPadding(dp(context, 4f), 0, dp(context, 4f), dp(context, 4f))
        }
        val scroll = ScrollView(context).apply { addView(grid) }

        if (!gifs) {
            val ime = ime(context)
            Stickers.list(context).forEach { name ->
                val view = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = dp(context, 6f); setPadding(p, p, p, p)
                    contentDescription = name.removeSuffix(".png").replace('_', ' ')
                    setOnClickListener {
                        if (ime != null) MediaSender.sendSticker(ime, name)
                        KeyboardMenu.hide()
                    }
                }
                grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                    .apply { width = 0; height = dp(context, 64f) })
                loader.execute {
                    val bmp = runCatching {
                        context.assets.open("stickers/$name").use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }
                    }.getOrNull()
                    main.post { if (gen == generation) view.setImageBitmap(bmp) }
                }
            }
            column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(footer(context, "Stickers: Microsoft Fluent Emoji (MIT licence)", text))
        } else if (!MediaPrefs.gifsReady(context)) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 24f), dp(context, 8f), dp(context, 24f), dp(context, 8f))
                addView(TextView(context).apply {
                    this.text = "GIF search is off. It uses the free KLIPY service online, so it only runs if you switch it on."
                    setTextColor(text); gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                addView(pill("Set up GIFs", true) {
                    KeyboardMenu.hide()
                    context.startActivity(Intent(context, GifSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(context, 4f), 0, dp(context, 4f), 0) }
            categories.forEach { (label, query) ->
                chipRow.addView(pill(label, query == lastQuery) { lastQuery = query; show(context, true) })
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
                val result = runCatching { Klipy.gifs(context, query) }
                main.post {
                    if (gen != generation) return@post
                    val list = result.getOrNull()
                    if (list == null) {
                        Log.e("MediaPanel", "GIF loading failed", result.exceptionOrNull())
                        status.text = "Couldn't load GIFs. Check your internet and your KLIPY key in settings."
                        return@post
                    }
                    if (list.isEmpty()) { status.text = "No GIFs found"; return@post }
                    status.visibility = View.GONE
                    list.forEach { gif ->
                        val view = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = GradientDrawable().apply { cornerRadius = dp(context, 8f).toFloat(); setColor(tile) }
                            clipToOutline = true
                            setOnClickListener {
                                if (ime != null) MediaSender.sendGif(ime, gif)
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
