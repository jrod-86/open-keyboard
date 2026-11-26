// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File

/** How often and when each song was played (test 238): for Most played and Recently played. */
object PlayStats {
    private val counts = HashMap<Long, Int>()
    private val last = HashMap<Long, Long>()
    private var loaded = false
    /** changes whenever a song is counted, so screens refresh */
    val version = mutableStateOf(0)
    private fun file(ctx: Context) = File(ctx.filesDir, "music/stats.json")

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val j = JSONObject(file(ctx).readText())
            j.keys().forEach { k -> val o = j.getJSONObject(k); counts[k.toLong()] = o.optInt("n"); last[k.toLong()] = o.optLong("t") }
        }
    }

    @Synchronized fun record(ctx: Context, id: Long) {
        load(ctx)
        counts[id] = (counts[id] ?: 0) + 1; last[id] = System.currentTimeMillis()
        History.add(ctx, id)
        val j = JSONObject()
        counts.forEach { (k, n) -> j.put(k.toString(), JSONObject().put("n", n).put("t", last[k] ?: 0)) }
        runCatching { file(ctx).apply { parentFile?.mkdirs() }.writeText(j.toString()) }
        version.value++
    }

    @Synchronized fun count(id: Long) = counts[id] ?: 0
    @Synchronized fun lastPlayed(id: Long) = last[id] ?: 0L
    /** for Year in music and backups (test 311) */
    @Synchronized fun snapshot(): Map<Long, Pair<Int, Long>> = counts.mapValues { (k, n) -> n to (last[k] ?: 0L) }
    @Synchronized fun mostPlayed(lib: Library, n: Int = 100) = counts.entries.sortedByDescending { it.value }.mapNotNull { lib.byId[it.key] }.take(n)
    @Synchronized fun recentlyPlayed(lib: Library, n: Int = 100) = last.entries.sortedByDescending { it.value }.mapNotNull { lib.byId[it.key] }.take(n)
    fun recentlyAdded(lib: Library, n: Int = 200) = lib.songs.sortedByDescending { it.dateAdded }.take(n)
}

/** Delete, Share, Track details, Set as ringtone (test 238, like Samsung Music's song menu). */
object SongActions {
    /** set by the screen: asks Android to let us delete (it shows its own "Allow?" box) */
    var askDelete: ((IntentSender) -> Unit)? = null
    var afterDelete: (() -> Unit)? = null
    val details = mutableStateOf<Song?>(null)

    fun share(ctx: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val i = if (songs.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, songs[0].uri)
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(songs.map { it.uri }))
        i.type = "audio/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun delete(ctx: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val uris = songs.map { it.uri }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                askDelete?.invoke(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender)
            } else {
                uris.forEach { ctx.contentResolver.delete(it, null, null) }
                afterDelete?.invoke()
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) askDelete?.invoke(e.userAction.actionIntent.intentSender)
            else Toast.makeText(ctx, "Couldn't delete", Toast.LENGTH_SHORT).show()
        }
    }

    /** Makes the song your phone's ringtone. Android first asks you to allow Open Waves to change settings. */
    fun setAsRingtone(ctx: Context, song: Song) {
        if (!Settings.System.canWrite(ctx)) {
            Toast.makeText(ctx, "Allow Open Waves to change system settings, then try again", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val ok = runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE, song.uri) }.isSuccess
        Toast.makeText(ctx, if (ok) "“${song.title}” is now your ringtone" else "Couldn't set the ringtone", Toast.LENGTH_SHORT).show()
    }

    /** Size, type and quality of the file, for Track details. */
    fun fileInfo(ctx: Context, song: Song): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            val cols = arrayOf(MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, "bitrate", "genre")
            ctx.contentResolver.query(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id), cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getLong(0).takeIf { it > 0 }?.let { out += "Size" to "%.1f MB".format(it / 1048576.0) }
                    c.getString(1)?.let { out += "Format" to it.substringAfter('/').uppercase() }
                    runCatching { c.getInt(2) }.getOrNull()?.takeIf { it > 0 }?.let { out += "Bit rate" to "${it / 1000} kbps" }
                    runCatching { c.getString(3) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out += "Genre" to it }
                }
            }
        }
        return out
    }
}
