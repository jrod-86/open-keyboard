// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Test 256 (Jarrod): "we want it to permanently fix the song". After you agree, the fixed name, artist, album,
 * year and cover are written into the music file itself. Each song is copied out, edited with [TagWriter],
 * checked, and only then copied back over the original, then Android is told to re-read it.
 */
object FileFixer {
    /** What to write. Blank text / 0 year / null cover = leave that part of the file alone. */
    class Item(val song: Song, val title: String, val artist: String, val album: String, val year: Int, val cover: String?)

    /** Android's "Allow Open Waves to modify these files?" request (Android 11+), or null if not needed. */
    fun writeRequest(ctx: Context, items: List<Item>): android.app.PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(ctx.contentResolver, items.map { it.song.uri }) else null

    /** With "manage media" allowed (Android 12+), Android stops asking each time. */
    fun canManageMedia(ctx: Context) = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)
    fun manageMediaIntent(ctx: Context) = android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
        android.net.Uri.parse("package:" + ctx.packageName))

    /** Writes every item; returns how many files were changed and the names that failed. */
    suspend fun writeAll(ctx: Context, items: List<Item>): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        var ok = 0
        val failed = ArrayList<String>()
        val scanned = ArrayList<String>()
        for (it in items) {
            val ext = it.song.path.substringAfterLast('.', "mp3").lowercase()
            val tmp = File(ctx.cacheDir, "tagfix_${it.song.id}.$ext")
            try {
                ctx.contentResolver.openInputStream(it.song.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val size = tmp.length()
                TagWriter.write(tmp, it.title, it.artist, it.album, it.year, it.cover?.let(::File))
                // make sure the edited copy still opens before it replaces the original
                org.jaudiotagger.audio.AudioFileIO.read(tmp)
                if (tmp.length() < size / 2) throw IllegalStateException("edited file looks too small")
                ctx.contentResolver.openOutputStream(it.song.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ok++
                if (it.song.path.isNotEmpty()) scanned += it.song.path
            } catch (e: Exception) {
                android.util.Log.w("OpenWaves", "couldn't write tags to ${it.song.path}", e)
                failed += it.song.title
            } finally {
                tmp.delete()
            }
        }
        if (scanned.isNotEmpty()) MediaScannerConnection.scanFile(ctx, scanned.toTypedArray(), null, null)
        ok to failed
    }
}
