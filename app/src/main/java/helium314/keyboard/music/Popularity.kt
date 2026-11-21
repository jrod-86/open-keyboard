// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** How popular a song is: play count from ListenBrainz, chart peaks from Wikipedia (0 = not known). */
data class Pop(val plays: Long, val listeners: Long, val us: Int, val au: Int, val uk: Int, val found: Boolean, val at: Long, val year: Int = 0) {
    /** Test 296 (Jarrod): the line under the song on Now Playing, e.g. "Reached #10 in the US and #3 in Australia (2010) · 12.3M plays". */
    fun story(): String? {
        val spots = listOfNotNull(
            us.takeIf { it > 0 }?.let { "#$it in the US" }, au.takeIf { it > 0 }?.let { "#$it in Australia" }, uk.takeIf { it > 0 }?.let { "#$it in the UK" },
        )
        val chart = if (spots.isEmpty()) null else "Reached " + when (spots.size) {
            1 -> spots[0]; 2 -> spots[0] + " and " + spots[1]; else -> spots[0] + ", " + spots[1] + " and " + spots[2]
        } + (if (year > 0) " ($year)" else "")
        val p = if (plays > 0) Popularity.plays(plays) else null
        return listOfNotNull(chart, p).joinToString(" · ").ifBlank { null }?.let { (if (chart != null) "🏆 " else "🎧 ") + it }
    }

    fun peaks(): String = listOfNotNull(
        us.takeIf { it > 0 }?.let { "US #$it" }, au.takeIf { it > 0 }?.let { "AU #$it" }, uk.takeIf { it > 0 }?.let { "UK #$it" },
    ).joinToString(" · ")
}

/**
 * Open Waves test 295 (Jarrod): "Sort by popularity" for a playlist. Each song is found on MusicBrainz (one lookup a second,
 * their rule), then ListenBrainz gives how many times it was played by everyone (all songs in one request), and Wikipedia
 * gives the best chart spot (US Billboard Hot 100, Australia ARIA, UK) where the song has a page. Results are saved for 30 days.
 * No AI is used: the facts come from those sites, so nothing is made up.
 */
