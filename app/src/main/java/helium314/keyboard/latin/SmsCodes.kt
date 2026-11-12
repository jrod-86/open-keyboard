// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * One-tap SMS codes: when a message with a login or verification code arrives, the keyboard offers
 * the code in its suggestion bar for a few minutes. Needs notification access, which the user turns
 * on in settings. Only the latest code is kept, in memory, and nothing leaves the phone.
 */
object SmsCodes {
    private const val VALID_MS = 3 * 60 * 1000L

    @Volatile private var code: String? = null
    @Volatile private var at = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Set by the keyboard so a new code shows straight away if the keyboard is open. */
    @JvmStatic var onNewCode: Runnable? = null

    @JvmStatic fun recent(): String? {
        val c = code ?: return null
        if (SystemClock.elapsedRealtime() - at > VALID_MS) { code = null; return null }
        return c
    }

    @JvmStatic fun used() { code = null }

    fun isAllowed(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private val keywords = Regex("(?i)(code|otp|verif|pass ?code|\\bpin\\b|one[- ]time|security|2fa|log ?in|sign[- ]in|authenticat)")
    private val googleStyle = Regex("\\bG-(\\d{4,8})\\b")
    private val number = Regex("(?<![\\d$£€.,:/-])(\\d{4,8})(?![\\d.,:/%-]?\\d)")

    /** Finds a one-time code in a message, or null. */
    fun find(text: String): String? {
        googleStyle.find(text)?.let { return it.groupValues[1] }
        if (!keywords.containsMatchIn(text)) return null
        return number.findAll(text).map { it.groupValues[1] }.firstOrNull { !(it.length == 4 && it.startsWith("20") && it.toInt() in 2000..2099) }
    }

    fun offer(found: String) {
        code = found
        at = SystemClock.elapsedRealtime()
        main.post { onNewCode?.run() }
    }

    class Listener : NotificationListenerService() {
        override fun onNotificationPosted(sbn: StatusBarNotification?) {
            val n = sbn?.notification ?: return
            if (sbn.packageName == packageName) return
            val e = n.extras ?: return
            val text = listOfNotNull(
                e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
                e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ).joinToString(" ")
            if (text.isBlank()) return
            find(text)?.let { offer(it) }
        }

        companion object {
            fun component(ctx: Context) = ComponentName(ctx, Listener::class.java)
        }
    }
}
