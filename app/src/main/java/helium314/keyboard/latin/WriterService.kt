// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Open Keyboard (test 221, Jarrod: "the product is the keyboard"): the writing model (1.1 to 2.5 GB) lives in Chat's
 * process (":chat"), never in the keyboard's. Smart compose, Writing help and the AI writer ask this service for their
 * text, so loading or running the model can't stall typing, and Chat and the keyboard share one copy of the model.
 */
class WriterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Int, Job>()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo
            val id = msg.arg1
            when (msg.what) {
                ASK -> {
                    val d = Bundle(msg.data)
                    jobs[id] = scope.launch {
                        val b = Bundle()
                        var lastSent = 0L
                        try {
                            val system = d.getString("system").orEmpty()
                            val prompt = d.getString("prompt").orEmpty()
                            val max = d.getInt("max", 256)
                            val out = if (d.getBoolean("ifIdle")) WritingTools.askIfIdle(this@WriterService, system, prompt, max)
                            else WritingTools.generateFor(this@WriterService, system, prompt, max, quickOnly = d.getBoolean("quickOnly")) { sofar ->
                                val now = System.currentTimeMillis()
                                if (d.getBoolean("pieces") && now - lastSent > 100) {
                                    lastSent = now
                                    send(reply, PIECE, id, Bundle().apply { putString("text", sofar) })
                                }
                            }
                            if (out == null) b.putBoolean("busy", true) else b.putString("text", out)
                        } catch (e: CancellationException) {
                            b.putBoolean("cancelled", true)
                        } catch (e: Throwable) {
                            Log.w(TAG, "writing failed", e)
                            b.putString("error", if (e is UnsatisfiedLinkError) "Writing help needs a 64-bit phone" else e.message ?: e.javaClass.simpleName)
                        }
                        jobs.remove(id)
                        send(reply, DONE, id, b)
                    }
                }
                CANCEL -> jobs.remove(id)?.cancel()
                FREE -> WritingTools.freeIfIdle(this@WriterService)
            }
        }
    }

    private fun send(to: Messenger?, what: Int, id: Int, data: Bundle) {
        runCatching { to?.send(Message.obtain(null, what, id, 0).apply { this.data = data }) }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) WritingTools.freeIfIdle(this)
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WriterService"
        const val ASK = 1
        const val CANCEL = 2
        const val FREE = 3
        const val PIECE = 10
        const val DONE = 11
    }
}

/** The keyboard's side of [WriterService]: binds when needed and lets go again after 3 idle minutes. */
object WriterClient {
    private const val TAG = "WriterClient"
    private val main = Handler(Looper.getMainLooper())
    private class Call(val onPiece: (String) -> Unit, val done: CompletableDeferred<Bundle> = CompletableDeferred())
    private val calls = ConcurrentHashMap<Int, Call>()
    private val nextId = AtomicInteger()
    @Volatile private var service: Messenger? = null
    @Volatile private var connecting: CompletableDeferred<Messenger?>? = null
    private var bound: Context? = null

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                WriterService.PIECE -> msg.data.getString("text")?.let { calls[msg.arg1]?.onPiece?.invoke(it) }
                WriterService.DONE -> calls.remove(msg.arg1)?.done?.complete(msg.data)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            connecting?.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { lost("the writing helper stopped") }
        override fun onBindingDied(name: ComponentName?) { lost("the writing helper stopped"); unbind() }
        override fun onNullBinding(name: ComponentName?) { connecting?.complete(null); unbind() }
    }

    private fun lost(why: String) {
        service = null
        WritingTools.writerGone()
        calls.values.forEach { it.done.complete(Bundle().apply { putString("error", why) }) }
        calls.clear()
    }

    private suspend fun connect(ctx: Context): Messenger? {
        service?.let { return it }
        val wait = synchronized(this) {
            connecting?.takeIf { !it.isCompleted } ?: CompletableDeferred<Messenger?>().also { d ->
                connecting = d
                val app = ctx.applicationContext
                main.post {
                    val ok = runCatching { app.bindService(Intent(app, WriterService::class.java), connection, Context.BIND_AUTO_CREATE) }
                        .getOrDefault(false)
                    if (ok) bound = app else d.complete(null)
                }
            }
        }
        return withTimeoutOrNull(15_000) { wait.await() }
    }

    private val unbindLater = Runnable { if (calls.isEmpty()) unbind() }

    private fun unbind() {
        main.removeCallbacks(unbindLater)
        val ctx = bound ?: return
        bound = null
        service = null
        runCatching { ctx.unbindService(connection) }
    }

    /**
     * Asks the writing model in Chat's process. Returns null when there's no model (or, with [ifIdle], when it's busy
     * right now). Throws if it fails. [onPiece] (on the main thread) gets the text so far while it's written.
     */
    suspend fun ask(ctx: Context, system: String, prompt: String, maxTokens: Int, ifIdle: Boolean = false, quickOnly: Boolean = false, onPiece: ((String) -> Unit)? = null): String? {
        main.removeCallbacks(unbindLater)
        val to = connect(ctx) ?: throw IllegalStateException("the writing helper didn't start")
        val id = nextId.incrementAndGet()
        val call = Call(onPiece ?: {})
        calls[id] = call
        try {
            to.send(Message.obtain(null, WriterService.ASK, id, 0).apply {
                data = Bundle().apply {
                    putString("system", system); putString("prompt", prompt); putInt("max", maxTokens)
                    putBoolean("ifIdle", ifIdle); putBoolean("quickOnly", quickOnly); putBoolean("pieces", onPiece != null)
                }
                replyTo = replies
            })
            val r = call.done.await()
            r.getString("error")?.let { throw IllegalStateException(it) }
            if (r.getBoolean("busy") || r.getBoolean("cancelled")) return null
            WritingTools.markUsed()
            return r.getString("text")
        } catch (e: CancellationException) {
            runCatching { to.send(Message.obtain(null, WriterService.CANCEL, id, 0)) }
            throw e
        } finally {
            calls.remove(id)
            main.removeCallbacks(unbindLater)
            main.postDelayed(unbindLater, 3 * 60_000L)
        }
    }

    /** Phone short of memory: ask Chat's process to give the model back if nothing is using it. */
    fun free() {
        val to = service ?: return
        runCatching { to.send(Message.obtain(null, WriterService.FREE)) }
    }
}
