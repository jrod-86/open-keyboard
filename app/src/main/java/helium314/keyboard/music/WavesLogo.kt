// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Test 246 (Jarrod): the Open Waves logo's equaliser bars bounce while music plays and settle back to the
 * logo's own shape when it stops. The picture has the bars taken out (open_waves_mark_base); they're drawn here.
 */
private class Bar(val x: Float, val top: Float, val bottom: Float, val color: Color)

private val bars = listOf(
    Bar(7f, 130f, 138f, Color(0xFF00C0FC)), Bar(22f, 125f, 143f, Color(0xFF01C6F5)), Bar(39f, 117f, 150f, Color(0xFF03C2FA)),
    Bar(56f, 104f, 162f, Color(0xFF02A4F6)), Bar(93f, 102f, 168f, Color(0xFF0B83EC)), Bar(112f, 80f, 176f, Color(0xFF049AEE)),
    Bar(131f, 106f, 154f, Color(0xFF068FF0)), Bar(150f, 123f, 144f, Color(0xFF107BEB)),
    Bar(265f, 103f, 159f, Color(0xFF0C87E9)), Bar(283f, 80f, 176f, Color(0xFF0F87EA)), Bar(318f, 102f, 162f, Color(0xFF7142F3)),
    Bar(336f, 117f, 150f, Color(0xFF8F32F7)), Bar(354f, 125f, 143f, Color(0xFFA12EFB)), Bar(369f, 130f, 138f, Color(0xFF9B2FFA)),
)

@Composable
internal fun WavesLogo(playing: Boolean, height: Dp) {
    // 0 = the logo's own bar heights, 1 = fully bouncing; eases in and out so play/pause never jumps
    val amount by animateFloatAsState(if (playing) 1f else 0f, tween(if (playing) 400 else 700), label = "eq")
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing || amount > 0f) {
        if (!(playing || amount > 0f)) return@LaunchedEffect
        val start = withFrameNanos { it }
        val t0 = t
        while (true) withFrameNanos { t = t0 + (it - start) / 1e9f }
    }
    Box(Modifier.height(height).aspectRatio(384f / 232f)) {
        Image(painterResource(helium314.keyboard.latin.R.drawable.open_waves_mark_base), "Open Waves", Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 384f
            val w = 9f * s
            bars.forEachIndexed { i, b ->
                val half = (b.bottom - b.top) / 2f
                val cy = (b.top + b.bottom) / 2f
                // a few out-of-step waves per bar, so it looks like music rather than a metronome
                val wave = 0.5f * abs(sin(t * (5.1f + i * 0.73f) + i * 1.7f)) + 0.35f * abs(sin(t * (2.3f + i * 0.41f) + i * 0.9f)) +
                    0.15f * abs(sin(t * 9.7f + i))
                val cap = if (b.x in 120f..160f) 26f else 60f // the two bars by the note stay clear of it
                val target = (4.5f + wave * cap).coerceAtMost(cap) // half height in logo pixels
                val h = half + (target - half) * amount
                val top = (cy - h) * s; val hh = 2 * h * s
                drawRoundRect(b.color.copy(alpha = 0.22f), Offset(b.x * s - w, top - 3 * s), Size(w * 2, hh + 6 * s), CornerRadius(w))
                drawRoundRect(b.color, Offset(b.x * s - w / 2, top), Size(w, hh), CornerRadius(w / 2))
            }
        }
    }
}
