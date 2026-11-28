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
internal const val ENHANCE_BEST = "You write prompts for an image generator that draws best from long, detailed descriptions " +
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
internal const val ENHANCE_QUICK = "You write prompts for a small, fast image generator that only reads about 50 words. Turn the " +
    "user's idea into one picture description of 30 to 45 words, as short descriptive phrases separated by commas, most " +
    "important first: the main subject and what it looks like, what it is doing, the setting, the lighting, the colours, " +
    "the style. Keep everything the user asked for and choose fitting details where they didn't say. Don't add writing to " +
    "the picture. Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, " +
    "reply only BLOCKED. Reply with only the description, in English, on one line.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: smiling woman with wavy auburn hair in a cream trench coat, leaning on a glossy red sports car, " +
    "cobblestone street, old stone buildings, golden hour sunlight, soft shadows, warm colours, realistic photo, sharp focus"

// 🧪 FLUX.2-klein (Qwen3 text reader). Black Forest Labs' guide: write "like a novelist, not a search engine": flowing
// prose, most important first (subject, action, style, setting, details), lighting matters most; 30-80 words for most
// pictures; the prompt is used literally (no rewriting), so every sentence should add something visible.
internal const val ENHANCE_KLEIN = "You write prompts for an image generator that reads natural prose, like a novelist " +
    "describing a scene, not a list of keywords. Turn the user's idea into one description of a single picture, 50 to 70 " +
    "words, most important first: the main subject with specific details (look, clothing, colours, materials) and what they " +
    "are doing; then the setting with a few concrete objects; then the lighting, which matters most, described precisely " +
    "(light source, direction, quality, time of day); then the camera or medium and the mood. Keep everything the user asked " +
    "for, and choose fitting, specific details where they didn't say. Every phrase must add something you can see: no filler " +
    "such as \"beautiful\", \"stunning\" or \"high quality\". Describe only what should be in the picture, never what should " +
    "not. Only put writing in the picture if the user asked for it, with the exact words in quotes. " +
    "Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, reply only BLOCKED. " +
    "Reply with only the description, in English, as one paragraph.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: A confident woman with wavy auburn hair and a cream trench coat leans against a glossy cherry-red " +
    "sports car on a quiet cobblestone street, old stone buildings and a small corner cafe behind her. Low golden sunlight " +
    "from the left rakes across the car's curves and casts long soft shadows. Shot on a 50mm lens at eye level, shallow " +
    "depth of field, warm and relaxed mood."

// 🧪 DreamShaper 8 LCM (Stable Diffusion 1.5, CLIP text reader): treats the prompt as a "bag of words" and only reads the
// first ~75 word-pieces; its own example is comma-separated phrases with look words ("intricate, sharp focus, soft lighting").
// Drawn with LCM at guidance 1, so there's no negative prompt.
internal const val ENHANCE_DREAM = "You write prompts for a Stable Diffusion 1.5 image generator that reads a list of short " +
    "phrases and only the first 60 or so words. Turn the user's idea into 35 to 50 words of short descriptive phrases " +
    "separated by commas, most important first: the medium (for example portrait photo, digital painting, 3D render), the " +
    "main subject and what it looks like, what it is doing, the setting, then lighting and colour words, then a few look " +
    "words such as intricate, highly detailed, sharp focus, soft lighting, vibrant colors. Keep everything the user asked " +
    "for and choose fitting details where they didn't say. Don't use full sentences and don't add writing to the picture. " +
    "Keep it family-friendly: never describe nudity, sexual content or gore; if the idea asks for any of that, reply only " +
    "BLOCKED. Reply with only the description, in English, on one line.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: portrait photo of a smiling woman with wavy auburn hair, cream trench coat, leaning on a glossy " +
    "red sports car, cobblestone street, old stone buildings, golden hour, soft lighting, light bokeh, intricate, sharp " +
    "focus, vibrant colors"

