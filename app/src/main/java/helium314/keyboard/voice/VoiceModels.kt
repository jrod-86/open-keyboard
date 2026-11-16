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
    /**
     * Shows the words while you speak. Open Keyboard (test 184/185): chosen after cloud tests with
     * truck noise mixed into speech, live words wrong at 0 dB: small Zipformer 38%, NVIDIA
     * FastConformer 19%, Nemotron 17%. Writes capitals and punctuation itself.
     */
    LIVE_NEMOTRON(
        "Live words (NVIDIA Nemotron)",
        "Shows your words as you speak, with capitals and punctuation. Credit: NVIDIA, NVIDIA Open Model License.",
        "csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        633, "NVIDIA Open Model License",
    ),

    /** Re-checks each sentence when you pause and makes it accurate. */
    PARAKEET_EN(
        "Clean-up (NVIDIA Parakeet)",
        "When you pause, listens to the sentence again and fixes any wrong words. Credit: NVIDIA, licensed CC-BY-4.0.",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        661, "CC-BY-4.0",
    );

    fun dir(context: Context) = File(context.filesDir, "voice-models/$name")
    fun file(context: Context, name: String) = File(dir(context), name)
    fun url(fileName: String) = "https://huggingface.co/$repo/resolve/main/$fileName"
    fun isInstalled(context: Context) = files.all { file(context, it).let { f -> f.isFile && f.length() > 0 } }

    companion object {
        /** Folders of models earlier test builds offered, removed to free space (Jarrod, 16 Nov: keep only the two). */
        private val oldModelFolders = listOf("STREAMING_EN", "LIVE_FASTCONFORMER", "WHISPER_SMALL_EN", "WHISPER_TURBO")

        /** Deletes the models older builds could download. */
        fun removeOldModels(context: Context) {
            val root = File(context.filesDir, "voice-models")
            for (name in oldModelFolders) {
                File(root, name).takeIf { it.exists() }?.deleteRecursively()
            }
        }
    }
}

/** User choices for voice typing, kept in their own small preferences file. */
object VoicePrefs {
    private const val FILE = "voice_typing"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The clean-up model, or null if it isn't downloaded yet. */
    fun accuracyModel(context: Context): VoiceModel? = VoiceModel.PARAKEET_EN.takeIf { it.isInstalled(context) }

    /** The live model, or null if it isn't downloaded yet. */
    fun liveModel(context: Context): VoiceModel? = VoiceModel.LIVE_NEMOTRON.takeIf { it.isInstalled(context) }

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
