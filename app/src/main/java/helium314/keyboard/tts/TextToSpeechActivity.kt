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
import helium314.keyboard.media.ImageThemes
import helium314.keyboard.media.Themed
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** One voice of either engine. */
class TtsVoice(val engine: String, val id: String, val name: String, val female: Boolean, val badge: String, val about: String,
               val kokoro: KokoroTts.Voice? = null, val zonos: ZonosTts.Voice? = null) {
    val custom get() = zonos?.file != null
}

/**
 * Text to speech tile: type or bring words from the chat, pick an engine (⚡ Kokoro, small and quick, or ✨ Zonos,
 * the most natural) and a voice, hear it, share or save it.
 */
class TextToSpeechActivity : Activity(), Themed {
    /** 🎨 this screen's own theme, same picker as Local Image (Jarrod, test 191); Make a voice uses it too. */
    override val pageTheme by lazy { ImageThemes.current(this, ImageThemes.night(this), ImageThemes.KEY_TTS) }
    companion object {
        const val EXTRA_TEXT = "text"
        private const val TAG = "TextToSpeech"
        private val SPEEDS = listOf(0.8f to "🐢 Slower", 1.0f to "Normal", 1.25f to "🐇 Faster")
        const val KOKORO = "kokoro"
        const val ZONOS = "zonos"
        val KOKORO_VOICES = KokoroTts.VOICES.map { TtsVoice(KOKORO, it.id, it.name, it.female, if (it.british) "🇬🇧" else "🇺🇸", it.about, kokoro = it) }
        val ZONOS_VOICES = ZonosTts.VOICES.map { zonosVoice(it) }
        private fun zonosVoice(v: ZonosTts.Voice) = TtsVoice(ZONOS, "z_" + v.id, v.name, v.female, v.flag, v.about, zonos = v)
        private const val MAKE_VOICE = 7
        // Zonos: speaking speed as a condition (UTF-8 bytes per second); null = the voice's own pace
        private fun zonosRate(speed: Float): Double? = when { speed < 0.95f -> 11.0; speed > 1.05f -> 18.5; else -> null }
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
    private val engineButtons = mutableListOf<TextView>()
    private lateinit var voicesBox: LinearLayout
    private lateinit var downloadText: TextView
    private lateinit var credit: TextView
    private var engine = KOKORO
    private var voice = KOKORO_VOICES[0]
    private var speed = 1.0f

    @Volatile private var stopping = false
    @Volatile private var busy = false
    @Volatile private var downloading = false
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    /** The last thing spoken, kept for Play again / Share / Save. */
    private var lastAudio: FloatArray? = null
    private var lastRate = KokoroTts.RATE
    private var lastVoice = voice
    private val previews = mutableMapOf<String, FloatArray>()

    private val night get() = UiKit.night(this)
    private val textColor get() = pageTheme.text

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        engine = if (prefs.getString("engine", KOKORO) == ZONOS && ZonosTts.supported(this)) ZONOS else KOKORO
        voice = voicesOf(engine).firstOrNull { it.id == prefs.getString("voice_$engine", null) } ?: voicesOf(engine)[0]
        speed = prefs.getFloat("speed", 1.0f)
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiKit.shinyTitle(this, "🔊 Text to speech"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ImageThemes.button(this, ImageThemes.KEY_TTS))
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

        // engine: Kokoro (small, quick) or Zonos (most natural)
        root.addView(label("ENGINE"))
        val engSeg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = UiKit.card(this@TextToSpeechActivity, 20f)
        }
        for ((id, name) in listOf(KOKORO to "⚡ Kokoro · quick", ZONOS to "✨ Zonos · natural")) {
            val b = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(9f), 0, dp(9f))
                setOnClickListener { pickEngine(id) }
            }
            engineButtons.add(b)
            engSeg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(engSeg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // the voices: women on top, men below
        root.addView(label("VOICE"))
        voicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(voicesBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildVoices()
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
        downloadText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.75f; setPadding(0, dp(4f), 0, dp(10f)) }
        downloadCard.addView(downloadText)
        downloadButton = UiKit.glow(this, "Download voices") { startDownload() }
        downloadCard.addView(downloadButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f)))
        root.addView(downloadCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })

        speak = UiKit.glow(this, "🔊 Speak") { if (busy) stop() else speakNow() }
        root.addView(speak, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)).apply { topMargin = pad })

        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(4f)) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(pageTheme.a1)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        for ((i, pair) in listOf<Pair<String, () -> Unit>>("▶ Play again" to ::playAgain, "📤 Share" to ::share, "💾 Save" to ::save).withIndex()) {
            actions.addView(UiKit.chip(this, pair.first) { pair.second() }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply { if (i > 0) marginStart = dp(8f) })
        }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        credit = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.5f; gravity = Gravity.CENTER; setPadding(0, pad, 0, 0) }
        root.addView(credit)

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        ImageThemes.applyPage(this, root, pageTheme)
        setContentView(scroll)
        scroll.fitAboveKeyboard(includeTop = true)
        refresh()
        warm()
    }

    /** Zonos: the built-in voices, then the ones you made. */
    private fun voicesOf(e: String) = if (e == ZONOS) ZONOS_VOICES + VoiceClones.list(this).map { zonosVoice(it) } else KOKORO_VOICES
    private fun installed(e: String = engine) = if (e == ZONOS) ZonosTts.installed(this) else KokoroTts.installed(this)

    /** Load the engine in the background so the first Speak is quicker. */
    private fun warm() {
        if (!installed()) return
        val v = voice
        thread(name = "tts-warm") {
            runCatching {
                if (v.engine == ZONOS) ZonosTts.start(this)
                else { KokoroTts.knownWords(this, v.kokoro!!); KokoroTts.engine(this, v.kokoro) }
            }.onFailure { Log.w(TAG, "warm-up failed", it) }
        }
    }

    private fun pickEngine(e: String) {
        if (busy || e == engine) return
        if (e == ZONOS && !ZonosTts.supported(this)) {
            AlertDialog.Builder(this).setTitle("✨ Zonos").setMessage("Zonos needs a newer phone chip (like a Galaxy S22 or later). Kokoro works on every phone.")
                .setPositiveButton("OK", null).show()
            return
        }
        stopPlayback()
        engine = e; prefs.edit().putString("engine", e).apply()
        voice = voicesOf(e).firstOrNull { it.id == prefs.getString("voice_$e", null) } ?: voicesOf(e)[0]
        lastAudio = null; status.text = ""
        if (e == KOKORO) ZonosTts.stop() // frees its memory
        buildVoices(); refresh(); warm()
    }

    /** Voice cards, three to a row: women first, then men; for Zonos, then your voices and "Make a voice". */
    private fun buildVoices() {
        voicesBox.removeAllViews(); voiceCards.clear()
        val all = voicesOf(engine)
        fun rows(cards: List<View>) = cards.chunked(3).forEach { rowCards ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(8f) }
                row.addView(rowCards.getOrNull(i) ?: View(this), lp)
            }
            voicesBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        }
        for (female in listOf(true, false)) rows(all.filter { !it.custom && it.female == female }.map { voiceCard(it) })
        if (engine == ZONOS) {
            voicesBox.addView(label("YOUR VOICES"))
            rows(all.filter { it.custom }.map { voiceCard(it) } + makeVoiceCard())
        }
    }

    private fun makeVoiceCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        background = UiKit.card(this@TextToSpeechActivity, 16f)
        addView(TextView(context).apply { text = "➕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER; setTextColor(textColor) })
        addView(TextView(context).apply {
            text = "Make a voice"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(textColor)
        })
        addView(TextView(context).apply { text = "Copy one from a clip"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); gravity = Gravity.CENTER; alpha = 0.8f; setTextColor(textColor) })
        contentDescription = "Make a voice from a clip"
        setOnClickListener {
            if (busy) return@setOnClickListener
            if (!ZonosTts.installed(this@TextToSpeechActivity)) { toast("Download the Zonos voices first"); return@setOnClickListener }
            stopPlayback()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@TextToSpeechActivity, MakeVoiceActivity::class.java), MAKE_VOICE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MAKE_VOICE) return
        val id = data?.getStringExtra(MakeVoiceActivity.EXTRA_VOICE)
        if (resultCode == RESULT_OK && id != null) prefs.edit().putString("voice_$ZONOS", "z_$id").apply()
        voice = voicesOf(engine).firstOrNull { it.id == prefs.getString("voice_$engine", null) } ?: voicesOf(engine)[0]
        buildVoices(); refresh(); warm()
        if (resultCode == RESULT_OK && id != null) preview(voice)
    }

    /** Long-press one of your voices: rename or delete it. */
    private fun editVoice(v: TtsVoice) {
        val z = v.zonos ?: return
        AlertDialog.Builder(this).setTitle("🎤 ${v.name}")
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) {
                    val field = UiKit.field(this, "Name").apply { setText(v.name); setSingleLine() }
                    AlertDialog.Builder(this).setTitle("Rename").setView(field)
                        .setPositiveButton("Save") { _, _ ->
                            field.text.toString().trim().take(24).takeIf { it.isNotEmpty() }?.let { VoiceClones.rename(z, it); buildVoices(); refresh() }
                        }.setNegativeButton("Cancel", null).show()
                } else AlertDialog.Builder(this).setMessage("Delete the voice \"${v.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        VoiceClones.delete(this, z); previews.remove(v.id)
                        if (voice.id == v.id) { voice = voicesOf(engine)[0]; prefs.edit().putString("voice_$engine", voice.id).apply() }
                        buildVoices(); refresh()
                    }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = android.graphics.Typeface.DEFAULT_BOLD; alpha = 0.6f; setTextColor(textColor)
        letterSpacing = 0.08f; setPadding(dp(4f), dp(18f), 0, dp(8f))
    }

    private fun voiceCard(v: TtsVoice) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        addView(TextView(context).apply {
            text = if (v.custom) "🎤" else (if (v.female) "👩 " else "👨 ") + v.badge
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
            voice = v; prefs.edit().putString("voice_${v.engine}", v.id).apply(); refresh()
            preview(v)
        }
        if (v.custom) setOnLongClickListener { if (!busy) editVoice(v); true }
        voiceCards[v.id] = this
    }

    private fun refresh() {
        val installed = installed()
        listOf(KOKORO, ZONOS).forEachIndexed { i, e -> UiKit.setChosen(engineButtons[i], e == engine, textColor) }
        downloadText.text = if (engine == ZONOS)
            "One-time download of about ${mb(ZonosTts.missingBytes(this))} MB (a big, very natural model). After that everything runs on your phone, even with no internet. Wi-Fi recommended."
        else "One-time download of about ${mb(KokoroTts.missingBytes(this))} MB. After that everything runs on your phone, even with no internet."
        credit.text = if (engine == ZONOS) "Voices: ZONOS3-Mini by Zyphra (Apache 2.0), run on your phone by its own engine (MIT)."
        else "Voices: Kokoro-82M by hexgrad (Apache 2.0), run on your phone by sherpa-onnx."
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
        val e = engine
        downloadButton.text = "Stop download"
        progress.visibility = View.VISIBLE; status.text = "Downloading voices…"
        thread(name = "tts-download") {
            val result = runCatching {
                val onProgress: (Float) -> Unit = { p ->
                    runOnUiThread { progress.progress = (p * 1000).toInt(); status.text = "Downloading voices… ${(p * 100).toInt()}%" }
                }
                if (e == ZONOS) ZonosTts.download(this, { !downloading || isDestroyed }, onProgress)
                else KokoroTts.download(this, { !downloading || isDestroyed }, onProgress)
            }
            runOnUiThread {
                downloading = false
                downloadButton.text = "Download voices"
                progress.visibility = View.GONE
                result.onSuccess {
                    status.text = "Voices ready. Type something and tap Speak."
                    warm()
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
        val rate = if (v.engine == ZONOS) ZonosTts.RATE else KokoroTts.RATE
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
                if (v.engine == ZONOS) ZonosTts.start(this)
                Log.i(TAG, "speaking ${text.length} chars with ${v.id} at $s")
                val t = openTrack(rate)
                player = thread(name = "tts-play") {
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === end || stopping) break
                        if (runCatching { t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1) < 0) break // track released
                    }
                    if (!stopping) runCatching { drain(t, all.sumOf { it.size }) }
                }
                var done = 0
                val t0 = System.currentTimeMillis()
                runOnUiThread { status.text = "Speaking…"; progress.isIndeterminate = false; progress.progress = 0 }
                if (v.engine == ZONOS) {
                    // about 15 characters a second of speech, for the progress bar
                    val expected = (text.length / 15f * rate).coerceAtLeast(rate.toFloat())
                    ZonosTts.speak(this, text, v.zonos!!, zonosRate(s), { stopping }) { samples ->
                        all.add(samples); queue.put(samples)
                        val made = all.sumOf { it.size }
                        runOnUiThread { progress.progress = (made * 1000 / expected).toInt().coerceAtMost(990) }
                    }
                } else {
                    val k = v.kokoro!!
                    val ready = Pronounce.prepare(text, KokoroTts.knownWords(this, k))
                    KokoroTts.engine(this, k).generateWithCallback(ready, k.sid, s, SpeechChunks { samples ->
                        if (stopping) return@SpeechChunks 0
                        val copy = samples.copyOf()
                        all.add(copy); queue.put(copy); done++
                        runOnUiThread { progress.progress = (done * 1000 / sentences).coerceAtMost(1000) }
                        1
                    })
                }
                Log.i(TAG, "made ${all.sumOf { it.size } / rate.toFloat()} s of speech in ${System.currentTimeMillis() - t0} ms")
                queue.put(end)
                player?.join()
            }
            queue.put(end)
            val audio = join(all)
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                if (audio.isNotEmpty()) { lastAudio = audio; lastVoice = v; lastRate = rate }
                result.onFailure { Log.w(TAG, "speak failed", it) }
                status.text = when {
                    result.isFailure -> "Something went wrong. Try again, or send a Bug report."
                    stopping -> "Stopped."
                    audio.isEmpty() -> "Nothing to say. Try some words."
                    else -> "Done · ${"%.1f".format(audio.size / rate.toFloat())} s · ${v.name}"
                }
                if (!stopping) releaseTrack()
                refresh()
            }
        }
    }

    /** Hear a voice say hello (made once per voice, then kept). */
    private fun preview(v: TtsVoice) {
        if (busy || !installed(v.engine)) return
        stopPlayback()
        val rate = if (v.engine == ZONOS) ZonosTts.RATE else KokoroTts.RATE
        previews[v.id]?.let { playSamples(it, rate); return }
        status.text = "Getting ${v.name} ready…"
        thread(name = "tts-preview") {
            runCatching {
                val hello = if (v.custom) "Hi, this is the voice you made. I can read anything you type out loud."
                else "Hi, I'm ${v.name}. I can read your messages out loud."
                val a = if (v.engine == ZONOS) {
                    val parts = ArrayList<FloatArray>()
                    ZonosTts.speak(this, hello, v.zonos!!, null, { false }) { parts.add(it) }
                    join(parts)
                } else KokoroTts.engine(this, v.kokoro!!).generate(hello, v.kokoro.sid, 1.0f).samples
                previews[v.id] = a
                runOnUiThread { if (voice.id == v.id && !busy) { status.text = ""; playSamples(a, rate) } }
            }.onFailure { Log.w(TAG, "preview failed", it); runOnUiThread { status.text = "" } }
        }
    }

    private fun playAgain() { lastAudio?.let { playSamples(it, lastRate) } }

    private fun playSamples(a: FloatArray, rate: Int) {
        stopPlayback()
        stopping = false
        thread(name = "tts-replay") {
            val t = runCatching { openTrack(rate) }.getOrNull() ?: return@thread
            var i = 0
            // Tapping another voice (or Stop) releases this track from the main thread, maybe mid-write: that
            // throws "Unable to retrieve AudioTrack pointer", which just means we're done (crash in test 176).
            runCatching {
                while (i < a.size && !stopping && track === t) {
                    val n = minOf(rate / 4, a.size - i)
                    if (t.write(a, i, n, AudioTrack.WRITE_BLOCKING) < 0) break
                    i += n
                }
                if (!stopping && track === t) { drain(t, a.size); runOnUiThread { if (track === t) releaseTrack() } }
            }
        }
    }

    private fun openTrack(rate: Int): AudioTrack {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        }
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
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
        f.outputStream().use { writeWav(it, a, lastRate) }
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
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/O-Board")
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { writeWav(it, a, lastRate) }
        }.onSuccess { toast("Saved to Music › O-Board") }
            .onFailure { Log.w(TAG, "save failed", it); toast("Couldn't save it") }
    }

    /** 16-bit mono WAV. */
    private fun writeWav(out: OutputStream, a: FloatArray, rate: Int) {
        val data = a.size * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data)
        out.write(h.array())
        val b = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        for (x in a) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        out.write(b.array())
    }

    // ---- help ----

    private fun showHelp() {
        val installed = installed()
        val b = AlertDialog.Builder(this).setTitle("🔊 Text to speech")
            .setMessage(
                "• Type or paste words, or open this from the keyboard while writing: what you've written comes along.\n\n" +
                    "• Two engines. ⚡ Kokoro is small (${mb(KokoroTts.TOTAL_BYTES)} MB) and quick: Heart, Bella and Emma (British), " +
                    "Fenrir, Michael and Puck. ✨ Zonos (ZONOS3-Mini) is bigger (${mb(ZonosTts.TOTAL_BYTES)} MB) and sounds the most natural: " +
                    "Savannah, Imogen (British), Wyatt, Rupert (British) and Cillian (Irish), plus fun voices: Willow whispers, Sakura is anime, " +
                    "Morgana is a witch, Jett is energetic and Thaddeus is an announcer.\n\n" +
                    "• Make a voice (Zonos): record or pick a 10 to 20 second clip of someone talking and it becomes one of your voices. " +
                    "Only copy your own voice or someone who said yes. Long-press one of your voices to rename or delete it.\n\n" +
                    "• Speak starts talking after the first sentence is ready, while it works on the rest. Stop ends it.\n\n" +
                    "• Share sends the speech as a sound file to any app (Messenger, WhatsApp, email). Save keeps it in Music › O-Board.\n\n" +
                    "• Everything runs on your phone. Nothing you type is sent anywhere."
            )
            .setPositiveButton("OK", null)
        val e = engine
        val name = if (e == ZONOS) "Zonos" else "Kokoro"
        if (installed) b.setNeutralButton("Remove $name (${mb(if (e == ZONOS) ZonosTts.TOTAL_BYTES else KokoroTts.TOTAL_BYTES)} MB)") { _, _ ->
            AlertDialog.Builder(this).setMessage("Remove the $name voices from this phone? You can download them again any time.")
                .setPositiveButton("Remove") { _, _ ->
                    stopPlayback(); if (e == ZONOS) ZonosTts.delete(this) else KokoroTts.delete(this)
                    previews.clear(); lastAudio = null; refresh()
                }
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
        if (isFinishing) ZonosTts.stop() // free the engine's memory once the screen is closed
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