// 🧪 SDXL-Lightning (SDXL, two CLIP text readers): SDXL "can actually understand what you say", so a short plain sentence
// for the scene, then comma phrases for light and style; still only ~75 word-pieces count. Guidance 0/1: no negative prompt.
internal const val ENHANCE_LIGHTNING = "You write prompts for an SDXL image generator that understands a plain sentence but " +
    "only reads the first 60 or so words. Turn the user's idea into 40 to 55 words: first one clear sentence saying the main " +
    "subject, what it looks like and what it is doing, and where; then short comma-separated phrases for the lighting, the " +
    "camera or medium, the colours and the style. Most important things first. Keep everything the user asked for and " +
    "choose fitting details where they didn't say. No filler words such as \"masterpiece\" or \"best quality\". Only put " +
    "writing in the picture if the user asked for it, with the exact words in quotes. Keep it family-friendly: never describe " +
    "nudity, sexual content or gore; if the idea asks for any of that, reply only BLOCKED. Reply with only the description, " +
    "in English, on one line.\n\n" +
    "Example idea: a woman next to a sports car\n" +
    "Example description: A smiling woman with wavy auburn hair in a cream trench coat leans on a glossy red sports car on " +
    "a cobblestone street lined with old stone buildings, golden hour sunlight, long soft shadows, 50mm photo, shallow depth " +
    "of field, warm natural colours, realistic"

