// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Test 307 (Jarrod): a recording or other sound turns up while you listen. Now Playing ⋮ (or a song's ⋯) > "Not music, hide it"
 * takes it out of the library and the queue, and can also mark the file itself ([NotMusicMark]) so Open Waves never counts it as
 * music again, even after a reinstall or on another phone. Each new song's file is checked once for the mark in the background.
 */
object NotMusic {
    /** the song the "Not music?" box is open for */
    val asking = mutableStateOf<Song?>(null)
    /** set by the activity: shows Android's "Allow Open Waves to modify this file?" box */
    var askWrite: ((android.content.IntentSender) -> Unit)? = null
    /** set by the activity: reload the library */
    var afterHide: (() -> Unit)? = null
    private var waitingToMark: Song? = null

    private fun checkedFile(ctx: Context) = File(ctx.filesDir, "music/mark_checked.txt")
    private fun allowedFile(ctx: Context) = File(ctx.filesDir, "music/mark_allowed.txt")
    private fun lines(f: File): MutableSet<String> = runCatching { f.readLines().filter { it.isNotBlank() }.toMutableSet() }.getOrDefault(HashSet())
    @Synchronized private fun writeLines(f: File, s: Set<String>) { runCatching { f.parentFile?.mkdirs(); f.writeText(s.joinToString("\n")) } }

    /** "Show again" on a marked song: the mark is then ignored for that file */
    @Synchronized fun allow(ctx: Context, path: String) { if (path.isNotEmpty()) writeLines(allowedFile(ctx), lines(allowedFile(ctx)) + path) }
    @Synchronized fun unallow(ctx: Context, path: String) { val s = lines(allowedFile(ctx)); if (s.remove(path)) writeLines(allowedFile(ctx), s) }

    /** Hides the song now, takes it out of the queue, and marks the file if you asked (Android may ask to allow it first). */
    fun hide(ctx: Context, song: Song, ui: PlayerUi, markFile: Boolean) {
        HiddenSongs.hide(ctx, song)
        ui.controller?.let { c -> for (i in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(i).mediaId == song.id.toString()) c.removeMediaItem(i) }
        afterHide?.invoke()
        if (!markFile) { Toast.makeText(ctx, "Hidden. Settings > Hidden songs brings it back.", Toast.LENGTH_SHORT).show(); return }
        if (Build.VERSION.SDK_INT >= 30 && !FileFixer.canManageMedia(ctx)) {
            val ask = askWrite
            if (ask != null) runCatching {
                waitingToMark = song
                ask(MediaStore.createWriteRequest(ctx.contentResolver, listOf(song.uri)).intentSender)
                return
            }
            Toast.makeText(ctx, "Hidden, but the file couldn't be marked", Toast.LENGTH_SHORT).show()
            return
        }
        markInBackground(ctx, song)
    }

    /** Android said yes (or no) to changing the file */
    fun writeAnswered(ctx: Context, ok: Boolean) {
        val s = waitingToMark ?: return
        waitingToMark = null
        if (ok) markInBackground(ctx, s)
        else Toast.makeText(ctx, "Hidden in Open Waves. The file wasn't marked.", Toast.LENGTH_SHORT).show()
    }

    private fun markInBackground(ctx: Context, song: Song) {
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val msg = when (mark(app, song)) {
                true -> "Hidden, and the file is marked as not music"
                false -> "Hidden. This kind of file can't hold the mark, so it's hidden in Open Waves only."
                null -> "Hidden, but the file couldn't be changed"
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
    }

    /** true = marked, false = this file type can't hold the mark, null = couldn't write. Copies the file out, marks and checks it, copies it back. */
    suspend fun mark(ctx: Context, song: Song): Boolean? = withContext(Dispatchers.IO) {
        // Android's own "not music" flag too, so other players skip it (Android can reset it when it re-scans; the tag stays)
        runCatching { ctx.contentResolver.update(song.uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_MUSIC, 0) }, null, null) }
        val ext = song.path.substringAfterLast('.', "mp3").lowercase()
        val tmp = File(ctx.cacheDir, "notmusic_${song.id}.$ext")
        try {
            ctx.contentResolver.openInputStream(song.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val size = tmp.length()
            try { NotMusicMark.set(tmp, true) } catch (e: UnsupportedOperationException) { return@withContext false }
            catch (e: org.jaudiotagger.audio.exceptions.CannotReadException) { return@withContext false }
            if (!NotMusicMark.has(tmp) || tmp.length() < size / 2) throw IllegalStateException("marked copy didn't check out")
            ctx.contentResolver.openOutputStream(song.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (song.path.isNotEmpty()) {
                MediaScannerConnection.scanFile(ctx, arrayOf(song.path), null, null)
                writeLines(checkedFile(ctx), lines(checkedFile(ctx)) + key(song))
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("OpenWaves", "couldn't mark ${song.path}", e)
            null
        } finally { tmp.delete() }
    }

    private fun key(s: Song) = "${s.path}|${s.dateAdded}"

    /** Looks once at each song's file for the mark; hides marked ones. Returns how many were newly hidden. */
    suspend fun check(ctx: Context, songs: List<Song>): Int = withContext(Dispatchers.IO) {
        val checked = lines(checkedFile(ctx))
        val allowed = lines(allowedFile(ctx))
        val todo = songs.filter { it.path.isNotEmpty() && key(it) !in checked }
        if (todo.isEmpty()) return@withContext 0
        var found = 0; var n = 0
        java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
        for (s in todo) {
            val f = File(s.path)
            if (s.path !in allowed && f.canRead() && NotMusicMark.has(f)) { HiddenSongs.hide(ctx, s); found++ }
            checked += key(s)
            if (++n % 100 == 0) writeLines(checkedFile(ctx), checked)
        }
        writeLines(checkedFile(ctx), checked)
        found
    }
}

@Composable
internal fun NotMusicDialog(ui: PlayerUi) {
    val ctx = LocalContext.current
    val s = NotMusic.asking.value ?: return
    var markFile by remember(s.id) { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { NotMusic.asking.value = null },
        title = { Text("Not music?") },
        text = {
            Column {
                Text("“${s.title}” will be taken out of your library, playlists and what's playing. Settings > Hidden songs brings it back.")
                Row(Modifier.padding(top = 10.dp).clickable { markFile = !markFile }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(markFile, { markFile = it }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                    Text("Also mark the file, so Open Waves never sees it as music again (even after reinstalling). The sound itself isn't changed.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
            }
        },
        confirmButton = { TextButton({ NotMusic.asking.value = null; NotMusic.hide(ctx, s, ui, markFile) }) { Text("Hide it", color = Accent) } },
        dismissButton = { TextButton({ NotMusic.asking.value = null }) { Text("Cancel") } })
}
