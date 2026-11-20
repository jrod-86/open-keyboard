// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One song on the phone, read from Android's media list (MediaStore). */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val year: Int,
    val dateAdded: Long,
    val path: String,
    /** artwork you picked in "Fix song info" (a file in Music's own folder) */
    val artFile: String = "",
    /** true when some of this song's info comes from "Fix song info" */
    val fixed: Boolean = false,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = if (artFile.isNotEmpty()) Uri.fromFile(java.io.File(artFile))
        else ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(artUri)
                .setExtras(CoverBitmapLoader.extras(this)) // test 266: lets the session load the real cover
                .setTrackNumber(track)
                .setReleaseYear(year.takeIf { it > 0 })
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        ).build()
}

data class Album(val id: Long, val title: String, val artist: String, val year: Int, val songs: List<Song>, val dateAdded: Long) {
    val cover: Song get() = songs.first()
}

data class Artist(val name: String, val albums: List<Album>, val songs: List<Song>)

class Library(val songs: List<Song>) {
    val byId: Map<Long, Song> = songs.associateBy { it.id }
    val albums: List<Album> = songs.groupBy { it.albumId }.map { (id, list) ->
        val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
        val first = sorted.first()
        Album(id, first.album, first.albumArtist.ifBlank { first.artist }, sorted.maxOf { it.year }, sorted, sorted.maxOf { it.dateAdded })
    }.sortedBy { it.title.lowercase() }
    /** Test 282 (Jarrod: playlists showing up as albums): only things that look like a real album. Songs with no album
     *  tag get their folder's name as the album, so a folder (or playlist) of mixed songs turned into a fake "album".
     *  Those stay in Tracks, Artists, Folders and Playlists, just not in Albums. */
    val realAlbums: List<Album> = albums.filter { AlbumRules.looksLikeAlbum(it) }
    val artists: List<Artist> = songs.groupBy { (it.albumArtist.ifBlank { it.artist }) }.map { (name, list) ->
        val ids = list.map { it.albumId }.toSet()
        Artist(name, realAlbums.filter { it.id in ids }.sortedByDescending { it.year }, list.sortedBy { it.title.lowercase() })
    }.sortedBy { it.name.lowercase() }
    /** Test 283 (Jarrod): the same song saved in lots of playlist folders shows once in Tracks. Copies = same name
     *  and main artist and within 3 seconds of each other in length; the best-tagged copy is the one shown. */
    val uniqueSongs: List<Song> by lazy { AlbumRules.unique(songs) }
    val recentlyAdded: List<Album> = realAlbums.sortedByDescending { it.dateAdded }.take(20)

    fun search(q: String): Triple<List<Song>, List<Album>, List<Artist>> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        return Triple(
            songs.filter { it.title.lowercase().contains(s) || it.artist.lowercase().contains(s) }.take(50),
            realAlbums.filter { it.title.lowercase().contains(s) || it.artist.lowercase().contains(s) }.take(20),
            artists.filter { it.name.lowercase().contains(s) }.take(20),
        )
    }

    companion object {
        @Volatile var cached: Library? = null

        /** Reads every song Android knows about. Runs off the main thread. */
        suspend fun load(ctx: Context, fresh: Boolean = false): Library = withContext(Dispatchers.IO) {
            cached?.takeIf { !fresh }?.let { return@withContext it }
            val out = ArrayList<Song>()
            val cols = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, "album_artist",
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA,
            )
            runCatching {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000", null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val rawTrack = c.getInt(7)
                        out += Song(
                            id = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unknown song",
                            artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                            album = c.getString(3)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                            albumId = c.getLong(4),
                            albumArtist = c.getString(5)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "",
                            durationMs = c.getLong(6),
                            track = rawTrack % 1000,
                            disc = rawTrack / 1000,
                            year = c.getInt(8),
                            dateAdded = c.getLong(9),
                            path = c.getString(10) ?: "",
                        )
                    }
                }
            }
            // songs you said aren't music (test 255) stay out of the library
            val hidden = HiddenSongs.all(ctx)
            if (hidden.isNotEmpty()) out.removeAll { it.id in hidden }
            val fixes = SongFixer.all(ctx)
            if (fixes.isNotEmpty()) for (i in out.indices) if (out[i].id in fixes) out[i] = SongFixer.apply(ctx, out[i])
            Library(out.sortedBy { it.title.lowercase() }).also { cached = it }
        }
    }
}

