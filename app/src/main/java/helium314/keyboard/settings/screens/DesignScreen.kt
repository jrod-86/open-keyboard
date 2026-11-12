// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.KeySounds
import helium314.keyboard.keyboard.internal.TypingEffectsPreview
import androidx.compose.material3.OutlinedTextField
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen

/** A keyboard look: colours for the background, letter keys, special keys, the Enter key and the letters. */
data class Design(val name: String, val bg: Long, val key: Long, val special: Long, val accent: Long, val text: Long,
    val picture: Boolean = false, val dim: Float = 0.25f, val seeThrough: Boolean = true)

private val presets = listOf(
    Design("Midnight", 0xFF121212, 0xFF2A2A2A, 0xFF1C1C1C, 0xFF8AB4F8, 0xFFFFFFFF),
    Design("Snow", 0xFFF2F4F7, 0xFFFFFFFF, 0xFFDDE1E7, 0xFF1A73E8, 0xFF202124),
    Design("Bubblegum", 0xFFFCE4EC, 0xFFFFFFFF, 0xFFF8BBD0, 0xFFEC407A, 0xFF5D2A3A),
    Design("Arcade", 0xFF3355DD, 0xFFE8B98A, 0xFF6CBF6C, 0xFF6CBF6C, 0xFF2B1D0E),
    Design("Gold and black", 0xFF101010, 0xFFF3E6C8, 0xFF2A2418, 0xFFD4AF37, 0xFF1A1408),
    Design("Puppy", 0xFFE6F1F8, 0xFFFFFFFF, 0xFFFFE4E1, 0xFFFFC0CB, 0xFF2E3A44),
    Design("Lavender", 0xFFEDE7F6, 0xFFFAF7FF, 0xFFD9CCF0, 0xFF9575CD, 0xFF3A2F55),
    Design("Sky", 0xFFE3F2FD, 0xFFF7FBFF, 0xFFCFE3F7, 0xFF64B5F6, 0xFF203040),
    Design("Honey", 0xFFFFF5D6, 0xFFFFD54F, 0xFFE57373, 0xFFE53935, 0xFF3E2C00),
    Design("Cocoa", 0xFF2E211C, 0xFF5D473F, 0xFFC9A227, 0xFFC9A227, 0xFFF5E9DC),
    Design("Peach", 0xFFFFF0E6, 0xFFFFFFFF, 0xFFFFCCA8, 0xFFFF9E5E, 0xFF5A3A26),
    Design("Neon cat", 0xFF141414, 0xFF3A2F2F, 0xFF4A3B3B, 0xFFFF6FD8, 0xFFFF9BE6),
    Design("Lemon", 0xFFFFF35C, 0xFFFFFFA8, 0xFFE6DB3C, 0xFF2E7D32, 0xFF2B2B00),
    Design("Coffee cream", 0xFFF6F0E6, 0xFFFFFFFF, 0xFF3B2A20, 0xFF3B2A20, 0xFF2B1D14),
    Design("Starry night", 0xFF0B0B10, 0xFF1A1A22, 0xFF26262F, 0xFFFFE066, 0xFFFFFFFF),
    Design("Lucky red", 0xFFC62828, 0xFFD84343, 0xFFB71C1C, 0xFFFFD54F, 0xFFFFF3E0),
    Design("Matcha", 0xFF8DBF9F, 0xFFE9F3EC, 0xFFCDE5D4, 0xFF2E7D4F, 0xFF1E3326),
    Design("Graphite", 0xFF6B6E73, 0xFFA3A7AD, 0xFF8A8E94, 0xFFFFF59D, 0xFFFFFFFF),
    Design("Ember", 0xFF0F0F0F, 0xFF8E1F1F, 0xFF5A1414, 0xFFE53935, 0xFFFFE0E0),
    Design("Tennis", 0xFFD7B49E, 0xFFB5D96A, 0xFF8DBF5A, 0xFF8DBF5A, 0xFF2E3B10),
    Design("Spider", 0xFFB71C1C, 0xFFC62828, 0xFF1A2A5A, 0xFF1A2A5A, 0xFFFFFFFF),
    Design("Badminton", 0xFF1E5E2A, 0xFF2E7D32, 0xFF256D2A, 0xFFC5E1A5, 0xFFE8F5E9),
    Design("Ghost", 0xFF000000, 0xFF000000, 0xFF161616, 0xFFBDBDBD, 0xFFE0E0E0),
    Design("Ocean", 0xFF0D3B66, 0xFF1C5D99, 0xFF124A7D, 0xFF4FC3F7, 0xFFFFFFFF),
)

