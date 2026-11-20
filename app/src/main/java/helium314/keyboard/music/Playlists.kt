// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.text.Normalizer

data class Playlist(val id: Long, val name: String, val songs: List<Long>)

/** Open Keyboard Music (test 235, Jarrod): your playlists, kept in Music's own folder. */
object Playlists {
    val all = mutableStateOf<List<Playlist>>(emptyList())
    private var loaded = false
    private fun file(ctx: Context) = File(ctx.filesDir, "music/playlists.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        all.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val s = o.getJSONArray("songs")
                Playlist(o.getLong("id"), o.getString("name"), (0 until s.length()).map { s.getLong(it) })
            }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<Playlist>) {
        all.value = list
        val a = JSONArray()
        list.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songs))) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(a.toString()) }
    }

    fun get(id: Long) = all.value.find { it.id == id }

    private fun freeName(name: String): String {
        val names = all.value.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in names) return name
        var n = 2
        while ("$name ($n)".lowercase() in names) n++
        return "$name ($n)"
    }

    fun create(ctx: Context, name: String, songs: List<Long> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis() + all.value.size, freeName(name.trim().ifBlank { "New Playlist" }), songs)
        write(ctx, all.value + p)
        return p
    }
    fun rename(ctx: Context, id: Long, name: String) = write(ctx, all.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    fun delete(ctx: Context, id: Long) = write(ctx, all.value.filter { it.id != id })
    fun add(ctx: Context, id: Long, songs: List<Long>) = write(ctx, all.value.map { if (it.id == id) it.copy(songs = it.songs + songs) else it })
    fun removeAt(ctx: Context, id: Long, index: Int) =
        write(ctx, all.value.map { if (it.id == id) it.copy(songs = it.songs.filterIndexed { i, _ -> i != index }) else it })
    fun move(ctx: Context, id: Long, from: Int, to: Int) = write(ctx, all.value.map {
        if (it.id != id || from !in it.songs.indices || to !in it.songs.indices) it
        else it.copy(songs = it.songs.toMutableList().apply { add(to, removeAt(from)) })
    })
}

/** One line of a playlist file: whatever it tells us about the song. */
data class Entry(val path: String = "", val title: String = "", val artist: String = "", val album: String = "", val durationMs: Long = 0) {
    fun label() = when {
        title.isNotBlank() && artist.isNotBlank() -> "$artist - $title"
        title.isNotBlank() -> title
        else -> path.substringAfterLast('/').substringAfterLast('\\')
    }
}

data class ImportedList(val name: String, val entries: List<Entry>, val from: String)

/** A playlist ready to import: which songs we found in your music, and which we couldn't. */
class ImportResult(val list: ImportedList, val found: List<Long>, val missing: List<Entry>) {
    val selected = mutableStateOf(found.isNotEmpty())
}

/**
 * Reads playlists from other music players: M3U/M3U8 (Samsung Music, Poweramp, VLC, most Android players, Apple Music
 * "Export Playlist" as M3U), Apple Music / iTunes exports (Unicode text and XML, including the whole Library.xml), PLS,
 * XSPF (VLC), WPL / ZPL (Windows Media Player), and CSV lists (Spotify via Exportify, TuneMyMusic, Soundiiz).
 */
object PlaylistImport {

    fun readFile(ctx: Context, uri: Uri): List<ImportedList> {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        return parse(name, decode(bytes))
    }

