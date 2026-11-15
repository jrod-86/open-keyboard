// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import helium314.keyboard.latin.utils.fitAboveKeyboard
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

// Best (Z-Image Turbo, Qwen3 text reader): its makers say it draws best from long, detailed descriptions written as
// natural sentences, in the order subject, setting, camera, lighting, style. No "no ..." phrases: it has no negative prompt.
private const val ENHANCE_BEST = "You write prompts for an image generator that draws best from long, detailed descriptions " +
    "written as natural sentences, not lists of tags. Turn the user's idea into one rich description of a single picture, " +
    "70 to 100 words, in this order: first the main subject with specific details (age, build, face, hair, expression, " +
    "clothing, colours and materials); then what they are doing and their pose; then the setting and background with a few " +
    "concrete objects; then the lighting, time of day and weather; then the camera: shot type, angle, lens and depth of field; " +
    "last the style and mood. Keep everything the user asked for. Wherever the user didn't say, choose fitting, specific " +
    "details yourself. Be concise: every phrase must add something you can see, with no filler such as \"beautiful\", " +
    "\"stunning\" or \"the image shows\". Describe only what should be in the picture, never what should not. Only put writing in the picture " +
    "if the user asked for it, with the exact words in quotes. " +
    "Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, reply only BLOCKED. " +
    "Reply with only the description, in English, as one paragraph.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: A confident woman in her early thirties with wavy auburn hair and a warm smile, wearing a cream " +
    "trench coat and dark jeans, leans against a glossy cherry-red sports car with chrome wheels. They are parked on a quiet " +
    "cobblestone street lined with old stone buildings and a small corner cafe. Golden late-afternoon sunlight casts long " +
    "soft shadows. Medium full-length shot at eye level, 50mm lens, background gently blurred. Realistic photo, rich natural " +
    "colours, relaxed and stylish mood."

// Quick (SDXS, a small Stable Diffusion model with a CLIP text reader): only the first ~75 words-pieces count,
// so a short description with the most important things first, in comma-separated phrases.
private const val ENHANCE_QUICK = "You write prompts for a small, fast image generator that only reads about 50 words. Turn the " +
    "user's idea into one picture description of 30 to 45 words, as short descriptive phrases separated by commas, most " +
    "important first: the main subject and what it looks like, what it is doing, the setting, the lighting, the colours, " +
    "the style. Keep everything the user asked for and choose fitting details where they didn't say. Don't add writing to " +
    "the picture. Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, " +
    "reply only BLOCKED. Reply with only the description, in English, on one line.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: smiling woman with wavy auburn hair in a cream trench coat, leaning on a glossy red sports car, " +
    "cobblestone street, old stone buildings, golden hour sunlight, soft shadows, warm colours, realistic photo, sharp focus"

/** Image maker: type what you want, the phone draws it (see [ImageGen]), then send it, save it or make it a sticker. */
class ImageMakerActivity : Activity() {
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var stageList: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var picture: ImageView
    private lateinit var placeholder: TextView
    private lateinit var glowCard: GlowCard
    private lateinit var magic: MagicView
    private lateinit var generate: Button
    private lateinit var actions: LinearLayout
    private var style = 0
    private lateinit var modelValue: TextView
    private lateinit var styleValue: TextView
    private val stepButtons = mutableListOf<TextView>()
    private lateinit var stepRow: View
    private lateinit var engineRow: View
    private lateinit var speedRow: View
    private lateinit var enhanceButton: TextView
    @Volatile private var enhancing = false
    private val speedButtons = mutableListOf<TextView>()
    private val engineButtons = mutableListOf<TextView>()
    private var gpuName = ""
    private var model = ImageGen.QUICK
    private var steps = 4
    private val prefs by lazy { getSharedPreferences("image_maker", MODE_PRIVATE) }
    private var result: Bitmap? = null
    @Volatile private var working = false

    private class Style(val icon: String, val name: String, val sub: String, val suffix: String)

    private val styles = listOf(
        Style("🏷️", "Sticker", "Cute, bold outline, white background", ", cute sticker, cartoon, bold outline, plain white background"),
        Style("🖍️", "Cartoon", "Bright and playful", ", cartoon illustration, bright colours"),
        Style("🌸", "Anime", "Japanese animation look", ", anime style illustration, vibrant, detailed"),
        Style("📷", "Photo", "Realistic, like a camera shot", ", photo, realistic, detailed, natural light"),
        Style("🧸", "3D", "Soft 3D toy look", ", 3d render, soft lighting, cute, clay style"),
        Style("🎨", "Watercolour", "Painted, soft edges", ", watercolour painting, soft colours, paper texture"),
        Style("👾", "Pixel art", "Retro video game", ", pixel art, 16-bit, retro game style"),
        Style("😀", "Emoji", "Glossy icon", ", emoji icon, 3d, glossy, plain white background"),
        Style("✏️", "None", "Just your words", ""),
    )

