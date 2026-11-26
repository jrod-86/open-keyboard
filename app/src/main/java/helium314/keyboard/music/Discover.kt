// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Test 317 (Jarrod): Emerging artist Discovery feed + Support Artist links.
 * Uses free, open ListenBrainz Fresh Releases + MusicBrainz genre/URL data (no API keys, on-demand only).
 * Previews open cleanly in YouTube; Support Artist opens verified Bandcamp / Ko-fi / Patreon / official links.
 */
data class FreshPick(val title: String, val artist: String, val date: String, val kind: String)
data class SupportLink(val label: String, val url: String)

object SupportArtist {
    val asking = mutableStateOf<String?>(null)
    private val cache = HashMap<String, List<SupportLink>>()

    private fun getJson(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "OpenWaves/1.0 (https://github.com/jrod-86/open-keyboard)")
        c.setRequestProperty("Accept", "application/json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    suspend fun findLinks(artist: String): List<SupportLink> = withContext(Dispatchers.IO) {
        val key = artist.trim().lowercase()
        cache[key]?.let { return@withContext it }
        val out = ArrayList<SupportLink>()
        runCatching {
            val q = URLEncoder.encode("artist:\"$artist\"", "UTF-8")
            val search = JSONObject(getJson("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=1"))
            val mbid = search.optJSONArray("artists")?.optJSONObject(0)?.optString("id").orEmpty()
            if (mbid.isNotBlank()) {
                val detail = JSONObject(getJson("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"))
                val rels = detail.optJSONArray("relations")
                val seen = HashSet<String>()
                if (rels != null) for (i in 0 until rels.length()) {
                    val r = rels.optJSONObject(i) ?: continue
                    val url = r.optJSONObject("url")?.optString("resource").orEmpty()
                    if (url.isBlank() || !seen.add(url)) continue
                    val lower = url.lowercase()
                    val label = when {
                        "bandcamp.com" in lower -> "🎵 Bandcamp (DRM-free music)"
                        "ko-fi.com" in lower -> "☕ Ko-fi"
                        "patreon.com" in lower -> "❤️ Patreon"
                        "buymeacoffee.com" in lower -> "☕ Buy Me a Coffee"
                        r.optString("type") == "purchase for download" -> "💿 Buy digital download"
                        r.optString("type") == "official homepage" -> "🌐 Official website"
                        else -> null
                    }
                    if (label != null) out += SupportLink(label, url)
                }
            }
        }
        // Always include a direct Bandcamp search fallback so users can buy DRM-free music directly
        if (out.none { "bandcamp.com" in it.url.lowercase() }) {
            val q = URLEncoder.encode(artist, "UTF-8")
            out += SupportLink("🎵 Find on Bandcamp", "https://bandcamp.com/search?q=$q")
        }
        cache[key] = out
        out
    }

