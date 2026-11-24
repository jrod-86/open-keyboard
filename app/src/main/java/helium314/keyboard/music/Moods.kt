// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/** How a song sounds, measured on the phone (test 299). */
data class Feel(
    val bpm: Float,      // tempo
    val lufs: Float,     // how loud it was made (loud = usually energetic)
    val density: Float,  // beats a second
    val steady: Float,   // 0..1, how regular the beat is (dance-ability)
    val bright: Float,   // share of treble: sharp/bright vs mellow
    val bass: Float,     // share of bass
    val dyn: Float,      // how much the loudness moves around
)

/** A song placed on the mood map: every value 0..1, compared with the rest of your library. */
class Placed(val song: Song, val energy: Float, val dance: Float, val bright: Float, val tempo: Float, val happy: Float, val group: String,
    /** test 305: how well the AI listener thinks the song fits each mood (0..1 within your library, by mood id), null if not listened yet */
    val ai: Map<String, Float>? = null,
    /** test 305: genre and mood words from MusicBrainz (artist and song) */
    val tags: String = "")

/** One mood tile. Targets are 0..1 on the mood map; a negative number means "don't care". */
class Mood(
    val id: String, val emoji: String, val name: String, val color: Long,
    val energy: Float, val dance: Float = -1f, val happy: Float = -1f, val tempo: Float = -1f, val bright: Float = -1f,
    val likes: Set<String> = emptySet(), val avoids: Set<String> = emptySet(),
    /** test 305: when not empty, only songs of these genres (songs with no known genre still count) */
    val only: Set<String> = emptySet(),
    /** test 305: tag words that mean a song suits this mood */
    val words: Set<String> = emptySet(),
)

/**
 * Open Waves test 299 (Jarrod): Moods. Every song is listened to once on the phone (tempo, energy, brightness, how steady
 * the beat is), genres come from the music files and from MusicBrainz, and each mood picks the songs that fit,
 * in an order that flows. Nothing is made up: the 4B writing model only turns "how are you feeling" into mood settings.
 */
