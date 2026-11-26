// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast

/**
 * Open Keyboard (test 230, Jarrod): press and hold an app in the app drawer for a second or two, and "Add to home
 * screen" comes up. The shortcut opens that app straight from the phone's home screen.
 */
object HomeShortcuts {
    /** App drawer tile id -> (screen it opens, its plugin switch). Only tiles with a screen of their own can be pinned. */
    val targets = mapOf(
        "chat" to ("helium314.keyboard.latin.ChatActivity" to "app_chat"),
        "text to speech" to ("helium314.keyboard.tts.TextToSpeechActivity" to "app_tts"),
        "image maker" to ("helium314.keyboard.media.ImageMakerActivity" to "app_image"),
        "sticker maker" to ("helium314.keyboard.media.StickerMakerActivity" to "app_sticker_maker"),
        "Scan text" to ("helium314.keyboard.latin.ScanTextActivity" to "scan"),
        "music" to ("helium314.keyboard.music.MusicActivity" to "app_music"),
        "converter" to ("helium314.keyboard.convert.ConverterActivity" to "app_converter"),
    )

    fun canPin(id: String) = id in targets

    fun pin(ctx: Context, id: String, label: String, icon: Drawable?) {
        // Open Waves gets a real app icon (no shortcut badge), test 245
        if (id == "music") { helium314.keyboard.music.OpenWavesIcon.add(ctx); return }
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(ctx, "Your home screen doesn't take shortcuts", Toast.LENGTH_LONG).show(); return
        }
        val open = Intent(ctx, ShortcutActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA, id)
        val info = ShortcutInfo.Builder(ctx, "tile_" + id.replace(' ', '_'))
            .setShortLabel(label)
            .setIcon(Icon.createWithAdaptiveBitmap(iconBitmap(icon)))
            .setIntent(open)
            .build()
        runCatching { sm.requestPinShortcut(info, null) }
            .onFailure { Toast.makeText(ctx, "Couldn't add it: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /** The tile's picture, dark on a light background, sized for an adaptive launcher icon. */
    private fun iconBitmap(icon: Drawable?): Bitmap {
        val size = 216 // 108 dp at 2x; the middle 66% shows
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = Color.rgb(232, 236, 250) })
        icon?.constantState?.newDrawable()?.mutate()?.let { d ->
            d.setTint(Color.rgb(44, 56, 110))
            val inset = size * 0.31f
            d.setBounds(inset.toInt(), inset.toInt(), (size - inset).toInt(), (size - inset).toInt())
            d.draw(c)
        }
        return bmp
    }

    /** Re-binds every pinned home screen shortcut (Chat, TTS, Local Image, Sticker maker, Scan text, Converter…) after APK updates & reboots. */
    @JvmStatic
    fun refreshAll(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return@runCatching
            val updated = sm.pinnedShortcuts.mapNotNull { sc ->
                if (!sc.id.startsWith("tile_")) return@mapNotNull null
                val targetId = targets.keys.firstOrNull { "tile_" + it.replace(' ', '_') == sc.id } ?: return@mapNotNull null
                val open = Intent(ctx, ShortcutActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA, targetId)
                ShortcutInfo.Builder(ctx, sc.id)
                    .setShortLabel(sc.shortLabel ?: targetId)
                    .setIntent(open)
                    .build()
            }
            if (updated.isNotEmpty()) sm.updateShortcuts(updated)
        }
    }

    const val EXTRA = "open_tile"
}

/** What a home screen shortcut opens: checks the app is still switched on, then opens it. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(HomeShortcuts.EXTRA)
        val (cls, plugin) = HomeShortcuts.targets[id] ?: run { finish(); return }
        if (!Plugins.isOn(this, plugin)) {
            Toast.makeText(this, "That app is switched off in the keyboard's Settings, Plugins", Toast.LENGTH_LONG).show()
        } else {
            runCatching {
                startActivity(Intent().setClassName(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(this, "Couldn't open it", Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}
