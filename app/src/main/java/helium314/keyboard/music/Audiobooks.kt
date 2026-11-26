// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.util.Xml
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Open Waves test 371 (Jarrod): Free open-source Audiobooks tab powered by LibriVox (20,000+ public-domain
 * audiobooks read by human volunteers, zero API key) + local Audiobooks folder support, full chapter playlists,
 * offline chapter downloads, and exact auto-resume per book & chapter.
 */
data class Audiobook(
    val id: String,
    val title: String,
    val author: String = "",
    val rssUrl: String = "",
    val totalTime: String = "",
    val numSections: Int = 0,
    val description: String = "",
    val isLocal: Boolean = false,
)

data class BookChapter(
    val index: Int,
    val title: String,
    val audioUrl: String,
    val durationMs: Long = 0L,
    val localSong: Song? = null,
)

object AudiobookStore {
    val saved = mutableStateListOf<Audiobook>()
    val downloadProgress = mutableStateMapOf<String, Float>()
    @Volatile private var loadedSaved = false
    private val listCache = HashMap<String, List<Audiobook>>()
    private val chapterCache = HashMap<String, List<BookChapter>>()

    val presets = listOf(
        "SAVED" to "📚 My Books",
        "TOP" to "🔥 Featured",
        "Mystery Fiction" to "🔎 Mystery",
        "Science Fiction" to "🚀 Sci-Fi",
        "Action & Adventure Fiction" to "⚔️ Adventure",
        "History" to "📜 History",
        "Philosophy" to "🧠 Philosophy",
        "Horror & Supernatural Fiction" to "👻 Horror",
        "LOCAL" to "📂 On Phone",
    )

    private fun savedFile(ctx: Context) = File(ctx.filesDir, "music/audiobooks_saved.json")
    private fun dlDir(ctx: Context) = File(ctx.filesDir, "music/book_dl")
    private fun posPrefs(ctx: Context) = ctx.getSharedPreferences("music_audiobooks", Context.MODE_PRIVATE)

    fun loadSaved(ctx: Context) {
        if (loadedSaved) return
        loadedSaved = true
        runCatching {
            val f = savedFile(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val out = ArrayList<Audiobook>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val id = j.optString("id")
                if (id.isNotBlank()) {
                    out += Audiobook(
                        id = id,
                        title = j.optString("title", "Audiobook"),
                        author = j.optString("author", ""),
                        rssUrl = j.optString("rssUrl", ""),
                        totalTime = j.optString("totalTime", ""),
                        numSections = j.optInt("numSections", 0),
                        description = j.optString("description", ""),
                    )
                }
            }
            saved.clear()
            saved.addAll(out)
        }
    }