/** Album covers, loaded once and kept in memory (about 30 MB at most). */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    private fun key(song: Song, size: Int) = if (song.artFile.isNotEmpty()) "f${song.id}/$size" else "${song.albumId}/$size"
    fun cached(song: Song, size: Int): Bitmap? = cache.get(key(song, size))
    fun clear() { cache.evictAll(); synchronized(missing) { missing.clear() } }

    /** Whether the song has any artwork (reads the file, so call off the main thread). */
    suspend fun has(ctx: Context, song: Song) = load(ctx, song, 96) != null

    suspend fun load(ctx: Context, song: Song, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = key(song, size)
        cache.get(key)?.let { return@withContext it }
        synchronized(missing) { if (key in missing) return@withContext null }
        val bmp = runCatching {
            if (song.artFile.isNotEmpty()) BitmapFactory.decodeFile(song.artFile)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
            else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(song.uri, Size(size, size), null)
            else ctx.contentResolver.openInputStream(song.artUri)?.use { s ->
                val o = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeStream(s, null, o)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
            }
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        bmp
    }
}

/** Test 255 (Jarrod): recordings and other non-music sounds you hid from the library. id -> "title · artist". */
object HiddenSongs {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "music/hidden.json")
    @Synchronized fun all(ctx: Context): Map<Long, String> = runCatching {
        val o = org.json.JSONObject(file(ctx).readText())
        o.keys().asSequence().associate { it.toLong() to o.getString(it) }
    }.getOrDefault(emptyMap())
    @Synchronized private fun write(ctx: Context, map: Map<Long, String>) {
        val o = org.json.JSONObject(); map.forEach { (k, v) -> o.put(k.toString(), v) }
        file(ctx).apply { parentFile?.mkdirs() }.writeText(o.toString())
    }
    fun hide(ctx: Context, song: Song) = write(ctx, all(ctx) + (song.id to "${song.title} · ${song.artist}"))
    fun show(ctx: Context, id: Long) = write(ctx, all(ctx) - id)
}

object AlbumRules {
    /** "Calvin Harris feat. Rihanna" and "Calvin Harris & Dua Lipa" both count as Calvin Harris */
    fun mainArtist(a: String): String = a.lowercase().split(" feat", " ft.", " ft ", " featuring ", " & ", ", ", " x ", " with ", " vs", ";", "/")
        .first().trim()
    private val playlistLike = Regex("(?i)\\b(playlist|mix|mixtape|favou?rites|my songs|liked songs|downloads?|top \\d+)\\b")

    /**
     * Looks like a real album when the songs share an album artist (any properly tagged album, even "Various Artists"
     * compilations) or are mostly by one artist. A bunch of songs by lots of different artists with no album artist
     * is a folder or playlist, not an album.
     */
    private val noise = Regex("(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyrics?|visuali[sz]er|hq|hd|4k)[^)\\]]*[)\\]]")
    fun titleKey(t: String) = noise.replace(t, "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** artist for spotting copies: main artist, without "The" and punctuation ("The Smashing Pumpkins" = "Smashing Pumpkins") */
    private fun artistKey(a: String) = mainArtist(a).removePrefix("the ").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    fun unique(songs: List<Song>): List<Song> =
        // test 284 (Jarrod: still doubles): the same song from a different album (e.g. a Greatest Hits) can differ in length
        // by more than a few seconds, so the length no longer matters; live / remix / extended versions say so in the name
        songs.groupBy { titleKey(it.title).ifEmpty { "#" + it.id } + "|" + artistKey(it.artist) }.values.map { best(it) }

    /** the copy with the best info: fixed in Fix song info, has its own artwork, a real album artist and year, then the oldest */
    private fun best(copies: List<Song>): Song = copies.sortedWith(compareByDescending<Song> { it.fixed }
        .thenByDescending { it.artFile.isNotEmpty() }
        .thenByDescending { it.albumArtist.isNotBlank() }
        .thenByDescending { it.year > 0 }
        .thenBy { it.dateAdded }).first()

    fun looksLikeAlbum(a: Album, playlistNames: Set<String> = emptySet()): Boolean {
        val songs = a.songs
        if (songs.isEmpty() || a.title.isBlank()) return false
        val byArtist = songs.groupingBy { mainArtist(it.artist) }.eachCount()
        val sameArtist = byArtist.values.max() >= songs.size * 0.7f || byArtist.size <= 1
        if (a.title.trim().lowercase() in playlistNames && !sameArtist) return false
        if (sameArtist) return true
        // names like "#Chill~Ax" or "My Hits 2024" with lots of artists are playlists, even if a downloader gave them
        // all one album artist
        val fancy = a.title.trimStart().startsWith("#") || a.title.contains('~') || a.title.any { Character.isSurrogate(it) }
        if (fancy || playlistLike.containsMatchIn(a.title)) return false
        val albumArtists = songs.map { it.albumArtist.trim().lowercase() }.toSet()
        if (albumArtists.size == 1 && albumArtists.first().isNotEmpty()) return true
        // mixed artists, no album artist: only a real album if it has just two artists (a duet album)
        return byArtist.size <= 2
    }
}
