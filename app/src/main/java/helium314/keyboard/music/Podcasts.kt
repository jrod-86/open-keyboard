// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * Open Waves test 370 (Jarrod): Free, open-RSS Podcasts tab.
 * Searches millions of open podcasts via the free iTunes directory (zero API key), parses standard RSS 2.0 feeds
 * (including custom/Patreon RSS URLs), supports offline episode downloads for truck shifts, and remembers exact
 * resume positions per episode.
 */
data class PodcastShow(
    val id: String,
    val title: String,
    val author: String = "",
    val feedUrl: String = "",
    val artworkUrl: String = "",
    val description: String = "",
)

data class PodcastEpisode(
    val id: String,
    val showId: String,
    val showTitle: String,
    val title: String,
    val audioUrl: String,
    val pubDate: String = "",
    val durationMs: Long = 0L,
    val description: String = "",
    val artworkUrl: String = "",
)

object PodcastStore {
    val subscriptions = mutableStateListOf<PodcastShow>()
    val downloadProgress = mutableStateMapOf<String, Float>()
    @Volatile private var loadedSubs = false
    private val chartCache = HashMap<String, List<PodcastShow>>()
    private val episodeCache = HashMap<String, List<PodcastEpisode>>()
    private val artCache = object : android.util.LruCache<String, Bitmap>(64) {}

    private fun subsFile(ctx: Context) = File(ctx.filesDir, "music/podcasts_subs.json")
    private fun dlDir(ctx: Context) = File(ctx.filesDir, "music/podcast_dl")
    private fun posPrefs(ctx: Context) = ctx.getSharedPreferences("music_podcasts", Context.MODE_PRIVATE)

    fun loadSubscriptions(ctx: Context) {
        if (loadedSubs) return
        loadedSubs = true
        runCatching {
            val f = subsFile(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val out = ArrayList<PodcastShow>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val feed = j.optString("feedUrl")
                if (feed.isNotBlank()) {
                    out += PodcastShow(
                        id = j.optString("id", feed.hashCode().toString()),
                        title = j.optString("title", "Podcast"),
                        author = j.optString("author", ""),
                        feedUrl = feed,
                        artworkUrl = j.optString("artworkUrl", ""),
                        description = j.optString("description", ""),
                    )
                }
            }
            subscriptions.clear()
            subscriptions.addAll(out)
        }
    }

