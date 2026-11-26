// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One lyric line. [timeMs] is -1 for plain (not timed) lyrics. */
data class LyricLine(val timeMs: Long, val text: String, val marks: List<Pair<Int, Long>> = emptyList()) {
    /**
     * Test 308 (Jarrod: karaoke): how much of the line has been sung at [posMs], in characters (can be fractional).
     * Uses the word times when the lyrics have them (enhanced LRC); otherwise spreads the line over its time, letters
     * weighing more than spaces, and caps that time so a long gap after the line doesn't make it crawl.
     */
    fun sungChars(posMs: Long, nextMs: Long): Float {
        if (timeMs < 0 || text.isEmpty()) return 0f
        if (marks.size >= 2) {
            if (posMs <= marks.first().second) return marks.first().first.toFloat()
            // the last word ends at the next line, or after a normal word's length
            val (lc, lt) = marks.last()
            val end = (text.length - lc) * 90L + 300
            val all = if (lc < text.length) marks + (text.length to (if (nextMs > lt) minOf(nextMs - 100, lt + end) else lt + end)) else marks
            for (k in 0 until all.size - 1) {
                val (c0, t0) = all[k]; val (c1, t1) = all[k + 1]
                if (posMs < t1) return c0 + (c1 - c0) * ((posMs - t0).toFloat() / maxOf(1L, t1 - t0))
            }
            return text.length.toFloat()
        }
        val weights = text.map { if (it == ' ') 0.4f else 1f }
        val total = weights.sum()
        val natural = (total * 95f + 600f).toLong()
        val dur = minOf(if (nextMs > timeMs) nextMs - timeMs - 150 else natural, natural).coerceAtLeast(400)
        var want = ((posMs - timeMs).toFloat() / dur).coerceIn(0f, 1f) * total
        for ((i, w) in weights.withIndex()) { if (want <= w) return i + want / w; want -= w }
        return text.length.toFloat()
    }
}

data class Lyrics(val lines: List<LyricLine>, val source: String) {
    val synced: Boolean get() = lines.isNotEmpty() && lines.first().timeMs >= 0

    /** Index of the line being sung at [posMs], or -1 before the first line. */
    fun lineAt(posMs: Long): Int {
        if (!synced) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= posMs + 250) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val metaTag = Regex("""^\[(ar|ti|al|by|offset|length|re|ve|au|#):.*]$""", RegexOption.IGNORE_CASE)

