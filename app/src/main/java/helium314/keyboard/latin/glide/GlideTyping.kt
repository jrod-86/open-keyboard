// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryCollection
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.utils.Log
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * Open Keyboard's own swipe (glide) typing decoder, written from scratch so the app needs no closed
 * gesture library. The finger path is compared with the ideal path through each candidate word's
 * keys (an in-order alignment plus a whole-shape distance), and that is combined with how common the
 * word is in the main dictionary.
 */
object GlideTyping {
    private const val TAG = "GlideTyping"
    private const val N = 48 // resampled points per path
    private const val SIGMA = 0.25f
    private const val PRIOR = 6f
    private const val MAX_RESULTS = 12

    private class Entry(val word: String, val path: CharArray, val freq: Int)
    private class Index(val buckets: HashMap<Int, ArrayList<Entry>>)

    @Volatile private var indexed: Dictionary? = null
    @Volatile private var index: Index? = null
    @Volatile private var loading: Dictionary? = null
    private val executor = Executors.newSingleThreadExecutor()

    private fun bucketKey(a: Char, b: Char) = (a.code shl 16) or b.code

    fun baseLetters(word: String): String {
        val lower = word.lowercase()
        val sb = StringBuilder(lower.length)
        for (c in Normalizer.normalize(lower, Normalizer.Form.NFD)) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c == '\'' || c == '-' || c == '’') continue
            if (sb.isNotEmpty() && sb.last() == c) continue // a double letter is one key on the path
            sb.append(c)
        }
        return sb.toString()
    }

    /** Builds the word index for this main dictionary in the background, if not done yet. */
    fun prepare(mainDict: Dictionary?) {
        if (mainDict == null || mainDict === indexed || mainDict === loading) return
        loading = mainDict
        executor.execute {
            try {
                val buckets = HashMap<Int, ArrayList<Entry>>()
                val block = try { Settings.getValues().mBlockPotentiallyOffensive } catch (_: Throwable) { true }
                val dicts = if (mainDict is DictionaryCollection) mainDict.dictionaries else listOf(mainDict)
                val seen = HashSet<String>()
                for (d in dicts) {
                    if (d !is ReadOnlyBinaryDictionary) continue
                    d.forEachWord { word, prob, offensive ->
                        if (prob < 1 || (offensive && block) || word.length < 2) return@forEachWord
                        if (word.uppercase() == word && word.length > 1) return@forEachWord // acronyms
                        if (word.any { it.isDigit() || it.isWhitespace() }) return@forEachWord
                        if (!seen.add(word)) return@forEachWord
                        val path = baseLetters(word)
                        if (path.length < 2) return@forEachWord
                        buckets.getOrPut(bucketKey(path.first(), path.last())) { ArrayList() }
                            .add(Entry(word, path.toCharArray(), prob))
                    }
                }
                index = Index(buckets)
                indexed = mainDict
                Log.i(TAG, "indexed ${seen.size} words")
            } catch (e: Throwable) {
                Log.w(TAG, "could not index dictionary", e)
            } finally {
                if (loading === mainDict) loading = null
            }
        }
    }

    private class Geo(val keyW: Float, val keyH: Float) {
        fun d(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx) / keyW, (ay - by) / keyH)
    }

    private fun length(xs: FloatArray, ys: FloatArray, n: Int, g: Geo): Float {
        var l = 0f
        for (i in 1 until n) l += g.d(xs[i - 1], ys[i - 1], xs[i], ys[i])
        return l
    }

    /** Resamples a polyline to N evenly spaced points (in key units). */
    private fun resample(xs: FloatArray, ys: FloatArray, n: Int, g: Geo, outX: FloatArray, outY: FloatArray) {
        val total = length(xs, ys, n, g)
        if (n == 1 || total <= 0f) {
            outX.fill(xs[0]); outY.fill(ys[0]); return
        }
        val step = total / (N - 1)
        outX[0] = xs[0]; outY[0] = ys[0]
        var count = 1
        var acc = 0f
        var px = xs[0]; var py = ys[0]
        var i = 1
        while (i < n && count < N) {
            val d = g.d(px, py, xs[i], ys[i])
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d
                px += t * (xs[i] - px); py += t * (ys[i] - py)
                outX[count] = px; outY[count] = py
                count++
                acc = 0f
            } else {
                acc += d
                px = xs[i]; py = ys[i]
                i++
            }
        }
        while (count < N) { outX[count] = xs[n - 1]; outY[count] = ys[n - 1]; count++ }
    }

    fun decode(pointers: InputPointers, keyboard: Keyboard, sourceDict: Dictionary): List<SuggestedWordInfo> {
        prepare(sourceDict)
        val idx = if (indexed === sourceDict) index else null
        if (idx == null) return emptyList()
        val size = pointers.pointerSize
        if (size < 2) return emptyList()

        // key centres for letter keys
        val keyX = HashMap<Char, Float>()
        val keyY = HashMap<Char, Float>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            if (code <= 0 || !Character.isLetter(code)) continue
            val c = baseLetters(String(Character.toChars(code))).firstOrNull() ?: continue
            if (keyX.containsKey(c)) continue
            keyX[c] = key.x + key.width / 2f
            keyY[c] = key.y + key.height / 2f
        }
        if (keyX.isEmpty()) return emptyList()
        val geo = Geo(max(1, keyboard.mMostCommonKeyWidth).toFloat(), max(1, keyboard.mMostCommonKeyHeight).toFloat())

        val rawX = FloatArray(size) { pointers.xCoordinates[it].toFloat() }
        val rawY = FloatArray(size) { pointers.yCoordinates[it].toFloat() }
        val gx = FloatArray(N); val gy = FloatArray(N)
        resample(rawX, rawY, size, geo, gx, gy)
        val gLen = length(gx, gy, N, geo)
        if (gLen < 0.6f) return emptyList()

        val starts = keyX.keys.filter { geo.d(keyX[it]!!, keyY[it]!!, gx[0], gy[0]) < 1.1f }
        val ends = keyX.keys.filter { geo.d(keyX[it]!!, keyY[it]!!, gx[N - 1], gy[N - 1]) < 1.1f }

        val px = FloatArray(32); val py = FloatArray(32)
        val ix = FloatArray(N); val iy = FloatArray(N)
        val prev = FloatArray(N); val cur = FloatArray(N)
        val results = ArrayList<Pair<Float, Entry>>()

        for (a in starts) for (b in ends) {
            val bucket = idx.buckets[bucketKey(a, b)] ?: continue
            entries@ for (e in bucket) {
                val m = e.path.size
                if (m > px.size) continue
                for (k in 0 until m) {
                    px[k] = keyX[e.path[k]] ?: continue@entries
                    py[k] = keyY[e.path[k]]!!
                }
                val iLen = length(px, py, m, geo)
                if (iLen < 0.5f || gLen < 0.5f * iLen || gLen > 2.5f * iLen + 1f) continue

                // in-order alignment of the word's letters onto the finger path
                prev[0] = geo.d(px[0], py[0], gx[0], gy[0])
                for (j in 1 until N) prev[j] = Float.MAX_VALUE
                for (k in 1 until m) {
                    var best = Float.MAX_VALUE
                    for (j in 0 until N) {
                        if (prev[j] < best) best = prev[j]
                        cur[j] = if (best == Float.MAX_VALUE) best else best + geo.d(px[k], py[k], gx[j], gy[j])
                    }
                    System.arraycopy(cur, 0, prev, 0, N)
                }
                val align = prev[N - 1] / m
                if (align > 0.9f) continue

                // whole-shape distance
                resample(px, py, m, geo, ix, iy)
                var loc = 0f
                for (j in 0 until N) loc += geo.d(ix[j], iy[j], gx[j], gy[j])
                loc /= N

                val cost = 0.6f * align + 0.4f * loc
                val score = -(cost * cost) / (2 * SIGMA * SIGMA) + PRIOR * e.freq / 255f
                results.add(score to e)
            }
        }
        results.sortByDescending { it.first }
        return results.take(MAX_RESULTS).map { (score, e) ->
            SuggestedWordInfo(e.word, "", (1_000_000 + score * 100_000).toInt(),
                SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }
}
