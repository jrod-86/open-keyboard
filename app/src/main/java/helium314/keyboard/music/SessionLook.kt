// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Test 266 (Jarrod): the phone's own pop-up player (Samsung's status bar / Now bar player) looked plain next to
 * Samsung Music's. Samsung draws that player itself; what it shows comes from our media session. It had no picture
 * (the old "albumart" link doesn't open on new Android), so it fell back to plain black with a blue wave.
 * This hands it the real cover, so it gets the cover behind it and a wave coloured to match, and adds
 * shuffle and repeat buttons like Samsung Music has.
 */
@UnstableApi
class CoverBitmapLoader(private val ctx: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader(ctx)
    private val exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = fallback.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val id = metadata.extras?.getLong(EXTRA_ID, -1L) ?: -1L
        if (id <= 0) return super.loadBitmapFromMetadata(metadata)
        val artFile = metadata.extras?.getString(EXTRA_ART).orEmpty()
        return exec.submit<Bitmap> {
            val bmp = if (artFile.isNotEmpty()) BitmapFactory.decodeFile(artFile)?.let { Bitmap.createScaledBitmap(it, SIZE, SIZE, true) }
            else if (Build.VERSION.SDK_INT >= 29) runCatching {
                ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), Size(SIZE, SIZE), null)
            }.getOrNull()
            else null
            bmp ?: throw IllegalStateException("no cover")
        }
    }

    companion object {
        const val EXTRA_ID = "ow_id"
        const val EXTRA_ART = "ow_art"
        private const val SIZE = 600
        fun extras(song: Song) = Bundle().apply { putLong(EXTRA_ID, song.id); putString(EXTRA_ART, song.artFile) }
    }
}

/** Shuffle and repeat buttons on the phone's own player and notification. */
@UnstableApi
object SessionButtons {
    private val SHUFFLE = SessionCommand("ow.shuffle", Bundle.EMPTY)
    private val REPEAT = SessionCommand("ow.repeat", Bundle.EMPTY)

    fun buttons(player: Player): List<CommandButton> {
        val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName("Shuffle").setSessionCommand(SHUFFLE).build()
        val repeat = CommandButton.Builder(when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }).setDisplayName("Repeat").setSessionCommand(REPEAT).build()
        return listOf(shuffle, repeat)
    }

    fun callback(): MediaSession.Callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SHUFFLE).add(REPEAT).build())
                .setMediaButtonPreferences(buttons(session.player))
                .build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                SHUFFLE.customAction -> p.shuffleModeEnabled = !p.shuffleModeEnabled
                REPEAT.customAction -> p.repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            session.setMediaButtonPreferences(buttons(p))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
