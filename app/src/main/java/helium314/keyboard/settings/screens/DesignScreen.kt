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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import helium314.keyboard.latin.common.ColorType
import java.util.EnumMap
import helium314.keyboard.latin.KeySounds
import helium314.keyboard.keyboard.internal.TypingEffectsPreview
import helium314.keyboard.keyboard.internal.KeyEffects
import helium314.keyboard.keyboard.internal.KeyEffectDemoView
import androidx.compose.material3.OutlinedTextField
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen

/** A keyboard look: colours for the background, letter keys, special keys, the Enter key and the letters. */
data class Design(val name: String, val bg: Long, val key: Long, val special: Long, val accent: Long, val text: Long,
    val picture: Boolean = false, val dim: Float = 0.25f, val seeThrough: Boolean = true,
    /** colours for single parts of the keyboard (part id to colour), on top of the five main colours */
    val parts: Map<String, Long> = emptyMap())

/** A part of the keyboard that can get its own colour. The first five are the design's main colours. */
private class Part(val id: String, val label: String, val types: List<ColorType>)

private val partList = listOf(
    Part("bg", "Background", emptyList()),
    Part("keys", "Letter keys", emptyList()),
    Part("special", "Special keys", emptyList()),
    Part("letters", "Letters", emptyList()),
    Part("accent", "Highlights", emptyList()),
    Part("space", "Space bar", listOf(ColorType.SPACE_BAR_BACKGROUND)),
    Part("spaceText", "Space bar text", listOf(ColorType.SPACE_BAR_TEXT)),
    Part("enter", "Enter key", listOf(ColorType.ACTION_KEY_BACKGROUND, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND)),
    Part("enterIcon", "Enter arrow", listOf(ColorType.ACTION_KEY_ICON)),
    Part("specialText", "Special key symbols", listOf(ColorType.FUNCTIONAL_KEY_TEXT, ColorType.KEY_ICON, ColorType.SHIFT_KEY_ICON)),
    Part("hints", "Small numbers and symbols", listOf(ColorType.KEY_HINT_TEXT)),
    Part("toolbar", "Top bar", listOf(ColorType.STRIP_BACKGROUND)),
    Part("toolbarIcons", "Top bar icons", listOf(ColorType.TOOL_BAR_KEY, ColorType.TOOL_BAR_EXPAND_KEY)),
    Part("suggestions", "Word suggestions", listOf(ColorType.SUGGESTED_WORD, ColorType.SUGGESTION_TYPED_WORD,
        ColorType.SUGGESTION_VALID_WORD, ColorType.SUGGESTION_AUTO_CORRECT)),
    Part("popup", "Key pop-up", listOf(ColorType.KEY_PREVIEW_BACKGROUND)),
    Part("popupText", "Key pop-up letter", listOf(ColorType.KEY_PREVIEW_TEXT)),
    Part("longpress", "Long-press menu", listOf(ColorType.POPUP_KEYS_BACKGROUND)),
    Part("trail", "Swipe trail", listOf(ColorType.GESTURE_TRAIL)),
)

/** The colour a part shows in the editor: its own if set, otherwise what it gets from the main colours. */
private fun Design.partColor(id: String): Long = when (id) {
    "bg" -> bg; "keys" -> key; "special" -> special; "letters" -> text; "accent" -> accent
    else -> parts[id] ?: when (id) {
        "space" -> key; "spaceText", "suggestions", "specialText", "toolbarIcons", "popupText" -> text
        "hints" -> withAlpha(text, 0x88).toLong() and 0xFFFFFFFFL
        "enter", "trail" -> accent; "enterIcon" -> readableOn(accent)
        "toolbar" -> bg; "popup", "longpress" -> key
        else -> text
    }
}

private fun Design.withPart(id: String, c: Long?): Design = when (id) {
    "bg" -> copy(name = "My design", bg = c ?: bg); "keys" -> copy(name = "My design", key = c ?: key)
    "special" -> copy(name = "My design", special = c ?: special); "letters" -> copy(name = "My design", text = c ?: text)
    "accent" -> copy(name = "My design", accent = c ?: accent)
    else -> copy(name = "My design", parts = if (c == null) parts - id else parts + (id to c))
}

