// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ZONOS3-Mini by Zyphra (weights Apache-2.0, runtime MIT): natural 44.1 kHz speech with 27 built-in voices.
 * Its own C++/ggml engine (zonos3-server, see third_party/zonos3) is shipped as an executable named
 * libzonos3_server.so, run as a small local server inside the Text to speech process (127.0.0.1 only), which keeps
 * the model loaded and streams the speech back as it is made. The model (~1.1 GB) is downloaded once from
 * Hugging Face at a fixed commit and checked against its SHA-256.
 */
object ZonosTts {
    private const val TAG = "ZonosTts"
    const val RATE = 44100

    /**
     * A voice: the ten English ones that come with it (reference codes in assets/zonos-voices/[id].json), or one you
     * made ([file] holds its codes, see VoiceClones).
     */
    class Voice(val id: String, val name: String, val female: Boolean, val flag: String, val about: String, val fun_: Boolean = false,
                val file: File? = null)

    val VOICES = listOf(
        Voice("savannah", "Savannah", true, "🇺🇸", "Clear and friendly"),
        Voice("imogen", "Imogen", true, "🇬🇧", "British, warm"),
        Voice("willow", "Willow", true, "🌙", "Soft whisper (ASMR)", true),
        Voice("sakura", "Sakura", true, "🌸", "Anime style", true),
        Voice("morgana", "Morgana", true, "🧙", "Witch", true),
        Voice("wyatt", "Wyatt", false, "🇺🇸", "Relaxed and natural"),
        Voice("rupert", "Rupert", false, "🇬🇧", "British, polished"),
        Voice("cillian", "Cillian", false, "🇮🇪", "Irish"),
        Voice("jett", "Jett", false, "⚡", "Energetic", true),
        Voice("thaddeus", "Thaddeus", false, "🎙️", "Announcer", true),
    )

    private const val REPO = "https://huggingface.co/Zyphra/ZONOS3-Mini/resolve/d2d142a86b1e6aeb4252679673c86ecd86177bf8/gguf/"

    private class ModelFile(val name: String, val size: Long, val sha: String)

    private val FILES = listOf(
        ModelFile("backbone-ptq.gguf", 663150624, "66fceb6d73a8c30bde80249e4112bb308380ab7f3384fa11c8e4048115e4ceec"),
        ModelFile("zac-keep2.gguf", 452325440, "834e5b70b88294afc3c702a478487e0664dcc6b574c16fc6167d72444aabeafd"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }

    fun dir(ctx: Context) = File(ctx.filesDir, "tts-models/zonos3-mini")
    fun installed(ctx: Context) = FILES.all { File(dir(ctx), it.name).length() == it.size }
    fun missingBytes(ctx: Context) = FILES.filter { File(dir(ctx), it.name).length() != it.size }.sumOf { it.size }

    /** The engine is built for newer phone chips (ARMv8.6 with i8mm, e.g. Galaxy S22 and later). */
    fun supported(ctx: Context): Boolean {
        if (!File(ctx.applicationInfo.nativeLibraryDir, "libzonos3_server.so").isFile) return false
        val features = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty() }.getOrDefault("")
        return " i8mm" in features && " asimddp" in features
    }

    fun download(ctx: Context, cancel: () -> Boolean, onProgress: (Float) -> Unit) {
        val d = dir(ctx).apply { mkdirs() }
        var done = FILES.filter { File(d, it.name).length() == it.size }.sumOf { it.size }
        for (f in FILES) {
            val target = File(d, f.name)
            if (target.length() == f.size) continue
            val part = File(d, f.name + ".part")
            // carry on from where a stopped download left off; the hash covers the whole file
            val digest = MessageDigest.getInstance("SHA-256")
            var have = if (part.length() in 1 until f.size) part.length() else { part.delete(); 0L }
            if (have > 0) part.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            done += have
            var url = URL(REPO + f.name)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                if (code == 200 && have > 0) { // server ignored the range: start again
                    part.delete(); digest.reset(); done -= have; have = 0
                } else if (code != 200 && code != 206) throw IllegalStateException("HTTP $code for ${f.name}")
                break
            }
            try {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancel()) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (done - last > 1 shl 20) { last = done; onProgress(done.toFloat() / TOTAL_BYTES) }
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
        stop()
        dir(ctx).deleteRecursively()
        VoiceClones.deleteModels(ctx) // your voices themselves are kept
    }

    // ---- the local engine ----

    private var process: Process? = null
    @Volatile private var port = 0

