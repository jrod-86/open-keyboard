// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.content.Context
import android.os.Build
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.dictionary.ExpandableDictionaryUtils
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        appContext = applicationContext
        // the Image maker runs in its own process so a graphics driver crash can't take the keyboard down;
        // it needs none of the keyboard set-up, and running it twice would race on the keyboard's settings files
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        if (process.endsWith(":imagemaker")) return
        val ctx: Context = this
        DebugFlags.init(this) // needed by several other inits, so do it first
        // Open Keyboard: the TTS screens (":tts") only need crash reports. Running the rest of the keyboard
        // set-up there too meant two processes writing the same settings file, so a setting changed
        // in the keyboard could be put back to its old value after using TTS.
        // Chat (":chat", test 201) is the same: its model load stalled the keyboard for seconds when they shared a process
        // Sticker maker and Scan text (":tools", test 217) run picture AI models (hundreds of MB): kept out of the keyboard's
        // process so the keyboard's memory stays small and Android doesn't close and reopen it (a slow, laggy reopen)
        if (process.endsWith(":tts") || process.endsWith(":chat") || process.endsWith(":tools")) return
        NativeCrashes.collect(this)

        val scope = CoroutineScope(Dispatchers.Default)
        // other inits can be in parallel, except what depends on SubtypeSettings
        val jobs = listOf(
            scope.launch {
                SubtypeSettings.init(ctx)
                RichInputMethodManager.init(ctx)
                checkVersionUpgrade(ctx)
            },
            scope.launch { FoldableUtils.init(ctx) },
            scope.launch { Settings.init(ctx) },
            // upgrade toolbar on every debug apk start because we may work on adding a new toolbar key
            scope.launch { if (BuildConfig.DEBUG) upgradeToolbarPrefs(prefs()) },
            scope.launch { transferOldPinnedClips(ctx) }, // todo: remove in a few months, maybe end 2026
            scope.launch { Defaults.initDynamicDefaults(ctx) }
        )
        runBlocking { jobs.joinAll() } // word should be finished before anything else is started

        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            ExpandableDictionaryUtils.initUpToDateDicts(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        // stays set, for code that can run before Settings.init finishes
        @JvmStatic var appContext: Context? = null
            private set
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
