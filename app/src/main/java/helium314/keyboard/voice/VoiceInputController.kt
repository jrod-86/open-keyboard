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
    private val tones = runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 70) }.getOrNull()

    /** Short click so you can hear the mic turn on (higher) and off (lower). */
    private fun click(on: Boolean) {
        tones?.startTone(if (on) android.media.ToneGenerator.TONE_PROP_BEEP else android.media.ToneGenerator.TONE_PROP_ACK, if (on) 80 else 120)
    }

    @Volatile private var running = false
    private var audioThread: Thread? = null

    // Main-thread state: sentences waiting for the accuracy model, and the sentence being spoken.
    private val pending = ArrayDeque<String>()
    private var current = ""
    private var needsLeadingSpace = false
    private var sessionId = 0

    val isListening get() = running

    /** True while listening or while spoken text is still being finished. */
    val isActive get() = running || pending.isNotEmpty() || current.isNotEmpty()

    private var lastToggle = 0L

    fun toggle() {
        // ignore a second event from the same tap
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastToggle < 400) return
        lastToggle = now
        if (running) stop() else start()
    }

    fun start() {
        if (running) return
        val missingPermission = ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (missingPermission || !VoiceModel.STREAMING_EN.isInstalled(ime)) {
            Toast.makeText(ime, "Set up voice typing first", Toast.LENGTH_SHORT).show()
            ime.startActivity(Intent(ime, VoiceSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        finishKeyboardComposing()
        pending.clear(); current = ""
        val before = ic()?.getTextBeforeCursor(1, 0)
        needsLeadingSpace = !before.isNullOrEmpty() && !before[0].isWhitespace()
        running = true
        val session = ++sessionId
        click(true)
        onListeningChanged(true)
        audioThread = thread(name = "voice-input") { listen(session) }
    }

    /** Stops listening. Text already heard is still finished off and committed. */
    fun stop() {
        if (!running) return
        running = false
        click(false)
        onListeningChanged(false)
    }

    /** Stops immediately when the text field goes away, keeping what is on screen. */
    fun cancel() {
        if (running) stop()
        sessionId++
        val hadText = pending.isNotEmpty() || current.isNotEmpty()
        pending.clear(); current = ""
        if (hadText) { ic()?.finishComposingText(); resync() }
    }

    fun release() {
        cancel()
        finalPass.shutdownNow()
        tones?.release()
    }

    private fun ic(): InputConnection? = ime.currentInputConnection

    @SuppressLint("MissingPermission") // checked in start()
    private fun listen(session: Int) {
        val minBuffer = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, VoiceEngine.SAMPLE_RATE * 2 * 5))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            main.post { toast("Microphone is not available"); stop() }
            record.release()
            return
        }
        // start recording before loading the model, so the first words aren't lost (the buffer holds 5 s)
        Thread.sleep(150) // let the start click finish so it isn't heard as speech
        record.startRecording()
        val recognizer = try {
            VoiceEngine.onlineRecognizer(ime)
        } catch (e: Throwable) {
            Log.e(TAG, "could not load streaming model", e)
            main.post { toast("Voice model could not be loaded"); stop() }
            runCatching { record.stop() }
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
        try {
            while (running && session == sessionId) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
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
            record.release()
            stream.release()
            finalPass.execute { main.post { if (session == sessionId) finishIfIdle() } }
        }
    }

    /** Moves the spoken sentence to the waiting list and asks the accuracy model for its version. */
    private fun endSentence(session: Int, streamingText: String, audio: FloatArray) {
        val shown = formatStreaming(streamingText)
        main.post {
            if (session != sessionId) return@post
            pending.addLast(shown); current = ""
            render()
        }
        finalPass.execute {
            val final = runCatching { VoiceEngine.transcribeFinal(ime, audio) }
                .onFailure { Log.e(TAG, "accuracy model failed", it) }.getOrNull()
            main.post { if (session == sessionId) commitOldest(final?.takeIf { it.isNotBlank() } ?: shown) }
        }
    }

    private fun showPartial(partial: String) {
        current = if (partial.isEmpty()) "" else formatStreaming(partial)
        render()
    }

    /** Shows the waiting sentences and the live sentence as underlined (composing) text. */
    private fun render() {
        val text = (pending + current).filter { it.isNotEmpty() }.joinToString(" ")
        ic()?.setComposingText(if (text.isEmpty()) "" else lead() + text, 1)
    }

    private fun commitOldest(finalText: String) {
        if (pending.isEmpty()) return
        pending.removeFirst()
        val ic = ic() ?: return
        ic.beginBatchEdit()
        ic.commitText(lead() + finalText, 1)
        needsLeadingSpace = true
        render()
        ic.endBatchEdit()
    }

    private fun finishIfIdle() {
        if (running || pending.isNotEmpty()) return
        if (current.isNotEmpty()) { current = ""; render() }
        ic()?.finishComposingText()
        resync()
    }

    private fun resync() {
        val extracted = ic()?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        onFinished(extracted.startOffset + extracted.selectionStart, extracted.startOffset + extracted.selectionEnd)
    }

    private fun lead() = if (needsLeadingSpace) " " else ""

    /** The streaming model writes in capitals without punctuation; make it readable. */
    private fun formatStreaming(text: String): String {
        val lower = text.lowercase().replace(Regex("\\bi\\b"), "I")
        return lower.replaceFirstChar { it.titlecase() }
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
