// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Open Keyboard: newer Android draws apps edge to edge and ignores "adjustResize", so our own
 * simple screens shrink themselves above the keyboard and system bars here.
 */
fun View.fitAboveKeyboard() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (_, bottom) = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            0 to maxOf(bars.bottom, ime.bottom)
        } else {
            @Suppress("DEPRECATION") 0 to insets.systemWindowInsetBottom
        }
        v.setPadding(base[0], base[1], base[2], base[3] + bottom) // the title bar already keeps the top clear
        insets
    }
    requestApplyInsets()
}
