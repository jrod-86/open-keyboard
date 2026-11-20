// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Test 248 (Jarrod): find a song by how it sounds. The first 2 minutes are decoded, turned into a Chromaprint
 * fingerprint on the phone, and looked up on AcoustID (free, open, linked to MusicBrainz). Only the fingerprint
 * and the song's length are sent, never the audio.
 */
object AcoustId {
    /** Open Waves' AcoustID app key (free, from acoustid.org/new-application). Empty = fingerprints off. */
    private const val APP_KEY = ""
    private const val AGENT = "OpenKeyboard-Music/1.0 ( https://github.com/jrod-86/open-keyboard )"

    fun key(ctx: Context): String =
        ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getString("acoustid_key", null)?.takeIf { it.isNotBlank() } ?: APP_KEY
    fun setKey(ctx: Context, key: String) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("acoustid_key", key.trim()).apply()
    fun available(ctx: Context) = key(ctx).isNotBlank()
    fun usable(ctx: Context) = available(ctx) && !keyRejected

    sealed class Result {
        class Found(val matches: List<Match>) : Result()
        data object NotInDatabase : Result()
        class Failed(val why: String) : Result()
    }

    private var lastCall = 0L
    /** AcoustID said the key is wrong (test 253), so the Fix page offers to change it. */
    var keyRejected by androidx.compose.runtime.mutableStateOf(false)

    suspend fun lookUp(ctx: Context, song: Song): Result {
        // decode off the main thread: reading 2 minutes of audio takes seconds (test 250, froze the app in 248/249)
        val pcm = withContext(Dispatchers.IO) { RingtoneMaker.decodeRange(ctx, song.uri, 0, 120_000) } ?: return Result.Failed("Couldn't read the song")
        val fp = withContext(Dispatchers.Default) {
            Chromaprint.encode(Chromaprint.raw(Chromaprint.toMono(pcm.data, pcm.channels), pcm.rate))
        }
        val secs = ((if (song.durationMs > 0) song.durationMs else pcm.data.size / pcm.channels * 1000L / pcm.rate) / 1000).coerceAtLeast(1)
        // AcoustID allows 3 requests a second
        val wait = 350 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        val form = "client=" + URLEncoder.encode(key(ctx), "UTF-8") + "&format=json&meta=recordings+releasegroups+releases" +
            "&duration=$secs&fingerprint=" + URLEncoder.encode(fp, "UTF-8")
        val body = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.acoustid.org/v2/lookup").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", AGENT)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
                try { (if (c.responseCode == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText() } finally { c.disconnect() }
            }.getOrNull()
        } ?: return Result.Failed("Couldn't reach AcoustID")
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return Result.Failed("AcoustID sent something odd")
        if (json.optString("status") != "ok") {
            val err = json.optJSONObject("error")
            // code 4 = invalid API key (often the account's user key pasted instead of the app key)
            if (err?.optInt("code") == 4 || err?.optString("message")?.contains("key", true) == true) keyRejected = true
            return Result.Failed(if (keyRejected) "AcoustID key didn't work" else err?.optString("message") ?: "AcoustID said no")
        }
        val matches = parse(json.optJSONArray("results"))
        return if (matches.isEmpty()) Result.NotInDatabase else Result.Found(matches)
    }

    private fun artists(a: JSONArray?): String = a?.let { arr ->
        (0 until arr.length()).joinToString("") { k -> arr.getJSONObject(k).let { it.optString("name") + it.optString("joinphrase", if (k < arr.length() - 1) ", " else "") } }
    } ?: ""

    internal fun parse(results: JSONArray?): List<Match> {
        if (results == null) return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val score = (r.optDouble("score", 0.0) * 100).toInt()
            if (score < 50) continue
            val recs = r.optJSONArray("recordings") ?: continue
            for (j in 0 until recs.length()) {
                val rec = recs.getJSONObject(j)
                val title = rec.optString("title").takeIf { it.isNotBlank() } ?: continue
                val artist = artists(rec.optJSONArray("artists"))
                val lengthMs = (rec.optDouble("duration", 0.0) * 1000).toLong()
                // albums: release groups (with their releases), best kind first
                val groups = rec.optJSONArray("releasegroups")
                val picks = ArrayList<Pair<JSONObject, JSONObject?>>() // group, first release
                if (groups != null) for (g in 0 until groups.length()) {
                    val grp = groups.getJSONObject(g)
                    picks += grp to grp.optJSONArray("releases")?.let { rs -> earliest(rs) }
                }
                picks.sortBy { (g, _) ->
                    val secondary = g.optJSONArray("secondarytypes")?.length() ?: 0
                    when (g.optString("type")) { "Album" -> 0; "EP" -> 1; "Single" -> 2; else -> 3 } * 2 + if (secondary > 0) 1 else 0
                }
                val best = picks.firstOrNull()
                val rel = best?.second
                out += Match(
                    title = title,
                    artist = artist.ifBlank { best?.first?.let { artists(it.optJSONArray("artists")) } ?: "" },
                    album = best?.first?.optString("title") ?: rel?.optString("title") ?: "",
                    year = rel?.optJSONObject("date")?.optInt("year", 0) ?: 0,
                    releaseId = rel?.optString("id") ?: "",
                    groupId = best?.first?.optString("id") ?: "",
                    score = score, lengthMs = lengthMs,
                )
            }
        }
        return out.distinctBy { it.title.lowercase() + it.artist.lowercase() + it.album.lowercase() }.take(5)
    }

    private fun earliest(rs: JSONArray): JSONObject? {
        var best: JSONObject? = null; var bestKey = Int.MAX_VALUE
        for (k in 0 until rs.length()) {
            val r = rs.getJSONObject(k)
            val d = r.optJSONObject("date")
            val key = d?.let { it.optInt("year", 9999) * 10000 + it.optInt("month", 12) * 100 + it.optInt("day", 31) } ?: 99999999
            if (key < bestKey) { bestKey = key; best = r }
        }
        return best
    }
}
