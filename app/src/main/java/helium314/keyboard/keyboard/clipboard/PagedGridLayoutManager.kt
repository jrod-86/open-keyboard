// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Open Keyboard: clipboard as small cards, [columns] across, in pages you swipe left and right
 * (same feel as My info). Newest clip first, reading left to right, top to bottom.
 */
class PagedGridLayoutManager(private val columns: Int, private val minCell: Int, private val gap: Int, private val dotsSpace: Int) :
        RecyclerView.LayoutManager() {
    private var offset = 0 // horizontal scroll in px
    private var rows = 1
    private var recycler: RecyclerView? = null
    var dotColor = 0xFFFFFFFF.toInt()

    private val pageW get() = width - paddingLeft - paddingRight
    private val perPage get() = columns * rows
    val pageCount get() = if (itemCount == 0) 0 else (itemCount + perPage - 1) / perPage
    private val maxOffset get() = maxOf(0, (pageCount - 1) * pageW)

    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun canScrollHorizontally() = true
    override fun isAutoMeasureEnabled() = false

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recycler = view
        view.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                if (abs(velocityX) < 300 || pageW <= 0) return false
                val current = (offset.toFloat() / pageW).let { if (velocityX > 0) kotlin.math.floor(it) else kotlin.math.ceil(it) }.toInt()
                val target = (current + if (velocityX > 0) 1 else -1).coerceIn(0, maxOf(0, pageCount - 1))
                view.smoothScrollBy(target * pageW - offset, 0)
                return true
            }
        }
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                val pages = pageCount
                if (pages < 2 || pageW <= 0) return
                val current = (offset.toFloat() / pageW).roundToInt()
                val r = dotsSpace / 6f
                val step = r * 4
                var x = parent.width / 2f - (pages - 1) * step / 2
                val y = parent.height - dotsSpace / 2f
                for (i in 0 until pages) {
                    paint.color = dotColor
                    paint.alpha = if (i == current) 230 else 80
                    c.drawCircle(x, y, r, paint)
                    x += step
                }
            }
        })
    }

    override fun onScrollStateChanged(state: Int) {
        // let go between pages: settle on the nearest one
        if (state != RecyclerView.SCROLL_STATE_IDLE || pageW <= 0) return
        val target = ((offset.toFloat() / pageW).roundToInt() * pageW).coerceIn(0, maxOffset)
        if (target != offset) recycler?.smoothScrollBy(target - offset, 0)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || pageW <= 0) { offset = 0; return }
        val availH = height - paddingTop - paddingBottom - dotsSpace
        rows = (availH / minCell).coerceIn(1, 4)
        offset = offset.coerceIn(0, maxOffset)
        fill(recycler)
    }

    private fun fill(recycler: RecyclerView.Recycler) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0) return
        val availH = height - paddingTop - paddingBottom - dotsSpace
        val cellW = pageW.toFloat() / columns
        val cellH = availH.toFloat() / rows
        // only the pages on screen (and one either side) are built
        val firstPage = maxOf(0, offset / maxOf(1, pageW) - 1)
        val lastPage = minOf(pageCount - 1, offset / maxOf(1, pageW) + 1)
        for (i in firstPage * perPage until minOf(itemCount, (lastPage + 1) * perPage)) {
            val page = i / perPage
            val idx = i % perPage
            val col = idx % columns
            val row = idx / columns
            val left = (paddingLeft + page * pageW + col * cellW + gap / 2f - offset).roundToInt()
            val top = (paddingTop + row * cellH + gap / 2f).roundToInt()
            val w = (cellW - gap).roundToInt()
            val h = (cellH - gap).roundToInt()
            val v = recycler.getViewForPosition(i)
            (v.layoutParams as RecyclerView.LayoutParams).apply { marginStart = 0; marginEnd = 0; topMargin = 0; bottomMargin = 0 }
            addView(v)
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layoutDecorated(v, left, top, left + w, top + h)
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val newOffset = (offset + dx).coerceIn(0, maxOffset)
        val moved = newOffset - offset
        if (moved == 0) return 0
        val oldPage = offset / maxOf(1, pageW)
        offset = newOffset
        if (offset / maxOf(1, pageW) != oldPage) {
            fill(recycler)
            for (holder in recycler.scrapList.toList()) recycler.recycleView(holder.itemView)
        } else offsetChildrenHorizontal(-moved)
        return moved
    }

    override fun scrollToPosition(position: Int) {
        if (pageW <= 0 || perPage <= 0) return
        offset = ((position / perPage) * pageW).coerceIn(0, maxOffset)
        requestLayout()
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State?, position: Int) {
        if (pageW <= 0 || perPage <= 0) return
        val target = ((position / perPage) * pageW).coerceIn(0, maxOffset)
        recyclerView.smoothScrollBy(target - offset, 0)
    }
}