/** Colours to choose from in the editor. */
private val swatches = listOf(
    0xFF000000, 0xFF121212, 0xFF2A2A2A, 0xFF3C4043, 0xFF6B6E73, 0xFFA3A7AD, 0xFFDDE1E7, 0xFFF2F4F7, 0xFFFFFFFF,
    0xFF5D2A3A, 0xFFB71C1C, 0xFFE53935, 0xFFFF6F61, 0xFFFF9E5E, 0xFFFFCCA8, 0xFFFFF0E6, 0xFFFCE4EC, 0xFFF8BBD0, 0xFFEC407A, 0xFFFF6FD8,
    0xFF3A2F55, 0xFF6A1B9A, 0xFF9575CD, 0xFFD9CCF0, 0xFFEDE7F6,
    0xFF0D3B66, 0xFF1A2A5A, 0xFF3355DD, 0xFF1A73E8, 0xFF64B5F6, 0xFF8AB4F8, 0xFFCFE3F7, 0xFFE3F2FD, 0xFF4FC3F7,
    0xFF1E3326, 0xFF1E5E2A, 0xFF2E7D32, 0xFF6CBF6C, 0xFF8DBF9F, 0xFFB5D96A, 0xFFC5E1A5, 0xFFE9F3EC,
    0xFF2B1D0E, 0xFF3B2A20, 0xFF5D473F, 0xFFC9A227, 0xFFD4AF37, 0xFFE8B98A, 0xFFF3E6C8, 0xFFFFD54F, 0xFFFFF35C, 0xFFFFF5D6,
)

private fun col(v: Long) = Color(v.toInt())

private const val THEME_NAME = "Open design"
private const val PREF_DRAFT = "open_keyboard_design"

private fun Design.encode() = listOf(name, bg, key, special, accent, text, picture, dim, seeThrough).joinToString(";")
private fun decode(s: String?): Design? = runCatching {
    val p = s!!.split(";"); Design(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong(),
        p.getOrNull(6)?.toBoolean() ?: false, p.getOrNull(7)?.toFloat() ?: 0.25f, p.getOrNull(8)?.toBoolean() ?: true)
}.getOrNull()

/** The picture chosen in the designer, cropped to the keyboard's shape. */
private fun pictureFile(ctx: Context) = File(ctx.filesDir, "design_background.png")

private fun loadPicture(ctx: Context): Bitmap? = pictureFile(ctx).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }

/** Crops the middle of the picture to the keyboard's shape and keeps it. */
private fun savePicture(ctx: Context, uri: android.net.Uri): Boolean = runCatching {
    val src = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
    val targetW = 1080; val targetH = 820
    val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
    val w = (targetW / scale).toInt().coerceAtMost(src.width); val h = (targetH / scale).toInt().coerceAtMost(src.height)
    val cropped = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
    val out = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    pictureFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
}.getOrDefault(false)