/** The 🪄 instructions and length (in word-pieces) that suit how [m] likes to be prompted. */
internal fun enhanceFor(m: ImageGen.Model): Pair<String, Int> = when (m.id) {
    "best" -> ENHANCE_BEST to 220
    "klein" -> ENHANCE_KLEIN to 160
    "dreamshaper" -> ENHANCE_DREAM to 110
    "lightning" -> ENHANCE_LIGHTNING to 120
    else -> ENHANCE_QUICK to 110
}

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
    private lateinit var journey: JourneyMap
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
        // most popular first
        Style("📷", "Photorealistic", "Looks like a real photo", ", photorealistic photo, realistic, highly detailed, natural light, sharp focus"),
        Style("🎬", "Cinematic", "A still from a movie", ", cinematic film still, dramatic lighting, shallow depth of field, film grain, widescreen composition"),
        Style("🌸", "Anime", "Japanese animation look", ", anime style illustration, vibrant colours, clean line art, detailed"),
        Style("🖥️", "Digital art", "Polished digital painting", ", digital art, detailed digital painting, vivid colours, artstation quality"),
        Style("🧸", "3D animation", "Like an animated family film", ", 3d animated movie style, soft lighting, expressive, cute, high quality render"),
        Style("🐉", "Fantasy art", "Epic, magical worlds", ", epic fantasy art, magical atmosphere, dramatic light, highly detailed illustration"),
        Style("🌃", "Cyberpunk", "Neon city of the future", ", cyberpunk style, neon lights, futuristic city, rain reflections, pink and cyan glow"),
        Style("🏷️", "Logo", "Clean logo design (put any name in \"quotes\")", ", minimalist vector logo design, flat colours, simple bold shapes, clean lines, centered, plain white background"),
        Style("🖍️", "Cartoon", "Bright and playful", ", cartoon illustration, bright colours, bold outlines"),
        Style("🖼️", "Oil painting", "Classic painted canvas", ", oil painting on canvas, visible brush strokes, rich colours, fine art"),
        Style("🎨", "Watercolour", "Painted, soft edges", ", watercolour painting, soft colours, paper texture"),
        Style("💥", "Comic book", "Inked comic panel", ", comic book art, bold ink lines, halftone dots, vibrant colours"),
        Style("🎭", "Concept art", "Game and film design art", ", concept art, painterly, atmospheric, detailed environment design"),
        Style("📸", "Portrait photo", "Studio portrait", ", professional studio portrait photo, soft key light, 85mm lens, blurred background"),
        Style("🎁", "Greeting card", "Festive card illustration", ", greeting card illustration, festive, charming, soft colours, decorative border"),
        Style("📚", "Storybook", "Children's book illustration", ", children's storybook illustration, whimsical, soft gouache colours, gentle"),
        Style("🏷", "Sticker", "Cute, bold outline, white background", ", cute sticker, cartoon, bold white outline, plain white background"),
        Style("👾", "Pixel art", "Retro video game", ", pixel art, 16-bit, retro game style"),
        Style("🏰", "Isometric", "Tiny 3D diorama", ", isometric 3d diorama, miniature, clean, soft shadows, cute"),
        Style("🟣", "Low poly", "Faceted 3D shapes", ", low poly 3d art, geometric facets, soft pastel lighting"),
        Style("🧱", "Claymation", "Handmade clay figures", ", claymation style, plasticine models, handmade texture, stop motion look"),
        Style("✏️", "Pencil sketch", "Hand-drawn graphite", ", detailed pencil sketch, graphite, cross-hatching, white paper"),
        Style("🖋️", "Line art", "Clean black lines", ", clean black line art, minimal, white background, vector illustration"),
        Style("🎞️", "Vintage photo", "Old film photograph", ", vintage 1970s film photo, faded colours, film grain, nostalgic"),
        Style("⚫", "Black & white", "Monochrome photo", ", black and white photograph, high contrast, dramatic shadows"),
        Style("🌅", "Synthwave", "80s neon retro", ", synthwave retro 80s style, neon grid, sunset gradient, purple and pink glow"),
        Style("⚙️", "Steampunk", "Brass and gears", ", steampunk style, brass, copper gears, victorian machinery, warm light"),
        Style("🟡", "Pop art", "Bold comic-print colours", ", pop art style, bold flat colours, halftone, thick outlines"),
        Style("🏛️", "Art deco", "1920s glamour", ", art deco style, gold and black, geometric patterns, elegant poster"),
        Style("🌊", "Ukiyo-e", "Japanese woodblock print", ", ukiyo-e japanese woodblock print, flat colours, textured paper"),
        Style("🌻", "Impressionist", "Soft dabs of colour", ", impressionist painting, loose brush strokes, light and colour, plein air"),
        Style("🌀", "Surreal", "Dreamlike and strange", ", surreal dreamlike art, impossible scene, soft light, highly detailed"),
        Style("🦇", "Dark fantasy", "Gothic and moody", ", dark fantasy art, gothic, moody lighting, mist, dramatic"),
        Style("🪟", "Stained glass", "Glowing coloured glass", ", stained glass window art, bold lead lines, glowing colours"),
        Style("📄", "Paper craft", "Cut paper layers", ", layered paper craft art, cut paper, soft shadows, handmade"),
        Style("🎌", "Chibi", "Tiny cute characters", ", chibi style, big head, tiny body, cute, kawaii, pastel colours"),
        Style("💀", "Tattoo", "Tattoo flash design", ", tattoo flash design, bold lines, traditional tattoo style, limited colours"),
        Style("🧯", "Graffiti", "Street art spray paint", ", graffiti street art, spray paint, bold colours, urban wall"),
        Style("🔬", "Macro photo", "Extreme close-up", ", macro photography, extreme close-up, fine detail, shallow depth of field"),
        Style("📦", "Product photo", "Clean product shot", ", professional product photography, clean studio background, soft shadows, sharp"),
        Style("◻️", "Minimalist", "Simple and clean", ", minimalist art, simple shapes, lots of empty space, calm colours"),
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
    private lateinit var shapeValue: TextView
    private lateinit var queueValue: TextView
    private lateinit var seedValue: TextView
    private lateinit var galleryGrid: LinearLayout
    /** Hold a picture to start picking several (Jarrod, test 175): these are the picked ones, by file name. */
    private val picked = linkedSetOf<String>()
    private var picking = false
    private lateinit var pickBar: LinearLayout
    private lateinit var pickCount: TextView
    private val pickMarks = mutableListOf<Triple<ImageView, TextView, Gallery.Item>>()
    private var galleryShown = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        model = ImageGen.MODELS.firstOrNull { it.id == prefs.getString("model", "quick") } ?: ImageGen.QUICK
        steps = prefs.getInt("steps", 4)
        // remembered by name, so adding or reordering styles keeps the person's choice ("Photo" is now "Photorealistic")
        style = styles.indexOfFirst { it.name == prefs.getString("style_name", "Photorealistic")!!.let { n -> if (n == "Photo") "Photorealistic" else n } }.coerceAtLeast(0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        // title with a two-colour shine, and the 🎨 themes, ⚙️ settings and ℹ️ help buttons beside it
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(head)
        // the Local Image logo, then the name in the logo's purple → pink → orange
        head.addView(android.widget.ImageView(this).apply {
            setImageResource(helium314.keyboard.latin.R.drawable.local_image_logo); contentDescription = "Local Image logo"
        }, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { rightMargin = dp(8f) })
        head.addView(TextView(this).apply {
            text = "Local Image"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; setPadding(0, dp(2f), 0, 0)
            val logoColours = if (night) intArrayOf(0xFFB38CFF.toInt(), 0xFFE5579B.toInt(), 0xFFFF9A4D.toInt())
                else intArrayOf(0xFF3E1460.toInt(), 0xFFC2337E.toInt(), 0xFFF57A3A.toInt())
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val tw = (v as TextView).paint.measureText("Local Image")
                v.paint.shader = android.graphics.LinearGradient(0f, 0f, tw, 0f, logoColours, floatArrayOf(0.3f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
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
        // picture shape, and the 🌙 queue for lining up many pictures (e.g. overnight)
        val drops2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (shapeCard, shv) = dropdown("SHAPE") { if (!working) pickShape(it) }
        val (queueCard, qv) = dropdown("QUEUE") { if (!ImageQueue.active) openQueue() }
        shapeValue = shv; queueValue = qv
        drops2.addView(shapeCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad / 4 })
        drops2.addView(queueCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad / 4 })
        root.addView(drops2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        // 🎲 a new seed every picture, or 📌 a fixed one so the same words give the same picture (good for comparing models)
        val (seedCard, sdv) = dropdown("SEED") { if (!working) pickSeed(it) }
        seedValue = sdv
        root.addView(seedCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })

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
            setOnClickListener { result?.let { b -> PictureViewer.show(this@ImageMakerActivity, b, listOf(
                "📤 Export" to { export(b); false }, "💾 Save" to { saveBitmap(b); false })) } }
        }
        square.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        magic = MagicView(this).apply { visibility = View.GONE }
        square.addView(magic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the progress map with a little animal walking it, over the colour clouds (Jarrod's idea, test 170)
        journey = JourneyMap(this, purple, blue, theme.text, cardColor).apply { visibility = View.GONE }
        square.addView(journey, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        // shown while picking several pictures: how many, select all, delete, cancel
        pickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            background = UiKit.rounded(this@ImageMakerActivity, 16f, cardColor, lineColor)
        }
        pickCount = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(theme.text) }
        pickBar.addView(pickCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, act) in listOf<Pair<String, () -> Unit>>("☑️ All" to ::pickAll, "🗑️ Delete" to ::deletePicked, "✕" to ::stopPicking)) {
            pickBar.addView(TextView(this).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(theme.text); gravity = Gravity.CENTER
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                setOnClickListener { act() }
            })
        }
        root.addView(pickBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
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
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        // long lists (40+ styles) scroll, at most about two thirds of the screen tall
        val maxH = resources.displayMetrics.heightPixels * 2 / 3
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply {
            addView(list)
            background = UiKit.rounded(this@ImageMakerActivity, 16f, cardColor, lineColor)
        }
        val popup = android.widget.PopupWindow(scroll, maxOf(anchor.width, dp(280f)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        rows.forEachIndexed { i, (icon, name, sub) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                if (i == selected) background = UiKit.rounded(this@ImageMakerActivity, 12f, Color.argb(40, Color.red(purple), Color.green(purple), Color.blue(purple)))
                setOnClickListener { popup.dismiss(); onPick(i) }
            }
            row.addView(TextView(this).apply { text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(0, 0, dp(12f), 0) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(theme.text) })
            texts.addView(TextView(this).apply { text = sub; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f; setTextColor(theme.text) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == selected) row.addView(TextView(this).apply { text = "✓"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(purple) })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f); marginEnd = dp(6f) })
        }
        popup.elevation = dp(8f).toFloat()
        popup.showAsDropDown(anchor, 0, dp(6f))
        // start scrolled to the current choice
        if (selected > 3) scroll.post { list.getChildAt(selected)?.let { scroll.scrollTo(0, it.top - dp(40f)) } }
    }

    private fun pickModel(anchor: View) {
        val rows = ImageGen.MODELS.map { m ->
            Triple(m.icon, "${m.name} · ${m.label}",
                m.detail + if (ImageGen.downloaded(this, m)) "" else " · not downloaded yet")
        }
        sheet(anchor, rows, ImageGen.MODELS.indexOf(model)) { i ->
            model = ImageGen.MODELS[i]; prefs.edit().putString("model", model.id).apply(); refresh()
        }
    }

    private fun pickStyle(anchor: View) {
        sheet(anchor, styles.map { Triple(it.icon, it.name, it.sub) }, style) { i ->
            style = i; prefs.edit().putString("style_name", styles[i].name).apply(); refresh()
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
        val best = model.longPrompt
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
                        engine.setSystemPrompt(enhanceFor(model).first)
                        val sb = StringBuilder()
                        val style = if (styleName == "None") "" else " The picture style will be: $styleName."
                        var lastShown = 0L
                        engine.sendUserPrompt("Idea: $words$style", enhanceFor(model).second).collect {
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
        stepRow.visibility = if (model.steps == 0) View.VISIBLE else View.GONE
        modelValue.text = model.icon + " " + model.name + "  ▾"
        styleValue.text = styles[style].icon + " " + styles[style].name + "  ▾"
        shapeValue.text = ImageShapes.label(this) + "  ▾"
        seedValue.text = (if (prefs.getBoolean("seed_fixed", false)) "📌 Fixed: ${prefs.getLong("seed", 0L)}" else "🎲 Random") + "  ▾"
        val queued = ImageQueue.items(this).size
        queueValue.text = (if (ImageQueue.active) "🌙 Running" else if (queued == 0) "🌙 Set up" else "🌙 $queued waiting") + "  ▾"
        engineRow.visibility = if (model !== ImageGen.QUICK && gpuName.isNotEmpty()) View.VISIBLE else View.GONE
        speedRow.visibility = if (model !== ImageGen.QUICK) View.VISIBLE else View.GONE
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
        val (w, h) = ImageShapes.size(this)
        ImageJob.start(this, m, words + styles[style].suffix, steps, words, w, h)
        show()
    }

    /** Shows where the current (or last) picture is up to. */
    private fun show() {
        val job = ImageJob
        working = job.running
        generate.removeCallbacks(ticker)
        glowCard.magic = job.running
        magic.running = job.running
        val mapOn = job.running && job.model !== ImageGen.QUICK
        // when the picture is done, let the animal reach the 🏁 first, then show the picture
        if (!job.running && journey.running && job.result != null) {
            if (!journey.finishing) journey.finish { journey.running = false; show() }
            magic.running = true; glowCard.magic = true
            return
        }
        journey.running = mapOn
        magic.alpha = if (mapOn) 0.45f else 1f
        if (mapOn) journey.update(job.stageIndex, job.step, if (job.total > 0) job.total else if (job.model.steps > 0) job.model.steps else steps, 0 in job.skipped)
        if (job.running) {
            picture.visibility = View.GONE; placeholder.visibility = View.GONE
            val quick = job.model === ImageGen.QUICK
            generate.text = if (job.stopping) "Stopping…" else if (ImageQueue.active) "■  Stop the queue" else "■  Stop"
            generate.isEnabled = !job.stopping
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = job.stageIndex < 0
            progress.progress = job.overall(this)
            val secs = (System.currentTimeMillis() - job.startedAt) / 1000
            val left = job.secondsLeft(this)
            status.text = (if (ImageQueue.active) "🌙 Queue: picture ${ImageQueue.done + 1} of ${ImageQueue.total}\n" else "") +
                (if (job.note.isNotEmpty()) job.note + "\n" else "") + (if (left > 0) "About ${job.fmtLeft(left)} left  ·  " else if (!quick) "Working out how long…  ·  " else "") +
                "${secs / 60}:%02d".format(secs % 60) +
                if (!quick) (when (ImageJob.speed(this)) { 2 -> "\nFastest: your phone may be hard to use until it's done."; 1 -> "\nBalanced: your phone may feel slow until it's done."; else -> "\nYou can switch apps, it keeps going." }) else ""
            // the map's stops say what's happening now, so the old list stays hidden
            stageList.visibility = View.GONE
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
        refresh()
    }

    private fun pickSeed(anchor: View) {
        val fixed = prefs.getBoolean("seed_fixed", false)
        val last = prefs.getLong("last_seed", -1L)
        // 📌 Fixed is always offered: it keeps the last picture's seed, or picks one now if nothing has been drawn yet
        val keep = if (fixed) prefs.getLong("seed", 0L) else if (last >= 0) last else Random.nextLong(0, 1_000_000_000L)
        val rows = listOf(Triple("🎲", "Random", "A different picture every time"),
            Triple("📌", "Fixed", (if (!fixed && last >= 0) "Keep the last picture's seed, $keep" else "Seed $keep") + " · same words give the same picture"),
            Triple("✏️", "Type a seed", "Any whole number"))
        sheet(anchor, rows, if (fixed) 1 else 0) { i ->
            when (i) {
                0 -> { prefs.edit().putBoolean("seed_fixed", false).apply(); refresh() }
                1 -> { prefs.edit().putBoolean("seed_fixed", true).putLong("seed", keep).apply(); refresh() }
                else -> {
                    val box = EditText(this).apply {
                        setText(if (fixed) prefs.getLong("seed", 0L).toString() else ""); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("📌 Fixed seed")
                        .setMessage("The seed is the starting point for the picture. With the same seed, words, model, style and shape, you get the same picture again.")
                        .setView(box)
                        .setPositiveButton("Use it") { _, _ ->
                            val n = box.text.toString().take(9).toLongOrNull() ?: return@setPositiveButton
                            prefs.edit().putBoolean("seed_fixed", true).putLong("seed", n).apply(); refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun pickShape(anchor: View) {
        val rows = ImageShapes.PRESETS.map { sh -> ImageShapes.size(sh.wr, sh.hr).let { (w, h) -> Triple(sh.icon, sh.name, "${sh.use} · $w×$h") } } +
            Triple("✏️", "Custom", "Any shape you like, e.g. 5:4 or 1:3")
        sheet(anchor, rows, ImageShapes.index(this)) { i ->
            if (i < ImageShapes.PRESETS.size) { ImageShapes.set(this, i); refresh(); return@sheet }
            val (cw, ch) = ImageShapes.custom(this)
            fun num(v: Int) = EditText(this).apply {
                setText(v.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); minWidth = dp(80f)
            }
            val wBox = num(cw); val hBox = num(ch)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(20f), dp(12f), dp(20f), 0)
                addView(LinearLayout(this@ImageMakerActivity).apply { orientation = LinearLayout.VERTICAL; addView(TextView(this@ImageMakerActivity).apply { text = "Width"; gravity = Gravity.CENTER }); addView(wBox) })
                addView(TextView(this@ImageMakerActivity).apply { text = " : "; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) })
                addView(LinearLayout(this@ImageMakerActivity).apply { orientation = LinearLayout.VERTICAL; addView(TextView(this@ImageMakerActivity).apply { text = "Height"; gravity = Gravity.CENTER }); addView(hBox) })
            }
            AlertDialog.Builder(this)
                .setTitle("✏️ Custom shape")
                .setMessage("Type the shape as width : height, like 5 : 4 or 1 : 3. The picture keeps about the same number of pixels, so it takes about the same time.")
                .setView(row)
                .setPositiveButton("Use it") { _, _ ->
                    val w = wBox.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: return@setPositiveButton
                    val h = hBox.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: return@setPositiveButton
                    // keep it to shapes the models can draw: no thinner than 1:4
                    val (fw, fh) = when { w > h * 4 -> 4 to 1; h > w * 4 -> 1 to 4; else -> w to h }
                    ImageShapes.setCustom(this, fw, fh); refresh()
                    val (pw, ph) = ImageShapes.size(fw, fh)
                    status.text = "Shape set to $fw:$fh ($pw × $ph pixels)." + if (fw != w || fh != h) " That was thinner than 1:4, so it's been widened." else ""
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Opens the 🌙 queue sheet; pictures added there use the settings on this screen. */
    private fun openQueue() {
        val (w, h) = ImageShapes.size(this)
        val m = model; val st = styles[style]; val n = if (m.steps > 0) m.steps else steps
        val per = if (m === ImageGen.QUICK) 20L else ((if (ImageJob.gpuOn(this) && gpuName.isNotEmpty()) 240L else 480L) * n / 4)
        val label = "${m.icon} ${m.name} · ${st.name} · " + (if (m === ImageGen.QUICK) "" else "$n steps · ") + ImageShapes.label(this)
        QueueSheet(this, theme, m.longPrompt, enhanceFor(m), st.name, label, per,
            mainWords = { prompt.text.toString() },
            makeItem = { words -> ImageQueue.Item(words, words + st.suffix, m.id, n, w, h) },
            onStart = { startQueue() },
            onClosed = { refresh() },
        ).show()
    }

    private fun startQueue() {
        if (!ImageGen.downloaded(this, model)) { askDownload(model); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5) }
        if (ImageJob.startQueue(this)) show() else status.text = "Couldn't start the queue: is its model downloaded?"
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
        pickMarks.clear()
        picked.retainAll(items.map { it.file.name }.toSet())
        if (items.isEmpty()) {
            stopPicking()
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
                        setOnClickListener { if (picking) togglePick(item) else viewGalleryItem(item) }
                        setOnLongClickListener { if (!picking) { picking = true; picked.clear() }; togglePick(item); true }
                    }
                    cell.addView(img, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    // the tick in the corner while picking
                    val mark = TextView(this).apply {
                        gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Color.WHITE)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    cell.addView(mark, android.widget.FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6f), dp(6f), 0) })
                    pickMarks.add(Triple(img, mark, item))
                    cells.add(img to item)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (k > 0) marginStart = gap / 2
                    if (k < 2) marginEnd = gap / 2
                })
            }
            galleryGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
        }
        showPicks()
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

    /** Tap a gallery picture: see it full screen, with export, save, reuse and delete along the bottom. */
    private fun viewGalleryItem(item: Gallery.Item) {
        // swipe left and right through the whole gallery (Jarrod, test 174), starting at the one tapped
        val items = Gallery.items(this).toMutableList()
        val start = items.indexOfFirst { it.file == item.file }.coerceAtLeast(0)
        if (items.isEmpty()) return
        var pager: PictureViewer.Pager? = null
        pager = PictureViewer.showMany(this, items.size, start, { i -> items.getOrNull(i)?.full() }, listOf(
            "📤 Export" to { i -> items.getOrNull(i)?.full()?.let { export(it) }; false },
            "💾 Save" to { i -> items.getOrNull(i)?.full()?.let { saveBitmap(it) }; false },
            "✏️ Reuse" to { i -> items.getOrNull(i)?.let { openFromGallery(it) }; true },
            "🗑️ Delete" to { i ->
                // asks first; the viewer then shows the next picture (or closes when none are left)
                val it = items.getOrNull(i)
                if (it != null) {
                    AlertDialog.Builder(this).setMessage("Delete this picture from your gallery?")
                        .setPositiveButton("Delete") { _, _ ->
                            it.delete(); items.remove(it); loadGallery(); Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                            pager?.refresh?.invoke()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                false
            },
        ), total = { items.size })
    }

    /** Opens Android's share sheet so the picture can go to any app (messages, email, Drive…). */
    private fun export(bmp: Bitmap) {
        runCatching {
            val dir = File(cacheDir, "media").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("local_image_") }?.forEach { it.delete() }
            val f = File(dir, "local_image_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.media", f)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Export picture"))
        }.onFailure { Toast.makeText(this, "Couldn't export it", Toast.LENGTH_SHORT).show() }
    }

    private fun togglePick(item: Gallery.Item) {
        val n = item.file.name
        if (!picked.add(n)) picked.remove(n)
        if (picked.isEmpty()) picking = false
        showPicks()
    }

    private fun pickAll() {
        val all = Gallery.items(this).map { it.file.name }
        if (picked.size == all.size) picked.clear() else picked.addAll(all)
        if (picked.isEmpty()) picking = false
        showPicks()
    }

    private fun stopPicking() { picking = false; picked.clear(); if (::pickBar.isInitialized) showPicks() }

    private fun deletePicked() {
        val chosen = Gallery.items(this).filter { it.file.name in picked }
        if (chosen.isEmpty()) return
        val n = chosen.size
        AlertDialog.Builder(this)
            .setMessage("Delete ${if (n == 1) "this picture" else "these $n pictures"} from your gallery? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                chosen.forEach { it.delete() }
                stopPicking(); loadGallery()
                Toast.makeText(this, if (n == 1) "Deleted 1 picture" else "Deleted $n pictures", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Ticks on the picked pictures, dims the rest, and shows the bar with how many are picked. */
    private fun showPicks() {
        pickBar.visibility = if (picking) View.VISIBLE else View.GONE
        pickCount.text = "${picked.size} selected"
        for ((img, mark, item) in pickMarks) {
            val on = item.file.name in picked
            mark.visibility = if (picking) View.VISIBLE else View.GONE
            mark.text = if (on) "✓" else ""
            mark.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (on) purple else Color.argb(90, 0, 0, 0)); setStroke(dp(2f), Color.WHITE)
            }
            img.alpha = if (picking && !on) 0.55f else 1f
            img.scaleX = if (on) 0.92f else 1f; img.scaleY = img.scaleX
        }
    }

    private fun galleryMenu(item: Gallery.Item) {
        AlertDialog.Builder(this)
            .setTitle("This picture")
            .setItems(arrayOf("🔍 See it full screen", "✏️ Use its words again", "🗑️ Delete")) { _, which ->
                when (which) {
                    0 -> viewGalleryItem(item)
                    1 -> { prompt.setText(item.words); prompt.setSelection(prompt.text.length) }
                    2 -> AlertDialog.Builder(this).setMessage("Delete this picture from your gallery?")
                        .setPositiveButton("Delete") { _, _ -> item.delete(); loadGallery() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }

    /** 🎨 theme picker (shared with Sticker maker, TTS and Bug report). */
    private fun pickTheme() = ImageThemes.pick(this)

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
Quick makes a picture in seconds, but it's simple. Z-Image Turbo makes much better pictures but takes a few minutes. The 🧪 Test models are candidates for a better Quick. Pick one in MODEL. Each downloads once the first time you use it.

📷 Style
Adds a look to your words. There are over 40, most popular first: Photorealistic, Cinematic, Anime, Cyberpunk, Logo, Oil painting and more. For a logo, put the name in "quotes", e.g. a coffee shop logo with the name "Bean There".

🎲 Seed
The seed is the starting point for a picture. 🎲 Random gives a different picture every time. 📌 Fixed gives the same picture again for the same words, model, style and shape. That's handy for comparing models, or for changing one word and seeing what changes. 📌 Fixed straight after a picture you liked keeps that picture's seed.

⬛ Shape
The picture's shape: Square, Phone (9:16), Wide (16:9), Portrait, Landscape, Photo, Card, Cinema, or ✏️ Custom for any shape you like. Every shape takes about the same time.

🌙 Queue
Line up lots of pictures and let them draw one after another, for example overnight.
• ✨ Automatic: type a theme (like "Christmas cards") and how many. The writing helper invents a different idea for each, with detailed descriptions if you tick 🪄.
• ✍️ Manual: type each picture yourself, or add what's in the main box.
Each picture uses the model, style, steps and shape set on the main screen when you add it. Tap ▶ Start, plug in your charger and turn the screen off. Every picture goes into your gallery. Stop ends the run, and anything not drawn yet stays in the queue.

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
Every picture you make is kept here, newest first. Tap one to see it full screen, with 📤 Export (send it to any app), 💾 Save, ✏️ Reuse (bring it back with its words) and 🗑️ Delete. ⚙️ Settings chooses how many to keep. Once that many are stored, the oldest are replaced.

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
                if (m !== ImageGen.QUICK) "\n\nIt needs plenty of free memory and takes a few minutes per picture." else "")
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

    private fun save() { result?.let { saveBitmap(it) } }

    private fun saveBitmap(bmp: Bitmap) {
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OpenKeyboard_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/O-Board")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        Toast.makeText(this, if (ok) "Saved to Pictures › O-Board" else "Couldn't save it", Toast.LENGTH_SHORT).show()
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
