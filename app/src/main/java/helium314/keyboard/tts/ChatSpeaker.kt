// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Reads Chat's replies out loud, a sentence at a time while the reply is still being written, with any voice from the
 * TTS tile (Kokoro, Zonos, or one you made). The voice engine is only loaded while it's talking: a minute after the
 * last reply it's freed again, so the chat model has the memory.
 */
class ChatSpeaker(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val releaser = Runnable { thread(name = "chat-voice-free") { releaseEngines() } }
    /** The thread making the last reply's audio: the engine is only freed once it has stopped. */
    @Volatile private var lastMaker: Thread? = null

    companion object {
        private const val TAG = "ChatSpeaker"
        private const val IDLE_RELEASE_MS = 60_000L
        private val END = Any()

        /** The voices you can chat with: those of the engines that are downloaded. */
        fun voices(ctx: Context): List<TtsVoice> {
            val out = ArrayList<TtsVoice>()
            if (KokoroTts.installed(ctx)) out += TextToSpeechActivity.KOKORO_VOICES
            if (ZonosTts.installed(ctx) && ZonosTts.supported(ctx)) {
                out += TextToSpeechActivity.ZONOS_VOICES
                out += VoiceClones.list(ctx).map { TtsVoice(TextToSpeechActivity.ZONOS, "z_" + it.id, it.name, it.female, it.flag, it.about, zonos = it) }
            }
            return out
        }

        // Open Keyboard (test 217): made once instead of for every sentence
        private val markup = Regex("[*_#`~]+")
        private val dash = Regex("\\s*[—–]\\s*")
        private val isContraction = Regex("(?i)\\b(how|what|where|who|when|why|there|here|that|it|he|she|everything|nothing|something)'s\\b")
        private val letUs = Regex("(?i)\\blet's\\b")
        private val possessive = Regex("(\\w)'s\\b")
        private val quotes = Regex("[“”„\"«»]")
        private val emoji = Regex("[\\uD800-\\uDFFF]|[\\u2600-\\u27BF]|[\\uFE0F\\u200D]")
        private val spaces = Regex("\\s+")
        /** AudioTrack.getLatency is hidden: look it up once (null if the phone doesn't have it). */
        private val latencyMethod by lazy { runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull() }

        /** Words for the ear: no emojis, markdown stars or hashes. */
        fun forSpeech(text: String): String = text
            .replace(markup, "")
            // "breeze—perfect" was read as one unknown word and skipped
            .replace(dash, ", ")
            .replace('’', '\'')
            // the word list has no "how's", "what's"…: say them in full
            .replace(isContraction) { it.groupValues[1] + " is" }
            .replace(letUs, "let us")
            // "autumn's" isn't in the word list either, but sounds just like "autumns"
            .replace(possessive, "$1s")
            // quotes stuck to a word ("“error”") made it unknown
            .replace(quotes, "")
            .replace(emoji, "")
            .replace(spaces, " ")
            .trim()
    }

    /** How loud the voice is right now (0..1), following what the speaker is actually playing, for the talking face. */
    fun level(): Float = session?.level() ?: 0f

    /** Sentences waiting for their speech to be made (not counting the one being made now). */
    fun backlog(): Int = session?.sentences?.count { it !== END } ?: 0

    val speaking get() = session?.let { !it.stopped && !it.finishedPlaying } ?: false

    /** Starts a new reply in [voice]; anything still being said stops. */
    fun begin(voice: TtsVoice) {
        stop()
        main.removeCallbacks(releaser)
        session = Session(voice).also { it.start() }
    }

    /** Adds the next sentence(s) of the reply. */
    fun add(text: String) {
        val t = forSpeech(text)
        if (t.isNotEmpty()) session?.sentences?.put(t)
    }

    /** No more text for this reply; [onDone] runs (on the main thread) once it has all been said, unless stopped. */
    fun finish(onDone: () -> Unit) {
        val s = session ?: run { onDone(); return }
        s.onDone = onDone
        s.allText = true
        s.sentences.put(END)
    }

    fun stop() {
        session?.cancel()
        session = null
        main.removeCallbacks(releaser)
        main.postDelayed(releaser, IDLE_RELEASE_MS)
    }

    /** The chat screen closed: stop and free the voice engines now. */
    fun close() {
        session?.cancel()
        session = null
        main.removeCallbacks(releaser)
        thread(name = "chat-voice-free") { releaseEngines() }
    }

    private fun releaseEngines() {
        lastMaker?.join(10_000)
        if (session?.stopped == false) return
        runCatching { KokoroTts.release() }
        runCatching { ZonosTts.stop() }
        Log.i(TAG, "voice engines freed")
    }

    private inner class Session(val voice: TtsVoice) {
        val sentences = LinkedBlockingQueue<Any>()
        private val audio = LinkedBlockingQueue<Any>()
        @Volatile var stopped = false
        @Volatile var finishedPlaying = false
        @Volatile var onDone: (() -> Unit)? = null
        /** The chat model has finished writing: the voice has the processor to itself again. */
        @Volatile var allText = false
        private val rate = if (voice.engine == TextToSpeechActivity.ZONOS) ZonosTts.RATE else KokoroTts.RATE
        @Volatile private var track: AudioTrack? = null
        /** Loudness of every 1/50 s written to the track, so the face can follow the frame being heard. */
        private val envelope = FloatArray(50 * 60 * 10)
        @Volatile private var envelopeSize = 0
        private val window = rate / 50
        private var winSum = 0.0
        private var winCount = 0
        private var peak = 0.08f

        private val stamp = android.media.AudioTimestamp()

        /**
         * The loudness of what's coming out of the speaker right now. playbackHeadPosition is where the phone has
         * taken the sound from, which can be well ahead of what you hear (test 199: the mouth wasn't in time, worse on
         * Bluetooth), so it uses the track's timestamp (the frame actually being played, and when), else the head
         * minus the output latency.
         */
        fun level(): Float {
            val t = track ?: return 0f
            val frame = runCatching {
                if (t.getTimestamp(stamp) && stamp.framePosition > 0) {
                    stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
                } else {
                    val latencyMs = runCatching { latencyMethod?.invoke(t) as? Int }.getOrNull() ?: 150
                    t.playbackHeadPosition - latencyMs.toLong() * rate / 1000
                }
            }.getOrDefault(0L)
            if (frame < 0) return 0f
            val i = (frame / window).toInt()
            return if (i in 0 until envelopeSize) envelope[i] else 0f
        }

        private fun measure(samples: FloatArray) {
            for (x in samples) {
                winSum += x * x; winCount++
                if (winCount == window) {
                    if (envelopeSize < envelope.size) {
                        // loudness against this voice's own recent loudest, so quiet and loud voices both use the full mouth,
                        // and it shuts between words (fixed scaling in test 199 left it "half good")
                        val rms = kotlin.math.sqrt(winSum / winCount).toFloat()
                        peak = maxOf(peak * 0.997f, rms, 0.02f)
                        envelope[envelopeSize] = ((rms - peak * 0.12f) / (peak * 0.7f)).coerceIn(0f, 1f)
                        envelopeSize++
                    }
                    winSum = 0.0; winCount = 0
                }
            }
        }
        private var focus: AudioFocusRequest? = null

        fun start() {
            lastMaker = thread(name = "chat-voice-make") {
                try {
                    while (!stopped) {
                        val item = sentences.take()
                        if (item === END || stopped) break
                        make(item as String)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "speaking failed", e)
                }
                audio.put(END)
            }
            thread(name = "chat-voice-play") {
                var frames = 0L
                // Speech is made while the chat model is still writing, and they share the phone's processor, so the
                // voice can fall behind and stutter (test 200 logs: "underrun"). So it waits until there's a little
                // speech in hand before playing, and again whenever it ran dry, giving clean pauses instead of choppy words.
                val pending = ArrayList<FloatArray>()
                var pendingFrames = 0
                var ended = false
                var dryRuns = 0
                var wasPlaying = false
                fun ranDry(t: AudioTrack?) = t == null || frames - runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(0L) <= rate / 20
                try {
                    while (!stopped && !ended) {
                        val chunk = audio.take()
                        if (chunk === END || stopped) ended = true
                        else { pending.add(chunk as FloatArray); pendingFrames += chunk.size }
                        val dry = ranDry(track)
                        if (dry && wasPlaying) { dryRuns++; wasPlaying = false }
                        // Test 207: a long story was written and spoken at once, and the voice kept running dry. While the
                        // model is still writing, each time it runs dry it waits for a bigger lead (3 s, 6 s, 10 s) so it
                        // then speaks in long smooth stretches; once the writing is done the voice catches up quickly.
                        val need = when {
                            track == null -> rate * 3 / 2 // 1.5 s to start
                            allText -> rate // 1 s
                            else -> rate * minOf(10, 3 * dryRuns.coerceAtLeast(1))
                        }
                        if (!ended && pendingFrames < need && dry) continue
                        if (pending.isEmpty()) continue
                        val t = track ?: open()
                        var failed = false
                        for (samples in pending) {
                            measure(samples)
                            if (runCatching { t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1) < 0) { failed = true; break }
                            frames += samples.size
                        }
                        pending.clear(); pendingFrames = 0
                        wasPlaying = true
                        if (failed) break
                    }
                    track?.let { t ->
                        val until = System.currentTimeMillis() + 4000
                        while (!stopped && runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(frames) < frames && System.currentTimeMillis() < until) Thread.sleep(40)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "playing failed", e)
                }
                closeTrack()
                finishedPlaying = true
                if (!stopped) {
                    val done = onDone
                    main.post {
                        if (session === this) { session = null; main.removeCallbacks(releaser); main.postDelayed(releaser, IDLE_RELEASE_MS) }
                        done?.invoke()
                    }
                }
            }
        }

        private fun make(text: String) {
            if (voice.engine == TextToSpeechActivity.ZONOS) {
                ZonosTts.speak(ctx, text, voice.zonos!!, null, { stopped }) { audio.put(it) }
            } else {
                val k = voice.kokoro!!
                val ready = Pronounce.prepare(text, KokoroTts.knownWords(ctx, k))
                KokoroTts.engine(ctx, k).generateWithCallback(ready, k.sid, 1.0f, SpeechChunks { samples ->
                    if (stopped) return@SpeechChunks 0
                    audio.put(samples.copyOf())
                    1
                })
            }
        }

        private fun open(): AudioTrack {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        private fun closeTrack() {
            synchronized(this) {
                track?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
                track = null
            }
            if (Build.VERSION.SDK_INT >= 26) focus?.let { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
            focus = null
        }

        fun cancel() {
            stopped = true
            sentences.clear(); sentences.put(END)
            audio.clear(); audio.put(END)
            closeTrack()
        }
    }
}
