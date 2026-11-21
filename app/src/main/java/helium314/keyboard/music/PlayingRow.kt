// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private class Puff(val dx: Float, val rise: Float, val hue: Float, val phase: Float, val wobble: Float)

/**
 * Test 308 (Jarrod): the cover of the song that's playing, in any list. It pumps with the bass like a speaker cone,
 * and multicoloured smoke drifts up from behind it, thicker when the music is louder.
 */
@Composable
internal fun PlayingCover(song: Song, ui: PlayerUi) {
    val map = rememberBeatMap(song, true)
    val clock = rememberBeatClock(ui, ui.playing)
    val puffs = remember {
        val r = java.util.Random(11)
        List(14) { Puff((r.nextFloat() - 0.5f) * 1.6f, 0.6f + r.nextFloat() * 0.7f, r.nextFloat() * 360f, r.nextFloat() * 2600f, r.nextFloat() * 6f) }
    }
    fun pulse(ms: Long) = map?.pulse(ms) ?: exp(-((ms % 500) / 120f))           // a steady 120 bpm until the beats are known
    fun bass(ms: Long) = (map?.bass(ms) ?: (0.5f + 0.5f * sin(ms / 250f))).coerceIn(0f, 1f)
    fun level(ms: Long) = (map?.level(ms) ?: 0.6f).coerceIn(0f, 1f)
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // the smoke is drawn past the cover's edges on purpose
        Canvas(Modifier.size(48.dp)) {
            val ms = clock.ms
            val loud = 0.45f + 0.55f * level(ms)
            val life = 2600f
            for (p in puffs) {
                val t = ((ms + p.phase) % life) / life
                val x = center.x + p.dx * t * size.width + sin(t * p.wobble + p.phase) * 3.dp.toPx()
                val y = center.y - t * size.height * p.rise
                val r = size.minDimension * (0.28f + 0.55f * t) * (0.85f + 0.3f * loud)
                val hue = (p.hue + ms / 35f) % 360f
                val a = sin(PI * t).toFloat() * 0.42f * loud
                val c = Color.hsv(hue, 0.75f, 1f)
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.4f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
            }
        }
        Cover(song, 160, Modifier.size(48.dp).graphicsLayer {
            val ms = clock.ms
            val s = 1f + 0.06f * bass(ms) + 0.07f * pulse(ms)
            scaleX = s; scaleY = s
        }, corner = 6)
    }
}