        /** Reads LRC (timed) lyrics, or plain text when there are no time tags. */
        fun parse(text: String, source: String): Lyrics? {
            val timed = ArrayList<LyricLine>()
            val plain = ArrayList<LyricLine>()
            var offset = 0L
            text.replace("\r", "").lines().forEach { raw ->
                val line = raw.trim()
                Regex("""^\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0 }
                if (metaTag.matches(line)) return@forEach
                val tags = timeTag.findAll(line).toList()
                if (tags.isNotEmpty() && line.startsWith("[")) {
                    val rest = line.substring(tags.last().range.last + 1)
                    // word times (<mm:ss.xx> before each word) are kept as character positions for the karaoke sweep
                    val sb = StringBuilder(); val marks = ArrayList<Pair<Int, Long>>()
                    var at = 0
                    for (w in wordTag.findAll(rest)) {
                        sb.append(rest, at, w.range.first); at = w.range.last + 1
                        val g = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""").find(w.value)!!.groupValues
                        val fr = g[3]; val fms = when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() }
                        marks += sb.length to (g[1].toLong() * 60_000 + g[2].toLong() * 1000 + fms - offset)
                    }
                    sb.append(rest.substring(at))
                    val lead = sb.length - sb.trimStart().length
                    val words = sb.toString().trim()
                    val wordMarks = marks.map { (c, t) -> (c - lead).coerceIn(0, words.length) to t }
                    tags.forEach { m ->
                        val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                        val frac = m.groupValues[3]
                        val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                        val start = (min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0)
                        // a repeated line ([00:10][01:30]words) gets its word times moved along with it
                        val shift = if (wordMarks.isNotEmpty()) start - (tags.first().let { f ->
                            val fr = f.groupValues[3]
                            f.groupValues[1].toLong() * 60_000 + f.groupValues[2].toLong() * 1000 +
                                when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() } - offset
                        }.coerceAtLeast(0)) else 0L
                        timed += LyricLine(start, words, wordMarks.map { (c, t) -> c to t + shift })
                    }
                } else plain += LyricLine(-1, line)
            }
            if (timed.size >= 2) return Lyrics(timed.sortedBy { it.timeMs }, source)
            val p = plain.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
            return if (p.isEmpty()) null else Lyrics(p, source)
        }

        /** Lyrics stored inside the music file (MP3 USLT / SYLT-less, FLAC and OGG LYRICS). */
        fun fromMetadata(list: List<Metadata>): String? {
            for (md in list) for (i in 0 until md.length()) {
                when (val e = md[i]) {
                    is VorbisComment -> if (e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true)) return e.value
                    is BinaryFrame -> if (e.id == "USLT") decodeUslt(e.data)?.let { return it }
                    is TextInformationFrame -> if (e.id == "USLT" || e.id.contains("lyr", true)) return e.values.joinToString("\n")
                    else -> {}
                }
            }
            return null
        }

        private fun decodeUslt(d: ByteArray): String? {
            if (d.size < 5) return null
            val enc = d[0].toInt()
            val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
            // skip 3 language bytes, then the description which ends with a 0 (or 00 for UTF-16)
            var i = 4
            val wide = enc == 1 || enc == 2
            if (wide) { while (i + 1 < d.size && !(d[i] == 0.toByte() && d[i + 1] == 0.toByte())) i += 2; i += 2 }
            else { while (i < d.size && d[i] != 0.toByte()) i++; i += 1 }
            if (i >= d.size) return null
            return String(d, i, d.size - i, cs).trim('\u0000', ' ', '\n').takeIf { it.isNotBlank() }
        }

        private fun cacheFile(ctx: Context, song: Song) = File(ctx.filesDir, "music/lyrics/${song.id}.lrc")

        /** Lyrics we already have: saved earlier, or a .lrc file next to the song. */
        suspend fun local(ctx: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
            cacheFile(ctx, song).takeIf { it.exists() }?.readText()?.let { parse(it, "Saved")?.let { l -> return@withContext l } }
            if (song.path.isNotEmpty()) {
                val lrc = File(song.path.substringBeforeLast('.') + ".lrc")
                runCatching { if (lrc.canRead()) parse(lrc.readText(), "Lyrics file") else null }.getOrNull()?.let { return@withContext it }
            }
            null
        }

        fun save(ctx: Context, song: Song, text: String) {
            runCatching { cacheFile(ctx, song).apply { parentFile?.mkdirs() }.writeText(text) }
        }

        /**
         * Looks the song up on LRCLIB (free, open lyrics site). Only runs when you tap "Find lyrics online".
         * Sends only the song name, artist, album and length.
         */
        /** Test 308: LRCLIB often has a timed copy of a song under a slightly different name or album; its search finds it. */
        private fun searchSynced(song: Song): String? {
            fun q(s: String) = URLEncoder.encode(s, "UTF-8")
            val body = runCatching {
                val c = URL("https://lrclib.net/api/search?track_name=${q(song.title)}&artist_name=${q(song.artist)}").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "OpenKeyboard-Music/1.0")
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            }.getOrNull() ?: return null
            val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return null
            val secs = song.durationMs / 1000.0
            var best: String? = null; var bestGap = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val gap = if (secs > 0 && o.has("duration")) kotlin.math.abs(o.optDouble("duration", secs) - secs) else 0.0
                if (gap < bestGap && gap <= 8) { best = synced; bestGap = gap }
            }
            return best
        }

        /** Timed lyrics for a song that only has plain ones. */
        suspend fun findTimed(ctx: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
            val text = searchSynced(song) ?: return@withContext null
            save(ctx, song, text)
            parse(text, "LRCLIB")
        }

        suspend fun online(ctx: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
            fun q(s: String) = URLEncoder.encode(s, "UTF-8")
            val urls = listOf(
                "https://lrclib.net/api/get?track_name=${q(song.title)}&artist_name=${q(song.artist)}&album_name=${q(song.album)}&duration=${song.durationMs / 1000}",
                "https://lrclib.net/api/get?track_name=${q(song.title)}&artist_name=${q(song.artist)}",
            )
            for (u in urls) {
                val body = runCatching {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 10000
                    c.setRequestProperty("User-Agent", "OpenKeyboard-Music/1.0")
                    try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
                }.getOrNull() ?: continue
                val j = runCatching { JSONObject(body) }.getOrNull() ?: continue
                val text = j.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                    ?: searchSynced(song)
                    ?: j.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: continue
                save(ctx, song, text)
                return@withContext parse(text, "LRCLIB")
            }
            null
        }
    }
}
