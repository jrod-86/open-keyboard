// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Open Waves test 297 (Jarrod): look up the popularity of the whole library for the Popular tab.
 * Songs are done in chunks of 25 and every chunk is saved straight away, so Pause (or losing internet, or the phone
 * stopping the app) never loses progress: the next start skips everything already looked up. Songs you play most go first.
 */
object PopScan {
    const val CHUNK = 25
    val running = mutableStateOf(false)
    val done = mutableIntStateOf(0)
    val total = mutableIntStateOf(0)
    val status = mutableStateOf("")
    /** goes up after every saved chunk, so the Popular tab re-sorts */
    val version = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("pop_wifi_only", true)
    fun setWifiOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("pop_wifi_only", on).apply()

    fun start(ctx: Context) {
        running.value = true; status.value = "Starting…"
        ContextCompat.startForegroundService(ctx, Intent(ctx, PopScanService::class.java))
    }
    fun pause(ctx: Context) {
        running.value = false; status.value = ""
        ctx.startService(Intent(ctx, PopScanService::class.java).setAction(PopScanService.PAUSE))
    }

    /** How many of these songs still need looking up. */
    fun left(ctx: Context, songs: List<Song>) = songs.distinctBy { Popularity.key(it) }.count { Popularity.known(ctx, it) == null }

    internal fun netOk(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly(ctx) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

class PopScanService : Service() {
    companion object {
        const val PAUSE = "pause"
        private const val CHANNEL = "open_waves_popular"
        private const val ID = 4297
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            job?.cancel(); stop(); return START_NOT_STICKY
        }
        startForegroundNow("Getting ready…", 0, 0)
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val ctx = this
        PopScan.running.value = true
        val lib = Library.load(ctx)
        PlayStats.load(ctx)
        val all = lib.uniqueSongs.distinctBy { Popularity.key(it) }
            .sortedByDescending { PlayStats.count(it.id) } // the songs you play most get their numbers first
        val todo = all.filter { Popularity.known(ctx, it) == null }
        PopScan.total.intValue = all.size
        PopScan.done.intValue = all.size - todo.size
        var failures = 0
        for (chunk in todo.chunked(PopScan.CHUNK)) {
            while (scope.isActive && !PopScan.netOk(ctx)) {
                PopScan.status.value = if (PopScan.wifiOnly(ctx)) "Waiting for Wi-Fi…" else "Waiting for internet…"
                update()
                delay(10_000)
            }
            PopScan.status.value = ""
            val ok = runCatching { Popularity.lookUp(ctx, chunk) { _, _, _ -> } }.getOrDefault(false)
            if (!ok) {
                // the sites didn't answer: wait a bit and go on; nothing is lost because this chunk wasn't saved
                failures++
                PopScan.status.value = "Couldn't reach the music sites, trying again soon…"
                update()
                if (failures >= 5) break
                delay(30_000L * failures)
                continue
            }
            failures = 0
            PopScan.done.intValue = all.size - PopScan.left(ctx, all)
            PopScan.version.intValue++
            update()
        }
        val left = PopScan.left(ctx, all)
        PopScan.done.intValue = all.size - left
        PopScan.status.value = if (left == 0) "" else "Paused: couldn't reach the music sites. Tap Resume to try again."
        PopScan.version.intValue++
        stop()
    }

    private fun stop() {
        PopScan.running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update() = startForegroundNow(PopScan.status.value.ifBlank { "Looking up song popularity" }, PopScan.done.intValue, PopScan.total.intValue)

    private fun startForegroundNow(text: String, done: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Popular tab lookups", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, PopScanService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.open_waves_mark)
            .setContentTitle(if (total > 0) "Open Waves: $done of $total songs" else "Open Waves")
            .setContentText(text)
            .setProgress(maxOf(total, 1), done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    override fun onDestroy() {
        PopScan.running.value = false
        scope.cancel()
        super.onDestroy()
    }
}
