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
 * The mic never starts by itself on opening; 🎙️ Talk mode (opt-in, off each time) makes it hands-free, say "send".
 */
class ChatActivity : Activity(), Themed {
    override val pageTheme by lazy { ImageThemes.current(this, ImageThemes.night(this), ImageThemes.KEY_CHAT) }

    companion object {
        private const val TAG = "Chat"
        /** Put on the message box: smart compose leaves it alone, and the keyboard knows when to start the mic. */
        const val CHAT_OPTION = "io.github.jrod86.openkeyboard.chat"
        /** Called when the keyboard opens on a field: true once, for Chat's box just after Hands-free asked for the mic. */
        fun takeAutoMic(editorInfo: EditorInfo?): Boolean {
            if (!isChatBox(editorInfo)) return false
            val asked = listenAsked
            if (asked == 0L || System.currentTimeMillis() - asked > 5000) return false
            listenAsked = 0L
            return true
        }

        private const val MAX_REPLY_TOKENS = 220
        private val REFUSAL = Regex("(?i)\\b(can(no|')?t|unable to|not able to) (assist|help) (you )?with (that|this)|as an ai,? i (don't|do not|can't|cannot)")
        /** Hands-free on a new chat: they say hello first, then the mic opens (Jarrod, test 205). */
        private const val GREETING = "(The user has just opened the chat and will talk to you out loud. Say hello and introduce " +
            "yourself in your own style, in one or two short sentences, and ask them something.)"

        /** Sent to the keyboard (in the main process) to start the mic on Chat's box. */
        const val LISTEN_ACTION = "io.github.jrod86.openkeyboard.CHAT_LISTEN"
        @Volatile private var listenAsked = 0L

        /** Keyboard side: Chat asked for the mic (Hands-free on). */
        fun askListen() { listenAsked = System.currentTimeMillis() }

        fun isChatBox(editorInfo: EditorInfo?) = editorInfo?.privateImeOptions?.startsWith(CHAT_OPTION) == true
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
    // Talk mode (Jarrod, test 205): opt-in hands-free chat. It is OFF every time Chat opens, so the mic never starts
    // by itself on opening (test 204); only once you tap 🎙️ Talk mode does it listen, and again after each answer.
    private var talkMode = false

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
        head.addView(iconButton("🙂", "Show or hide the picture") { toggleFace() })
        head.addView(iconButton("➕", "New chat") { newChat() })
        head.addView(ImageThemes.button(this, ImageThemes.KEY_CHAT))
        head.addView(iconButton("ℹ️", "About Chat") { showHelp() })
        root.addView(head)

        // the character's picture: it bounces and glows with the voice (Jarrod's pictures, test 209)
        face = TalkingFace(this, t.a1, t.a2, t.card) { speaker.level() }.apply {
            visibility = if (showFace) View.VISIBLE else View.GONE
            setOnClickListener { changePersonality() }
        }
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)).apply { topMargin = dp(2f) })
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
        talkChip = chip("") { toggleTalk() }
        chips.addView(speakChip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginEnd = dp(4f) })
        chips.addView(voiceChip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        chips.addView(talkChip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginStart = dp(4f) })
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
        listenWhenReady = false // never listen just because Chat opened
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

    /** Sends what's in the box; with [greeting], nothing is sent and they say hello and who they are instead. */
    private fun send(greeting: Boolean = false) {
        if (job?.isActive == true) return
        val text = if (greeting) GREETING else input.text.toString().trim()
        if (text.isEmpty()) return
        if (WritingTools.chatModel(this) == null) { toast("Download a model first (below)"); refreshModelCard(); scrollDown(); return }
        speaker.stop()
        if (!greeting) input.setText("")
        // the earlier messages, in case the model has to start this chat again
        val history = historyText()
        if (!greeting) {
            messages.add(Msg(true, text))
            list.addView(bubble(messages.last()))
        }
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
            var attempt = 0
            suspend fun ask(message: String, hist: String) = withContext(Dispatchers.Default) {
                val mine = ++attempt
                runCatching {
                    WritingTools.chatTurn(applicationContext, chat.id, systemPrompt(), hist, message, MAX_REPLY_TOKENS) { soFar ->
                        view.post {
                            if (destroyed || finished || mine != attempt) return@post
                            val shown = tidy(soFar)
                            // a "Sorry, I can't assist with that" is asked again below, so don't show or say it
                            if (isRefusal(shown)) return@post
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
            var out = ask(text, history)
            // The small model sometimes answers harmless chat ("what's it like over there?") with an assistant-style
            // refusal, then copies it every turn (Jarrod, test 206). Ask once more, fresh, without the refusals.
            if (out != null && isRefusal(tidy(out)) && !destroyed) {
                Log.i(TAG, "refused harmless chat, asking again")
                WritingTools.forgetChat()
                spokenTo = 0
                out = ask("$text\n\n(Friendly casual chat: answer in character, playfully, and make up fun details about yourself if you need to.)",
                    historyText(excludeLast = !greeting))
                if (out != null && isRefusal(tidy(out))) out = null.also { WritingTools.forgetChat() }
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
        return "${p.style} ${ChatPersonalities.soul(p)} You live inside the Open Keyboard app as a small AI that runs offline on the user's phone, made for casual chat and fun. " +
            "This is a friendly pretend chat, not a work task: when asked about yourself, your day, how you're going or what it's like where " +
            "you are, play along in character and invent fun little details about your life inside the phone. Never answer ordinary chat " +
            "with \"I can't assist with that\". Stay in character the whole time. Keep answers short (one to three sentences) unless the user asks for jokes, a story or more, like texting: " +
            "no lists, no headings, no markdown, at most one emoji. Sometimes ask a question back. You can't go online, look things up or do anything " +
            "on the phone. If you don't know something, say so (in character) instead of making it up. It is $day, $time."
    }

    /** The last few messages, for when the model starts this chat again (it was freed, or writing help used it). */
    private fun historyText(excludeLast: Boolean = false): String {
        // earlier refusals are left out: the model would copy them
        val recent = (if (excludeLast) messages.dropLast(1) else messages).filterNot { !it.me && isRefusal(it.text) }.takeLast(HISTORY_MESSAGES)
        if (recent.isEmpty()) return ""
        val sb = StringBuilder("The conversation so far (continue it naturally):\n")
        for (m in recent) sb.append(if (m.me) "User: " else "You: ").append(m.text.take(500).replace('\n', ' ')).append('\n')
        return sb.toString().trimEnd()
    }

    private val personality get() = ChatPersonalities.byId(chat.personality)

    /** The assistant-style brush-off small models give to ordinary chat. */
    private fun isRefusal(s: String) = s.length < 160 && REFUSAL.containsMatchIn(s)

    /** ➕ New chat: pick who to talk to first. */
    private fun newChat() {
        pickPersonality("Who do you want to chat with?") { p ->
            stopAll()
            save()
            prefs.edit().putString("personality", p.id).apply()
            openChat(ChatStore.newChat(p.id))
            toast("New chat with ${p.name}")
            if (talkMode) send(greeting = true) // Hands-free: they say hello, then the mic opens
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
        // each with their picture (test 209)
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = all.size
            override fun getItem(i: Int) = all[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val c = all[i]
                val row = (convert as? LinearLayout) ?: LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                    addView(android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f).toFloat()) }
                        }
                    }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
                    addView(TextView(context).apply { setPadding(dp(14f), 0, 0, 0); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                (row.getChildAt(0) as android.widget.ImageView).setImageResource(c.avatar)
                val who = when (ChatPersonalities.female(c)) { true -> "female voice"; false -> "male voice"; null -> "any voice" }
                (row.getChildAt(1) as TextView).text = "${c.emoji} ${c.name}${if (c.cheeky) "  (cheeky)" else ""}\n${c.about} · $who"
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(all[which]) }
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

    /** 🙂 shows or hides the character's picture (more room for the chat). */
    private fun toggleFace() {
        showFace = !showFace
        face.visibility = if (showFace) View.VISIBLE else View.GONE
    }

    /** The "who you're chatting with" chip, their picture, and their voice. */
    private fun refreshPersonality() {
        val p = personality
        personaChip.text = "${p.emoji} ${p.name} ▾"
        face.avatar = p.avatar
        refreshChips()
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

    /** The voices that suit this character: female ones for the women, male for the men, any for the robots. */
    private fun voicesFor(p: ChatPersonality): List<TtsVoice> {
        val all = ChatSpeaker.voices(this)
        val female = ChatPersonalities.female(p) ?: return all
        return all.filter { it.female == female }.ifEmpty { all }
    }

    /** Each character keeps their own voice; until one is picked, a fitting one (robots get different ones each). */
    private fun currentVoice(): TtsVoice? {
        val p = personality
        val voices = voicesFor(p)
        if (voices.isEmpty()) return null
        voices.firstOrNull { it.id == prefs.getString("voice_" + p.id, null) }?.let { return it }
        val builtIn = voices.filter { !it.custom }.ifEmpty { voices }
        return builtIn[ChatPersonalities.ALL.indexOf(p).coerceAtLeast(0) % builtIn.size]
    }

    private fun toggleSpeak() {
        if (speakOn) { speakOn = false; speaker.stop(); refreshChips(); updateSendButton(); return }
        if (currentVoice() == null) { noVoices(); return }
        speakOn = true
        refreshChips()
    }

    private fun pickVoice() {
        val voices = voicesFor(personality)
        if (voices.isEmpty()) { noVoices(); return }
        val cur = currentVoice()
        val labels = voices.map { v ->
            val engine = if (v.engine == TextToSpeechActivity.ZONOS) "Zonos" else "Kokoro"
            "${v.badge} ${v.name} · ${if (v.custom) "your voice" else v.about} ($engine)"
        }
        AlertDialog.Builder(this)
            .setTitle("${personality.name}'s voice")
            .setSingleChoiceItems(labels.toTypedArray(), voices.indexOf(cur)) { d, which ->
                prefs.edit().putString("voice_" + personality.id, voices[which].id).apply()
                speakOn = true
                d.dismiss()
                refreshChips()
                val v = voices[which]
                speaker.begin(v); speaker.add(if (v.custom) "Hi! This is the voice you made. Let's chat." else "Hi, I'm ${personality.name}. Let's chat!")
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

    private fun toggleTalk() {
        talkMode = !talkMode
        if (talkMode) {
            if (currentVoice() != null) speakOn = true
            toast("Hands-free on: talk, then say \"send\". It answers, then listens again. Tap again to stop.")
            when {
                job?.isActive == true || speaker.speaking -> {} // listens once it has finished answering
                messages.isEmpty() && WritingTools.chatModel(this) != null -> send(greeting = true) // hello first, then the mic
                else -> listen()
            }
        } else {
            hideKeyboard() // stops the mic
        }
        refreshChips()
    }

    /**
     * Opens the keyboard on the message box with the mic listening. Test 205 changed the box's options and asked for the
     * keyboard, but the phone didn't show it, so the mic never started. Now Chat asks the keyboard directly: it shows
     * itself on the box if needed and starts the mic.
     */
    private fun listen() {
        if (destroyed || !visible || !talkMode || WritingTools.chatModel(this) == null) return
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { window.insetsController?.show(android.view.WindowInsets.Type.ime()) }
        input.postDelayed({
            if (destroyed || !talkMode) return@postDelayed
            sendBroadcast(Intent(LISTEN_ACTION).setPackage(packageName))
        }, 150)
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
        talkChip.text = if (talkMode) "🎙️ Hands-free: on" else "🎙️ Hands-free"
        UiKit.setChosen(talkChip, talkMode, t.text)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("💬 Chat")
            .setMessage("A small AI to chat with for fun. It runs on your phone, offline, so it's private, but it's nowhere near as clever " +
                "as the big online AIs: expect wrong facts and silly answers.\n\n" +
                "• Type, or tap the keyboard's mic and talk.\n" +
                "• 🔊 Talks: reads its answers out loud. Tap the voice to pick one from the TTS tile, including voices you made.\n" +
                "• Tap the keyboard's mic to talk to it. Or tap 🎙️ Hands-free: talk, say \"send\", it answers, then listens again. It is off whenever Chat opens.\n" +
                "• Tap the picture (or the name under it) to chat with someone else. Each one has their own story and voice: the " +
                "women talk with female voices, the men with male ones, the robots with either. 🙂 hides or shows the picture.\n" +
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
