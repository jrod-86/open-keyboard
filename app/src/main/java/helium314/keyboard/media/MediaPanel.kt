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
    private var showingRecent = false
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
        if (KeyboardMenu.currentOverlay is Root && !showingRecent && showingGifs == gifs) KeyboardMenu.hide()
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

    private fun show(context: Context, gifs: Boolean, recent: Boolean = false) {
        showingGifs = gifs
        showingRecent = recent
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

        /** Round button with a symbol: a drawable from the app, or a picture of the pack's first sticker. */
        fun iconPill(res: Int?, packDir: java.io.File?, selected: Boolean, desc: String, onClick: () -> Unit) = FrameLayout(context).apply {
            contentDescription = desc
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (res != null) { setImageResource(res); setColorFilter(text) }
            }
            val size = dp(context, if (packDir != null) 32f else 24f)
            addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            if (packDir != null) {
                val first = StickerPacks.stickers(packDir).firstOrNull()
                if (first != null) loader.execute {
                    val d = runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(first)) { decoder, info, _ ->
                            val s = maxOf(info.size.width, info.size.height)
                            if (s > 128) decoder.setTargetSampleSize(s / 128)
                        }
                    }.getOrNull()
                    main.post { if (gen == generation && d != null) iv.setImageDrawable(d) }
                }
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20f).toFloat()
                setColor(if (selected) ColorUtils.setAlphaComponent(accent, 110) else tile)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(context, 56f), dp(context, 40f))
                .apply { setMargins(dp(context, 4f), dp(context, 6f), dp(context, 4f), dp(context, 6f)) }
        }

        // top row: tabs on the left, ABC on the right
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6f), 0, dp(context, 6f), 0)
            addView(iconPill(helium314.keyboard.latin.R.drawable.ic_emoji_recents_rounded, null, recent, "Recent") { show(context, gifs, recent = true) })
            addView(iconPill(helium314.keyboard.latin.R.drawable.ic_sticker, null, !gifs && !recent, "Stickers") { show(context, false) })
            addView(iconPill(helium314.keyboard.latin.R.drawable.ic_gif, null, gifs && !recent, "GIFs") { show(context, true) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (!gifs && !recent) addView(pill("+ Make", false) {
                KeyboardMenu.hide()
                context.startActivity(Intent(context, StickerMakerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            addView(pill("ABC", false) { KeyboardMenu.hide() })
        }
        column.addView(header)

        if (recent) {
            showRecent(context, column, text, tile, gen)
            KeyboardMenu.showOverlay(root)
            return
        }

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
            packRow.addView(iconPill(helium314.keyboard.latin.R.drawable.ic_store, null, false, "Sticker store") {
                KeyboardMenu.hide()
                context.startActivity(Intent(context, StickerStoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            packRow.addView(iconPill(helium314.keyboard.latin.R.drawable.sym_keyboard_search_rounded, null, pack == null, "Online stickers (KLIPY)") { packSource = null; show(context, false) })
            packs.forEach { p -> packRow.addView(iconPill(null, p, p == pack, p.name) { packSource = p.name; show(context, false) }) }
            column.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(packRow) })
        }

        if (pack != null) {
            val ime = ime(context)
            val ordered = StickerPacks.byUse(context, pack)
            if (ime != null) MediaSender.prefetchAlts(ime, ordered)
            ordered.forEach { file ->
                val view = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = dp(context, 4f); setPadding(p, p, p, p)
                    setOnClickListener { v ->
                        // a quick dip so the tap shows straight away
                        v.animate().cancel(); v.alpha = 0.4f; v.animate().alpha(1f).setDuration(250).start()
                        if (ime != null) MediaSender.sendPackSticker(ime, file)
                        // stay open to send another, like Gboard
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
                                // stay open to send another, like Gboard
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

    /** Recently sent stickers, GIFs and emojis, newest first, all in one grid. */
    private fun showRecent(context: Context, column: LinearLayout, text: Int, tile: Int, gen: Int) {
        val items = MediaRecents.list(context)
        if (items.isEmpty()) {
            column.addView(TextView(context).apply {
                this.text = "Stickers, GIFs and emojis you send will show up here."
                setTextColor(text); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(context, 24f), dp(context, 24f), dp(context, 24f), dp(context, 24f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        val grid = GridLayout(context).apply {
            columnCount = 4
            setPadding(dp(context, 4f), 0, dp(context, 4f), dp(context, 4f))
        }
        val ime = ime(context)
        items.forEach { item ->
            val view: View = when (item) {
                is RecentItem.Emoji -> TextView(context).apply {
                    this.text = item.text
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                    setOnClickListener {
                        (ime as? helium314.keyboard.latin.LatinIME)?.onTextInput(item.text)
                        MediaRecents.add(context, item)
                    }
                }
                is RecentItem.Online -> ImageView(context).apply {
                    if (item.sticker) {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = dp(context, 4f); setPadding(p, p, p, p)
                    } else {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply { cornerRadius = dp(context, 8f).toFloat(); setColor(tile) }
                        clipToOutline = true
                    }
                    setOnClickListener {
                        if (ime != null) MediaSender.sendGif(ime, item.gif, sticker = item.sticker)
                        // stay open to send another, like Gboard
                    }
                    loadGif(item.gif.previewUrl, gen) { setImageDrawable(it) }
                }
                is RecentItem.PackSticker -> ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = dp(context, 4f); setPadding(p, p, p, p)
                    setOnClickListener {
                        if (ime != null) MediaSender.sendPackSticker(ime, item.file)
                        // stay open to send another, like Gboard
                    }
                    val iv = this
                    loader.execute {
                        val d = runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(item.file)) { decoder, info, _ ->
                                val size = maxOf(info.size.width, info.size.height)
                                if (size > 256) decoder.setTargetSampleSize(size / 256)
                            }
                        }.getOrNull()
                        main.post {
                            if (gen != generation || d == null) return@post
                            iv.setImageDrawable(d)
                            (d as? AnimatedImageDrawable)?.start()
                        }
                    }
                }
            }
            val m = dp(context, 3f)
            grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; height = dp(context, 84f); setMargins(m, m, m, m) })
        }
        column.addView(ScrollView(context).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
