// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design studio (test 264, Jarrod): themes, the mini player's own look, sizes and beat effects in one place,
 * instead of split between the Themes page and Settings. Changes show straight away (the real mini player
 * at the bottom of the screen is the live preview).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioPage(open: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun changed() = MusicSettings.save(ctx)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Design studio", onBack) }
        item {
            Text("Make Open Waves look the way you like. Changes show straight away, and the mini player at the bottom shows its new look live.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = dim)
        }

        // ---------------- app background ----------------
        item { Section("App background") }
        item {
            Row(Modifier.fillMaxWidth().clickable { open(Page.Themes) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 46.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))) {
                    if (MusicSettings.theme == -1) Box(Modifier.fillMaxSize().background(Color.Black))
                    else ThemeBackground(MusicSettings.theme, dim = 0.05f, preview = true)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Themes", fontSize = 17.sp)
                    Text("30 colour themes and 30 ✨ FX themes that move. Now: " + (WavesThemes.of(MusicSettings.theme)?.name ?: "Plain"), fontSize = 13.sp, color = dim)
                }
                Icon(MusicIcons.chevron, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }

        // ---------------- mini player ----------------
        item { Section("Mini player (bottom bar)") }
        item {
            Text("Background", Modifier.padding(start = 16.dp, top = 10.dp), fontWeight = FontWeight.SemiBold)
            Text("Pick any theme, including the moving ✨ FX ones.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
            val choices = listOf(-3, -2, -1) + WavesThemes.colour.indices.toList() + WavesThemes.fx.indices.map { 100 + it }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(choices.size) { n ->
                    val id = choices[n]
                    val name = when (id) { -3 -> "Song colour"; -2 -> "Same as app"; -1 -> "Plain"; else -> WavesThemes.of(id)?.name ?: "" }
                    MiniTile(name, MusicSettings.miniTheme == id, {
                        when (id) {
                            -3 -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF6A1B9A)))))
                            -2 -> if (MusicSettings.theme == -1) Box(Modifier.fillMaxSize().background(Color.Black)) else ThemeBackground(MusicSettings.theme, dim = 0.1f, preview = true)
                            -1 -> Box(Modifier.fillMaxSize().background(Color(0xFF111114)))
                            else -> ThemeBackground(id, dim = 0.1f, preview = true)
                        }
                    }) { MusicSettings.miniTheme = id; changed() }
                }
            }
        }
        item {
            Text("Size", Modifier.padding(start = 16.dp, top = 12.dp), fontWeight = FontWeight.SemiBold)
            SizeSlider(MusicSettings.miniSize, 1.0f..2.0f, { MusicSettings.miniSize = it }) { changed() }
            Text("Past about a third, the song name moves above the buttons and the artist below.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
        }

        // ---------------- album cover ----------------
        // test 265 (Jarrod): everything about the cover together, each beat move with a live preview under it
        item { Section("Album cover") }
        item {
            Text("Album cover size (Now Playing)", Modifier.padding(start = 16.dp, top = 10.dp), fontWeight = FontWeight.SemiBold)
            SizeSlider(MusicSettings.coverSize, 0.35f..1.0f, { MusicSettings.coverSize = it }) { changed() }
        }
        item {
            Text("Now Playing cover moves to the beat", Modifier.padding(start = 16.dp, top = 12.dp), fontWeight = FontWeight.SemiBold)
            if (!MusicSettings.effectsOn) Text("Turn on \"Move to the music\" under Beat effects to see it in Now Playing.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicSettings.coverEffects.forEachIndexed { i, name -> Chip(name, MusicSettings.cover == i) { MusicSettings.cover = i; changed() } }
            }
            MovePreview(MusicSettings.cover, round = false)
        }
        item {
            Text("Mini player cover moves to the beat", Modifier.padding(start = 16.dp, top = 12.dp), fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicSettings.coverEffects.forEachIndexed { i, name -> Chip(name, MusicSettings.miniCover == i) { MusicSettings.miniCover = i; changed() } }
            }
            MovePreview(MusicSettings.miniCover, round = true)
            Text("Strength (how much it moves) is under Beat effects.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
        }

        // ---------------- now playing ----------------
        item { Section("Now Playing") }
        item {
            Text("Play buttons size", Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = FontWeight.SemiBold)
            SizeSlider(MusicSettings.buttonSize, 0.7f..1.5f, { MusicSettings.buttonSize = it }) { changed() }
            // live preview of the sizes
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                Box(Modifier.size((90 * MusicSettings.coverSize).dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Accent, Color(0xFF7C4DFF)))))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(MusicIcons.previous to 22, MusicIcons.play to 32, MusicIcons.next to 22).forEach { (ic, sz) ->
                        Icon(ic, null, tint = Color.White, modifier = Modifier.padding(horizontal = 4.dp).size((sz * MusicSettings.buttonSize).dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { MusicSettings.blurBackground = !MusicSettings.blurBackground; changed() }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blurred moving cover background", fontWeight = FontWeight.SemiBold)
                    Text("Used when the app background is Plain. Off gives the plain Samsung-style look.", fontSize = 13.sp, color = dim)
                }
                Switch(MusicSettings.blurBackground, { MusicSettings.blurBackground = it; changed() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
            TextButton({ MusicSettings.coverSize = 0.5f; MusicSettings.buttonSize = 1f; MusicSettings.miniSize = 1.25f; changed() }, Modifier.padding(start = 8.dp)) {
                Text("Reset all sizes", color = Accent)
            }
        }

        // ---------------- beat effects ----------------
        item { Section("Beat effects") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Move to the music", fontSize = 17.sp)
                    Text("The Now Playing background (and cover, see Album cover above) move to the beat. Open Waves works out the beats on your phone.", fontSize = 13.sp, color = dim)
                }
                Switch(MusicSettings.effectsOn, { MusicSettings.effectsOn = it; changed() }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
        if (MusicSettings.effectsOn) {
            item {
                Text("Background", Modifier.padding(start = 16.dp, top = 14.dp), fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSettings.backgroundEffects.forEachIndexed { i, name -> if (i != 2) Chip(name, MusicSettings.background == i) { MusicSettings.background = i; changed() } }
                }
            }
        }
        item {
            Text("Strength", Modifier.padding(start = 16.dp, top = 14.dp), fontWeight = FontWeight.SemiBold)
            Text("How much things move, for Now Playing and the mini player covers too.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = dim)
            Slider(MusicSettings.strength, { MusicSettings.strength = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0.3f..1.8f,
                onValueChangeFinished = { changed() }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        }
    }
}

@Composable
private fun SizeSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Small", fontSize = 13.sp)
        Slider(value, onChange, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = range,
            onValueChangeFinished = onDone, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        Text("Big", fontSize = 13.sp)
    }
}

/** A wide little tile shaped like the mini player bar. */
@Composable
private fun MiniTile(name: String, on: Boolean, preview: @Composable () -> Unit, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp).width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).size(width = 96.dp, height = 46.dp).clip(RoundedCornerShape(23.dp))
            .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.2f), RoundedCornerShape(23.dp))) {
            preview()
            // a little cover dot so it reads as the bar
            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
            if (on) Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(20.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(name, Modifier.padding(top = 4.dp, bottom = 2.dp), fontSize = 12.sp, maxLines = 1)
    }
}

/** A little cover moving to a pretend beat (2 beats a second), so you can see a move before you use it. */
@Composable
private fun MovePreview(effect: Int, round: Boolean) {
    val anim = androidx.compose.animation.core.rememberInfiniteTransition(label = "beat")
    val t by anim.animateFloat(0f, 8f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    val liftPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E))
        .padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            fun move(): CoverMove {
                val beat = t.toInt()
                val ph = t - beat
                val k = MusicSettings.strength
                val p = kotlin.math.exp(-ph * 6f) * k                                  // sharp hit, quick fade
                val b = kotlin.math.exp(-ph * 3f) * (if (beat % 2 == 0) 1f else 0.6f) * k // softer, heavier on the 1
                return moveFor(effect, p, b, beat)
            }
            if (effect == 3) Box(Modifier.size(70.dp).graphicsLayer {
                val m = move(); alpha = m.glow; scaleX = 1.05f + 0.25f * m.glow; scaleY = scaleX
            }.blur(16.dp).clip(shape).background(Accent))
            Box(Modifier.size(70.dp).graphicsLayer {
                val m = move(); scaleX = m.scale; scaleY = m.scale; rotationZ = m.rotation; translationY = m.lift * liftPx
            }.clip(shape).background(Brush.linearGradient(listOf(Accent, Color(0xFF7C4DFF)))), contentAlignment = Alignment.Center) {
                Icon(MusicIcons.play, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp))
            }
        }
        Text(if (effect == 0) "Still" else MusicSettings.coverEffects.getOrElse(effect) { "" } + " preview",
            Modifier.padding(start = 20.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
    }
}
