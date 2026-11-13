// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Offline AI writing help: fix grammar, change the tone, shorten, add emojis. Runs Qwen2.5 1.5B
 * Instruct (Apache-2.0) with llama.cpp (MIT) on the phone. The model downloads once (about 1.1 GB);
 * after that no text leaves the phone.
 */
object WritingTools {
    private const val TAG = "WritingTools"
    private const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/" +
        "91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    private const val MODEL_SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    private const val MODEL_SIZE = 1_117_320_736L
    private const val PREF_DOWNLOAD_ID = "open_keyboard_llm_download_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var unloadJob: Job? = null
    private var undoText: String? = null
    private var undoResult: String? = null

    private const val SYSTEM = "You are a writing assistant built into a phone keyboard. You rewrite the user's text " +
        "exactly as asked. Reply with only the rewritten text: no quotes, no notes, no explanations. " +
        "Keep the same language as the text."

    private class Action(val label: String, val instruction: String)
    private val actions = listOf(
        Action("Fix spelling and grammar", "Fix the spelling, grammar and punctuation. Keep the meaning, wording and tone the same."),
        Action("Friendlier", "Rewrite it to sound warmer and friendlier. Keep it about the same length."),
        Action("Professional", "Rewrite it to sound clear, polite and professional."),
        Action("Shorter", "Make it shorter and simpler, keeping the key points."),
        Action("Clearer", "Rewrite it so it is clearer and easier to read."),
        Action("Add emojis", "Add a few fitting emojis to it. Do not change the words."),
    )

