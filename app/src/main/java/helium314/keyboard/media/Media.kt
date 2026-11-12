// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.ClipDescription
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

/** Settings for GIF search, kept in their own small preferences file. */
object MediaPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stickers_gifs", Context.MODE_PRIVATE)

    /** GIF search is off until the user turns it on, because searches go to an online service. */
    fun gifsEnabled(ctx: Context) = prefs(ctx).getBoolean("gifs_enabled", false)
    fun setGifsEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("gifs_enabled", on).apply()

    fun klipyKey(ctx: Context) = prefs(ctx).getString("klipy_key", "")!!.trim()
    fun setKlipyKey(ctx: Context, key: String) = prefs(ctx).edit().putString("klipy_key", key.trim()).apply()

    fun gifsReady(ctx: Context) = gifsEnabled(ctx) && klipyKey(ctx).isNotEmpty()

    /** Random id KLIPY asks for, so it can tell users apart. Not linked to anything else. */
    fun customerId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("customer_id", null)?.let { return it }
        return UUID.randomUUID().toString().also { p.edit().putString("customer_id", it).apply() }
    }
}

/** Free-licence stickers shipped in the app (Microsoft Fluent Emoji 3D, MIT licence). */
object Stickers {
    fun list(ctx: Context): List<String> =
        (ctx.assets.list("stickers") ?: emptyArray()).filter { it.endsWith(".png") }.sorted()
            .sortedBy { order.indexOf(it.removeSuffix(".png")).let { i -> if (i < 0) 999 else i } }

    private val order = listOf(
        "face_with_tears_of_joy", "rolling_on_the_floor_laughing", "smiling_face_with_heart_eyes", "red_heart",
        "thumbs_up", "fire", "party_popper", "pleading_face", "loudly_crying_face", "face_blowing_a_kiss",
        "winking_face", "smiling_face_with_sunglasses", "thinking_face", "face_with_rolling_eyes", "clapping_hands",
        "folded_hands", "hundred_points", "sparkles", "star_struck", "partying_face",
    )
}

data class Gif(val id: String, val previewUrl: String, val sendUrl: String, val width: Int, val height: Int)

/** Talks to the KLIPY GIF API (https://docs.klipy.com). Only used when the user turns GIFs on. */
object Klipy {
    private const val BASE = "https://api.klipy.com/api/v1/"

    /** Trending GIFs when [query] is null, otherwise a search. Runs on the calling (background) thread. */
    fun gifs(ctx: Context, query: String?): List<Gif> {
        val key = MediaPrefs.klipyKey(ctx)
        val params = "per_page=30&customer_id=${MediaPrefs.customerId(ctx)}&content_filter=medium&format_filter=gif"
        val url = if (query == null) "$BASE$key/gifs/trending?$params"
            else "$BASE$key/gifs/search?q=${URLEncoder.encode(query, "UTF-8")}&$params"
        val json = JSONObject(String(download(url)))
        val items = json.getJSONObject("data").getJSONArray("data")
        return (0 until items.length()).mapNotNull { i ->
            runCatching {
                val item = items.getJSONObject(i)
                val file = item.getJSONObject("file")
                val sm = file.getJSONObject("sm").getJSONObject("gif")
                val md = file.optJSONObject("md")?.optJSONObject("gif") ?: sm
                Gif(item.get("id").toString(), sm.getString("url"), md.getString("url"), sm.optInt("width"), sm.optInt("height"))
            }.getOrNull()
        }
    }

    fun download(url: String): ByteArray {
        var conn = URL(url).openConnection() as HttpURLConnection
        var redirects = 0
        while (true) {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                continue
            }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            break
        }
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }
}

/** Sends a sticker or GIF into the app being typed in, the same way Gboard does. */
object MediaSender {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun sendSticker(ime: InputMethodService, assetName: String) {
        io.execute {
            val file = runCatching {
                val f = File(mediaDir(ime), "sticker_$assetName")
                if (!f.isFile) ime.assets.open("stickers/$assetName").use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            }.getOrNull()
            main.post { if (file != null) commit(ime, file, "image/png", "Sticker", null) }
        }
    }

    fun sendGif(ime: InputMethodService, gif: Gif) {
        io.execute {
            val file = runCatching {
                val f = File(mediaDir(ime), "gif_${gif.id.filter { it.isLetterOrDigit() }}.gif")
                if (!f.isFile) f.writeBytes(Klipy.download(gif.sendUrl))
                f
            }.onFailure { Log.e("MediaSender", "gif download failed", it) }.getOrNull()
            main.post {
                if (file == null) toast(ime, "Couldn't download that GIF")
                else commit(ime, file, "image/gif", "GIF", Uri.parse(gif.sendUrl))
            }
        }
    }

    private fun mediaDir(ctx: Context) = File(ctx.cacheDir, "media").apply {
        mkdirs()
        // keep the cache small
        listFiles()?.sortedBy { it.lastModified() }?.dropLast(40)?.forEach { it.delete() }
    }

    private fun commit(ime: InputMethodService, file: File, mime: String, label: String, link: Uri?) {
        val editorInfo = ime.currentInputEditorInfo
        val ic = ime.currentInputConnection
        if (editorInfo == null || ic == null) return
        val accepted = EditorInfoCompat.getContentMimeTypes(editorInfo)
        val fits = accepted.any { ClipDescription.compareMimeTypes(mime, it) }
            || (mime == "image/gif" && accepted.any { ClipDescription.compareMimeTypes("image/png", it) })
        if (!fits) {
            toast(ime, "This app doesn't accept ${label.lowercase()}s here")
            return
        }
        val uri = FileProvider.getUriForFile(ime, ime.packageName + ".media", file)
        val info = InputContentInfoCompat(uri, ClipDescription(label, arrayOf(mime)), link)
        val ok = InputConnectionCompat.commitContent(ic, editorInfo, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        if (!ok) toast(ime, "This app didn't accept the ${label.lowercase()}")
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
