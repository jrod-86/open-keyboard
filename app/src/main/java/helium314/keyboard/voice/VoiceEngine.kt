// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import helium314.keyboard.latin.utils.Log

/**
 * Holds the loaded sherpa-onnx recognizers. Loading takes a second or two, so it happens on a
 * background thread the first time voice typing starts. Open Keyboard: the models (often
 * close to 1 GB) are freed a few minutes after voice typing was last used, so the keyboard
 * doesn't sit on that memory all day (which made the phone close it and reopen it slowly).
 */
object VoiceEngine {
    const val SAMPLE_RATE = 16000

    @Volatile private var online: OnlineRecognizer? = null
    @Volatile private var onlineModel: VoiceModel? = null
    /** Whether the live model in use writes capitals and punctuation itself. */
    val liveWritesCapitals: Boolean get() = true
    @Volatile private var offline: OfflineRecognizer? = null
    @Volatile private var offlineModel: VoiceModel? = null

    /** Voice sessions still using the models (listening, or sentences still being checked). */
    private var users = 0
    private var lastUsed = 0L
    const val IDLE_RELEASE_MS = 5 * 60_000L

    /** For the lag log. */
    val loaded: Boolean get() = online != null || offline != null
    /** Whether the live model is already in memory (for the timing log). */
    val liveLoaded: Boolean get() = online != null
    /** Whether the accuracy model is already in memory (for the timing log). */
    val accuracyLoaded: Boolean get() = offline != null

    @Synchronized fun begin() { users++; lastUsed = android.os.SystemClock.uptimeMillis() }
    @Synchronized fun end() { users = (users - 1).coerceAtLeast(0); lastUsed = android.os.SystemClock.uptimeMillis() }

    /** Frees the models if no voice session has used them for [idleMs]. Never frees them mid-use. */
    @Synchronized
    fun releaseIfIdle(idleMs: Long) {
        if (users > 0 || online == null && offline == null && denoiser == null) return
        if (android.os.SystemClock.uptimeMillis() - lastUsed < idleMs) return
        Log.i("VoiceEngine", "voice models not used for a while, freeing their memory")
        release()
    }

    @Synchronized
    fun onlineRecognizer(context: Context): OnlineRecognizer {
        val m = VoicePrefs.liveModel(context) ?: throw IllegalStateException("live model not downloaded")
        if (m == onlineModel) online?.let { return it }
        online?.release(); online = null; onlineModel = null
        val f = { name: String -> m.file(context, name).absolutePath }
        val modelConfig = OnlineModelConfig().apply {
            transducer = OnlineTransducerModelConfig().apply {
                encoder = f(m.files[0]); decoder = f(m.files[1]); joiner = f(m.files[2])
            }
            tokens = f(m.files[3])
            // the big model needs more of the phone's cores to keep up with speech
            numThreads = 4
        }
        Log.i("VoiceEngine", "live model: ${m.name}")
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f)
            this.modelConfig = modelConfig
            enableEndpoint = true
            // rule1: nothing said yet, rule2: pause after speech ends a sentence, rule3: very long sentence
            endpointConfig = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 1.0f, 0f),
                EndpointRule(false, 0f, 25f),
            )
            decodingMethod = "greedy_search"
        }
        return OnlineRecognizer(null, config).also { online = it; onlineModel = m }
    }

    /** The accuracy model chosen in settings, or null if none is chosen or installed. */
    @Synchronized
    fun offlineRecognizer(context: Context): OfflineRecognizer? {
        val wanted = VoicePrefs.accuracyModel(context)?.takeIf { it.isInstalled(context) }
        if (wanted == offlineModel && offline != null) return offline
        offline?.release(); offline = null; offlineModel = null
        if (wanted == null) return null
        val f = { name: String -> wanted.file(context, name).absolutePath }
        val modelConfig = OfflineModelConfig().apply {
            numThreads = 4
            when (wanted) {
                VoiceModel.PARAKEET_EN -> {
                    transducer = OfflineTransducerModelConfig().apply {
                        encoder = f(wanted.files[0]); decoder = f(wanted.files[1]); joiner = f(wanted.files[2])
                    }
                    tokens = f(wanted.files[3])
                    modelType = "nemo_transducer"
                }
                else -> return null
            }
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f)
            this.modelConfig = modelConfig
            decodingMethod = "greedy_search"
        }
        return OfflineRecognizer(null, config).also { offline = it; offlineModel = wanted }
    }

    @Volatile private var denoiser: OfflineSpeechDenoiser? = null

    /** GTCRN noise remover (MIT licence, about 0.5 MB, shipped inside the app). */
    @Synchronized
    private fun denoiser(context: Context): OfflineSpeechDenoiser? {
        denoiser?.let { return it }
        return runCatching {
            val config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "gtcrn_simple.onnx"),
                    numThreads = 1,
                )
            )
            OfflineSpeechDenoiser(context.assets, config)
        }.onFailure { Log.e("VoiceEngine", "could not load noise remover", it) }.getOrNull().also { denoiser = it }
    }

    /**
     * Removes background noise from a finished sentence, if turned on in settings.
     * Open Keyboard: the cleaned audio is blended with the original (60/40). Tested in the cloud
     * with car and truck noise mixed into speech: the fully cleaned audio made Parakeet worse in
     * loud noise (6.9% wrong words against 4.4% with no cleaning at -5 dB), because the cleaner
     * also takes bits of the voice away. The blend did best (3.8-4.2%).
     */
    private fun clean(context: Context, samples: FloatArray): FloatArray {
        if (!VoicePrefs.noiseReduction(context)) return samples
        val d = denoiser(context) ?: return samples
        return runCatching {
            val out = d.run(samples, SAMPLE_RATE)
            if (out.sampleRate != SAMPLE_RATE || out.samples.isEmpty()) return samples
            val cleaned = out.samples
            FloatArray(samples.size) { i ->
                val c = if (i < cleaned.size) cleaned[i] else 0f
                CLEAN_SHARE * c + (1 - CLEAN_SHARE) * samples[i]
            }
        }.getOrDefault(samples)
    }

    private const val CLEAN_SHARE = 0.6f

    /**
     * A new speech detector (Silero VAD, MIT licence, about 0.6 MB, shipped inside the app) for
     * one voice session. Open Keyboard: it decides where each sentence starts and ends, instead of
     * the small live model. In noise the live model often heard nothing and the sentence was thrown
     * away before the accurate model got to hear it. Null if it can't be loaded (then the old way is used).
     */
    fun newSpeechDetector(context: Context): Vad? = runCatching {
        Vad(context.assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.8f, // a pause this long ends the sentence
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 25f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ))
    }.onFailure { Log.e("VoiceEngine", "could not load speech detector", it) }.getOrNull()

    const val VAD_WINDOW = 512

    /** Run the accuracy model on a finished sentence. Returns null if there is no accuracy model. */
    fun transcribeFinal(context: Context, rawSamples: FloatArray): String? {
        val recognizer = offlineRecognizer(context) ?: return null
        val samples = clean(context, rawSamples)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        online?.release(); online = null; onlineModel = null
        offline?.release(); offline = null; offlineModel = null
        denoiser?.release(); denoiser = null
    }
}
