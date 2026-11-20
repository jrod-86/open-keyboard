// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.media3.common.Player
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Test 277 (Jarrod): our own floating player over other apps, because the phone's own pop-up player can't be restyled.
 * Shows when you leave Open Waves while music plays (if turned on in the Design studio and "Appear on top" is allowed).
 * Drag it anywhere; drop it at the bottom of the screen to close it until Open Waves is opened again.
 * Five styles, each with the same theme choices as the mini player.
 */
class FloatingPlayer(private val ctx: Context, private val player: Player) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val ui = PlayerUi()
    private var frame: DragFrame? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pausedAt = 0L
    private var shownStyle = -1
    /** dragged into the close area at the bottom */
    private var nearClose by mutableStateOf(false)
    private val tick = object : Runnable { override fun run() { update(); handler.postDelayed(this, 600) } }

    fun start() { handler.post(tick) }
    fun stop() { handler.removeCallbacksAndMessages(null); hide() }

    private fun prefs() = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    private fun wanted(): Boolean {
        if (!MusicSettings.floatOn || MusicState.appVisible || MusicState.floatClosed) return false
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false
        if (player.mediaItemCount == 0) return false
        if (player.playWhenReady) { pausedAt = 0; return true }
        // paused: stays (so you can press play again) for 5 minutes, but never pops up by itself while paused
        if (frame == null) return false
        if (pausedAt == 0L) pausedAt = SystemClock.elapsedRealtime()
        return SystemClock.elapsedRealtime() - pausedAt < 5 * 60_000
    }

    private fun update() {
        val want = wanted()
        if (want && frame != null && shownStyle != MusicSettings.floatStyle) hide() // style changed: rebuild
        if (want && frame == null) runCatching { show() }.onFailure { hide() }
        else if (!want && frame != null) hide()
    }

    private fun show() {
        val o = OverlayOwner().also { it.create() }
        owner = o
        shownStyle = MusicSettings.floatStyle
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs().getInt("float_x", screenW - dp(270f)).coerceIn(0, (screenW - dp(60f)).coerceAtLeast(0))
            y = prefs().getInt("float_y", (screenH * 0.3f).toInt()).coerceIn(0, (screenH - dp(80f)).coerceAtLeast(0))
        }
        lp = p
        val compose = ComposeView(ctx).apply {
            setContent {
                FloatPlayerView(ui, MusicSettings.floatStyle, nearClose, preview = false,
                    onOpen = {
                        ctx.startActivity(Intent(ctx, MusicActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    },
                    onClose = { close() })
            }
        }
        val f = DragFrame(ctx)
        f.addView(compose)
        listOf(f, compose).forEach { v ->
            v.setViewTreeLifecycleOwner(o); v.setViewTreeSavedStateRegistryOwner(o); v.setViewTreeViewModelStoreOwner(o)
        }
        var startX = 0; var startY = 0
        f.onStart = { startX = p.x; startY = p.y }
        f.onMove = { dx, dy ->
            p.x = (startX + dx).coerceIn(0, (screenW - f.width).coerceAtLeast(0))
            p.y = (startY + dy).coerceIn(0, (screenH - f.height / 2).coerceAtLeast(0))
            nearClose = p.y + f.height / 2 > screenH * 0.85f
            runCatching { wm.updateViewLayout(f, p) }
        }
        f.onDrop = {
            if (nearClose) { nearClose = false; close() }
            else {
                // round styles tuck against the nearest side, like chat bubbles
                if (shownStyle == 0 || shownStyle == 3) snapToSide(f, p) else savePos(p)
            }
        }
        f.onResized = {
            // a bubble opening near the right edge would go off screen: pull it back in
            val maxX = (screenW - f.width).coerceAtLeast(0)
            if (p.x > maxX) { p.x = maxX; runCatching { wm.updateViewLayout(f, p) } }
        }
        wm.addView(f, p)
        frame = f
        ui.attach(player)
    }

    private fun snapToSide(f: DragFrame, p: WindowManager.LayoutParams) {
        val margin = dp(6f)
        val target = if (p.x + f.width / 2 < screenW / 2) margin else screenW - f.width - margin
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 220
            addUpdateListener { p.x = it.animatedValue as Int; if (frame === f) runCatching { wm.updateViewLayout(f, p) } }
            start()
        }
        p.x = target; savePos(p)
    }

    private fun savePos(p: WindowManager.LayoutParams) = prefs().edit().putInt("float_x", p.x).putInt("float_y", p.y).apply()

    private fun close() {
        MusicState.floatClosed = true
        hide()
        Toast.makeText(ctx, "Floating player closed. It comes back next time you open Open Waves.", Toast.LENGTH_LONG).show()
    }

    private fun hide() {
        ui.detach()
        frame?.let { runCatching { wm.removeView(it) } }
        frame = null
        owner?.destroy(); owner = null
        nearClose = false
        shownStyle = -1
    }
}

