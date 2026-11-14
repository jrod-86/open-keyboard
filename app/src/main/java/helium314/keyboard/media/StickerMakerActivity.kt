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
import android.graphics.drawable.GradientDrawable
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
import android.widget.SeekBar
import android.widget.FrameLayout
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
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
 * "Tap to pick" uses MobileSAM (Apache-2.0, downloaded on first use) to keep just what you tap,
 * and Erase / Restore brushes touch up the edges by finger.
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
    /** The photo at full size with the background made clear: what the tools edit. */
    private var masked: Bitmap? = null
    private lateinit var faded: ImageView
    private lateinit var toolRow: LinearLayout
    private lateinit var undoBtn: Button
    private val toolBtns = mutableMapOf<Int, Button>()
    private var mode = MODE_NONE
    private val undo = ArrayDeque<Bitmap>()
    private val taps = mutableListOf<FloatArray>()
    private var sam: Sam? = null
    private var samFor: Bitmap? = null
    private var samEmbedding: FloatArray? = null
    @Volatile private var busy = false
    private var lastX = 0f; private var lastY = 0f
    private lateinit var box: FrameLayout
    private lateinit var ring: RingView
    private lateinit var sizeRow: LinearLayout
    /** Brush radius on screen, in pixels; zooming in makes it finer on the photo. */
    private var brushPx = 0f
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private var pinching = false
    private var stroking = false
    private var pinchDist = 0f; private var pinchZoom = 1f; private var pinchLx = 0f; private var pinchLy = 0f
    private var photoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(UiKit.shinyTitle(this, "✂️ Sticker maker"))
        status = UiKit.hint(this, "Take a photo or choose one. The background is removed for you.")
        root.addView(status)
        val pick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, pad * 3 / 4) }
        pick.addView(UiKit.chip(this, "📷  Take photo") { takePhoto() }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginEnd = pad / 4 })
        pick.addView(UiKit.chip(this, "🖼️  Choose picture") { choose() }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = pad / 4 })
        root.addView(pick)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val frame = FrameLayout(this).apply {
            background = checkerboard()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(18f).toFloat())
            }
            clipToOutline = true
        }
        faded = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.35f; visibility = View.GONE }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box = FrameLayout(this).apply { pivotX = 0f; pivotY = 0f }
        box.addView(faded, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ring = RingView(this)
        frame.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.clipChildren = true
        frame.setOnTouchListener { _, e -> onPreviewTouch(e) }
        // a thin purple-to-blue frame around the preview
        val border = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(UiKit.PURPLE, UiKit.ACCENT)).apply { cornerRadius = dp(19.5f).toFloat() }
            val b = dp(1.5f); setPadding(b, b, b, b)
        }
        border.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(border, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        brushPx = dp(14f).toFloat()
        sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(0, pad / 2, 0, 0) }
        sizeRow.addView(TextView(this).apply { text = "Size"; setPadding(0, 0, pad / 2, 0) })
        sizeRow.addView(SeekBar(this).apply {
            max = 100; progress = 25
            progressTintList = android.content.res.ColorStateList.valueOf(UiKit.PURPLE); thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    brushPx = dp(3f + v * 0.45f).toFloat()
                    ring.show(frame.width / 2f, frame.height / 2f, brushPx)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { ring.hideSoon() }
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(TextView(this).apply { text = "Pinch to zoom"; alpha = 0.7f; setPadding(pad / 2, 0, 0, 0) })
        root.addView(sizeRow)
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, pad / 2, 0, 0) }
        for (m in listOf(MODE_PICK, MODE_ERASE, MODE_RESTORE)) {
            val b = UiKit.chip(this, toolLabel(m)) { setMode(if (mode == m) MODE_NONE else m) }
            toolBtns[m] = b
            toolRow.addView(b, LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginEnd = pad / 4 })
        }
        undoBtn = UiKit.chip(this, "↩️ Undo") { undoLast() }.apply { isEnabled = false; alpha = 0.4f }
        toolRow.addView(undoBtn, LinearLayout.LayoutParams(0, dp(42f), 0.8f))
        root.addView(toolRow)
        cutOut = CheckBox(this).apply { text = "Remove the background"; isChecked = true; buttonTintList = android.content.res.ColorStateList.valueOf(UiKit.PURPLE); setOnCheckedChangeListener { _, on ->
            if (!on) setMode(MODE_NONE)
            toolRow.visibility = if (on && photo != null) View.VISIBLE else View.GONE
            render()
        } }
        root.addView(cutOut)
        caption = UiKit.field(this, "Add text (optional)").apply {
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
        }
        root.addView(caption)
        val packRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, pad / 2, 0, pad * 3 / 4) }
        packRow.addView(TextView(this).apply { text = "Save to pack"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.8f; setPadding(0, 0, pad * 3 / 4, 0) })
        packName = UiKit.field(this, "Pack name").apply { setText(MAKER_PACK); isSingleLine = true }
        packRow.addView(packName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(packRow)
        save = UiKit.glow(this, "✨  Save sticker") { saveSticker() }.apply { isEnabled = false; alpha = 0.5f }
        root.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        setContentView(root)
        root.fitAboveKeyboard(includeTop = true)
        if (savedInstanceState == null) {
            val given = intent?.getStringExtra(EXTRA_IMAGE)?.let { File(it) }
            if (given != null && given.isFile) process(Uri.fromFile(given)) else choose()
        }
    }

    private fun setUndo(on: Boolean) { undoBtn.isEnabled = on; undoBtn.alpha = if (on) 1f else 0.4f }

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
        save.isEnabled = false; save.alpha = 0.5f
        status.text = "Cutting it out…"
        thread(name = "sticker-maker") {
            val result = runCatching {
                val bmp = loadBitmap(uri) ?: throw IllegalStateException("Couldn't open the picture")
                bmp to cutOut(bmp)
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { (p, c) ->
                    photo = p; masked = c; cut = c?.let { crop(it) }
                    undo.clear(); taps.clear(); samEmbedding = null; samFor = null
                    setMode(MODE_NONE)
                    toolRow.visibility = if (cutOut.isChecked) View.VISIBLE else View.GONE
                    status.text = if (cut == null) "Couldn't find anything to cut out. Use Tap to pick and tap what you want."
                        else "Looks good? Add text and save. Not quite right? Use Tap to pick, Erase or Restore."
                    render()
                    save.isEnabled = true; save.alpha = 1f
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
        return Bitmap.createBitmap(photoPx, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Crops a cut-out to what's left visible, with a small margin. Null if nothing is left. */
    private fun crop(full: Bitmap): Bitmap? {
        val w = full.width; val h = full.height
        val px = IntArray(w * h); full.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in 0 until w * h) if (px[i] ushr 24 > 8) {
            val x = i % w; val y = i / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0 || (x1 - x0 + 1) * (y1 - y0 + 1) < 64) return null
        val margin = (max(x1 - x0, y1 - y0) * 0.01f).toInt()
        val l = max(0, x0 - margin); val t = max(0, y0 - margin)
        return Bitmap.createBitmap(full, l, t, min(w, x1 + margin + 1) - l, min(h, y1 + margin + 1) - t)
    }

    // ---- touch-up tools ----

    private fun setMode(m: Int) {
        if (m != MODE_NONE && photo == null) return
        if (m != MODE_NONE && masked == null) masked = Bitmap.createBitmap(photo!!.width, photo!!.height, Bitmap.Config.ARGB_8888)
        val leaving = mode != MODE_NONE && m == MODE_NONE
        mode = m
        sizeRow.visibility = if (mode == MODE_ERASE || mode == MODE_RESTORE) View.VISIBLE else View.GONE
        if (mode == MODE_NONE) setZoom(1f, 0f, 0f)
        toolBtns.forEach { (k, b) -> b.alpha = if (mode == MODE_NONE || k == mode) 1f else 0.55f; b.text = toolLabel(k); UiKit.setChosen(b, k == mode, caption.currentTextColor) }
        if (leaving) cut = masked?.let { crop(it) }
        when (mode) {
            MODE_PICK -> { status.text = "Tap what you want to keep. Tap more spots to add more of it."; ensureSam() }
            MODE_ERASE -> status.text = "Rub out anything you don't want with your finger."
            MODE_RESTORE -> status.text = "Paint back parts that got cut off by mistake."
            else -> if (leaving) status.text = if (cut == null) "Nothing is left to use. Tap Undo or Tap to pick." else "Looks good? Add text if you like, then save."
        }
        render()
    }

    private fun toolLabel(k: Int) = if (mode == k) "✓ Done" else when (k) {
        MODE_PICK -> "👆 Pick"
        MODE_ERASE -> "🧽 Erase"
        else -> "🖌️ Restore"
    }

    private fun pushUndo() {
        masked?.let { undo.addLast(it.copy(Bitmap.Config.ARGB_8888, true)) }
        while (undo.size > 12) undo.removeFirst()
        setUndo(true)
    }

    private fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        masked = prev
        if (taps.isNotEmpty() && mode == MODE_PICK) taps.removeAt(taps.size - 1)
        setUndo(undo.isNotEmpty())
        if (mode == MODE_NONE) cut = crop(prev)
        render()
    }

    /** Where a point on screen (in the frame) lands on the photo, in the photo's own pixels. */
    private fun toPhoto(x: Float, y: Float): FloatArray? {
        val inv = Matrix()
        if (!preview.imageMatrix.invert(inv)) return null
        val pt = floatArrayOf((x - panX) / zoom - preview.paddingLeft, (y - panY) / zoom - preview.paddingTop)
        inv.mapPoints(pt)
        return pt
    }

    private fun setZoom(z: Float, px: Float, py: Float) {
        zoom = z
        val w = box.width.toFloat(); val h = box.height.toFloat()
        // keep the picture covering the view: no panning off into empty space
        panX = px.coerceIn(w - w * z, 0f); panY = py.coerceIn(h - h * z, 0f)
        box.scaleX = z; box.scaleY = z; box.translationX = panX; box.translationY = panY
    }

    private fun onPreviewTouch(e: MotionEvent): Boolean {
        if (mode == MODE_NONE || busy) return false
        val bmp = masked ?: return false
        // two fingers: pinch to zoom and drag to move around; a stroke started by the first finger is undone
        if (e.pointerCount >= 2) {
            val x0 = e.getX(0); val y0 = e.getY(0); val x1 = e.getX(1); val y1 = e.getY(1)
            val fx = (x0 + x1) / 2; val fy = (y0 + y1) / 2
            val d = kotlin.math.hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
            if (!pinching) {
                if (stroking) { undo.removeLastOrNull()?.let { masked = it; preview.setImageBitmap(it) }; setUndo(undo.isNotEmpty()); stroking = false }
                pinching = true; pinchDist = d; pinchZoom = zoom
                pinchLx = (fx - panX) / zoom; pinchLy = (fy - panY) / zoom
                ring.hide()
            } else {
                val z = (pinchZoom * d / pinchDist).coerceIn(1f, 10f)
                setZoom(z, fx - z * pinchLx, fy - z * pinchLy)
            }
            return true
        }
        if (pinching) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        val pt = toPhoto(e.x, e.y) ?: return true
        if (mode == MODE_PICK) {
            if (e.action == MotionEvent.ACTION_UP && pt[0] in 0f..bmp.width.toFloat() && pt[1] in 0f..bmp.height.toFloat()) pick(pt)
            return true
        }
        val imgScale = FloatArray(9).also { preview.imageMatrix.getValues(it) }[Matrix.MSCALE_X].coerceAtLeast(1e-3f)
        val r = brushPx / (zoom * imgScale)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { stroking = false; ring.hideSoon(); return true }
        ring.show(e.x, e.y, brushPx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * r; strokeCap = Paint.Cap.ROUND
            if (mode == MODE_ERASE) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            else { shader = BitmapShader(photo!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        }
        val c = Canvas(bmp)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pushUndo(); stroking = true; lastX = pt[0]; lastY = pt[1]; c.drawPoint(pt[0], pt[1], paint) }
            MotionEvent.ACTION_MOVE -> if (stroking) { c.drawLine(lastX, lastY, pt[0], pt[1], paint); lastX = pt[0]; lastY = pt[1] }
        }
        preview.invalidate()
        return true
    }

    private fun ensureSam() {
        val p = photo ?: return
        if (sam != null && samFor === p && samEmbedding != null) return
        busy = true
        progress.visibility = View.VISIBLE
        thread(name = "sticker-sam") {
            val r = runCatching {
                val dir = File(filesDir, "sticker_sam")
                if (!Sam.downloaded(dir)) {
                    runOnUiThread { status.text = "Getting the tap-to-pick helper (11 MB, one time only)…" }
                    Sam.download(dir)
                }
                runOnUiThread { status.text = "Getting ready…" }
                val s = sam ?: Sam(dir).also { sam = it }
                samEmbedding = s.embed(p); samFor = p
            }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                if (mode != MODE_PICK) return@runOnUiThread
                status.text = r.fold({ "Tap what you want to keep. Tap more spots to add more of it." },
                    { "Couldn't get tap-to-pick ready. Check your internet and try again." })
                if (r.isFailure) setMode(MODE_NONE)
            }
        }
    }

    private fun pick(pt: FloatArray) {
        val p = photo ?: return
        val s = sam ?: return
        val emb = samEmbedding ?: return
        busy = true
        progress.visibility = View.VISIBLE
        taps.add(pt)
        val points = taps.toList()
        thread(name = "sticker-pick") {
            val r = runCatching { s.mask(emb, p, points) }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                r.onSuccess { m ->
                    pushUndo()
                    val w = p.width; val h = p.height
                    val px = IntArray(w * h); p.getPixels(px, 0, w, 0, 0, w, h)
                    for (i in 0 until w * h) px[i] = if (m[i]) px[i] or (0xFF shl 24) else 0
                    masked = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                    status.text = "Got it. Tap more spots to add, or tap Done."
                    render()
                }.onFailure { taps.removeAt(taps.size - 1); status.text = "That didn't work, try tapping again." }
            }
        }
    }

    /** Shows the brush size as a ring where the finger is. */
    private class RingView(ctx: android.content.Context) : View(ctx) {
        private var cx = 0f; private var cy = 0f; private var r = 0f; private var on = false
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.argb(160, 0, 0, 0) }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE }
        private val hider = Runnable { on = false; invalidate() }
        fun show(x: Float, y: Float, radius: Float) { removeCallbacks(hider); cx = x; cy = y; r = radius; on = true; invalidate() }
        fun hide() { removeCallbacks(hider); on = false; invalidate() }
        fun hideSoon() { removeCallbacks(hider); postDelayed(hider, 500) }
        override fun onDraw(canvas: Canvas) {
            if (!on) return
            canvas.drawCircle(cx, cy, r, dark); canvas.drawCircle(cx, cy, r, light)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { sam?.close() }
    }

    /** The sticker as it will be saved: fills a canvas of at most 512 px, white outline, optional text. */
    private fun compose(): Bitmap? {
        val src = (if (cutOut.isChecked) cut ?: photo else photo) ?: return null
        val text = caption.text.toString().trim()
        val outline = if (cutOut.isChecked && cut != null) 8 else 0
        val textH = if (text.isEmpty()) 0 else 84
        // as big as possible: apps scale a sticker so its longer side fills their box, so no spare border
        val scale = min((512f - 2 * outline) / src.width, (512f - 2 * outline - textH) / src.height)
        val sw = (src.width * scale).roundToInt().coerceAtLeast(1); val sh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val outW = if (text.isEmpty()) sw + 2 * outline else 512
        val out = Bitmap.createBitmap(outW, sh + 2 * outline + textH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val left = (outW - sw) / 2f
        val top = outline.toFloat()
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        if (outline > 0) {
            // a white sticker outline: the silhouette drawn in white around the edges
            val white = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
            for (k in 0 until 16) {
                val ang = k * Math.PI / 8
                c.drawBitmap(scaled, left + (outline * Math.cos(ang)).toFloat(), top + (outline * Math.sin(ang)).toFloat(), white)
            }
        }
        c.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        if (text.isNotEmpty()) {
            val size = 64f.coerceAtMost(64f * 14 / max(14, text.length))
            val y = out.height - 22f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE; strokeWidth = size / 5; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
            }
            val fill = Paint(stroke).apply { style = Paint.Style.FILL; color = Color.WHITE }
            c.drawText(text, outW / 2f, y, stroke)
            c.drawText(text, outW / 2f, y, fill)
        }
        return out
    }

    private fun render() {
        if (mode != MODE_NONE && masked != null) {
            faded.setImageBitmap(photo); faded.visibility = View.VISIBLE
            preview.setImageBitmap(masked)
            return
        }
        faded.visibility = View.GONE
        preview.setImageBitmap(compose())
    }

    private fun saveSticker() {
        if (mode != MODE_NONE) setMode(MODE_NONE)
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
        const val EXTRA_IMAGE = "image_path"
        private const val MODE_NONE = 0
        private const val MODE_PICK = 1
        private const val MODE_ERASE = 2
        private const val MODE_RESTORE = 3
    }
}
