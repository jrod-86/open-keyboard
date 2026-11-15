// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.utils.fitAboveKeyboard
import helium314.keyboard.media.UiKit
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Text to speech tile: type or bring words from the chat, pick one of six Kokoro voices, hear it, share or save it. */
class TextToSpeechActivity : Activity() {
    companion object {
        const val EXTRA_TEXT = "text"
        private const val TAG = "TextToSpeech"
        private const val RATE = 24000
        private val SPEEDS = listOf(0.8f to "🐢 Slower", 1.0f to "Normal", 1.25f to "🐇 Faster")
    }

    private val prefs by lazy { getSharedPreferences("text_to_speech", MODE_PRIVATE) }
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var speak: Button
    private lateinit var actions: LinearLayout
    private lateinit var downloadCard: LinearLayout
    private lateinit var downloadButton: Button
    private val voiceCards = mutableMapOf<String, LinearLayout>()
    private val speedButtons = mutableListOf<TextView>()
    private var voice = KokoroTts.VOICES[0]
    private var speed = 1.0f

    @Volatile private var stopping = false
    @Volatile private var busy = false
    @Volatile private var downloading = false
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    /** The last thing spoken, kept for Play again / Share / Save. */
    private var lastAudio: FloatArray? = null
    private var lastVoice = voice
    private val previews = mutableMapOf<String, FloatArray>()