/** Samsung-style keyboard designer: pick a ready-made design or your own colours, and a key sound. */
@Composable
fun DesignScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(decode(ctx.prefs().getString(PREF_DRAFT, null)) ?: presets[0]) }
    val undo = remember { mutableStateListOf<Design>() }
    val redo = remember { mutableStateListOf<Design>() }
    var tab by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var effect by remember { mutableStateOf(ctx.prefs().getString(TypingEffectsPreview.PREF, "none") ?: "none") }
    var tryText by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf(if (ctx.prefs().getBoolean(Settings.PREF_SOUND_ON, false)) KeySounds.pack(ctx) else "off") }
    fun change(d: Design) { if (d != draft) { undo.add(draft); redo.clear(); draft = d; saved = false } }
    var picture by remember { mutableStateOf(loadPicture(ctx)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && savePicture(ctx, uri)) {
            picture = loadPicture(ctx)
            change(draft.copy(picture = true))
            tab = 1
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = "Design", settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            KeyboardPreview(draft, Modifier.fillMaxWidth(), big = true, picture = if (draft.picture) picture else null)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = undo.isNotEmpty(), onClick = { redo.add(draft); draft = undo.removeAt(undo.lastIndex); saved = false }) { Text("↶") }
                OutlinedButton(enabled = redo.isNotEmpty(), onClick = { undo.add(draft); draft = redo.removeAt(redo.lastIndex); saved = false }) { Text("↷") }
                OutlinedButton(onClick = { change(presets[0]) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { applyDesign(ctx, draft); saved = true }) { Text(if (saved) "Saved ✓" else "Save") }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Designs", "Colours", "Effects", "Sound").forEachIndexed { i, t -> Pill(t, tab == i) { tab = i } }
            }
            when (tab) {
                0 -> presets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        row.forEach { d ->
                            Column(Modifier.weight(1f).clickable { change(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                KeyboardPreview(d, Modifier.fillMaxWidth(), selected = d == draft)
                                Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                1 -> {
                    Text("Background picture", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { pick.launch("image/*") }) { Text(if (draft.picture) "Change picture" else "Choose a picture") }
                        if (draft.picture) OutlinedButton(onClick = { change(draft.copy(picture = false)) }) { Text("Remove") }
                    }
                    if (draft.picture) {
                        Text("Darken the picture", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                        Slider(value = draft.dim, valueRange = 0f..0.7f, onValueChange = { draft = draft.copy(dim = it); saved = false },
                            onValueChangeFinished = { undo.add(draft) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("See-through keys", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = draft.seeThrough, onCheckedChange = { change(draft.copy(seeThrough = it)) })
                        }
                    }
                    ColourRow(if (draft.picture) "Background (behind the picture's edges)" else "Background", draft.bg) { change(draft.copy(name = "My design", bg = it)) }
                    ColourRow("Letter keys", draft.key) { change(draft.copy(name = "My design", key = it)) }
                    ColourRow("Special keys (shift, delete, ?123)", draft.special) { change(draft.copy(name = "My design", special = it)) }
                    ColourRow("Enter key and highlights", draft.accent) { change(draft.copy(name = "My design", accent = it)) }
                    ColourRow("Letters", draft.text) { change(draft.copy(name = "My design", text = it)) }
                }
                2 -> {
                    Text("Typing effect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    Text("Something fun pops out of each key you press. Pick one, then type in the box below to try it.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(value = tryText, onValueChange = { tryText = it }, label = { Text("Try it here") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    TypingEffectsPreview.effects.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (id, info) ->
                                val (label, symbols) = info
                                val icon = symbols.firstOrNull() ?: when (id) { "bubbles" -> "◯"; "confetti" -> "🎉"; "ripple" -> "◎"; else -> "🚫" }
                                Row(Modifier.weight(1f).padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (effect == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { effect = id; ctx.prefs().edit { putString(TypingEffectsPreview.PREF, id) } }
                                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(icon, fontSize = 22.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                else -> {
                    Text("Key sound", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    (listOf("off" to "No sound") + KeySounds.packs).forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            sound = id
                            ctx.prefs().edit {
                                putBoolean(Settings.PREF_SOUND_ON, id != "off")
                                if (id != "off") putString(KeySounds.PREF, id)
                            }
                            if (id != "off") KeySounds.play(ctx, KeySounds.Kind.STANDARD, -1f, id)
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sound == id, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text("Tap a sound to hear it. It's used straight away.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50))
        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColourRow(label: String, current: Long, onPick: (Long) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { s ->
            Box(Modifier.size(36.dp).clip(CircleShape).background(col(s))
                .border(if (s == current) 3.dp else 1.dp, if (s == current) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                .clickable { onPick(s) })
        }
    }
}

/** A small drawing of the keyboard in the given colours. */
@Composable
fun KeyboardPreview(d: Design, modifier: Modifier, big: Boolean = false, selected: Boolean = false, picture: Bitmap? = null) {
    if (picture != null) {
        val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
        Box(modifier.clip(shape)) {
            Image(picture.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = d.dim)))
            KeyboardPreview(d.copy(bg = 0x00000000, key = keyColor(d.key, d), special = keyColor(d.special, d)), Modifier.fillMaxWidth(), big, selected)
        }
        return
    }
    val gap: Dp = if (big) 4.dp else 2.dp
    val keyH: Dp = if (big) 38.dp else 18.dp
    val font = if (big) 15.sp else 8.sp
    val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
    Column(modifier.clip(shape).background(col(d.bg))
        .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
        .padding(if (big) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        @Composable
        fun key(label: String, weight: Float, color: Long, textColor: Long = d.text) {
            Box(Modifier.height(keyH).clip(RoundedCornerShape(if (big) 8.dp else 4.dp)).background(col(color))
                .then(Modifier.fillMaxWidth(weight)), contentAlignment = Alignment.Center) {
                Text(label, color = col(textColor), fontSize = font, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        @Composable
        fun row(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
        row { "qwertyuiop".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), 1f, d.key) } } }
        row {
            Spacer(Modifier.weight(0.5f))
            "asdfghjkl".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), 1f, d.key) } }
            Spacer(Modifier.weight(0.5f))
        }
        row {
            Box(Modifier.weight(1.5f)) { key("⇧", 1f, d.special) }
            "zxcvbnm".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), 1f, d.key) } }
            Box(Modifier.weight(1.5f)) { key("⌫", 1f, d.special) }
        }
        row {
            Box(Modifier.weight(1.5f)) { key("?123", 1f, d.special) }
            Box(Modifier.weight(1f)) { key("☺", 1f, d.special) }
            Box(Modifier.weight(1f)) { key(",", 1f, d.key) }
            Box(Modifier.weight(4f)) { key("", 1f, d.key) }
            Box(Modifier.weight(1f)) { key(".", 1f, d.key) }
            Box(Modifier.weight(1.5f)) { key("↵", 1f, d.accent, readableOn(d.accent)) }
        }
    }
}

private fun keyColor(c: Long, d: Design): Long = if (d.picture && d.seeThrough) (0xA0L shl 24) or (c and 0xFFFFFF) else c

private fun readableOn(c: Long): Long {
    val v = c.toInt()
    val lum = (0.299 * ((v shr 16) and 255) + 0.587 * ((v shr 8) and 255) + 0.114 * (v and 255)) / 255
    return if (lum > 0.6) 0xFF202124 else 0xFFFFFFFF
}

private fun withAlpha(c: Long, a: Int) = ((a.toLong() shl 24) or (c and 0xFFFFFF)).toInt()

/** Saves the design as the keyboard's own colour theme and switches to it. */
fun applyDesign(ctx: Context, d: Design) {
    val prefs = ctx.prefs()
    val colors = listOf(
        ColorSetting(KeyboardTheme.COLOR_BACKGROUND, false, d.bg.toInt()),
        ColorSetting(KeyboardTheme.COLOR_KEYS, false, keyColor(d.key, d).toInt()),
        ColorSetting(KeyboardTheme.COLOR_FUNCTIONAL_KEYS, false, keyColor(d.special, d).toInt()),
        ColorSetting(KeyboardTheme.COLOR_SPACEBAR, false, keyColor(d.key, d).toInt()),
        ColorSetting(KeyboardTheme.COLOR_ACCENT, false, d.accent.toInt()),
        ColorSetting(KeyboardTheme.COLOR_GESTURE, false, d.accent.toInt()),
        ColorSetting(KeyboardTheme.COLOR_TEXT, false, d.text.toInt()),
        ColorSetting(KeyboardTheme.COLOR_SUGGESTION_TEXT, false, d.text.toInt()),
        ColorSetting(KeyboardTheme.COLOR_SPACEBAR_TEXT, false, withAlpha(d.text, 0x99)),
        ColorSetting(KeyboardTheme.COLOR_HINT_TEXT, false, withAlpha(d.text, 0x88)),
    )
    KeyboardTheme.writeUserColors(prefs, THEME_NAME, colors)
    KeyboardTheme.writeUserMoreColors(prefs, THEME_NAME, 0)
    for (n in listOf(false, true)) for (l in listOf(false, true)) Settings.getCustomBackgroundFile(ctx, n, l).delete()
    val pic = if (d.picture) loadPicture(ctx) else null
    if (pic != null) {
        val out = pic.copy(Bitmap.Config.ARGB_8888, true)
        android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((d.dim * 255).toInt(), 0, 0, 0))
        for (night in listOf(false, true))
            Settings.getCustomBackgroundFile(ctx, night, false).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    prefs.edit {
        putString(PREF_DRAFT, d.encode())
        putString(Settings.PREF_THEME_COLORS, THEME_NAME)
        putString(Settings.PREF_THEME_COLORS_NIGHT, THEME_NAME)
        putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
        putBoolean(Settings.PREF_THEME_KEY_BORDERS, true)
        putString("open_keyboard_theme_tile", "design")
    }
    Settings.clearCachedBackgroundImages()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}
