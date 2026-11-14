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

/** Image maker: type what you want, the phone draws it (see [ImageGen]), then send it, save it or make it a sticker. */
class ImageMakerActivity : Activity() {
    private lateinit var prompt: EditText
    private lateinit var status: TextView
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

        // what to draw, with a dice for ideas
        val promptBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(this@ImageMakerActivity, 16f, cardColor, lineColor)
        }
        prompt = EditText(this).apply {
            hint = "What should it draw?"
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            maxLines = 3; background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(pad, pad * 3 / 4, dp(4f), pad * 3 / 4)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { start(); true } else false }
        }
        promptBox.addView(prompt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            setOnClickListener { if (working && drawing) { ImageGen.cancel(); status.text = "Stopping…" } else start() }
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
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
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

    private fun refresh() {
        stepRow.visibility = if (model === ImageGen.BEST) View.VISIBLE else View.GONE
        modelValue.text = (if (model === ImageGen.QUICK) "⚡ " else "💎 ") + model.name + "  ▾"
        styleValue.text = styles[style].icon + " " + styles[style].name + "  ▾"
        stepButtons.forEachIndexed { i, b ->
            val on = listOf(4, 8)[i] == steps
            b.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, blue)).apply { cornerRadius = dp(17f).toFloat() } else null
            b.setTextColor(if (on) Color.WHITE else prompt.currentTextColor)
        }
    }

    @Volatile private var drawing = false

    private fun start() {
        if (working) return
        val words = prompt.text.toString().trim()
        if (words.isEmpty()) { status.text = "Type what you'd like a picture of first."; return }
        val m = model
        if (!ImageGen.downloaded(this, m)) { askDownload(m); return }
        working = true; drawing = true
        generate.text = if (m === ImageGen.QUICK) "Drawing…" else "■  Stop"
        generate.isEnabled = m !== ImageGen.QUICK
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        status.text = if (m === ImageGen.QUICK) "Drawing…" else "Getting ready… the best quality model takes a few minutes."
        val full = words + styles[style].suffix
        val n = steps
        val t = System.currentTimeMillis()
        ImageGen.progressListener = { step, total ->
            runOnUiThread {
                if (!drawing || total <= 1) return@runOnUiThread
                progress.isIndeterminate = false; progress.progress = (step * 1000 / total).coerceIn(0, 1000)
                val secs = (System.currentTimeMillis() - t) / 1000
                status.text = "Drawing… step $step of $total (${secs}s so far)"
            }
        }
        thread(name = "image-maker") {
            val r = runCatching { ImageGen.generate(this, m, full, Random.nextLong(0, Int.MAX_VALUE.toLong()), n) }
            runOnUiThread {
                working = false; drawing = false
                ImageGen.progressListener = null
                generate.isEnabled = true; generate.text = "✨  Make another"
                progress.visibility = View.GONE
                r.onSuccess {
                    result = it
                    picture.setImageBitmap(it); picture.visibility = View.VISIBLE; placeholder.visibility = View.GONE
                    actions.visibility = View.VISIBLE
                    status.text = "Done in %.0f s. Not quite right? Tap Try again for a different one.".format((System.currentTimeMillis() - t) / 1000f)
                }.onFailure { status.text = if (it is OutOfMemoryError) "Your phone ran out of memory for this one. Try Quick." else "Stopped: ${it.message}" }
            }
        }
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
        MediaSender.pendingImage = f
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
        if (!working) thread { ImageGen.close() }
    }
}
