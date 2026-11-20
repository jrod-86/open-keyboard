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
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId)

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
    val artists: List<Artist> = songs.groupBy { (it.albumArtist.ifBlank { it.artist }) }.map { (name, list) ->
        val ids = list.map { it.albumId }.toSet()
        Artist(name, albums.filter { it.id in ids }.sortedByDescending { it.year }, list.sortedBy { it.title.lowercase() })
    }.sortedBy { it.name.lowercase() }
    val recentlyAdded: List<Album> = albums.sortedByDescending { it.dateAdded }.take(20)

    fun search(q: String): Triple<List<Song>, List<Album>, List<Artist>> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        return Triple(
            songs.filter { it.title.lowercase().contains(s) || it.artist.lowercase().contains(s) }.take(50),
            albums.filter { it.title.lowercase().contains(s) || it.artist.lowercase().contains(s) }.take(20),
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

    fun cached(song: Song, size: Int): Bitmap? = cache.get("${song.albumId}/$size")

    suspend fun load(ctx: Context, song: Song, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${song.albumId}/$size"
        cache.get(key)?.let { return@withContext it }
        synchronized(missing) { if (key in missing) return@withContext null }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(song.uri, Size(size, size), null)
            else ctx.contentResolver.openInputStream(song.artUri)?.use { s ->
                val o = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeStream(s, null, o)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
            }
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        bmp
    }
}
