// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Open Waves test 305: genres and mood words for each artist, from MusicBrainz (artists are tagged far more often than
 * single songs). One lookup per artist, saved straight away, so it carries on where it stopped.
 */
object ArtistTags {
    @Volatile private var map: ConcurrentHashMap<String, String>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "music/artist_tags.json")

    fun all(ctx: Context): Map<String, String> {
        map?.let { return it }
        val m = ConcurrentHashMap<String, String>()
        runCatching { val j = JSONObject(file(ctx).readText()); j.keys().forEach { m[it] = j.getString(it) } }
        return m.also { map = it }
    }
    private fun save(ctx: Context) {
        val j = JSONObject(); (map ?: return).forEach { (k, v) -> j.put(k, v) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
    }
    fun clear(ctx: Context) { (all(ctx) as ConcurrentHashMap).clear(); file(ctx).delete() }

    fun key(artist: String) = AlbumRules.artistKey(artist)
    fun of(ctx: Context, artist: String): String = all(ctx)[key(artist)] ?: ""

    /** Artists of these songs not looked up yet. */
    fun todo(ctx: Context, songs: List<Song>): List<String> {
        val have = all(ctx)
        return songs.filter { it.artist != "Unknown artist" }.map { AlbumRules.mainArtist(it.artist) }
            .filter { it.isNotBlank() }.distinctBy { key(it) }.filter { key(it) !in have }
    }

    /** Looks one artist up. False when MusicBrainz couldn't be reached (try again later). */
    suspend fun lookUp(ctx: Context, artist: String): Boolean {
        val q = "artist:\"" + artist.replace("\"", "") + "\""
        val body = SongFixer.get("https://musicbrainz.org/ws/2/artist?fmt=json&limit=5&query=" + URLEncoder.encode(q, "UTF-8"))
            ?: return !SongFixer.lastFailed
        val want = key(artist)
        val tags = runCatching {
            val a = JSONObject(body).getJSONArray("artists")
            val hit = (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optInt("score") >= 85 && key(it.optString("name")) == want }
            val votes = HashMap<String, Int>()
            hit?.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) t.optJSONObject(i)?.let { o -> votes.merge(o.optString("name").lowercase(), maxOf(1, o.optInt("count")), Int::plus) } }
            votes.entries.sortedByDescending { it.value }.take(10).joinToString(",") { it.key }
        }.getOrDefault("")
        (all(ctx) as ConcurrentHashMap)[want] = tags
        save(ctx)
        return true
    }
}
