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

    /** test 316: [measureOnly] = just the first listen (loudness, tempo, feel) for every song, no AI listener (Settings > Measure all). */
    fun start(ctx: Context, measureOnly: Boolean = false) {
        running.value = true; status.value = "Starting…"
        ContextCompat.startForegroundService(ctx, Intent(ctx, MoodScanService::class.java).putExtra("measure_only", measureOnly))
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
    private var measureOnly = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { job?.cancel(); stop(); return START_NOT_STICKY }
        measureOnly = intent?.getBooleanExtra("measure_only", false) == true
        note("Getting ready…", 0, 0)
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    // test 310 (Jarrod: a third done in 3 to 4 hours): keep the phone's processor awake while it works, or it mostly
    // sleeps with the screen off. Renewed as it goes, so it can never stay on by mistake.
    private var wake: android.os.PowerManager.WakeLock? = null
    private fun awake() {
        val w = wake ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenWaves:moods").also { it.setReferenceCounted(false); wake = it }
        w.acquire(10 * 60_000L)
    }
    private fun sleep() { runCatching { wake?.takeIf { it.isHeld }?.release() } }

    private suspend fun run() {
        try { work() } finally { Moods.flush(this); Clap.flush(this); sleep() }
    }

    private suspend fun work() {
        val ctx = this
        MoodScan.running.value = true
        awake()
        val lib = Library.load(ctx)
        PlayStats.load(ctx)
        val songs = if (measureOnly) lib.songs else lib.uniqueSongs
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
            val ai = Clap.ready(ctx) && !measureOnly
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
            // test 310: two songs at a time (one is being read while the other is listened to), so the phone's cores share the work
            val queue = java.util.concurrent.ConcurrentLinkedQueue(todo)
            val workers = List(2) {
                scope.launch(Dispatchers.Default) {
                    while (isActive) {
                        val s = queue.poll() ?: break
                        while (isActive && MoodScan.chargingOnly(ctx) && !MoodScan.charging(ctx)) {
                            MoodScan.status.value = "Waiting for the phone to charge…"
                            kotlinx.coroutines.withContext(Dispatchers.Main) { note(MoodScan.status.value, MoodScan.done.intValue, MoodScan.total.intValue, force = true) }
                            delay(15_000)
                        }
                        MoodScan.status.value = ""
                        awake()
                        if (s.id !in Moods.feels(ctx)) { runCatching { Moods.measure(ctx, s) }; kotlinx.coroutines.withContext(Dispatchers.Main) { MoodScan.measured.intValue++ } }
                        if (ai && s.id !in Clap.scores(ctx)) { runCatching { Clap.listen(ctx, s) }; kotlinx.coroutines.withContext(Dispatchers.Main) { MoodScan.heard.intValue++ } }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            MoodScan.done.intValue++
                            if (MoodScan.done.intValue % 10 == 0) MoodScan.version.intValue++
                            note(if (ai) "Listening to your songs with the AI listener" else "Listening to your songs for Moods", MoodScan.done.intValue, MoodScan.total.intValue)
                        }
                    }
                }
            }
            workers.forEach { it.join() }
            Moods.flush(ctx); Clap.flush(ctx)
        }
        Clap.close() // give the model's memory back
        MoodScan.status.value = "Getting artist genres…"
        artists.join()
        MoodScan.status.value = ""
        MoodScan.version.intValue++
        stop()
    }

    private fun stop() {
        inForeground = false
        MoodScan.running.value = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // test 320: Android 15+ enforces a 6h/24h limit on dataSync foreground services — pause cleanly instead of crashing
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        Clap.flush(this)
        MoodScan.status.value = "Paused — Android's daily background limit reached. Tap Resume later."
        stop()
    }

    private var inForeground = false

    private fun note(text: String, done: Int, total: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && inForeground && total > 0 && now - lastNote < 1500) return
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
        if (inForeground) {
            runCatching { nm.notify(ID, n) }
        } else {
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(ID, n)
            }.isSuccess
            if (ok) inForeground = true
            else {
                job?.cancel()
                Clap.flush(this)
                MoodScan.status.value = "Paused — Android's daily background limit reached. Tap Resume later."
                stop()
            }
        }
    }

    override fun onDestroy() {
        Clap.close()
        MoodScan.running.value = false
        scope.cancel()
        super.onDestroy()
    }
}
