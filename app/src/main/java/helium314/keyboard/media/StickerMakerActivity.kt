// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import helium314.keyboard.latin.utils.fitAboveKeyboard
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.nio.FloatBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sticker maker: pick or take a photo, the background is cut out on the phone (U²-Net small model,
 * Apache-2.0), add some text, and save it to one of your own sticker packs.
 */
class StickerMakerActivity : Activity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var caption: EditText
    private lateinit var cutOut: CheckBox
    private lateinit var packName: EditText
    private lateinit var save: Button
    private var photo: Bitmap? = null
    private var cut: Bitmap? = null
    private var photoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Make a sticker"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        status = TextView(this).apply { text = "Take a photo or choose one. The background is removed for you."; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        val pick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pick.addView(Button(this).apply { text = "Take photo"; setOnClickListener { takePhoto() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pick.addView(Button(this).apply { text = "Choose picture"; setOnClickListener { choose() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(pick)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = checkerboard() }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cutOut = CheckBox(this).apply { text = "Remove the background"; isChecked = true; setOnCheckedChangeListener { _, _ -> render() } }
        root.addView(cutOut)
        caption = EditText(this).apply {
            hint = "Add text (optional)"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
        }
        root.addView(caption)
        packName = EditText(this).apply { hint = "Pack name"; setText(MAKER_PACK); isSingleLine = true }
        root.addView(packName)
        save = Button(this).apply { text = "Save sticker"; isEnabled = false; setOnClickListener { saveSticker() } }
        root.addView(save)
        setContentView(root)
        root.fitAboveKeyboard()
        if (savedInstanceState == null) choose()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun checkerboard(): BitmapDrawable {
        val s = dp(10f)
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(200, 200, 200))
            val p = Paint().apply { color = Color.rgb(235, 235, 235) }
            drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        }
        return BitmapDrawable(resources, b).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "media").apply { mkdirs() }
        val file = File(dir, "sticker-photo.jpg").also { it.delete(); photoFile = it }
        val uri = FileProvider.getUriForFile(this, "$packageName.media", file)
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { startActivityForResult(intent, REQ_CAMERA) }
            .onFailure { Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show() }
    }

    private fun choose() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> photoFile?.takeIf { it.length() > 0 }?.let { process(Uri.fromFile(it)) }
            REQ_PICK -> data?.data?.let { process(it) }
        }
    }

    private fun process(uri: Uri) {
        progress.visibility = View.VISIBLE
        save.isEnabled = false
        status.text = "Cutting it out…"
        thread(name = "sticker-maker") {
            val result = runCatching {
                val bmp = loadBitmap(uri) ?: throw IllegalStateException("Couldn't open the picture")
                bmp to cutOut(bmp)
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { (p, c) ->
                    photo = p; cut = c
                    status.text = if (c == null) "Couldn't find anything to cut out, so the whole photo is used." else "Looks good? Add text if you like, then save."
                    render()
                    save.isEnabled = true
                }.onFailure { status.text = "Didn't work: ${it.message}" }
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: return null
        val degrees = when (runCatching {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it) }?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** Runs the cut-out model and returns the subject on a clear background, cropped, or null. */
    private fun cutOut(bmp: Bitmap): Bitmap? {
        val n = 320
        val small = Bitmap.createScaledBitmap(bmp, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        var maxV = 1
        for (p in px) maxV = max(maxV, max(p shr 16 and 255, max(p shr 8 and 255, p and 255)))
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * n * n)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c // R, G, B
            for (i in 0 until n * n) input.put(((px[i] shr shift and 255).toFloat() / maxV - mean[c]) / std[c])
        }
        input.rewind()
        val env = OrtEnvironment.getEnvironment()
        val model = assets.open("u2netp.onnx").use { it.readBytes() }
        val mask = OrtSession.SessionOptions().use { opts ->
            env.createSession(model, opts).use { session ->
                OnnxTensor.createTensor(env, input, longArrayOf(1, 3, n.toLong(), n.toLong())).use { t ->
                    session.run(mapOf(session.inputNames.first() to t)).use { out ->
                        val fb = (out[0] as OnnxTensor).floatBuffer
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                }
            }
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in mask) { lo = min(lo, v); hi = max(hi, v) }
        if (hi - lo < 1e-3f) return null
        // the mask as a small greyscale picture, scaled up smoothly to the photo's size
        val m = IntArray(n * n) { val a = ((mask[it] - lo) / (hi - lo) * 255).roundToInt().coerceIn(0, 255); Color.argb(a, 0, 0, 0) }
        val maskSmall = Bitmap.createBitmap(m, n, n, Bitmap.Config.ARGB_8888)
        val maskBig = Bitmap.createScaledBitmap(maskSmall, bmp.width, bmp.height, true)
        val w = bmp.width; val h = bmp.height
        val photoPx = IntArray(w * h); bmp.getPixels(photoPx, 0, w, 0, 0, w, h)
        val maskPx = IntArray(w * h); maskBig.getPixels(maskPx, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in 0 until w * h) {
            var a = maskPx[i] ushr 24
            a = if (a < 70) 0 else if (a > 215) 255 else (a - 70) * 255 / 145 // firmer edges, less haze
            photoPx[i] = (photoPx[i] and 0xFFFFFF) or (a shl 24)
            if (a > 0) { val x = i % w; val y = i / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 < 0 || (x1 - x0) * (y1 - y0) < w * h / 100) return null
        val full = Bitmap.createBitmap(photoPx, w, h, Bitmap.Config.ARGB_8888)
        val margin = (max(x1 - x0, y1 - y0) * 0.06f).toInt()
        val l = max(0, x0 - margin); val t = max(0, y0 - margin)
        return Bitmap.createBitmap(full, l, t, min(w, x1 + margin + 1) - l, min(h, y1 + margin + 1) - t)
    }

    /** The sticker as it will be saved: up to 512 px, white outline around a cut-out, optional text. */
    private fun compose(): Bitmap? {
        val src = (if (cutOut.isChecked) cut ?: photo else photo) ?: return null
        val text = caption.text.toString().trim()
        val scale = 440f / max(src.width, src.height)
        val sw = (src.width * scale).roundToInt().coerceAtLeast(1); val sh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val textH = if (text.isEmpty()) 0 else 84
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val left = (512 - sw) / 2f
        val top = (512 - textH - sh) / 2f
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        if (cutOut.isChecked && cut != null) {
            // a white sticker outline: the silhouette drawn in white around the edges
            val white = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
            val r = 8f
            for (k in 0 until 16) {
                val ang = k * Math.PI / 8
                c.drawBitmap(scaled, left + (r * Math.cos(ang)).toFloat(), top + (r * Math.sin(ang)).toFloat(), white)
            }
        }
        c.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        if (text.isNotEmpty()) {
            val size = 64f.coerceAtMost(64f * 14 / max(14, text.length))
            val y = 512f - 26f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE; strokeWidth = size / 5; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
            }
            val fill = Paint(stroke).apply { style = Paint.Style.FILL; color = Color.WHITE }
            c.drawText(text, 256f, y, stroke)
            c.drawText(text, 256f, y, fill)
        }
        return out
    }

    private fun render() {
        preview.setImageBitmap(compose())
    }

    private fun saveSticker() {
        val bmp = compose() ?: return
        val name = packName.text.toString().ifBlank { MAKER_PACK }
        val ok = runCatching { StickerPacks.addMade(this, name, bmp) }.isSuccess
        Toast.makeText(this, if (ok) "Saved to \"$name\". Find it in the sticker panel." else "Couldn't save the sticker", Toast.LENGTH_LONG).show()
        if (ok) finish()
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        const val MAKER_PACK = "My stickers"
    }
}
