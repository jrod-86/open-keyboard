// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Open Keyboard: newer Android draws apps edge to edge and ignores "adjustResize", so our own
 * simple screens shrink themselves above the keyboard and system bars here.
 */
fun View.fitAboveKeyboard(includeTop: Boolean = false) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            bars.top to maxOf(bars.bottom, ime.bottom)
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
        // screens without a title bar keep clear of the status bar themselves
        v.setPadding(base[0], base[1] + if (includeTop) top else 0, base[2], base[3] + bottom)
        insets
    }
    requestApplyInsets()
}