    private fun saveSubscriptions(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            subscriptions.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id); put("title", s.title); put("author", s.author)
                    put("feedUrl", s.feedUrl); put("artworkUrl", s.artworkUrl); put("description", s.description)
                })
            }
            subsFile(ctx).apply { parentFile?.mkdirs() }.writeText(arr.toString())
        }
    }

    fun isSubscribed(show: PodcastShow) = subscriptions.any {
        (show.feedUrl.isNotBlank() && it.feedUrl == show.feedUrl) || it.id == show.id
    }

    fun toggleSubscribe(ctx: Context, show: PodcastShow) {
        val idx = subscriptions.indexOfFirst {
            (show.feedUrl.isNotBlank() && it.feedUrl == show.feedUrl) || it.id == show.id
        }
        if (idx >= 0) subscriptions.removeAt(idx) else subscriptions.add(0, show)
        saveSubscriptions(ctx)
    }

    // ---- per-episode resume positions ----

    fun savePos(ctx: Context, epId: String, posMs: Long, durMs: Long) {
        if (epId.isBlank() || posMs < 0) return
        // If finished (within last 15s), reset to 0 so replay starts cleanly
        val save = if (durMs > 30_000L && posMs >= durMs - 15_000L) 0L else posMs
        posPrefs(ctx).edit().putLong("pos_$epId", save).apply()
    }

    fun posOf(ctx: Context, epId: String): Long = posPrefs(ctx).getLong("pos_$epId", 0L)

    // ---- offline downloads ----

    fun downloadedFile(ctx: Context, epId: String): File? {
        val f = File(dlDir(ctx), "$epId.audio")
        return if (f.exists() && f.length() > 16_384L) f else null
    }

    fun deleteDownload(ctx: Context, epId: String) {
        File(dlDir(ctx), "$epId.audio").delete()
        File(dlDir(ctx), "$epId.part").delete()
    }

    suspend fun downloadEpisode(ctx: Context, ep: PodcastEpisode): Result<File> = withContext(Dispatchers.IO) {
        val dir = dlDir(ctx).apply { mkdirs() }
        val target = File(dir, "${ep.id}.audio")
        val part = File(dir, "${ep.id}.part")
        withContext(Dispatchers.Main) { downloadProgress[ep.id] = 0f }
        try {
            val conn = openFollowRedirects(ep.audioUrl, 15_000, 30_000)
            val total = conn.contentLengthLong
            var done = 0L
            var lastUi = 0L
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
                            withContext(Dispatchers.Main) { downloadProgress[ep.id] = p }
                        }
                    }
                }
            }
            conn.disconnect()
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("Could not save episode")
            withContext(Dispatchers.Main) { downloadProgress.remove(ep.id) }
            Result.success(target)
        } catch (e: Throwable) {
            part.delete()
            withContext(Dispatchers.Main) { downloadProgress.remove(ep.id) }
            Result.failure(e)
        }
    }

    // ---- directory search & top charts (free iTunes public endpoints, zero API key) ----

    suspend fun search(query: String): Result<List<PodcastShow>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=35&term=$q")
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            val out = ArrayList<PodcastShow>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val feed = j.optString("feedUrl").trim()
                if (feed.isBlank()) continue
                out += PodcastShow(
                    id = j.optString("collectionId", feed.hashCode().toString()),
                    title = j.optString("collectionName", j.optString("trackName", "Podcast")),
                    author = j.optString("artistName", ""),
                    feedUrl = feed,
                    artworkUrl = j.optString("artworkUrl600").ifBlank { j.optString("artworkUrl100") },
                )
            }
            out
        }
    }

    suspend fun topChart(countryCode: String): Result<List<PodcastShow>> = withContext(Dispatchers.IO) {
        chartCache[countryCode]?.let { return@withContext Result.success(it) }
        runCatching {
            val cc = countryCode.lowercase()
            val body = httpGet("https://itunes.apple.com/$cc/rss/toppodcasts/limit=25/json")
            val entries = JSONObject(body).optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
            val out = ArrayList<PodcastShow>(entries.length())
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id").orEmpty()
                val title = e.optJSONObject("im:name")?.optString("label").orEmpty()
                val author = e.optJSONObject("im:artist")?.optString("label").orEmpty()
                val imgs = e.optJSONArray("im:image")
                val art = if (imgs != null && imgs.length() > 0) imgs.getJSONObject(imgs.length() - 1).optString("label") else ""
                if (id.isNotBlank() && title.isNotBlank()) {
                    out += PodcastShow(id = id, title = title, author = author, feedUrl = "", artworkUrl = art)
                }
            }
            chartCache[countryCode] = out
            out
        }
    }

    /** Resolves a Top Chart show's iTunes ID into its open RSS feedUrl. */
    suspend fun resolveFeedUrl(show: PodcastShow): PodcastShow = withContext(Dispatchers.IO) {
        if (show.feedUrl.isNotBlank()) return@withContext show
        runCatching {
            val body = httpGet("https://itunes.apple.com/lookup?id=${show.id}")
            val arr = JSONObject(body).optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                val j = arr.getJSONObject(0)
                val feed = j.optString("feedUrl").trim()
                val art = j.optString("artworkUrl600").ifBlank { show.artworkUrl }
                if (feed.isNotBlank()) return@withContext show.copy(feedUrl = feed, artworkUrl = art)
            }
        }
        show
    }

    /** Fetches and parses a podcast's open RSS 2.0 feed (works for public & private/Patreon feeds). */
    suspend fun fetchEpisodes(show: PodcastShow, forceRefresh: Boolean = false): Result<Pair<PodcastShow, List<PodcastEpisode>>> = withContext(Dispatchers.IO) {
        val resolved = resolveFeedUrl(show)
        if (resolved.feedUrl.isBlank()) return@withContext Result.failure(IllegalStateException("No RSS feed available for this show"))
        if (!forceRefresh) {
            episodeCache[resolved.feedUrl]?.let { return@withContext Result.success(resolved to it) }
        }
        runCatching {
            val xml = httpGet(resolved.feedUrl)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xml))
            }
            var channelTitle = resolved.title
            var channelAuthor = resolved.author
            var channelArt = resolved.artworkUrl
            var channelDesc = resolved.description

            var inItem = false
            var itemTitle = ""; var itemAudio = ""; var itemPub = ""; var itemDur = 0L; var itemDesc = ""; var itemArt = ""; var itemGuid = ""
            val episodes = ArrayList<PodcastEpisode>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && episodes.size < 80) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        when {
                            tag == "item" -> {
                                inItem = true
                                itemTitle = ""; itemAudio = ""; itemPub = ""; itemDur = 0L; itemDesc = ""; itemArt = ""; itemGuid = ""
                            }
                            inItem && tag == "enclosure" -> {
                                val u = parser.getAttributeValue(null, "url").orEmpty().trim()
                                val t = parser.getAttributeValue(null, "type").orEmpty().lowercase()
                                if (u.isNotBlank() && (t.isEmpty() || t.startsWith("audio") || u.contains(".mp3", true) || u.contains(".m4a", true))) {
                                    itemAudio = u
                                }
                            }
                            inItem && (tag == "itunes:image" || tag == "media:thumbnail") -> {
                                val href = parser.getAttributeValue(null, "href") ?: parser.getAttributeValue(null, "url")
                                if (!href.isNullOrBlank()) itemArt = href.trim()
                            }
                            !inItem && tag == "itunes:image" && channelArt.isBlank() -> {
                                parser.getAttributeValue(null, "href")?.trim()?.takeIf { it.isNotBlank() }?.let { channelArt = it }
                            }
                            inItem && tag == "title" -> itemTitle = readText(parser)
                            inItem && tag == "pubdate" -> itemPub = tidyDate(readText(parser))
                            inItem && tag == "itunes:duration" -> itemDur = parseDurationMs(readText(parser))
                            inItem && (tag == "itunes:summary" || tag == "description") && itemDesc.isBlank() -> itemDesc = stripHtml(readText(parser))
                            inItem && tag == "guid" -> itemGuid = readText(parser)
                            !inItem && tag == "title" && channelTitle.isBlank() -> channelTitle = readText(parser)
                            !inItem && tag == "itunes:author" && channelAuthor.isBlank() -> channelAuthor = readText(parser)
                            !inItem && (tag == "itunes:summary" || tag == "description") && channelDesc.isBlank() -> channelDesc = stripHtml(readText(parser))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", true) && inItem) {
                            inItem = false
                            if (itemAudio.isNotBlank() && itemTitle.isNotBlank()) {
                                val epId = (itemGuid.ifBlank { itemAudio }).hashCode().toUInt().toString(16)
                                episodes += PodcastEpisode(
                                    id = epId,
                                    showId = resolved.id,
                                    showTitle = channelTitle.ifBlank { resolved.title },
                                    title = itemTitle.trim(),
                                    audioUrl = itemAudio,
                                    pubDate = itemPub,
                                    durationMs = itemDur,
                                    description = itemDesc.take(260),
                                    artworkUrl = itemArt.ifBlank { channelArt },
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
            val updatedShow = resolved.copy(
                title = channelTitle.ifBlank { resolved.title }.ifBlank { "Podcast" },
                author = channelAuthor,
                artworkUrl = channelArt,
                description = channelDesc.take(300),
            )
            episodeCache[resolved.feedUrl] = episodes
            updatedShow to episodes
        }
    }

    suspend fun loadArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        artCache.get(url)?.let { return@withContext it }
        runCatching {
            val conn = openFollowRedirects(url, 8_000, 10_000)
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            val sample = if (maxDim > 320) (maxDim / 160).coerceAtLeast(1) else 1
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { artCache.put(url, it) }
        }.getOrNull()
    }

    private fun readText(p: XmlPullParser): String = runCatching { p.nextText().trim() }.getOrDefault("")

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun tidyDate(raw: String): String {
        // "Thu, 26 Nov 2026 05:00:00 +0000" -> "26 Nov 2026"
        val parts = raw.replace(",", "").split(Regex("\\s+"))
        return if (parts.size >= 4 && parts[1].all { it.isDigit() }) "${parts[1]} ${parts[2]} ${parts[3]}"
        else raw.take(16)
    }

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

