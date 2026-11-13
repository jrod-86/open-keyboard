// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Gboard-style voice typing inside the keyboard: one tap starts listening, words appear as
 * underlined text while you speak, and each sentence is replaced by the accuracy model's version
 * when you pause. Tap again, or stay quiet for 30 seconds, to stop.
 */
class VoiceInputController(
    private val ime: InputMethodService,
    /** Commits whatever word the keyboard is composing, so voice text starts cleanly. */
    private val finishKeyboardComposing: () -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    /** Called with the cursor position once voice typing has finished editing the text. */
    private val onFinished: (selStart: Int, selEnd: Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val finalPass = Executors.newSingleThreadExecutor()
    private val chimes = VoiceChimes()

    /** Rising chime when the mic turns on, falling chime when it turns off. */
    private fun click(on: Boolean) = chimes.play(on)

    private val audioManager = ime.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }
        .build()
    private var hasFocus = false

    /**
     * Mic on: ask other apps (YouTube, podcasts, music) to pause while you talk.
     * Mic off: hand the sound back so what you were playing carries on.
     */
    private fun listening(on: Boolean) {
        onListeningChanged(on)
        runCatching {
            if (on && !hasFocus) {
                hasFocus = audioManager.requestAudioFocus(focusRequest) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.i(TAG, "media paused for voice: $hasFocus")
            } else if (!on && hasFocus) {
                audioManager.abandonAudioFocusRequest(focusRequest)
                hasFocus = false
                Log.i(TAG, "media handed back")
            }
        }
    }

    @Volatile private var running = false
    private var audioThread: Thread? = null

    // Main-thread state: sentences waiting for the accuracy model, and the sentence being spoken.
    private val pending = ArrayDeque<String>()
    private var current = ""
    private var needsLeadingSpace = false
    /** Whether this session has put text in the box, so an empty box means the app cleared it (e.g. sent it). */
    private var wrote = false
    private var sessionId = 0
    private var region = EnglishRegion.US
    /** Language to type in when speaking English ("km"), or null to type English. Fixed per session. */
    @Volatile private var translateTo: String? = null

    val isListening get() = running

    /** True while listening or while spoken text is still being finished. */
    val isActive get() = running || pending.isNotEmpty() || current.isNotEmpty()

    private var lastToggle = 0L

    fun toggle() {
        // ignore a second event from the same tap
        val now = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "mic tapped: running=$running pending=${pending.size} sinceLast=${now - lastToggle}ms translate=$translateTo")
        if (now - lastToggle < 400) { Log.i(TAG, "mic tap ignored (double event)"); return }
        lastToggle = now
        if (running) stop() else start()
    }

    fun start() {
        if (running) return
        val missingPermission = ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (missingPermission || !VoiceModel.STREAMING_EN.isInstalled(ime)) {
            Log.i(TAG, "mic not started: permission=${!missingPermission}")
            Toast.makeText(ime, "Set up voice typing first", Toast.LENGTH_SHORT).show()
            ime.startActivity(Intent(ime, VoiceSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        finishKeyboardComposing()
        pending.clear(); current = ""; committed.clear()
        region = VoicePrefs.region(ime)
        translateTo = helium314.keyboard.latin.MadladTranslator.voiceTarget(ime)
        translateTo?.let { tag ->
            if (helium314.keyboard.latin.MadladTranslator.covers(tag) && !helium314.keyboard.latin.MadladTranslator.downloaded(ime)) {
                helium314.keyboard.latin.MadladTranslator.ensureDownload(ime)
                translateTo = null
            } else toast("Speak English, typing " + helium314.keyboard.latin.TranslatePanel.name(tag))
        }
        val before = ic()?.getTextBeforeCursor(1, 0)
        needsLeadingSpace = !before.isNullOrEmpty() && !before[0].isWhitespace()
        wrote = false
        running = true
        val session = ++sessionId
        listening(true) // pause other media first, so the chime isn't talked over
        click(true)
        audioThread = thread(name = "voice-input") { listen(session) }
    }

    /** Stops listening. Text already heard is still finished off and committed. */
    fun stop() {
        Log.i(TAG, "mic stop: running=$running")
        if (!running) return
        running = false
        click(false)
        listening(false)
    }

    /** Stops immediately when the text field goes away, keeping what is on screen. */
    fun cancel() {
        val wasActive = isActive
        if (wasActive) Log.i(TAG, "voice cancelled (field changed or closed)")
        if (running) stop()
        sessionId++
        pending.clear(); current = ""
        // keep what is on screen, and let the typing logic catch up with the text voice typing wrote
        if (wasActive) { ic()?.finishComposingText(); resync() }
    }

    fun release() {
        cancel()
        finalPass.shutdownNow()
        chimes.release()
    }

    private fun ic(): InputConnection? = ime.currentInputConnection

    private fun writeWav(file: java.io.File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        val rate = VoiceEngine.SAMPLE_RATE
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun listen(session: Int) {
        val minBuffer = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // "call" mic: the phone's call processing (several mics, strong noise cancelling) for loud places
        val source = if (VoicePrefs.callMic(ime)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = AudioRecord(source, VoiceEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, VoiceEngine.SAMPLE_RATE * 2 * 5))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            main.post { toast("Microphone is not available"); stop() }
            record.release()
            return
        }
        // the phone's own noise suppression, when turned on and the phone has it
        val suppressor = if (VoicePrefs.noiseReduction(ime) && android.media.audiofx.NoiseSuppressor.isAvailable())
            runCatching { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        else null
        // start recording before loading the model, so the first words aren't lost (the buffer holds 5 s)
        Thread.sleep(chimes.onLengthMs) // let the start chime finish so it isn't heard as speech
        record.startRecording()
        Log.i(TAG, "mic recording, loading streaming model")
        val recognizer = try {
            VoiceEngine.onlineRecognizer(ime)
        } catch (e: Throwable) {
            Log.e(TAG, "could not load streaming model", e)
            main.post { toast("Voice model could not be loaded"); stop() }
            runCatching { record.stop() }
            suppressor?.release()
            record.release()
            return
        }
        // warm up the accuracy model while the user starts talking
        finalPass.execute { runCatching { VoiceEngine.offlineRecognizer(ime) } }

        val stream = recognizer.createStream("")
        val chunk = ShortArray(VoiceEngine.SAMPLE_RATE / 10) // 100 ms
        var segment = SampleBuffer()
        var lastPartial = ""
        var lastSpeechAt = android.os.SystemClock.uptimeMillis()
        // optional copy of the whole session for tuning (at most 3 minutes)
        val keep = if (VoicePrefs.keepRecording(ime)) java.io.ByteArrayOutputStream() else null
        try {
            while (running && session == sessionId) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
                if (keep != null && keep.size() < VoiceEngine.SAMPLE_RATE * 2 * 180)
                    for (i in 0 until n) { keep.write(chunk[i].toInt() and 0xff); keep.write(chunk[i].toInt() shr 8 and 0xff) }
                stream.acceptWaveform(samples, VoiceEngine.SAMPLE_RATE)
                segment.add(samples)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val partial = recognizer.getResult(stream).text.trim()
                if (partial.isNotEmpty()) lastSpeechAt = android.os.SystemClock.uptimeMillis()
                if (partial != lastPartial) {
                    lastPartial = partial
                    main.post { if (session == sessionId) showPartial(partial) }
                }
                if (recognizer.isEndpoint(stream)) {
                    if (partial.isNotEmpty()) endSentence(session, partial, segment.toArray())
                    recognizer.reset(stream)
                    segment = SampleBuffer()
                    lastPartial = ""
                }
                // stop after a long quiet spell
                if (android.os.SystemClock.uptimeMillis() - lastSpeechAt > SILENCE_TIMEOUT_MS) {
                    main.post { if (session == sessionId) stop() }
                    lastSpeechAt = Long.MAX_VALUE / 2 // only ask once
                }
            }
            // stopped by the user: finish the sentence in progress
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val last = recognizer.getResult(stream).text.trim()
            if (last.isNotEmpty() && session == sessionId) endSentence(session, last, segment.toArray())
            else main.post { if (session == sessionId) showPartial("") }
        } catch (e: Throwable) {
            Log.e(TAG, "voice input failed", e)
            main.post { toast("Voice typing stopped because of an error"); stop() }
        } finally {
            runCatching { record.stop() }
            suppressor?.release()
            record.release()
            stream.release()
            if (keep != null) runCatching { writeWav(VoicePrefs.lastRecording(ime), keep.toByteArray()) }
            finalPass.execute { main.post { if (session == sessionId) finishIfIdle() } }
        }
    }

    /** Moves the spoken sentence to the waiting list and asks the accuracy model for its version. */
    private fun endSentence(session: Int, streamingText: String, audio: FloatArray) {
        VoiceCommands.action(streamingText)?.let { action ->
            main.post { if (session == sessionId) { current = ""; render() } }
            // run after the sentences before it have been written
            finalPass.execute { main.post { if (session == sessionId) runAction(action) } }
            return
        }
        val shown = formatStreaming(streamingText)
        main.post {
            if (session != sessionId) return@post
            pending.addLast(shown); current = ""
            render()
        }
        finalPass.execute {
            val final = runCatching { VoiceEngine.transcribeFinal(ime, audio) }
                .onFailure { Log.e(TAG, "accuracy model failed", it) }.getOrNull()
            val english = VoiceCommands.punctuate(VoiceSpelling.apply(final?.takeIf { it.isNotBlank() } ?: shown, VoicePrefs.region(ime)))
            val to = translateTo
            val text = if (to == null) english
                else helium314.keyboard.latin.TranslatePanel.translateBlocking(ime, english, "en", to) ?: english
            main.post { if (session == sessionId) commitOldest(text) }
        }
    }

    private fun showPartial(partial: String) {
        current = if (partial.isEmpty()) "" else formatStreaming(partial)
        render()
    }

    /** Shows the waiting sentences and the live sentence as underlined (composing) text. */
    private fun render() {
        if (appClearedBox()) return
        val text = (pending + current).filter { it.isNotEmpty() }.joinToString(" ")
        ic()?.setComposingText(if (text.isEmpty()) "" else lead() + text, 1)
        if (text.isNotEmpty()) wrote = true
    }

    private fun commitOldest(finalText: String) {
        if (pending.isEmpty()) return
        if (appClearedBox()) return
        pending.removeFirst()
        val ic = ic() ?: return
        ic.beginBatchEdit()
        val noLead = finalText.isEmpty() || finalText[0] in ",.!?;:…)\n"
        val piece = (if (noLead) "" else lead()) + finalText
        ic.commitText(piece, 1)
        committed.addLast(piece)
        if (committed.size > 20) committed.removeFirst()
        needsLeadingSpace = !(finalText.endsWith("\n") || finalText.endsWith("("))
        wrote = true
        render()
        ic.endBatchEdit()
    }

    /**
     * If we already wrote text and the box is now empty, the app took the message (its send button).
     * Stop and drop the rest, so nothing is written back into the empty box.
     */
    private fun appClearedBox(): Boolean {
        if (!wrote) return false
        val ic = ic() ?: return false
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        val after = ic.getTextAfterCursor(1, 0) ?: return false
        if (before.isNotEmpty() || after.isNotEmpty()) return false
        val wasRunning = running
        running = false
        sessionId++
        pending.clear(); current = ""; wrote = false; needsLeadingSpace = false
        if (wasRunning) { click(false); listening(false) }
        ic.finishComposingText()
        resync()
        return true
    }

    private val committed = ArrayDeque<String>()

    /** Spoken commands: "delete that", "send", "stop listening", "clear all". */
    private fun runAction(action: VoiceCommands.Action) {
        val ic = ic() ?: return
        when (action) {
            VoiceCommands.Action.DELETE_THAT -> {
                val last = committed.removeLastOrNull() ?: return
                if (ic.getTextBeforeCursor(last.length, 0)?.toString() == last) ic.deleteSurroundingText(last.length, 0)
                val before = ic.getTextBeforeCursor(1, 0)
                needsLeadingSpace = !before.isNullOrEmpty() && !before[0].isWhitespace()
                resync()
            }
            VoiceCommands.Action.CLEAR_ALL -> {
                ic.beginBatchEdit()
                ic.finishComposingText()
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
                ic.endBatchEdit()
                committed.clear(); wrote = false; needsLeadingSpace = false
                resync()
            }
            VoiceCommands.Action.STOP -> stop()
            VoiceCommands.Action.SEND -> {
                stop()
                val editorAction = (ime.currentInputEditorInfo?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                val sendLike = editorAction == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        editorAction == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                        editorAction == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                        editorAction == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                main.postDelayed({
                    ic()?.finishComposingText()
                    if (sendLike) ic()?.performEditorAction(editorAction)
                    else toast("This app needs its own send button tapped")
                }, 300)
            }
        }
    }

    private fun finishIfIdle() {
        if (running || pending.isNotEmpty()) return
        if (current.isNotEmpty()) { current = ""; render() }
        ic()?.finishComposingText()
        resync()
    }

    private fun resync() {
        val extracted = ic()?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted == null || extracted.selectionStart < 0) onFinished(-1, -1)
        else onFinished(extracted.startOffset + extracted.selectionStart, extracted.startOffset + extracted.selectionEnd)
    }

    private fun lead() = if (needsLeadingSpace) " " else ""

    /** The streaming model writes in capitals without punctuation; make it readable. */
    private fun formatStreaming(text: String): String {
        val lower = text.lowercase().replace(Regex("\\bi\\b"), "I")
        return VoiceCommands.punctuate(VoiceSpelling.apply(lower.replaceFirstChar { it.titlecase() }, region))
    }

    private fun toast(message: String) = Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()

    /** Grows as audio arrives, so a whole sentence can be handed to the accuracy model. */
    private class SampleBuffer {
        private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 5)
        private var size = 0
        fun add(samples: FloatArray) {
            if (size + samples.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + samples.size))
            System.arraycopy(samples, 0, data, size, samples.size)
            size += samples.size
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "VoiceInput"
        /** Voice typing turns itself off after this much silence. */
        private const val SILENCE_TIMEOUT_MS = 30_000L
    }
}
