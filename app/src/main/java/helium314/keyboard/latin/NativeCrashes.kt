// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes inside native libraries (ONNX Runtime, Tesseract, llama.cpp...) kill the app without a
 * Java stack trace, so the normal crash report never gets written. On the next start, ask Android
 * why the app last stopped and save native crashes as crash reports, with the system's crash dump
 * (a "tombstone") where Android 12+ provides one. They are offered with the other crash reports.
 */
object NativeCrashes {
    private const val PREF_LAST = "open_keyboard_last_exit_checked"

    fun collect(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val prefs = ctx.prefs()
            val last = prefs.getLong(PREF_LAST, 0L)
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                .filter { it.timestamp > last && it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            prefs.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply()
            val dir = ctx.getExternalFilesDir(null) ?: return
            for (e in exits) {
                val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(e.timestamp))
                File(dir, "crash_report_native_$date.txt").writeText(
                    "Native crash\nprocess: ${e.processName}\ndescription: ${e.description}\n" +
                        "app version: ${BuildConfig.VERSION_NAME}\ndevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    e.traceInputStream?.use { input ->
                        File(dir, "crash_report_native_${date}_tombstone.pb").outputStream().use { input.copyTo(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
