// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Open Waves test 369 (Jarrod): Free, open-source Internet Radio tab powered by Radio Browser (radio-browser.info).
 * Streams live stations worldwide (NZ, AU, UK, US, etc.) through ExoPlayer (MP3, AAC, HLS) with favourites and custom URLs.
 */
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val country: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val favicon: String = "",
)

object InternetRadio {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
    )

    val presets = listOf(
        "FAV" to "⭐ Favourites",
        "NZ" to "🇳🇿 New Zealand",
        "AU" to "🇦🇺 Australia",
        "GB" to "🇬🇧 UK",
        "US" to "🇺🇸 USA",
        "TOP" to "🌍 Top Worldwide",
    )

    val favourites = mutableStateListOf<RadioStation>()
    @Volatile private var loadedFavs = false
    private val cache = HashMap<String, List<RadioStation>>()

    private fun favFile(ctx: Context) = File(ctx.filesDir, "music/radio_favs.json")

    fun loadFavourites(ctx: Context) {
        if (loadedFavs) return
        loadedFavs = true
        runCatching {
            val f = favFile(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<RadioStation>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val url = j.optString("url")
                if (url.isNotBlank()) {
                    list += RadioStation(
                        id = j.optString("id", url.hashCode().toString()),
                        name = j.optString("name", "Radio station"),
                        url = url,
                        country = j.optString("country", ""),
                        tags = j.optString("tags", ""),
                        codec = j.optString("codec", ""),
                        bitrate = j.optInt("bitrate", 0),
                        favicon = j.optString("favicon", ""),
                    )
                }
            }
            favourites.clear()
            favourites.addAll(list)
        }
    }

    private fun saveFavourites(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            favourites.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id); put("name", s.name); put("url", s.url)
                    put("country", s.country); put("tags", s.tags)
                    put("codec", s.codec); put("bitrate", s.bitrate); put("favicon", s.favicon)
                })
            }
            favFile(ctx).apply { parentFile?.mkdirs() }.writeText(arr.toString())
        }
    }

    fun isFavourite(station: RadioStation) = favourites.any { it.id == station.id || it.url == station.url }

    fun toggleFavourite(ctx: Context, station: RadioStation) {
        val idx = favourites.indexOfFirst { it.id == station.id || it.url == station.url }
        if (idx >= 0) favourites.removeAt(idx) else favourites.add(0, station)
        saveFavourites(ctx)
    }

    fun addCustom(ctx: Context, name: String, url: String): RadioStation {
        val cleanUrl = url.trim()
        val s = RadioStation(
            id = "custom_${cleanUrl.hashCode()}",
            name = name.trim().ifBlank { "Custom Radio" },
            url = cleanUrl,
            country = "Custom",
            tags = "Live stream",
        )
        if (!isFavourite(s)) {
            favourites.add(0, s)
            saveFavourites(ctx)
        }
        return s
    }

    suspend fun fetchPreset(code: String): Result<List<RadioStation>> = withContext(Dispatchers.IO) {
        cache[code]?.let { return@withContext Result.success(it) }
        val path = when (code) {
            "TOP" -> "/json/stations/topclick/60?hidebroken=true"
            else -> "/json/stations/bycountrycodeexact/$code?order=clickcount&reverse=true&hidebroken=true&limit=60"
        }
        fetch(path).onSuccess { cache[code] = it }
    }

    suspend fun search(query: String): Result<List<RadioStation>> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val byName = fetch("/json/stations/search?name=$q&order=clickcount&reverse=true&hidebroken=true&limit=60")
        val list = byName.getOrNull().orEmpty()
        if (list.isNotEmpty() || byName.isFailure) byName
        else fetch("/json/stations/search?tag=$q&order=clickcount&reverse=true&hidebroken=true&limit=60")
    }

    fun pingClick(stationId: String) {
        if (stationId.startsWith("custom_")) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val conn = (URL("${servers[0]}/json/url/$stationId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                    setRequestProperty("User-Agent", "OpenWaves/1.0 (OpenKeyboard; GPL-3.0)")
                }
                conn.inputStream.use { it.read() }
                conn.disconnect()
            }
        }
    }

    private fun fetch(path: String): Result<List<RadioStation>> {
        var lastErr: Throwable? = null
        for (base in servers) {
            try {
                val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 9000; readTimeout = 12000
                    setRequestProperty("User-Agent", "OpenWaves/1.0 (OpenKeyboard; GPL-3.0)")
                }
                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = JSONArray(body)
                val out = ArrayList<RadioStation>(arr.length())
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val url = j.optString("url_resolved").ifBlank { j.optString("url") }.trim()
                    val name = j.optString("name").trim()
                    if (url.isBlank() || name.isBlank() || !seen.add(url.lowercase())) continue
                    val tags = j.optString("tags", "")
                        .split(',').map { it.trim() }.filter { it.isNotBlank() }.take(3).joinToString(" · ")
                    out += RadioStation(
                        id = j.optString("stationuuid", url.hashCode().toString()),
                        name = name,
                        url = url,
                        country = j.optString("country", "").ifBlank { j.optString("countrycode", "") },
                        tags = tags,
                        codec = j.optString("codec", ""),
                        bitrate = j.optInt("bitrate", 0),
                        favicon = j.optString("favicon", ""),
                    )
                }
                return Result.success(out)
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        return Result.failure(lastErr ?: IllegalStateException("Couldn't reach radio directory"))
    }
}

