// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
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
 * Tesseract (Apache-2.0) with its English "fast" model, all on the phone. The text is put on the
 * clipboard, so the keyboard offers it in the suggestion bar with one tap.
 */
class ScanTextActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: EditText
    private lateinit var progress: ProgressBar
    private lateinit var useButton: Button
    private var photoFile: File? = null

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
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = android.view.View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        result = EditText(this).apply {
            hint = "Recognised text appears here; you can fix it before using it"
            gravity = Gravity.TOP
            minLines = 6
        }
        root.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        useButton = Button(this).apply {
            text = "Use this text"
            isEnabled = false
            setOnClickListener { useText() }
        }
        root.addView(useButton)
        setContentView(root)
        if (savedInstanceState == null) takePhoto()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

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
    private fun prepare(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
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
        progress.visibility = android.view.View.VISIBLE
        status.text = "Reading the text…"
        useButton.isEnabled = false
        thread(name = "scan-text") {
            val text = try {
                val bmp = loadBitmap(uri) ?: throw IllegalStateException("Couldn't open the picture")
                val api = TessBaseAPI()
                try {
                    val started = bestDataPath()?.let { api.init(it, "eng") } == true || api.init(fastDataPath(), "eng")
                    if (!started) throw IllegalStateException("Text recognition couldn't start")
                    // find the blocks of text on the page instead of reading it as one block
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setImage(prepare(bmp))
                    api.utF8Text.orEmpty()
                } finally {
                    api.recycle()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Didn't work: ${e.message}"; progress.visibility = android.view.View.GONE }
                return@thread
            }
            val tidy = tidy(text)
            runOnUiThread {
                progress.visibility = android.view.View.GONE
                if (tidy.isBlank()) {
                    status.text = "No text found. Try again with the text filling more of the photo."
                } else {
                    status.text = "Check the text, then tap Use this text."
                    result.setText(tidy)
                    useButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Joins lines that belong to one paragraph and keeps paragraph breaks. Drops the bits of noise
     * a photo picks up (stray marks, edges of pictures) and turns a lone "|" back into "I".
     */
    private fun tidy(raw: String): String {
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
            val letters = s.count { it.isLetter() }
            if (letters < 3 || letters < 0.5 * s.count { !it.isWhitespace() } || !Regex("\\p{L}{3,}").containsMatchIn(s)) return null
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

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        private const val BEST_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/eng.traineddata"
        private const val BEST_SHA256 = "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba"
        private const val BEST_SIZE = 15400601L
    }
}