object Popularity {
    private const val AGENT = "OpenKeyboard-Music/1.0 ( https://github.com/jrod-86/open-keyboard )"
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    @Volatile private var cache: MutableMap<String, Pop>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "music/popularity.json")
    fun key(s: Song) = AlbumRules.titleKey(s.title) + "|" + AlbumRules.artistKey(s.artist)

    private fun load(ctx: Context): MutableMap<String, Pop> {
        cache?.let { return it }
        val m = HashMap<String, Pop>()
        runCatching {
            val j = JSONObject(file(ctx).readText())
            j.keys().forEach { k ->
                val o = j.getJSONObject(k)
                m[k] = Pop(o.optLong("p"), o.optLong("l"), o.optInt("us"), o.optInt("au"), o.optInt("uk"), o.optBoolean("f"),
                    if (o.has("y")) o.optLong("t") else 0L /* saved by test 295, before the year: look again */, o.optInt("y"))
            }
        }
        return m.also { cache = it }
    }

    private fun save(ctx: Context) {
        val m = cache ?: return
        val j = JSONObject()
        synchronized(m) {
            m.forEach { (k, p) -> j.put(k, JSONObject().put("p", p.plays).put("l", p.listeners).put("us", p.us).put("au", p.au).put("uk", p.uk).put("f", p.found).put("t", p.at).put("y", p.year)) }
        }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
    }

    fun known(ctx: Context, s: Song): Pop? = load(ctx)[key(s)]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }

    /**
     * Looks up every song not already saved. [progress] gets (done, total) and the results so far, so the list can fill in.
     * Returns false when the music sites couldn't be reached at all.
     */
    suspend fun lookUp(ctx: Context, songs: List<Song>, progress: (Int, Int, Map<String, Pop>) -> Unit): Boolean = coroutineScope {
        val map = load(ctx)
        val todo = songs.distinctBy { key(it) }.filter { known(ctx, it) == null }
        val results = HashMap<String, Pop>()
        songs.forEach { s -> known(ctx, s)?.let { results[key(s)] = it } }
        var done = 0
        val total = todo.size
        progress(0, total, results.toMap())
        if (todo.isEmpty()) return@coroutineScope true
        var reached = false

        // chart peaks from Wikipedia, alongside (a different site, so it doesn't slow the MusicBrainz part)
        val wikiGate = Semaphore(2)
        val peaks = todo.associate { s -> key(s) to async(Dispatchers.IO) { wikiGate.withPermit { runCatching { chartPeaks(s) }.getOrNull() } } }

        // MusicBrainz ids, one song a second
        val ids = HashMap<String, List<String>>()
        for (s in todo) {
            val found = recordingIds(s)
            if (found != null) reached = true
            ids[key(s)] = found.orEmpty()
            done++
            // show the step for MusicBrainz as most of the bar; ListenBrainz is one quick request at the end
            progress(done, total + 1, results.toMap())
        }

        // play counts for all of them at once
        val counts = listenCounts(ids.values.flatten().distinct())
        if (counts != null) reached = true
        val now = System.currentTimeMillis()
        for (s in todo) {
            val k = key(s)
            val c = ids[k].orEmpty().mapNotNull { counts?.get(it) }
            val best = c.maxByOrNull { it.first }
            val pk = peaks[k]?.await()
            if (pk != null) reached = true
            val pop = Pop(best?.first ?: 0, best?.second ?: 0, pk?.get(0) ?: 0, pk?.get(1) ?: 0, pk?.get(2) ?: 0,
                found = best != null || (pk?.take(3)?.any { it > 0 } == true), at = now, year = pk?.getOrNull(3) ?: 0)
            results[k] = pop
            // nothing reached: don't save "not found", it may just be no internet
            if (counts != null) synchronized(map) { map[k] = pop }
        }
        save(ctx)
        progress(total + 1, total + 1, results.toMap())
        reached
    }

    /** The best order: most played first; songs with only a chart spot next (best spot first); songs not found last, as they were. */
    fun order(songs: List<Song>, pops: Map<String, Pop>): List<Song> {
        fun bestPeak(p: Pop) = listOf(p.us, p.au, p.uk).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE
        return songs.withIndex().sortedWith(compareBy<IndexedValue<Song>>(
            { val p = pops[key(it.value)]; when { p == null || !p.found -> 2; p.plays > 0 -> 0; else -> 1 } },
            { -(pops[key(it.value)]?.plays ?: 0) },
            { pops[key(it.value)]?.let(::bestPeak) ?: Int.MAX_VALUE },
            { it.index },
        )).map { it.value }
    }

    // ---- MusicBrainz: which recordings this song is ----
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** null = MusicBrainz couldn't be reached, empty = not found. */
    private suspend fun recordingIds(s: Song): List<String>? {
        val title = SongFixer.cleanTitle(s.title).ifBlank { s.title }
        val artist = AlbumRules.mainArtist(s.artist).takeIf { it.isNotBlank() && s.artist != "Unknown artist" } ?: ""
        val body = SongFixer.get("https://musicbrainz.org/ws/2/recording?fmt=json&limit=25&query=" +
            URLEncoder.encode(SongFixer.exact(title, artist), "UTF-8")) ?: return if (SongFixer.lastFailed) null else emptyList()
        val recs = runCatching { JSONObject(body).getJSONArray("recordings") }.getOrNull() ?: return emptyList()
        val want = norm(title)
        val wantArtist = norm(artist).removePrefix("the")
        val out = ArrayList<String>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            if (r.optInt("score") < 70) continue
            val t = norm(SongFixer.cleanTitle(r.optString("title")))
            if (t != want) continue
            val credit = r.optJSONArray("artist-credit")?.let { a -> (0 until a.length()).joinToString("") { k -> a.getJSONObject(k).optString("name") } } ?: ""
            if (wantArtist.isNotEmpty() && !norm(credit).contains(wantArtist)) continue
            // live versions and remixes are other songs as far as popularity goes
            val dis = r.optString("disambiguation").lowercase()
            if ("live" in dis || "remix" in dis || "demo" in dis) continue
            out += r.optString("id")
            if (out.size >= 10) break
        }
        return out
    }

    // ---- ListenBrainz: how many times each was played ----
    private suspend fun listenCounts(ids: List<String>): Map<String, Pair<Long, Long>>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, Pair<Long, Long>>()
        var any = false
        for (chunk in ids.chunked(400)) {
            val text = post("https://api.listenbrainz.org/1/popularity/recording", JSONObject().put("recording_mbids", JSONArray(chunk)).toString())
                ?: continue
            any = true
            runCatching {
                val t = text.trim()
                val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.optJSONArray("payload") ?: o.optJSONArray("recordings") ?: JSONArray() }
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val id = o.optString("recording_mbid")
                    val plays = if (o.isNull("total_listen_count")) 0L else o.optLong("total_listen_count")
                    val people = if (o.isNull("total_user_count")) 0L else o.optLong("total_user_count")
                    if (id.isNotBlank() && plays > 0) out[id] = plays to people
                }
            }
        }
        if (any) out else null
    }

    private fun post(url: String, body: String): String? {
        repeat(3) { attempt ->
            val r = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", AGENT); c.setRequestProperty("Content-Type", "application/json")
                try {
                    c.outputStream.use { it.write(body.toByteArray()) }
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
                } finally { c.disconnect() }
            }.getOrNull()
            if (r != null) return r
            Thread.sleep(1000L * (attempt + 1))
        }
        return null
    }

    private fun getText(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", AGENT)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    // ---- Wikipedia: best chart spots ----
    /** [US, AU, UK] best positions (0 = not on that chart or not known), or null when no page for the song was found. */
    private fun chartPeaks(s: Song): IntArray? {
        val title = SongFixer.cleanTitle(s.title).ifBlank { s.title }
        val artist = AlbumRules.mainArtist(s.artist).takeIf { s.artist != "Unknown artist" } ?: ""
        val q = "\"$title\" $artist song"
        val text = getText("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrlimit=4" +
            "&prop=revisions&rvprop=content&rvslots=main&gsrsearch=" + URLEncoder.encode(q, "UTF-8")) ?: return null
        val pages = runCatching { JSONObject(text).getJSONObject("query").getJSONArray("pages") }.getOrNull() ?: return null
        val want = norm(title)
        val wantArtist = norm(artist).removePrefix("the")
        val list = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }
        for (p in list) {
            val pageTitle = norm(p.optString("title").substringBefore(" ("))
            if (pageTitle != want) continue
            val wt = p.optJSONArray("revisions")?.optJSONObject(0)?.optJSONObject("slots")?.optJSONObject("main")?.optString("content") ?: continue
            if (!Regex("(?i)\\{\\{\\s*infobox (song|single)").containsMatchIn(wt)) continue
            if (wantArtist.isNotEmpty() && !norm(wt.take(4000)).contains(wantArtist)) continue
            return peaksIn(wt) + intArrayOf(releaseYear(wt))
        }
        return null
    }

    /** The year from the song box ("| released = {{start date|2010|5|3}}" or "| released = 3 May 2010"). */
    internal fun releaseYear(wt: String): Int {
        val line = Regex("(?im)^\\s*\\|\\s*released\\s*=(.*)$").find(wt)?.groupValues?.get(1) ?: return 0
        return Regex("\\b(19[0-9]{2}|20[0-9]{2})\\b").find(line)?.value?.toInt() ?: 0
    }

    internal fun peaksIn(wt: String): IntArray {
        val best = intArrayOf(0, 0, 0)
        fun put(i: Int, n: Int) { if (n in 1..200 && (best[i] == 0 || n < best[i])) best[i] = n }
        // most pages: {{single chart|Billboardhot100|3|artist=…}}
        Regex("(?i)\\{\\{\\s*single ?chart\\s*\\|\\s*([^|}]+?)\\s*\\|\\s*(\\d{1,3})\\b").findAll(wt).forEach { m ->
            val chart = m.groupValues[1].lowercase().replace(" ", "")
            val n = m.groupValues[2].toInt()
            when {
                chart == "billboardhot100" || chart == "billboard100" || chart == "us" -> put(0, n)
                chart == "australia" || chart == "aria" -> put(1, n)
                chart == "uk" || chart == "uk2" || chart == "uksinglesbyname" || chart == "uksinglesbyartist" -> put(2, n)
            }
        }
        // older pages: a table row naming the chart, with the spot on the same or the next line
        val lines = wt.lines()
        for ((i, line) in lines.withIndex()) {
            if (!line.trimStart().startsWith("|") && !line.trimStart().startsWith("!")) continue
            val which = when {
                Regex("(?i)billboard\\]*'*\\s*hot 100|\\bUS\\b.*hot 100").containsMatchIn(line) && !Regex("(?i)year-end|airplay|adult|r&b|dance|rock|latin|country").containsMatchIn(line) -> 0
                Regex("(?i)australia.*aria|aria.*singles").containsMatchIn(line) -> 1
                Regex("(?i)uk singles|official charts company").containsMatchIn(line) && !Regex("(?i)download|indie|r&b|dance|rock").containsMatchIn(line) -> 2
                else -> -1
            }
            if (which < 0) continue
            val sameLine = Regex("\\|\\|\\s*(\\d{1,3})\\s*$").find(line)?.groupValues?.get(1)
            val next = lines.getOrNull(i + 1)?.let { Regex("^\\s*\\|\\s*(?:[^|]*\\|)?\\s*(\\d{1,3})\\s*(<ref|\\{\\{|$)").find(it)?.groupValues?.get(1) }
            (sameLine ?: next)?.toIntOrNull()?.let { put(which, it) }
        }
        return best
    }

    /** "12.3M plays" style. */
    fun plays(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM plays", n / 1e6)
        n >= 10_000 -> "${n / 1000}K plays"
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK plays", n / 1e3)
        else -> "$n plays"
    }
}
