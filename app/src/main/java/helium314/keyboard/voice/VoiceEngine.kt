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
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Holds the loaded sherpa-onnx recognizers. Loading takes a second or two, so it happens on a
 * background thread the first time voice typing starts, and the models stay loaded until
 * [release] is called (when the keyboard is destroyed or memory runs low).
 */
object VoiceEngine {
    const val SAMPLE_RATE = 16000

    @Volatile private var online: OnlineRecognizer? = null
    @Volatile private var offline: OfflineRecognizer? = null
    @Volatile private var offlineModel: VoiceModel? = null

    @Synchronized
    fun onlineRecognizer(context: Context): OnlineRecognizer {
        online?.let { return it }
        val m = VoiceModel.STREAMING_EN
        val f = { name: String -> m.file(context, name).absolutePath }
        val modelConfig = OnlineModelConfig().apply {
            transducer = OnlineTransducerModelConfig().apply {
                encoder = f(m.files[0]); decoder = f(m.files[1]); joiner = f(m.files[2])
            }
            tokens = f(m.files[3])
            numThreads = 2
        }
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
        return OnlineRecognizer(null, config).also { online = it }
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
                VoiceModel.WHISPER_SMALL_EN -> {
                    whisper = OfflineWhisperModelConfig().apply {
                        encoder = f(wanted.files[0]); decoder = f(wanted.files[1])
                        language = "en"; task = "transcribe"
                    }
                    tokens = f(wanted.files[2])
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

    /** Run the accuracy model on a finished sentence. Returns null if there is no accuracy model. */
    fun transcribeFinal(context: Context, samples: FloatArray): String? {
        val recognizer = offlineRecognizer(context) ?: return null
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
        online?.release(); online = null
        offline?.release(); offline = null; offlineModel = null
    }
}