    private val night get() = UiKit.night(this)
    private val textColor get() = if (night) Color.WHITE else Color.rgb(28, 28, 32)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        voice = KokoroTts.VOICES.firstOrNull { it.id == prefs.getString("voice", null) } ?: KokoroTts.VOICES[0]
        speed = prefs.getFloat("speed", 1.0f)
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiKit.shinyTitle(this, "🔊 Text to speech"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "ℹ️"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
            contentDescription = "How it works"; setOnClickListener { showHelp() }
        })
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "Type anything, pick a voice, and your phone reads it out loud. Nothing leaves your phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.7f; setPadding(0, dp(2f), 0, pad * 3 / 4)
        })

        // the words
        input = EditText(this).apply {
            hint = "What should it say?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            val p = dp(14f); setPadding(p, p, p, p)
            background = UiKit.card(this@TextToSpeechActivity, 16f)
            isVerticalScrollBarEnabled = true
            setOnTouchListener { v, e -> // the box scrolls, not the page, when dragging inside it
                if (v.canScrollVertically(1) || v.canScrollVertically(-1)) v.parent.requestDisallowInterceptTouchEvent(true)
                if (e.action == android.view.MotionEvent.ACTION_UP) v.parent.requestDisallowInterceptTouchEvent(false)
                false
            }
        }
        intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { input.setText(it.trim()) }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val textRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        textRow.addView(UiKit.chip(this, "📋 Paste") { paste() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginEnd = dp(8f) })
        textRow.addView(UiKit.chip(this, "✕ Clear") { input.setText("") }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)))
        root.addView(textRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        // the six voices: women on top, men below
        root.addView(label("VOICE"))
        for (female in listOf(true, false)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            KokoroTts.VOICES.filter { it.female == female }.forEachIndexed { i, v ->
                row.addView(voiceCard(v), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(8f)
                })
            }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        }
        root.addView(TextView(this).apply {
            text = "Tap a voice to hear it."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.6f
        })

        // speed
        root.addView(label("SPEED"))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = UiKit.card(this@TextToSpeechActivity, 20f)
        }
        for ((s, name) in SPEEDS) {
            val b = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(9f), 0, dp(9f))
                setOnClickListener { speed = s; prefs.edit().putFloat("speed", s).apply(); refresh() }
            }
            speedButtons.add(b)
            seg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // one-time download, shown until the voices are on the phone
        downloadCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); background = UiKit.card(this@TextToSpeechActivity, 16f)
        }
        downloadCard.addView(TextView(this).apply {
            text = "⬇️ Get the voices first"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        downloadCard.addView(TextView(this).apply {
            text = "One-time download of about ${mb(KokoroTts.missingBytes(this@TextToSpeechActivity))} MB. After that everything runs on your phone, even with no internet."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.75f; setPadding(0, dp(4f), 0, dp(10f))
        })
        downloadButton = UiKit.glow(this, "Download voices") { startDownload() }
        downloadCard.addView(downloadButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f)))
        root.addView(downloadCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })

        speak = UiKit.glow(this, "🔊 Speak") { if (busy) stop() else speakNow() }
        root.addView(speak, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)).apply { topMargin = pad })

        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(4f)) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(UiKit.PURPLE)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        for ((i, pair) in listOf<Pair<String, () -> Unit>>("▶ Play again" to ::playAgain, "📤 Share" to ::share, "💾 Save" to ::save).withIndex()) {
            actions.addView(UiKit.chip(this, pair.first) { pair.second() }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { if (i > 0) marginStart = dp(8f) })
        }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        root.addView(TextView(this).apply {
            text = "Voices: Kokoro-82M by hexgrad (Apache 2.0), run on your phone by sherpa-onnx."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.5f; gravity = Gravity.CENTER; setPadding(0, pad, 0, 0)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        scroll.fitAboveKeyboard(includeTop = true)
        refresh()
        // warm up the voice in the background so the first Speak is quicker
        if (KokoroTts.installed(this)) thread(name = "tts-warm") { runCatching { KokoroTts.knownWords(this, voice); KokoroTts.engine(this, voice) } }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = android.graphics.Typeface.DEFAULT_BOLD; alpha = 0.6f
        letterSpacing = 0.08f; setPadding(dp(4f), dp(18f), 0, dp(8f))
    }

    private fun voiceCard(v: KokoroTts.Voice) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        addView(TextView(context).apply {
            text = (if (v.female) "👩" else "👨") + if (v.british) " 🇬🇧" else " 🇺🇸"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = v.name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; tag = "name"
        })
        addView(TextView(context).apply {
            text = v.about; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); gravity = Gravity.CENTER; alpha = 0.8f; tag = "about"
        })
        contentDescription = "${v.name}, ${if (v.female) "female" else "male"} voice. ${v.about}"
        setOnClickListener {
            voice = v; prefs.edit().putString("voice", v.id).apply(); refresh()
            preview(v)
        }
        voiceCards[v.id] = this
    }

    private fun refresh() {
        val installed = KokoroTts.installed(this)
        for ((id, card) in voiceCards) {
            val on = id == voice.id
            card.background = if (on) UiKit.shine(this, 16f) else UiKit.card(this, 16f)
            for (i in 0 until card.childCount) (card.getChildAt(i) as? TextView)?.setTextColor(if (on) Color.WHITE else textColor)
        }
        SPEEDS.forEachIndexed { i, (s, _) -> UiKit.setChosen(speedButtons[i], s == speed, textColor) }
        downloadCard.visibility = if (installed) View.GONE else View.VISIBLE
        speak.visibility = if (installed) View.VISIBLE else View.GONE
        speak.text = if (busy) "⏹ Stop" else "🔊 Speak"
        actions.visibility = if (lastAudio != null && !busy) View.VISIBLE else View.GONE
    }

    private fun paste() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { toast("Nothing copied yet"); return }
        input.setText(t); input.setSelection(input.text.length)
    }

    // ---- download ----

    private fun startDownload() {
        if (downloading) { downloading = false; return } // second tap stops it
        downloading = true
        downloadButton.text = "Stop download"
        progress.visibility = View.VISIBLE; status.text = "Downloading voices…"
        thread(name = "tts-download") {
            val result = runCatching {
                KokoroTts.download(this, { !downloading || isDestroyed }) { p ->
                    runOnUiThread { progress.progress = (p * 1000).toInt(); status.text = "Downloading voices… ${(p * 100).toInt()}%" }
                }
            }
            runOnUiThread {
                downloading = false
                downloadButton.text = "Download voices"
                progress.visibility = View.GONE
                result.onSuccess {
                    status.text = "Voices ready. Type something and tap Speak."
                    thread(name = "tts-warm") { runCatching { KokoroTts.knownWords(this, voice); KokoroTts.engine(this, voice) } }
                }.onFailure {
                    Log.w(TAG, "download failed", it)
                    status.text = if (it is InterruptedException) "Download stopped. Tap Download to carry on."
                    else "Download didn't finish. Check the internet and tap Download again."
                }
                refresh()
            }
        }
    }

    // ---- speaking ----

    private fun speakNow() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("Type something first"); return }
        stopPlayback()
        busy = true; stopping = false; lastAudio = null
        val v = voice; val s = speed
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Getting the voice ready…"
        progress.visibility = View.VISIBLE; progress.isIndeterminate = true
        refresh()
        // rough sentence count, for the progress bar
        val sentences = Regex("[.!?;:]+(\\s|$)|\\n+").findAll(text).count().coerceAtLeast(1)
        thread(name = "tts-speak") {
            val all = ArrayList<FloatArray>()
            val queue = LinkedBlockingQueue<FloatArray>()
            val end = FloatArray(0)
            var player: Thread? = null
            val result = runCatching {
                val known = KokoroTts.knownWords(this, v)
                val tts = KokoroTts.engine(this, v)
                val ready = Pronounce.prepare(text, known)
                Log.i(TAG, "speaking ${text.length} chars with ${v.id} at $s")
                val t = openTrack()
                player = thread(name = "tts-play") {
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === end || stopping) break
                        t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    }
                    if (!stopping) drain(t, all.sumOf { it.size })
                }
                var done = 0
                val t0 = System.currentTimeMillis()
                runOnUiThread { status.text = "Speaking…"; progress.isIndeterminate = false; progress.progress = 0 }
                tts.generateWithCallback(ready, v.sid, s, SpeechChunks { samples ->
                    if (stopping) return@SpeechChunks 0
                    val copy = samples.copyOf()
                    all.add(copy); queue.put(copy); done++
                    runOnUiThread { progress.progress = (done * 1000 / sentences).coerceAtMost(1000) }
                    1
                })
                Log.i(TAG, "made ${all.sumOf { it.size } / RATE.toFloat()} s of speech in ${System.currentTimeMillis() - t0} ms")
                queue.put(end)
                player?.join()
            }
            queue.put(end)
            val audio = join(all)
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                if (audio.isNotEmpty()) { lastAudio = audio; lastVoice = v }
                result.onFailure { Log.w(TAG, "speak failed", it) }
                status.text = when {
                    result.isFailure -> "Something went wrong. Try again, or send a Bug report."
                    stopping -> "Stopped."
                    audio.isEmpty() -> "Nothing to say. Try some words."
                    else -> "Done · ${"%.1f".format(audio.size / RATE.toFloat())} s · ${v.name}"
                }
                if (!stopping) releaseTrack()
                refresh()
            }
        }
    }

    /** Hear a voice say hello (made once per voice, then kept). */
    private fun preview(v: KokoroTts.Voice) {
        if (busy || !KokoroTts.installed(this)) return
        stopPlayback()
        previews[v.id]?.let { playSamples(it); return }
        status.text = "Getting ${v.name} ready…"
        thread(name = "tts-preview") {
            runCatching {
                val tts = KokoroTts.engine(this, v)
                val a = tts.generate("Hi, I'm ${v.name}. I can read your messages out loud.", v.sid, 1.0f).samples
                previews[v.id] = a
                runOnUiThread { if (voice.id == v.id && !busy) { status.text = ""; playSamples(a) } }
            }.onFailure { Log.w(TAG, "preview failed", it); runOnUiThread { status.text = "" } }
        }
    }

    private fun playAgain() { lastAudio?.let { playSamples(it) } }

    private fun playSamples(a: FloatArray) {
        stopPlayback()
        stopping = false
        thread(name = "tts-replay") {
            val t = runCatching { openTrack() }.getOrNull() ?: return@thread
            var i = 0
            while (i < a.size && !stopping && track === t) {
                val n = minOf(RATE / 4, a.size - i)
                t.write(a, i, n, AudioTrack.WRITE_BLOCKING); i += n
            }
            if (!stopping && track === t) { drain(t, a.size); runOnUiThread { if (track === t) releaseTrack() } }
        }
    }

    private fun openTrack(): AudioTrack {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        }
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, RATE * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play()
        synchronized(this) { track = t }
        return t
    }

    /** Waits until the speaker has played everything written. */
    private fun drain(t: AudioTrack, frames: Int) {
        val until = System.currentTimeMillis() + 3000
        while (!stopping && runCatching { t.playbackHeadPosition }.getOrDefault(frames) < frames && System.currentTimeMillis() < until) Thread.sleep(40)
    }

    private fun stopPlayback() {
        synchronized(this) {
            track?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
            track = null
        }
        abandonFocus()
    }

    private fun releaseTrack() = stopPlayback()

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focus?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun stop() {
        stopping = true
        stopPlayback()
        status.text = "Stopping…"
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    // ---- share and save ----

    private fun fileName() = "OpenKeyboard-${lastVoice.name}-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.wav"

    private fun share() {
        val a = lastAudio ?: return
        val dir = File(cacheDir, "tts").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, fileName())
        f.outputStream().use { writeWav(it, a) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.media", f)
        val send = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, "Share speech")) }.onFailure { toast("No app to share with") }
    }

    private fun save() {
        val a = lastAudio ?: return
        if (Build.VERSION.SDK_INT < 29) { toast("Use Share to keep it on this Android version"); return }
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName())
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Open Keyboard")
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { writeWav(it, a) }
        }.onSuccess { toast("Saved to Music › Open Keyboard") }
            .onFailure { Log.w(TAG, "save failed", it); toast("Couldn't save it") }
    }

    /** 16-bit mono WAV. */
    private fun writeWav(out: OutputStream, a: FloatArray) {
        val data = a.size * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data)
        out.write(h.array())
        val b = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        for (x in a) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        out.write(b.array())
    }

    // ---- help ----

    private fun showHelp() {
        val installed = KokoroTts.installed(this)
        val b = AlertDialog.Builder(this).setTitle("🔊 Text to speech")
            .setMessage(
                "• Type or paste words, or open this from the keyboard while writing: what you've written comes along.\n\n" +
                    "• Pick a voice. Heart, Bella and Emma are women's voices (Emma is British). Fenrir, Michael and Puck are men's voices. " +
                    "They're the best-rated English voices of Kokoro.\n\n" +
                    "• Speak starts talking after the first sentence is ready, while it works on the rest. Stop ends it.\n\n" +
                    "• Share sends the speech as a sound file to any app (Messenger, WhatsApp, email). Save keeps it in Music › Open Keyboard.\n\n" +
                    "• Everything runs on your phone. Nothing you type is sent anywhere."
            )
            .setPositiveButton("OK", null)
        if (installed) b.setNeutralButton("Remove voices (${mb(KokoroTts.TOTAL_BYTES)} MB)") { _, _ ->
            AlertDialog.Builder(this).setMessage("Remove the voices from this phone? You can download them again any time.")
                .setPositiveButton("Remove") { _, _ -> stopPlayback(); KokoroTts.delete(this); previews.clear(); lastAudio = null; refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        b.show()
    }

    private fun mb(bytes: Long) = (bytes + 500_000) / 1_000_000
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float) = UiKit.dp(this, v)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { input.setText(it.trim()) }
    }

    override fun onDestroy() {
        stopping = true; downloading = false
        stopPlayback()
        super.onDestroy()
    }
}

/**
 * sherpa-onnx's native code calls the callback by name, as invoke(float[]) returning Integer. A plain lambda gets
 * rewritten by R8 (test 168 crashed: "no non-static method ...invoke([F)Ljava/lang/Integer;"), so it's a real class,
 * kept as-is in proguard-rules.pro.
 */
class SpeechChunks(private val onChunk: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onChunk(samples)
}
