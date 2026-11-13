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
object KhmerTranslator {
    private const val TAG = "KhmerTranslator"
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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "llm/madlad400-3b-mt-q4_k_m.gguf")
    private fun toast(ctx: Context, s: String) = main.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }

    /** "km" when voice typing should type Khmer from spoken English, otherwise null. */
    fun voiceTarget(ctx: Context): String? = prefs(ctx).getString(PREF_VOICE_TO, null)

    /** Turns "speak English, type Khmer" on or off. Starts the model download if it's missing. Returns the new state. */
    fun toggleVoiceKhmer(ctx: Context): Boolean {
        val on = voiceTarget(ctx) == null
        prefs(ctx).edit().putString(PREF_VOICE_TO, if (on) "km" else null).apply()
        if (on) {
            if (downloaded(ctx)) toast(ctx, "Voice typing: speak English, it types Khmer")
            else ensureDownload(ctx)
        } else toast(ctx, "Voice typing: back to English")
        return on
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
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Open Keyboard Khmer translation")
            .setDescription("About 1.9 GB, needed once for offline Khmer")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(f)))
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
                        OfflineTranslator.translate("<2$to> $s", 256).trim().ifEmpty { s }
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

    private fun splitSentences(s: String): List<String> =
        Regex("(?<=[.!?។៕])\\s+").split(s.trim()).filter { it.isNotBlank() }
}
