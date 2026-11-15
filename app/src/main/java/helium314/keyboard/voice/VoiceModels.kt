// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import java.io.File

/** Speech models the keyboard can download. All run fully on the device through sherpa-onnx. */
enum class VoiceModel(
    val title: String,
    val description: String,
    val repo: String,
    val files: List<String>,
    val approxMb: Int,
    val license: String,
) {
    /** Small streaming model that types words while you speak. */
    STREAMING_EN(
        "Live English (streaming Zipformer)",
        "Small and fast. Voice typing needs one of the live models.",
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
        listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt",
        ),
        73, "Apache-2.0",
    ),

    /**
     * Open Keyboard: bigger live models (test 184). Tested in the cloud with truck noise mixed into
     * speech, words wrong in the live text at 0 dB: small Zipformer 38%, FastConformer 19%, Nemotron 17%.
     */
    LIVE_FASTCONFORMER(
        "Live English, better (NVIDIA FastConformer)",
        "Live words much closer to right in noise than the small model. Credit: NVIDIA, licensed CC-BY-4.0.",
        "csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        131, "CC-BY-4.0",
    ),

    LIVE_NEMOTRON(
        "Live English, best (NVIDIA Nemotron streaming 0.6B)",
        "Most accurate live words, with capitals and punctuation as you speak. Uses more battery. " +
                "Credit: NVIDIA, NVIDIA Open Model License.",
        "csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        633, "NVIDIA Open Model License",
    ),

    /** Accurate English model used to re-check each finished sentence. Default accuracy model. */
    PARAKEET_EN(
        "NVIDIA Parakeet TDT 0.6B v2",
        "Most accurate English model, adds punctuation and capitals. Credit: NVIDIA, licensed CC-BY-4.0.",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        661, "CC-BY-4.0",
    ),

    /** Alternative accuracy model, for comparing against Parakeet. */
    WHISPER_SMALL_EN(
        "OpenAI Whisper small.en",
        "Alternative accurate English model, adds punctuation and capitals.",
        "csukuangfj/sherpa-onnx-whisper-small.en",
        listOf("small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt"),
        375, "MIT",
    ),

    /** Largest model: best with accents and noise, but slower and a big download. */
    WHISPER_TURBO(
        "OpenAI Whisper large-v3-turbo",
        "Best with strong accents and background noise. Slower and a large download.",
        "csukuangfj/sherpa-onnx-whisper-turbo",
        listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"),
        1036, "MIT",
    );

    fun dir(context: Context) = File(context.filesDir, "voice-models/$name")
    fun file(context: Context, name: String) = File(dir(context), name)
    fun url(fileName: String) = "https://huggingface.co/$repo/resolve/main/$fileName"
    fun isInstalled(context: Context) = files.all { file(context, it).let { f -> f.isFile && f.length() > 0 } }

    companion object {
        /** Models that can re-check a finished sentence. */
        val accuracyModels = listOf(PARAKEET_EN, WHISPER_SMALL_EN, WHISPER_TURBO)
        /** Models that show the words while you speak, best first. */
        val liveModels = listOf(LIVE_NEMOTRON, LIVE_FASTCONFORMER, STREAMING_EN)
    }
}

/** User choices for voice typing, kept in their own small preferences file. */
object VoicePrefs {
    private const val FILE = "voice_typing"
    private const val KEY_ACCURACY_MODEL = "accuracy_model"
    const val NONE = "NONE"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The accuracy model to use, or null to keep the live text as it is. */
    fun accuracyModel(context: Context): VoiceModel? {
        val stored = prefs(context).getString(KEY_ACCURACY_MODEL, VoiceModel.PARAKEET_EN.name)
        return VoiceModel.entries.firstOrNull { it.name == stored && it in VoiceModel.accuracyModels }
    }

    fun setAccuracyModel(context: Context, model: VoiceModel?) {
        prefs(context).edit().putString(KEY_ACCURACY_MODEL, model?.name ?: NONE).apply()
    }

    /**
     * The live model to use: the one picked in settings if downloaded, otherwise the best one
     * downloaded, or null if none is.
     */
    fun liveModel(context: Context): VoiceModel? {
        val stored = prefs(context).getString("live_model", null)
        VoiceModel.liveModels.firstOrNull { it.name == stored && it.isInstalled(context) }?.let { return it }
        return VoiceModel.liveModels.firstOrNull { it.isInstalled(context) }
    }
    fun pickedLiveModel(context: Context): VoiceModel? =
        prefs(context).getString("live_model", null)?.let { n -> VoiceModel.liveModels.firstOrNull { it.name == n } }
    fun setLiveModel(context: Context, model: VoiceModel) = prefs(context).edit().putString("live_model", model.name).apply()

    /** Clean up background noise before recognition. On by default. */
    fun noiseReduction(context: Context) = prefs(context).getBoolean("noise_reduction", true)
    fun setNoiseReduction(context: Context, on: Boolean) = prefs(context).edit().putBoolean("noise_reduction", on).apply()

    /** "call": record like a phone call, so the phone's strongest noise cancelling is used (trucks, traffic). */
    fun callMic(context: Context) = prefs(context).getBoolean("call_mic", false)
    fun setCallMic(context: Context, on: Boolean) = prefs(context).edit().putBoolean("call_mic", on).apply()

    /** Keep a copy of the last voice session, so it can be shared to help tune for noisy places. Off by default. */
    fun keepRecording(context: Context) = prefs(context).getBoolean("keep_recording", false)
    fun setKeepRecording(context: Context, on: Boolean) = prefs(context).edit().putBoolean("keep_recording", on).apply()
    fun lastRecording(context: Context) = java.io.File(context.cacheDir, "voice/last-recording.wav")

    /** Where you're from: sets the spelling used for voice typing. */
    fun region(context: Context): EnglishRegion =
        EnglishRegion.entries.firstOrNull { it.name == prefs(context).getString("region", null) } ?: EnglishRegion.US
    fun setRegion(context: Context, region: EnglishRegion) = prefs(context).edit().putString("region", region.name).apply()
}
