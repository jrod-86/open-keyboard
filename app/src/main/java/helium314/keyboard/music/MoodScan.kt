// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Open Waves test 299 (Jarrod): gets the library ready for Moods by listening to every song once, on the phone.
 * Each song is saved as soon as it's done, so Pause (or the phone stopping the app) never loses progress.
 */
object MoodScan {
    val running = mutableStateOf(false)
    val done = mutableIntStateOf(0)
    val total = mutableIntStateOf(0)
    // test 307 (Jarrod: Resume looked like it started again): what's already kept, shown separately
    val measured = mutableIntStateOf(0)
    val heard = mutableIntStateOf(0)
    val aiOn = mutableStateOf(false)
    val status = mutableStateOf("")
    val version = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun chargingOnly(ctx: Context) = prefs(ctx).getBoolean("mood_charging", false)
    fun setChargingOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("mood_charging", on).apply()

    fun start(ctx: Context) {
        running.value = true; status.value = "Starting…"
        ContextCompat.startForegroundService(ctx, Intent(ctx, MoodScanService::class.java))
    }
    fun pause(ctx: Context) {
        running.value = false; status.value = ""
        ctx.startService(Intent(ctx, MoodScanService::class.java).setAction(MoodScanService.PAUSE))
    }

    internal fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }
}

class MoodScanService : Service() {
    companion object {
        const val PAUSE = "pause"
        private const val CHANNEL = "open_waves_moods"
        private const val ID = 4299
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastNote = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { job?.cancel(); stop(); return START_NOT_STICKY }
        note("Getting ready…", 0, 0)
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val ctx = this
        MoodScan.running.value = true
        val lib = Library.load(ctx)
        PlayStats.load(ctx)
        val songs = lib.uniqueSongs
        // test 305: each artist's genres from MusicBrainz, at the same time (one a second, saved after each)
        val artists = scope.launch {
            var fails = 0
            for (a in ArtistTags.todo(ctx, songs)) {
                if (!ArtistTags.lookUp(ctx, a)) { if (++fails >= 5) break; delay(20_000L * fails) } else fails = 0
            }
            MoodScan.version.intValue++
        }
        // more than one pass: if the AI listener is downloaded while this runs, the next pass listens to the songs already measured
        var pass = 0
        while (scope.isActive && pass++ < 3) {
            val ai = Clap.ready(ctx)
            val feels = Moods.feels(ctx)
            val heard = Clap.scores(ctx)
            // the songs you play most are ready first
            val todo = songs.filter { it.id !in feels || (ai && it.id !in heard) }.sortedByDescending { PlayStats.count(it.id) }
            if (todo.isEmpty()) break
            MoodScan.total.intValue = songs.size
            MoodScan.done.intValue = songs.size - todo.size
            MoodScan.aiOn.value = ai
            MoodScan.measured.intValue = songs.count { it.id in feels }
            MoodScan.heard.intValue = songs.count { it.id in heard }
            for ((n, s) in todo.withIndex()) {
                if (!scope.isActive) break
                while (scope.isActive && MoodScan.chargingOnly(ctx) && !MoodScan.charging(ctx)) {
                    MoodScan.status.value = "Waiting for the phone to charge…"
                    note(MoodScan.status.value, MoodScan.done.intValue, MoodScan.total.intValue, force = true)
                    delay(15_000)
                }
                MoodScan.status.value = ""
                if (s.id !in Moods.feels(ctx)) { runCatching { Moods.measure(ctx, s) }; MoodScan.measured.intValue++ }
                if (ai && s.id !in Clap.scores(ctx)) { runCatching { Clap.listen(ctx, s) }; MoodScan.heard.intValue++ }
                MoodScan.done.intValue++
                if (n % 10 == 9) MoodScan.version.intValue++
                note(if (ai) "Listening to your songs with the AI listener" else "Listening to your songs for Moods", MoodScan.done.intValue, MoodScan.total.intValue)
            }
        }
        Clap.close() // give the model's memory back
        MoodScan.status.value = "Getting artist genres…"
        artists.join()
        MoodScan.status.value = ""
        MoodScan.version.intValue++
        stop()
    }

    private fun stop() {
        MoodScan.running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun note(text: String, done: Int, total: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && total > 0 && now - lastNote < 1500) return
        lastNote = now
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Getting songs ready for Moods", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, MoodScanService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.open_waves_mark)
            .setContentTitle(if (total > 0) "Open Waves Moods: $done of $total songs" else "Open Waves Moods")
            .setContentText(text)
            .setProgress(maxOf(total, 1), done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    override fun onDestroy() {
        Clap.close()
        MoodScan.running.value = false
        scope.cancel()
        super.onDestroy()
    }
}