    private fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "llm/qwen2.5-1.5b-instruct-q4_k_m.gguf")
    private fun isReady(ctx: Context) = modelFile(ctx).length() == MODEL_SIZE
    private fun downloadRunning(ctx: Context): Boolean {
        val id = ctx.getSharedPreferences("open_keyboard_llm", Context.MODE_PRIVATE).getLong(PREF_DOWNLOAD_ID, -1)
        if (id < 0) return false
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return false
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
        }
        return false
    }

    private fun startDownload(ctx: Context) {
        val file = modelFile(ctx)
        file.parentFile?.mkdirs()
        file.delete()
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Open Keyboard writing model")
            .setDescription("About 1.1 GB, needed once for offline writing help")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val id = dm.enqueue(request)
        ctx.getSharedPreferences("open_keyboard_llm", Context.MODE_PRIVATE).edit().putLong(PREF_DOWNLOAD_ID, id).apply()
        toast(ctx, "Downloading the writing model (about 1.1 GB). You'll get a notification when it's done.")
    }

    /** True when the writing model is downloaded and checked, so smart compose can use it. */
    fun modelReady(ctx: Context): Boolean {
        val f = modelFile(ctx)
        return isReady(ctx) && File(f.path + ".ok").let { it.exists() && it.lastModified() >= f.lastModified() }
    }

    /** Runs [block] with the loaded model, or returns null if writing help is busy right now. */
    suspend fun <T> withEngine(ctx: Context, block: suspend (InferenceEngine) -> T): T? {
        if (!lock.tryLock()) return null
        unloadJob?.cancel()
        try {
            return block(engine(ctx))
        } finally {
            lock.unlock()
            scheduleUnload(ctx)
        }
    }

    private fun verified(file: File): Boolean {
        val marker = File(file.path + ".ok")
        if (marker.exists() && marker.lastModified() >= file.lastModified()) return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
        if (ok) marker.writeText("ok") else file.delete()
        return ok
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
    private fun toast(ctx: Context, s: String) = main.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }

    @SuppressLint("ViewConstructor")
    private class Root(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun show(context: Context) {
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        val tile = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.GESTURE_TRAIL)
        val root = Root(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        }
        root.addView(ScrollView(context).apply { addView(column) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun pill(s: String, highlight: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 10f), dp(context, 14f), dp(context, 10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20f).toFloat()
                setColor(if (highlight) ColorUtils.setAlphaComponent(accent, 110) else tile)
            }
            setOnClickListener { onClick() }
        }
        fun note(s: String) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(context, 8f), 0, dp(context, 4f))
        }

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            this.text = "Writing help"
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (undoText != null) top.addView(pill("Undo") { KeyboardMenu.hide(); undo(context) })
        top.addView(pill("✕") { KeyboardMenu.hide() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(context, 6f) })
        column.addView(top)

        when {
            isReady(context) -> {
                actions.chunked(2).forEach { pair ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { a ->
                        row.addView(pill(a.label, a === actions[0]) { KeyboardMenu.hide(); run(context, a) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                setMargins(dp(context, 3f), dp(context, 4f), dp(context, 3f), dp(context, 4f))
                            })
                    }
                    column.addView(row)
                }
                column.addView(note("Works on what you wrote, or on the selected text. Runs on your phone; nothing is sent anywhere."))
            }
            downloadRunning(context) -> column.addView(note("The writing model is downloading (about 1.1 GB). Check the notification for progress, then open this again."))
            else -> {
                column.addView(note("Writing help runs a small AI model on your phone, so your text stays private. " +
                    "It needs a one-time download of about 1.1 GB (Wi-Fi recommended)."))
                column.addView(pill("Download the writing model", true) { KeyboardMenu.hide(); startDownload(context) })
            }
        }
        KeyboardMenu.showOverlay(root)
    }

    private suspend fun engine(ctx: Context): InferenceEngine {
        val engine = AiChat.getInferenceEngine(ctx.applicationContext)
        val state = engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        if (state is InferenceEngine.State.Error) engine.cleanUp()
        if (engine.state.value !is InferenceEngine.State.ModelReady) engine.loadModel(modelFile(ctx).absolutePath)
        return engine
    }

    private fun clean(out: String): String {
        var t = out.trim()
        t = t.removePrefix("Here is the rewritten text:").removePrefix("Rewritten text:").trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        return t.trim()
    }

    private fun run(context: Context, action: Action) {
        val ime = ime(context) ?: return
        val ic = ime.currentInputConnection ?: return
        ic.finishComposingText()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before = ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(8000, 0)?.toString().orEmpty()
        val source = selected.ifEmpty { before + after }
        if (source.isBlank()) { toast(context, "Write something first"); return }
        toast(context, "Working on it…")
        unloadJob?.cancel()
        scope.launch {
            try {
                val result = lock.withLock {
                    if (!verified(modelFile(context))) {
                        toast(context, "The writing model download was damaged. Please download it again.")
                        return@launch
                    }
                    val engine = engine(context)
                    engine.startFresh()
                    engine.setSystemPrompt(SYSTEM)
                    val out = StringBuilder()
                    val maxTokens = (source.length / 2 + 64).coerceIn(64, 768)
                    engine.sendUserPrompt("${action.instruction}\n\nText:\n$source", maxTokens).collect { out.append(it) }
                    clean(out.toString())
                }
                if (result.isBlank()) { toast(context, "Couldn't come up with anything, try again"); return@launch }
                withContext(Dispatchers.Main) { replace(ime, selected.isNotEmpty(), before, after, source, result) }
            } catch (e: Throwable) {
                Log.w(TAG, "writing help failed", e)
                toast(context, if (e is UnsatisfiedLinkError) "Writing help needs a 64-bit phone" else "Writing help didn't work: ${e.message}")
            } finally {
                scheduleUnload(context)
            }
        }
    }

    /** Frees the memory the model uses once writing help hasn't been used for a while. */
    private fun scheduleUnload(context: Context) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(90_000)
            lock.withLock { runCatching { AiChat.getInferenceEngine(context.applicationContext).cleanUp() } }
        }
    }

    private fun replace(ime: LatinIME, selectionOnly: Boolean, before: String, after: String, source: String, result: String) {
        val ic = ime.currentInputConnection ?: return
        if (!selectionOnly) {
            val nowBefore = ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()
            val nowAfter = ic.getTextAfterCursor(8000, 0)?.toString().orEmpty()
            if (nowBefore != before || nowAfter != after) { Toast.makeText(ime, "Text changed, not replaced", Toast.LENGTH_SHORT).show(); return }
        }
        ic.beginBatchEdit()
        if (selectionOnly) ic.commitText(result, 1)
        else { ic.deleteSurroundingText(before.length, after.length); ic.commitText(result, 1) }
        ic.endBatchEdit()
        undoText = source
        undoResult = result
        resync(ime)
    }

    private fun undo(context: Context) {
        val ime = ime(context) ?: return
        val ic = ime.currentInputConnection ?: return
        val original = undoText ?: return
        val result = undoResult ?: return
        val before = ic.getTextBeforeCursor(8000, 0)?.toString().orEmpty()
        if (!before.endsWith(result)) { Toast.makeText(ime, "Can't undo now", Toast.LENGTH_SHORT).show(); return }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(result.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        undoText = null; undoResult = null
        resync(ime)
    }

    private fun resync(ime: LatinIME) {
        val ex = ime.currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        if (ex.selectionStart >= 0) ime.mInputLogic.resyncAfterVoiceInput(ex.startOffset + ex.selectionStart, ex.startOffset + ex.selectionEnd)
    }
}