    private fun persistSaved(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            saved.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id); put("title", b.title); put("author", b.author)
                    put("rssUrl", b.rssUrl); put("totalTime", b.totalTime)
                    put("numSections", b.numSections); put("description", b.description)
                })
            }
            savedFile(ctx).apply { parentFile?.mkdirs() }.writeText(arr.toString())
        }
    }

    fun isSaved(book: Audiobook) = saved.any { it.id == book.id }

    fun toggleSaved(ctx: Context, book: Audiobook) {
        val idx = saved.indexOfFirst { it.id == book.id }
        if (idx >= 0) saved.removeAt(idx) else saved.add(0, book)
        persistSaved(ctx)
    }

    // ---- per-book & chapter resume positions ----

    fun savePos(ctx: Context, bookId: String, chapterIdx: Int, posMs: Long, durMs: Long) {
        if (bookId.isBlank() || posMs < 0) return
        val save = if (durMs > 30_000L && posMs >= durMs - 10_000L) 0L else posMs
        posPrefs(ctx).edit()
            .putInt("ch_$bookId", chapterIdx)
            .putLong("pos_$bookId", save)
            .apply()
    }

    /** Returns Pair(chapterIndex, positionMs), or null if not started yet. */
    fun resumeOf(ctx: Context, bookId: String): Pair<Int, Long>? {
        val p = posPrefs(ctx)
        if (!p.contains("ch_$bookId")) return null
        val ch = p.getInt("ch_$bookId", 0)
        val pos = p.getLong("pos_$bookId", 0L)
        return if (ch > 0 || pos > 5_000L) ch to pos else null
    }

    // ---- offline chapter downloads ----

    private fun dlKey(bookId: String, chIdx: Int) = "${bookId}_$chIdx"

    fun downloadedFile(ctx: Context, bookId: String, chIdx: Int): File? {
        val f = File(dlDir(ctx), "${dlKey(bookId, chIdx)}.mp3")
        return if (f.exists() && f.length() > 16_384L) f else null
    }

    fun deleteDownload(ctx: Context, bookId: String, chIdx: Int) {
        File(dlDir(ctx), "${dlKey(bookId, chIdx)}.mp3").delete()
        File(dlDir(ctx), "${dlKey(bookId, chIdx)}.part").delete()
    }

    suspend fun downloadChapter(ctx: Context, bookId: String, ch: BookChapter): Result<File> = withContext(Dispatchers.IO) {
        val key = dlKey(bookId, ch.index)
        val dir = dlDir(ctx).apply { mkdirs() }
        val target = File(dir, "$key.mp3")
        val part = File(dir, "$key.part")
        withContext(Dispatchers.Main) { downloadProgress[key] = 0f }
        try {
            val conn = openFollowRedirects(ch.audioUrl, 15_000, 30_000)
            val total = conn.contentLengthLong
            var done = 0L; var lastUi = 0L
            conn.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 15)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 150) {
                            lastUi = now
                            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 0.99f) else 0.5f
                            withContext(Dispatchers.Main) { downloadProgress[key] = p }
                        }
                    }
                }
            }
            conn.disconnect()
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("Could not save chapter")
            withContext(Dispatchers.Main) { downloadProgress.remove(key) }
            Result.success(target)
        } catch (e: Throwable) {
            part.delete()
            withContext(Dispatchers.Main) { downloadProgress.remove(key) }
            Result.failure(e)
        }
    }

    // ---- LibriVox free public API ----

    suspend fun fetchPreset(genre: String): Result<List<Audiobook>> = withContext(Dispatchers.IO) {
        listCache[genre]?.let { return@withContext Result.success(it) }
        val url = if (genre == "TOP") {
            "https://librivox.org/api/feed/audiobooks/?format=json&limit=35"
        } else {
            val g = URLEncoder.encode(genre, "UTF-8")
            "https://librivox.org/api/feed/audiobooks/?genre=$g&format=json&limit=35"
        }
        fetchBooks(url).onSuccess { listCache[genre] = it }
    }

    suspend fun search(query: String): Result<List<Audiobook>> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("^${query.trim()}", "UTF-8")
        val byTitle = fetchBooks("https://librivox.org/api/feed/audiobooks/?title=$q&format=json&limit=30").getOrNull().orEmpty()
        if (byTitle.size >= 8) return@withContext Result.success(byTitle)
        val qa = URLEncoder.encode(query.trim(), "UTF-8")
        val byAuthor = fetchBooks("https://librivox.org/api/feed/audiobooks/?author=$qa&format=json&limit=30").getOrNull().orEmpty()
        val combined = (byTitle + byAuthor).distinctBy { it.id }
        Result.success(combined)
    }

    private fun fetchBooks(url: String): Result<List<Audiobook>> = runCatching {
        val body = httpGet(url)
        val arr = JSONObject(body).optJSONArray("books") ?: JSONArray()
        val out = ArrayList<Audiobook>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val id = j.optString("id").trim()
            val title = stripHtml(j.optString("title", "")).trim()
            val rss = j.optString("url_rss").trim()
            if (id.isBlank() || title.isBlank() || rss.isBlank()) continue
            val authorsArr = j.optJSONArray("authors")
            val author = buildList {
                if (authorsArr != null) for (a in 0 until authorsArr.length()) {
                    val aj = authorsArr.getJSONObject(a)
                    val full = "${aj.optString("first_name")} ${aj.optString("last_name")}".trim()
                    if (full.isNotBlank()) add(full)
                }
            }.joinToString(", ")
            out += Audiobook(
                id = id,
                title = title,
                author = author,
                rssUrl = rss,
                totalTime = j.optString("totaltime", "").trim(),
                numSections = j.optInt("num_sections", 0),
                description = stripHtml(j.optString("description", "")).take(320),
            )
        }
        out
    }

    suspend fun fetchChapters(book: Audiobook, forceRefresh: Boolean = false): Result<List<BookChapter>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) chapterCache[book.id]?.let { return@withContext Result.success(it) }
        runCatching {
            val xml = httpGet(book.rssUrl)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xml))
            }
            var inItem = false
            var title = ""; var audio = ""; var dur = 0L
            val chapters = ArrayList<BookChapter>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        when {
                            tag == "item" -> { inItem = true; title = ""; audio = ""; dur = 0L }
                            inItem && tag == "enclosure" -> {
                                val u = parser.getAttributeValue(null, "url").orEmpty().trim()
                                if (u.isNotBlank()) audio = u
                            }
                            inItem && tag == "title" -> title = runCatching { parser.nextText().trim() }.getOrDefault("")
                            inItem && tag == "itunes:duration" -> dur = parseDurationMs(runCatching { parser.nextText().trim() }.getOrDefault(""))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", true) && inItem) {
                            inItem = false
                            if (audio.isNotBlank()) {
                                chapters += BookChapter(
                                    index = chapters.size,
                                    title = title.ifBlank { "Chapter ${chapters.size + 1}" },
                                    audioUrl = audio,
                                    durationMs = dur,
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
            chapterCache[book.id] = chapters
            chapters
        }
    }

    /** Finds any audiobooks stored locally on the phone (e.g. inside an Audiobooks folder or .m4b files). */
    fun localBooks(lib: Library): List<Pair<Audiobook, List<BookChapter>>> {
        val candidates = lib.songs.filter { s ->
            val f = s.folder.lowercase()
            f.contains("audiobook") || f.contains("books") || s.genre.contains("audiobook", true)
        }
        if (candidates.isEmpty()) return emptyList()
        return candidates.groupBy { it.album.ifBlank { it.folder } }.map { (name, tracks) ->
            val sorted = tracks.sortedWith(compareBy<Song> { if (it.track > 0) it.track else Int.MAX_VALUE }.thenBy { it.title.lowercase() })
            val totalMin = sorted.sumOf { it.durationMs } / 60_000L
            val book = Audiobook(
                id = "local_${name.hashCode()}",
                title = name,
                author = sorted.firstOrNull()?.artist.orEmpty(),
                totalTime = if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m",
                numSections = sorted.size,
                isLocal = true,
            )
            val chapters = sorted.mapIndexed { idx, s ->
                BookChapter(index = idx, title = s.title, audioUrl = s.uri.toString(), durationMs = s.durationMs, localSong = s)
            }
            book to chapters
        }.sortedBy { it.first.title.lowercase() }
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun parseDurationMs(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        t.toLongOrNull()?.let { return it * 1000L }
        val p = t.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (p.size) {
            3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000L
            2 -> (p[0] * 60 + p[1]) * 1000L
            else -> 0L
        }
    }

    private fun openFollowRedirects(rawUrl: String, connectMs: Int, readMs: Int): HttpURLConnection {
        var url = URL(rawUrl)
        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectMs; readTimeout = readMs
                setRequestProperty("User-Agent", "OpenWaves/1.0 (OpenKeyboard; GPL-3.0)")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 6) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Empty redirect")
                url = URL(url, loc)
                continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
            return conn
        }
    }

    private fun httpGet(rawUrl: String): String {
        val conn = openFollowRedirects(rawUrl, 10_000, 15_000)
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }
}

