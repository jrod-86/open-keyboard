// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-device text to speech with Kokoro-82M v1.0 (Apache-2.0, hexgrad), run by sherpa-onnx (Apache-2.0).
 * The int8 model (k2-fsa export, Apache-2.0) is downloaded once from Hugging Face at a fixed commit, ~155 MB,
 * and every file is checked against its SHA-256.
 */
object KokoroTts {
    private const val TAG = "KokoroTts"

    /**
     * The best English voices by the Kokoro makers' own grades (VOICES.md): female af_heart (A), af_bella (A-),
     * bf_emma (B-, British); male am_fenrir, am_michael, am_puck (C+, the top male grade). [sid] is the speaker id
     * in voices.bin of kokoro-multi-lang-v1_0.
     */
    class Voice(val id: String, val name: String, val sid: Int, val female: Boolean, val british: Boolean, val about: String)

    val VOICES = listOf(
        Voice("af_heart", "Heart", 3, true, false, "Warm and natural · top rated"),
        Voice("af_bella", "Bella", 2, true, false, "Bright and lively"),
        Voice("bf_emma", "Emma", 21, true, true, "British, calm and clear"),
        Voice("am_fenrir", "Fenrir", 14, false, false, "Deep and strong"),
        Voice("am_michael", "Michael", 16, false, false, "Friendly and steady"),
        Voice("am_puck", "Puck", 18, false, false, "Upbeat and playful"),
    )

    private const val REPO = "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2/"

    private class ModelFile(val name: String, val size: Long, val sha: String)

    private val FILES = listOf(
        ModelFile("model.int8.onnx", 114202993, "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"),
        ModelFile("voices.bin", 28200960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
        ModelFile("tokens.txt", 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        ModelFile("lexicon-us-en.txt", 5956885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
        ModelFile("lexicon-gb-en.txt", 6366635, "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"),
        ModelFile("LICENSE", 11358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }
    const val RATE = 24000

    fun dir(ctx: Context) = File(ctx.filesDir, "tts-models/kokoro-v1")
    fun installed(ctx: Context) = FILES.all { File(dir(ctx), it.name).length() == it.size }

    /** Bytes still to fetch (for the download pop-up). */
    fun missingBytes(ctx: Context) = FILES.filter { File(dir(ctx), it.name).length() != it.size }.sumOf { it.size }

    /** Fetches the missing files, reporting overall progress 0..1. Can be stopped with [cancel]. */
    fun download(ctx: Context, cancel: () -> Boolean, onProgress: (Float) -> Unit) {
        val d = dir(ctx).apply { mkdirs() }
        var done = FILES.filter { File(d, it.name).length() == it.size }.sumOf { it.size }
        for (f in FILES) {
            val target = File(d, f.name)
            if (target.length() == f.size) continue
            val part = File(d, f.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            var url = URL(REPO + f.name)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) { // Hugging Face redirects to its file servers
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                if (code != 200) throw IllegalStateException("HTTP $code for ${f.name}")
                break
            }
            try {
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancel()) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (done - last > 512 * 1024) { last = done; onProgress(done.toFloat() / TOTAL_BYTES) }
                        }
                    }
                }
            } finally { conn.disconnect() }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != f.sha || part.length() != f.size) { part.delete(); throw IllegalStateException("bad download: ${f.name}") }
            if (!part.renameTo(target)) throw IllegalStateException("could not save ${f.name}")
        }
        onProgress(1f)
    }

    fun delete(ctx: Context) {
        release()
        dir(ctx).deleteRecursively()
    }

    // ---- engine ----

    private var tts: OfflineTts? = null
    private var loadedBritish: Boolean? = null
    private var words: Set<String>? = null
    private var wordsBritish: Boolean? = null

    /** Loads the engine for the voice's accent (British voices read with the British word list). Slow the first time. */
    @Synchronized
    fun engine(ctx: Context, voice: Voice, maxThreads: Int = 4): OfflineTts {
        tts?.let { if (loadedBritish == voice.british) return it }
        release()
        val d = dir(ctx)
        val extra = Pronounce.writeExtraLexicon(File(d, "extra-lexicon.txt"))
        val lexicon = File(d, if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt").path + "," + extra.path
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, maxThreads.coerceAtLeast(2))
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(d, "model.int8.onnx").path,
                    voices = File(d, "voices.bin").path,
                    tokens = File(d, "tokens.txt").path,
                    lexicon = lexicon,
                ),
                numThreads = threads,
            ),
            maxNumSentences = 1, // a callback per sentence, so playing starts after the first one
        )
        val t0 = System.currentTimeMillis()
        val e = OfflineTts(config = config)
        Log.i(TAG, "engine ready in ${System.currentTimeMillis() - t0} ms (british=${voice.british}, threads=$threads)")
        tts = e; loadedBritish = voice.british
        return e
    }

    /** The words the voice's word list knows, for spotting names it would otherwise skip. */
    @Synchronized
    fun knownWords(ctx: Context, voice: Voice): Set<String> {
        words?.let { if (wordsBritish == voice.british) return it }
        val f = File(dir(ctx), if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt")
        val set = HashSet<String>(400_000)
        f.bufferedReader().useLines { lines -> lines.forEach { l -> val sp = l.indexOf(' '); if (sp > 0) set.add(l.substring(0, sp)) } }
        words = set; wordsBritish = voice.british
        return set
    }

    @Synchronized
    fun release() {
        tts?.release(); tts = null; loadedBritish = null
    }
}
