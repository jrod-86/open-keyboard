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
import kotlinx.coroutines.flow.buffer
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
    fun choose(ctx: Context, m: Model) { llmPrefs(ctx).edit().putString(PREF_MODEL, m.id).apply(); readyCache = null }
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
        // Jarrod: "Longer" turned one line into a whole story. Now: a little longer, same message, nothing made up.
        Action("Longer", "Make it a little longer by expanding on the writer's own idea, saying the same thing more fully. It is a message, not a story: " +
            "do not invent events, places, feelings or details that are not in the text. Keep the same meaning, tone and point of view."),
        Action("Friendlier", "Rewrite it to sound warmer and friendlier. Keep it about the same length."),
        Action("Professional", "Rewrite it to sound clear, polite and professional."),
        Action("Spelling/grammar", "Fix the spelling, grammar and punctuation. Keep the meaning, wording and tone the same."),
        Action("Clearer", "Rewrite it so it is clearer and easier to read."),
        Action("Funnier", "Rewrite it to be funnier and more playful, keeping the same message."),
        Action("More loving", "Rewrite it to sound more loving and affectionate, keeping the same message."),
        Action("Casual", "Rewrite it to sound relaxed and casual, like a text to a friend."),
        Action("Add emojis", "Add a few fitting emojis to it. Do not change the words."),
        // Jarrod (test 196): a cheeky, ruder teasing one, and a rhyming poem
        Action("Teasing", "Rewrite it to be cheeky and a bit rude, with a playful teasing tone, like friends ribbing each other. " +
            "Keep the same message. No slurs and nothing truly hurtful."),
        // small models rhyme badly unless told exactly how, with an example; the answer is also checked (see rhymes())
        Action("Rhyme", "Rewrite it as a short poem in rhyming couplets that says the same thing: 2 or 4 short lines, " +
            "each on its own line. Lines 1 and 2 MUST end with words that rhyme, and so must lines 3 and 4 " +
            "(rhymes sound the same at the end, like day/way, night/light, you/too, fun/done). " +
            "Example: \"Can't wait to see you\" becomes \"I count the hours till we meet,\nseeing you will be so sweet.\" " +
            "No title, no story."),
    )

    private fun modelFile(ctx: Context, m: Model) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "llm/${m.fileName}")
    fun isReady(ctx: Context, m: Model) = modelFile(ctx, m).length() == m.size
    private fun downloadKey(m: Model) = if (m === QUICK) PREF_DOWNLOAD_ID else PREF_DOWNLOAD_ID + "_" + m.id
    fun downloadRunning(ctx: Context, m: Model): Boolean {
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

    fun startDownload(ctx: Context, m: Model) {
        val file = modelFile(ctx, m)
        file.parentFile?.mkdirs()
        file.delete()
        readyCache = null
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        val request = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("O-Board writing model (${m.label})")
            .setDescription("About ${m.sizeText}, needed once for offline writing help")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val id = dm.enqueue(request)
        llmPrefs(ctx).edit().putLong(downloadKey(m), id).apply()
        toast(ctx, "Downloading the ${m.label} model (about ${m.sizeText}). You'll get a notification when it's done.")
    }

    /** True when the writing model is downloaded and checked, so smart compose can use it. */
    fun modelReady(ctx: Context): Boolean {
        // Open Keyboard (test 217): smart compose asks this on the keyboard's main thread after every pause in typing.
        // It looks at up to 6 files on shared storage (slow on Android's storage layer), so the answer is kept for 30 s.
        val now = android.os.SystemClock.uptimeMillis()
        readyCache?.let { (at, ready) -> if (now - at < 30_000) return ready }
        val m = active(ctx)
        val ready = m != null && modelFile(ctx, m).let { f -> File(f.path + ".ok").let { it.exists() && it.lastModified() >= f.lastModified() } }
        readyCache = now to ready
        return ready
    }
    @Volatile private var readyCache: Pair<Long, Boolean>? = null

    /** Whether the writing model is in memory right now (smart compose waits longer before loading it). */
    @Volatile var loaded = false
        private set

    /**
     * Open Keyboard (test 221): true in the keyboard's own process. There the model is never loaded: every request goes
     * to [WriterService] in Chat's process, so the keyboard stays light and fast whatever the model is doing.
     */
    private val remote: Boolean by lazy { !android.app.Application.getProcessName().orEmpty().contains(':') }

    /** The writer service answered: the model is in memory (in Chat's process) for the next 90 s or so. */
    fun markUsed() { loaded = true; scheduleUnload(null) }
    fun writerGone() { loaded = false }

    /** Like [generate], but returns null straight away if the model is busy (Smart compose doesn't wait). */
    suspend fun askIfIdle(ctx: Context, system: String, prompt: String, maxTokens: Int): String? {
        if (remote) return WriterClient.ask(ctx, system, prompt, maxTokens, ifIdle = true)
        if (active(ctx) == null) return null
        return withEngine(ctx) { engine ->
            engine.startFresh()
            engine.setSystemPrompt(system)
            val sb = StringBuilder()
            engine.sendUserPrompt(prompt, maxTokens).collect { sb.append(it) }
            sb.toString()
        }
    }

    /** True when some writing model is downloaded, so the AI writer can run. */
    fun anyDownloaded(ctx: Context) = active(ctx) != null

    /**
     * Runs one request on the writing model and returns the reply, streaming pieces to [onPiece].
     * Waits if writing help is busy. Returns null if no model is downloaded or the download is damaged.
     */
    suspend fun generate(ctx: Context, system: String, prompt: String, maxTokens: Int, onPiece: (String) -> Unit = {}): String? =
        generateFor(ctx, system, prompt, maxTokens, quickOnly = false, modelId = null, onPiece = onPiece)

    suspend fun generateFor(ctx: Context, system: String, prompt: String, maxTokens: Int, quickOnly: Boolean = false, modelId: String? = null, onPiece: (String) -> Unit = {}): String? {
        // test 222: at most 3 minutes, so a "writing…" sign can never hang around
        val explicit = when {
            modelId == QUICK.id || quickOnly -> QUICK
            modelId == BETTER.id -> BETTER
            else -> null
        }
        val wanted = if (explicit != null) explicit.takeIf { isReady(ctx, it) } else active(ctx)
        if (remote) return if (wanted == null) null else withTimeoutOrNull(180_000) { WriterClient.ask(ctx, system, prompt, maxTokens, quickOnly = quickOnly, modelId = modelId, onPiece = onPiece) }
        unloadJob?.cancel()
        try {
            return lock.withLock {
                val m = (if (explicit != null) explicit.takeIf { isReady(ctx, it) } else active(ctx)) ?: return@withLock null
                if (!verified(modelFile(ctx, m), m)) { toast(ctx, "The ${m.label} model download was damaged. Please download it again."); return@withLock null }
                val engine = engine(ctx, explicit)
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

    /** The Chat conversation the model is holding in its memory right now (null = none). */
    @Volatile private var chatInModel: String? = null

    /** The model Chat uses: Better writer when it's downloaded (it chats much better), otherwise Quick. */
    fun chatModel(ctx: Context): Model? {
        // kept in Chat's own settings file: Chat runs in its own process, and two processes writing one file clash
        // Quick by default (test 203): Better plus voice typing and a voice is more memory than the phone likes, so
        // Android kept closing and reopening things, and every reopen loaded the model again
        val wanted = if (ctx.getSharedPreferences("chat", Context.MODE_PRIVATE).getString(PREF_CHAT_MODEL, QUICK.id) == BETTER.id) BETTER else QUICK
        return wanted.takeIf { isReady(ctx, it) } ?: listOf(BETTER, QUICK).firstOrNull { isReady(ctx, it) }
    }
    private const val PREF_CHAT_MODEL = "open_keyboard_chat_model"
    /** Chat's own pick: Better (smarter, slower) or Quick (about twice as fast, simpler). */
    fun chooseChatModel(ctx: Context, m: Model) { ctx.getSharedPreferences("chat", Context.MODE_PRIVATE).edit().putString(PREF_CHAT_MODEL, m.id).apply() }

    /**
     * Loads Chat's model as soon as the screen opens, so the slow part (several seconds, and the phone tidying memory
     * while 2-3 GB arrive) happens while you're still reading, not when you send (test 199 felt sluggish).
     */
    fun warmChat(ctx: Context, onLoading: () -> Unit = {}, onReady: () -> Unit = {}) {
        chatOpen = true
        val m = chatModel(ctx) ?: return
        unloadJob?.cancel()
        scope.launch {
            if (!lock.tryLock()) { main.post(onReady); return@launch }
            try {
                val path = modelFile(ctx, m).absolutePath
                val e = AiChat.getInferenceEngine(ctx.applicationContext)
                if (e.state.value is InferenceEngine.State.ModelReady && loadedPath == path) return@launch
                main.post(onLoading)
                if (!verified(modelFile(ctx, m), m)) return@launch
                engine(ctx, m)
            } catch (e: Throwable) {
                Log.w(TAG, "chat warm-up failed", e)
            } finally {
                lock.unlock()
                scheduleUnload(ctx, chatKeep())
                main.post(onReady)
            }
        }
    }

    /**
     * One turn of the Chat tile. The model keeps the conversation [chatId] in its memory between turns, so only the
     * new message has to be read. If something else used the model meanwhile (writing help, smart compose) or it was
     * unloaded, the chat starts again from [system], which then holds the earlier messages ([history]).
     */
    suspend fun chatTurn(ctx: Context, chatId: String, system: String, history: String, message: String, maxTokens: Int,
                         pace: (suspend () -> Unit)? = null, onPiece: (String) -> Unit): String? {
        unloadJob?.cancel()
        try {
            return lock.withLock {
                val m = chatModel(ctx) ?: return@withLock null
                if (!verified(modelFile(ctx, m), m)) { toast(ctx, "The ${m.label} model download was damaged. Please download it again."); return@withLock null }
                val had = chatInModel
                justLoaded = false
                val engine = engine(ctx, m) // clears chatInModel
                val continuing = had == chatId && engine.state.value is InferenceEngine.State.ModelReady && loadedPath == modelFile(ctx, m).absolutePath && !justLoaded
                justLoaded = false
                if (!continuing) {
                    engine.startFresh()
                    engine.setSystemPrompt(if (history.isBlank()) system else "$system\n\n$history")
                }
                val out = StringBuilder()
                var pieces = 0
                // no buffer: when [pace] waits, the model really stops writing (by default it ran 64 words ahead)
                engine.sendUserPrompt(message, maxTokens).buffer(0).collect {
                    pieces++; out.append(it); onPiece(out.toString())
                    pace?.invoke()
                }
                // finished by itself (not cut off at the length limit): the model now holds this conversation properly
                if (pieces < maxTokens - 2) chatInModel = chatId
                out.toString()
            }
        } finally {
            scheduleUnload(ctx, chatKeep())
        }
    }

    /** Forget the chat held in the model (New chat). */
    fun forgetChat() { chatInModel = null }

    /** Chat closed: free the model's memory after the usual short wait instead of Chat's longer one. */
    fun chatClosed(ctx: Context) { chatOpen = false; if (!lock.isLocked) scheduleUnload(ctx) }

    /**
     * Open Keyboard (test 217): Chat closed while it was still answering used to keep the model (1-3 GB) for 5 more
     * minutes, because the answer's own clean-up set the long timer after the screen had gone. Now the long wait only
     * applies while the Chat screen is open.
     */
    @Volatile private var chatOpen = false
    private fun chatKeep() = if (chatOpen) CHAT_KEEP_MS else 90_000L

    private const val CHAT_KEEP_MS = 5 * 60_000L
    private var justLoaded = false

    /** Lets Undo in Writing help take back text the AI writer put in. */
    fun rememberForUndo(original: String, result: String) { undoText = original; undoResult = result }

    /** Runs [block] with the loaded model, or returns null if writing help is busy right now. */
    suspend fun <T> withEngine(ctx: Context, block: suspend (InferenceEngine) -> T): T? {
        check(!remote) { "the writing model doesn't load in the keyboard's process" }
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
        readyCache = null
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

    private suspend fun engine(ctx: Context, model: Model? = null): InferenceEngine {
        // anyone using the model starts their own conversation, so Chat's one is no longer in its memory
        chatInModel = null
        val engine = AiChat.getInferenceEngine(ctx.applicationContext)
        val state = engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        if (state is InferenceEngine.State.Error) engine.cleanUp()
        // Open Keyboard (test 221): Chat and the keyboard share one model now. If one is already in memory, writing help
        // uses it instead of swapping (swapping means seconds of loading)
        val held = loadedPath?.takeIf { p -> model == null && engine.state.value is InferenceEngine.State.ModelReady && models.any { modelFile(ctx, it).absolutePath == p } }
        val path = held ?: modelFile(ctx, model ?: active(ctx) ?: QUICK).absolutePath
        // switched between Quick and Better writer: load the other one
        if (engine.state.value is InferenceEngine.State.ModelReady && loadedPath != path) {
            engine.cleanUp()
            withTimeoutOrNull(10_000) { engine.state.first { it is InferenceEngine.State.Initialized } }
        }
        if (engine.state.value !is InferenceEngine.State.ModelReady) { engine.loadModel(path); loadedPath = path; justLoaded = true }
        loaded = true
        return engine
    }

    private fun clean(out: String): String {
        var t = out.trim()
        t = t.removePrefix("Here is the rewritten text:").removePrefix("Rewritten text:").trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        return t.trim()
    }

    /** Writing help's styles, for other apps (the keyboard's MCP server, test 226). */
    val styleNames: List<String> get() = actions.map { it.label }

    /** Rewrites [text] in the Writing help style called [label] and returns it, or null if no model / unknown style. */
    suspend fun rewrite(ctx: Context, text: String, label: String): String? {
        val a = actions.firstOrNull { it.label.equals(label, true) } ?: return null
        val out = generate(ctx, SYSTEM, "${a.instruction}\n\nText:\n$text", (text.length / 2 + 64).coerceIn(64, 768)) ?: return null
        return clean(out)
    }

    /** Runs the Writing help button called [label] ("Shorter"), e.g. from voice: "make that shorter". False if it can't. */
    fun runNamed(context: Context, label: String): Boolean {
        val a = actions.firstOrNull { it.label == label } ?: return false
        if (!anyDownloaded(context) || !Plugins.isOn(context, "writing")) return false
        run(context, a)
        return true
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
                val words = source.trim().split(Regex("\\s+")).size
                val instruction = if (action.label == "Longer")
                    action.instruction + " Use about ${(words * 2).coerceIn(words + 8, words + 60)} words at most."
                else action.instruction
                // a token is about 4 characters; Longer gets room for roughly double, the rest about the same length
                val maxTokens = if (action.label == "Rhyme") (source.length + 120).coerceIn(120, 500)
                    else if (action.label == "Longer") (source.length / 2 + 48).coerceIn(48, 400) else (source.length / 2 + 64).coerceIn(64, 768)
                // Open Keyboard (test 221): through generate(), so from the keyboard it runs in Chat's process
                val ask = "$instruction\n\nText:\n$source"
                var c = clean(generate(context, SYSTEM, ask, maxTokens) ?: return@launch)
                // Rhyme: try again (up to 2 more times) if the line endings don't actually rhyme
                var tries = 1
                while (action.label == "Rhyme" && tries < 3 && !rhymes(c)) {
                    tries++
                    c = clean(generate(context, SYSTEM, ask, maxTokens) ?: break)
                }
                // ran out of room mid-sentence: end at the last full sentence
                val end = c.indexOfLast { it == '.' || it == '!' || it == '?' }
                val result = if (action.label == "Longer" && end > 0 && end < c.length - 1 && c.last() !in ".!?…\"')") c.substring(0, end + 1) else c
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

    /** Rough rhyme check: each pair of lines (1+2, 3+4…) ends in words whose last 2 letters match. */
    private fun rhymes(poem: String): Boolean {
        val ends = poem.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("\\s+")).last().lowercase().filter { it.isLetter() } }
        if (ends.size < 2) return false
        return ends.chunked(2).filter { it.size == 2 }.all { (a, b) ->
            a.length >= 2 && b.length >= 2 && a != b && a.takeLast(2) == b.takeLast(2)
        }
    }

    /** Frees the memory the model uses once writing help hasn't been used for a while. */
    /** Frees the model now, e.g. before the Image maker needs the memory for drawing. */
    suspend fun unloadNow(context: Context) {
        if (remote) { WriterClient.free(); return }
        unloadJob?.cancel()
        lock.withLock { runCatching { AiChat.getInferenceEngine(context.applicationContext).cleanUp() }; loaded = false }
    }

    /** Phone short of memory: free the model now unless something is using it this moment (test 219). */
    /** A model file was deleted (Model Manager): check again next time instead of trusting the 30 s cache. */
    fun forgetReady() { readyCache = null }

    fun freeIfIdle(context: Context) {
        if (remote) { WriterClient.free(); return }
        if (!loaded || chatOpen) return
        scope.launch {
            if (!lock.tryLock()) return@launch
            try { runCatching { AiChat.getInferenceEngine(context.applicationContext).cleanUp() }; loaded = false }
            finally { lock.unlock() }
            Log.i(TAG, "writing model freed: phone short of memory")
        }
    }

    private fun scheduleUnload(context: Context?, after: Long = 90_000) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(after)
            // keyboard's process: nothing to free here, Chat's process frees the model by itself after the same wait
            if (remote || context == null) { loaded = false; return@launch }
            lock.withLock { runCatching { AiChat.getInferenceEngine(context.applicationContext).cleanUp() }; loaded = false }
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
