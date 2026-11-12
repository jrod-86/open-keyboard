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
        onHidden?.invoke()
    }

    /** Lets the top bar tidy itself when a panel closes, so it is never left empty. */
    var onHidden: (() -> Unit)? = null

    /** Shows another full-keyboard panel (stickers, GIFs) in the menu's place. */
    fun showOverlay(view: View) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        hide()
        view.tag = OVERLAY_TAG
        wrapper.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuView = view
    }

    val currentOverlay: View? get() = menuView?.takeIf { it.parent != null }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private class Item(val label: String, val icon: () -> android.graphics.drawable.Drawable?, val action: () -> Unit)

    private fun showMenu(context: Context, onCode: (Int) -> Unit) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        hide()
        val colors = Settings.getValues().mColors
        val textColor = colors.get(ColorType.KEY_TEXT)
        val tileColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        fun toolbarIcon(key: ToolbarKey) = { KeyboardIconsSet.instance.getNewDrawable(key.name, context) }
        fun ownIcon(res: Int) = { ContextCompat.getDrawable(context, res)?.mutate() }

        val items = listOf(
            Item("Theme", ownIcon(R.drawable.ic_menu_theme)) { openSettings(context, SettingsDestination.Theme) },
            Item("Resize", ownIcon(R.drawable.ic_menu_resize)) { showResize(context) },
            Item("One-handed", toolbarIcon(ToolbarKey.ONE_HANDED)) { onCode(KeyCode.TOGGLE_ONE_HANDED_MODE) },
            Item("Floating", toolbarIcon(ToolbarKey.FLOATING)) { onCode(KeyCode.TOGGLE_FLOATING_WINDOW) },
            Item("Text editing", toolbarIcon(ToolbarKey.DPAD)) { onCode(KeyCode.DPAD) },
            Item("Clipboard", toolbarIcon(ToolbarKey.CLIPBOARD)) { onCode(KeyCode.CLIPBOARD) },
            Item("Emoji", toolbarIcon(ToolbarKey.EMOJI)) { onCode(KeyCode.EMOJI) },
            Item("Undo", toolbarIcon(ToolbarKey.UNDO)) { onCode(KeyCode.UNDO) },
            Item("Numbers", toolbarIcon(ToolbarKey.NUMPAD)) { onCode(KeyCode.NUMPAD) },
            Item("Split", toolbarIcon(ToolbarKey.SPLIT)) { onCode(KeyCode.SPLIT_LAYOUT) },
            Item("Incognito", toolbarIcon(ToolbarKey.INCOGNITO)) { onCode(KeyCode.TOGGLE_INCOGNITO_MODE) },
            Item("Settings", toolbarIcon(ToolbarKey.SETTINGS)) { onCode(KeyCode.SETTINGS) },
            Item("Switch keyboard", ownIcon(R.drawable.ic_ime_switcher)) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            },
        )

        val columns = 4
        val grid = GridLayout(context).apply {
            columnCount = columns
            val pad = dp(context, 8f).toInt()
            setPadding(pad, pad, pad, pad)
        }
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
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (item.label != "Resize") hide()
                    item.action()
                }
            }
            val icon = ImageView(context).apply {
                setImageDrawable(item.icon())
                setColorFilter(textColor)
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
            val m = dp(context, 4f).toInt()
            grid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; setMargins(m, m, m, m) })
        }

        val scroll = FitScrollView(context).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // a plain colour: themes with a photo/gradient background can't be drawn on a view that has no size yet
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true // don't let touches reach the keys underneath
        }
        scroll.tag = OVERLAY_TAG
        wrapper.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuView = scroll
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

    /** Takes the keyboard's size instead of making the keyboard taller. */
    private class FitScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
