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
 * Talk mode makes it hands-free: after each answer the mic comes back on, say "send" when you've finished.
 */
class ChatActivity : Activity(), Themed {
    override val pageTheme by lazy { ImageThemes.current(this, ImageThemes.night(this), ImageThemes.KEY_CHAT) }

    companion object {
        private const val TAG = "Chat"
        /** Put on the message box: smart compose leaves it alone, and the keyboard knows when to start the mic. */
        const val CHAT_OPTION = "io.github.jrod86.openkeyboard.chat"
        @Volatile private var autoMic = false

        /** Called when the keyboard opens on a field: true once, for Chat's box in talk mode, to start the mic. */
        fun takeAutoMic(editorInfo: EditorInfo?): Boolean {
            if (!autoMic || editorInfo?.privateImeOptions != CHAT_OPTION) return false
            autoMic = false
            return true
        }

        private const val MAX_REPLY_TOKENS = 320
        private const val HISTORY_MESSAGES = 12
    }

    private class Msg(val me: Boolean, var text: String)

    private val prefs by lazy { getSharedPreferences("chat", MODE_PRIVATE) }
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var speaker: ChatSpeaker
    private val messages = ArrayList<Msg>()
    private var chatId = UUID.randomUUID().toString()

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
    private var talkMode: Boolean
        get() = prefs.getBoolean("talk_mode", false)
        set(v) { prefs.edit().putBoolean("talk_mode", v).apply() }

    private val t get() = pageTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        speaker = ChatSpeaker(this)
        val pad = dp(14f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad / 2) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiKit.shinyTitle(this, "💬 Chat"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconButton("🙂", "Show or hide the face") { showFace = !showFace; face.visibility = if (showFace) View.VISIBLE else View.GONE })
        head.addView(iconButton("🗑️", "New chat") { newChat() })
        head.addView(ImageThemes.button(this, ImageThemes.KEY_CHAT))
        head.addView(iconButton("ℹ️", "About Chat") { showHelp() })
        root.addView(head)

        // the talking face: its mouth follows the voice (Jarrod's idea)
        face = TalkingFace(this, t.a1, t.a2) { speaker.level() }.apply { visibility = if (showFace) View.VISIBLE else View.GONE }
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104f)).apply { topMargin = dp(2f) })

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

        ImageThemes.applyPage(this, root, t)
        setContentView(root)
        root.fitAboveKeyboard(includeTop = true)

        modelCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        load()
        rebuild()
        refreshChips()
        if (talkMode && WritingTools.chatModel(this) != null) input.postDelayed({ listen() }, 300)
    }

    override fun onResume() {
        super.onResume()
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
                    WritingTools.chatTurn(applicationContext, chatId, systemPrompt(), history, text, MAX_REPLY_TOKENS) { soFar ->
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
        x = x.trim().removePrefix("Assistant:").removePrefix("You:").removePrefix("Buddy:").trim()
        x = x.replace("**", "").replace(Regex("(?m)^#+\\s*"), "")
        return x.trim()
    }

    private fun systemPrompt(): String {
        val now = Date()
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(now)
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)
        return "You are a friendly chat buddy inside the Open Keyboard app. You are a small AI that runs offline on the user's phone, " +
            "made for casual conversation and fun. Talk like a warm, upbeat friend texting: keep answers short (one to three sentences) " +
            "unless the user asks for more, use plain everyday words, no lists, no headings, no markdown, and at most one emoji. " +
            "Be curious and sometimes ask a question back. You can't go online, look things up or do anything on the phone. " +
            "If you don't know something, say so honestly instead of making it up. It is $day, $time."
    }

    /** The last few messages, for when the model starts this chat again (it was freed, or writing help used it). */
    private fun historyText(): String {
        if (messages.isEmpty()) return ""
        val recent = messages.takeLast(HISTORY_MESSAGES)
        val sb = StringBuilder("The conversation so far (continue it naturally):\n")
        for (m in recent) sb.append(if (m.me) "User: " else "You: ").append(m.text.take(500).replace('\n', ' ')).append('\n')
        return sb.toString().trimEnd()
    }

    private fun newChat() {
        stopAll()
        messages.clear()
        chatId = UUID.randomUUID().toString()
        WritingTools.forgetChat()
        save()
        rebuild()
        toast("New chat started")
    }

    // ---- saving the chat, so it's still there next time ----

    private fun file() = File(filesDir, "chat/conversation.json")

    private fun load() {
        runCatching {
            val f = file()
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            chatId = o.optString("id", chatId)
            val a = o.getJSONArray("messages")
            for (i in 0 until a.length()) { val m = a.getJSONObject(i); messages.add(Msg(m.getBoolean("me"), m.getString("text"))) }
        }.onFailure { Log.w(TAG, "couldn't read the saved chat", it) }
    }

    private fun save() {
        runCatching {
            val a = JSONArray()
            messages.takeLast(200).forEach { a.put(JSONObject().put("me", it.me).put("text", it.text)) }
            val f = file(); f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("id", chatId).put("messages", a).toString())
        }.onFailure { Log.w(TAG, "couldn't save the chat", it) }
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
        if (m != null) addView(TextView(this@ChatActivity).apply {
            text = "Using ${m.label} (${if (m === WritingTools.BETTER) "Qwen3 4B" else "Qwen2.5 1.5B, quicker but simpler"})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(t.text); alpha = 0.6f; setPadding(0, dp(6f), 0, 0)
        })
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

    private fun toggleTalk() {
        talkMode = !talkMode
        if (talkMode) {
            if (currentVoice() != null) speakOn = true
            toast("Talk mode on: talk, then say \"send\". It answers out loud and listens again.")
            if (job?.isActive != true && !speaker.speaking) listen()
        } else {
            autoMic = false
        }
        refreshChips()
    }

    /** Opens the keyboard on the message box with the mic already listening. */
    private fun listen() {
        if (destroyed || !visible || WritingTools.chatModel(this) == null) return
        autoMic = true
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // the mic starts when the keyboard opens, so close it first if it's already up
        imm.hideSoftInputFromWindow(input.windowToken, 0)
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
                "• 🎙️ Talk mode: hands-free. Talk, then say \"send\". It answers out loud, then listens again.\n" +
                "• 🙂 shows or hides the face. Its mouth moves with the voice while it talks.\n" +
                "• Long-press a message to copy it or hear it again. 🗑️ starts a new chat.\n\n" +
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