/** Lets the floating player be dragged by any part of it, while taps still reach its buttons. */
private class DragFrame(ctx: Context) : FrameLayout(ctx) {
    var onStart: () -> Unit = {}
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var onDrop: () -> Unit = {}
    var onResized: () -> Unit = {}
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false; onStart() }
            MotionEvent.ACTION_MOVE -> if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) { dragging = true; return true }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) dragging = true
                if (dragging) onMove((ev.rawX - downX).toInt(), (ev.rawY - downY).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (dragging) onDrop(); dragging = false }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); post { onResized() } }
}

/** A window outside any activity still needs these for Compose to run. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    fun create() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}

// ------------------------------------------------------------------ the look ------------------------------------------------------------------

/**
 * The floating player itself, in the chosen [style]. [preview] = shown in the Design studio (pretend song, pretend beat).
 */
@Composable
internal fun FloatPlayerView(ui: PlayerUi, style: Int, nearClose: Boolean = false, preview: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lib by produceState(Library.cached, preview) { if (!preview) value = Library.load(ctx) }
    val song = if (preview) null else songOf(ui, lib)
    val title = song?.title ?: if (preview) "Song name" else ""
    val artist = song?.artist ?: if (preview) "Artist" else ""
    val art by produceState<android.graphics.Bitmap?>(null, song?.id, song?.artFile) { value = song?.let { Covers.load(ctx, it, 200) } }
    val tint = remember(art) { art?.let { averageColor(it) } ?: Color(0xFF3A3A40) }
    if (!preview) LaunchedEffect(ui.controller) {
        while (true) { ui.controller?.let { ui.position = it.currentPosition; ui.positionAt = SystemClock.elapsedRealtime() }; kotlinx.coroutines.delay(250) }
    }
    val wantBeat = MusicSettings.floatCover != 0 || style == 4 || style == 3
    val beatMap = rememberBeatMap(song, wantBeat && !preview)
    val clock = rememberBeatClock(ui, wantBeat && beatMap != null && ui.playing)
    // the studio preview gets a pretend beat (2 a second) so the moves show
    var fakeT by remember { mutableFloatStateOf(0f) }
    if (preview) LaunchedEffect(Unit) { val t0 = withFrameNanos { it }; while (true) withFrameNanos { fakeT = (it - t0) / 1e9f } }
    val beat = Beat(
        pulse = { if (preview) kotlin.math.exp(-((fakeT * 2) % 1f) * 6f) else beatMap?.pulse(clock.ms) ?: 0f },
        bass = { if (preview) kotlin.math.exp(-((fakeT * 2) % 1f) * 3f) * 0.8f else beatMap?.bass(clock.ms) ?: 0f },
        level = { off -> if (preview) (0.45f + 0.35f * sin(fakeT * 5f + off * 0.04f)).coerceIn(0f, 1f) else beatMap?.level((clock.ms - off).coerceAtLeast(0)) ?: 0f },
        index = { if (preview) (fakeT * 2).toInt() else beatMap?.lastBeat(clock.ms) ?: 0 },
    )
    val k = MusicSettings.floatSize
    val playing = if (preview) true else ui.playing
    val f = FloatParts(song, title, artist, tint, k, playing, beat, ui, onOpen, onClose)
    Box(Modifier.padding(8.dp).graphicsLayer { alpha = if (nearClose) 0.55f else 1f }) {
        when (style) {
            0 -> f.Bubble()
            2 -> f.Card()
            3 -> f.Vinyl()
            4 -> f.Bars()
            else -> f.Pill()
        }
        if (nearClose) Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0xFFE53935)), contentAlignment = Alignment.Center) {
            Icon(MusicIcons.close, "Close", Modifier.size(26.dp), tint = Color.White)
        }
    }
}

/** The song's beat right now, read while drawing. */
private class Beat(val pulse: () -> Float, val bass: () -> Float, val level: (Long) -> Float, val index: () -> Int)