    /** Text in whatever encoding the file uses (iTunes text exports are UTF-16). */
    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        b.size >= 4 && b[1] == 0.toByte() && b[3] == 0.toByte() -> String(b, Charsets.UTF_16LE)
        else -> {
            val utf = String(b, Charsets.UTF_8)
            if ('�' in utf) String(b, Charsets.ISO_8859_1) else utf
        }
    }

    fun parse(fileName: String, text: String): List<ImportedList> {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val t = text.trimStart()
        return runCatching {
            when {
                t.startsWith("<?xml") && "<plist" in t.take(500) || ext == "xml" && "<plist" in t -> itunesXml(t)
                ext == "xspf" || "<playlist" in t.take(300) && "xspf" in t.take(300) -> listOf(ImportedList(xspfTitle(t) ?: base, xspf(t), "XSPF"))
                ext == "wpl" || ext == "zpl" || "<smil" in t.take(300) -> listOf(ImportedList(base, wpl(t), "Windows Media Player"))
                ext == "pls" || t.startsWith("[playlist]", ignoreCase = true) -> listOf(ImportedList(base, pls(t), "PLS"))
                t.lineSequence().firstOrNull()?.let { "\t" in it && it.contains("Name") && it.contains("Artist") } == true ->
                    listOf(ImportedList(base, itunesText(t), "Apple Music / iTunes"))
                ext == "csv" -> listOf(ImportedList(base, csv(t), "CSV"))
                else -> m3u(t).let { (n, e) -> listOf(ImportedList(n ?: base, e, "M3U")) }
            }
        }.getOrDefault(emptyList()).filter { it.entries.isNotEmpty() }
    }

    private fun m3u(t: String): Pair<String?, List<Entry>> {
        val out = ArrayList<Entry>()
        var name: String? = null
        var title = ""; var artist = ""; var dur = 0L
        t.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> {}
                line.startsWith("#PLAYLIST:", true) -> name = line.substringAfter(':').trim()
                line.startsWith("#EXTINF:", true) -> {
                    val rest = line.substringAfter(':')
                    dur = rest.substringBefore(',').substringBefore(' ').toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0
                    val info = rest.substringAfter(',', "").trim()
                    if (" - " in info) { artist = info.substringBefore(" - ").trim(); title = info.substringAfter(" - ").trim() } else title = info
                }
                line.startsWith("#") -> {}
                else -> { out += Entry(cleanPath(line), title, artist, "", dur); title = ""; artist = ""; dur = 0 }
            }
        }
        return name to out
    }

    private fun pls(t: String): List<Entry> {
        val files = HashMap<Int, String>(); val titles = HashMap<Int, String>(); val lens = HashMap<Int, Long>()
        t.lineSequence().forEach { raw ->
            val l = raw.trim(); val k = l.substringBefore('=').lowercase(); val v = l.substringAfter('=', "")
            val n = k.filter { it.isDigit() }.toIntOrNull() ?: return@forEach
            when {
                k.startsWith("file") -> files[n] = cleanPath(v)
                k.startsWith("title") -> titles[n] = v
                k.startsWith("length") -> lens[n] = (v.toLongOrNull() ?: 0) * 1000
            }
        }
        return files.keys.sorted().map { n ->
            val ti = titles[n].orEmpty()
            if (" - " in ti) Entry(files[n]!!, ti.substringAfter(" - "), ti.substringBefore(" - "), "", lens[n] ?: 0)
            else Entry(files[n]!!, ti, "", "", lens[n] ?: 0)
        }
    }

    private fun xspfTitle(t: String) = Regex("""<playlist[^>]*>\s*<title>([^<]*)</title>""").find(t)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotBlank() }

    private fun xspf(t: String): List<Entry> = Regex("""<track>(.*?)</track>""", RegexOption.DOT_MATCHES_ALL).findAll(t).map { m ->
        val b = m.groupValues[1]
        fun tag(n: String) = Regex("<$n>([^<]*)</$n>").find(b)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
        Entry(cleanPath(tag("location")), tag("title"), tag("creator"), tag("album"), tag("duration").toLongOrNull() ?: 0)
    }.toList()

    private fun wpl(t: String): List<Entry> = Regex("""<media\s[^>]*src="([^"]*)"""").findAll(t).map { Entry(cleanPath(unescape(it.groupValues[1]))) }.toList()

    private fun itunesText(t: String): List<Entry> {
        val lines = t.lines().filter { it.isNotBlank() }
        val head = lines.first().split('\t').map { it.trim() }
        fun col(vararg n: String) = head.indexOfFirst { h -> n.any { h.equals(it, true) } }
        val iName = col("Name", "Nom", "Titel", "Nombre"); val iArtist = col("Artist", "Artiste", "Künstler", "Artista")
        val iAlbum = col("Album"); val iTime = col("Time", "Durée", "Dauer"); val iLoc = col("Location", "Emplacement", "Ort")
        return lines.drop(1).map { line ->
            val f = line.split('\t')
            fun at(i: Int) = if (i in f.indices) f[i].trim() else ""
            Entry(cleanPath(at(iLoc)), at(iName), at(iArtist), at(iAlbum), (at(iTime).toLongOrNull() ?: 0) * 1000)
        }
    }

    private fun csv(t: String): List<Entry> {
        val rows = csvRows(t)
        if (rows.size < 2) return emptyList()
        val head = rows.first().map { it.trim().lowercase() }
        fun col(vararg n: String) = head.indexOfFirst { h -> n.any { h == it } }.takeIf { it >= 0 } ?: head.indexOfFirst { h -> n.any { h.contains(it) } }
        val iTitle = col("track name", "title", "song", "name", "track")
        val iArtist = col("artist name(s)", "artist name", "artist", "artists")
        val iAlbum = col("album name", "album")
        val iDur = col("duration (ms)", "track duration (ms)", "duration")
        if (iTitle < 0) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            fun at(i: Int) = if (i in r.indices) r[i].trim() else ""
            val title = at(iTitle).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val d = at(iDur).toLongOrNull() ?: 0
            Entry("", title, at(iArtist).substringBefore(';').substringBefore(", "), at(iAlbum), if (d in 1..9999) d * 1000 else d)
        }
    }

    private fun csvRows(t: String): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        val sep = if (t.lineSequence().first().count { it == ';' } > t.lineSequence().first().count { it == ',' }) ';' else ','
        while (i < t.length) {
            val c = t[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                sep -> { row += cell.toString(); cell.clear() }
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    /** Apple Music / iTunes XML: one playlist, or the whole library with all of them. */
    private fun itunesXml(t: String): List<ImportedList> {
        val root = plist(t) as? Map<*, *> ?: return emptyList()
        val tracks = root["Tracks"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val lists = root["Playlists"] as? List<*> ?: return emptyList()
        return lists.mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            if (m["Master"] == true || m["Distinguished Kind"] != null || m["Folder"] == true) return@mapNotNull null
            val name = m["Name"] as? String ?: return@mapNotNull null
            if (name == "Library" || name == "Music") return@mapNotNull null
            val items = (m["Playlist Items"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("Track ID")?.toString() }
            val entries = items.mapNotNull { id ->
                val tr = tracks[id] as? Map<*, *> ?: return@mapNotNull null
                Entry(cleanPath(tr["Location"] as? String ?: ""), tr["Name"] as? String ?: "", tr["Artist"] as? String ?: "",
                    tr["Album"] as? String ?: "", (tr["Total Time"] as? Long) ?: 0)
            }
            ImportedList(name, entries, "Apple Music / iTunes")
        }
    }

    /** Reads an Apple property list (SAX, so a big Library.xml doesn't need a lot of memory). */
    private fun plist(t: String): Any? {
        val stack = ArrayList<Any>() // maps and lists being filled
        val keys = ArrayList<String?>() // pending key for each map on the stack
        val text = StringBuilder()
        var root: Any? = null
        fun put(v: Any?) {
            if (stack.isEmpty()) { root = v; return }
            when (val top = stack.last()) {
                is MutableMap<*, *> -> { @Suppress("UNCHECKED_CAST") (top as MutableMap<String, Any?>)[keys.last() ?: ""] = v; keys[keys.size - 1] = null }
                is MutableList<*> -> @Suppress("UNCHECKED_CAST") (top as MutableList<Any?>).add(v)
            }
        }
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?) = org.xml.sax.InputSource(StringReader(""))
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: org.xml.sax.Attributes?) {
                text.setLength(0)
                when (qName) {
                    "dict" -> { stack += LinkedHashMap<String, Any?>(); keys += null }
                    "array" -> { stack += ArrayList<Any?>(); keys += null }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "dict", "array" -> { val v = stack.removeAt(stack.size - 1); keys.removeAt(keys.size - 1); put(v) }
                    "key" -> if (stack.isNotEmpty()) keys[keys.size - 1] = text.toString()
                    "integer" -> put(text.toString().trim().toLongOrNull())
                    "true" -> put(true)
                    "false" -> put(false)
                    "plist" -> {}
                    else -> put(text.toString())
                }
                text.setLength(0)
            }
        }
        val f = javax.xml.parsers.SAXParserFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.newSAXParser().parse(org.xml.sax.InputSource(StringReader(t)), handler)
        return root
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

    /** Turns file:///… and %20 into a normal path, with / separators. */
    private fun cleanPath(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("file:", true)) {
            s = s.substringAfter("file:").trimStart('/')
            s = "/" + runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        }
        return s.replace('\\', '/')
    }

    // ---- playlists the phone already knows (including M3U files other players saved) ----

    @Suppress("DEPRECATION")
    fun fromPhone(ctx: Context): List<Pair<ImportedList, List<Long>>> {
        val out = ArrayList<Pair<ImportedList, List<Long>>>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val name = c.getString(1) ?: continue
                    val ids = ArrayList<Long>()
                    runCatching {
                        ctx.contentResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", id),
                            arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID), null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER)?.use { m ->
                            while (m.moveToNext()) ids += m.getLong(0)
                        }
                    }
                    if (ids.isNotEmpty()) out += ImportedList(name, ids.map { Entry(path = "id:$it") }, "On this phone") to ids
                }
            }
        }
        return out
    }

    // ---- matching to your music ----

    private fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        t = t.replace(Regex("""\s*[(\[](feat|ft|with|remaster|live|explicit|clean|radio edit|album version|mono|stereo)[^)\]]*[)\]]"""), "")
        t = t.replace(Regex("""\s+-\s+(remaster(ed)?|\d{4} remaster).*$"""), "")
        t = t.replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return t.removePrefix("the ").trim()
    }

    class Index(lib: Library) {
        val byId = lib.byId
        val byFile = HashMap<String, MutableList<Song>>()
        val byTitleArtist = HashMap<String, Song>()
        val byTitle = HashMap<String, MutableList<Song>>()
        init {
            lib.songs.forEach { s ->
                byFile.getOrPut(s.path.substringAfterLast('/').lowercase()) { ArrayList() } += s
                val t = norm(s.title)
                byTitleArtist.putIfAbsent("$t|${norm(s.artist)}", s)
                if (s.albumArtist.isNotBlank()) byTitleArtist.putIfAbsent("$t|${norm(s.albumArtist)}", s)
                byTitle.getOrPut(t) { ArrayList() } += s
            }
        }
    }

    fun match(idx: Index, e: Entry): Song? {
        if (e.path.startsWith("id:")) return idx.byId[e.path.removePrefix("id:").toLongOrNull()]
        if (e.path.isNotBlank()) {
            val cands = idx.byFile[e.path.substringAfterLast('/').lowercase()].orEmpty()
            if (cands.size == 1) return cands[0]
            if (cands.size > 1) {
                // same file name in several folders: pick the one whose folders match best
                val parts = e.path.lowercase().split('/').reversed()
                return cands.maxByOrNull { s -> s.path.lowercase().split('/').reversed().zip(parts).takeWhile { (a, b) -> a == b }.size }
            }
        }
        var title = e.title; var artist = e.artist
        if (title.isBlank() && e.path.isNotBlank()) {
            val f = e.path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace(Regex("""^\d{1,3}\s*[-.]?\s*"""), "")
            if (" - " in f) { artist = f.substringBefore(" - "); title = f.substringAfter(" - ") } else title = f
        }
        if (title.isBlank()) return null
        val t = norm(title)
        if (artist.isNotBlank()) {
            idx.byTitleArtist["$t|${norm(artist)}"]?.let { return it }
            // "Artist A, Artist B" or "A & B": try the first name
            idx.byTitleArtist["$t|${norm(artist.split(',', ';', '&', '/').first())}"]?.let { return it }
        }
        val same = idx.byTitle[t].orEmpty()
        if (same.size == 1) return same[0]
        if (same.size > 1) {
            val a = norm(artist)
            return same.firstOrNull { a.isNotEmpty() && (norm(it.artist).contains(a) || a.contains(norm(it.artist))) }
                ?: same.minByOrNull { if (e.durationMs > 0) kotlin.math.abs(it.durationMs - e.durationMs) else 0 }?.takeIf { e.durationMs > 0 || artist.isBlank() }
        }
        return null
    }

    fun resolve(lib: Library, lists: List<ImportedList>): List<ImportResult> {
        val idx = Index(lib)
        return lists.map { l ->
            val found = ArrayList<Long>(); val missing = ArrayList<Entry>()
            l.entries.forEach { e -> val s = match(idx, e); if (s != null) found += s.id else missing += e }
            ImportResult(l, found, missing)
        }
    }

    fun save(ctx: Context, results: List<ImportResult>): Int {
        var n = 0
        results.filter { it.selected.value && it.found.isNotEmpty() }.forEach { Playlists.create(ctx, it.list.name, it.found); n++ }
        return n
    }
}
