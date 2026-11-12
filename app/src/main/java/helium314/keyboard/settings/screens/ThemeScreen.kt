// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import java.io.File

/** One tile on the theme screen: what it looks like, and what choosing it does. */
private class ThemeTile(
    val id: String,
    val label: String?,
    val background: Brush,
    val key: Color,
    val accent: Color,
    val apply: (Context) -> Unit,
)

private fun c(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Gboard-style theme picker: tap a tile to use it. */
@Composable
fun ThemeScreen(onClickBack: () -> Unit, onClickCustom: () -> Unit, onClickDesign: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var selected by remember { mutableStateOf(prefs.getString(PREF_SELECTED_TILE, "system_auto")) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            for (night in listOf(false, true)) FileUtils.copyContentUriToNewFile(uri, ctx, Settings.getCustomBackgroundFile(ctx, night, false))
            setColors(ctx, KeyboardTheme.THEME_DARK, KeyboardTheme.THEME_DARK, keepImage = true)
        }.isSuccess
        if (ok) { selected = "photo"; prefs.edit { putString(PREF_SELECTED_TILE, "photo") } }
    }

    val defaults = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            add(ThemeTile("dynamic", "Dynamic Color", Brush.linearGradient(listOf(c("#E8EAF6"), c("#D7E3FF"))), c("#FFFFFF"), c("#3F5F90")) {
                setColors(it, KeyboardTheme.THEME_DYNAMIC, KeyboardTheme.THEME_DYNAMIC)
            })
        add(ThemeTile("system_auto", "System Auto", Brush.horizontalGradient(0.5f to c("#ECEFF1"), 0.5f to c("#263238")), c("#9E9E9E"), c("#A8C7FA")) {
            setColors(it, KeyboardTheme.THEME_LIGHT, KeyboardTheme.THEME_DARK)
        })
        add(ThemeTile("light", "Default", solid("#ECEFF1"), c("#FFFFFF"), c("#1A73E8")) {
            setColors(it, KeyboardTheme.THEME_LIGHT, KeyboardTheme.THEME_LIGHT)
        })
        add(ThemeTile("dark", "Default Dark", solid("#263238"), c("#364248"), c("#A8C7FA")) {
            setColors(it, KeyboardTheme.THEME_DARK, KeyboardTheme.THEME_DARK)
        })
    }
    fun colorTile(name: String, bg: String, key: String, accent: String) =
        ThemeTile(name, null, solid(bg), c(key), c(accent)) { setColors(it, name, name) }
    val colors = listOf(
        colorTile(KeyboardTheme.THEME_DARKER, "#202124", "#3C4043", "#8AB4F8"),
        colorTile(KeyboardTheme.THEME_BLACK, "#000000", "#1F1F1F", "#8AB4F8"),
        colorTile(KeyboardTheme.THEME_BLUE_GRAY, "#ECEFF1", "#FFFFFF", "#78909C"),
        colorTile(KeyboardTheme.THEME_BROWN, "#EFEBE9", "#FFFFFF", "#8D6E63"),
        colorTile(KeyboardTheme.THEME_CHOCOLATE, "#8C705E", "#C1A392", "#5080FF"),
        colorTile(KeyboardTheme.THEME_CLOUDY, "#516171", "#75808E", "#FF7181"),
        colorTile(KeyboardTheme.THEME_FOREST, "#B57D58", "#E4D4BF", "#4B6E4B"),
        colorTile(KeyboardTheme.THEME_INDIGO, "#E8EAF6", "#FFFFFF", "#5C6BC0"),
        colorTile(KeyboardTheme.THEME_OCEAN, "#596D9B", "#849DD4", "#FF7C00"),
        colorTile(KeyboardTheme.THEME_PINK, "#FCE4EC", "#FFFFFF", "#EC407A"),
        colorTile(KeyboardTheme.THEME_SAND, "#F2E8DA", "#FFFFFF", "#6E9BFF"),
        colorTile(KeyboardTheme.THEME_VIOLETTE, "#7070AE", "#9696D8", "#FF60FF"),
    )
    fun gradientTile(id: String, top: String, bottom: String, dark: Boolean) = ThemeTile(
        id, null, Brush.verticalGradient(listOf(c(top), c(bottom))),
        if (dark) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.7f),
        if (dark) c("#A8C7FA") else c("#1A73E8"),
    ) { setGradient(it, top, bottom, dark) }
    val lightGradients = listOf(
        gradientTile("lg_rose", "#F4B6B8", "#C5D3F5", false),
        gradientTile("lg_mist", "#E6E4DE", "#CDD8D8", false),
        gradientTile("lg_dusk", "#DECFCF", "#C3CCD6", false),
        gradientTile("lg_silver", "#E9EEF8", "#BDBDBD", false),
        gradientTile("lg_candy", "#F2C9D8", "#E59AB8", false),
        gradientTile("lg_peach_sky", "#FBE5DA", "#C6E7F6", false),
        gradientTile("lg_lemon", "#FCE9B0", "#DCD6F4", false),
        gradientTile("lg_mint", "#C3E3B1", "#E8CFD5", false),
        gradientTile("lg_apricot", "#F7C59C", "#FCE6B3", false),
    )
    val darkGradients = listOf(
        gradientTile("dg_purple", "#1B1464", "#9B4DA6", true),
        gradientTile("dg_graphite", "#5F6368", "#0B0B0B", true),
        gradientTile("dg_cocoa", "#5D4740", "#2E1F1C", true),
        gradientTile("dg_slate", "#5A6670", "#1F2A30", true),
        gradientTile("dg_teal", "#2F7A6A", "#1E4F45", true),
        gradientTile("dg_blue", "#2F6BD1", "#1A3C8C", true),
        gradientTile("dg_navy", "#3E5C80", "#13263D", true),
        gradientTile("dg_charcoal", "#55575C", "#25272B", true),
        gradientTile("dg_espresso", "#6B625C", "#140D0A", true),
    )

    SearchSettingsScreen(onClickBack = onClickBack, title = "Theme", settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            DesignStudioBanner(onClickDesign)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddTile("Custom colours", onClickCustom, Modifier.weight(1f))
                AddTile("Your photo", {
                    pickPhoto.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
                }, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            listOf("Default" to defaults, "Colors" to colors, "Light gradient" to lightGradients, "Dark gradient" to darkGradients)
                .forEach { (title, tiles) ->
                    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                    tiles.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            row.forEach { tile ->
                                Tile(tile, selected == tile.id, Modifier.weight(1f)) {
                                    tile.apply(ctx)
                                    selected = tile.id
                                    prefs.edit { putString(PREF_SELECTED_TILE, tile.id) }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A large, colourful card so the Design studio is easy to spot. */
@Composable
private fun DesignStudioBanner(onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().padding(bottom = 20.dp).height(96.dp).clip(shape)
            .background(Brush.linearGradient(listOf(c("#7B5CFF"), c("#C850C0"), c("#FF8A65"))))
            .clickable(onClick = onClick)
    ) {
        // a few soft circles for depth
        Box(Modifier.size(120.dp).align(Alignment.TopEnd).padding(start = 30.dp, bottom = 30.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)))
        Box(Modifier.size(70.dp).align(Alignment.BottomStart).padding(top = 20.dp, end = 20.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)))
        Row(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text("🎨", fontSize = 26.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("Design studio", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("›", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
        }
    }
}

private fun solid(hex: String) = Brush.linearGradient(listOf(c(hex), c(hex)))

@Composable
private fun Tile(tile: ThemeTile, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.45f).clip(shape)
                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .background(tile.background).clickable(onClick = onClick)
        ) {
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(44.dp).height(10.dp).clip(CircleShape).background(tile.key))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(width = 18.dp, height = 12.dp).clip(CircleShape).background(tile.accent))
            }
            if (isSelected)
                Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary)
                }
        }
        tile.label?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AddTile(label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.45f).clip(shape)
                .border(2.dp, MaterialTheme.colorScheme.primary, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary) }
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