private fun formatBookTime(ms: Long): String {
    if (ms <= 0L) return ""
    val s = ms / 1000L
    val h = s / 3600L; val m = (s % 3600L) / 60L; val sec = s % 60L
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}

@Composable
internal fun AudiobooksTab(lib: Library, ui: PlayerUi) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { AudiobookStore.loadSaved(ctx) }

    var openBook by remember { mutableStateOf<Pair<Audiobook, List<BookChapter>?>?>(null) }
    var preset by remember { mutableStateOf(if (AudiobookStore.saved.isNotEmpty()) "SAVED" else "TOP") }
    var query by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Audiobook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val local = remember(lib) { AudiobookStore.localBooks(lib) }

    val viewing = openBook
    if (viewing != null) {
        AudiobookDetail(viewing.first, viewing.second, ui) { openBook = null }
        return
    }

    fun loadBooks(p: String, q: String) {
        if (q.isBlank() && (p == "SAVED" || p == "LOCAL")) {
            loading = false; error = null
            books = if (p == "SAVED") AudiobookStore.saved.toList() else local.map { it.first }
            return
        }
        loading = true; error = null
        scope.launch {
            val res = if (q.isNotBlank()) AudiobookStore.search(q) else AudiobookStore.fetchPreset(p)
            res.onSuccess { books = it; loading = false }
                .onFailure { error = "Couldn't load audiobooks (${it.message ?: "check internet"})"; loading = false }
        }
    }

    LaunchedEffect(preset, activeSearch) { loadBooks(preset, activeSearch) }

    val shown = when {
        activeSearch.isBlank() && preset == "SAVED" -> AudiobookStore.saved.toList()
        activeSearch.isBlank() && preset == "LOCAL" -> local.map { it.first }
        else -> books
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "book_search") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isBlank()) activeSearch = "" },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search 20,000+ free LibriVox books…", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { activeSearch = query.trim() }),
                    trailingIcon = { if (query.isNotEmpty()) TextButton({ query = ""; activeSearch = "" }) { Text("✕", color = dim) } },
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { if (query.isNotBlank()) activeSearch = query.trim() },
                    enabled = query.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) { Text("Go", color = Accent, fontSize = 13.sp) }
            }
        }

        item(key = "book_chips") {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(AudiobookStore.presets) { (id, label) ->
                    FilterChip(
                        selected = activeSearch.isBlank() && preset == id,
                        onClick = { activeSearch = ""; query = ""; preset = id },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)),
                    )
                }
            }
        }

        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Browsing LibriVox library…", color = dim, fontSize = 14.sp)
                }
            }
        } else if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = dim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ loadBooks(preset, activeSearch) }) { Text("Try again", color = Accent) }
                }
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    when {
                        preset == "SAVED" && activeSearch.isBlank() -> "No saved books yet. Tap 🔥 Featured or pick a genre above to start!"
                        preset == "LOCAL" && activeSearch.isBlank() -> "No local audiobooks found. Put audiobook files in an 'Audiobooks' folder on your phone to see them here."
                        else -> "No audiobooks found for “$activeSearch”."
                    },
                    Modifier.padding(24.dp), color = dim, fontSize = 14.sp,
                )
            }
        } else {
            items(shown, key = { it.id }) { b ->
                val isSaved = AudiobookStore.isSaved(b)
                val resume = AudiobookStore.resumeOf(ctx, b.id)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val localCh = if (b.isLocal) local.firstOrNull { it.first.id == b.id }?.second else null
                            openBook = b to localCh
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("📖", fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            b.author.takeIf { it.isNotBlank() },
                            b.totalTime.takeIf { it.isNotBlank() },
                            if (b.numSections > 0) "${b.numSections} ch" else null,
                            resume?.let { (ch, pos) -> "Ch ${ch + 1} · ${formatBookTime(pos).ifBlank { "start" }}" },
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = if (resume != null) Accent.copy(alpha = 0.9f) else dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!b.isLocal && isSaved) Text("★", color = Accent, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AudiobookDetail(book: Audiobook, localChapters: List<BookChapter>?, ui: PlayerUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scope = rememberCoroutineScope()
    var chapters by remember(book.id) { mutableStateOf(localChapters.orEmpty()) }
    var loading by remember(book.id) { mutableStateOf(localChapters == null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var dlVersion by remember { mutableStateOf(0) }
    var downloadingAll by remember { mutableStateOf(false) }

    fun load(force: Boolean = false) {
        if (localChapters != null) return
        loading = true; error = null
        scope.launch {
            AudiobookStore.fetchChapters(book, forceRefresh = force)
                .onSuccess { chapters = it; loading = false }
                .onFailure { error = "Couldn't load chapters (${it.message ?: "check internet"})"; loading = false }
        }
    }
    LaunchedEffect(book.id) { load() }

    val saved = AudiobookStore.isSaved(book)
    val resume = AudiobookStore.resumeOf(ctx, book.id)
    val playingMediaId = ui.current?.mediaId ?: ""

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("← Back", color = Accent, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                if (!book.isLocal) {
                    TextButton({ AudiobookStore.toggleSaved(ctx, book) }) {
                        Text(if (saved) "★ In My Books" else "☆ Save Book", color = Accent, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val info = listOfNotNull(
                    book.author.takeIf { it.isNotBlank() },
                    book.totalTime.takeIf { it.isNotBlank() },
                    if (chapters.isNotEmpty()) "${chapters.size} chapters" else null,
                ).joinToString(" · ")
                if (info.isNotBlank()) Text(info, fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 2.dp))
                if (book.description.isNotBlank()) {
                    Text(book.description, fontSize = 12.sp, color = dim, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                if (chapters.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val startCh = resume?.first?.coerceIn(0, chapters.lastIndex) ?: 0
                                val startMs = resume?.second ?: 0L
                                ui.playAudiobook(ctx, book, chapters, startCh, startMs)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) {
                            Text(if (resume != null) "▶ Resume Ch ${resume.first + 1}" else "▶ Listen")
                        }
                        if (!book.isLocal) {
                            OutlinedButton(
                                enabled = !downloadingAll,
                                onClick = {
                                    downloadingAll = true
                                    scope.launch {
                                        for (ch in chapters) {
                                            if (AudiobookStore.downloadedFile(ctx, book.id, ch.index) == null) {
                                                AudiobookStore.downloadChapter(ctx, book.id, ch)
                                                dlVersion++
                                            }
                                        }
                                        downloadingAll = false
                                    }
                                },
                            ) { Text(if (downloadingAll) "Downloading…" else "⬇️ Download all", color = Accent, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading chapters…", color = dim, fontSize = 14.sp)
                }
            }
        } else if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = dim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ load(force = true) }) { Text("Try again", color = Accent) }
                }
            }
        } else {
            itemsIndexed(chapters, key = { _, ch -> "${book.id}_${ch.index}_$dlVersion" }) { idx, ch ->
                val isPlayingThis = playingMediaId == "book:${book.id}:$idx"
                val key = "${book.id}_$idx"
                val dlProg = AudiobookStore.downloadProgress[key]
                val offline = if (book.isLocal) null else AudiobookStore.downloadedFile(ctx, book.id, idx)

                Column(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val startMs = if (resume != null && resume.first == idx) resume.second else 0L
                            ui.playAudiobook(ctx, book, chapters, idx, startMs)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${idx + 1}", Modifier.width(28.dp), fontSize = 13.sp, color = if (isPlayingThis) Accent else dim, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.title,
                                fontSize = 14.sp,
                                fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPlayingThis) Accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(
                                formatBookTime(ch.durationMs).takeIf { it.isNotBlank() },
                                if (offline != null) "✓ Offline" else if (book.isLocal) "On phone" else null,
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = if (offline != null) Accent.copy(alpha = 0.85f) else dim)
                        }
                        if (!book.isLocal) {
                            if (dlProg != null) {
                                Text("${(dlProg * 100).toInt()}%", fontSize = 12.sp, color = Accent, modifier = Modifier.padding(horizontal = 8.dp))
                            } else if (offline != null) {
                                IconButton({ AudiobookStore.deleteDownload(ctx, book.id, idx); dlVersion++ }) { Text("🗑️", fontSize = 15.sp) }
                            } else {
                                IconButton({
                                    scope.launch { AudiobookStore.downloadChapter(ctx, book.id, ch); dlVersion++ }
                                }) { Text("⬇️", fontSize = 16.sp) }
                            }
                        }
                    }
                    if (dlProg != null) {
                        LinearProgressIndicator(progress = { dlProg }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
                    }
                }
            }
        }
    }
}
