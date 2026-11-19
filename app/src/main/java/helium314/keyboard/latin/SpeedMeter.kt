// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer

/**
 * Open Keyboard (test 219, Jarrod's "keyboard first" idea 1): keeps score of how fast the keyboard is, so the speed
 * meter in Settings, Plugins can show it. Everything here is a few numbers in memory: it never slows typing down.
 * - How long the keyboard takes to show (from the app asking for it to the first frame drawn).
 * - Freezes LagWatch noticed (the keyboard's main thread busy for more than 0.25 s).
 * - Times the phone was short of memory and what was given back.
 */
object SpeedMeter {
    class Freeze(val atWallMs: Long, val ms: Long, val what: String)

    private val opens = ArrayDeque<Long>() // ms per opening, newest last
    private val freezes = ArrayDeque<Freeze>()
    private val lowMemory = ArrayDeque<Pair<Long, String>>()
    private var openStartedAt = 0L

    /** The keyboard has been asked to show: time until the next frame is drawn. */
    fun openStarted() {
        openStartedAt = SystemClock.uptimeMillis()
        runCatching {
            Choreographer.getInstance().postFrameCallback {
                val started = openStartedAt
                if (started == 0L) return@postFrameCallback
                openStartedAt = 0L
                add(opens, SystemClock.uptimeMillis() - started, 50)
            }
        }
    }

    fun freeze(ms: Long, what: String) = add(freezes, Freeze(System.currentTimeMillis(), ms, what), 50)

    fun lowMemory(note: String) = add(lowMemory, System.currentTimeMillis() to note, 20)

    private fun <T> add(list: ArrayDeque<T>, value: T, max: Int) = synchronized(list) {
        list.addLast(value)
        while (list.size > max) list.removeFirst()
    }

    fun openTimes(): List<Long> = synchronized(opens) { opens.toList() }
    fun freezes(): List<Freeze> = synchronized(freezes) { freezes.toList() }
    fun lowMemoryEvents(): List<Pair<Long, String>> = synchronized(lowMemory) { lowMemory.toList() }

    /** Memory used by each part of the app that is running now, in MB (keyboard, Chat, TTS…). Slow-ish: call off the main thread. */
    fun memoryByPart(ctx: Context): List<Pair<String, Int>> {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val procs = runCatching { am.runningAppProcesses }.getOrNull().orEmpty().filter { it.processName.startsWith(ctx.packageName) }
        if (procs.isEmpty()) return emptyList()
        val info = runCatching { am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray()) }.getOrNull() ?: return emptyList()
        return procs.mapIndexed { i, p ->
            val name = when (p.processName.substringAfter(':', "")) {
                "" -> "Keyboard"
                "chat" -> "Chat"
                "tts" -> "TTS"
                "tools" -> "Sticker maker / Scan text"
                "imagemaker" -> "Local Image"
                else -> p.processName.substringAfter(':')
            }
            name to (info.getOrNull(i)?.totalPss ?: 0) / 1024
        }.sortedByDescending { it.second }
    }

    /** What big things the keyboard has in memory right now. */
    fun loadedNow(): List<String> = buildList {
        if (helium314.keyboard.voice.VoiceEngine.loaded) add("Voice typing models")
        if (WritingTools.loaded) add("Writing model (Smart compose, Writing help)")
        if (SmartCompose.busy) add("Smart compose is guessing")
    }
}
