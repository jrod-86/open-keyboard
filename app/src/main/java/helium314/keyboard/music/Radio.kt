// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Test 311 (Jarrod, "radio from this song"): an endless run of songs from your own library that sound like the one you
 * picked. Uses what the AI listener heard (mood and genre scores), how each song feels (tempo, energy, brightness, bass)
 * and its genre. Keeps topping the queue up as it plays, drifting slowly from song to song like a radio station.
 */
object Radio {
    /** the song the radio started from, while it's on */
    val seed = mutableStateOf<Song?>(null)
    @Volatile var on = false; private set
    @Volatile private var busy = false
    private val played = java.util.Collections.synchronizedSet(HashSet<Long>())

    private class Stats(val mean: FloatArray, val sd: FloatArray)
    private var aiStats: Stats? = null; private var aiKey = -1
    private var feelStats: Stats? = null; private var feelKey = -1

    private fun stats(vectors: Collection<FloatArray>, dims: Int): Stats {
        val mean = FloatArray(dims); val sd = FloatArray(dims); var n = 0
        for (v in vectors) { if (v.size < dims) continue; n++; for (i in 0 until dims) mean[i] += v[i] }
        if (n == 0) return Stats(mean, FloatArray(dims) { 1f })
        for (i in 0 until dims) mean[i] /= n
        for (v in vectors) { if (v.size < dims) continue; for (i in 0 until dims) { val d = v[i] - mean[i]; sd[i] += d * d } }
        for (i in 0 until dims) sd[i] = sqrt(sd[i] / n).coerceAtLeast(1e-4f)
        return Stats(mean, sd)
    }

    private fun feelVec(f: Feel) = floatArrayOf(f.bpm, f.lufs, f.bright, f.bass, f.steady, f.density)

    /** The songs that sound most like [from], best first, leaving out [skip]. */
    fun similar(ctx: Context, lib: Library, from: Song, n: Int, skip: Set<Long>): List<Song> {
        val ai = Clap.scores(ctx)
        val feels = Moods.feels(ctx)
        val songs = lib.uniqueSongs
        val genres = Moods.genres(ctx, songs)
        val aiDims = ai.values.firstOrNull { it.isNotEmpty() }?.size ?: 0
        if (aiDims > 0 && (aiStats == null || aiKey != ai.size)) { aiStats = stats(ai.values, aiDims); aiKey = ai.size }
        if (feelStats == null || feelKey != feels.size) { feelStats = stats(feels.values.filter { !it.bpm.isNaN() }.map(::feelVec), 6); feelKey = feels.size }
        fun z(v: FloatArray, s: Stats) = FloatArray(v.size) { (v[it] - s.mean[it]) / s.sd[it] }
        val seedAi = ai[from.id]?.takeIf { it.size == aiDims && aiDims > 0 }?.let { z(it, aiStats!!) }
        val seedFeel = feels[from.id]?.takeIf { !it.bpm.isNaN() }?.let { z(feelVec(it), feelStats!!) }
        val seedGenre = genres[from.id]
        val seedArtist = AlbumRules.artistKey(from.artist)
        val rnd = java.util.Random()
        val scored = ArrayList<Pair<Song, Double>>()
        for (s in songs) {
            if (s.id == from.id || s.id in skip || s.id in played) continue
            var score = 0.0; var parts = 0
            if (seedAi != null) ai[s.id]?.takeIf { it.size == aiDims }?.let { v ->
                val zv = z(v, aiStats!!); var d = 0.0
                for (i in zv.indices) { val x = zv[i] - seedAi[i]; d += x * x }
                score -= sqrt(d / aiDims) * 1.6; parts++
            }
            if (seedFeel != null) feels[s.id]?.takeIf { !it.bpm.isNaN() }?.let { f ->
                val zv = z(feelVec(f), feelStats!!); var d = 0.0
                for (i in zv.indices) { val x = zv[i] - seedFeel[i]; d += x * x }
                score -= sqrt(d / 6) * 1.0; parts++
            }
            val g = genres[s.id]
            if (seedGenre != null && g != null) score += if (g == seedGenre) 0.9 else -0.6
            if (AlbumRules.artistKey(s.artist) == seedArtist) score += 0.35
            if (parts == 0 && (seedGenre == null || g != seedGenre)) score -= 2.0 // nothing known: only same-genre songs are safe
            score += rnd.nextDouble() * 0.35 // a little variety, so each radio is different
            scored += s to score
        }
        // best first, but no more than 2 by the same artist in a row of picks
        val out = ArrayList<Song>(); val perArtist = HashMap<String, Int>()
        for ((s, _) in scored.sortedByDescending { it.second }) {
            val a = AlbumRules.artistKey(s.artist)
            if ((perArtist[a] ?: 0) >= 2) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out += s
            if (out.size >= n) break
        }
        return out
    }

    private val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun start(ctx: Context, ui: PlayerUi, lib: Library, from: Song) {
        Toast.makeText(ctx, "📻 Radio from “${from.title}”", Toast.LENGTH_SHORT).show()
        work.launch {
            played.clear()
            val list = listOf(from) + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { similar(ctx, lib, from, 24, emptySet()) }
            ui.play(list, 0, false)
            on = true; seed.value = from
            played.addAll(list.map { it.id })
        }
    }

    fun stop() { on = false; seed.value = null }

    /** Called by the player when a song starts: keeps at least 5 songs waiting, picked from the song playing now. */
    fun topUp(ctx: Context, player: androidx.media3.common.Player) {
        if (!on) return
        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (left >= 5) return
        val lib = Library.cached ?: return
        val cur = player.currentMediaItem?.mediaId?.toLongOrNull()?.let { lib.byId[it] } ?: return
        val inQueue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }.toSet()
        if (busy) return
        busy = true
        work.launch {
            val more = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { similar(ctx, lib, cur, 10, inQueue) }.getOrDefault(emptyList()) }
            busy = false
            if (more.isEmpty() || !on) return@launch
            played.addAll(more.map { it.id })
            player.addMediaItems(more.map { it.toMediaItem() })
        }
    }
}