@Composable
private fun PodcastArt(url: String, size: Dp, corner: Dp = 10.dp) {
    val bmp by produceState<Bitmap?>(null, url) { value = PodcastStore.loadArtwork(url) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("🎙️", fontSize = (size.value * 0.45f).sp)
        }
    }
}

private fun formatPodTime(ms: Long): String {
    if (ms <= 0L) return ""
    val s = ms / 1000L
    val h = s / 3600L; val m = (s % 3600L) / 60L; val sec = s % 60L
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}

@Composable
internal fun PodcastsTab(ui: PlayerUi) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { PodcastStore.loadSubscriptions(ctx) }

    var openShow by remember { mutableStateOf<PodcastShow?>(null) }
    var mode by remember { mutableStateOf(if (PodcastStore.subscriptions.isNotEmpty()) "SUBS" else "AU") }
    var query by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf("") }
    var shows by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askRss by remember { mutableStateOf(false) }

    // If a show is open, render its episodes list right inside the tab!
    val viewing = openShow
    if (viewing != null) {
        PodcastShowDetail(viewing, ui) { openShow = null }
        return
    }

    fun loadShows(m: String, q: String) {
        if (q.isBlank() && m == "SUBS") {
            loading = false; error = null; shows = PodcastStore.subscriptions.toList(); return
        }
        loading = true; error = null
        scope.launch {
            val res = if (q.isNotBlank()) PodcastStore.search(q) else PodcastStore.topChart(m)
            res.onSuccess { shows = it; loading = false }
                .onFailure { error = "Couldn't load podcasts (${it.message ?: "check internet"})"; loading = false }
        }
    }

    LaunchedEffect(mode, activeSearch) { loadShows(mode, activeSearch) }

    val shown = if (activeSearch.isBlank() && mode == "SUBS") PodcastStore.subscriptions.toList() else shows
    val modes = listOf("SUBS" to "🎙 My Shows", "AU" to "🇦🇺 Top AU", "NZ" to "🇳🇿 Top NZ", "US" to "🇺🇸 Top US")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "pod_search") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isBlank()) activeSearch = "" },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search millions of podcasts…", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { activeSearch = query.trim() }),
                    trailingIcon = { if (query.isNotEmpty()) TextButton({ query = ""; activeSearch = "" }) { Text("✕", color = dim) } },
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { if (query.isNotBlank()) activeSearch = query.trim() else askRss = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) { Text(if (query.isNotBlank()) "Go" else "＋ RSS", color = Accent, fontSize = 13.sp) }
            }
        }

        item(key = "pod_chips") {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(modes) { (id, label) ->
                    FilterChip(
                        selected = activeSearch.isBlank() && mode == id,
                        onClick = { activeSearch = ""; query = ""; mode = id },
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
                    Text("Loading podcasts…", color = dim, fontSize = 14.sp)
                }
            }
        } else if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = dim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ loadShows(mode, activeSearch) }) { Text("Try again", color = Accent) }
                }
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    if (mode == "SUBS" && activeSearch.isBlank())
                        "No subscribed shows yet. Search for any podcast above or tap 🇦🇺 Top AU / 🇳🇿 Top NZ to browse!"
                    else "No podcasts found for “$activeSearch”.",
                    Modifier.padding(24.dp), color = dim, fontSize = 14.sp,
                )
            }
        } else {
            items(shown, key = { it.id + "|" + it.feedUrl }) { s ->
                val sub = PodcastStore.isSubscribed(s)
                Row(
                    Modifier.fillMaxWidth().clickable { openShow = s }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodcastArt(s.artworkUrl, 52.dp, 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.author.isNotBlank()) Text(s.author, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sub) Text("✓", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }

    if (askRss) {
        var rssUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askRss = false },
            title = { Text("Add podcast RSS feed") },
            text = {
                Column {
                    Text("Paste any public or private (e.g. Patreon) podcast RSS feed URL.", fontSize = 13.sp, color = dim)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(rssUrl, { rssUrl = it }, label = { Text("RSS URL (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rssUrl.trim().startsWith("http"),
                    onClick = {
                        val u = rssUrl.trim()
                        askRss = false
                        openShow = PodcastShow(id = "rss_${u.hashCode()}", title = "", feedUrl = u)
                    },
                ) { Text("Open", color = Accent) }
            },
            dismissButton = { TextButton({ askRss = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PodcastShowDetail(initialShow: PodcastShow, ui: PlayerUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scope = rememberCoroutineScope()
    var show by remember(initialShow.id, initialShow.feedUrl) { mutableStateOf(initialShow) }
    var episodes by remember(initialShow.id, initialShow.feedUrl) { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var loading by remember(initialShow.id, initialShow.feedUrl) { mutableStateOf(true) }
    var error by remember(initialShow.id, initialShow.feedUrl) { mutableStateOf<String?>(null) }
    var dlVersion by remember { mutableStateOf(0) }

    fun load(force: Boolean = false) {
        loading = true; error = null
        scope.launch {
            PodcastStore.fetchEpisodes(show, forceRefresh = force)
                .onSuccess { (s, eps) -> show = s; episodes = eps; loading = false }
                .onFailure { error = "Couldn't load episodes (${it.message ?: "check internet"})"; loading = false }
        }
    }
    LaunchedEffect(initialShow.id, initialShow.feedUrl) { load() }

    val sub = PodcastStore.isSubscribed(show)
    val playingMediaId = ui.current?.mediaId ?: ""

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("← Back", color = Accent, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                TextButton({ load(force = true) }) { Text("Refresh", color = dim, fontSize = 13.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PodcastArt(show.artworkUrl, 76.dp, 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(show.title.ifBlank { "Loading…" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (show.author.isNotBlank()) Text(show.author, fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (show.feedUrl.isNotBlank()) {
                        if (sub) {
                            OutlinedButton({ PodcastStore.toggleSubscribe(ctx, show) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                Text("✓ Subscribed", color = Accent, fontSize = 13.sp)
                            }
                        } else {
                            Button({ PodcastStore.toggleSubscribe(ctx, show) }, colors = ButtonDefaults.buttonColors(containerColor = Accent), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                Text("＋ Subscribe", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            if (show.description.isNotBlank()) {
                Text(show.description, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }

        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Fetching episodes…", color = dim, fontSize = 14.sp)
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
            items(episodes, key = { it.id + "_" + dlVersion }) { ep ->
                val isPlayingThis = playingMediaId == "pod:${ep.id}"
                val dlProg = PodcastStore.downloadProgress[ep.id]
                val offlineFile = PodcastStore.downloadedFile(ctx, ep.id)
                val resumeMs = PodcastStore.posOf(ctx, ep.id)

                Column(
                    Modifier.fillMaxWidth()
                        .clickable {
                            ui.playPodcast(ep, offlineFile, resumeMs)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                ep.title,
                                fontSize = 15.sp,
                                fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPlayingThis) Accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(
                                ep.pubDate.takeIf { it.isNotBlank() },
                                formatPodTime(ep.durationMs).takeIf { it.isNotBlank() },
                                if (offlineFile != null) "✓ Offline" else null,
                                if (resumeMs > 5_000L) "Resume ${formatPodTime(resumeMs)}" else null,
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = if (offlineFile != null || resumeMs > 5_000L) Accent.copy(alpha = 0.85f) else dim)
                        }
                        if (dlProg != null) {
                            Text("${(dlProg * 100).toInt()}%", fontSize = 12.sp, color = Accent, modifier = Modifier.padding(horizontal = 8.dp))
                        } else if (offlineFile != null) {
                            IconButton({ PodcastStore.deleteDownload(ctx, ep.id); dlVersion++ }) {
                                Text("🗑️", fontSize = 16.sp)
                            }
                        } else {
                            IconButton({
                                scope.launch {
                                    PodcastStore.downloadEpisode(ctx, ep)
                                    dlVersion++
                                }
                            }) { Text("⬇️", fontSize = 17.sp) }
                        }
                    }
                    if (dlProg != null) {
                        LinearProgressIndicator(progress = { dlProg }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
                    } else if (ep.description.isNotBlank()) {
                        Text(ep.description, fontSize = 12.sp, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
