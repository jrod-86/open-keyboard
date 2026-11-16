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
            if (on) {
                main.removeCallbacks(handBack)
                main.removeCallbacks(nudgePlayer)
                if (!hasFocus) {
                    mediaWasPlaying = audioManager.isMusicActive
                    hasFocus = audioManager.requestAudioFocus(focusRequest) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    Log.i(TAG, "media paused for voice: $hasFocus (was playing: $mediaWasPlaying)")
                }
            } else if (hasFocus) {
                // Open Keyboard: wait until the mic is fully closed and the off chime has played
                // before handing the sound back. Handing it back while the mic was still open left
                // podcasts frozen (Jarrod, 16 Nov).
                main.removeCallbacks(handBack)
                main.postDelayed(handBack, HAND_BACK_DELAY_MS)
            }
        }
    }

    private var mediaWasPlaying = false

    private val handBack = Runnable {
        if (running || !hasFocus) return@Runnable
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        hasFocus = false
        Log.i(TAG, "media handed back")
        if (mediaWasPlaying) main.postDelayed(nudgePlayer, 1000)
    }

    /** Some players don't start again by themselves: if it was playing before the mic, press play. */
    private val nudgePlayer = Runnable {
        if (running || hasFocus || !mediaWasPlaying) return@Runnable
        mediaWasPlaying = false
        // Open Keyboard: always press play (it never pauses, unlike play/pause). The "is music playing" check said
        // yes while the podcast was still paused (17 Nov logs), so it was never pressed and Jarrod had to restart it.
        Log.i(TAG, "pressing play so the media carries on (music active: ${audioManager.isMusicActive})")
        runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0))
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
        if (missingPermission || VoicePrefs.liveModel(ime) == null) {
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
        VoicePrefs.markUsed(ime)
        helium314.keyboard.latin.SmartCompose.pauseForVoice() // keep the processor for voice (resumed in listen())
        audioThread = thread(name = "voice-input") {
            var handedOver = false
            try { handedOver = listen(session) }
            finally { if (!handedOver) helium314.keyboard.latin.SmartCompose.resumeAfterVoice() }
        }
    }

    @Volatile private var preparing = false

    /**
     * The keyboard has opened: if voice typing has been used in the last week, load the live model now in the
     * background, so the mic writes straight away when tapped. Freed again after 5 minutes without voice.
     */
    fun prepare() {
        if (running || preparing) return
        if (System.currentTimeMillis() - VoicePrefs.lastUsed(ime) > PREPARE_IF_USED_WITHIN_MS) return
        if (VoicePrefs.liveModel(ime) == null) return
        if (ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        preparing = true
        thread(name = "voice-prepare", priority = Thread.MIN_PRIORITY) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                if (VoiceEngine.preloadLive(ime))
                    main.postDelayed({ onFinal { VoiceEngine.releaseIfIdle(VoiceEngine.IDLE_RELEASE_MS) } },
                        VoiceEngine.IDLE_RELEASE_MS + 1000)
            } finally { preparing = false }
        }
    }

    /** Stops listening. Text already heard is still finished off and committed. */
    fun stop() {
        Log.i(TAG, "mic stop: running=$running")
        if (!running) return
        running = false
        click(false)
        listening(false)
    }

    /** True once this session has put text in the box (so an empty box afterwards means the app sent it). */
    val hasWritten get() = wrote

    /** The app took the message (its send button emptied the box): turn the mic off and drop the rest. */
    fun onAppSent() {
        if (!isActive) return
        Log.i(TAG, "voice stopped: app sent the message")
        if (running) stop()
        sessionId++
        pending.clear(); current = ""; wrote = false; needsLeadingSpace = false
        resync()
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
        main.removeCallbacks(handBack); main.removeCallbacks(nudgePlayer)
        if (hasFocus) { runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }; hasFocus = false }
        finalPass.shutdownNow()
        chimes.release()
    }

    private fun ic(): InputConnection? = ime.currentInputConnection

    /**
     * Runs [block] on the final-pass thread. Open Keyboard: once the keyboard is closed down
     * ([release]) that thread is gone, and the voice thread finishing a moment later crashed the
     * keyboard (crash report 2026-11-16, ChatGPT). Returns false if it couldn't run.
     */
    private fun onFinal(block: () -> Unit): Boolean = try {
        finalPass.execute(block); true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    private fun writeWav(file: java.io.File, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        val rate = VoiceEngine.SAMPLE_RATE
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    /** Returns true if smart compose will be let back on by the final-pass thread (after the last sentence). */
    @SuppressLint("MissingPermission") // checked in start()
    private fun listen(session: Int): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // "call" mic: the phone's call processing (several mics, strong noise cancelling) for loud places
        val source = if (VoicePrefs.callMic(ime)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = AudioRecord(source, VoiceEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, VoiceEngine.SAMPLE_RATE * 2 * 5))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            main.post { toast("Microphone is not available"); stop() }
            record.release()
            return false
        }
        // the phone's own noise suppression, when turned on and the phone has it
        val suppressor = if (VoicePrefs.noiseReduction(ime) && android.media.audiofx.NoiseSuppressor.isAvailable())
            runCatching { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        else null
        // start recording before loading the model, so the first words aren't lost (the buffer holds 5 s)
        Thread.sleep(chimes.onLengthMs) // let the start chime finish so it isn't heard as speech
        record.startRecording()
        Log.i(TAG, "mic recording, loading streaming model")
        val recordingAt = android.os.SystemClock.uptimeMillis()
        val wasLoaded = VoiceEngine.liveLoaded
        VoiceEngine.begin() // keeps the models loaded until this session's last sentence is checked
        val recognizer = try {
            VoiceEngine.onlineRecognizer(ime).also {
                if (!wasLoaded) Log.i(TAG, "streaming model loaded in ${android.os.SystemClock.uptimeMillis() - recordingAt} ms")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "could not load streaming model", e)
            main.post { toast("Voice model could not be loaded"); stop() }
            VoiceEngine.end()
            runCatching { record.stop() }
            suppressor?.release()
            record.release()
            return false
        }
        // Open Keyboard: warm up the accuracy model once the live model has caught up with the speech recorded
        // while it loaded (or after 2 s), so the two don't fight for the processor and the first words show sooner
        var accuracyWarming = false
        fun warmAccuracyModel() {
            if (accuracyWarming) return
            accuracyWarming = true
            onFinal {
                val t = android.os.SystemClock.uptimeMillis()
                val cold = !VoiceEngine.accuracyLoaded
                runCatching { VoiceEngine.offlineRecognizer(ime) }
                if (cold) Log.i(TAG, "accuracy model loaded in ${android.os.SystemClock.uptimeMillis() - t} ms")
            }
        }
        var firstWordsLogged = false
        var resumeHandedOver = false
        val liveReadyAt = android.os.SystemClock.uptimeMillis()

        val stream = recognizer.createStream("")
        val chunk = ShortArray(VoiceEngine.SAMPLE_RATE / 10) // 100 ms
        var segment = SampleBuffer()
        var lastPartial = ""
        var lastSpeechAt = android.os.SystemClock.uptimeMillis()
        // optional copy of the whole session for tuning (at most 3 minutes)
        val keep = if (VoicePrefs.keepRecording(ime)) java.io.ByteArrayOutputStream() else null
        // Open Keyboard: the speech detector decides where sentences start and end (see VoiceEngine)
        val vad = VoiceEngine.newSpeechDetector(ime)
        val vadWindow = FloatArray(VoiceEngine.VAD_WINDOW)
        var vadFill = 0
        var fed = 0L // samples heard so far this session
        var segmentStart = 0L // where [segment] starts, counted the same way
        val pad = VoiceEngine.SAMPLE_RATE * 3 / 10 // keep 0.3 s either side of the speech

        /** Hands every sentence the detector has finished to the accurate model. */
        fun takeDetectedSentences(partial: String) {
            if (vad == null) return
            var text = partial
            var lastEnd = segmentStart
            while (!vad.empty()) {
                val s = vad.front(); vad.pop()
                val from = (s.start - pad - segmentStart).coerceIn(0L, segment.size.toLong()).toInt()
                val to = (s.start + s.samples.size + pad - segmentStart).coerceIn(from.toLong(), segment.size.toLong()).toInt()
                endSentence(session, text, segment.slice(from, to))
                text = "" // the live text all goes with the first sentence
                lastEnd = s.start + s.samples.size
            }
            recognizer.reset(stream)
            // keep what came after the sentence, in case the next one has already begun
            val keepFrom = (lastEnd - segmentStart).coerceIn(0L, segment.size.toLong()).toInt()
            segment = SampleBuffer().also { it.add(segment.slice(keepFrom, segment.size)) }
            segmentStart = fed - segment.size
            lastPartial = ""
        }

        try {
            while (running && session == sessionId) {
                val readStart = android.os.SystemClock.uptimeMillis()
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                // a read that had to wait for the mic means the recorded backlog is used up
                if (!accuracyWarming && (android.os.SystemClock.uptimeMillis() - readStart > 30 ||
                        android.os.SystemClock.uptimeMillis() - liveReadyAt > 2000)) warmAccuracyModel()
                val samples = FloatArray(n) { chunk[it] / 32768f }
                if (keep != null && keep.size() < VoiceEngine.SAMPLE_RATE * 2 * 180)
                    for (i in 0 until n) { keep.write(chunk[i].toInt() and 0xff); keep.write(chunk[i].toInt() shr 8 and 0xff) }
                stream.acceptWaveform(samples, VoiceEngine.SAMPLE_RATE)
                segment.add(samples)
                fed += n
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val partial = recognizer.getResult(stream).text.trim()
                if (partial.isNotEmpty()) lastSpeechAt = android.os.SystemClock.uptimeMillis()
                if (partial != lastPartial) {
                    lastPartial = partial
                    if (!firstWordsLogged && partial.isNotEmpty()) {
                        firstWordsLogged = true
                        Log.i(TAG, "first words after ${android.os.SystemClock.uptimeMillis() - recordingAt} ms")
                    }
                    main.post { if (session == sessionId) showPartial(partial) }
                }
                if (vad != null) {
                    var finished = false
                    for (x in samples) {
                        vadWindow[vadFill++] = x
                        if (vadFill == vadWindow.size) {
                            vad.acceptWaveform(vadWindow)
                            vadFill = 0
                            if (!vad.empty()) finished = true
                        }
                    }
                    if (finished) takeDetectedSentences(partial)
                    else if (!vad.isSpeechDetected() && segment.size > VoiceEngine.SAMPLE_RATE * 3) {
                        // nobody talking: forget old background noise, but keep the last second
                        // (the detector notices speech a little after it starts)
                        val keepFrom = segment.size - VoiceEngine.SAMPLE_RATE
                        segment = SampleBuffer().also { it.add(segment.slice(keepFrom, segment.size)) }
                        segmentStart = fed - segment.size
                        if (partial.isEmpty()) recognizer.reset(stream)
                    }
                } else if (recognizer.isEndpoint(stream)) {
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
            if (vad != null && session == sessionId) {
                if (vadFill > 0) vad.acceptWaveform(vadWindow.copyOf(vadFill))
                vad.flush()
                if (!vad.empty()) takeDetectedSentences(last)
                else main.post { if (session == sessionId) showPartial("") } // only noise was heard
            } else if (last.isNotEmpty() && session == sessionId) endSentence(session, last, segment.toArray())
            else main.post { if (session == sessionId) showPartial("") }
        } catch (e: Throwable) {
            Log.e(TAG, "voice input failed", e)
            main.post { toast("Voice typing stopped because of an error"); stop() }
        } finally {
            runCatching { record.stop() }
            suppressor?.release()
            record.release()
            stream.release()
            vad?.release()
            if (keep != null) runCatching { writeWav(VoicePrefs.lastRecording(ime), keep.toByteArray()) }
            val handedOver = onFinal {
                VoiceEngine.end()
                helium314.keyboard.latin.SmartCompose.resumeAfterVoice() // all sentences written: guesses may run again
                main.post { if (session == sessionId) finishIfIdle() }
                // free the models' memory if voice typing isn't used again for a few minutes
                main.postDelayed({ onFinal { VoiceEngine.releaseIfIdle(VoiceEngine.IDLE_RELEASE_MS) } },
                    VoiceEngine.IDLE_RELEASE_MS + 1000)
            }
            if (!handedOver) VoiceEngine.end() // keyboard closed down: still let the models go
            resumeHandedOver = handedOver
        }
        return resumeHandedOver
    }

    /** Moves the spoken sentence to the waiting list and asks the accuracy model for its version. */
    private fun endSentence(session: Int, streamingText: String, audio: FloatArray) {
        // Chat's box (Hands-free): "how are you today, send" sends, even said without a pause before "send" (test 206)
        val chatBox = helium314.keyboard.latin.ChatActivity.isChatBox(ime.currentInputEditorInfo)
        val saidSend = chatBox && VoiceCommands.endsWithSend(streamingText)
        if (saidSend && VoiceCommands.withoutSend(streamingText).isBlank()) {
            main.post { if (session == sessionId) { current = ""; render() } }
            onFinal { main.post { if (session == sessionId) runAction(VoiceCommands.Action.SEND) } }
            return
        }
        VoiceCommands.action(streamingText)?.let { action ->
            main.post { if (session == sessionId) { current = ""; render() } }
            // run after the sentences before it have been written
            onFinal { main.post { if (session == sessionId) runAction(action) } }
            return
        }
        val shown = formatStreaming(if (saidSend) VoiceCommands.withoutSend(streamingText) else streamingText)
        main.post {
            if (session != sessionId) return@post
            pending.addLast(shown); current = ""
            render()
        }
        onFinal {
            val final = runCatching { VoiceEngine.transcribeFinal(ime, audio) }
                .onFailure { Log.e(TAG, "accuracy model failed", it) }.getOrNull()
            var heard = final?.takeIf { it.isNotBlank() } ?: shown
            // the accuracy model may hear the "send" the live one missed, or the other way round
            val send = chatBox && (saidSend || VoiceCommands.endsWithSend(heard))
            if (chatBox) heard = VoiceCommands.withoutSend(heard)
            val english = VoiceCommands.punctuate(VoiceSpelling.apply(heard, VoicePrefs.region(ime)))
            val to = translateTo
            val text = if (to == null) english
                else helium314.keyboard.latin.TranslatePanel.translateBlocking(ime, english, "en", to) ?: english
            main.post {
                if (session != sessionId) return@post
                commitOldest(text)
                if (send) runAction(VoiceCommands.Action.SEND)
            }
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
        if (finalText.isBlank()) { render(); return } // only noise in that sentence
        // "write a birthday message for my wife": the AI writer writes it instead
        if (helium314.keyboard.latin.AiWriter.fromVoice(ime, finalText)) { if (running) stop(); render(); return }
        val ic = ic() ?: return
        ic.beginBatchEdit()
        val noLead = finalText.isEmpty() || finalText[0] in ",.!?;:…)\n"
        val piece = (if (noLead) "" else lead()) + finalText
        ic.commitText(piece, 1)
        // Open Keyboard: we just wrote into the box, so it can't have been emptied by the app. Asking the app
        // inside this batch edit gets the text from before it (Claude app, 17 Nov logs): the first sentence into
        // an empty box looked like "the app sent the message" and the mic turned itself off.
        lastClearCheck = android.os.SystemClock.uptimeMillis()
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
        // Asking the app is a round trip to its screen thread, done for every live word update. When the app is busy
        // (Chat loading its model, test 201 logs) each ask waited up to 2 s and froze the keyboard, so ask at most
        // twice a second: a cleared box is still noticed within half a second.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClearCheck < 500) return false
        lastClearCheck = now
        val ic = ic() ?: return false
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        val after = ic.getTextAfterCursor(1, 0) ?: return false
        if (before.isNotEmpty() || after.isNotEmpty()) return false
        val wasRunning = running
        Log.i(TAG, "voice stopped: the text box was emptied (app sent the message)")
        running = false
        sessionId++
        pending.clear(); current = ""; wrote = false; needsLeadingSpace = false
        if (wasRunning) { click(false); listening(false) }
        ic.finishComposingText()
        resync()
        return true
    }

    private val committed = ArrayDeque<String>()
    private var lastClearCheck = 0L

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
        if (VoiceEngine.liveWritesCapitals) return VoiceCommands.punctuate(VoiceSpelling.apply(text, region))
        val lower = text.lowercase().replace(Regex("\\bi\\b"), "I")
        return VoiceCommands.punctuate(VoiceSpelling.apply(lower.replaceFirstChar { it.titlecase() }, region))
    }

    private fun toast(message: String) = Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()

    /** Grows as audio arrives, so a whole sentence can be handed to the accuracy model. */
    private class SampleBuffer {
        private var data = FloatArray(VoiceEngine.SAMPLE_RATE * 5)
        private var count = 0
        fun add(samples: FloatArray) {
            if (count + samples.size > data.size) data = data.copyOf(maxOf(data.size * 2, count + samples.size))
            System.arraycopy(samples, 0, data, count, samples.size)
            count += samples.size
        }
        fun toArray(): FloatArray = data.copyOf(count)
        val size get() = count
        fun slice(from: Int, to: Int): FloatArray = data.copyOfRange(from, to)
    }

    companion object {
        private const val TAG = "VoiceInput"
        /** Voice typing turns itself off after this much silence. */
        private const val SILENCE_TIMEOUT_MS = 30_000L
        /** Mic closed and off chime played (about 260 ms) before other sound carries on. */
        private const val HAND_BACK_DELAY_MS = 600L
        /** Only get voice ready ahead of time for people who used it in the last week. */
        private const val PREPARE_IF_USED_WITHIN_MS = 7 * 24 * 3600_000L
    }
}
