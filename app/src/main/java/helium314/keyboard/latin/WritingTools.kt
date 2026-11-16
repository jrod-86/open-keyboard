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
import android.view.View
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Offline AI writing help: fix grammar, change the tone, shorten, add emojis. Runs Qwen2.5 1.5B
 * Instruct (Apache-2.0) with llama.cpp (MIT) on the phone. The model downloads once (about 1.1 GB);
 * after that no text leaves the phone.
 */
object WritingTools {
    private const val TAG = "WritingTools"
    /** A writing model: Quick (small, fast) or Better writer (bigger, warmer writing). Both Apache-2.0. */
    class Model(val id: String, val label: String, val url: String, val sha256: String, val size: Long, val fileName: String, val sizeText: String)
    val QUICK = Model("quick", "Quick",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 1_117_320_736L,
        "qwen2.5-1.5b-instruct-q4_k_m.gguf", "1.1 GB")
    // Qwen3 4B Instruct 2507 (Apache-2.0), Unsloth's GGUF conversion
    val BETTER = Model("better", "Better writer",
        "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597", 2_497_281_120L,
        "qwen3-4b-instruct-2507-q4_k_m.gguf", "2.5 GB")
    private val models = listOf(QUICK, BETTER)
    private const val PREF_MODEL = "open_keyboard_llm_model"
    private var loadedPath: String? = null

