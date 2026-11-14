// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The picture being drawn, kept outside the Image maker screen so it carries on when you switch apps,
 * and the screen shows where it's up to when you come back. A notification shows progress and opens the screen.
 */
object ImageJob {
    @Volatile var running = false; private set
    @Volatile var stage = ""; private set
    @Volatile var step = 0; private set
    @Volatile var total = 0; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var result: Bitmap? = null; private set
    @Volatile var error: String? = null; private set
    @Volatile var tookMs = 0L; private set
    @Volatile var model: ImageGen.Model = ImageGen.QUICK; private set

    /** Called on the main thread whenever something changes. */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private const val CHANNEL = "image_maker"
    private const val NOTE_ID = 7301
    private var lastNote = 0L

    private fun changed(ctx: Context) {
        main.post { listener?.invoke() }
        notifyProgress(ctx)
    }

    fun start(ctx: Context, m: ImageGen.Model, prompt: String, steps: Int) {
        if (running) return
        val app = ctx.applicationContext
        running = true; result = null; error = null; step = 0; total = 0; model = m
        stage = if (m === ImageGen.QUICK) "Drawing…" else "Opening the model…"
        startedAt = System.currentTimeMillis()
        ImageGen.progressListener = { s, t -> if (t > 1) { step = s; total = t; stage = "Drawing"; changed(app) } }
        ImageGen.stageListener = { st, done, all ->
            stage = when (st) {
                "conditioning" -> "Reading your words…"
                "preparing" -> "Getting ready to draw…"
                "sampling" -> "Drawing"
                "decoding" -> "Finishing the picture…"
                else -> stage
            }
            if (st == "sampling" && all > 1) { step = done.toInt(); total = all.toInt() }
            Log.i("ImageMaker", "stage $st $done/$all after ${(System.currentTimeMillis() - startedAt) / 1000}s")
            changed(app)
        }
        changed(app)
        thread(name = "image-maker") {
            val r = runCatching { ImageGen.generate(app, m, prompt, Random.nextLong(0, Int.MAX_VALUE.toLong()), steps) }
            tookMs = System.currentTimeMillis() - startedAt
            Log.i("ImageMaker", "finished in ${tookMs / 1000}s: ${r.exceptionOrNull()?.toString() ?: "ok"}")
            ImageGen.progressListener = null; ImageGen.stageListener = null
            result = r.getOrNull()
            error = r.exceptionOrNull()?.let { if (it is OutOfMemoryError) "Your phone ran out of memory for this one. Try Quick." else "Stopped: ${it.message}" }
            running = false
            changed(app)
        }
    }

    fun stop() { if (running) { stage = "Stopping…"; ImageGen.cancel() } }

    /** Forget a finished picture once it's been shown or used. */
    fun clear() { if (!running) { result = null; error = null } }

    private fun notifyProgress(ctx: Context) {
        val now = System.currentTimeMillis()
        if (running && now - lastNote < 1500 && step != total) return
        lastNote = now
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Image maker", NotificationManager.IMPORTANCE_LOW))
        // only worth a notification for the slow model
        if (model === ImageGen.QUICK) { nm.cancel(NOTE_ID); return }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, ImageMakerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx))
            .setSmallIcon(helium314.keyboard.latin.R.drawable.ic_menu_image_maker)
            .setContentIntent(open)
        if (running) {
            val text = if (total > 0) "Step $step of $total" else stage
            b.setContentTitle("Drawing your picture…").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(maxOf(total, 1), step, total == 0)
        } else {
            b.setContentTitle(if (result != null) "Your picture is ready ✨" else "Image maker").setAutoCancel(true)
                .setContentText(if (result != null) "Tap to see it" else (error ?: "Stopped"))
            result?.let { b.setLargeIcon(it) }
        }
        nm.notify(NOTE_ID, b.build())
    }
}
