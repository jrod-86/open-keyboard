// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.utils.Log

/**
 * Open Keyboard: notices when the keyboard freezes for a moment (its main thread is busy, so key
 * presses wait) and writes what it was doing into the app's log, so a Bug report with logs shows
 * the cause. Only runs while the keyboard is on screen, and costs one tiny check every half second.
 */
object LagWatch {
    private const val TAG = "LagWatch"
    private const val CHECK_EVERY_MS = 500L
    private const val SLOW_MS = 250L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null
    @Volatile private var answeredAt = 0L

    fun start() {
        if (thread != null) return
        val t = Thread({ watch() }, "lag-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        thread = t
        t.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** What else was going on: memory use and the big models, which can slow the whole phone down. */
    private fun state(): String {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_000_000
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / 1_000_000
        return "memory: java $javaMb MB, native $nativeMb MB; smart compose working: ${SmartCompose.busy}; " +
            "voice models loaded: ${helium314.keyboard.voice.VoiceEngine.loaded}; writing model loaded: ${WritingTools.loaded}"
    }

    private fun watch() {
        val me = Thread.currentThread()
        try {
            while (thread === me) {
                val askedAt = SystemClock.uptimeMillis()
                answeredAt = 0L
                main.post { answeredAt = SystemClock.uptimeMillis() }
                Thread.sleep(SLOW_MS)
                if (answeredAt == 0L) {
                    // stuck: note what the main thread is doing right now
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(25).joinToString("\n    at ")
                    while (answeredAt == 0L && thread === me) Thread.sleep(50)
                    val ms = (if (answeredAt == 0L) SystemClock.uptimeMillis() else answeredAt) - askedAt
                    Log.w(TAG, "keyboard froze for about $ms ms (${state()}), it was busy in:\n    at $stack")
                }
                Thread.sleep(CHECK_EVERY_MS)
            }
        } catch (_: InterruptedException) {
        }
    }
}