@Composable
internal fun RadioTab(ui: PlayerUi) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { InternetRadio.loadFavourites(ctx) }

    var selectedPreset by remember { mutableStateOf(if (InternetRadio.favourites.isNotEmpty()) "FAV" else "NZ") }
    var query by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askCustom by remember { mutableStateOf(false) }

    fun loadList(preset: String, searchQ: String) {
        if (searchQ.isBlank() && preset == "FAV") {
            loading = false; error = null; stations = InternetRadio.favourites.toList(); return
        }
        loading = true; error = null
        scope.launch {
            val res = if (searchQ.isNotBlank()) InternetRadio.search(searchQ) else InternetRadio.fetchPreset(preset)
            res.onSuccess { stations = it; loading = false }
                .onFailure { error = "Couldn't load stations (${it.message ?: "check internet"})"; loading = false }
        }
    }

    LaunchedEffect(selectedPreset, activeSearch) { loadList(selectedPreset, activeSearch) }

    val playingMediaId = ui.current?.mediaId ?: ""
    val shown = if (activeSearch.isBlank() && selectedPreset == "FAV") InternetRadio.favourites.toList() else stations

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        // Search box + Add custom URL
        item(key = "radio_search") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isBlank()) activeSearch = "" },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search stations (e.g. George FM, The Rock, Triple J)", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { activeSearch = query.trim() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            TextButton({ query = ""; activeSearch = "" }) { Text("✕", color = dim) }
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { if (query.isNotBlank()) activeSearch = query.trim() else askCustom = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(if (query.isNotBlank()) "Go" else "＋ URL", color = Accent, fontSize = 13.sp)
                }
            }
        }

        // Country & Favourites chips (transparent, matching the rest of Open Waves)
        item(key = "radio_chips") {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(InternetRadio.presets) { (code, label) ->
                    FilterChip(
                        selected = activeSearch.isBlank() && selectedPreset == code,
                        onClick = { activeSearch = ""; query = ""; selectedPreset = code },
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
                    Text("Tuning in directory…", color = dim, fontSize = 14.sp)
                }
            }
        } else if (error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = dim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ loadList(selectedPreset, activeSearch) }) { Text("Try again", color = Accent) }
                }
            }
        } else if (shown.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (selectedPreset == "FAV" && activeSearch.isBlank())
                            "No favourite stations yet. Tap 🇳🇿 New Zealand or 🇦🇺 Australia above and tap ☆ on your favourites!"
                        else "No stations found for “$activeSearch”.",
                        color = dim, fontSize = 14.sp,
                    )
                }
            }
        } else {
            items(shown, key = { it.id + "|" + it.url }) { st ->
                val isThisPlaying = playingMediaId == "radio:${st.id}"
                val fav = InternetRadio.isFavourite(st)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val sub = listOfNotNull(
                                "LIVE",
                                st.country.takeIf { it.isNotBlank() },
                                st.tags.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            ui.playStream(st.name, sub, st.url, st.id, st.favicon)
                            InternetRadio.pingClick(st.id)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isThisPlaying) Accent.copy(alpha = 0.28f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isThisPlaying && ui.playing) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                        } else {
                            Text("📻", fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            st.name,
                            fontSize = 15.sp,
                            fontWeight = if (isThisPlaying) FontWeight.Bold else FontWeight.Medium,
                            color = if (isThisPlaying) Accent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val info = listOfNotNull(
                            st.country.takeIf { it.isNotBlank() },
                            st.tags.takeIf { it.isNotBlank() },
                            if (st.bitrate > 0) "${st.bitrate}k ${st.codec}".trim() else st.codec.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (info.isNotBlank()) {
                            Text(info, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton({ InternetRadio.toggleFavourite(ctx, st) }) {
                        Text(if (fav) "★" else "☆", fontSize = 20.sp, color = if (fav) Accent else dim)
                    }
                }
            }
        }
    }

    if (askCustom) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askCustom = false },
            title = { Text("Add custom radio stream") },
            text = {
                Column {
                    Text("Paste any live MP3, AAC, or HLS (.m3u8) stream link.", fontSize = 13.sp, color = dim)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Station name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(url, { url = it }, label = { Text("Stream URL (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.trim().startsWith("http"),
                    onClick = {
                        val st = InternetRadio.addCustom(ctx, name, url)
                        askCustom = false
                        selectedPreset = "FAV"; activeSearch = ""; query = ""
                        ui.playStream(st.name, "LIVE · Custom", st.url, st.id)
                    },
                ) { Text("Add & Play", color = Accent) }
            },
            dismissButton = { TextButton({ askCustom = false }) { Text("Cancel") } },
        )
    }
}
