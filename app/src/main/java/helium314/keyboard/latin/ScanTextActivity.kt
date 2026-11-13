// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.concurrent.thread

/**
 * Scan text with the camera (or from a picture) and hand it to the keyboard. Text recognition is
 * Tesseract (Apache-2.0), all on the phone. After the photo, either extract all the text, or tap
 * T and run a finger over just the part you want, like Samsung's camera. The text is put on the
 * clipboard, so the keyboard offers it in the suggestion bar with one tap.
 */
class ScanTextActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: EditText
    private lateinit var progress: ProgressBar
    private lateinit var photo: PhotoTextView
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private var photoFile: File? = null
    private var allText = ""
    /** True when the photo reader wasn't available and Tesseract was used instead. */
    @Volatile private var basicReader = false

    private enum class Mode { EMPTY, READING, PHOTO, SELECT, RESULT }
    private var mode = Mode.EMPTY

    /** One recognised word, with where it is in the picture and where it sits in the text. */
    class Word(val text: String, val box: Rect, val line: Int, val para: Int, val confidence: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Scan text"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        status = TextView(this).apply {
            text = "Take a photo of some text, or choose a picture or screenshot."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Take photo"; setOnClickListener { takePhoto() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply { text = "Choose picture"; setOnClickListener { pickPicture() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        photo = PhotoTextView(this).apply { onSelectionChanged = { updateButtons() } }
        root.addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        result = EditText(this).apply {
            hint = "Recognised text appears here; you can fix it before using it"
            gravity = Gravity.TOP
            minLines = 6
            visibility = View.GONE
        }
        root.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftButton = Button(this).apply { setOnClickListener { onLeft() } }
        rightButton = Button(this).apply { setOnClickListener { onRight() } }
        actions.addView(leftButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(rightButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        setContentView(root)
        setMode(Mode.EMPTY)
        if (savedInstanceState == null) takePhoto()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun setMode(m: Mode) {
        mode = m
        progress.visibility = if (m == Mode.READING) View.VISIBLE else View.GONE
        photo.visibility = if (m == Mode.RESULT || m == Mode.EMPTY) View.GONE else View.VISIBLE
        result.visibility = if (m == Mode.RESULT) View.VISIBLE else View.GONE
        photo.selecting = m == Mode.SELECT
        if (m != Mode.SELECT) photo.clearSelection()
        status.text = when (m) {
            Mode.EMPTY -> "Take a photo of some text, or choose a picture or screenshot."
            Mode.READING -> "Reading the text…"
            Mode.PHOTO -> (if (photo.words.isEmpty()) "No text found. Try again with the text filling more of the photo."
                else "Extract all the text, or tap T and run your finger over the part you want.") +
                (if (basicReader) " (Using the basic reader.)" else "")
            Mode.SELECT -> "Run your finger over the text you want. Do it again to change it."
            Mode.RESULT -> "Check the text, then tap Use this text."
        }
        updateButtons()
    }

    private fun updateButtons() {
        val (left, right) = when (mode) {
            Mode.EMPTY, Mode.READING -> null to null
            Mode.PHOTO -> "Extract all text" to "T   Select text"
            Mode.SELECT -> "Cancel" to "Use selected text"
            Mode.RESULT -> "Back to photo" to "Use this text"
        }
        leftButton.visibility = if (left == null) View.GONE else View.VISIBLE
        rightButton.visibility = if (right == null) View.GONE else View.VISIBLE
        left?.let { leftButton.text = it }
        right?.let { rightButton.text = it }
        leftButton.isEnabled = mode != Mode.PHOTO || allText.isNotBlank()
        rightButton.isEnabled = when (mode) {
            Mode.PHOTO -> photo.words.isNotEmpty()
            Mode.SELECT -> photo.selectedWords().isNotEmpty()
            else -> true
        }
    }

    private fun onLeft() {
        when (mode) {
            Mode.PHOTO -> { result.setText(allText); setMode(Mode.RESULT) }
            Mode.SELECT -> setMode(Mode.PHOTO)
            Mode.RESULT -> setMode(Mode.PHOTO)
            else -> {}
        }
    }

    private fun onRight() {
        when (mode) {
            Mode.PHOTO -> setMode(Mode.SELECT)
            Mode.SELECT -> {
                val text = selectedText(photo.selectedWords())
                if (text.isBlank()) return
                result.setText(text)
                setMode(Mode.RESULT)
            }
            Mode.RESULT -> useText()
            else -> {}
        }
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "media").apply { mkdirs() }
        val file = File(dir, "scan.jpg").also { it.delete(); photoFile = it }
        val uri = FileProvider.getUriForFile(this, "$packageName.media", file)
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { startActivityForResult(intent, REQ_CAMERA) }
            .onFailure { Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show() }
    }

    private fun pickPicture() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        runCatching { startActivityForResult(intent, REQ_PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> photoFile?.takeIf { it.length() > 0 }?.let { recognise(Uri.fromFile(it)) }
            REQ_PICK -> data?.data?.let { recognise(it) }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it) }?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()
        val degrees = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** Copies the bundled small model to where Tesseract expects it. */
    private fun fastDataPath(): String {
        val base = File(filesDir, "ocr")
        val target = File(base, "tessdata/eng.traineddata")
        if (target.length() == 0L) {
            target.parentFile?.mkdirs()
            assets.open("tessdata/eng.traineddata").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return base.absolutePath
    }

    /**
     * The more accurate model (tessdata_best, Apache-2.0, 15 MB) is downloaded once on first use,
     * pinned to a commit and checked by its SHA-256. Returns null if it can't be had right now.
     */
    private fun bestDataPath(): String? {
        val base = File(filesDir, "ocr-best")
        val target = File(base, "tessdata/eng.traineddata")
        if (target.length() == BEST_SIZE) return base.absolutePath
        return try {
            runOnUiThread { status.text = "Getting the better text reader (15 MB, only once)…" }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "eng.part")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            (java.net.URL(BEST_URL).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 30000
                inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
                } }
                disconnect()
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != BEST_SHA256 || tmp.length() != BEST_SIZE) { tmp.delete(); return null }
            tmp.renameTo(target)
            runOnUiThread { status.text = "Reading the text…" }
            base.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Grey, with the contrast stretched, which helps with photos of screens and coloured paper. */
    private fun prepare(src: Bitmap, channel: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = when (channel) {
                CH_RED -> c shr 16 and 255
                CH_GREEN -> c shr 8 and 255
                else -> ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
            }
            px[i] = l; hist[l]++
        }
        val cut = px.size / 100
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        val range = (hi - lo).coerceAtLeast(1)
        for (i in px.indices) {
            val v = ((px[i] - lo) * 255 / range).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun recognise(uri: Uri) {
        allText = ""
        photo.setPhoto(null, emptyList())
        setMode(Mode.READING)
        thread(name = "scan-text") {
            var bmp: Bitmap? = null
            basicReader = false
            val words = mutableListOf<Word>()
            val text = try {
                bmp = loadBitmap(uri) ?: throw IllegalStateException("Couldn't open the picture")
                runOnUiThread { photo.setPhoto(bmp, emptyList()); photo.visibility = View.VISIBLE }
                // If the photo reader crashed the app last time (a native crash can't be caught),
                // use Tesseract instead until a new build is installed.
                val paddleDir = File(filesDir, "ocr-paddle").apply { mkdirs() }
                val running = File(paddleDir, "running")
                val crashed = File(paddleDir, "crashed")
                // each installed build counts as a new version, so a fixed build tries again
                val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
                // a "running" left over from this same build means the reader crashed mid-way
                if (running.exists()) {
                    if (running.readText() == version) crashed.writeText(version)
                    running.delete()
                }
                val paddleOk = !(crashed.exists() && crashed.readText() == version)
                val lines = if (!paddleOk) null else try {
                    running.writeText(version)
                    val paddle = PaddleOcr.open(paddleDir) {
                        runOnUiThread { status.text = "Getting the photo text reader (13 MB, only once)…" }
                    }
                    runOnUiThread { status.text = "Reading the text…" }
                    paddle?.use { runCatching { it.read(bmp) }.getOrNull() }
                } finally { running.delete() }
                if (lines != null) {
                    val (text, found) = wordsFromLines(lines)
                    words += found
                    text
                } else {
                    basicReader = true
                    readWithTesseract(bmp, words)
                }
            } catch (e: Throwable) {
                runOnUiThread { setMode(Mode.EMPTY); status.text = "Didn't work: ${e.message}" }
                return@thread
            }
            // stray marks that aren't letters or numbers can't be picked
            // ("|" is often the letter I)
            val usable = words.filter { w -> (w.text.any { it.isLetterOrDigit() } || w.text == "|") && w.confidence >= 10f }
            val tidied = tidy(text)
            runOnUiThread {
                allText = tidied
                photo.setPhoto(bmp, usable)
                setMode(Mode.PHOTO)
            }
        }
    }

    /**
     * Turns PaddleOCR's lines into words in reading order, with lines grouped into paragraphs:
     * a new paragraph starts after a gap, or where the text starts somewhere else across the page.
     */
    private fun wordsFromLines(lines: List<PaddleOcr.Line>): Pair<String, List<Word>> {
        val sorted = lines.filter { it.confidence >= 0.5f }.sortedBy { it.box.top }
        val words = mutableListOf<Word>()
        val sb = StringBuilder()
        var para = 0
        var prev: PaddleOcr.Line? = null
        sorted.forEachIndexed { n, line ->
            val p = prev
            val h = line.box.height()
            if (p == null || line.box.top - p.box.bottom > 0.6f * h || kotlin.math.abs(line.box.left - p.box.left) > 1.5f * h) {
                para++
                if (p != null) sb.append("\n\n")
            } else sb.append("\n")
            sb.append(line.text)
            line.words.forEach { (t, box) -> words += Word(t, box, n, para, line.confidence * 100) }
            prev = line
        }
        return sb.toString() to words
    }

    /** Fallback when the photo reader can't be downloaded: Tesseract, which comes with the app. */
    private fun readWithTesseract(bmp: Bitmap, words: MutableList<Word>): String {
        val model = bestDataPath() ?: fastDataPath()
        // Read the photo three ways at once: in grey, and using only the red or green light.
        // White writing on a coloured bubble often only stands out in one of them. Keep the
        // reading that found the most text it was sure of.
        val passes = listOf(CH_GRAY, CH_RED, CH_GREEN).map { ch ->
            var out: Pass? = null
            thread(name = "scan-text-$ch") { out = runCatching { readPass(model, prepare(bmp, ch)) }.getOrNull() } to { out }
        }.map { (t, get) -> t.join(); get() }.filterNotNull()
        val best = passes.maxByOrNull { it.score } ?: throw IllegalStateException("Text recognition couldn't start")
        words += best.words
        return best.text
    }

    private class Pass(val text: String, val words: List<Word>, val score: Int)

    private fun readPass(model: String, image: Bitmap): Pass {
        val api = TessBaseAPI()
        try {
            if (!api.init(model, "eng") && !api.init(fastDataPath(), "eng")) throw IllegalStateException("Text recognition couldn't start")
            // find the blocks of text on the page instead of reading it as one block
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(image)
            val all = api.utF8Text.orEmpty()
            val words = mutableListOf<Word>()
            api.resultIterator?.let { it ->
                try {
                    it.begin()
                    var line = 0
                    var para = 0
                    do {
                        if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) para++
                        if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
                        val w = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val box = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!w.isNullOrBlank() && box != null)
                            words += Word(w.trim(), box, line, para, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    it.delete()
                }
            }
            val score = words.filter { it.confidence >= 70f }.sumOf { w -> w.text.count { it.isLetterOrDigit() } }
            return Pass(all, words, score)
        } finally {
            api.recycle()
            image.recycle()
        }
    }

    /** The picked words as text: spaces within a paragraph, a blank line between paragraphs. */
    private fun selectedText(words: List<Word>): String {
        val sb = StringBuilder()
        var last: Word? = null
        for (w in words) {
            last?.let { l -> sb.append(if (w.para != l.para) "\n\n" else if (w.line != l.line) "\n" else " ") }
            sb.append(w.text)
            last = w
        }
        return tidy(sb.toString(), strict = false)
    }

    /**
     * Joins lines that belong to one paragraph and keeps paragraph breaks. Drops the bits of noise
     * a photo picks up (stray marks, edges of pictures) and turns a lone "|" back into "I".
     */
    private fun tidy(raw: String, strict: Boolean = true): String {
        val lower = Regex("[a-z]")
        fun junk(w: String) = !w.any { it.isLetterOrDigit() } || (w.length == 1 && w !in setOf("I", "a", "A"))
        fun cleanLine(line: String): String? {
            val t = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            for (i in t.indices) {
                if (t[i] == "|" && (i == 0 || lower.containsMatchIn(t[i - 1].takeLast(1)) || (i + 1 < t.size && lower.containsMatchIn(t[i + 1].take(1)))))
                    t[i] = "I"
                if (Regex("^\\|t[a-z]*$").matches(t[i])) t[i] = "I" + t[i].substring(1)
            }
            while (t.isNotEmpty() && junk(t.first())) t.removeAt(0)
            while (t.isNotEmpty() && junk(t.last()) && !Regex("[.!?…]+").matches(t.last())) t.removeAt(t.size - 1)
            val s = t.joinToString(" ")
            if (!strict) return s.ifEmpty { null }
            // keep lines that are mostly letters or numbers, like "Total $12.50" or a phone number
            val alnum = s.count { it.isLetterOrDigit() }
            val hasWord = Regex("\\p{L}{3,}").containsMatchIn(s) || s.count { it.isDigit() } >= 3
            if (alnum < 3 || alnum < 0.5 * s.count { !it.isWhitespace() } || !hasWord) return null
            return s
        }
        val paras = mutableListOf<String>()
        val cur = mutableListOf<String>()
        for (line in raw.lines()) {
            if (line.isBlank()) { if (cur.isNotEmpty()) { paras += cur.joinToString(" "); cur.clear() }; continue }
            cleanLine(line)?.let { cur += it }
        }
        if (cur.isNotEmpty()) paras += cur.joinToString(" ")
        return paras.joinToString("\n\n")
    }

    private fun useText() {
        val text = result.text.toString().trim()
        if (text.isEmpty()) return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Scanned text", text))
        Toast.makeText(this, "Ready: tap it in the keyboard's suggestion bar to paste", Toast.LENGTH_LONG).show()
        finish()
    }

    /** Shows the photo, and in select mode lets a finger run over words to pick a run of them. */
    @SuppressLint("ViewConstructor")
    class PhotoTextView(context: Context) : View(context) {
        var words: List<Word> = emptyList(); private set
        var selecting = false
            set(value) { field = value; invalidate() }
        var onSelectionChanged: (() -> Unit)? = null
        private var bitmap: Bitmap? = null
        private var anchor = -1
        private var end = -1
        private val toScreen = Matrix()
        private val toPhoto = Matrix()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hintPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.FILL }
        private val hintEdge = Paint().apply { color = Color.argb(140, 120, 170, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val pickPaint = Paint().apply { color = Color.argb(120, 60, 130, 255); style = Paint.Style.FILL }
        private val dim = Paint().apply { color = Color.argb(90, 0, 0, 0) }

        fun setPhoto(bmp: Bitmap?, list: List<Word>) {
            bitmap = bmp; words = list; clearSelection(); requestLayout(); invalidate()
        }

        fun clearSelection() { anchor = -1; end = -1; invalidate(); onSelectionChanged?.invoke() }

        fun selectedWords(): List<Word> =
            if (anchor < 0 || end < 0) emptyList() else words.subList(minOf(anchor, end), maxOf(anchor, end) + 1)

        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: return
            val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            toScreen.reset()
            toScreen.postScale(scale, scale)
            toScreen.postTranslate((width - bmp.width * scale) / 2f, (height - bmp.height * scale) / 2f)
            toScreen.invert(toPhoto)
            canvas.drawBitmap(bmp, toScreen, bitmapPaint)
            if (!selecting) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val lo = if (anchor < 0) -1 else minOf(anchor, end)
            val hi = if (anchor < 0) -1 else maxOf(anchor, end)
            val r = RectF()
            words.forEachIndexed { i, w ->
                r.set(w.box); toScreen.mapRect(r)
                r.inset(-3f, -3f)
                if (i in lo..hi) canvas.drawRect(r, pickPaint)
                else { canvas.drawRect(r, hintPaint); canvas.drawRect(r, hintEdge) }
            }
        }

        /** The word under the finger, or the nearest one close by. */
        private fun wordAt(x: Float, y: Float): Int {
            val p = floatArrayOf(x, y)
            toPhoto.mapPoints(p)
            val slop = 24f * resources.displayMetrics.density * toPhoto.mapRadius(1f)
            var best = -1
            var bestDist = Float.MAX_VALUE
            words.forEachIndexed { i, w ->
                val dx = maxOf(w.box.left - p[0], 0f, p[0] - w.box.right)
                val dy = maxOf(w.box.top - p[1], 0f, p[1] - w.box.bottom)
                val d = dx * dx + dy * dy * 4 // rather stay on the same line
                if (d < bestDist) { bestDist = d; best = i }
            }
            return if (best >= 0 && bestDist <= slop * slop * 4) best else -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!selecting || words.isEmpty()) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            val i = wordAt(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { anchor = i; end = i }
                MotionEvent.ACTION_MOVE -> if (i >= 0) { if (anchor < 0) anchor = i; end = i }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onSelectionChanged?.invoke()
            }
            invalidate()
            return true
        }
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        private const val CH_GRAY = 0
        private const val CH_RED = 1
        private const val CH_GREEN = 2
        private const val BEST_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/eng.traineddata"
        private const val BEST_SHA256 = "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba"
        private const val BEST_SIZE = 15400601L
    }
}
