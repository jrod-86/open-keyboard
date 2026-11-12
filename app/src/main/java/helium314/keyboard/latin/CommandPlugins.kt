// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Lets other apps add "/" commands. A plugin app declares a content provider with this intent
 * filter (see PLUGINS.md in the repository):
 *
 *     <provider android:name=".MyCommands" android:authorities="com.example.mycommands"
 *         android:exported="true">
 *         <intent-filter><action android:name="io.github.jrod86.openkeyboard.COMMANDS" /></intent-filter>
 *     </provider>
 *
 * The keyboard calls the provider's call() method:
 *  - method "commands": return a Bundle with string arrays "names" and "usages".
 *  - method "run", arg = command name, extras "args" = what was typed after the command:
 *    return a Bundle with the string "text" to insert, or no "text" if there's no answer.
 *
 * A plugin only ever sees what is typed after its own command, never anything else.
 */
object CommandPlugins {
    const val ACTION = "io.github.jrod86.openkeyboard.COMMANDS"

    class Provider(val authority: String, val packageName: String, val label: String,
                   val names: List<String>, val usages: List<String>) {
        val id get() = "cmd:$authority"
    }

    @Volatile var providers: List<Provider> = emptyList(); private set
    private var lastRefresh = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Looks for plugin apps again, at most once a minute unless forced. */
    fun refresh(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000) return
        lastRefresh = now
        val app = ctx.applicationContext
        thread(name = "command-plugins") {
            val pm = app.packageManager
            val found = runCatching { pm.queryIntentContentProviders(Intent(ACTION), 0) }.getOrDefault(emptyList())
                .mapNotNull { info ->
                    val p = info.providerInfo ?: return@mapNotNull null
                    if (!p.exported || p.packageName == app.packageName) return@mapNotNull null
                    val authority = p.authority?.split(';')?.firstOrNull() ?: return@mapNotNull null
                    val b = callWithTimeout(app, authority, "commands", null, null) ?: return@mapNotNull null
                    val names = b.getStringArray("names")?.map { it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' } }
                        ?: return@mapNotNull null
                    val usages = b.getStringArray("usages")?.toList() ?: names.map { "/$it" }
                    Provider(authority, p.packageName, info.loadLabel(pm).toString(), names, usages)
                }
            providers = found
            main.post {
                SlashCommands.setExternal("apps", found.filter { Plugins.isOn(app, "app_commands") && Plugins.isOn(app, it.id) }
                    .flatMap { prov -> prov.names.mapIndexedNotNull { i, name ->
                        if (name.isEmpty()) null
                        else SlashCommands.Command(name, prov.usages.getOrNull(i) ?: "/$name", remote = prov.authority) { null }
                    } })
                done?.invoke()
            }
        }
    }

    private fun callWithTimeout(ctx: Context, authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        var result: Bundle? = null
        val t = thread(name = "command-plugin-call") {
            result = runCatching { ctx.contentResolver.call(Uri.parse("content://$authority"), method, arg, extras) }.getOrNull()
        }
        t.join(3000)
        return result
    }

    // ---------- running commands ----------

    private val answers = ConcurrentHashMap<String, String>() // "" = no answer
    private val asking = ConcurrentHashMap.newKeySet<String>()

    /**
     * The answer for a command, or [SlashCommands.WAITING] while the plugin works on it. [onAnswer]
     * is called on the main thread when the answer arrives.
     */
    fun answer(ctx: Context, authority: String, name: String, args: String, onAnswer: () -> Unit): String? {
        val key = "$authority/$name/$args"
        answers[key]?.let { return it.ifEmpty { null } }
        if (asking.add(key)) {
            val app = ctx.applicationContext
            thread(name = "command-plugin-run") {
                val b = callWithTimeout(app, authority, "run", name, Bundle().apply { putString("args", args) })
                if (answers.size > 50) answers.clear()
                answers[key] = b?.getString("text")?.take(5000) ?: ""
                asking.remove(key)
                main.post(onAnswer)
            }
        }
        return SlashCommands.WAITING
    }
}
