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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Test 288 (Jarrod): while music plays, the "Open Waves" name does a CapCut-style text animation every 60 seconds,
 * going through 10 different ones in turn and then starting again. Paused = still.
 */
internal object TitleFx {
    val names = listOf("Typewriter", "Wave", "Glitch", "Neon flicker", "Rainbow", "Pop", "Flip", "Drop", "Focus", "Heartbeat")
    /** which effect comes next (kept while the app is open, so it carries on through the list) */
    var next = 0
    const val EVERY_MS = 60_000L
    const val FIRST_MS = 5_000L
    const val LENGTH_MS = 3500
}

@Composable
internal fun AnimatedTitle(playing: Boolean, modifier: Modifier = Modifier) {
    val base = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val text = "Open Waves"
    var effect by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(playing) {
        effect = -1
        if (!playing) return@LaunchedEffect
        var wait = TitleFx.FIRST_MS // the first one soon after the music starts, then once a minute
        while (true) {
            kotlinx.coroutines.delay(wait); wait = TitleFx.EVERY_MS
            p.snapTo(0f)
            effect = TitleFx.next
            TitleFx.next = (TitleFx.next + 1) % TitleFx.names.size
            p.animateTo(1f, tween(TitleFx.LENGTH_MS, easing = androidx.compose.animation.core.LinearEasing))
            effect = -1
        }
    }
    val pv = if (effect >= 0) p.value else 0f
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        text.forEachIndexed { i, c ->
            val color = if (i < 5) base else androidx.compose.ui.graphics.lerp(Color(0xFF1FE3FF), Color(0xFF8E5BFF), (i - 5) / 4f)
            TitleLetter(c, color, i, text.length, effect, pv)
        }
    }
}

private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
private fun easeOutBack(x: Float): Float { val c1 = 2.2f; val c3 = c1 + 1; val y = x - 1; return 1 + c3 * y * y * y + c1 * y * y }
private fun bounce(x: Float): Float {
    val n = 7.5625f; val d = 2.75f
    return when {
        x < 1 / d -> n * x * x
        x < 2 / d -> (x - 1.5f / d).let { n * it * it + 0.75f }
        x < 2.5 / d -> (x - 2.25f / d).let { n * it * it + 0.9375f }
        else -> (x - 2.625f / d).let { n * it * it + 0.984375f }
    }
}

@Composable
private fun TitleLetter(ch: Char, color: Color, i: Int, n: Int, fx: Int, p: Float) {
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val px = with(dens) { 1f * density }
    val s = i / (n - 1f)                                       // 0 for the first letter, 1 for the last
    fun stag(spread: Float, len: Float, at: Float = p) = ((at - s * spread) / len).coerceIn(0f, 1f)
    val bump = sin(Math.PI * p).toFloat()                      // 0 → 1 → 0 over the effect
    var alpha = 1f; var tx = 0f; var ty = 0f; var sc = 1f; var rotX = 0f; var rotZ = 0f; var blur = 0f
    var c = color; var glow = 0f; var glitch = 0f
    when (fx) {
        0 -> { // Typewriter: wiped away left to right, then typed back in one letter at a time
            alpha = if (p < 0.38f) 1f - ((p - s * 0.3f) / 0.05f).coerceIn(0f, 1f) else ((p - 0.42f - s * 0.48f) / 0.02f).coerceIn(0f, 1f)
            if (p >= 0.38f && alpha in 0.01f..0.99f) c = Color.White
        }
        1 -> { // Wave: the letters jump up one after another, twice
            val ph = (p * 2f % 1f) * 1.6f - s * 0.6f
            if (ph in 0f..1f) { ty = -sin(Math.PI * ph).toFloat() * 9f * px; sc = 1f + 0.12f * sin(Math.PI * ph).toFloat() }
        }
        2 -> { // Glitch: jumpy red/blue split and letters jerking sideways
            val step = (p * 28).toInt()
            val r = java.util.Random(step * 131L + i * 7L)
            glitch = bump
            if (r.nextFloat() < 0.55f * bump) { tx = (r.nextFloat() - 0.5f) * 10f * px; ty = (r.nextFloat() - 0.5f) * 4f * px }
            if (r.nextFloat() < 0.12f * bump) alpha = 0.2f
        }
        3 -> { // Neon flicker: lights up with a glow and flickers like a neon sign
            val step = (p * 22).toInt()
            val on = java.util.Random(step * 17L + i / 3).nextFloat() > 0.25f * bump
            glow = if (on) bump else 0f
            alpha = if (on) 1f else 0.35f
            c = androidx.compose.ui.graphics.lerp(color, Color.White, 0.55f * glow)
        }
        4 -> { // Rainbow: colours run across the name
            val hue = ((s * 160f + p * 900f) % 360f + 360f) % 360f
            c = androidx.compose.ui.graphics.lerp(color, Color.hsv(hue, 0.75f, 1f), (bump * 2f).coerceAtMost(1f))
            glow = 0.35f * bump
        }
        5 -> { // Pop: letters shrink away, then pop back in bigger with a bounce
            sc = if (p < 0.35f) 1f - stag(0.15f, 0.2f) else easeOutBack(stag(0.35f, 0.3f, p - 0.35f))
            alpha = sc.coerceIn(0f, 1f)
        }
        6 -> { // Flip: each letter does a full flip, one after another
            rotX = 360f * easeInOut(stag(0.5f, 0.45f))
        }
        7 -> { // Drop: letters fly up and away, then drop back down with a bounce
            if (p < 0.35f) { val k = stag(0.2f, 0.15f); ty = -28f * px * k * k; alpha = 1f - k }
            else { val k = stag(0.4f, 0.25f, p - 0.35f); ty = -28f * px * (1f - bounce(k)); alpha = if (k > 0f) 1f else 0f }
        }
        8 -> { // Focus: goes blurry and comes back sharp, sweeping across
            val k = sin(Math.PI * stag(0.5f, 0.5f)).toFloat()
            blur = 7f * k; alpha = 1f - 0.45f * k; sc = 1f + 0.08f * k
        }
        9 -> { // Heartbeat: the whole name beats twice and flashes
            val beats = listOf(0.12f, 0.3f, 0.6f, 0.78f)
            val b = beats.maxOf { at -> if (p >= at) kotlin.math.exp(-(p - at) * 18f) else 0f }
            sc = 1f + 0.16f * b; glow = b
            c = androidx.compose.ui.graphics.lerp(color, Color(0xFFFF4D8D), 0.6f * b)
            rotZ = 0f
        }
    }
    val style = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        shadow = if (glow > 0.02f) androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.9f * glow), blurRadius = 22f * glow) else null)
    val t = ch.toString()
    Box {
        if (glitch > 0.05f) {
            androidx.compose.material3.Text(t, style = style.copy(color = Color(0xFFFF2E63).copy(alpha = 0.7f * glitch), shadow = null),
                modifier = Modifier.graphicsLayer { translationX = tx - 3f * px * glitch })
            androidx.compose.material3.Text(t, style = style.copy(color = Color(0xFF08D9D6).copy(alpha = 0.7f * glitch), shadow = null),
                modifier = Modifier.graphicsLayer { translationX = tx + 3f * px * glitch })
        }
        androidx.compose.material3.Text(t, style = style.copy(color = c), maxLines = 1, softWrap = false,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha; translationX = tx; translationY = ty; scaleX = sc; scaleY = sc
                rotationX = rotX; rotationZ = rotZ; cameraDistance = 12f * density
            }.then(if (blur > 0.3f) Modifier.blur(blur.dp) else Modifier))
    }
}
