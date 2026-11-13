// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsDestination
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gboard-style menu opened by the grid button at the top left: a grid of rounded buttons
 * (Theme, Resize, One-handed, ...) shown over the keys, and the drag-to-resize mode.
 */
object KeyboardMenu {
    const val OVERLAY_TAG = "open_keyboard_menu_overlay"
    private var menuView: View? = null
    private var resizeView: View? = null

    val isShowing get() = menuView?.parent != null || resizeView?.parent != null

    fun toggle(context: Context, onCode: (Int) -> Unit) {
        if (isShowing) hide() else showMenu(context, onCode)
    }

    fun hide() {
        listOf(menuView, resizeView).forEach { v -> (v?.parent as? ViewGroup)?.removeView(v) }
        menuView = null
        resizeView = null
        keepToolbar = true
        onHidden?.invoke()
    }

    /** True after a panel closes until the next key press, so late word predictions don't replace the buttons. */
    var keepToolbar = false

    /** Lets the top bar tidy itself when a panel closes, so it is never left empty. */
    var onHidden: (() -> Unit)? = null

    /** Shows another full-keyboard panel (stickers, GIFs) in the menu's place. */
    fun showOverlay(view: View) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        hide()
        view.tag = OVERLAY_TAG
        view.clearSystemButtons()
        wrapper.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuView = view
    }

    val currentOverlay: View? get() = menuView?.takeIf { it.parent != null }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private class Item(val label: String, val icon: () -> android.graphics.drawable.Drawable?, val key: ToolbarKey? = null, val plugin: String? = null, val tint: Boolean = true, idOverride: String? = null, val action: () -> Unit) {
        /** Stays the same when the label changes (e.g. "Voice → Khmer"), so the saved order holds. */
        val id: String = idOverride ?: label
    }

    // ---- moving buttons between the menu and the top bar ----

    /** Tells the top bar to rebuild its buttons after one was dragged. */
    var onBarChanged: (() -> Unit)? = null
    private var lastOnCode: ((Int) -> Unit)? = null
    private const val MAX_ON_BAR = 5 // not counting the menu button and the mic

    fun startDrag(view: View, from: String, key: ToolbarKey) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val state = "$from:${key.name}"
        view.startDragAndDrop(android.content.ClipData.newPlainText("open-keyboard-button", state), View.DragShadowBuilder(view), state, 0)
    }

    /** Buttons that can't go on the top bar (Translate, app actions…) can still be moved around the menu. */
    private fun startItemDrag(view: View, id: String) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val state = "item:$id"
        view.startDragAndDrop(android.content.ClipData.newPlainText("open-keyboard-button", state), View.DragShadowBuilder(view), state, 0)
    }

    private fun draggedItem(event: android.view.DragEvent): String? =
        (event.localState as? String)?.takeIf { it.startsWith("item:") }?.substringAfter("item:")

    private fun dragged(event: android.view.DragEvent): Pair<String, ToolbarKey>? {
        val state = event.localState as? String ?: return null
        if (state.startsWith("item:")) return null
        val key = runCatching { ToolbarKey.valueOf(state.substringAfter(':')) }.getOrNull() ?: return null
        return state.substringBefore(':') to key
    }

    private const val PREF_MENU_ORDER = "open_keyboard_menu_order"
    private var currentGrid: TilePager? = null
    /** The page the menu was on, so it reopens there after moving a tile. */
    private var lastPage = 0
    private const val COLUMNS = 4
    private const val ANIM_MS = 140L

    private fun anim(v: View, tx: Float, ty: Float = 0f) {
        v.animate().translationX(tx).translationY(ty).setDuration(ANIM_MS).start()
    }

    /** Android-style "move mode": every button wobbles gently while one is being dragged. */
    private val jiggles = java.util.WeakHashMap<View, android.animation.Animator>()

    private fun startJiggle(group: ViewGroup?) {
        group ?: return
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (jiggles[v]?.isRunning == true) continue
            val a = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -2.2f, 2.2f).apply {
                duration = 110L + (i % 3) * 15L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                startDelay = (i * 37L) % 120L
            }
            a.start()
            jiggles[v] = a
        }
    }

    private fun stopJiggle(group: ViewGroup?) {
        group ?: return
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            jiggles.remove(v)?.cancel()
            v.rotation = 0f
        }
    }

    private fun resetAll(group: ViewGroup?) {
        group ?: return
        for (i in 0 until group.childCount) group.getChildAt(i).apply { animate().cancel(); translationX = 0f; translationY = 0f; alpha = 1f }
    }

    // ---- the top bar ----

    /** Where along the bar a drop at [x] (in the strip) would go, among the bar buttons other than [key]. */
    private fun barIndex(strip: View, toolbar: ViewGroup, x: Float, key: ToolbarKey): Int {
        val loc = IntArray(2); strip.getLocationInWindow(loc)
        val wx = x + loc[0]
        var index = 0
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i)
            if (c.tag == key) continue
            val l = IntArray(2); c.getLocationInWindow(l)
            if (wx > l[0] + c.width / 2) index++
        }
        return index
    }

    /** Slides the bar buttons apart to show where the dragged one would land. */
    private fun previewBar(toolbar: ViewGroup, key: ToolbarKey, index: Int) {
        val others = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filter { it.tag != key }
        val n = others.size
        val fromBar = (0 until toolbar.childCount).any { toolbar.getChildAt(it).tag == key }
        val total = toolbar.width.toFloat().takeIf { it > 0 } ?: return
        val oldSlots = toolbar.childCount
        val newSlots = n + 1
        others.forEachIndexed { j, v ->
            val current = (0 until toolbar.childCount).indexOfFirst { toolbar.getChildAt(it) === v }
            val slot = if (j < index) j else j + 1
            val tx = (slot + 0.5f) * total / newSlots - (current + 0.5f) * total / oldSlots
            anim(v, tx)
        }
        if (fromBar) (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.firstOrNull { it.tag == key }?.alpha = 0f
    }

    /** Drops on the top bar: add a menu button to the bar, or move a bar button along it. */
    fun onBarDrag(strip: View, event: android.view.DragEvent, toolbar: ViewGroup): Boolean {
        val (from, key) = dragged(event) ?: return false
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                startJiggle(toolbar)
                if (from == "bar") (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.firstOrNull { it.tag == key }?.alpha = 0.25f
            }
            android.view.DragEvent.ACTION_DRAG_LOCATION -> if (key != ToolbarKey.VOICE) previewBar(toolbar, key, barIndex(strip, toolbar, event.x, key))
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                resetAll(toolbar)
                if (from == "bar") (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.firstOrNull { it.tag == key }?.alpha = 0.25f
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> { resetAll(toolbar); stopJiggle(toolbar) }
            android.view.DragEvent.ACTION_DROP -> {
                val index = barIndex(strip, toolbar, event.x, key)
                resetAll(toolbar); stopJiggle(toolbar)
                val prefs = strip.context.prefs()
                val bar = helium314.keyboard.latin.utils.getBarKeys(prefs)
                val hasMic = ToolbarKey.VOICE in bar || key == ToolbarKey.VOICE
                val buttons = bar.filter { it != ToolbarKey.VOICE && it != key }.toMutableList()
                if (key != ToolbarKey.VOICE) {
                    buttons.add(index.coerceIn(0, buttons.size), key)
                    // a full bar pushes its last other button back into the menu
                    while (buttons.size > MAX_ON_BAR) buttons.removeAt(buttons.indexOfLast { it != key })
                }
                helium314.keyboard.latin.utils.setBarKeys(prefs, if (hasMic) buttons + ToolbarKey.VOICE else buttons)
                onBarChanged?.invoke()
                refreshMenu(strip.context)
            }
        }
        return true
    }

    // ---- the menu grid: pages of tiles you swipe between ----

    /** Which tile spot (counting across all pages) a drop at [x], [y] in the pager would go to. */
    private fun gridIndex(pager: TilePager, x: Float, y: Float, count: Int): Int =
        (pager.page * pager.perPage + pager.slotAt(x, y)).coerceIn(0, count)

    /** Slides the tiles along to open a gap where the dragged one would land. */
    private fun previewGrid(pager: TilePager, label: String?, index: Int) {
        val children = (0 until pager.childCount).map { pager.getChildAt(it) }
        val others = children.filter { it.tag != label }
        others.forEachIndexed { j, v ->
            val slot = if (j < index) j else j + 1
            val (nx, ny) = pager.slotPosition(slot)
            anim(v, nx - v.left, ny - v.top)
        }
        children.firstOrNull { it.tag == label }?.alpha = 0f
    }

    /** While dragging, hold a tile at the left or right edge to turn the page. */
    private var edgeFlip: Runnable? = null
    private fun edgeCheck(pager: TilePager, x: Float) {
        val dir = when { x < pager.width * 0.07f -> -1; x > pager.width * 0.93f -> 1; else -> 0 }
        if (dir == 0) { edgeFlip?.let { pager.removeCallbacks(it) }; edgeFlip = null; return }
        if (edgeFlip != null) return
        edgeFlip = Runnable { edgeFlip = null; pager.goTo(pager.page + dir) }.also { pager.postDelayed(it, 550) }
    }

    private fun labelOf(key: ToolbarKey) = menuLabels[key]
    private val menuLabels = mutableMapOf<ToolbarKey, String>()

    /** Drops on the menu: take a button off the bar, or move a menu button to another spot. */
    private fun onMenuDrag(menu: View, event: android.view.DragEvent): Boolean {
        val itemId = draggedItem(event)
        val (from, key) = if (itemId != null) "menu" to null else (dragged(event) ?: return false)
        val pager = currentGrid ?: return false
        val label = itemId ?: labelOf(key!!)
        val othersCount = (0 until pager.childCount).count { pager.getChildAt(it).tag != label }
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> if (startJiggle(pager) == Unit && from == "menu") previewGrid(pager, label, (0 until pager.childCount).indexOfFirst { pager.getChildAt(it).tag == label })
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                edgeCheck(pager, event.x)
                previewGrid(pager, label, gridIndex(pager, event.x, event.y, othersCount))
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                edgeFlip?.let { pager.removeCallbacks(it) }; edgeFlip = null
                resetAll(pager)
                if (from == "menu") (0 until pager.childCount).map { pager.getChildAt(it) }.firstOrNull { it.tag == label }?.alpha = 0.25f
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> { edgeFlip?.let { pager.removeCallbacks(it) }; edgeFlip = null; resetAll(pager); stopJiggle(pager) }
            android.view.DragEvent.ACTION_DROP -> {
                val index = gridIndex(pager, event.x, event.y, othersCount)
                stopJiggle(pager)
                lastPage = pager.page
                val visible = (0 until pager.childCount).mapNotNull { pager.getChildAt(it).tag as? String }.filter { it != label }.toMutableList()
                if (label != null) visible.add(index.coerceIn(0, visible.size), label)
                val prefs = menu.context.prefs()
                val saved = prefs.getString(PREF_MENU_ORDER, "")!!.split(",").filter { it.isNotEmpty() && it !in visible }
                prefs.edit { putString(PREF_MENU_ORDER, (visible + saved).joinToString(",")) }
                if (from == "bar" && key != null) {
                    helium314.keyboard.latin.utils.setBarKeys(prefs, helium314.keyboard.latin.utils.getBarKeys(prefs) - key)
                    onBarChanged?.invoke()
                }
                refreshMenu(menu.context)
            }
        }
        return true
    }

    private fun refreshMenu(context: Context) {
        val onCode = lastOnCode ?: return
        if (menuView?.parent != null && menuView?.tag == OVERLAY_TAG && menuView is TilePager) {
            menuView?.post { showMenu(context, onCode) }
        }
    }

    private fun showMenu(context: Context, onCode: (Int) -> Unit) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        lastOnCode = onCode
        hide()
        val colors = Settings.getValues().mColors
        val textColor = colors.get(ColorType.KEY_TEXT)
        val tileColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        fun toolbarIcon(key: ToolbarKey) = { KeyboardIconsSet.instance.getNewDrawable(key.name, context) }
        fun ownIcon(res: Int) = { ContextCompat.getDrawable(context, res)?.mutate() }

        fun keyItem(label: String, key: ToolbarKey, action: (() -> Unit)? = null) =
            Item(label, toolbarIcon(key), key, action = action ?: { onCode(helium314.keyboard.latin.utils.getCodeForToolbarKey(key)) })
        val allItems = listOf(
            Item("Theme", ownIcon(R.drawable.ic_menu_theme)) { openSettings(context, SettingsDestination.Theme) },
            Item("Resize", ownIcon(R.drawable.ic_menu_resize)) { showResize(context) },
            keyItem("Stickers", ToolbarKey.STICKER) { helium314.keyboard.media.MediaPanel.toggle(context, false) },
            keyItem("GIFs", ToolbarKey.GIF) { helium314.keyboard.media.MediaPanel.toggle(context, true) },
            keyItem("Voice typing", ToolbarKey.VOICE),
            Item("Translate", plugin = "translate", icon = ownIcon(R.drawable.ic_menu_translate)) { TranslatePanel.show(context) },
            Item(MadladTranslator.voiceTarget(context)?.let { "Voice → " + TranslatePanel.name(it) } ?: "Voice translate", plugin = "translate",
                icon = toolbarIcon(ToolbarKey.VOICE), idOverride = "Voice translate") { TranslatePanel.show(context, forVoice = true) },
            Item("Writing help", plugin = "writing", icon = ownIcon(R.drawable.ic_menu_ai)) { WritingTools.show(context) },
            Item("Scan text", plugin = "scan", icon = ownIcon(R.drawable.ic_menu_scan)) {
                hide()
                runCatching {
                    context.startActivity(android.content.Intent(context, ScanTextActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { android.widget.Toast.makeText(context, "Scan text couldn't open", android.widget.Toast.LENGTH_SHORT).show() }
            },
            keyItem("One-handed", ToolbarKey.ONE_HANDED),
            keyItem("Floating", ToolbarKey.FLOATING),
            keyItem("Text editing", ToolbarKey.DPAD),
            keyItem("Clipboard", ToolbarKey.CLIPBOARD),
            keyItem("Emoji", ToolbarKey.EMOJI),
            keyItem("Undo", ToolbarKey.UNDO),
            keyItem("Redo", ToolbarKey.REDO),
            keyItem("Numbers", ToolbarKey.NUMPAD),
            keyItem("Split", ToolbarKey.SPLIT),
            keyItem("Incognito", ToolbarKey.INCOGNITO),
            keyItem("Settings", ToolbarKey.SETTINGS),
            Item("Switch keyboard", ownIcon(R.drawable.ic_ime_switcher)) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            },
        ).filter { it.plugin == null || Plugins.isOn(context, it.plugin) } +
            Plugins.enabledAppActions(context).map { app ->
                Item(app.label, { app.icon?.mutate() }, tint = false) { // the app's own colourful icon
                    hide()
                    var c: Context = context
                    while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
                    Plugins.runAppAction(context, (c as? LatinIME)?.currentInputConnection, app)
                }
            }
        // buttons already on the top bar aren't repeated here
        val onBar = helium314.keyboard.latin.utils.getBarKeys(context.prefs())
        allItems.forEach { if (it.key != null) menuLabels[it.key] = it.id }
        val order = context.prefs().getString(PREF_MENU_ORDER, "")!!.split(",")
        val items = allItems.filter { it.key == null || it.key !in onBar }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 1000 + allItems.indexOf(it) else i } }

        val pager = TilePager(context, dp(context, 8f).toInt(), dp(context, 4f).toInt(), dp(context, 64f).toInt())
        for (item in items) {
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val p = dp(context, 6f).toInt()
                setPadding(p, dp(context, 10f).toInt(), p, dp(context, 8f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 22f)
                    setColor(tileColor)
                }
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                tag = item.id
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (item.label != "Resize") hide()
                    item.action()
                }
                setOnLongClickListener { if (item.key != null) startDrag(it, "menu", item.key) else startItemDrag(it, item.id); true }
            }
            val icon = ImageView(context).apply {
                setImageDrawable(item.icon())
                if (item.tint) setColorFilter(textColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(context, 26f).toInt(), dp(context, 26f).toInt()))
            val label = TextView(context).apply {
                text = item.label
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(context, 4f).toInt(), 0, 0)
            }
            tile.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pager.addView(tile)
        }

        currentGrid = pager
        pager.apply {
            dotColor = textColor
            startPage = lastPage
            setOnDragListener { v, e -> onMenuDrag(v, e) }
            // a plain colour: themes with a photo/gradient background can't be drawn on a view that has no size yet
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true // don't let touches reach the keys underneath
            onPageChanged = { lastPage = it }
        }
        pager.tag = OVERLAY_TAG
        pager.clearSystemButtons()
        wrapper.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuView = pager
    }

    private fun openSettings(context: Context, destination: String) {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        (c as? LatinIME)?.launchSettings(destination)
    }

    // ---- resize ----

    private fun heightKey(): String {
        val landscape = Settings.getValues().mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
        return createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
            findIndexOfDefaultSetting(landscape, FoldableUtils.isFolded), 2)
    }

    private fun bottomKey(): String {
        val landscape = Settings.getValues().mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
        return createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
            findIndexOfDefaultSetting(landscape, FoldableUtils.isFolded), 2)
    }

    private fun showResize(context: Context) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        hide()
        val view = ResizeOverlay(context)
        view.tag = OVERLAY_TAG
        view.clearSystemButtons()
        wrapper.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        resizeView = view
    }

    /** Blue outline with drag handles: drag the top handle to change the height, the bottom one to raise the keyboard. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class ResizeOverlay(context: Context) : FrameLayout(context) {
        // take the keyboard's size, never make the keyboard taller
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private val blue = Color.rgb(66, 133, 244)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = blue; strokeWidth = dp(context, 3f) }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        private val shade = Paint().apply { color = ColorUtils.setAlphaComponent(Color.BLACK, 70) }
        private val handleZone = dp(context, 40f)
        private val defaultHeight: Float
        private var dragging = 0 // 1 = top handle, 2 = bottom handle
        private var lastY = 0f

        init {
            setWillNotDraw(false)
            val res = context.resources
            defaultHeight = helium314.keyboard.latin.utils.ResourceUtils.getKeyboardHeight(res, Settings.getValues()) /
                Settings.getValues().mKeyboardHeightScale
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(button("Reset") { reset() })
                addView(button("Done") { hide() })
            }
            addView(buttons, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        private fun button(text: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val h = dp(context, 22f).toInt(); val v = dp(context, 10f).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply { cornerRadius = dp(context, 24f); setColor(blue) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = dp(context, 8f).toInt(); setMargins(m, 0, m, 0)
            }
            setOnClickListener { onClick() }
        }

        private fun reset() {
            context.prefs().edit { remove(heightKey()); remove(bottomKey()) }
            KeyboardSwitcher.getInstance().reloadKeyboard()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, shade)
            val inset = outline.strokeWidth / 2
            canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), dp(context, 12f), dp(context, 12f), outline)
            val hw = dp(context, 56f); val hh = dp(context, 6f); val r = hh / 2
            val top = dp(context, 10f)
            canvas.drawRoundRect(RectF(w / 2 - hw / 2, top, w / 2 + hw / 2, top + hh), r, r, handle)
            val bottom = h - dp(context, 10f)
            canvas.drawRoundRect(RectF(w / 2 - hw / 2, bottom - hh, w / 2 + hw / 2, bottom), r, r, handle)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = when {
                        event.y < handleZone -> 1
                        event.y > height - handleZone -> 2
                        else -> 0
                    }
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - lastY
                    if (dragging == 0 || abs(dy) < dp(context, 4f)) return true
                    lastY = event.rawY
                    val prefs = context.prefs()
                    val sv = Settings.getValues()
                    if (dragging == 1) {
                        // dragging up makes the keys taller
                        val newScale = ((sv.mKeyboardHeightScale - dy / defaultHeight) * 100).roundToInt() / 100f
                        val clamped = newScale.coerceIn(0.5f, 1.6f)
                        if (clamped == sv.mKeyboardHeightScale) return true
                        prefs.edit { putFloat(heightKey(), clamped) }
                    } else {
                        // dragging up adds space below the keys, so the keyboard sits higher
                        val unit = defaultHeight * 0.05f
                        val newScale = ((sv.mBottomPaddingScale - dy / unit) * 10).roundToInt() / 10f
                        val clamped = newScale.coerceIn(0f, 5f)
                        if (clamped == sv.mBottomPaddingScale) return true
                        prefs.edit { putFloat(bottomKey(), clamped) }
                    }
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
            }
            return true
        }

    }

    /**
     * Keeps a panel's content above the phone's own buttons along the bottom (hide keyboard, switch
     * keyboard), which sit on top of the keyboard area and would otherwise take taps on the last row.
     */
    private fun View.clearSystemButtons() {
        fun apply(insets: android.view.WindowInsets?) {
            val nav = insets?.let {
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    it.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.tappableElement()).bottom
                else @Suppress("DEPRECATION") it.systemWindowInsetBottom
            } ?: 0
            val bottom = maxOf(nav, dp(context, 48f).toInt())
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
            if (this is ViewGroup) clipToPadding = false
        }
        apply(rootWindowInsets ?: KeyboardSwitcher.getInstance().wrapperView?.rootWindowInsets)
    }

    /**
     * The grid menu: 4 columns and up to 4 rows a page (fewer if the keyboard is short), swipe left or
     * right for the next page, dots along the bottom. Pages never run under the phone's own buttons.
     */
    private class TilePager(context: Context, private val pad: Int, private val gap: Int, private val minCell: Int) : ViewGroup(context) {
        var dotColor = 0
        var startPage = 0
        var onPageChanged: ((Int) -> Unit)? = null
        var perPage = 16; private set
        private var rows = 4
        private var cellW = 0f
        private var cellH = 0f
        private val dotsH get() = (resources.displayMetrics.density * 18).toInt()
        val pageCount get() = maxOf(1, (childCount + perPage - 1) / perPage)
        var page = 0; private set

        init { setWillNotDraw(false) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            // take the keyboard's size instead of making the keyboard taller
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
            setMeasuredDimension(w, h)
            if (h == 0) return
            val availH = h - paddingTop - pad - paddingBottom - dotsH
            rows = (availH / minCell).coerceIn(1, 4)
            perPage = COLUMNS * rows
            cellW = (w - 2f * pad) / COLUMNS
            cellH = availH.toFloat() / rows
            val cw = MeasureSpec.makeMeasureSpec((cellW - 2 * gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
            val ch = MeasureSpec.makeMeasureSpec((cellH - 2 * gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        }

        /** Top-left corner of tile spot [slot] (counting across pages), in this view's own coordinates. */
        fun slotPosition(slot: Int): Pair<Float, Float> {
            val p = slot / perPage; val s = slot % perPage
            return (p * width + pad + (s % COLUMNS) * cellW + gap) to (paddingTop + pad + (s / COLUMNS) * cellH + gap)
        }

        /** The spot on the current page under [x], [y] (as seen on screen). */
        fun slotAt(x: Float, y: Float): Int {
            if (cellW <= 0f || cellH <= 0f) return 0
            val col = ((x - pad) / cellW).toInt().coerceIn(0, COLUMNS - 1)
            val row = ((y - paddingTop - pad) / cellH).toInt().coerceIn(0, rows - 1)
            return row * COLUMNS + col
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val (x, y) = slotPosition(i)
                c.layout(x.toInt(), y.toInt(), x.toInt() + c.measuredWidth, y.toInt() + c.measuredHeight)
            }
            if (startPage >= 0) { page = startPage.coerceIn(0, pageCount - 1); startPage = -1 }
            page = page.coerceIn(0, pageCount - 1)
            if (!scroller.isRunning) scrollTo(page * width, 0)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (pageCount < 2) return
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val d = resources.displayMetrics.density
            val r = 3.5f * d; val space = 14f * d
            val cy = height - paddingBottom - dotsH / 2f
            val startX = scrollX + width / 2f - (pageCount - 1) * space / 2f
            for (i in 0 until pageCount) {
                paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(dotColor, if (i == page) 255 else 90)
                canvas.drawCircle(startX + i * space, cy, r, paint)
            }
        }

        // ---- swiping between pages ----
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var downScroll = 0
        private var dragging = false
        private var velocity: android.view.VelocityTracker? = null
        private var scroller = android.animation.ValueAnimator()

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; downScroll = scrollX; dragging = false
                    velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                    velocity?.addMovement(ev)
                    if (scroller.isRunning) { scroller.cancel(); downScroll = scrollX; dragging = true }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (pageCount > 1 && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        dragging = true; downX = ev.x; downScroll = scrollX
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            return dragging
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            velocity?.addMovement(ev)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downScroll = scrollX; return true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    if (!dragging && kotlin.math.abs(dx) > slop && pageCount > 1) { dragging = true; downX = ev.x; downScroll = scrollX }
                    if (dragging) {
                        val max = (pageCount - 1) * width
                        var target = downScroll - (ev.x - downX)
                        if (target < 0) target /= 3f else if (target > max) target = max + (target - max) / 3f // a little give at the ends
                        scrollTo(target.toInt(), 0)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        val moved = scrollX - page * width
                        val target = when {
                            vx < -800 -> page + 1
                            vx > 800 -> page - 1
                            moved > width / 3 -> page + 1
                            moved < -width / 3 -> page - 1
                            else -> page
                        }
                        goTo(target)
                    }
                    dragging = false
                    velocity?.recycle(); velocity = null
                }
            }
            return true
        }

        fun goTo(target: Int) {
            page = target.coerceIn(0, pageCount - 1)
            onPageChanged?.invoke(page)
            scroller.cancel()
            scroller = android.animation.ValueAnimator.ofInt(scrollX, page * width).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { scrollTo(it.animatedValue as Int, 0); invalidate() }
                start()
            }
            invalidate()
        }
    }

    /** Takes the keyboard's size instead of making the keyboard taller. */
    private class FitScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