    private fun llmPrefs(ctx: Context) = ctx.getSharedPreferences("open_keyboard_llm", Context.MODE_PRIVATE)
    /** The model picked in Writing help (Quick unless changed). */
    fun chosen(ctx: Context): Model = models.firstOrNull { it.id == llmPrefs(ctx).getString(PREF_MODEL, QUICK.id) } ?: QUICK
    fun choose(ctx: Context, m: Model) { llmPrefs(ctx).edit().putString(PREF_MODEL, m.id).apply() }
    /** The model that will actually run: the chosen one, or the other if only that one is downloaded. */
    private fun active(ctx: Context): Model? = chosen(ctx).takeIf { isReady(ctx, it) } ?: models.firstOrNull { isReady(ctx, it) }

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
        Action("Shorter", "Make it shorter and simpler, keeping the key points."),
        Action("Longer", "Make it longer, adding a bit more detail and feeling. Keep the same meaning and tone."),
        Action("Friendlier", "Rewrite it to sound warmer and friendlier. Keep it about the same length."),
        Action("Professional", "Rewrite it to sound clear, polite and professional."),
        Action("Spelling/grammar", "Fix the spelling, grammar and punctuation. Keep the meaning, wording and tone the same."),
        Action("Clearer", "Rewrite it so it is clearer and easier to read."),
        Action("Funnier", "Rewrite it to be funnier and more playful, keeping the same message."),
        Action("More loving", "Rewrite it to sound more loving and affectionate, keeping the same message."),
        Action("Casual", "Rewrite it to sound relaxed and casual, like a text to a friend."),
        Action("Add emojis", "Add a few fitting emojis to it. Do not change the words."),
    )

    private fun modelFile(ctx: Context, m: Model) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "llm/${m.fileName}")
    private fun isReady(ctx: Context, m: Model) = modelFile(ctx, m).length() == m.size
    private fun downloadKey(m: Model) = if (m === QUICK) PREF_DOWNLOAD_ID else PREF_DOWNLOAD_ID + "_" + m.id
    private fun downloadRunning(ctx: Context, m: Model): Boolean {
        val id = llmPrefs(ctx).getLong(downloadKey(m), -1)
        if (id < 0) return false
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return false
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
        }
        return false
    }

    private fun startDownload(ctx: Context, m: Model) {
        val file = modelFile(ctx, m)
        file.parentFile?.mkdirs()
        file.delete()
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        val request = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("Open Keyboard writing model (${m.label})")
            .setDescription("About ${m.sizeText}, needed once for offline writing help")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val id = dm.enqueue(request)
        llmPrefs(ctx).edit().putLong(downloadKey(m), id).apply()
        toast(ctx, "Downloading the ${m.label} model (about ${m.sizeText}). You'll get a notification when it's done.")
    }

    /** True when the writing model is downloaded and checked, so smart compose can use it. */
    fun modelReady(ctx: Context): Boolean {
        val m = active(ctx) ?: return false
        val f = modelFile(ctx, m)
        return File(f.path + ".ok").let { it.exists() && it.lastModified() >= f.lastModified() }
    }

    /** True when some writing model is downloaded, so the AI writer can run. */
    fun anyDownloaded(ctx: Context) = active(ctx) != null

    /**
     * Runs one request on the writing model and returns the reply, streaming pieces to [onPiece].
     * Waits if writing help is busy. Returns null if no model is downloaded or the download is damaged.
     */
    suspend fun generate(ctx: Context, system: String, prompt: String, maxTokens: Int, onPiece: (String) -> Unit = {}): String? {
        unloadJob?.cancel()
        try {
            return lock.withLock {
                val m = active(ctx) ?: return@withLock null
                if (!verified(modelFile(ctx, m), m)) { toast(ctx, "The ${m.label} model download was damaged. Please download it again."); return@withLock null }
                val engine = engine(ctx)
                engine.startFresh()
                engine.setSystemPrompt(system)
                val out = StringBuilder()
                engine.sendUserPrompt(prompt, maxTokens).collect { out.append(it); onPiece(out.toString()) }
                out.toString()
            }
        } finally {
            scheduleUnload(ctx)
        }
    }

    /** Lets Undo in Writing help take back text the AI writer put in. */
    fun rememberForUndo(original: String, result: String) { undoText = original; undoResult = result }

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

    private fun verified(file: File, m: Model): Boolean {
        val marker = File(file.path + ".ok")
        if (marker.exists() && marker.lastModified() >= file.lastModified()) return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == m.sha256
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

        fun row(vararg pills: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            pills.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 3f), dp(context, 4f), dp(context, 3f), dp(context, 4f))
            }) }
        }
        fun heading(s: String) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(context, 3f), dp(context, 10f), 0, dp(context, 2f))
        }
        val ready = anyDownloaded(context)

        // AI writer: write something new from a request
        column.addView(heading("✨ Write something new"))
        if (ready) column.addView(row(pill(if (AiWriter.mode(context) == AiWriter.Mode.WRITER && AiWriter.writerOn) "Turn writer mode off" else AiWriter.buttonLabel(context), true) {
            AiWriter.start(context)
        }))
        val mode = AiWriter.mode(context)
        column.addView(row(
            pill("Talk box", mode == AiWriter.Mode.BOX) { AiWriter.setMode(context, AiWriter.Mode.BOX); show(context) },
            pill("Writer mode", mode == AiWriter.Mode.WRITER) { AiWriter.setMode(context, AiWriter.Mode.WRITER); show(context) },
        ))
        column.addView(note(when (mode) {
            AiWriter.Mode.BOX -> "Talk box: say or type what you want, check it, then tap Insert."
            AiWriter.Mode.WRITER -> "Writer mode: once it's on, type or say your request in the message box and press Enter. " +
                "The AI swaps it for the message. Press Enter again to send."
        } + " Any time, you can also say \"write a birthday message for my wife\" while voice typing, or type /write and your request."))

        // model choice
        column.addView(heading("AI model"))
        val chosen = chosen(context)
        column.addView(row(*models.map { m ->
            pill(m.label + (if (isReady(context, m)) "" else " (${m.sizeText})"), m === chosen) { choose(context, m); show(context) }
        }.toTypedArray()))
        when {
            isReady(context, chosen) -> column.addView(note(if (chosen === BETTER) "Better writer: warmer, more natural writing. Takes a bit longer (about 10 to 15 seconds for a message)."
                else "Quick: fast (a few seconds), but its writing is a bit plain. Try Better writer for messages that matter."))
            downloadRunning(context, chosen) -> column.addView(note("The ${chosen.label} model is downloading (about ${chosen.sizeText}). Check the notification for progress, then open this again."))
            else -> {
                column.addView(note("Writing help runs an AI model on your phone, so your text stays private. " +
                    "${chosen.label} needs a one-time download of about ${chosen.sizeText} (Wi-Fi recommended)." +
                    if (ready) " Until then the other model is used." else ""))
                column.addView(pill("Download ${chosen.label}", true) { KeyboardMenu.hide(); startDownload(context, chosen) })
            }
        }

        // fix up what you already wrote
        if (ready) {
            column.addView(heading("Fix up what you wrote"))
            actions.chunked(2).forEach { pair ->
                column.addView(row(*pair.map { a -> pill(a.label, false) { KeyboardMenu.hide(); run(context, a) } }.toTypedArray()))
            }
            column.addView(note("Works on what you wrote, or on the selected text. Runs on your phone; nothing is sent anywhere."))
        }
        KeyboardMenu.showOverlay(root)
    }

    private suspend fun engine(ctx: Context): InferenceEngine {
        val engine = AiChat.getInferenceEngine(ctx.applicationContext)
        val state = engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        if (state is InferenceEngine.State.Error) engine.cleanUp()
        val path = modelFile(ctx, active(ctx) ?: QUICK).absolutePath
        // switched between Quick and Better writer: load the other one
        if (engine.state.value is InferenceEngine.State.ModelReady && loadedPath != path) {
            engine.cleanUp()
            withTimeoutOrNull(10_000) { engine.state.first { it is InferenceEngine.State.Initialized } }
        }
        if (engine.state.value !is InferenceEngine.State.ModelReady) { engine.loadModel(path); loadedPath = path }
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
        showBusy(action.label)
        unloadJob?.cancel()
        scope.launch {
            try {
                val result = lock.withLock {
                    val m = active(context) ?: return@launch
                    if (!verified(modelFile(context, m), m)) {
                        toast(context, "The ${m.label} model download was damaged. Please download it again.")
                        return@launch
                    }
                    val engine = engine(context)
                    engine.startFresh()
                    engine.setSystemPrompt(SYSTEM)
                    val out = StringBuilder()
                    val maxTokens = if (action.label == "Longer") (source.length + 200).coerceIn(200, 900) else (source.length / 2 + 64).coerceIn(64, 768)
                    engine.sendUserPrompt("${action.instruction}\n\nText:\n$source", maxTokens).collect { out.append(it) }
                    clean(out.toString())
                }
                if (result.isBlank()) { toast(context, "Couldn't come up with anything, try again"); return@launch }
                withContext(Dispatchers.Main) { replace(ime, selected.isNotEmpty(), before, after, source, result) }
            } catch (e: Throwable) {
                Log.w(TAG, "writing help failed", e)
                toast(context, if (e is UnsatisfiedLinkError) "Writing help needs a 64-bit phone" else "Writing help didn't work: ${e.message}")
            } finally {
                main.post { hideBusy() }
                scheduleUnload(context)
            }
        }
    }

    private const val BUSY_TAG = "writing-help-busy"
    private var busyAnim: android.animation.Animator? = null

    /**
     * While writing help works: a ✨ "Shorter…" pill at the top of the keyboard with a light that keeps
     * sweeping across, so it's clear something is happening. Goes away once the text is replaced (or it fails).
     */
    private fun showBusy(label: String) {
        val wrapper = helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().wrapperView ?: return
        wrapper.findViewWithTag<View>(BUSY_TAG)?.let { wrapper.removeView(it) }
        val ctx = wrapper.context
        val colors = Settings.getValues().mColors
        val accent = AiWriter.ON_COLOR
        val box = FrameLayout(ctx).apply { tag = BUSY_TAG; isClickable = false }
        val pill = TextView(ctx).apply {
            text = "✨ $label…"
            setTextColor(colors.get(ColorType.KEY_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(ctx, 16f), dp(ctx, 6f), dp(ctx, 16f), dp(ctx, 6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16f).toFloat()
                setColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 240))
                setStroke(dp(ctx, 2f), accent)
            }
        }
        box.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(ctx, 8f) })
        val track = FrameLayout(ctx)
        box.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 3f), Gravity.TOP))
        val sweep = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x007C4DFF, accent, 0xFFFF80AB.toInt(), accent, 0x007C4DFF))
        }
        val w = wrapper.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        track.addView(sweep, FrameLayout.LayoutParams(w / 3, ViewGroup.LayoutParams.MATCH_PARENT))
        wrapper.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val anims = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(sweep, View.TRANSLATION_X, -w / 3f, w.toFloat()).apply {
                    duration = 1100; repeatCount = android.animation.ValueAnimator.INFINITE
                },
                android.animation.ObjectAnimator.ofFloat(pill, View.ALPHA, 1f, 0.55f).apply {
                    duration = 700; repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                },
            )
        }
        busyAnim?.cancel()
        busyAnim = anims
        anims.start()
    }

    private fun hideBusy() {
        val wrapper = helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().wrapperView ?: return
        val box = wrapper.findViewWithTag<View>(BUSY_TAG) ?: return
        busyAnim?.cancel(); busyAnim = null
        box.animate().alpha(0f).setDuration(200).withEndAction { wrapper.removeView(box) }.start()
    }

    /** Frees the memory the model uses once writing help hasn't been used for a while. */
    /** Frees the model now, e.g. before the Image maker needs the memory for drawing. */
    suspend fun unloadNow(context: Context) {
        unloadJob?.cancel()
        lock.withLock { runCatching { AiChat.getInferenceEngine(context.applicationContext).cleanUp() } }
    }

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