    private val night get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    // colours come from the chosen 🎨 theme (ImageThemes); "purple" and "blue" are its two accent colours
    private val theme by lazy { ImageThemes.current(this, night) }
    private val purple get() = theme.a1
    private val blue get() = theme.a2
    private val cardColor get() = theme.card
    private val lineColor get() = theme.line
    private lateinit var galleryTitle: TextView
    private lateinit var galleryGrid: LinearLayout
    private var galleryShown = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        model = ImageGen.MODELS.firstOrNull { it.id == prefs.getString("model", "quick") } ?: ImageGen.QUICK
        steps = prefs.getInt("steps", 4)
        style = prefs.getInt("style", 0).coerceIn(0, styles.size - 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        // title with a two-colour shine, and the 🎨 themes, ⚙️ settings and ℹ️ help buttons beside it
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(head)
        head.addView(TextView(this).apply {
            text = "✨ Local Image"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6f), 0, 0)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                (v as TextView).paint.shader = android.graphics.LinearGradient(0f, 0f, v.width.toFloat(), 0f, purple, blue, android.graphics.Shader.TileMode.CLAMP)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((icon, what, act) in listOf<Triple<String, String, () -> Unit>>(Triple("🎨", "Themes", ::pickTheme), Triple("⚙️", "Settings", ::openSettings), Triple("ℹ️", "How it works", ::showHelp))) {
            head.addView(TextView(this).apply {
                text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
                contentDescription = what; setOnClickListener { act() }
            })
        }
        root.addView(TextView(this).apply {
            text = "Dream it up, your phone draws it. Nothing leaves your phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.7f; setPadding(0, dp(2f), 0, pad * 3 / 4)
        })

        // model and style dropdowns, side by side
        val drops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (modelCard, mv) = dropdown("MODEL") { if (!working) pickModel(it) }
        val (styleCard, sv) = dropdown("STYLE") { if (!working) pickStyle(it) }
        modelValue = mv; styleValue = sv
        drops.addView(modelCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad / 4 })
        drops.addView(styleCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad / 4 })
        root.addView(drops)

        // steps, only for the model that has them: a two-part switch
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = UiKit.rounded(this@ImageMakerActivity, 20f, cardColor, lineColor)
        }
        listOf(4 to "4 steps · faster", 8 to "8 steps · sharper").forEach { (n, label) ->
            val b = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(8f), 0, dp(8f))
                setOnClickListener { if (!working) { steps = n; prefs.edit().putInt("steps", n).apply(); refresh() } }
            }
            stepButtons.add(b)
            seg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        stepRow = seg
        root.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })

        // where to draw: the graphics chip or the main processor, shown once a usable graphics chip is found
        val eng = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f)); visibility = View.GONE
            background = UiKit.rounded(this@ImageMakerActivity, 20f, cardColor, lineColor)
        }
        listOf(true to "🎮 Graphics chip", false to "🧠 Main processor").forEach { (gpu, label) ->
            val b = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(8f), 0, dp(8f))
                setOnClickListener { if (!working) { ImageJob.setGpu(this@ImageMakerActivity, gpu); refresh() } }
            }
            engineButtons.add(b)
            eng.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        engineRow = eng
        root.addView(eng, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })

        // how hard to work: Smooth keeps the phone smooth, Balanced is in between, Fastest takes it all; each explains itself when tapped
        val spd = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = UiKit.rounded(this@ImageMakerActivity, 20f, cardColor, lineColor)
        }
        listOf("🌿 Smooth", "⚖️ Balanced", "🚀 Fastest").forEachIndexed { speed, label ->
            val b = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(8f), 0, dp(8f))
                setOnClickListener { if (!working) pickSpeed(speed) }
            }
            speedButtons.add(b)
            spd.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        speedRow = spd
        root.addView(spd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        ImageJob.findGpu(this) { name -> gpuName = name; refresh() }

        // what to draw, with a dice for ideas
        val promptBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(this@ImageMakerActivity, 16f, cardColor, lineColor)
        }
        prompt = EditText(this).apply {
            hint = "What should it draw?"
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            maxLines = 8; background = null
            // long 🪄 prompts: a scroll bar, and the box (not the page) scrolls when you drag inside it
            // a view made in code has no scroll bar drawable until it's given one (without it, drawing crashed: test 160)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                verticalScrollbarThumbDrawable = GradientDrawable().apply { setColor(purple); cornerRadius = dp(2f).toFloat(); setSize(dp(4f), dp(24f)) }
                verticalScrollbarTrackDrawable = GradientDrawable().apply { setColor(lineColor); cornerRadius = dp(2f).toFloat(); setSize(dp(4f), dp(24f)) }
                isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
                scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            }
            setOnTouchListener { v, e ->
                if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                    v.parent.requestDisallowInterceptTouchEvent(e.actionMasked != android.view.MotionEvent.ACTION_UP && e.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
                }
                false
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(pad, pad * 3 / 4, dp(4f), pad * 3 / 4)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { start(); true } else false }
        }
        promptBox.addView(prompt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // ✕ clears the box for a new idea; only shown when there's something to clear
        val clear = TextView(this).apply {
            text = "✕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
            alpha = 0.6f; visibility = View.GONE
            contentDescription = "Clear"
            setOnClickListener { prompt.setText(""); beforeEnhance = null; lastEnhanced = null; prompt.requestFocus() }
        }
        promptBox.addView(clear)
        prompt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
        enhanceButton = TextView(this).apply {
            text = "🪄"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
            contentDescription = "Improve my words"
            setOnClickListener { enhance() }
        }
        promptBox.addView(enhanceButton)
        promptBox.addView(TextView(this).apply {
            text = "🎲"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
            contentDescription = "Surprise me"
            setOnClickListener { prompt.setText(Ideas.next(this@ImageMakerActivity)); prompt.setSelection(prompt.text.length) }
        })
        root.addView(promptBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad * 3 / 4 })

        generate = Button(this).apply {
            text = "✨  Generate"; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; stateListAnimator = null
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(26f).toFloat() }
            setOnClickListener { if (drawing) { ImageJob.stop(this@ImageMakerActivity); show() } else start() }
        }
        root.addView(generate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = pad * 3 / 4 })

        // the picture card: a thin frame that turns into a spinning rainbow glow while drawing (GlowCard),
        // with a big square preview that the screen scrolls down to
        val card = GlowCard(this, cardColor, dp(20f).toFloat(), dp(1.5f).toFloat()).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad + dp(4f), pad + dp(4f), pad + dp(4f), pad + dp(4f))
        }
        glowCard = card
        val square = SquareFrame(this)
        placeholder = TextView(this).apply { text = "🖼️"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); gravity = Gravity.CENTER; alpha = 0.6f }
        square.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
            contentDescription = "Tap to see it full screen"
            setOnClickListener { result?.let { PictureViewer.show(this@ImageMakerActivity, it) } }
        }
        square.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        magic = MagicView(this).apply { visibility = View.GONE }
        square.addView(magic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(square, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(purple)
        }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Your picture appears here"
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.75f
            setPadding(0, pad / 2, 0, 0)
        }
        card.addView(status)
        stageList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, pad / 2, 0, 0) }
        for (name in ImageJob.STAGES) stageList.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(2f), 0, dp(2f))
        })
        card.addView(stageList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })

        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, pad * 3 / 4, 0, 0) }
        for ((label, act) in listOf<Pair<String, () -> Unit>>("📤 Send" to ::send, "💾 Save" to ::save, "✂️ Sticker" to ::makeSticker)) {
            actions.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = UiKit.rounded(this@ImageMakerActivity, 22f, cardColor, lineColor)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginStart = pad / 4; marginEnd = pad / 4 })
        }
        root.addView(actions)

        // the gallery: every picture made, newest first, three across
        galleryTitle = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(4f), pad * 3 / 2, 0, pad / 2)
        }
        root.addView(galleryTitle)
        galleryGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(galleryGrid)

        // theme colours for the page and all its text
        root.setBackgroundColor(theme.bg)
        window.decorView.setBackgroundColor(theme.bg)
        window.statusBarColor = theme.bg; window.navigationBarColor = theme.bg
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (theme.dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        tint(root)
        refresh()
        // the whole screen scrolls, so the preview can be big
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroller)
        scroller.fitAboveKeyboard(includeTop = true)
        // no keyboard until the prompt box is tapped (Jarrod, test 157)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root.isFocusableInTouchMode = true; root.requestFocus()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** A tappable card with a small caption and the current choice. Returns the card and the text showing the choice. */
    private fun dropdown(caption: String, onClick: (View) -> Unit): Pair<View, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(9f), dp(12f), dp(10f))
            background = UiKit.rounded(this@ImageMakerActivity, 16f, cardColor, lineColor)
        }
        card.addView(TextView(this).apply { text = caption; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); alpha = 0.6f; letterSpacing = 0.08f })
        val value = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(value)
        card.setOnClickListener { onClick(it) }
        return card to value
    }

    /** A dropdown list under [anchor]: icon, name, a short line about it, and a tick on the current one. */
    private fun sheet(anchor: View, rows: List<Triple<String, String, String>>, selected: Int, onPick: (Int) -> Unit) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, dp(6f))
            background = UiKit.rounded(this@ImageMakerActivity, 16f, if (night) Color.rgb(44, 45, 51) else Color.WHITE, lineColor)
        }
        val popup = android.widget.PopupWindow(list, maxOf(anchor.width, dp(280f)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        rows.forEachIndexed { i, (icon, name, sub) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                if (i == selected) background = UiKit.rounded(this@ImageMakerActivity, 12f, Color.argb(40, Color.red(purple), Color.green(purple), Color.blue(purple)))
                setOnClickListener { popup.dismiss(); onPick(i) }
            }
            row.addView(TextView(this).apply { text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(0, 0, dp(12f), 0) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            texts.addView(TextView(this).apply { text = sub; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == selected) row.addView(TextView(this).apply { text = "✓"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(purple) })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f); marginEnd = dp(6f) })
        }
        popup.elevation = dp(8f).toFloat()
        popup.showAsDropDown(anchor, 0, dp(6f))
    }

    private fun pickModel(anchor: View) {
        val rows = ImageGen.MODELS.map { m ->
            Triple(if (m === ImageGen.QUICK) "⚡" else "💎", "${m.name} · ${m.label}",
                m.detail + if (ImageGen.downloaded(this, m)) "" else " · not downloaded yet")
        }
        sheet(anchor, rows, ImageGen.MODELS.indexOf(model)) { i ->
            model = ImageGen.MODELS[i]; prefs.edit().putString("model", model.id).apply(); refresh()
        }
    }

    private fun pickStyle(anchor: View) {
        sheet(anchor, styles.map { Triple(it.icon, it.name, it.sub) }, style) { i ->
            style = i; prefs.edit().putInt("style", i).apply(); refresh()
        }
    }

    /**
     * Prompt enhancer: the keyboard's writing helper (Qwen2.5 1.5B, on the phone) turns a few words into a
     * detailed picture description, which these models draw much better. Tap 🪄 again to undo.
     */
    private var beforeEnhance: String? = null
    private fun enhance() {
        if (enhancing || working) return
        val words = prompt.text.toString().trim()
        beforeEnhance?.let { old -> if (words == lastEnhanced) { prompt.setText(old); prompt.setSelection(old.length); beforeEnhance = null; status.text = "Back to your words."; return } }
        if (words.isEmpty()) { status.text = "Type a few words first, then tap 🪄 to make them more detailed."; return }
        SafePrompt.check(words)?.let { status.text = it; return }
        if (!helium314.keyboard.latin.WritingTools.modelReady(this)) {
            status.text = "🪄 uses the keyboard's Writing help model. Open Writing help from the keyboard menu once to download it (1.1 GB), then come back."
            return
        }
        enhancing = true
        val styleName = styles[style].name
        val best = model === ImageGen.BEST
        // show it's working: the wand waves, the status line animates, then the words appear in the box as they're written
        val wave = android.animation.ObjectAnimator.ofFloat(enhanceButton, View.ROTATION, -25f, 25f).apply {
            duration = 350; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start()
        }
        prompt.isEnabled = false
        var writing = false
        var tick = 0
        val sparkles = listOf("✨", "💫", "⭐", "🌟")
        val animateStatus = object : Runnable {
            override fun run() {
                if (!enhancing) return
                val dots = ".".repeat(tick % 4)
                val what = if (!writing) "Waking up the writing helper" else if (best) "Writing a detailed description" else "Adding detail"
                status.text = "${sparkles[tick % sparkles.size]} $what$dots"
                tick++
                status.postDelayed(this, 350)
            }
        }
        animateStatus.run()
        thread(name = "image-maker-enhance") {
            val out = runCatching {
                kotlinx.coroutines.runBlocking {
                    helium314.keyboard.latin.WritingTools.withEngine(this@ImageMakerActivity) { engine ->
                        engine.startFresh()
                        engine.setSystemPrompt(if (best) ENHANCE_BEST else ENHANCE_QUICK)
                        val sb = StringBuilder()
                        val style = if (styleName == "None") "" else " The picture style will be: $styleName."
                        var lastShown = 0L
                        engine.sendUserPrompt("Idea: $words$style", if (best) 220 else 110).collect {
                            sb.append(it)
                            writing = true
                            val now = System.currentTimeMillis()
                            if (now - lastShown > 120) {
                                lastShown = now
                                val sofar = sb.toString().trim().replace('\n', ' ').removePrefix("Description:").trim()
                                if (!sofar.contains("BLOCKED")) runOnUiThread { if (enhancing) { prompt.setText(sofar + " ▍"); prompt.setSelection(prompt.text.length) } }
                            }
                        }
                        sb.toString()
                    }.also { helium314.keyboard.latin.WritingTools.unloadNow(this@ImageMakerActivity) }
                }
            }.getOrNull()?.let { cleanEnhanced(it) }
            runOnUiThread {
                enhancing = false
                wave.cancel(); enhanceButton.rotation = 0f
                status.removeCallbacks(animateStatus)
                prompt.isEnabled = true
                fun giveBack() { prompt.setText(words); prompt.setSelection(words.length) }
                if (out.isNullOrBlank() || out.contains("BLOCKED")) { giveBack(); status.text = "🪄 couldn't improve that one, try again."; return@runOnUiThread }
                SafePrompt.check(out)?.let { giveBack(); status.text = it; return@runOnUiThread }
                beforeEnhance = words; lastEnhanced = out
                // show it from the start so it can be read top to bottom
                prompt.setText(out); prompt.setSelection(0); prompt.scrollTo(0, 0)
                status.text = "🪄 Done. Scroll the box to read it all, edit it if you like, or tap 🪄 again to go back to your words."
            }
        }
    }
    private var lastEnhanced: String? = null

    private fun cleanEnhanced(raw: String): String {
        var t = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        for (p in listOf("Example description:", "Description:", "Prompt:")) t = t.removePrefix(p).trim()
        t = t.trim('"').trim()
        // a cut-off last sentence reads oddly: end on the last full stop when there is one
        val end = t.lastIndexOf('.')
        if (end > t.length / 2) t = t.substring(0, end + 1)
        return t.take(1500)
    }

    private fun pickSpeed(speed: Int) {
        val (title, text) = when (speed) {
            0 -> "🌿 Smooth" to "Uses about 40% of the graphics chip and runs at low priority, so your phone stays smooth while it draws.\n\n" +
                "Slowest: about 5 to 6 minutes for a picture with the graphics chip."
            1 -> "⚖️ Balanced" to "Uses about 80% of the graphics chip. You can still use your phone, but it may feel slow or glitchy until it's done.\n\n" +
                "About 3½ minutes for a picture with the graphics chip."
            else -> "🚀 Fastest" to "Uses almost all of your phone's power (around 90%) to draw the picture.\n\n" +
                "While it's drawing, don't use other apps, or your phone may freeze up until it's done.\n\n" +
                "Quickest: about 2¾ minutes for a picture with the graphics chip."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text + "\n\nWith the main processor instead, every choice takes roughly twice as long.")
            .setPositiveButton("Use ${title.substringAfter(' ')}") { _, _ -> ImageJob.setSpeed(this, speed); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        stepRow.visibility = if (model === ImageGen.BEST) View.VISIBLE else View.GONE
        modelValue.text = (if (model === ImageGen.QUICK) "⚡ " else "💎 ") + model.name + "  ▾"
        styleValue.text = styles[style].icon + " " + styles[style].name + "  ▾"
        engineRow.visibility = if (model === ImageGen.BEST && gpuName.isNotEmpty()) View.VISIBLE else View.GONE
        speedRow.visibility = if (model === ImageGen.BEST) View.VISIBLE else View.GONE
        val speed = ImageJob.speed(this)
        speedButtons.forEachIndexed { i, b ->
            val on = i == speed
            b.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(17f).toFloat() } else null
            b.setTextColor(if (on) Color.WHITE else prompt.currentTextColor)
        }
        val gpuOn = ImageJob.gpuOn(this)
        engineButtons.forEachIndexed { i, b ->
            val on = (i == 0) == gpuOn
            b.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(17f).toFloat() } else null
            b.setTextColor(if (on) Color.WHITE else prompt.currentTextColor)
        }
        stepButtons.forEachIndexed { i, b ->
            val on = listOf(4, 8)[i] == steps
            b.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(17f).toFloat() } else null
            b.setTextColor(if (on) Color.WHITE else prompt.currentTextColor)
        }
    }

    private val drawing get() = ImageJob.running
    private val ticker = object : Runnable {
        override fun run() { if (ImageJob.running) { show(); generate.postDelayed(this, 1000) } }
    }

    private fun start() {
        if (working) return
        val words = prompt.text.toString().trim()
        if (words.isEmpty()) { status.text = "Type what you'd like a picture of first."; return }
        // put the keyboard away so the picture area shows
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(prompt.windowToken, 0)
        prompt.clearFocus()
        SafePrompt.check(words)?.let { status.text = it; return }
        val m = model
        if (!ImageGen.downloaded(this, m)) { askDownload(m); return }
        if (m !== ImageGen.QUICK && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5) }
        ImageJob.start(this, m, words + styles[style].suffix, steps, words)
        show()
    }

    /** Shows where the current (or last) picture is up to. */
    private fun show() {
        val job = ImageJob
        working = job.running
        generate.removeCallbacks(ticker)
        glowCard.magic = job.running
        magic.running = job.running
        if (job.running) {
            picture.visibility = View.GONE; placeholder.visibility = View.GONE
            val quick = job.model === ImageGen.QUICK
            generate.text = if (job.stopping) "Stopping…" else "■  Stop"
            generate.isEnabled = !job.stopping
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = job.stageIndex < 0
            progress.progress = job.overall(this)
            val secs = (System.currentTimeMillis() - job.startedAt) / 1000
            val left = job.secondsLeft(this)
            status.text = (if (job.note.isNotEmpty()) job.note + "\n" else "") + (if (left > 0) "About ${job.fmtLeft(left)} left  ·  " else if (!quick) "Working out how long…  ·  " else "") +
                "${secs / 60}:%02d".format(secs % 60) +
                if (!quick) (when (ImageJob.speed(this)) { 2 -> "\nFastest: your phone may be hard to use until it's done."; 1 -> "\nBalanced: your phone may feel slow until it's done."; else -> "\nYou can switch apps, it keeps going." }) else ""
            stageList.visibility = if (quick) View.GONE else View.VISIBLE
            for (i in ImageJob.STAGES.indices) {
                val tv = stageList.getChildAt(i) as TextView
                val name = ImageJob.STAGES[i] + if (i == 2 && job.usingGpu) " on the graphics chip" else ""
                val (mark, text) = when {
                    i in job.skipped -> "✓" to "$name (already open)"
                    i < job.stageIndex -> "✓" to name
                    i == job.stageIndex -> "●" to (if (i == 2 && job.total > 0) "$name, step ${minOf(job.step + 1, job.total)} of ${job.total}" else "$name…")
                    else -> "○" to name
                }
                tv.text = "$mark  $text"
                tv.alpha = if (i == job.stageIndex) 1f else if (i < job.stageIndex || i in job.skipped) 0.7f else 0.4f
                tv.setTypeface(null, if (i == job.stageIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tv.setTextColor(if (i == job.stageIndex) purple else status.currentTextColor)
            }
            generate.postDelayed(ticker, 1000)
            return
        }
        generate.isEnabled = true
        progress.visibility = View.GONE
        stageList.visibility = View.GONE
        val r = job.result
        if (r != null) {
            result = r
            picture.setImageBitmap(r); picture.visibility = View.VISIBLE; placeholder.visibility = View.GONE
            actions.visibility = View.VISIBLE
            generate.text = "✨  Make another"
            val secs = job.tookMs / 1000
            if (job.tookMs == 0L) status.text = "From your gallery. Tap it to see it full screen." else
            status.text = "Done in ${if (secs >= 60) "${secs / 60} min ${secs % 60} s" else "$secs s"}. Tap it to see it full screen. Not quite right? Make another."
        } else if (job.error != null) {
            if (result == null) placeholder.visibility = View.VISIBLE else picture.visibility = View.VISIBLE
            generate.text = "✨  Generate"
            status.text = job.error
        }
        loadGallery()
    }

    /** Text and hint colours from the theme, for every text view on the page (buttons with their own colour excepted). */
    private fun tint(v: View) {
        when (v) {
            is Button -> {}
            is EditText -> { v.setTextColor(theme.text); v.setHintTextColor((theme.text and 0x00FFFFFF) or 0x80000000.toInt()) }
            is TextView -> v.setTextColor(theme.text)
            is ViewGroup -> for (i in 0 until v.childCount) tint(v.getChildAt(i))
        }
    }

    /** Fills the gallery grid; only redone when the pictures changed. */
    private fun loadGallery() {
        val items = Gallery.items(this)
        val key = items.size.toString() + (items.firstOrNull()?.file?.name ?: "")
        if (key == galleryShown) return
        galleryShown = key
        galleryTitle.text = if (items.isEmpty()) "🖼️ Your pictures" else "🖼️ Your pictures (${items.size})"
        galleryGrid.removeAllViews()
        if (items.isEmpty()) {
            galleryGrid.addView(TextView(this).apply {
                text = "Pictures you make show up here."; alpha = 0.6f; setPadding(dp(4f), 0, 0, 0); setTextColor(theme.text)
            })
            return
        }
        val gap = dp(6f)
        val cells = mutableListOf<Pair<ImageView, Gallery.Item>>()
        items.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val cell = SquareFrame(this)
                val item = rowItems.getOrNull(k)
                if (item != null) {
                    val img = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                        background = UiKit.rounded(this@ImageMakerActivity, 12f, cardColor)
                        setOnClickListener { openFromGallery(item) }
                        setOnLongClickListener { galleryMenu(item); true }
                    }
                    cell.addView(img, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    cells.add(img to item)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (k > 0) marginStart = gap / 2
                    if (k < 2) marginEnd = gap / 2
                })
            }
            galleryGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
        }
        // small copies, read off the main thread
        thread(name = "image-maker-gallery") {
            for ((img, item) in cells) {
                val t = item.thumb(dp(120f)) ?: continue
                runOnUiThread { img.setImageBitmap(t) }
            }
        }
    }

    private fun openFromGallery(item: Gallery.Item) {
        if (working) return
        val bmp = item.full() ?: return
        ImageJob.pick(bmp)
        item.words.takeIf { it.isNotBlank() }?.let { prompt.setText(it); beforeEnhance = null }
        show()
        (picture.parent as View).let { v -> (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0).let { sc -> if (sc is ScrollView) sc.smoothScrollTo(0, (v.parent as View).top) } }
    }

    private fun galleryMenu(item: Gallery.Item) {
        AlertDialog.Builder(this)
            .setTitle("This picture")
            .setItems(arrayOf("🔍 See it full screen", "✏️ Use its words again", "🗑️ Delete")) { _, which ->
                when (which) {
                    0 -> item.full()?.let { PictureViewer.show(this, it) }
                    1 -> { prompt.setText(item.words); prompt.setSelection(prompt.text.length) }
                    2 -> AlertDialog.Builder(this).setMessage("Delete this picture from your gallery?")
                        .setPositiveButton("Delete") { _, _ -> item.delete(); loadGallery() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }

    private fun pickTheme() {
        val names = ImageThemes.ALL.mapIndexed { i, t -> (if (i == 0) "📱 " else if (t.dark) "🌙 " else "☀️ ") + t.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎨 Themes")
            .setSingleChoiceItems(names, ImageThemes.index(this)) { d, which ->
                ImageThemes.set(this, which); d.dismiss(); recreate()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openSettings() {
        val labels = Gallery.LIMITS.map { it.second }.toTypedArray()
        val now = Gallery.LIMITS.indexOfFirst { it.first == Gallery.limit(this) }
        AlertDialog.Builder(this)
            .setTitle("⚙️ Gallery storage: how many pictures to keep")
            .setSingleChoiceItems(labels, now) { d, which ->
                val n = Gallery.LIMITS[which].first
                val would = Gallery.items(this).size - (if (n <= 0) Int.MAX_VALUE else n)
                fun apply() { Gallery.setLimit(this, n); galleryShown = ""; loadGallery(); d.dismiss() }
                if (would > 0) AlertDialog.Builder(this)
                    .setMessage("That deletes your $would oldest picture${if (would == 1) "" else "s"} now. Pictures you saved to your phone's Pictures folder aren't touched.")
                    .setPositiveButton("OK") { _, _ -> apply() }.setNegativeButton("Cancel", null).show()
                else apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHelp() {
        val text = """
✨ Making a picture
Type what you'd like to see in the box, then tap Generate. Everything happens on your phone: nothing is sent anywhere.

⚡ Quick or 💎 Z-Image Turbo
Quick makes a picture in seconds, but it's simple. Z-Image Turbo makes much better pictures but takes a few minutes. Pick one in MODEL. Each downloads once the first time you use it.

📷 Style
Adds a look to your words, like Photo, Cartoon or Painting.

4 or 8 steps
More steps take longer but look sharper.

🎮 Graphics chip or 🧠 Main processor
The graphics chip is about twice as fast. If it ever crashes, the app switches to the main processor by itself.

🌿 Smooth · ⚖️ Balanced · 🚀 Fastest
How much of your phone's power to use. Smooth keeps your phone smooth but takes longest. Fastest is quickest, but don't use other apps while it draws. Tap one to see details.

🪄 Improve my words
Turns a few words into a detailed description that the model draws much better. It fills in anything you didn't say. Tap 🪄 again to go back to your own words. It needs the keyboard's Writing help model.

🎲 Surprise me
Gives you a new random idea every time.

✕ Clear
Empties the box so you can start a new idea.

🖼️ Your picture
Tap the picture to see it full screen (pinch or double-tap to zoom). Then:
• 📤 Send puts it into the chat you came from.
• 💾 Save copies it to Pictures › Open Keyboard.
• ✂️ Sticker cuts it out to make a sticker.

🖼️ Your pictures (gallery)
Every picture you make is kept here, newest first. Tap one to bring it back with its words. Press and hold one to see it full screen, reuse its words, or delete it. ⚙️ Settings chooses how many to keep. Once that many are stored, the oldest are replaced.

🎨 Themes
20 colour themes for this screen.

🛡️ Safety
Local Image won't draw nude, sexual or gory pictures. Some words are blocked, and every finished picture is checked on your phone before it's shown.

⏳ While it draws
You can leave the screen or switch apps. A notification shows how it's going and opens the picture when it's ready. Tap Stop to cancel.
""".trim()
        val tv = TextView(this).apply { this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(22f), dp(8f), dp(22f), dp(8f)); setLineSpacing(0f, 1.15f) }
        AlertDialog.Builder(this)
            .setTitle("ℹ️ How Local Image works")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        ImageJob.listener = { show() }
        show()
    }

    override fun onPause() {
        super.onPause()
        ImageJob.listener = null
        generate.removeCallbacks(ticker)
    }

    private fun askDownload(m: ImageGen.Model) {
        // only what's still missing (e.g. just the safety check after an update), not the whole model again
        val need = m.parts.filter { ImageGen.file(this, it).length() != it.size }.sumOf { it.size }.takeIf { it > 0 } ?: m.totalSize
        val size = if (need > 1_000_000_000L) "%.1f GB".format(need / 1e9) else "%d MB".format(need / 1_000_000)
        AlertDialog.Builder(this)
            .setTitle("Download \"${m.label}\"?")
            .setMessage("It's a one-time download of about $size, so Wi-Fi is best. After that it works without internet.\n\n" + m.about +
                if (m === ImageGen.BEST) "\n\nIt needs plenty of free memory and takes a few minutes per picture." else "")
            .setPositiveButton("Download") { _, _ -> download(m) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun download(m: ImageGen.Model) {
        working = true
        generate.isEnabled = false
        progress.isIndeterminate = false; progress.progress = 0; progress.visibility = View.VISIBLE
        status.text = "Downloading… keep this screen open."
        thread(name = "image-maker-download") {
            val r = runCatching { ImageGen.download(this, m) { f -> runOnUiThread { progress.progress = (f * 1000).toInt(); status.text = "Downloading… %d%%".format((f * 100).toInt()) } } }
            runOnUiThread {
                working = false
                generate.isEnabled = true
                progress.visibility = View.GONE
                r.onSuccess { status.text = "Ready."; start() }
                    .onFailure { status.text = "Download stopped (${it.message}). Tap Generate to carry on from where it got to." }
            }
        }
    }

    private fun saveTemp(): File? {
        val bmp = result ?: return null
        val dir = File(cacheDir, "media").apply { mkdirs() }
        return File(dir, "made_picture_${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun send() {
        val f = saveTemp() ?: return
        if (!f.renameTo(MediaSender.pendingFile(this))) return
        Toast.makeText(this, "Sending when you're back in the chat", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun save() {
        val bmp = result ?: return
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OpenKeyboard_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Open Keyboard")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        Toast.makeText(this, if (ok) "Saved to Pictures › Open Keyboard" else "Couldn't save it", Toast.LENGTH_SHORT).show()
    }

    private fun makeSticker() {
        val f = saveTemp() ?: return
        startActivity(Intent(this, StickerMakerActivity::class.java).putExtra(StickerMakerActivity.EXTRA_IMAGE, f.path))
    }

    override fun onDestroy() {
        super.onDestroy()
        // free the model's memory unless a picture is still being drawn
        if (!ImageJob.running && isFinishing) thread { ImageGen.close() }
    }
}
