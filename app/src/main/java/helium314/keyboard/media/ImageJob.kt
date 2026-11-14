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

    /** The stages a picture goes through, in order. */
    val STAGES = listOf("Opening the model", "Reading your words", "Drawing", "Finishing the picture")
    /** Which of [STAGES] it's on now, or -1 before it starts. */
    @Volatile var stageIndex = -1; private set
    /** Stages skipped this time (the model was already open). */
    @Volatile var skipped = setOf<Int>(); private set
    @Volatile private var stageStartedAt = 0L
    @Volatile private var drawStartedAt = 0L
    private val took = LongArray(4)

    private fun prefs(c: Context) = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE)
    /** What a stage took last time on this phone, in ms (drawing = per step); 0 if never measured. */
    private fun learned(c: Context, i: Int) = prefs(c).getLong("t_${model.id}_$i", 0L)

    private fun enter(i: Int) {
        if (i <= stageIndex) return
        val now = System.currentTimeMillis()
        if (stageIndex >= 0) took[stageIndex] = now - stageStartedAt
        stageIndex = i; stageStartedAt = now
        stage = STAGES[i] + (if (i == 2) "" else "…")
        if (i == 2) drawStartedAt = now
    }

    /** Seconds left, as best we can tell, or -1 when there's nothing to go on yet. */
    fun secondsLeft(c: Context): Long {
        if (!running || stageIndex < 0) return -1
        val now = System.currentTimeMillis()
        val steps = if (total > 0) total else stepsAsked
        // time per drawing step: measured this run once a step is done, else last run
        val perStep = if (stageIndex == 2 && step > 0) (now - drawStartedAt) / step else learned(c, 2)
        fun guess(i: Int) = if (i == 2) perStep * steps else learned(c, i)
        var left = 0L
        for (i in stageIndex until STAGES.size) {
            if (i in skipped) continue
            val g = guess(i)
            if (g == 0L) return -1
            left += if (i == stageIndex) {
                if (i == 2) perStep * (steps - step) - (if (step > 0) 0 else now - stageStartedAt)
                else g - (now - stageStartedAt)
            } else g
        }
        return (left / 1000).coerceAtLeast(5)
    }

    private var stepsAsked = 0

    private fun remember(c: Context) {
        val e = prefs(c).edit()
        for (i in STAGES.indices) {
            if (i in skipped || took[i] <= 0) continue
            val v = if (i == 2) took[i] / maxOf(total, stepsAsked, 1) else took[i]
            val old = learned(c, i)
            e.putLong("t_${model.id}_$i", if (old == 0L) v else (old + v) / 2)
        }
        e.apply()
    }

    fun start(ctx: Context, m: ImageGen.Model, prompt: String, steps: Int) {
        if (running) return
        val app = ctx.applicationContext
        running = true; result = null; error = null; step = 0; total = 0; model = m
        stepsAsked = if (m === ImageGen.QUICK) 1 else steps
        took.fill(0); stageIndex = -1
        skipped = if (ImageGen.isOpen(m)) setOf(0) else emptySet()
        enter(if (0 in skipped) 1 else 0)
        startedAt = System.currentTimeMillis()
        ImageGen.progressListener = null // counts tensors loaded, not drawing steps
        stopping = false
        ImageGen.stageListener = { st, done, all ->
            when (st) {
                "conditioning", "preparing" -> enter(1)
                "sampling" -> { enter(2); if (all > 1) { step = done.toInt(); total = all.toInt() } }
                "decoding" -> enter(3)
            }
            Log.i("ImageMaker", "stage $st $done/$all after ${(System.currentTimeMillis() - startedAt) / 1000}s")
            changed(app)
        }
        changed(app)
        thread(name = "image-maker") {
            val r = runCatching {
                ImageGen.ensureLoaded(app, m)
                enter(1); changed(app)
                ImageGen.generate(app, m, prompt, Random.nextLong(0, Int.MAX_VALUE.toLong()), steps)
            }
            val now = System.currentTimeMillis()
            tookMs = now - startedAt
            if (stageIndex >= 0) took[stageIndex] = now - stageStartedAt
            if (r.isSuccess) remember(app)
            Log.i("ImageMaker", "finished in ${tookMs / 1000}s (stages ms: ${took.joinToString()}): ${r.exceptionOrNull()?.toString() ?: "ok"}")
            ImageGen.progressListener = null; ImageGen.stageListener = null
            result = r.getOrNull()
            error = r.exceptionOrNull()?.let {
                if (stopping) "Stopped." else if (it is OutOfMemoryError) "Your phone ran out of memory for this one. Try Quick." else "Stopped: ${it.message}"
            }
            running = false
            changed(app)
        }
    }

    @Volatile var stopping = false; private set

    fun stop(ctx: Context) { if (running && !stopping) { stopping = true; ImageGen.cancel(); changed(ctx.applicationContext) } }

    /** Forget a finished picture once it's been shown or used. */
    fun clear() { if (!running) { result = null; error = null } }

    fun fmtLeft(secs: Long) = if (secs >= 90) "${(secs + 30) / 60} min" else if (secs >= 60) "1 min" else "${secs} s"

    /** How far through the whole picture it is, 0..1000, by time where we know it, else by stage. */
    fun overall(c: Context): Int {
        if (!running) return 1000
        val left = secondsLeft(c)
        val gone = (System.currentTimeMillis() - startedAt) / 1000
        if (left > 0) return (gone * 1000 / (gone + left)).toInt().coerceIn(0, 990)
        val inStage = if (stageIndex == 2 && total > 0) step.toFloat() / total else 0f
        return (((stageIndex.coerceAtLeast(0) + inStage) / STAGES.size) * 1000).toInt()
    }

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
            val left = secondsLeft(ctx)
            val where = "Stage ${stageIndex + 1} of ${STAGES.size}: " + (if (stageIndex == 2 && total > 0) "drawing step $step of $total" else STAGES[stageIndex.coerceAtLeast(0)].lowercase())
            val text = where + if (left > 0) " · about ${fmtLeft(left)} left" else ""
            b.setContentTitle("Drawing your picture…").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(1000, overall(ctx), stageIndex < 0)
        } else {
            b.setContentTitle(if (result != null) "Your picture is ready ✨" else "Image maker").setAutoCancel(true)
                .setContentText(if (result != null) "Tap to see it" else (error ?: "Stopped"))
            result?.let { b.setLargeIcon(it) }
        }
        nm.notify(NOTE_ID, b.build())
    }
}