object Moods {
    val all = listOf(
        Mood("relax", "😌", "Relax", 0xFF5B8DEF, energy = 0.2f, dance = 0.35f, bright = 0.35f, likes = setOf("Ambient", "Folk", "Jazz", "Indie", "R&B"),
            avoids = setOf("Metal", "Punk", "Dance"), words = setOf("chill", "mellow", "relax", "calm", "soft", "chillout", "easy listening", "laid-back", "acoustic")),
        Mood("sleep", "🌙", "Sleep", 0xFF3B3F8F, energy = 0.05f, dance = 0.15f, tempo = 0.15f, bright = 0.2f, likes = setOf("Ambient", "Classical"),
            avoids = setOf("Metal", "Punk", "Dance", "Hip-hop", "Rock", "Latin", "Reggae"), words = setOf("sleep", "ambient", "lullaby", "calm", "peaceful", "meditation", "piano", "drone")),
        Mood("focus", "🎯", "Focus", 0xFF2E9E8F, energy = 0.35f, dance = 0.45f, likes = setOf("Classical", "Ambient", "Electronic", "Jazz", "Soundtrack"),
            avoids = setOf("Metal", "Punk"), words = setOf("instrumental", "study", "lo-fi", "lofi", "downtempo", "post-rock", "minimal", "ambient", "soundtrack")),
        Mood("happy", "☀️", "Happy", 0xFFF2A93B, energy = 0.65f, happy = 0.9f, dance = 0.65f, likes = setOf("Pop", "Reggae", "Funk & Soul", "Country", "Latin"),
            avoids = setOf("Metal", "Blues"), words = setOf("happy", "uplifting", "feel good", "cheerful", "fun", "summer", "sunshine", "upbeat")),
        Mood("workout", "💪", "Workout", 0xFFE0533D, energy = 0.92f, dance = 0.8f, tempo = 0.85f, likes = setOf("Hip-hop", "Dance", "Rock", "Electronic", "Metal"),
            avoids = setOf("Classical", "Ambient", "Folk", "Jazz", "Blues"), words = setOf("energetic", "workout", "aggressive", "hard", "edm", "trap", "pump")),
        Mood("party", "🎉", "Party", 0xFFD9459B, energy = 0.82f, dance = 0.95f, happy = 0.75f, likes = setOf("Dance", "Pop", "Hip-hop", "Latin", "Electronic"),
            avoids = setOf("Classical", "Ambient", "Folk", "Metal", "Blues", "Soundtrack"), words = setOf("party", "dance", "club", "disco", "house", "edm", "danceable")),
        Mood("rainy", "🌧️", "Rainy day", 0xFF5E7390, energy = 0.3f, happy = 0.2f, bright = 0.3f, likes = setOf("Indie", "Folk", "Jazz", "Blues", "R&B"),
            avoids = setOf("Dance", "Metal", "Latin", "Reggae"), words = setOf("sad", "melancholic", "melancholy", "depressing", "emotional", "heartbreak", "moody", "dark", "lonely")),
        Mood("romance", "❤️", "Romance", 0xFFC2416B, energy = 0.32f, dance = 0.45f, happy = 0.5f, likes = setOf("R&B", "Funk & Soul", "Jazz", "Pop"),
            avoids = setOf("Metal", "Punk", "Hip-hop"), words = setOf("romantic", "love", "sensual", "sexy", "ballad", "slow jam", "soul")),
        Mood("driving", "🚗", "Driving", 0xFF3A8F4E, energy = 0.7f, dance = 0.6f, tempo = 0.6f, likes = setOf("Rock", "Pop", "Country", "Electronic", "Indie"),
            avoids = setOf("Classical", "Ambient"), words = setOf("driving", "road", "classic rock", "upbeat", "anthem", "arena rock")),
        Mood("heavy", "🤘", "Heavy", 0xFF4A4A4A, energy = 0.97f, bright = 0.85f, likes = setOf("Metal", "Rock", "Punk"), only = setOf("Metal", "Rock", "Punk"),
            words = setOf("metal", "heavy", "hard rock", "aggressive", "thrash", "hardcore", "grunge", "screamo")),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** The mood to suggest right now (time of day). */
    fun suggestion(hour: Int): Pair<String, Mood> = when (hour) {
        in 5..9 -> "☀️ Wake up" to byId("happy")!!
        in 10..16 -> "🎯 Get things done" to byId("focus")!!
        in 17..20 -> "😌 Unwind" to byId("relax")!!
        else -> "🌙 Wind down" to byId("sleep")!!
    }

    // ---- genres: messy tags tidied into a few groups ----
    val groups = listOf("Rock", "Metal", "Punk", "Pop", "Hip-hop", "Dance", "Electronic", "R&B", "Funk & Soul", "Jazz", "Blues",
        "Country", "Folk", "Indie", "Classical", "Ambient", "Soundtrack", "Reggae", "Latin", "World")
    private val rules = listOf(
        "Metal" to listOf("metal", "metalcore", "djent", "grindcore", "thrash", "doom"),
        "Punk" to listOf("punk", "hardcore", "emo", "grunge"),
        "Hip-hop" to listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime"),
        "Dance" to listOf("dance", "house", "edm", "techno", "trance", "disco", "dubstep", "drum and bass", "dnb", "garage", "eurodance"),
        "Electronic" to listOf("electronic", "electro", "synth", "idm", "downtempo", "trip hop", "chillout", "lo-fi", "lofi"),
        "R&B" to listOf("r&b", "rnb", "r and b", "rhythm and blues", "neo soul"),
        "Funk & Soul" to listOf("soul", "funk", "motown", "gospel"),
        "Jazz" to listOf("jazz", "swing", "bebop", "bossa"),
        "Blues" to listOf("blues"),
        "Country" to listOf("country", "bluegrass", "americana"),
        "Folk" to listOf("folk", "singer-songwriter", "acoustic"),
        "Indie" to listOf("indie", "alternative", "shoegaze", "dream pop"),
        "Classical" to listOf("classical", "orchestra", "symphony", "baroque", "opera", "piano", "chamber"),
        "Ambient" to listOf("ambient", "new age", "meditation", "relax", "sleep", "drone"),
        "Soundtrack" to listOf("soundtrack", "score", "film", "musical", "video game"),
        "Reggae" to listOf("reggae", "ska", "dancehall", "dub"),
        "Latin" to listOf("latin", "reggaeton", "salsa", "bachata", "cumbia", "samba", "k-pop", "kpop"),
        "Rock" to listOf("rock", "britpop", "new wave", "post-punk"),
        "Pop" to listOf("pop", "teen", "boy band", "girl group"),
        "World" to listOf("world", "afro", "celtic", "khmer", "bollywood", "arabic"),
    )
    /** "alternative rock, female vocalists" -> "Indie". The first rule that matches the first tag wins. */
    fun group(tags: String): String {
        if (tags.isBlank()) return ""
        for (tag in tags.lowercase().split(',', ';', '/')) {
            val t = tag.trim()
            if (t.isEmpty()) continue
            rules.firstOrNull { (_, words) -> words.any { w -> t == w || t.contains(w) } }?.let { return it.first }
        }
        return ""
    }

    // ---- what each song sounds like, saved in files/music/feel.json ----
    @Volatile private var feels: ConcurrentHashMap<Long, Feel>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "music/feel.json")

