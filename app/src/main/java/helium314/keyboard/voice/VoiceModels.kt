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
    /** Small streaming model that types words while you speak. Required for voice typing. */
    STREAMING_EN(
        "Live English (streaming Zipformer)",
        "Shows words as you speak. Required for voice typing.",
        "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
        listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt",
        ),
        73, "Apache-2.0",
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
    );

    fun dir(context: Context) = File(context.filesDir, "voice-models/$name")
    fun file(context: Context, name: String) = File(dir(context), name)
    fun url(fileName: String) = "https://huggingface.co/$repo/resolve/main/$fileName"
    fun isInstalled(context: Context) = files.all { file(context, it).let { f -> f.isFile && f.length() > 0 } }

    companion object {
        /** Models that can re-check a finished sentence. */
        val accuracyModels = listOf(PARAKEET_EN, WHISPER_SMALL_EN)
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
}