    /** Starts the engine (once; loading takes a few seconds) and returns its port. */
    @Synchronized
    fun start(ctx: Context): Int {
        process?.let { p -> if (isAlive(p) && port > 0) return port }
        stop()
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libzonos3_server.so")
        val work = File(ctx.cacheDir, "zonos3").apply { mkdirs() }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val cmd = listOf(exe.path, File(dir(ctx), "backbone-ptq.gguf").path,
            "--zac", File(dir(ctx), "zac-keep2.gguf").path,
            "--host", "127.0.0.1", "--port", "0", "--cpu", "--batch", "1", "--zac-threads", "2",
            "--tts-default-voices-dir", "", "--studio-dir", work.path, "--python-src", work.path)
        Log.i(TAG, "starting engine: $cmd")
        val t0 = System.currentTimeMillis()
        val p = ProcessBuilder(cmd).directory(work).redirectErrorStream(true).apply {
            environment()["HOME"] = work.path
            environment()["TMPDIR"] = work.path
            environment()["ZAC_THREADS"] = threads.coerceAtMost(4).toString()
        }.start()
        process = p
        // the engine prints "listening on http://127.0.0.1:PORT" once the model is loaded
        val found = java.util.concurrent.CompletableFuture<Int>()
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.startsWith("repack:")) Log.i(TAG, "engine: $line")
                    Regex("listening on http://127\\.0\\.0\\.1:(\\d+)").find(line)?.let { found.complete(it.groupValues[1].toInt()) }
                }
            }
            found.complete(0)
        }, "zonos3-log").apply { isDaemon = true }.start()
        val got = runCatching { found.get(120, TimeUnit.SECONDS) }.getOrDefault(0)
        if (got <= 0) {
            stop()
            throw IllegalStateException("engine did not start")
        }
        port = got
        Log.i(TAG, "engine ready on port $got in ${System.currentTimeMillis() - t0} ms")
        return got
    }

    private fun isAlive(p: Process) = runCatching { p.exitValue(); false }.getOrDefault(true)

    @Synchronized
    fun stop() {
        process?.let { runCatching { it.destroy() } }
        process = null; port = 0
    }

    private val codesCache = mutableMapOf<String, JSONArray>()
    private fun codes(ctx: Context, v: Voice): JSONArray = synchronized(codesCache) {
        codesCache.getOrPut(v.id) {
            if (v.file != null) JSONObject(v.file.readText()).getJSONArray("codes")
            else JSONArray(ctx.assets.open("zonos-voices/${v.id}.json").bufferedReader().readText())
        }
    }
    fun forget(v: Voice) { synchronized(codesCache) { codesCache.remove(v.id) } }

    /**
     * Speaks [text] with [v], calling [onChunk] with float samples (44.1 kHz mono) as they arrive. [rate] is speaking
     * speed in UTF-8 bytes per second (null = the voice's natural pace). Stops early when [cancel] says so.
     */
    fun speak(ctx: Context, text: String, v: Voice, rate: Double?, cancel: () -> Boolean, onChunk: (FloatArray) -> Unit) {
        val p = start(ctx)
        for (part in split(text)) {
            if (cancel()) return
            val body = JSONObject().apply {
                put("model", "zonos3")
                put("input", part)
                put("language", "en_us")
                put("speaker_reference_codes", codes(ctx, v))
                put("response_format", "pcm")
                if (rate != null) put("condition_values", JSONObject().put("speaking_rate", rate))
            }
            val conn = URL("http://127.0.0.1:$p/v1/audio/speech").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 10_000; conn.readTimeout = 120_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setChunkedStreamingMode(0)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode != 200) {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                    throw IllegalStateException("engine said ${conn.responseCode}: $err")
                }
                conn.inputStream.use { input ->
                    val buf = ByteArray(44100 / 5 * 4) // ~0.2 s
                    var carry = 0
                    while (true) {
                        if (cancel()) return
                        val n = input.read(buf, carry, buf.size - carry)
                        if (n < 0) break
                        val total = carry + n
                        val whole = total / 4 * 4
                        if (whole > 0) {
                            val fb = ByteBuffer.wrap(buf, 0, whole).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            val out = FloatArray(fb.remaining()); fb.get(out)
                            onChunk(out)
                        }
                        carry = total - whole
                        if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry)
                    }
                }
            } finally { conn.disconnect() }
        }
    }

    /** Long texts go in pieces of whole sentences (the model makes up to ~2 minutes per request). */
    private fun split(text: String): List<String> {
        val sentences = Regex("(?<=[.!?…])\\s+|\\n+").split(text.trim()).filter { it.isNotBlank() }
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > 400) { parts.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }
}