    fun feels(ctx: Context): Map<Long, Feel> {
        feels?.let { return it }
        val m = ConcurrentHashMap<Long, Feel>()
        runCatching {
            val j = JSONObject(file(ctx).readText())
            j.keys().forEach { k ->
                val a = j.getJSONArray(k)
                m[k.toLong()] = Feel(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(),
                    a.getDouble(4).toFloat(), a.getDouble(5).toFloat(), a.getDouble(6).toFloat())
            }
        }
        return m.also { feels = it }
    }

    // test 310 (Jarrod: the AI listener was slow): writing the whole file after every song got slow with thousands of
    // songs, so it's written at most every 10 seconds, and once more when the scan stops ([flush])
    @Volatile private var lastSave = 0L
    @Volatile private var unsaved = false
    private fun save(ctx: Context) {
        unsaved = true
        if (System.currentTimeMillis() - lastSave < 10_000) return
        flush(ctx)
    }
    @Synchronized fun flush(ctx: Context) {
        if (!unsaved) return
        unsaved = false; lastSave = System.currentTimeMillis()
        val m = feels ?: return
        val j = JSONObject()
        m.forEach { (id, f) -> j.put(id.toString(), JSONArray(listOf(f.bpm, f.lufs, f.density, f.steady, f.bright, f.bass, f.dyn).map { (it * 1000).toInt() / 1000.0 })) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
    }

    fun clear(ctx: Context) { (feels(ctx) as ConcurrentHashMap).clear(); file(ctx).delete() }

    /** Listens to one song (reads the whole file) and keeps what it found. False if the file couldn't be read. */
    suspend fun measure(ctx: Context, song: Song): Boolean {
        val map = BeatMap.analyze(ctx, song, needFeel = true) ?: run {
            // unreadable file: remember it, so it isn't tried again and again
            (feels(ctx) as ConcurrentHashMap)[song.id] = Feel(Float.NaN, Float.NaN, 0f, 0f, 0f, 0f, 0f); save(ctx); return false
        }
        (feels(ctx) as ConcurrentHashMap)[song.id] = feelOf(map, song.durationMs)
        save(ctx)
        return true
    }

    internal fun feelOf(m: BeatMap, durationMs: Long): Feel {
        val secs = (durationMs / 1000f).coerceAtLeast(1f)
        // tempo: the most common gap between beats (40 ms steps), folded into 70..170 BPM
        val gaps = (1 until m.beats.size).map { (m.beats[it] - m.beats[it - 1]).toInt() }.filter { it in 250..2000 }
        var bpm = 0f; var steady = 0f
        if (gaps.size >= 8) {
            val hist = HashMap<Int, Int>()
            gaps.forEach { g -> val b = (g + 10) / 20; hist[b] = (hist[b] ?: 0) + 2; hist[b - 1] = (hist[b - 1] ?: 0) + 1; hist[b + 1] = (hist[b + 1] ?: 0) + 1 }
            val gap = hist.maxByOrNull { it.value }!!.key * 20f
            bpm = 60000f / gap
            while (bpm < 70f) bpm *= 2f
            while (bpm >= 170f) bpm /= 2f
            // how many gaps are (close to) a whole number of beats
            steady = gaps.count { g -> val r = g / gap; val n = Math.round(r).coerceAtLeast(1); abs(r - n) < 0.12f * n }.toFloat() / gaps.size
        }
        val lv = m.level
        val mean = if (lv.isEmpty()) 0f else lv.average().toFloat()
        val dyn = if (lv.isEmpty()) 0f else sqrt(lv.map { (it - mean) * (it - mean) }.average()).toFloat()
        return Feel(bpm, if (m.lufs.isNaN()) -14f else m.lufs, m.beats.size / secs, steady,
            if (m.bright.isNaN()) 0.1f else m.bright, if (m.bassShare.isNaN()) 0.3f else m.bassShare, dyn)
    }

    /** Songs still to get ready: not measured yet, or (with the AI listener downloaded) not listened to yet. */
    fun left(ctx: Context, songs: List<Song>): Int {
        val f = feels(ctx); val ai = if (Clap.ready(ctx)) Clap.scores(ctx) else null
        return songs.count { it.id !in f || (ai != null && it.id !in ai) }
    }

    // ---- genres for each song ----
    /** Genre group for each song: the file's genre, else MusicBrainz tags, else what the artist's other songs are. */
    fun genres(ctx: Context, songs: List<Song>): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (s in songs) {
            var g = group(s.genre)
            if (g.isEmpty()) g = group(ArtistTags.of(ctx, s.artist)) // test 305: the artist's genres
            if (g.isEmpty()) g = group(Popularity.known(ctx, s)?.genres ?: "")
            if (g.isNotEmpty()) out[s.id] = g
        }
        // songs with no genre get their artist's most common one
        val byArtist = songs.groupBy { AlbumRules.artistKey(it.artist) }
        for ((_, list) in byArtist) {
            val common = list.mapNotNull { out[it.id] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
            list.forEach { if (it.id !in out) out[it.id] = common }
        }
        // test 305: still nothing? the AI listener's best guess, when it's clearly one genre
        val ai = Clap.scores(ctx)
        if (ai.isNotEmpty()) {
            val names = Clap.genreNames(ctx); val moods = Clap.moodNames(ctx).size
            for (s in songs) {
                if (s.id in out) continue
                val a = ai[s.id]?.takeIf { it.size >= moods + names.size } ?: continue
                val g = names.indices.sortedByDescending { a[moods + it] }
                if (a[moods + g[0]] - a[moods + g[1]] >= 0.015f) out[s.id] = names[g[0]]
            }
        }
        return out
    }

    // ---- the mood map ----
    /** Puts every measured song on the map (0..1 compared with your own library, so it works for any collection). */
    fun place(ctx: Context, songs: List<Song>): List<Placed> {
        val f = feels(ctx)
        val have = songs.filter { f[it.id]?.bpm?.isNaN() == false }
        if (have.isEmpty()) return emptyList()
        val g = genres(ctx, have)
        fun ranks(v: (Feel) -> Float): Map<Long, Float> {
            val sorted = have.sortedBy { v(f[it.id]!!) }
            val n = (sorted.size - 1).coerceAtLeast(1).toFloat()
            return sorted.withIndex().associate { it.value.id to it.index / n }
        }
        val rLoud = ranks { it.lufs }; val rDens = ranks { it.density }; val rBright = ranks { it.bright }; val rBpm = ranks { it.bpm }
        val rSteady = ranks { it.steady }; val rBass = ranks { it.bass }; val rDyn = ranks { it.dyn }
        // test 305: the AI listener's mood scores, as 0..1 within your library. Each song's scores are taken relative to its
        // own average first, so a song that is "close to everything" doesn't win every mood.
        val clap = Clap.scores(ctx)
        val moodIds = Clap.moodNames(ctx)
        val aiRank = HashMap<Long, MutableMap<String, Float>>()
        val listened = have.filter { (clap[it.id]?.size ?: 0) >= moodIds.size }
        if (listened.size >= 10) for ((mi, mid) in moodIds.withIndex()) {
            val rel = listened.associate { s -> val a = clap[s.id]!!; s.id to (a[mi] - (0 until moodIds.size).map { a[it] }.average().toFloat()) }
            val sorted = listened.sortedBy { rel[it.id] }
            val n = (sorted.size - 1).coerceAtLeast(1).toFloat()
            sorted.forEachIndexed { i, s -> aiRank.getOrPut(s.id) { HashMap() }[mid] = i / n }
        }
        return have.map { s ->
            val id = s.id
            val group = g[id] ?: ""
            var energy = 0.38f * rLoud[id]!! + 0.24f * rDens[id]!! + 0.2f * rBright[id]!! + 0.18f * rBpm[id]!!
            var dance = 0.5f * rSteady[id]!! + 0.3f * rBass[id]!! + 0.2f * (1 - rDyn[id]!!)
            // genres nudge the sound a little where the measuring can't tell
            when (group) {
                "Metal", "Punk" -> energy = energy * 0.7f + 0.3f
                "Classical", "Ambient" -> { energy *= 0.7f; dance *= 0.6f }
                "Dance", "Hip-hop", "Latin" -> dance = dance * 0.7f + 0.3f
            }
            // "happy" is the hardest to hear; quicker, brighter, steadier songs lean happy, heavy genres don't
            var happy = 0.35f * rBpm[id]!! + 0.3f * rBright[id]!! + 0.35f * rSteady[id]!!
            if (group in setOf("Metal", "Blues")) happy *= 0.6f
            if (group in setOf("Pop", "Reggae", "Funk & Soul", "Latin")) happy = happy * 0.8f + 0.2f
            val tags = (ArtistTags.of(ctx, s.artist) + "," + (Popularity.known(ctx, s)?.genres ?: "") + "," + s.genre).lowercase()
            Placed(s, energy.coerceIn(0f, 1f), dance.coerceIn(0f, 1f), rBright[id]!!, rBpm[id]!!, happy.coerceIn(0f, 1f), group, aiRank[id], tags)
        }
    }

    // ---- songs you took out of a mood: never again in that mood, and songs that sound like them drop down ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun removed(ctx: Context, mood: String): Set<Long> =
        prefs(ctx).getStringSet("mood_out_$mood", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun remove(ctx: Context, mood: String, id: Long) {
        prefs(ctx).edit().putStringSet("mood_out_$mood", (removed(ctx, mood) + id).map { it.toString() }.toSet()).apply()
    }
    fun forgetRemoved(ctx: Context) {
        val e = prefs(ctx).edit(); all.forEach { e.remove("mood_out_${it.id}") }; e.apply()
    }

    /** What to play: the mood's targets, moved calmer or livelier by [lift] (-1..1), optionally only one genre. */
    class Ask(val mood: Mood?, val lift: Float = 0f, val genre: String = "", val popularFirst: Boolean = false, val title: String = "")

    fun mix(ctx: Context, placed: List<Placed>, ask: Ask, size: Int = 50, seed: Long = System.currentTimeMillis()): List<Song> {
        val mood = ask.mood
        var pool = placed
        if (ask.genre.isNotEmpty()) pool = pool.filter { it.group == ask.genre }
        if (mood == null) {
            // a genre on its own: smooth flow through all of it, livelier with the slider
            val target = (0.5f + ask.lift * 0.4f).coerceIn(0f, 1f)
            return flow(pool.sortedBy { abs(it.energy - target) + rand(seed, it.song.id) * 0.25f }.take(size), ask.lift)
        }
        val out = removed(ctx, mood.id)
        val outPlaced = placed.filter { it.song.id in out }
        val energy = (mood.energy + ask.lift * 0.3f).coerceIn(0f, 1f)
        fun score(p: Placed): Float {
            var d = 2.2f * sq(p.energy - energy)
            if (mood.dance >= 0) d += 1.0f * sq(p.dance - mood.dance)
            if (mood.happy >= 0) d += 0.8f * sq(p.happy - mood.happy)
            if (mood.tempo >= 0) d += 0.8f * sq(p.tempo - (mood.tempo + ask.lift * 0.2f).coerceIn(0f, 1f))
            if (mood.bright >= 0) d += 0.6f * sq(p.bright - mood.bright)
            if (p.group in mood.likes) d -= 0.06f
            // test 305: tag words like "chill" or "sad" from MusicBrainz
            if (mood.words.any { w -> p.tags.contains(w) }) d -= 0.1f
            // test 305: the AI listener counts the most when it has heard the song
            p.ai?.get(mood.id)?.let { r -> d -= 1.1f * (r - 0.5f) }
            // sounds like a song you took out of this mood
            if (outPlaced.any { o -> sq(o.energy - p.energy) + sq(o.dance - p.dance) + sq(o.bright - p.bright) < 0.004f }) d += 0.12f
            return d + rand(seed, p.song.id) * 0.04f // a little variety each time
        }
        // test 305 (Jarrod: random songs that don't fit): genre rules are firm now, the AI listener's worst fits are left out,
        // and the mix is only as long as there are songs that really fit (it used to fill up to 50 with poor ones)
        val allowed = pool.filter { p ->
            p.song.id !in out &&
                (ask.genre.isNotEmpty() || p.group.isEmpty() || (p.group !in mood.avoids && (mood.only.isEmpty() || p.group in mood.only))) &&
                (p.ai?.get(mood.id)?.let { it >= 0.35f } ?: true)
        }
        val ranked = allowed.map { it to score(it) }.sortedBy { it.second }
        val good = ranked.filter { it.second <= 0.32f }
        // test 343 (Jarrod): shuffle the matching pool with a fresh random seed every tap so each mix (e.g. 15/30/60) is genuinely different
        val rng = java.util.Random(seed)
        val candidates = (if (good.size >= 12) good else ranked.take(maxOf(12, size))).map { it.first }.shuffled(rng)
        val picked = candidates.take(size)
        if (ask.popularFirst) {
            val plays = picked.associate { it.song.id to (Popularity.known(ctx, it.song)?.plays ?: 0L) }
            return picked.sortedByDescending { plays[it.song.id] }.map { it.song }
        }
        return flow(picked, ask.lift, seed)
    }

    /** Orders the songs so each one sounds close to the one before (no ballad straight into metal), starting from a random song each time. */
    private fun flow(list: List<Placed>, lift: Float, seed: Long = System.currentTimeMillis()): List<Song> {
        if (list.size < 3) return list.map { it.song }
        val rng = java.util.Random(seed xor 0x517CC1B727220A95L)
        val left = list.toMutableList()
        var cur = left.removeAt(rng.nextInt(left.size))
        val out = arrayListOf(cur.song)
        while (left.isNotEmpty()) {
            val c = cur
            val next = left.minByOrNull {
                sq(it.energy - c.energy) * 2 + sq(it.tempo - c.tempo) + sq(it.bright - c.bright) * 0.5f +
                    (if (it.song.artist == c.song.artist) 0.08f else 0f) + rng.nextFloat() * 0.06f
            }!!
            left.remove(next); out += next.song; cur = next
        }
        return out
    }

    private fun sq(x: Float) = x * x
    private fun rand(seed: Long, id: Long): Float { var x = seed xor (id * -7046029254386353131L); x = x xor (x ushr 33); x *= -49064778989728563L; x = x xor (x ushr 29); return ((x ushr 11) and 0xFFFF).toFloat() / 65535f }

    // ---- "How are you feeling?" ----
    /** Turns what you typed into mood settings, using the writing model if it's there, otherwise simple word matching. */
    suspend fun understand(ctx: Context, text: String): Ask {
        val words = keywordAsk(text)
        val reply = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(45_000) {
                helium314.keyboard.latin.WriterClient.ask(ctx,
                    "You pick music for someone's mood. Reply with ONLY one line of JSON, nothing else: " +
                        "{\"mood\":\"<one of: ${all.joinToString(", ") { it.id }}>\",\"lift\":<number from -1 (calmer) to 1 (livelier)>," +
                        "\"genre\":\"<one of: ${groups.joinToString(", ")}, or empty>\",\"title\":\"<2 to 4 word title for the mix>\"}",
                    "How I feel: $text", 80, quickOnly = true)
            }
        }.getOrNull() ?: return words
        return runCatching {
            val j = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
            val mood = byId(j.optString("mood").lowercase().trim()) ?: words.mood
            val genre = groups.firstOrNull { it.equals(j.optString("genre").trim(), true) } ?: words.genre
            Ask(mood, j.optDouble("lift", 0.0).toFloat().coerceIn(-1f, 1f), genre, title = j.optString("title").take(40).ifBlank { words.title })
        }.getOrDefault(words)
    }

    internal fun keywordAsk(text: String): Ask {
        val t = text.lowercase()
        val hits = listOf(
            "sleep" to listOf("sleep", "bed", "insomnia", "can't sleep", "night night"),
            "relax" to listOf("tired", "relax", "chill", "calm", "stress", "unwind", "exhausted", "rest", "lazy", "peace"),
            "focus" to listOf("focus", "study", "work", "concentrat", "read", "code", "homework"),
            "happy" to listOf("happy", "good mood", "great", "sunny", "excited", "cheer", "joy"),
            "workout" to listOf("gym", "workout", "run", "exercise", "lift", "train", "pumped", "motivat"),
            "party" to listOf("party", "dance", "friends over", "drinks", "celebrat", "weekend"),
            "rainy" to listOf("sad", "down", "lonely", "rain", "miss", "heartbroken", "blue", "cry", "low"),
            "romance" to listOf("love", "romantic", "date", "wife", "husband", "partner", "dinner"),
            "driving" to listOf("drive", "driving", "road trip", "car", "commute"),
            "heavy" to listOf("angry", "mad", "rage", "heavy", "metal", "furious", "headbang"),
        ).maxByOrNull { (_, ws) -> ws.count { it in t } }
        val mood = hits?.takeIf { (_, ws) -> ws.any { it in t } }?.first?.let(::byId) ?: byId("relax")
        var lift = 0f
        if (listOf("but not sleepy", "a bit of energy", "upbeat", "livelier", "pick me up", "wake me").any { it in t }) lift += 0.4f
        if (listOf("really tired", "very tired", "soft", "quiet", "gentle", "slow").any { it in t }) lift -= 0.4f
        val genre = groups.firstOrNull { g -> Regex("\\b" + Regex.escape(g.lowercase()) + "\\b").containsMatchIn(t) } ?: ""
        return Ask(mood, lift.coerceIn(-1f, 1f), genre, title = (mood?.name ?: "Your") + " mix")
    }

    /** Gives a stable-ish number for how calm/hype a mix is, for the slider label. */
    fun liftLabel(lift: Float) = when {
        lift <= -0.6f -> "Very calm"; lift <= -0.2f -> "Calmer"; lift < 0.2f -> "Just right"; lift < 0.6f -> "Livelier"; else -> "Full hype"
    }

}
