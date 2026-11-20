// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Song info you chose to fix, kept by Music (the music file itself is not changed). */
data class SongFix(val title: String, val artist: String, val album: String, val year: Int, val art: String)

/** A possible match found online. */
data class Match(
    val title: String, val artist: String, val album: String, val year: Int,
    val releaseId: String, val groupId: String, val score: Int, val lengthMs: Long,
) {
    fun confident(song: Song) = score >= 90 && (lengthMs <= 0 || abs(lengthMs - song.durationMs) < 6000)
}

/**
 * Open Keyboard Music (test 234, Jarrod): "Fix song info". Finds songs with missing details or artwork,
 * looks them up on MusicBrainz (free, open music database) with covers from the Cover Art Archive,
 * and lets you pick which ones to apply or skip. Nothing is sent until you tap "Find info".
 */
object SongFixer {
    private const val AGENT = "OpenKeyboard-Music/1.0 ( https://github.com/jrod-86/open-keyboard )"
    @Volatile private var fixes: Map<Long, SongFix>? = null

    private fun fixFile(ctx: Context) = File(ctx.filesDir, "music/fixes.json")
    fun artFile(ctx: Context, id: Long) = File(ctx.filesDir, "music/art/$id.jpg")

    fun all(ctx: Context): Map<Long, SongFix> {
        fixes?.let { return it }
        val map = HashMap<Long, SongFix>()
        runCatching {
            val j = JSONObject(fixFile(ctx).readText())
            j.keys().forEach { k ->
                val o = j.getJSONObject(k)
                map[k.toLong()] = SongFix(o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("year"), o.optString("art"))
            }
        }
        return map.also { fixes = it }
    }

