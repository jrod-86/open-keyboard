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
    private lateinit var generate: Button
    private lateinit var actions: LinearLayout
    private val stylePills = mutableListOf<TextView>()
    private var style = 0
    private val modelPills = mutableListOf<TextView>()
    private val stepPills = mutableListOf<TextView>()
    private lateinit var stepRow: View
    private var model = ImageGen.QUICK
    private var steps = 4
    private val prefs by lazy { getSharedPreferences("image_maker", MODE_PRIVATE) }
    private var result: Bitmap? = null
    @Volatile private var working = false

    private val styles = listOf(
        "Sticker" to ", cute sticker, cartoon, bold outline, plain white background",
        "Cartoon" to ", cartoon illustration, bright colours",
        "Photo" to ", photo, realistic, detailed, natural light",
        "Emoji" to ", emoji icon, 3d, glossy, plain white background",
        "Plain" to "",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        val accent = Color.rgb(66, 133, 244)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(UiKit.title(this, "Image maker"))
        root.addView(UiKit.hint(this, "Describe it, pick a style, and your phone draws it."))
        prompt = EditText(this).apply {
            hint = "Describe a picture, e.g. tuxedo cat eating pizza"
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            maxLines = 3
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.argb(28, 128, 128, 128)); setStroke(dp(1f), Color.argb(90, 128, 128, 128)) }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { start(); true } else false }
        }
        root.addView(prompt)
        model = ImageGen.MODELS.firstOrNull { it.id == prefs.getString("model", "quick") } ?: ImageGen.QUICK
        steps = prefs.getInt("steps", 4)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, pad * 3 / 4, 0, 0) }
        ImageGen.MODELS.forEach { m ->
            val pill = pill(m.label) { model = m; prefs.edit().putString("model", m.id).apply(); showStyles() }
            modelPills.add(pill)
            modelRow.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        val steps4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(4, 8).forEach { n ->
            val pill = pill("$n steps") { steps = n; prefs.edit().putInt("steps", n).apply(); showStyles() }
            stepPills.add(pill)
            steps4.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        stepRow = steps4
        modelRow.addView(View(this), LinearLayout.LayoutParams(dp(4f), 1))
        modelRow.addView(steps4)
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modelRow) })
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad * 3 / 4, 0, pad * 3 / 4) }
        styles.forEachIndexed { i, (name, _) ->
            val pill = pill(name) { style = i; showStyles() }
            stylePills.add(pill)
            pills.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        generate = Button(this).apply {
            text = "Generate"; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(accent) }
            setOnClickListener { if (working && drawing) { ImageGen.cancel(); status.text = "Stopping…" } else start() }
        }
        root.addView(generate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        // the picture card: picture (or a hint) with the status line and progress under it
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.argb(22, 128, 128, 128)) }
        }
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        card.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Your picture appears here.\nIt's drawn on your phone, nothing is sent anywhere."
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.8f
            setPadding(0, pad / 2, 0, 0)
        }
        card.addView(status)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad })
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, pad / 2, 0, 0) }
        for ((label, act) in listOf<Pair<String, () -> Unit>>("Send" to ::send, "Save" to ::save, "Make sticker" to ::makeSticker)) {
            actions.addView(Button(this).apply {
                text = label; isAllCaps = false
                background = GradientDrawable().apply { cornerRadius = dp(22f).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1f), accent) }
                setTextColor(accent)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = pad / 4; marginEnd = pad / 4 })
        }
        root.addView(actions)
        showStyles()
        setContentView(root)
        root.fitAboveKeyboard(includeTop = true)
        prompt.requestFocus()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        val pad = dp(14f)
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(pad, pad / 2, pad, pad / 2); gravity = Gravity.CENTER
        setOnClickListener { if (!working) onClick() }
    }

    private fun showStyles() {
        stepRow.visibility = if (model === ImageGen.BEST) View.VISIBLE else View.GONE
        val all = stylePills.mapIndexed { i, p -> p to (i == style) } +
            modelPills.mapIndexed { i, p -> p to (ImageGen.MODELS[i] === model) } +
            stepPills.mapIndexed { i, p -> p to (listOf(4, 8)[i] == steps) }
        all.forEach { (p, on) ->
            p.background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                if (on) setColor(Color.rgb(66, 133, 244)) else { setColor(Color.TRANSPARENT); setStroke(dp(1f), Color.GRAY) }
            }
            p.setTextColor(if (on) Color.WHITE else prompt.currentTextColor)
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
        generate.text = if (m === ImageGen.QUICK) "Drawing…" else "Stop"
        generate.isEnabled = m !== ImageGen.QUICK
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        status.text = if (m === ImageGen.QUICK) "Drawing…" else "Getting ready… the best quality model takes a few minutes."
        val full = words + styles[style].second
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
                generate.isEnabled = true; generate.text = "Try again"
                progress.visibility = View.GONE
                r.onSuccess {
                    result = it
                    picture.setImageBitmap(it); picture.visibility = View.VISIBLE
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
