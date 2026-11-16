// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.fitAboveKeyboard
import helium314.keyboard.media.ImageThemes
import helium314.keyboard.media.Themed
import helium314.keyboard.media.UiKit
import helium314.keyboard.tts.ChatSpeaker
import helium314.keyboard.tts.TextToSpeechActivity
import helium314.keyboard.tts.TtsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 💬 Chat tile: casual, just-for-fun chatting with the writing model that runs on the phone (Better writer when it's
 * downloaded, else Quick). Type or talk (the keyboard's voice typing), and it can answer out loud with any TTS voice.
 * The mic only ever starts when you tap it (Jarrod, test 204: no hands-free Talk mode).
 */
class ChatActivity : Activity(), Themed {
    override val pageTheme by lazy { ImageThemes.current(this, ImageThemes.night(this), ImageThemes.KEY_CHAT) }

    companion object {
        private const val TAG = "Chat"
        /** Put on the message box: smart compose leaves it alone, and the keyboard knows when to start the mic. */
        const val CHAT_OPTION = "io.github.jrod86.openkeyboard.chat"
        /** Chat runs in its own process (test 201), so "start the mic" travels to the keyboard in the box's options, with a time. */
        private const val LISTEN = ";listen="
        @Volatile private var lastListen = ""

        /** Called when the keyboard opens on a field: true once, for Chat's box in talk mode, to start the mic. */
        fun takeAutoMic(editorInfo: EditorInfo?): Boolean {
            val opts = editorInfo?.privateImeOptions ?: return false
            if (!opts.startsWith(CHAT_OPTION + LISTEN)) return false
            val stamp = opts.substringAfter(LISTEN)
            val at = stamp.toLongOrNull() ?: return false
            // only once per request, and only right after it was made
            if (stamp == lastListen || System.currentTimeMillis() - at > 5000) return false
            lastListen = stamp
            return true
        }

        private const val MAX_REPLY_TOKENS = 220
        private const val HISTORY_MESSAGES = 12
    }

    private class Msg(val me: Boolean, var text: String)

    private val prefs by lazy { getSharedPreferences("chat", MODE_PRIVATE) }
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var speaker: ChatSpeaker
    private val messages = ArrayList<Msg>()
    private lateinit var personaChip: TextView
    private lateinit var wakeNote: TextView
    private var listenWhenReady = false

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var speakChip: TextView
    private lateinit var voiceChip: TextView
    private lateinit var talkChip: TextView
    private lateinit var modelCard: LinearLayout
    private lateinit var face: TalkingFace
    private var destroyed = false
    private var visible = false

    private var speakOn: Boolean
        get() = prefs.getBoolean("speak", false)
        set(v) { prefs.edit().putBoolean("speak", v).apply() }
    private var showFace: Boolean
        get() = prefs.getBoolean("face", true)
        set(v) { prefs.edit().putBoolean("face", v).apply() }
    // Jarrod (test 203): the mic must never turn itself on, so Talk mode (mic back on after each answer) is gone
    private val talkMode: Boolean
        get() = false

    private val t get() = pageTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        speaker = ChatSpeaker(this)
        val pad = dp(14f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad / 2) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconButton("☰", "Your chats") { openDrawer() }.apply { setTextColor(t.text) })
        head.addView(UiKit.shinyTitle(this, "💬 Chat"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconButton("🙂", "Face style") { pickFace() })
        head.addView(iconButton("➕", "New chat") { newChat() })
        head.addView(ImageThemes.button(this, ImageThemes.KEY_CHAT))
        head.addView(iconButton("ℹ️", "About Chat") { showHelp() })
        root.addView(head)

        // the talking face: its mouth follows the voice (Jarrod's idea)
        face = TalkingFace(this, t.a1, t.a2, t.card) { speaker.level() }.apply {
            visibility = if (showFace) View.VISIBLE else View.GONE
            style = TalkingFace.Style.entries.firstOrNull { it.name == prefs.getString("face_style", null) } ?: TalkingFace.Style.CARTOON
            setOnClickListener { pickFace() }
        }
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150f)).apply { topMargin = dp(2f) })
        personaChip = chip("") { changePersonality() }
        root.addView(personaChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4f) })
        wakeNote = TextView(this).apply {
            text = "⏳ Waking up… (a few seconds, the phone is loading the AI)"
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(t.text); alpha = 0.75f
            setPadding(0, dp(4f), 0, 0); visibility = View.GONE
        }
        root.addView(wakeNote)

        // voice controls: read answers out loud, which voice, hands-free talk mode
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4f), 0, dp(6f)) }
        speakChip = chip("") { toggleSpeak() }
        voiceChip = chip("") { pickVoice() }
        talkChip = chip("") { }
        chips.addView(speakChip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginEnd = dp(4f) })
        chips.addView(voiceChip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        // (no Talk mode chip any more)
        root.addView(chips)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4f), 0, dp(8f)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(6f), 0, dp(4f)) }
        input = UiKit.field(this, "Say something…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setHorizontallyScrolling(false)
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            privateImeOptions = CHAT_OPTION
            background = UiKit.rounded(this@ChatActivity, 22f, t.card, t.line)
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendButton = TextView(this).apply {
            text = "➤"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.a1, t.a2)).apply { shape = GradientDrawable.OVAL }
            contentDescription = "Send"
            setOnClickListener { if (job?.isActive == true || speaker.speaking) stopAll() else send() }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { marginStart = dp(8f) })
        root.addView(bar)

        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildDrawer(frame)
        ImageThemes.applyPage(this, frame, t)
        setContentView(frame)
        frame.fitAboveKeyboard(includeTop = true)
        personaChip.setPadding(dp(16f), 0, dp(16f), 0)

        modelCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        load()
        rebuild()
        refreshPersonality()
        refreshChips()
        // talk mode: the mic waits until the AI has loaded (talking during the load made everything stutter, test 201)
        listenWhenReady = talkMode && WritingTools.chatModel(this) != null
    }

    override fun onResume() {
        super.onResume()
        // load the model while you read, not when you send
        WritingTools.warmChat(applicationContext,
            onLoading = { if (!destroyed) { wakeNote.visibility = View.VISIBLE; face.paused = true } },
            onReady = {
                if (destroyed) return@warmChat
                wakeNote.visibility = View.GONE
                face.paused = false
                if (listenWhenReady) { listenWhenReady = false; listen() }
            })
        refreshModelCard()
        refreshChips()
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // left the screen: stop talking (the answer itself keeps coming and is saved)
        speaker.stop()
        updateSendButton()
    }

    override fun onDestroy() {
        destroyed = true
        job?.cancel()
        speaker.close()
        WritingTools.chatClosed(this)
        scope.cancel()
        super.onDestroy()
    }

    // ---- the conversation ----

    private fun send() {
        if (job?.isActive == true) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (WritingTools.chatModel(this) == null) { toast("Download a model first (below)"); refreshModelCard(); scrollDown(); return }
        speaker.stop()
        input.setText("")
        // the earlier messages, in case the model has to start this chat again
        val history = historyText()
        messages.add(Msg(true, text))
        list.addView(bubble(messages.last()))
        val reply = Msg(false, "")
        val view = bubble(reply, thinking = true)
        // sent while the AI is still loading: say so, rather than a "…" that looks stuck (Jarrod, test 202)
        if (wakeNote.visibility == View.VISIBLE) setBubbleText(view, "⏳ Waking up, I'll answer in a moment…")
        list.addView(view)
        scrollDown()
        if (talkMode) hideKeyboard() // stops the mic while it answers and talks

        val voice = if (speakOn) currentVoice() else null
        if (voice != null) speaker.begin(voice)
        var spokenTo = 0
        var finished = false // pieces still on their way to the screen are dropped once the whole answer is in
        val started = System.currentTimeMillis()
        face.thinking = true
        job = scope.launch {
            updateSendButton()
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    WritingTools.chatTurn(applicationContext, chat.id, systemPrompt(), history, text, MAX_REPLY_TOKENS) { soFar ->
                        view.post {
                            if (destroyed || finished) return@post
                            val shown = tidy(soFar)
                            if (shown.isNotEmpty()) { reply.text = shown; setBubbleText(view, shown); scrollDown() }
                            // say each finished sentence while the next one is being written
                            if (voice != null) {
                                val cut = lastSentenceEnd(soFar, spokenTo)
                                if (cut > spokenTo && soFar.substring(spokenTo, cut).trim().length >= 3) {
                                    speaker.add(soFar.substring(spokenTo, cut)); spokenTo = cut
                                }
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "chat turn failed", it) }.getOrNull()
            }
            finished = true
            face.thinking = false
            if (destroyed) return@launch
            val answer = out?.let { tidy(it) }.orEmpty()
            Log.i(TAG, "answered ${answer.length} chars in ${System.currentTimeMillis() - started} ms")
            if (answer.isEmpty()) {
                if (reply.text.isEmpty()) {
                    list.removeView(view)
                    list.addView(note(if (out == null && WritingTools.chatModel(this@ChatActivity) == null) "No chat model downloaded yet."
                        else "Hmm, no answer came. Please try again."))
                }
                if (voice != null) speaker.stop()
            } else {
                reply.text = answer
                setBubbleText(view, answer)
                if (voice != null && out != null && spokenTo < out.length) speaker.add(out.substring(spokenTo))
            }
            if (reply.text.isNotEmpty()) messages.add(reply)
            save()
            scrollDown()
            if (voice != null && answer.isNotEmpty()) speaker.finish { updateSendButton(); if (talkMode && !destroyed) listen() }
            else if (talkMode) listen()
            updateSendButton()
        }
        job?.invokeOnCompletion { cause ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                face.thinking = false
                // stopped part way: keep what it had said so far
                if (cause != null) { finished = true; if (reply.text.isNotEmpty() && messages.lastOrNull() !== reply) { messages.add(reply); save() } }
                updateSendButton()
            }
        }
    }

    private fun stopAll() {
        job?.cancel()
        face.thinking = false
        speaker.stop()
        updateSendButton()
    }

    private fun updateSendButton() {
        val busy = job?.isActive == true || speaker.speaking
        sendButton.text = if (busy) "■" else "➤"
        sendButton.contentDescription = if (busy) "Stop" else "Send"
    }

    /** Where the last whole sentence in [s] (after [from]) ends, or [from] if there's none yet. */
    private fun lastSentenceEnd(s: String, from: Int): Int {
        var end = from
        Regex("[.!?…]+[\"'”’)]*\\s+|\\n+").findAll(s, from).forEach { end = it.range.last + 1 }
        return end
    }

    /** Small models sometimes add a label, think-tags or markdown: keep just the words. */
    private fun tidy(s: String): String {
        var x = s.replace(Regex("(?s)<think>.*?(</think>|$)"), "")
        x = x.trim().removePrefix("Assistant:").removePrefix("You:").trim().let { y -> ChatPersonalities.ALL.fold(y) { acc, p -> acc.removePrefix(p.name + ":") } }.trim()
        x = x.replace("**", "").replace(Regex("(?m)^#+\\s*"), "")
        return x.trim()
    }

    private fun systemPrompt(): String {
        val now = Date()
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(now)
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)
        val p = personality
        return "${p.style} You live inside the Open Keyboard app as a small AI that runs offline on the user's phone, made for casual chat and fun. " +
            "Stay in character the whole time. Keep answers short (one to three sentences) unless the user asks for more, like texting: " +
            "no lists, no headings, no markdown, at most one emoji. Sometimes ask a question back. You can't go online, look things up or do anything " +
            "on the phone. If you don't know something, say so (in character) instead of making it up. It is $day, $time."
    }

    /** The last few messages, for when the model starts this chat again (it was freed, or writing help used it). */
    private fun historyText(): String {
        if (messages.isEmpty()) return ""
        val recent = messages.takeLast(HISTORY_MESSAGES)
        val sb = StringBuilder("The conversation so far (continue it naturally):\n")
        for (m in recent) sb.append(if (m.me) "User: " else "You: ").append(m.text.take(500).replace('\n', ' ')).append('\n')
        return sb.toString().trimEnd()
    }

    private val personality get() = ChatPersonalities.byId(chat.personality)

    /** ➕ New chat: pick who to talk to first. */
    private fun newChat() {
        pickPersonality("Who do you want to chat with?") { p ->
            stopAll()
            save()
            prefs.edit().putString("personality", p.id).apply()
            openChat(ChatStore.newChat(p.id))
            toast("New chat with ${p.name}")
        }
    }

    /** Changes who you're talking to in this chat (they carry on from what was said). */
    private fun changePersonality() {
        pickPersonality("Change personality") { p ->
            if (p.id == chat.personality) return@pickPersonality
            stopAll()
            chat.personality = p.id
            prefs.edit().putString("personality", p.id).apply()
            WritingTools.forgetChat() // the model has to hear the new character
            save()
            refreshPersonality()
            toast("Now chatting with ${p.name}")
        }
    }

    private fun pickPersonality(title: String, onPick: (ChatPersonality) -> Unit) {
        val all = ChatPersonalities.ALL
        val labels = all.map { "${it.emoji}  ${it.name}${if (it.cheeky) "  (cheeky)" else ""}\n      ${it.about}" }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onPick(all[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChat(c: SavedChat) {
        stopAll()
        chat = c
        messages.clear()
        c.messages.forEach { (me, text) -> messages.add(Msg(me, text)) }
        WritingTools.forgetChat()
        rebuild()
        refreshPersonality()
    }

    // ---- saving chats, so they're still there next time (☰ lists them) ----

    private lateinit var chat: SavedChat

    private fun load() {
        val last = prefs.getString("last_chat", null)?.let { ChatStore.load(this, it) }
        chat = last ?: ChatStore.all(this).firstOrNull() ?: ChatStore.newChat(prefs.getString("personality", "buddy")!!)
        messages.clear()
        chat.messages.forEach { (me, text) -> messages.add(Msg(me, text)) }
    }

    private fun save() {
        if (!::chat.isInitialized) return
        chat.messages.clear()
        messages.forEach { chat.messages.add(it.me to it.text) }
        if (chat.title.isEmpty()) chat.title = ChatStore.titleFor(chat.messages)
        chat.updated = System.currentTimeMillis()
        ChatStore.save(this, chat)
        prefs.edit().putString("last_chat", chat.id).apply()
    }

    // ---- ☰ the side bar of old chats ----

    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var drawerList: LinearLayout
    private var drawerOpen = false

    private fun buildDrawer(frame: android.widget.FrameLayout) {
        scrim = View(this).apply { setBackgroundColor(0x88000000.toInt()); visibility = View.GONE; setOnClickListener { closeDrawer() } }
        frame.addView(scrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = minOf(resources.displayMetrics.widthPixels * 82 / 100, dp(340f))
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            setBackgroundColor(t.bg)
            elevation = dp(12f).toFloat()
            visibility = View.GONE
            isClickable = true
        }
        drawer.addView(TextView(this).apply {
            text = "Your chats"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text)
            setPadding(0, 0, 0, dp(10f))
        })
        drawer.addView(UiKit.glow(this, "➕  New chat") { closeDrawer(); newChat() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10f), 0, 0) }
        drawer.addView(ScrollView(this).apply { addView(drawerList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        drawer.addView(TextView(this).apply {
            text = "Hold a chat to rename or delete it."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(t.text); alpha = 0.6f
            setPadding(0, dp(8f), 0, 0)
        })
        frame.addView(drawer, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    private fun openDrawer() {
        hideKeyboard()
        save()
        fillDrawer()
        drawerOpen = true
        scrim.visibility = View.VISIBLE; scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(180).start()
        drawer.visibility = View.VISIBLE
        drawer.translationX = -drawer.width.toFloat().coerceAtLeast(dp(340f).toFloat())
        drawer.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun fillDrawer() {
        drawerList.removeAllViews()
        val chats = ChatStore.all(this)
        if (chats.isEmpty()) drawerList.addView(TextView(this).apply {
            text = "No saved chats yet."; setTextColor(t.text); alpha = 0.7f; setPadding(0, dp(8f), 0, 0)
        })
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        for (c in chats) {
            val p = ChatPersonalities.byId(c.personality)
            val current = ::chat.isInitialized && c.id == chat.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = if (current) UiKit.rounded(this@ChatActivity, 14f, t.card, t.a1) else UiKit.rounded(this@ChatActivity, 14f, t.card)
                setOnClickListener { closeDrawer(); if (!current) { save(); openChat(c) } }
                setOnLongClickListener { chatMenu(c); true }
            }
            row.addView(TextView(this).apply { text = p.emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(0, 0, dp(10f), 0) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = c.title.ifEmpty { "New chat" }; maxLines = 2; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(t.text)
            })
            texts.addView(TextView(this).apply {
                text = "${p.name} · ${fmt.format(Date(c.updated))}"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(t.text); alpha = 0.6f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            drawerList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
        }
    }

    private fun chatMenu(c: SavedChat) {
        AlertDialog.Builder(this).setItems(arrayOf("✏️ Rename", "🗑️ Delete")) { _, which ->
            if (which == 0) {
                val field = EditText(this).apply { setText(c.title); setSelection(text.length) }
                AlertDialog.Builder(this).setTitle("Rename chat").setView(field)
                    .setPositiveButton("Save") { _, _ ->
                        c.title = field.text.toString().trim().take(60)
                        if (::chat.isInitialized && c.id == chat.id) chat.title = c.title
                        ChatStore.save(this, c); fillDrawer()
                    }.setNegativeButton("Cancel", null).show()
            } else {
                AlertDialog.Builder(this).setMessage("Delete this chat?")
                    .setPositiveButton("Delete") { _, _ ->
                        ChatStore.delete(this, c.id)
                        if (::chat.isInitialized && c.id == chat.id) openChat(ChatStore.newChat(c.personality))
                        fillDrawer()
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    /** 🙂 the face's look, or no face. */
    private fun pickFace() {
        val styles = TalkingFace.Style.entries
        val labels = styles.map { it.label } + "🚫 No face"
        val cur = if (!showFace) styles.size else styles.indexOf(face.style)
        AlertDialog.Builder(this).setTitle("Face")
            .setSingleChoiceItems(labels.toTypedArray(), cur) { d, which ->
                if (which == styles.size) { showFace = false; face.visibility = View.GONE }
                else {
                    showFace = true; face.visibility = View.VISIBLE
                    face.style = styles[which]; prefs.edit().putString("face_style", styles[which].name).apply()
                }
                d.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    /** The "who you're chatting with" chip, and the face's cheeky eyebrows. */
    private fun refreshPersonality() {
        val p = personality
        personaChip.text = "${p.emoji} ${p.name} ▾"
        face.cheeky = p.cheeky
    }

    // ---- views ----

    private fun rebuild() {
        list.removeAllViews()
        list.addView(introCard())
        list.addView(modelCard)
        refreshModelCard()
        messages.forEach { list.addView(bubble(it)) }
        scrollDown()
    }

    /** The honest note: it's a small model, for fun. */
    private fun introCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = UiKit.rounded(this@ChatActivity, 16f, t.card, t.line)
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        addView(TextView(this@ChatActivity).apply {
            text = "🧸 Just for fun"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text)
        })
        addView(TextView(this@ChatActivity).apply {
            text = "This is a small AI that lives on your phone. It works offline and nothing you say leaves the phone. " +
                "It's not very smart: it gets facts wrong, forgets things and makes stuff up, so don't rely on it for anything important. " +
                "Just have a chat and some fun."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(t.text); alpha = 0.85f; setPadding(0, dp(4f), 0, 0)
        })
        val m = WritingTools.chatModel(this@ChatActivity)
        if (m != null) {
            val other = if (m === WritingTools.BETTER) WritingTools.QUICK else WritingTools.BETTER
            val canSwitch = WritingTools.isReady(this@ChatActivity, other)
            addView(TextView(this@ChatActivity).apply {
                text = (if (m === WritingTools.BETTER) "🧠 Using Better (Qwen3 4B): smarter, slower" else "⚡ Using Quick (Qwen2.5 1.5B): about twice as fast, simpler") +
                    (if (canSwitch) "\nTap to switch to ${if (other === WritingTools.BETTER) "🧠 Better" else "⚡ Quick"}" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(t.text); alpha = 0.7f; setPadding(0, dp(6f), 0, 0)
                if (canSwitch) setOnClickListener {
                    if (job?.isActive == true) return@setOnClickListener
                    WritingTools.chooseChatModel(this@ChatActivity, other)
                    WritingTools.forgetChat()
                    rebuild()
                    WritingTools.warmChat(applicationContext)
                    toast(if (other === WritingTools.BETTER) "Switched to Better" else "Switched to Quick")
                }
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
    }

    /** Shown while no model is downloaded: get one here (the same models as Writing help). */
    private fun refreshModelCard() {
        if (!::modelCard.isInitialized) return
        modelCard.removeAllViews()
        if (WritingTools.chatModel(this) != null) { modelCard.visibility = View.GONE; return }
        modelCard.visibility = View.VISIBLE
        modelCard.background = UiKit.rounded(this, 16f, t.card, t.a1)
        modelCard.setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
        modelCard.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
        val downloading = listOf(WritingTools.BETTER, WritingTools.QUICK).filter { WritingTools.downloadRunning(this, it) }
        modelCard.addView(TextView(this).apply {
            text = if (downloading.isNotEmpty()) "Downloading ${downloading.first().label}… You'll get a notification when it's done, then come back here."
                else "Chat needs a model on your phone, downloaded once (Wi-Fi is best). It's the same one Writing help uses."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(t.text)
        })
        if (downloading.isEmpty()) {
            modelCard.addView(UiKit.glow(this, "Better: ${WritingTools.BETTER.sizeText}, chats much better") { WritingTools.startDownload(this, WritingTools.BETTER); refreshModelCard() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)).apply { topMargin = dp(10f) })
            modelCard.addView(UiKit.chip(this, "Quick: ${WritingTools.QUICK.sizeText}, faster but simpler") { WritingTools.startDownload(this, WritingTools.QUICK); refreshModelCard() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)).apply { topMargin = dp(8f) })
        }
    }

    private fun bubble(m: Msg, thinking: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.me) Gravity.END else Gravity.START
            setPadding(if (m.me) dp(48f) else 0, dp(3f), if (m.me) 0 else dp(48f), dp(3f))
        }
        val tv = TextView(this).apply {
            text = if (thinking && m.text.isEmpty()) "…" else m.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            if (m.me) {
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.a1, t.a2)).apply { cornerRadius = dp(18f).toFloat() }
            } else {
                setTextColor(t.text)
                background = UiKit.rounded(this@ChatActivity, 18f, t.card, t.line)
            }
            setOnLongClickListener { bubbleMenu(m); true }
        }
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.tag = tv
        return row
    }

    private fun setBubbleText(row: View, s: String) { (row.tag as? TextView)?.text = s }

    private fun note(s: String) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(t.text); alpha = 0.7f
        setPadding(0, dp(6f), 0, dp(6f))
    }

    private fun bubbleMenu(m: Msg) {
        val options = mutableListOf("Copy")
        if (!m.me) options += "🔊 Say it"
        AlertDialog.Builder(this).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Copy" -> {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chat", m.text))
                    toast("Copied")
                }
                else -> {
                    val v = currentVoice() ?: run { pickVoice(); return@setItems }
                    speaker.begin(v); speaker.add(m.text); speaker.finish { updateSendButton() }
                    updateSendButton()
                }
            }
        }.show()
    }

    private fun scrollDown() {
        // smoothScrollTo, not fullScroll: fullScroll would take the focus from the message box
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }

    // ---- voice ----

    private fun currentVoice(): TtsVoice? {
        val voices = ChatSpeaker.voices(this)
        if (voices.isEmpty()) return null
        return voices.firstOrNull { it.id == prefs.getString("voice", null) } ?: voices[0]
    }

    private fun toggleSpeak() {
        if (speakOn) { speakOn = false; speaker.stop(); refreshChips(); updateSendButton(); return }
        if (currentVoice() == null) { noVoices(); return }
        speakOn = true
        refreshChips()
    }

    private fun pickVoice() {
        val voices = ChatSpeaker.voices(this)
        if (voices.isEmpty()) { noVoices(); return }
        val cur = currentVoice()
        val labels = voices.map { v ->
            val engine = if (v.engine == TextToSpeechActivity.ZONOS) "Zonos" else "Kokoro"
            "${v.badge} ${v.name} · ${if (v.custom) "your voice" else v.about} ($engine)"
        }
        AlertDialog.Builder(this)
            .setTitle("Chat voice")
            .setSingleChoiceItems(labels.toTypedArray(), voices.indexOf(cur)) { d, which ->
                prefs.edit().putString("voice", voices[which].id).apply()
                speakOn = true
                d.dismiss()
                refreshChips()
                val v = voices[which]
                speaker.begin(v); speaker.add(if (v.custom) "Hi! This is the voice you made. Let's chat." else "Hi, I'm ${v.name}. Let's chat!")
                speaker.finish { updateSendButton() }
                updateSendButton()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun noVoices() {
        AlertDialog.Builder(this)
            .setTitle("Get a voice first")
            .setMessage("Chat talks with the voices from the TTS tile. Open TTS and download Kokoro (small and quick) or Zonos " +
                "(the most natural, and it can use a voice you made), then come back here.")
            .setPositiveButton("Open TTS") { _, _ ->
                runCatching { startActivity(Intent(this, TextToSpeechActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Opens the keyboard on the message box with the mic already listening. */
    private fun listen() {
        if (destroyed || !visible || WritingTools.chatModel(this) == null) return
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // the mic starts when the keyboard opens on the box with a fresh "listen" stamp, so close it first if it's up
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.privateImeOptions = CHAT_OPTION + LISTEN + System.currentTimeMillis()
        imm.restartInput(input)
        input.postDelayed({ if (!destroyed) { input.requestFocus(); imm.showSoftInput(input, 0) } }, 250)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun refreshChips() {
        val v = currentVoice()
        speakChip.text = if (speakOn && v != null) "🔊 Talks: on" else "🔇 Talks: off"
        UiKit.setChosen(speakChip, speakOn && v != null, t.text)
        voiceChip.text = if (v != null) "🗣️ ${v.name}" else "🗣️ Voice"
        UiKit.setChosen(voiceChip, false, t.text)
        talkChip.text = if (talkMode) "🎙️ Talk mode" else "🎙️ Talk mode"
        UiKit.setChosen(talkChip, talkMode, t.text)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("💬 Chat")
            .setMessage("A small AI to chat with for fun. It runs on your phone, offline, so it's private, but it's nowhere near as clever " +
                "as the big online AIs: expect wrong facts and silly answers.\n\n" +
                "• Type, or tap the keyboard's mic and talk.\n" +
                "• 🔊 Talks: reads its answers out loud. Tap the voice to pick one from the TTS tile, including voices you made.\n" +
                "• Tap the keyboard's mic to talk to it; the mic only ever turns on when you tap it.\n" +
                "• 🙂 (or tap the face) picks its look: Cartoon, Matrix, Robot screen or Neon, or no face. Its mouth moves with the voice.\n" +
                "• Tap the name under the face to change personality: 11 friendly ones and 4 cheeky ones (Gremlin, Grumpy, Roaster, Diva).\n" +
                "• ➕ starts a new chat. ☰ shows your old chats (hold one to rename or delete it).\n" +
                "• Long-press a message to copy it or hear it again.\n\n" +
                "It uses Better writer (Qwen3 4B) if you have it, otherwise Quick. The model and voices are only kept in memory while you use them.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- small helpers ----

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; maxLines = 1; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(6f), 0, dp(6f), 0)
        background = UiKit.card(this@ChatActivity, 20f)
        setTextColor(t.text)
        setOnClickListener { onClick() }
    }

    private fun iconButton(icon: String, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setPadding(dp(7f), dp(8f), dp(7f), dp(4f))
        contentDescription = description; setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float) = UiKit.dp(this, v)
}
