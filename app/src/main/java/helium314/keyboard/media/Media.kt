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

data class Gif(val id: String, val previewUrl: String, val sendUrl: String, val pngUrl: String?, val width: Int, val height: Int)

/** Talks to the KLIPY GIF API (https://docs.klipy.com). Only used when the user turns GIFs on. */
object Klipy {
    private const val BASE = "https://api.klipy.com/api/v1/"

    /** Trending GIFs when [query] is null, otherwise a search. Runs on the calling (background) thread. */
    fun gifs(ctx: Context, query: String?, stickers: Boolean = false): List<Gif> {
        val key = MediaPrefs.klipyKey(ctx)
        val kind = if (stickers) "stickers" else "gifs"
        val formats = if (stickers) "gif,png" else "gif"
        val params = "per_page=30&customer_id=${MediaPrefs.customerId(ctx)}&content_filter=medium&format_filter=$formats"
        val url = if (query == null) "$BASE$key/$kind/trending?$params"
            else "$BASE$key/$kind/search?q=${URLEncoder.encode(query, "UTF-8")}&$params"
        val json = JSONObject(String(download(url)))
        val items = json.getJSONObject("data").getJSONArray("data")
        return (0 until items.length()).mapNotNull { i ->
            runCatching {
                val item = items.getJSONObject(i)
                val file = item.getJSONObject("file")
                val sm = file.getJSONObject("sm").getJSONObject("gif")
                val md = file.optJSONObject("md")?.optJSONObject("gif") ?: sm
                val png = file.optJSONObject("md")?.optJSONObject("png")?.optString("url")?.takeIf { it.isNotEmpty() }
                Gif(item.get("id").toString(), sm.getString("url"), md.getString("url"), png, sm.optInt("width"), sm.optInt("height"))
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

    /** Sends a GIF, or a sticker (animated if the app takes GIFs, otherwise a still PNG with a clear background). */
    fun sendGif(ime: InputMethodService, gif: Gif, sticker: Boolean = false) {
        val accepted = ime.currentInputEditorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()
        val takesGif = accepted.any { ClipDescription.compareMimeTypes("image/gif", it) }
        val usePng = sticker && gif.pngUrl != null && !takesGif
        val url = if (usePng) gif.pngUrl!! else gif.sendUrl
        val mime = if (usePng) "image/png" else "image/gif"
        val label = if (sticker) "Sticker" else "GIF"
        MediaRecents.add(ime, RecentItem.Online(gif, sticker))
        io.execute {
            val file = runCatching {
                val f = File(mediaDir(ime), "${label.lowercase()}_${gif.id.filter { it.isLetterOrDigit() }}.${if (usePng) "png" else "gif"}")
                if (!f.isFile) f.writeBytes(Klipy.download(url))
                f
            }.onFailure { Log.e("MediaSender", "download failed", it) }.getOrNull()
            main.post {
                if (file == null) toast(ime, "Couldn't download that ${label.lowercase()}")
                else commit(ime, file, mime, label, Uri.parse(url))
            }
        }
    }

    /** Sends a sticker from one of the user's own packs. */
    fun sendPackSticker(ime: InputMethodService, file: File) {
        val mime = StickerPacks.mimeOf(file)
        MediaRecents.add(ime, RecentItem.PackSticker(file))
        StickerPacks.used(ime, file)
        commit(ime, file, mime, "Sticker", null)
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

/**
 * The user's own sticker packs (for example a favourite character), kept on the phone in
 * files/sticker-packs/<pack name>/. A pack is made from photos in the gallery or imported from a .zip.
 */
object StickerPacks {
    private const val MAX_SIZE = 512

    fun root(ctx: Context) = File(ctx.filesDir, "sticker-packs").apply { mkdirs() }

    fun packs(ctx: Context): List<File> =
        root(ctx).listFiles()?.filter { it.isDirectory && stickers(it).isNotEmpty() }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private const val USE_PREFS = "sticker_use"
    private const val HALF_LIFE_MS = 7 * 24 * 3600 * 1000.0

    /** Remembers a sticker was sent, so it moves up its pack. */
    fun used(ctx: Context, file: File) {
        val prefs = ctx.getSharedPreferences(USE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val (score, at) = useOf(prefs.getString(file.path, null))
        prefs.edit().putString(file.path, "${decayed(score, at, now) + 1}:$now").apply()
    }

    private fun useOf(s: String?): Pair<Double, Long> =
        s?.split(':')?.let { (it[0].toDoubleOrNull() ?: 0.0) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) } ?: (0.0 to 0L)

    private fun decayed(score: Double, at: Long, now: Long) = score * Math.pow(0.5, (now - at) / HALF_LIFE_MS)

    /** A pack's stickers with the last one sent first, then the most used, then the rest in order. */
    fun byUse(ctx: Context, pack: File): List<File> {
        val list = stickers(pack)
        val prefs = ctx.getSharedPreferences(USE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val uses = list.associateWith { useOf(prefs.getString(it.path, null)) }
        val latest = list.filter { uses[it]!!.second > 0 }.maxByOrNull { uses[it]!!.second }
        val rest = list.filter { it != latest }
            .sortedByDescending { val (sc, at) = uses[it]!!; decayed(sc, at, now) } // stable: unused keep their order
        return listOfNotNull(latest) + rest
    }

    fun stickers(pack: File): List<File> =
        pack.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("png", "webp", "gif", "jpg", "jpeg") }
            ?.sortedBy { it.name } ?: emptyList()

    fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/png"
    }

    fun delete(pack: File) = pack.deleteRecursively()

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40).ifEmpty { "My pack" }

    private fun newPackDir(ctx: Context, name: String): File {
        val base = safeName(name)
        var dir = File(root(ctx), base)
        var i = 2
        while (dir.exists()) dir = File(root(ctx), "$base $i").also { i++ }
        return dir.apply { mkdirs() }
    }

    /** Makes a pack from pictures the user picked. Returns how many stickers were added. */
    fun createFromImages(ctx: Context, name: String, uris: List<Uri>): Int {
        val dir = newPackDir(ctx, name)
        var count = 0
        uris.forEachIndexed { i, uri ->
            runCatching {
                val mime = ctx.contentResolver.getType(uri) ?: ""
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                saveSticker(dir, "%03d".format(i), bytes, mime)
                count++
            }.onFailure { Log.e("StickerPacks", "could not add picture", it) }
        }
        if (count == 0) dir.deleteRecursively()
        return count
    }

    /** Imports a pack someone shared or downloaded as a .zip of pictures. Returns how many stickers were added. */
    fun importZip(ctx: Context, name: String, uri: Uri): Int =
        ctx.contentResolver.openInputStream(uri)!!.use { importZip(ctx, name, it, null) }

    /** Store packs remember their id, so the store can show them as installed. */
    fun storeId(pack: File) = File(pack, ".store_id").takeIf { it.isFile }?.readText()?.trim()

    fun installedStoreIds(ctx: Context) = packs(ctx).mapNotNull { storeId(it) }.toSet()

    fun importZip(ctx: Context, name: String, stream: java.io.InputStream, storeId: String?): Int {
        val dir = newPackDir(ctx, name)
        if (storeId != null) File(dir, ".store_id").writeText(storeId)
        var count = 0
        stream.let { input ->
            val zip = java.util.zip.ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (entry.isDirectory || entry.name.contains("__MACOSX") || ext !in listOf("png", "webp", "gif", "jpg", "jpeg")) continue
                if (count >= 200) break
                runCatching {
                    saveSticker(dir, "%03d".format(count), zip.readBytes(), if (ext == "gif") "image/gif" else if (ext == "webp") "image/webp" else "image/$ext")
                    count++
                }
            }
        }
        if (count == 0) dir.deleteRecursively()
        return count
    }

    /** Animated pictures are kept as they are; still pictures are shrunk to sticker size and saved as PNG. */
    private fun saveSticker(dir: File, baseName: String, bytes: ByteArray, mime: String) {
        // keep animations as they are; a still WebP becomes PNG, which more apps take as a sticker
        val animatedWebp = mime == "image/webp" && bytes.size > 40 && String(bytes, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (bytes[20].toInt() and 0x02) != 0
        if (mime == "image/gif" || animatedWebp) {
            File(dir, "$baseName.${mime.substringAfter('/')}").writeBytes(bytes)
            return
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not a picture")
        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        File(dir, "$baseName.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** One thing sent from the keyboard, for the Recent tab: a KLIPY GIF or sticker, a pack sticker, or an emoji. */
sealed class RecentItem {
    data class Online(val gif: Gif, val sticker: Boolean) : RecentItem()
    data class PackSticker(val file: File) : RecentItem()
    data class Emoji(val text: String) : RecentItem()

    val key: String get() = when (this) {
        is Online -> "o:${gif.id}:$sticker"
        is PackSticker -> "p:${file.absolutePath}"
        is Emoji -> "e:$text"
    }
}

/** The most recently sent stickers, GIFs and emojis, newest first. Kept on the phone only. */
object MediaRecents {
    private const val MAX = 48
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stickers_gifs", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<RecentItem> {
        val arr = runCatching { org.json.JSONArray(prefs(ctx).getString("recents", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                when (o.getString("t")) {
                    "o" -> RecentItem.Online(Gif(o.getString("id"), o.getString("pv"), o.getString("su"),
                        o.optString("png").takeIf { it.isNotEmpty() }, o.optInt("w"), o.optInt("h")), o.optBoolean("s"))
                    "p" -> File(o.getString("f")).takeIf { it.isFile }?.let { RecentItem.PackSticker(it) }
                    else -> RecentItem.Emoji(o.getString("e"))
                }
            }.getOrNull()
        }
    }

    fun add(ctx: Context, item: RecentItem) {
        val items = (listOf(item) + list(ctx).filter { it.key != item.key }).take(MAX)
        val arr = org.json.JSONArray()
        items.forEach { it ->
            arr.put(JSONObject().apply {
                when (it) {
                    is RecentItem.Online -> { put("t", "o"); put("id", it.gif.id); put("pv", it.gif.previewUrl); put("su", it.gif.sendUrl)
                        put("png", it.gif.pngUrl ?: ""); put("w", it.gif.width); put("h", it.gif.height); put("s", it.sticker) }
                    is RecentItem.PackSticker -> { put("t", "p"); put("f", it.file.absolutePath) }
                    is RecentItem.Emoji -> { put("t", "e"); put("e", it.text) }
                }
            })
        }
        prefs(ctx).edit().putString("recents", arr.toString()).apply()
    }

    @JvmStatic
    fun addEmoji(ctx: Context, text: String?) {
        if (!text.isNullOrBlank()) add(ctx, RecentItem.Emoji(text))
    }
}
