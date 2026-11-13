// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.arm.aichat.OfflineTranslator
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Offline English ⇄ Khmer translation with Google's MADLAD-400 3B MT model (Apache-2.0), run by
 * llama.cpp (MIT) on the phone. Mozilla's models (used for the other languages) have no Khmer.
 * The model downloads once (about 1.9 GB); after that nothing leaves the phone.
 */
object MadladTranslator {
    private const val TAG = "MadladTranslator"
    private const val MODEL_URL = "https://huggingface.co/mtsdurica/madlad400-3b-mt-Q4_K_M-GGUF/resolve/" +
        "7f55e827c4c2fd1fdc52893879e18966d2867dba/madlad400-3b-mt-q4_k_m.gguf"
    private const val MODEL_SHA256 = "fc56f16d215db71e856de3c3770974c867e3d95a782d415d4cfabc9fb470b8e4"
    private const val MODEL_SIZE = 1_858_124_864L
    private const val PREFS = "open_keyboard_mt"
    private const val PREF_DOWNLOAD_ID = "download_id"
    private const val PREF_VOICE_TO = "voice_translate_to"

    private val main = Handler(Looper.getMainLooper())
    private val idle = Executors.newSingleThreadScheduledExecutor()
    private var unload: ScheduledFuture<*>? = null

    @Volatile private var lastError: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "llm/madlad400-3b-mt-q4_k_m.gguf")
    private fun toast(ctx: Context, s: String) = main.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }

    /** "km" when voice typing should type Khmer from spoken English, otherwise null. */
    fun voiceTarget(ctx: Context): String? = prefs(ctx).getString(PREF_VOICE_TO, null)

    /**
     * Languages only this model covers (Mozilla's models, used for the rest, don't have them).
     * Quality is good for Khmer; for the smaller languages it varies.
     */
    val languages = listOf(
        "km", "lo", "my", "fil", "ceb", "jv", "su", "sw", "am", "so", "ha", "yo", "ig", "zu", "xh", "ne", "si", "pa",
        "ps", "ku", "mn", "ka", "hy", "uz", "kk", "be", "mk", "cy", "ga", "mt", "lb", "la", "eo", "mi", "sm", "to", "fj",
        "haw", "hmn",
    )

    fun covers(tag: String) = tag in languages

    fun setVoiceTarget(ctx: Context, tag: String?) {
        prefs(ctx).edit().putString(PREF_VOICE_TO, tag).apply()
        if (tag != null && covers(tag) && !downloaded(ctx)) ensureDownload(ctx)
    }

    /** The model file is fully downloaded (it's checked the first time it's used). */
    fun downloaded(ctx: Context) = modelFile(ctx).length() == MODEL_SIZE

    fun modelReady(ctx: Context): Boolean {
        val f = modelFile(ctx)
        return f.length() == MODEL_SIZE && File(f.path + ".ok").let { it.exists() && it.lastModified() >= f.lastModified() }
    }

    private fun downloadRunning(ctx: Context): Boolean {
        val id = prefs(ctx).getLong(PREF_DOWNLOAD_ID, -1)
        if (id < 0) return false
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return false
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
        }
        return false
    }

    /** Returns true if the model is ready; otherwise starts (or reports) the one-time download. */
    fun ensureDownload(ctx: Context): Boolean {
        val f = modelFile(ctx)
        if (f.length() == MODEL_SIZE) return true
        if (downloadRunning(ctx)) { toast(ctx, "The Khmer model is still downloading. You'll get a notification when it's done."); return false }
        f.parentFile?.mkdirs()
        f.delete()
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        val id = try {
            dm.enqueue(DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Open Keyboard Khmer translation")
                .setDescription("About 1.9 GB, needed once for offline Khmer")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(f)))
        } catch (e: Throwable) {
            Log.w(TAG, "download failed to start", e)
            lastError = "The download couldn't start: ${e.message}"
            return false
        }
        lastError = null
        prefs(ctx).edit().putLong(PREF_DOWNLOAD_ID, id).apply()
        toast(ctx, "Downloading the Khmer model (about 1.9 GB, best on Wi-Fi). You'll get a notification when it's done.")
        return false
    }

    private fun verified(file: File): Boolean {
        val marker = File(file.path + ".ok")
        if (marker.exists() && marker.lastModified() >= file.lastModified()) return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
        if (ok) marker.writeText("ok") else file.delete()
        return ok
    }

    /**
     * Translates [text] into [to] ("km" or "en"). Blocking: call off the main thread.
     * Returns null when the model isn't downloaded yet or translation failed.
     */
    fun translate(ctx: Context, text: String, to: String): String? {
        if (text.isBlank()) return text
        val f = modelFile(ctx)
        if (f.length() != MODEL_SIZE) { ensureDownload(ctx); return null }
        synchronized(this) {
            unload?.cancel(false)
            try {
                if (!verified(f)) { toast(ctx, "The Khmer model download was damaged, downloading again"); ensureDownload(ctx); return null }
                if (!OfflineTranslator.load(ctx.applicationContext, f.absolutePath, 4)) { Log.w(TAG, "model failed to load"); return null }
                // one sentence at a time translates best; keep line breaks
                val out = text.split("\n").joinToString("\n") { line ->
                    if (line.isBlank()) line
                    else splitSentences(line).joinToString(if (to == "km") " " else " ") { s ->
                        clean(OfflineTranslator.translate("<2$to> $s", 256)).ifEmpty { s }
                    }
                }
                return out
            } catch (e: Throwable) {
                Log.w(TAG, "translation failed", e)
                return null
            } finally {
                unload = idle.schedule({ synchronized(this) { OfflineTranslator.unload() } }, 90, TimeUnit.SECONDS)
            }
        }
    }

    /** The model sometimes writes HTML codes like "&#160;" (often spaced out as "& # 160 ;"); turn them back into text. */
    private fun clean(out: String): String {
        var t = Regex("&\\s*#\\s*(\\d{2,6})\\s*;").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
        t = t.replace(Regex("&\\s*(nbsp|amp|quot|lt|gt|apos)\\s*;")) { m ->
            when (m.groupValues[1]) { "nbsp" -> " "; "amp" -> "&"; "quot" -> "\""; "lt" -> "<"; "gt" -> ">"; else -> "'" }
        }
        t = t.replace('\u00a0', ' ').replace(Regex(" {2,}"), " ")
        // no space before Khmer or Western sentence endings
        t = t.replace(Regex("\\s+([។៕.!?,])"), "$1")
        return t.trim()
    }

    private fun splitSentences(s: String): List<String> =
        Regex("(?<=[.!?។៕])\\s+").split(s.trim()).filter { it.isNotBlank() }

    /** Download progress as (bytes so far, total) or null when no download is running. */
    private fun progress(ctx: Context): Pair<Long, Long>? {
        val id = prefs(ctx).getLong(PREF_DOWNLOAD_ID, -1)
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return null
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) return null
            return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) to MODEL_SIZE
        }
        return null
    }

    /** Status line for the voice language screen when [tag] needs this model. */
    fun status(ctx: Context, langName: String): String {
        lastError?.let { return it }
        if (downloaded(ctx)) return "On: speak English, and each sentence is typed in $langName when you pause."
        val p = progress(ctx)
        if (p != null) return "Downloading the language model for $langName: ${p.first / 1_000_000} of ${p.second / 1_000_000} MB. " +
            "You can close this; it keeps going and you'll get a notification."
        return "$langName needs a one-time 1.9 GB download (best on Wi-Fi). Tap Download."
    }

    fun downloadRunningNow(ctx: Context) = progress(ctx) != null
    fun clearError() { lastError = null }
}
