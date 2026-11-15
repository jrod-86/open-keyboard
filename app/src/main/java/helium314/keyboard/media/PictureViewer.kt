// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity

/** Full-screen look at a finished picture: pinch or double-tap to zoom, drag to look around, tap or Back to close. */
object PictureViewer {
    /** [actions]: buttons along the bottom, e.g. Export, Save, Delete; each closes the viewer if it returns true. */
    fun show(context: Context, bmp: Bitmap, actions: List<Pair<String, () -> Boolean>> = emptyList()) {
        showMany(context, 1, 0, { bmp }, actions.map { (l, a) -> l to { _: Int -> a() } })
    }

    /** Pages through [count] pictures (the gallery): swipe left or right for the next one. Actions get the index shown. */
    /** Lets the caller update an open viewer, e.g. after deleting the picture shown. */
    class Pager(val refresh: () -> Unit, val close: () -> Unit)

    fun showMany(context: Context, count: Int, start: Int, load: (Int) -> Bitmap?, actions: List<Pair<String, (Int) -> Boolean>>,
                 total: () -> Int = { count }): Pager? {
        val first = load(start) ?: return null
        var index = start
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val counter = TextView(context).apply { textSize = 15f; setTextColor(0xDDFFFFFF.toInt()); setPadding(48, 48, 48, 48) }
        fun showCount() { val n = total(); counter.text = if (n > 1) "${index + 1} / $n" else "" }
        lateinit var view: ZoomImage
        view = ZoomImage(context, first, { dialog.dismiss() }) { dir ->
            // dir +1 = next (swiped left), -1 = previous; false when there's nothing that way
            val n = total()
            val next = index + dir
            if (next < 0 || next >= n) return@ZoomImage false
            val b = load(next) ?: return@ZoomImage false
            index = next; view.setPicture(b); showCount(); true
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        showCount()
        val close = TextView(context).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); setPadding(40, 40, 40, 40)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        val hint = TextView(context).apply {
            text = if (count > 1) "Swipe for the next picture · pinch to zoom · tap to close" else "Pinch or double-tap to zoom · tap the picture to close"; textSize = 13f; setTextColor(0xAAFFFFFF.toInt()); setPadding(0, 0, 0, 60)
        }
        val density = context.resources.displayMetrics.density
        if (actions.isNotEmpty()) {
            val bar = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (28 * density).toInt())
                setBackgroundColor(0x99000000.toInt())
            }
            for ((label, act) in actions) {
                bar.addView(TextView(context).apply {
                    text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 22 * density }
                    setOnClickListener { if (act(index)) dialog.dismiss() }
                }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (4 * density).toInt(); marginEnd = (4 * density).toInt()
                })
            }
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            hint.setPadding(0, 0, 0, (100 * density).toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        return Pager(refresh = {
            // the list changed (a picture was deleted): show what's now at this place, or close when it's empty
            val n = total()
            if (n <= 0) dialog.dismiss()
            else { index = index.coerceAtMost(n - 1); load(index)?.let { view.setPicture(it) }; showCount() }
        }, close = { dialog.dismiss() })
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class ZoomImage(context: Context, private var bmp: Bitmap, private val onTap: () -> Unit,
                            private val onSwipe: (Int) -> Boolean = { false }) : ImageView(context) {
        private var dragX = 0f

        fun setPicture(b: Bitmap) { bmp = b; setImageBitmap(b); fit() }

        /** Slides the picture off to one side, swaps it, and slides the new one in; springs back if there's none. */
        private fun settle(fling: Int) {
            val dir = if (fling != 0) fling else if (dragX < -width * 0.2f) 1 else if (dragX > width * 0.2f) -1 else 0
            if (dir == 0) { animate().translationX(0f).setDuration(150).start(); return }
            animate().translationX(-dir * width.toFloat()).setDuration(140).withEndAction {
                if (onSwipe(dir)) {
                    translationX = dir * width.toFloat()
                    animate().translationX(0f).setDuration(170).start()
                } else animate().translationX(0f).setDuration(150).start()
            }.start()
            dragX = 0f
        }
        private val m = Matrix()
        private var base = 1f // scale that fits the whole picture on screen
        private var zoom = 1f // extra zoom on top of base, 1..6

        init {
            setImageBitmap(bmp)
            scaleType = ScaleType.MATRIX
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fit()
        }

        private fun fit() {
            if (width == 0 || height == 0) return
            base = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            zoom = 1f
            m.reset()
            m.postScale(base, base)
            m.postTranslate((width - bmp.width * base) / 2, (height - bmp.height * base) / 2)
            imageMatrix = m
        }

        /** Keeps the picture from sliding off screen: centred when smaller than the screen, edges pinned when bigger. */
        private fun clamp() {
            val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()).also { m.mapRect(it) }
            val dx = if (r.width() <= width) width / 2f - r.centerX() else when { r.left > 0 -> -r.left; r.right < width -> width - r.right; else -> 0f }
            val dy = if (r.height() <= height) height / 2f - r.centerY() else when { r.top > 0 -> -r.top; r.bottom < height -> height - r.bottom; else -> 0f }
            m.postTranslate(dx, dy)
            imageMatrix = m
        }

        private fun zoomBy(f: Float, x: Float, y: Float) {
            val target = (zoom * f).coerceIn(1f, 6f)
            val k = target / zoom
            zoom = target
            m.postScale(k, k, x, y)
            clamp()
        }

        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { dragX = 0f; translationX = 0f; zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
        })

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom > 1.01f) { m.postTranslate(-dx, -dy); clamp() }
                else { dragX -= dx; translationX = dragX } // not zoomed: the picture follows the finger sideways
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zoom > 1.01f || kotlin.math.abs(vx) < kotlin.math.abs(vy) || kotlin.math.abs(vx) < 800f) return false
                flung = if (vx < 0) 1 else -1
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.01f) fit() else zoomBy(2.5f, e.x, e.y)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        })

        private var flung = 0

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { flung = 0; dragX = 0f; animate().cancel(); translationX = 0f }
            scaler.onTouchEvent(e)
            if (!scaler.isInProgress) gestures.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (zoom <= 1.01f && (dragX != 0f || flung != 0)) settle(flung)
            }
            return true
        }
    }
}