/** Used for Reset and when nothing has been designed yet. */
private val defaultDesign = Design("Classic dark", 0xFF121212, 0xFF2A2A2A, 0xFF1C1C1C, 0xFF8AB4F8, 0xFFFFFFFF)

private val presets = listOf(
    Design("Bubblegum", 0xFFFCE4EC, 0xFFFFFFFF, 0xFFF8BBD0, 0xFFEC407A, 0xFF5D2A3A),
    Design("Arcade", 0xFF3355DD, 0xFFE8B98A, 0xFF6CBF6C, 0xFF6CBF6C, 0xFF2B1D0E),
    Design("Gold and black", 0xFF101010, 0xFFF3E6C8, 0xFF2A2418, 0xFFD4AF37, 0xFF1A1408),
    Design("Lavender", 0xFFEDE7F6, 0xFFFAF7FF, 0xFFD9CCF0, 0xFF9575CD, 0xFF3A2F55),
    Design("Sky", 0xFFE3F2FD, 0xFFF7FBFF, 0xFFCFE3F7, 0xFF64B5F6, 0xFF203040),
    Design("Honey", 0xFFFFF5D6, 0xFFFFD54F, 0xFFE57373, 0xFFE53935, 0xFF3E2C00),
    Design("Peach", 0xFFFFF0E6, 0xFFFFFFFF, 0xFFFFCCA8, 0xFFFF9E5E, 0xFF5A3A26),
    Design("Neon cat", 0xFF141414, 0xFF3A2F2F, 0xFF4A3B3B, 0xFFFF6FD8, 0xFFFF9BE6),
    Design("Lemon", 0xFFFFF35C, 0xFFFFFFA8, 0xFFE6DB3C, 0xFF2E7D32, 0xFF2B2B00),
    Design("Matcha", 0xFF8DBF9F, 0xFFE9F3EC, 0xFFCDE5D4, 0xFF2E7D4F, 0xFF1E3326),
    Design("Ember", 0xFF0F0F0F, 0xFF8E1F1F, 0xFF5A1414, 0xFFE53935, 0xFFFFE0E0),
    Design("Tennis", 0xFFD7B49E, 0xFFB5D96A, 0xFF8DBF5A, 0xFF8DBF5A, 0xFF2E3B10),
    Design("Mint choc chip", 0xFFBDEFD8, 0xFFFFFFFF, 0xFF6B4A3A, 0xFF4E342E, 0xFF3B2A20, parts = mapOf("space" to 0xFFA8E6CF, "toolbar" to 0xFF98DDBF, "specialText" to 0xFFFFFFFF)),
    Design("Sunset", 0xFFFF9A62, 0xFFFFE3C7, 0xFFFF6F61, 0xFF8E3B8E, 0xFF4A1E2E, parts = mapOf("space" to 0xFFFFC15E, "toolbar" to 0xFFFF7A59)),
    Design("Cotton candy", 0xFFCDE7FF, 0xFFFFFFFF, 0xFFFFC6E5, 0xFFFF7EB9, 0xFF4A3A66, parts = mapOf("space" to 0xFFFFE3F1, "toolbar" to 0xFFFFD6EC)),
    Design("Grape soda", 0xFF5B2A86, 0xFF7A43A8, 0xFF4A1F70, 0xFFFFC94D, 0xFFFFFFFF, parts = mapOf("space" to 0xFF9C6ADE, "toolbar" to 0xFF4A1F70)),
    Design("Watermelon", 0xFF7BC67B, 0xFFFF8A8A, 0xFFFFFFFF, 0xFF2E7D32, 0xFF3A1A1A, parts = mapOf("space" to 0xFFFFFFFF, "toolbar" to 0xFF5DAE5D)),
    Design("Lagoon", 0xFF2EC4B6, 0xFFE8FFFB, 0xFFA6F0E6, 0xFFFF9F1C, 0xFF0B4A45, parts = mapOf("space" to 0xFFFFD166, "toolbar" to 0xFF20A39E)),
    Design("Sorbet", 0xFFFFD6E0, 0xFFFFF4E6, 0xFFFFB3C7, 0xFFFF8C42, 0xFF5A2A3A, parts = mapOf("space" to 0xFFC7F0D8, "toolbar" to 0xFFFFC2D4)),
    Design("Blueberry", 0xFF2F3E9E, 0xFFE8EAFF, 0xFF8C9EFF, 0xFFFF6F91, 0xFF1A2160, parts = mapOf("space" to 0xFFB39DFF, "toolbar" to 0xFF26337F, "toolbarIcons" to 0xFFE8EAFF)),
    Design("Banana split", 0xFFFFF3B0, 0xFFFFFFFF, 0xFFF6C1D1, 0xFF8B5A3C, 0xFF5A3A1A, parts = mapOf("space" to 0xFFFFE08A, "toolbar" to 0xFFF6C1D1)),
    Design("Flamingo", 0xFFFF7EB3, 0xFFFFE3EE, 0xFFFFB3D1, 0xFF2EC4B6, 0xFF7A1E48, parts = mapOf("space" to 0xFFFFFFFF, "toolbar" to 0xFFFF5C9E)),
    Design("Mango", 0xFFFFB347, 0xFFFFF1D6, 0xFFFFD27F, 0xFFE8590C, 0xFF5A2E00, parts = mapOf("space" to 0xFF8BD17C, "toolbar" to 0xFFFF9F1C)),
    Design("Kiwi", 0xFF8BC34A, 0xFFF1F8E0, 0xFF6D4C41, 0xFF6D4C41, 0xFF2E3B10, parts = mapOf("space" to 0xFFDCE775, "toolbar" to 0xFF7CB342, "specialText" to 0xFFFFFFFF)),
    Design("Cherry blossom", 0xFFFFE4EC, 0xFFFFFFFF, 0xFFF8C8D8, 0xFFD81B60, 0xFF6D2840, parts = mapOf("space" to 0xFFC8E6C9, "toolbar" to 0xFFF8BBD0)),
    Design("Aurora", 0xFF0F2A3D, 0xFF1B4D5C, 0xFF2A3F7A, 0xFF6BFFB8, 0xFFE6FFF5, parts = mapOf("space" to 0xFF2E8A7A, "toolbar" to 0xFF3B2A6B)),
    Design("Neon lime", 0xFF121212, 0xFF1E2A12, 0xFF2A3A18, 0xFFC6FF00, 0xFFC6FF00, parts = mapOf("space" to 0xFF00E5FF, "toolbar" to 0xFF1E2A12, "spaceText" to 0xFF121212)),
    Design("Electric blue", 0xFF0A0F2C, 0xFF13205A, 0xFF1C2E7A, 0xFF00E5FF, 0xFF9CF6FF, parts = mapOf("space" to 0xFFFF4FD8, "toolbar" to 0xFF13205A, "spaceText" to 0xFFFFFFFF)),
    Design("Candy cane", 0xFFFFEBEE, 0xFFFFFFFF, 0xFFE53935, 0xFF2E7D32, 0xFFB71C1C, parts = mapOf("space" to 0xFFC8E6C9, "toolbar" to 0xFFFFCDD2, "specialText" to 0xFFFFFFFF)),
    Design("Rainbow sherbet", 0xFFFFF0F5, 0xFFFFFFFF, 0xFFB3E5FC, 0xFFFFB74D, 0xFF5E35B1, parts = mapOf("space" to 0xFFFFF59D, "toolbar" to 0xFFC8E6C9)),
    Design("Pistachio", 0xFFDCEDC8, 0xFFF9FBE7, 0xFFAED581, 0xFF689F38, 0xFF33691E, parts = mapOf("space" to 0xFFF8BBD0, "toolbar" to 0xFFC5E1A5)),
    Design("Coral reef", 0xFFFF7F50, 0xFFFFF0E8, 0xFF40C4C4, 0xFF00838F, 0xFF7A2E14, parts = mapOf("space" to 0xFFFFD166, "toolbar" to 0xFFFF6A3D)),
    Design("Royal", 0xFF311B92, 0xFF4527A0, 0xFF1A0F5C, 0xFFFFD54F, 0xFFFFF8E1, parts = mapOf("space" to 0xFF7E57C2, "toolbar" to 0xFF1A0F5C)),
    Design("Strawberry milk", 0xFFFFCDD2, 0xFFFFF5F7, 0xFFFF8A9A, 0xFFE91E63, 0xFF6A1B3A, parts = mapOf("space" to 0xFFFFFFFF, "toolbar" to 0xFFFFB3BF)),
    Design("Blue raspberry", 0xFF1E88E5, 0xFFE3F2FD, 0xFF90CAF9, 0xFFFF4081, 0xFF0D47A1, parts = mapOf("space" to 0xFFFFF176, "toolbar" to 0xFF1976D2, "toolbarIcons" to 0xFFFFFFFF)),
    Design("Seafoam", 0xFFB2DFDB, 0xFFF0FFFC, 0xFF80CBC4, 0xFF00897B, 0xFF004D40, parts = mapOf("space" to 0xFFFFCCBC, "toolbar" to 0xFFA0D6D0)),
    Design("Sunflower", 0xFFFFC107, 0xFFFFF8E1, 0xFF6D4C41, 0xFF6D4C41, 0xFF3E2723, parts = mapOf("space" to 0xFFAED581, "toolbar" to 0xFFFFB300, "specialText" to 0xFFFFFFFF)),
    Design("Hot pink", 0xFF1A1A1A, 0xFFFF2E93, 0xFFB0005E, 0xFFFFFFFF, 0xFFFFFFFF, parts = mapOf("space" to 0xFF7B2FF7, "toolbar" to 0xFF2A0A1C)),
    Design("Pastel dream", 0xFFE1F5FE, 0xFFFFFFFF, 0xFFF3E5F5, 0xFFB39DDB, 0xFF5C6BC0, parts = mapOf("space" to 0xFFFFF9C4, "toolbar" to 0xFFFCE4EC)),
    Design("Retro wave", 0xFF2B1055, 0xFF3D1A78, 0xFF7B2FF7, 0xFFFF6EC7, 0xFFFFE3F6, parts = mapOf("space" to 0xFF00D1FF, "toolbar" to 0xFF1E0B3F, "spaceText" to 0xFF2B1055)),
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

private fun Design.encode() = listOf(name, bg, key, special, accent, text, picture, dim, seeThrough,
    parts.entries.joinToString(",") { "${it.key}=${it.value}" }).joinToString(";")
private fun decode(s: String?): Design? = runCatching {
    val p = s!!.split(";"); Design(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong(),
        p.getOrNull(6)?.toBoolean() ?: false, p.getOrNull(7)?.toFloat() ?: 0.25f, p.getOrNull(8)?.toBoolean() ?: true,
        p.getOrNull(9).orEmpty().split(",").mapNotNull { e -> e.split("=").takeIf { it.size == 2 }?.let { (k, v) -> v.toLongOrNull()?.let { k to it } } }.toMap())
}.getOrNull()

private const val PREF_MY_DESIGNS = "open_keyboard_my_designs"

private fun loadMyDesigns(ctx: Context): List<Design> =
    ctx.prefs().getString(PREF_MY_DESIGNS, "").orEmpty().split("\n").mapNotNull { decode(it) }

private fun storeMyDesigns(ctx: Context, list: List<Design>) =
    ctx.prefs().edit { putString(PREF_MY_DESIGNS, list.joinToString("\n") { it.encode() }) }

private fun cleanName(n: String) = n.replace(Regex("[;,=\\n]"), " ").trim().take(30).ifEmpty { "My design" }

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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(decode(ctx.prefs().getString(PREF_DRAFT, null)) ?: defaultDesign) }
    val undo = remember { mutableStateListOf<Design>() }
    val redo = remember { mutableStateListOf<Design>() }
    var tab by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var effect by remember { mutableStateOf(ctx.prefs().getString(TypingEffectsPreview.PREF, "none") ?: "none") }
    var tryText by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf(if (ctx.prefs().getBoolean(Settings.PREF_SOUND_ON, false)) KeySounds.pack(ctx) else "off") }
    var part by remember { mutableStateOf("keys") }
    var fxGroup by remember { mutableStateOf(0) }
    var fxChoice by remember { mutableStateOf(mapOf(
        "around" to (ctx.prefs().getString(KeyEffects.PREF_AROUND, "none") ?: "none"),
        "color" to (ctx.prefs().getString(KeyEffects.PREF_COLOR, "none") ?: "none"),
        "motion" to (ctx.prefs().getString(KeyEffects.PREF_MOTION, "none") ?: "none"))) }
    var myDesigns by remember { mutableStateOf(loadMyDesigns(ctx)) }
    var naming by remember { mutableStateOf(false) }
    fun change(d: Design) { if (d != draft) { undo.add(draft); redo.clear(); draft = d; saved = false } }
    var picture by remember { mutableStateOf(loadPicture(ctx)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && savePicture(ctx, uri)) {
            picture = loadPicture(ctx)
            change(draft.copy(picture = true))
            tab = 1
        }
    }

    if (naming) {
        var name by remember { mutableStateOf(if (draft.name in presets.map { it.name } || draft.name == defaultDesign.name) "My design" else draft.name) }
        AlertDialog(onDismissRequest = { naming = false },
            title = { Text("Save your design") },
            text = { Column {
                Text("It goes into My designs on the Designs tab and becomes your keyboard's look.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            } },
            confirmButton = { Button(onClick = {
                val d = draft.copy(name = cleanName(name))
                myDesigns = myDesigns.filter { it.name != d.name } + d
                storeMyDesigns(ctx, myDesigns)
                draft = d; applyDesign(ctx, d); saved = true; naming = false
            }) { Text("Save") } },
            dismissButton = { OutlinedButton(onClick = { applyDesign(ctx, draft); saved = true; naming = false }) { Text("Just use it") } })
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = "Design", settings = emptyList()) {
        // the preview, buttons and tabs stay put at the top; only the choices below scroll
        // while the keyboard is open (trying an effect), make room: drop the preview and buttons and sit above the keyboard
        val imeOpen = WindowInsets.isImeVisible
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!imeOpen) KeyboardPreview(draft, Modifier.fillMaxWidth(), big = true, picture = if (draft.picture) picture else null,
                selectedPart = if (tab == 1) part else null, onPart = { part = it; tab = 1 })
            if (!imeOpen) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = undo.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp),
                    onClick = { redo.add(draft); draft = undo.removeAt(undo.lastIndex); saved = false }) { Text("↶", fontSize = 20.sp) }
                OutlinedButton(enabled = redo.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp),
                    onClick = { undo.add(draft); draft = redo.removeAt(redo.lastIndex); saved = false }) { Text("↷", fontSize = 20.sp) }
                OutlinedButton(onClick = { change(defaultDesign) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { naming = true }) { Text(if (saved) "Saved ✓" else "Save") }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Designs", "Colours", "Effects", "Sound").forEachIndexed { i, t -> Pill(t, tab == i, Modifier.weight(1f)) { tab = i } }
            }
            if (tab == 2) OutlinedTextField(value = tryText, onValueChange = { tryText = it }, label = { Text("Try it here") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            when (tab) {
                0 -> {
                if (myDesigns.isNotEmpty()) {
                    Text("My designs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    myDesigns.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                            row.forEach { d ->
                                Column(Modifier.weight(1f).clickable { change(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    KeyboardPreview(d, Modifier.fillMaxWidth(), selected = d == draft,
                                        picture = if (d.picture && d == draft) picture else null)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp).weight(1f, fill = false))
                                        Text("  ✕", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp).clickable {
                                            myDesigns = myDesigns - d; storeMyDesigns(ctx, myDesigns)
                                        })
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("Ready-made", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                }
                presets.chunked(2).forEach { row ->
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
                    Text("Colour any part", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text("Tap a part of the keyboard above, or pick one here, then choose its colour.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        partList.forEach { pt ->
                            val custom = pt.id in draft.parts
                            Text((if (custom) "● " else "") + pt.label, fontSize = 14.sp, maxLines = 1,
                                color = if (part == pt.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (part == pt.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { part = pt.id }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    val current = draft.partColor(part)
                    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(col(current)).border(1.dp, Color.Gray, CircleShape))
                        Text(partList.first { it.id == part }.label, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (part in draft.parts) OutlinedButton(onClick = { change(draft.withPart(part, null)) }) { Text("Match the theme") }
                    }
                    swatches.chunked(8).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                Box(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(col(c))
                                    .border(if (c == current) 3.dp else 1.dp, if (c == current) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                    .clickable { change(draft.withPart(part, c)) })
                            }
                            repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                2 -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Around", "Key colour", "Key motion", "Pop-outs").forEachIndexed { i, t ->
                            Pill(t, fxGroup == i, Modifier.weight(1f)) { fxGroup = i }
                        }
                    }
                    if (fxGroup < 3) {
                        val (kind, pref, list) = when (fxGroup) {
                            0 -> Triple("around", KeyEffects.PREF_AROUND, KeyEffects.around)
                            1 -> Triple("color", KeyEffects.PREF_COLOR, KeyEffects.colors)
                            else -> Triple("motion", KeyEffects.PREF_MOTION, KeyEffects.motions)
                        }
                        Text(when (fxGroup) {
                            0 -> "Colour spills out around each key you press."
                            1 -> "The key you press lights up with colour."
                            else -> "The key you press moves."
                        } + " You can mix one of each. Type in the box above to try them.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                        val chosen = fxChoice[kind] ?: "none"
                        list.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (id, label) ->
                                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                        .background(if (chosen == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                        .border(if (chosen == id) 2.dp else 0.dp, if (chosen == id) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                                        .clickable { fxChoice = fxChoice + (kind to id); ctx.prefs().edit { putString(pref, id) } }
                                        .padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        androidx.compose.ui.viewinterop.AndroidView(factory = { KeyEffectDemoView(it) },
                                            modifier = Modifier.fillMaxWidth().height(84.dp), update = { v ->
                                                v.kind = kind; v.effectId = id
                                                v.keyColor = (if ((draft.key and 0xFFFFFF) == (draft.bg and 0xFFFFFF)) draft.special else draft.key).toInt()
                                                v.textColor = draft.text.toInt(); v.invalidate()
                                            })
                                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                    Text("Something fun pops out of each key you press.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
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
}

@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50))
        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 1, softWrap = false, fontSize = 14.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A small drawing of the keyboard in the given colours. With [onPart], tapping a part picks it for colouring. */
@Composable
fun KeyboardPreview(d: Design, modifier: Modifier, big: Boolean = false, selected: Boolean = false, picture: Bitmap? = null,
                    selectedPart: String? = null, onPart: ((String) -> Unit)? = null) {
    if (picture != null) {
        val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
        Box(modifier.clip(shape)) {
            Image(picture.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = d.dim)))
            KeyboardPreview(d.copy(bg = 0x00000000, key = keyColor(d.key, d), special = keyColor(d.special, d),
                parts = d.parts.mapValues { (k, v) -> if (k == "space" || k == "toolbar") keyColor(v, d) else v } +
                    (if ("toolbar" !in d.parts) mapOf("toolbar" to 0L) else emptyMap())),
                Modifier.fillMaxWidth(), big, selected, null, selectedPart, onPart)
        }
        return
    }
    val gap: Dp = if (big) 4.dp else 2.dp
    val keyH: Dp = if (big) 38.dp else 18.dp
    val font = if (big) 15.sp else 8.sp
    val shape = RoundedCornerShape(if (big) 18.dp else 12.dp)
    val hl = MaterialTheme.colorScheme.primary
    fun Modifier.pick(id: String, keyShape: androidx.compose.ui.graphics.Shape): Modifier =
        (if (selectedPart == id) border(2.dp, hl, keyShape) else this).let { m -> if (onPart != null) m.clickable { onPart(id) } else m }
    Column(modifier.clip(shape).background(col(d.bg))
        .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
        .let { if (selectedPart == "bg") it.border(2.dp, hl, shape) else it }
        .let { if (onPart != null) it.clickable { onPart("bg") } else it }
        .padding(if (big) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        val keyShape = RoundedCornerShape(if (big) 8.dp else 4.dp)
        @Composable
        fun key(label: String, color: Long, textColor: Long, keyPart: String, textPart: String) {
            Box(Modifier.fillMaxWidth().height(keyH).clip(keyShape).background(col(color)).pick(keyPart, keyShape),
                contentAlignment = Alignment.Center) {
                if (label.isNotEmpty()) Text(label, color = col(textColor), fontSize = font, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = if (onPart != null) Modifier.clickable { onPart(textPart) } else Modifier)
            }
        }
        @Composable
        fun row(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
        if (big) {
            val tb = d.partColor("toolbar")
            Row(Modifier.fillMaxWidth().height(30.dp).clip(keyShape).background(col(tb)).pick("toolbar", keyShape),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf("▦", "☻", "GIF", "▤", "⚙", "🎙").forEach {
                    Text(it, color = col(d.partColor("toolbarIcons")), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = if (onPart != null) Modifier.clickable { onPart("toolbarIcons") }.padding(horizontal = 6.dp) else Modifier)
                }
            }
        }
        val letter = d.text; val sym = d.partColor("specialText")
        row { "qwertyuiop".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), d.key, letter, "keys", "letters") } } }
        row {
            Spacer(Modifier.weight(0.5f))
            "asdfghjkl".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), d.key, letter, "keys", "letters") } }
            Spacer(Modifier.weight(0.5f))
        }
        row {
            Box(Modifier.weight(1.5f)) { key("⇧", d.special, sym, "special", "specialText") }
            "zxcvbnm".forEach { c -> Box(Modifier.weight(1f)) { key(c.toString(), d.key, letter, "keys", "letters") } }
            Box(Modifier.weight(1.5f)) { key("⌫", d.special, sym, "special", "specialText") }
        }
        row {
            Box(Modifier.weight(1.5f)) { key("?123", d.special, sym, "special", "specialText") }
            Box(Modifier.weight(1f)) { key("☺", d.special, sym, "special", "specialText") }
            Box(Modifier.weight(1f)) { key(",", d.key, letter, "keys", "letters") }
            Box(Modifier.weight(4f)) { key(if (big) "English" else "", keyColor(d.partColor("space"), d), d.partColor("spaceText"), "space", "spaceText") }
            Box(Modifier.weight(1f)) { key(".", d.key, letter, "keys", "letters") }
            Box(Modifier.weight(1.5f)) { key("↵", d.partColor("enter"), d.partColor("enterIcon"), "enter", "enterIcon") }
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
    val custom = d.parts.filterKeys { id -> partList.any { it.id == id && it.types.isNotEmpty() } }
    if (custom.isEmpty()) KeyboardTheme.writeUserMoreColors(prefs, THEME_NAME, 0)
    else {
        // start from what the main colours give every part, then lay the chosen part colours on top
        val style = prefs.getString(Settings.PREF_THEME_STYLE, helium314.keyboard.latin.settings.Defaults.PREF_THEME_STYLE)!!
        val base = KeyboardTheme.readUserColorTheme(style, true, colors, ctx, false, null)
        val map = EnumMap<ColorType, Int>(ColorType::class.java)
        ColorType.entries.forEach { map[it] = base.get(it) }
        custom.forEach { (id, c) ->
            val v = (if (id == "space") keyColor(c, d) else c).toInt()
            partList.first { it.id == id }.types.forEach { map[it] = v }
        }
        KeyboardTheme.writeUserAllColors(prefs, THEME_NAME, map)
        KeyboardTheme.writeUserMoreColors(prefs, THEME_NAME, 2)
    }
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
