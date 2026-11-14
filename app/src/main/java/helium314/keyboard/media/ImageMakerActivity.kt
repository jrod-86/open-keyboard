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
import android.widget.HorizontalScrollView
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
    "120 to 180 words, in this order: first the main subject with specific details (age, build, face, hair, expression, " +
    "clothing, colours and materials); then what they are doing and their pose; then the setting and background with a few " +
    "concrete objects; then the lighting, time of day and weather; then the camera: shot type, angle, lens and depth of field; " +
    "last the style and mood. Keep everything the user asked for. Wherever the user didn't say, choose fitting, specific " +
    "details yourself. Describe only what should be in the picture, never what should not. Only put writing in the picture " +
    "if the user asked for it, with the exact words in quotes. " +
    "Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, reply only BLOCKED. " +
    "Reply with only the description, in English, as one paragraph.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: A confident woman in her early thirties with shoulder-length wavy auburn hair, light freckles and " +
    "a warm half smile stands beside a glossy cherry-red sports car. She wears a fitted cream trench coat over a black " +
    "turtleneck, slim dark jeans and brown leather ankle boots, one hand resting lightly on the car's curved front wing. The " +
    "car has sleek low lines, chrome-rimmed wheels and a faint reflection of the sky in its polished paint. They are parked " +
    "on a quiet cobblestone street lined with old stone buildings, flower boxes and a small corner cafe with striped awnings. " +
    "Golden late-afternoon sunlight comes from the side, casting long soft shadows and a warm glow on her face. Medium " +
    "full-length shot at eye level, 50mm lens, shallow depth of field with the background gently blurred. Realistic photo, " +
    "rich natural colours, relaxed and stylish mood."

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

    private val ideas = listOf(
        "tuxedo cat eating pizza", "kangaroo surfing at sunset", "a koala astronaut on the moon", "a robot making pancakes",
        "a cosy cabin in the snow at night", "a dragon drinking bubble tea", "a corgi wearing sunglasses at the beach",
        "a tiny house inside a teacup", "a penguin DJ at a party", "a lighthouse in a storm", "a fox reading a book under a tree",
        "an owl wizard casting a spell",
    )

    private val night get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val purple = Color.rgb(142, 84, 233)
    private val blue = Color.rgb(66, 133, 244)
    private val cardColor get() = if (night) Color.rgb(36, 37, 42) else Color.rgb(242, 243, 247)
    private val lineColor get() = if (night) Color.rgb(64, 66, 74) else Color.rgb(216, 218, 226)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        model = ImageGen.MODELS.firstOrNull { it.id == prefs.getString("model", "quick") } ?: ImageGen.QUICK
        steps = prefs.getInt("steps", 4)
        style = prefs.getInt("style", 0).coerceIn(0, styles.size - 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        // title with a purple-to-blue shine
        root.addView(TextView(this).apply {
            text = "✨ Image maker"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6f), 0, 0)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                (v as TextView).paint.shader = android.graphics.LinearGradient(0f, 0f, v.width.toFloat(), 0f, purple, blue, android.graphics.Shader.TileMode.CLAMP)
            }
        })
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
            maxLines = 6; background = null
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
            setOnClickListener { prompt.setText(ideas.random()); prompt.setSelection(prompt.text.length) }
        })
        root.addView(promptBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad * 3 / 4 })

        generate = Button(this).apply {
            text = "✨  Generate"; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; stateListAnimator = null
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(26f).toFloat() }
            setOnClickListener { if (drawing) { ImageJob.stop(this@ImageMakerActivity); show() } else start() }
        }
        root.addView(generate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = pad * 3 / 4 })

        // the picture card, with a thin purple-to-blue frame
        val frame = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(purple, blue)).apply { cornerRadius = dp(20f).toFloat() }
        val inner = UiKit.rounded(this, 18.5f, cardColor)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.LayerDrawable(arrayOf(frame, inner)).apply { setLayerInset(1, dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f)) }
            setPadding(pad, pad, pad, pad)
        }
        placeholder = TextView(this).apply { text = "🖼️"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); gravity = Gravity.CENTER; alpha = 0.6f }
        card.addView(placeholder)
        picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
            contentDescription = "Tap to see it full screen"
            setOnClickListener { result?.let { PictureViewer.show(this@ImageMakerActivity, it) } }
        }
        card.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad })

        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, pad * 3 / 4, 0, 0) }
        for ((label, act) in listOf<Pair<String, () -> Unit>>("📤 Send" to ::send, "💾 Save" to ::save, "✂️ Sticker" to ::makeSticker)) {
            actions.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = UiKit.rounded(this@ImageMakerActivity, 22f, cardColor, lineColor)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginStart = pad / 4; marginEnd = pad / 4 })
        }
        root.addView(actions)
        refresh()
        setContentView(root)
        root.fitAboveKeyboard(includeTop = true)
        prompt.requestFocus()
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
        enhanceButton.alpha = 0.4f
        val styleName = styles[style].name
        val best = model === ImageGen.BEST
        status.text = if (best) "🪄 Writing a long, detailed description (about half a minute)…" else "🪄 Making your words more detailed…"
        thread(name = "image-maker-enhance") {
            val out = runCatching {
                kotlinx.coroutines.runBlocking {
                    helium314.keyboard.latin.WritingTools.withEngine(this@ImageMakerActivity) { engine ->
                        engine.startFresh()
                        engine.setSystemPrompt(if (best) ENHANCE_BEST else ENHANCE_QUICK)
                        val sb = StringBuilder()
                        val style = if (styleName == "None") "" else " The picture style will be: $styleName."
                        engine.sendUserPrompt("Idea: $words$style", if (best) 360 else 110).collect { sb.append(it) }
                        sb.toString()
                    }.also { helium314.keyboard.latin.WritingTools.unloadNow(this@ImageMakerActivity) }
                }
            }.getOrNull()?.let { cleanEnhanced(it) }
            runOnUiThread {
                enhancing = false; enhanceButton.alpha = 1f
                if (out.isNullOrBlank() || out.contains("BLOCKED")) { status.text = "🪄 couldn't improve that one, try again."; return@runOnUiThread }
                SafePrompt.check(out)?.let { status.text = it; return@runOnUiThread }
                beforeEnhance = words; lastEnhanced = out
                prompt.setText(out); prompt.setSelection(out.length)
                status.text = "🪄 Done. Edit it if you like, or tap 🪄 again to go back to your words."
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
        SafePrompt.check(words)?.let { status.text = it; return }
        val m = model
        if (!ImageGen.downloaded(this, m)) { askDownload(m); return }
        if (m !== ImageGen.QUICK && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5) }
        ImageJob.start(this, m, words + styles[style].suffix, steps)
        show()
    }

    /** Shows where the current (or last) picture is up to. */
    private fun show() {
        val job = ImageJob
        working = job.running
        generate.removeCallbacks(ticker)
        if (job.running) {
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
            status.text = "Done in ${if (secs >= 60) "${secs / 60} min ${secs % 60} s" else "$secs s"}. Tap it to see it full screen. Not quite right? Make another."
        } else if (job.error != null) {
            generate.text = "✨  Generate"
            status.text = job.error
        }
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
        val size = if (m.totalSize > 1_000_000_000L) "%.1f GB".format(m.totalSize / 1e9) else "%d MB".format(m.totalSize / 1_000_000)
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