    private fun write(ctx: Context, map: Map<Long, SongFix>) {
        val j = JSONObject()
        map.forEach { (id, f) -> j.put(id.toString(), JSONObject().put("title", f.title).put("artist", f.artist).put("album", f.album).put("year", f.year).put("art", f.art)) }
        runCatching { fixFile(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
        fixes = map
    }

    /** Puts your fixes on top of what's in the music files. */
    fun apply(ctx: Context, song: Song): Song {
        val f = all(ctx)[song.id] ?: return song
        val newAlbum = f.album.ifBlank { song.album }
        return song.copy(
            title = f.title.ifBlank { song.title },
            artist = f.artist.ifBlank { song.artist },
            album = newAlbum,
            albumArtist = if (f.artist.isNotBlank()) f.artist else song.albumArtist,
            year = if (f.year > 0) f.year else song.year,
            // songs moved to a new album get an album number of their own, so they group together
            albumId = if (f.album.isNotBlank() && f.album != song.album) -(abs((newAlbum + "|" + f.artist).hashCode().toLong()) + 1) else song.albumId,
            artFile = f.art,
            fixed = true,
        )
    }

    fun save(ctx: Context, items: Map<Long, SongFix>) = write(ctx, all(ctx) + items)
    fun undo(ctx: Context, id: Long) { write(ctx, all(ctx) - id); artFile(ctx, id).delete() }

    // ---- which songs need help ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun skipped(ctx: Context): Set<Long> = prefs(ctx).getStringSet("fix_skipped", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun skip(ctx: Context, id: Long, on: Boolean) {
        val s = skipped(ctx).let { if (on) it + id else it - id }
        prefs(ctx).edit().putStringSet("fix_skipped", s.map { it.toString() }.toSet()).apply()
    }

    private fun fileName(song: Song) = song.path.substringAfterLast('/').substringBeforeLast('.')
    private fun folder(song: Song) = song.path.substringBeforeLast('/', "").substringAfterLast('/')

    /** What's missing, in plain words. Artwork is checked separately (it needs reading the file). */
    fun problems(song: Song): List<String> {
        val out = ArrayList<String>()
        val name = fileName(song)
        if (song.title == "Unknown song" || (name.isNotEmpty() && song.title == name && ('_' in name || Regex("""^\d+""").containsMatchIn(name) || " - " in name))
            || Regex("""(?i)^(track|audio|unknown)[\s_-]*\d*$""").matches(song.title)) out += "No proper title"
        if (song.artist == "Unknown artist") out += "No artist"
        if (song.album == "Unknown album" || (folder(song).isNotEmpty() && song.album == folder(song))) out += "No album"
        return out
    }

    /** What to search for: the title and artist if we have them, otherwise the file name, tidied up. */
    fun guess(song: Song): Pair<String, String> {
        val p = problems(song)
        if ("No proper title" !in p && "No artist" !in p) return song.title to song.artist
        var n = (if ("No proper title" in p) fileName(song) else song.title).replace('_', ' ')
        n = n.replace(Regex("""^\s*\d{1,3}\s*[-.)]?\s*"""), "") // track numbers at the start
        n = n.replace(Regex("""(?i)\((official|lyric|audio|video|hd|hq|music video)[^)]*\)|\[[^]]*]"""), "").trim()
        if (" - " in n) {
            val (a, t) = n.split(" - ", limit = 2)
            return t.trim() to (if ("No artist" in p) a.trim() else song.artist)
        }
        return n to (if ("No artist" in p) "" else song.artist)
    }

    // ---- online lookup ----
    private var lastCall = 0L

    private suspend fun get(url: String): String? {
        // MusicBrainz asks for at most one request a second
        val wait = 1100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", AGENT); c.setRequestProperty("Accept", "application/json")
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            }.getOrNull()
        }
    }

    private fun esc(s: String) = s.replace(Regex("""([+\-&|!(){}\[\]^"~*?:\\/])"""), "\\\\$1")

    /** Up to 5 possible matches, best first. */
    suspend fun search(song: Song, title: String, artist: String): List<Match> {
        if (title.isBlank()) return emptyList()
        // Test 248 (Jarrod got "No match found"): try the exact name first, then without "ft. …"/"(Remix)" bits,
        // then a fuzzy search that forgives small typos ("Womam" finds "Woman").
        val clean = cleanTitle(title)
        val cleanArtist = artist.replace(Regex("""(?i)\s+(ft\.?|feat\.?|featuring|&|x|,)\s+.*$"""), "").trim()
        val tries = LinkedHashSet<String>()
        tries += exact(title, artist)
        if (clean.isNotBlank()) tries += exact(clean, cleanArtist)
        fuzzy(clean.ifBlank { title }, cleanArtist)?.let { tries += it }
        if (cleanArtist.isNotBlank()) fuzzy(clean.ifBlank { title }, "")?.let { tries += it } // artist may be wrong
        for (q in tries) {
            val found = searchQuery(song, q)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun exact(title: String, artist: String) = buildString {
        append("recording:\"").append(esc(title)).append('"')
        if (artist.isNotBlank()) append(" AND artist:\"").append(esc(artist)).append('"')
    }

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).filter { it.isNotBlank() }

    /** Every word may be off by a letter or two (Lucene fuzzy search). */
    private fun fuzzy(title: String, artist: String): String? {
        val tw = words(title).ifEmpty { return null }
        return buildString {
            append("recording:(").append(tw.joinToString(" AND ") { w -> esc(w) + if (w.length >= 4) "~" else "" }).append(')')
            val aw = words(artist)
            if (aw.isNotEmpty()) append(" AND artist:(").append(aw.joinToString(" AND ") { w -> esc(w) + if (w.length >= 4) "~" else "" }).append(')')
        }
    }

    /** Drops "ft. Someone", "(Remix)", "[Live]" and similar from a title. */
    internal fun cleanTitle(t: String): String = t
        .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        .replace(Regex("""(?i)\s+(ft\.?|feat\.?|featuring)\s+.*$"""), "")
        .replace(Regex("""(?i)\b(remix|remaster(ed)?|radio edit|extended mix|live|explicit|clean)\b"""), " ")
        .replace(Regex("""\s+-\s*$"""), "")
        .replace(Regex("""\s+"""), " ").trim()

    private suspend fun searchQuery(song: Song, q: String): List<Match> {
        val body = get("https://musicbrainz.org/ws/2/recording?fmt=json&limit=10&query=" + URLEncoder.encode(q, "UTF-8"))
            ?: return emptyList()
        val recs = runCatching { JSONObject(body).getJSONArray("recordings") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val credit = r.optJSONArray("artist-credit")?.let { a -> (0 until a.length()).joinToString("") { k -> a.getJSONObject(k).let { it.optString("name") + it.optString("joinphrase") } } } ?: ""
            val rel = bestRelease(r.optJSONArray("releases")) ?: continue
            out += Match(
                title = r.optString("title"), artist = credit,
                album = rel.optString("title"), year = rel.optString("date").take(4).toIntOrNull() ?: 0,
                releaseId = rel.optString("id"), groupId = rel.optJSONObject("release-group")?.optString("id") ?: "",
                score = r.optInt("score"), lengthMs = r.optLong("length", 0),
            )
        }
        // closest length first among the high scores
        return out.distinctBy { it.title.lowercase() + it.artist.lowercase() + it.album.lowercase() }
            .sortedWith(compareByDescending<Match> { it.score / 10 }.thenBy { if (it.lengthMs > 0) abs(it.lengthMs - song.durationMs) else Long.MAX_VALUE })
            .take(5)
    }

    /** Prefers the official studio album, then the earliest release. */
    private fun bestRelease(a: JSONArray?): JSONObject? {
        if (a == null || a.length() == 0) return null
        val list = (0 until a.length()).map { a.getJSONObject(it) }
        return list.sortedWith(compareBy<JSONObject>(
            { if (it.optString("status") == "Official") 0 else 1 },
            { val rg = it.optJSONObject("release-group"); if (rg?.optString("primary-type") == "Album" && rg.optJSONArray("secondary-types")?.length() ?: 0 == 0) 0 else 1 },
            { it.optString("date").ifBlank { "9999" } },
        )).first()
    }

    /** Downloads the front cover (500 px) into Music's own folder. */
    suspend fun fetchCover(ctx: Context, id: Long, m: Match): String? = withContext(Dispatchers.IO) {
        val urls = listOfNotNull(
            m.releaseId.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-500" },
            m.groupId.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-500" },
        )
        for (u in urls) {
            val bytes = runCatching {
                var url = URL(u)
                var result: ByteArray? = null
                repeat(4) { // follow redirects to the image host
                    if (result != null) return@repeat
                    val c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 10_000; c.readTimeout = 20_000
                    c.setRequestProperty("User-Agent", AGENT)
                    try {
                        when (c.responseCode) {
                            200 -> result = c.inputStream.readBytes()
                            301, 302, 303, 307, 308 -> url = URL(url, c.getHeaderField("Location"))
                            else -> return@runCatching null
                        }
                    } finally { c.disconnect() }
                }
                result
            }.getOrNull() ?: continue
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth > 0) {
                val f = File(ctx.filesDir, "music/art/$id.part").apply { parentFile?.mkdirs() }
                f.writeBytes(bytes)
                return@withContext f.absolutePath
            }
        }
        null
    }

    /** Makes a downloaded cover the song's artwork. */
    /** Your own picture as the song's artwork (test 254): squared from the middle, max 800 px, saved as JPEG. */
    fun ownCover(ctx: Context, id: Long, uri: android.net.Uri): String? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 800) sample *= 2 // big phone photos: decode smaller
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val side = minOf(src.width, src.height)
        val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = if (side > 800) android.graphics.Bitmap.createScaledBitmap(sq, 800, 800, true) else sq
        val dest = artFile(ctx, id).apply { parentFile?.mkdirs() }
        dest.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        dest.absolutePath
    }.getOrNull()

    fun keepCover(ctx: Context, id: Long, partPath: String): String {
        val dest = artFile(ctx, id)
        File(partPath).renameTo(dest)
        return dest.absolutePath
    }
}
