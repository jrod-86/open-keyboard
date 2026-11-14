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
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Image maker"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        status = TextView(this).apply { text = "Describe a picture. It's drawn on your phone, nothing is sent anywhere."; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        prompt = EditText(this).apply {
            hint = "e.g. tuxedo cat eating pizza"
            imeOptions = EditorInfo.IME_ACTION_GO
            maxLines = 3
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { start(); true } else false }
        }
        root.addView(prompt)
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad / 2, 0, pad / 2) }
        styles.forEachIndexed { i, (name, _) ->
            val pill = TextView(this).apply {
                text = name; setPadding(pad, pad / 3, pad, pad / 3); gravity = Gravity.CENTER
                setOnClickListener { style = i; showStyles() }
            }
            stylePills.add(pill)
            pills.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad / 2 })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        showStyles()
        generate = Button(this).apply { text = "Generate"; isAllCaps = false; setOnClickListener { start() } }
        root.addView(generate)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        for ((label, act) in listOf<Pair<String, () -> Unit>>("Send" to ::send, "Save" to ::save, "Make sticker" to ::makeSticker)) {
            actions.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { act() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(actions)
        setContentView(root)
        root.fitAboveKeyboard()
        prompt.requestFocus()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun showStyles() {
        stylePills.forEachIndexed { i, p ->
            val on = i == style
            p.background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                if (on) setColor(Color.rgb(66, 133, 244)) else { setColor(Color.TRANSPARENT); setStroke(dp(1f), Color.GRAY) }
            }
            p.setTextColor(if (on) Color.WHITE else status.currentTextColor)
        }
    }

    private fun start() {
        if (working) return
        val words = prompt.text.toString().trim()
        if (words.isEmpty()) { status.text = "Type what you'd like a picture of first."; return }
        if (!ImageGen.downloaded(this)) { askDownload(); return }
        working = true
        generate.isEnabled = false
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        status.text = "Drawing…"
        val full = words + styles[style].second
        thread(name = "image-maker") {
            val t = System.currentTimeMillis()
            val r = runCatching { ImageGen.generate(this, full, Random.nextLong(0, Int.MAX_VALUE.toLong())) }
            runOnUiThread {
                working = false
                generate.isEnabled = true; generate.text = "Try again"
                progress.visibility = View.GONE
                r.onSuccess {
                    result = it
                    picture.setImageBitmap(it)
                    actions.visibility = View.VISIBLE
                    status.text = "Done in %.0f s. Not quite right? Tap Try again for a different one.".format((System.currentTimeMillis() - t) / 1000f)
                }.onFailure { status.text = "That didn't work: ${it.message}" }
            }
        }
    }

    private fun askDownload() {
        AlertDialog.Builder(this)
            .setTitle("Download the picture maker?")
            .setMessage("It's a one-time download of about 680 MB, so Wi-Fi is best. After that it works without internet.\n\n" +
                "The model is SDXS-512 (OpenRAIL++ licence), which asks that it isn't used to harm people, for example no fake " +
                "pictures of real people meant to deceive.")
            .setPositiveButton("Download") { _, _ -> download() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun download() {
        working = true
        generate.isEnabled = false
        progress.isIndeterminate = false; progress.progress = 0; progress.visibility = View.VISIBLE
        status.text = "Downloading… keep this screen open."
        thread(name = "image-maker-download") {
            val r = runCatching { ImageGen.download(this) { f -> runOnUiThread { progress.progress = (f * 1000).toInt(); status.text = "Downloading… %d%%".format((f * 100).toInt()) } } }
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