    fun openUrl(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openYouTube(ctx: Context, artist: String, title: String) {
        val q = URLEncoder.encode("$artist $title", "UTF-8")
        openUrl(ctx, "https://www.youtube.com/results?search_query=$q")
    }
}

@Composable
internal fun SupportArtistDialogHost() {
    val artist = SupportArtist.asking.value ?: return
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var links by remember(artist) { mutableStateOf<List<SupportLink>?>(null) }
    LaunchedEffect(artist) { links = SupportArtist.findLinks(artist) }
    AlertDialog(
        onDismissRequest = { SupportArtist.asking.value = null },
        title = { Text("☕ Support $artist") },
        text = {
            Column {
                Text("Support this artist directly via their official links (100% goes to them).", fontSize = 13.sp, color = dim)
                Spacer(Modifier.height(10.dp))
                val list = links
                if (list == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking up official links…", fontSize = 13.sp, color = dim)
                    }
                } else {
                    list.forEach { link ->
                        OutlinedButton(
                            onClick = { SupportArtist.openUrl(ctx, link.url) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) { Text(link.label, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ SupportArtist.asking.value = null }) { Text("Close", color = Accent) } },
    )
}

object DiscoverFeed {
    val GENRES = listOf("Fresh", "Hip-Hop", "Rock", "Electronic", "Indie", "Pop", "R&B", "Jazz", "Metal", "Country", "Lo-fi")
    private val cache = HashMap<String, List<FreshPick>>()

    private fun getJson(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "OpenWaves/1.0 (https://github.com/jrod-86/open-keyboard)")
        c.setRequestProperty("Accept", "application/json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    suspend fun load(genre: String, force: Boolean = false): Result<List<FreshPick>> = withContext(Dispatchers.IO) {
        if (!force) cache[genre]?.let { return@withContext Result.success(it) }
        runCatching {
            val out = ArrayList<FreshPick>()
            if (genre == "Fresh") {
                val root = JSONObject(getJson("https://api.listenbrainz.org/1/explore/fresh-releases/?days=30&sort=release_date&past=true&future=false"))
                val arr = root.optJSONObject("payload")?.optJSONArray("releases")
                if (arr != null) for (i in 0 until minOf(arr.length(), 40)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("release_name").trim()
                    val artist = o.optString("artist_credit_name").trim()
                    if (title.isBlank() || artist.isBlank()) continue
                    out += FreshPick(title, artist, o.optString("release_date", ""), o.optString("release_group_primary_type", "Release"))
                }
            } else {
                val tag = URLEncoder.encode(genre.lowercase(), "UTF-8")
                val root = JSONObject(getJson("https://musicbrainz.org/ws/2/release-group/?query=tag:$tag&fmt=json&limit=35"))
                val arr = root.optJSONArray("release-groups")
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val artist = o.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name").orEmpty().trim()
                    if (title.isBlank() || artist.isBlank()) continue
                    out += FreshPick(title, artist, o.optString("first-release-date", ""), o.optString("primary-type", "Release"))
                }
                out.sortByDescending { it.date }
            }
            val clean = out.distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
            cache[genre] = clean
            clean
        }
    }
}

@Composable
internal fun DiscoverTab() {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var genre by remember { mutableStateOf("Fresh") }
    var items by remember { mutableStateOf<List<FreshPick>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(genre, refreshKey) {
        items = null; error = null
        DiscoverFeed.load(genre, force = refreshKey > 0)
            .onSuccess { items = it }
            .onFailure { error = "Couldn't load feed (${it.message ?: "check internet"})" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Discover new & emerging music", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Pick a genre to browse fresh releases. Tap Watch on YouTube to preview, or Support to buy DRM-free music directly from the artist.",
                    fontSize = 12.sp, color = dim, modifier = Modifier.padding(top = 2.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DiscoverFeed.GENRES) { g ->
                    FilterChip(
                        selected = genre == g,
                        onClick = { genre = g },
                        label = { Text(g) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.25f)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val list = items
        if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = dim, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Button({ refreshKey++ }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Try again") }
                }
            }
        } else if (list == null) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.5.dp, color = Accent)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading $genre releases…", color = dim, fontSize = 14.sp)
                }
            }
        } else if (list.isEmpty()) {
            item { Text("No releases found for $genre right now.", Modifier.padding(24.dp), color = dim) }
        } else {
            items(list) { pick ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .clickable { SupportArtist.openYouTube(ctx, pick.artist, pick.title) }
                        .padding(14.dp),
                ) {
                    Text(pick.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pick.artist + listOfNotNull(pick.kind.takeIf { it.isNotBlank() }, pick.date.takeIf { it.isNotBlank() })
                            .joinToString(" · ").let { if (it.isNotEmpty()) "  ·  $it" else "" },
                        fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { SupportArtist.openYouTube(ctx, pick.artist, pick.title) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) { Text("▶ YouTube", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { SupportArtist.asking.value = pick.artist },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("☕ Support", fontSize = 13.sp, color = Accent) }
                    }
                }
            }
        }
    }
}