private class FloatParts(
    val song: Song?, val title: String, val artist: String, val tint: Color, val k: Float, val playing: Boolean,
    val beat: Beat, val ui: PlayerUi, val onOpen: () -> Unit, val onClose: () -> Unit,
) {
    fun move(): CoverMove {
        val s = MusicSettings.strength
        return moveFor(MusicSettings.floatCover, beat.pulse() * s, beat.bass() * s, beat.index())
    }

    /** The theme behind the player: song colour, same as the app, plain, or any colour/✨ FX theme. */
    @Composable
    fun Panel(shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
        val ft = MusicSettings.floatTheme
        val mt = if (ft == -2) MusicSettings.theme else ft
        val bg = when { mt == -3 || (ft == -2 && mt == -1) -> tint; mt == -1 -> Color(0xFF111114); else -> Color.Transparent }
        Box(modifier.shadow(12.dp, shape).clip(shape).background(Color.Black).background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)) {
            if (mt >= 0) ThemeBackground(mt, Modifier.matchParentSize(), dim = 0.4f, preview = true)
            content()
        }
    }

    @Composable
    fun Btn(icon: ImageVector, label: String, size: Float, box: Float = 36f, onClick: () -> Unit) {
        Box(Modifier.size((box * k).dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size((size * k).dp), tint = Color.White)
        }
    }
    @Composable fun Prev() = Btn(MusicIcons.sPrevious, "Previous", 22f) { ui.previous() }
    @Composable fun PlayPause(size: Float = 28f) = Btn(if (playing) MusicIcons.sPause else MusicIcons.sPlay, "Play or pause", size) { ui.toggle() }
    @Composable fun Next() = Btn(MusicIcons.sNext, "Next", 22f) { ui.next() }
    @Composable fun Close() = Btn(MusicIcons.close, "Close", 16f, box = 28f) { onClose() }

    /** The cover, moving to the beat the way the Design studio says. */
    @Composable
    fun FloatCover(size: Float, round: Boolean, modifier: Modifier = Modifier) {
        val lift = with(LocalDensity.current) { 5.dp.toPx() }
        val shape = if (round) CircleShape else RoundedCornerShape((8 * k).dp)
        Box(modifier, contentAlignment = Alignment.Center) {
            if (MusicSettings.floatCover == 3) Box(Modifier.size((size * k).dp).graphicsLayer {
                val m = move(); alpha = m.glow; scaleX = 1.05f + 0.25f * m.glow; scaleY = scaleX
            }.blur(12.dp).clip(shape).background(Accent))
            Cover(song, 200, Modifier.size((size * k).dp).graphicsLayer {
                val m = move(); scaleX = m.scale; scaleY = m.scale; rotationZ = m.rotation; translationY = m.lift * lift
            }, corner = if (round) (size * k / 2).roundToInt() else (8 * k).roundToInt())
        }
    }

    /** 1. Bubble: just the round cover, with a ring that flashes on the beat. Tap it to open the controls. */
    @Composable
    fun Bubble() {
        var open by remember { mutableStateOf(false) }
        if (!open) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size((68 * k).dp).graphicsLayer { val p = beat.pulse(); scaleX = 1f + 0.08f * p; scaleY = scaleX; alpha = 0.35f + 0.65f * p }
                    .border(3.dp, Brush.sweepGradient(listOf(Accent, Color.White, Accent)), CircleShape))
                FloatCover(60f, round = true, modifier = Modifier.shadow(10.dp, CircleShape).clip(CircleShape).clickable { open = true })
                if (!playing) Box(Modifier.size((60 * k).dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Icon(MusicIcons.sPlay, null, Modifier.size((24 * k).dp), tint = Color.White)
                }
            }
        } else Panel(RoundedCornerShape((30 * k).dp), Modifier.width((250 * k).dp)) {
            Column(Modifier.padding((10 * k).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatCover(46f, round = true, modifier = Modifier.clip(CircleShape).clickable { open = false })
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        RollText(title, Color.White, 13f * k)
                        ShineText(artist, Color.White.copy(alpha = 0.75f), 11.5f * k)
                    }
                    Close()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Btn(MusicIcons.sDown, "Make small", 22f) { open = false }
                    Prev(); PlayPause(); Next()
                    Btn(MusicIcons.chevron, "Open Open Waves", 20f) { onOpen() }
                }
            }
        }
    }

    /** 2. Pill: a slim bar like the mini player. Tap the cover to open Open Waves. */
    @Composable
    fun Pill() {
        Panel(RoundedCornerShape((32 * k).dp), Modifier.width((300 * k).dp)) {
            Row(Modifier.padding(horizontal = (8 * k).dp, vertical = (7 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                FloatCover(42f, round = true, modifier = Modifier.clip(CircleShape).clickable { onOpen() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, color = Color.White, fontSize = (13.5f * k).sp, maxLines = 1, modifier = Modifier.basicMarquee(initialDelayMillis = 1500))
                    ShineText(artist, Color.White.copy(alpha = 0.75f), 11f * k)
                }
                Prev(); PlayPause(26f); Next(); Close()
            }
        }
    }

    /** 3. Card: like the big mini player, with a progress line. */
    @Composable
    fun Card() {
        Panel(RoundedCornerShape((22 * k).dp), Modifier.width((240 * k).dp)) {
            Column(Modifier.padding((10 * k).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { RollText(title, Color.White, 14f * k) }
                    Close()
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FloatCover(64f, round = false, modifier = Modifier.clip(RoundedCornerShape((8 * k).dp)).clickable { onOpen() })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Prev(); PlayPause(32f); Next()
                    }
                }
                ShineText(artist, Color.White.copy(alpha = 0.75f), 12f * k)
                Spacer(Modifier.height(6.dp))
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val d = ui.duration
                    val done = if (d > 0) (ui.now().toFloat() / d).coerceIn(0f, 1f) else 0.35f
                    drawRoundRect(Color.White.copy(alpha = 0.2f), cornerRadius = CornerRadius(size.height))
                    drawRoundRect(Accent, size = Size(size.width * done, size.height), cornerRadius = CornerRadius(size.height))
                }
            }
        }
    }

    /** 4. Vinyl: a record that spins while music plays, the cover as its label. Tap the record to play or pause. */
    @Composable
    fun Vinyl() {
        var angle by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(playing) {
            var last = 0L
            while (playing) withFrameNanos { t -> if (last != 0L) angle = (angle + (t - last) / 1e9f * 120f) % 360f; last = t }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val d = 124f * k
            Box(Modifier.size(d.dp).graphicsLayer { val p = beat.pulse() * MusicSettings.strength; scaleX = 1f + 0.03f * p; scaleY = scaleX }
                .shadow(14.dp, CircleShape).clip(CircleShape).clickable { ui.toggle() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2A2E), Color(0xFF0B0B0C)), center, r), r)
                    var g = r * 0.52f
                    while (g < r * 0.97f) { drawCircle(Color.White.copy(alpha = 0.06f), g, style = Stroke(1f)); g += r * 0.045f }
                    // the shine across the record
                    drawArc(Color.White.copy(alpha = 0.08f), -60f, 40f, true)
                    drawArc(Color.White.copy(alpha = 0.08f), 120f, 40f, true)
                    // a beat glow round the edge
                    drawCircle(Accent.copy(alpha = 0.6f * beat.pulse()), r - 2f, style = Stroke(4f))
                }
                Cover(song, 200, Modifier.size((d * 0.42f).dp).graphicsLayer { rotationZ = angle }, corner = (d * 0.21f).roundToInt())
                Box(Modifier.size((d * 0.06f).dp).clip(CircleShape).background(Color.Black))
                if (!playing) Icon(MusicIcons.sPlay, "Play", Modifier.size((30 * k).dp), tint = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Panel(RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Prev(); Next(); Btn(MusicIcons.chevron, "Open Open Waves", 20f) { onOpen() }; Close()
                }
            }
        }
    }

    /** 5. Beat bars: a bar with a little equaliser that jumps with the song. */
    @Composable
    fun Bars() {
        Panel(RoundedCornerShape((24 * k).dp), Modifier.width((300 * k).dp)) {
            Row(Modifier.padding(horizontal = (8 * k).dp, vertical = (7 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                FloatCover(44f, round = false, modifier = Modifier.clip(RoundedCornerShape((8 * k).dp)).clickable { onOpen() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, color = Color.White, fontSize = (13f * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.basicMarquee(initialDelayMillis = 1500))
                    Canvas(Modifier.fillMaxWidth().height((22 * k).dp)) {
                        val n = 16
                        val gap = size.width / n * 0.3f
                        val w = size.width / n - gap
                        val p = beat.pulse(); val b = beat.bass()
                        for (i in 0 until n) {
                            // low bars follow the bass, the rest the loudness a little back in time, plus the beat's kick
                            val wob = 0.65f + 0.35f * sin(i * 1.7f + beat.index() * 0.9f)
                            var v = if (i < 4) 0.3f * beat.level(i * 30L) + 0.7f * b else beat.level(i * 35L) * wob
                            v = (v + 0.35f * p * (1f - i / n.toFloat())) * MusicSettings.strength
                            if (!playing) v = 0.08f
                            val h = (0.12f + 0.88f * v.coerceIn(0f, 1f)) * size.height
                            val c = androidx.compose.ui.graphics.lerp(Accent, Color.White, i / n.toFloat() * 0.6f)
                            drawRoundRect(c, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
                        }
                    }
                }
                Prev(); PlayPause(26f); Next(); Close()
            }
        }
    }
}