private const val PREF_SELECTED_TILE = "open_keyboard_theme_tile"

/** Uses [day] in light mode and [night] in dark mode; the same name for both means "always". */
private fun setColors(ctx: Context, day: String, night: String, keepImage: Boolean = false) {
    if (!keepImage) for (n in listOf(false, true)) for (l in listOf(false, true)) Settings.getCustomBackgroundFile(ctx, n, l).delete()
    ctx.prefs().edit {
        putString(Settings.PREF_THEME_COLORS, day)
        putString(Settings.PREF_THEME_COLORS_NIGHT, night)
        putBoolean(Settings.PREF_THEME_DAY_NIGHT, day != night)
    }
    Settings.clearCachedBackgroundImages()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/** Draws a vertical gradient into the keyboard background image. */
private fun setGradient(ctx: Context, top: String, bottom: String, dark: Boolean) {
    val w = 1080; val h = 900
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), android.graphics.Color.parseColor(top), android.graphics.Color.parseColor(bottom), Shader.TileMode.CLAMP)
    }
    android.graphics.Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    for (night in listOf(false, true)) {
        Settings.getCustomBackgroundFile(ctx, night, true).delete()
        val file: File = Settings.getCustomBackgroundFile(ctx, night, false)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    bmp.recycle()
    val base = if (dark) KeyboardTheme.THEME_DARK else KeyboardTheme.THEME_LIGHT
    setColors(ctx, base, base, keepImage = true)
}
