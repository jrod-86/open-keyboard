// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import helium314.keyboard.latin.utils.prefs

/**
 * Everything extra in the keyboard is a plugin that can be switched on or off. Built-in plugins are
 * listed here. Other apps on the phone become plugins too: any app that offers a "process text"
 * action (Android's standard way for apps to work on text, like translators and dictionaries)
 * shows up in the keyboard menu and works on the selected text, or on everything typed if nothing
 * is selected.
 */
object Plugins {
    class Plugin(val id: String, val name: String, val description: String)

    val builtIn = listOf(
        Plugin("slash", "/ commands", "Type /calc, /convert, /date, /roll and more, or = 12*7, and tap the answer"),
        Plugin("voice_commands", "Voice commands", "Say \"delete that\", \"send message\", \"comma\" or \"new line\" while voice typing. Pick which ones below"),
        Plugin("shortcuts", "Text shortcuts", "Type a shortcut like @@ and press space to get the full text"),
        Plugin("translate", "Translate", "Translate what you type, all on the phone"),
        Plugin("writing", "Writing help", "Fix, shorten or change the tone of your text with on-phone AI"),
        Plugin("fingers", "Learns your fingers", "Notices where you really tap each letter and moves the key's target to match"),
        Plugin("compose", "Smart compose", "Suggests the next few words when you pause (uses the writing help model)"),
        Plugin("scan", "Scan text", "Take a photo of text and type it in"),
        Plugin("app_commands", "Commands from your apps", "Apps made for Open Keyboard can add their own / commands"),
        Plugin("mcp", "MCP servers", "Connect AI tools and services you choose; their tools become / commands"),
        Plugin("app_actions", "Actions from your apps", "Apps on your phone that work on text appear in the keyboard menu"),
    )

    /**
     * Open Keyboard (test 218, Jarrod): the bigger apps in the app drawer are plugins too. Switched off, an app's tile
     * leaves the app drawer and the top bar, and none of its code or models load.
     */
    val apps = listOf(
        Plugin("app_chat", "💬 Chat", "Chat for fun with an AI that lives on your phone"),
        Plugin("app_tts", "🔊 TTS", "Read text out loud and make your own voices"),
        Plugin("app_image", "🖼️ Local Image", "Make pictures on your phone"),
        Plugin("app_sticker_maker", "✂️ Sticker maker", "Turn a photo into a sticker"),
        Plugin("app_fancy_text", "𝓕 Fancy text", "Type in 50 fancy letter styles"),
    )

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.prefs() }

    private fun key(id: String) = "open_keyboard_plugin_$id"

    /**
     * Whether a plugin is on. The keyboard's own plugins are on until switched off.
     * Open Keyboard (test 223, Jarrod: "keyboard first"): plugins from other apps ("cmd:" commands, "app:" text actions)
     * start OFF when they're new, so nothing new can surprise you or slow the keyboard. Ones you already had before
     * this version keep working (they were on).
     */
    fun isOn(id: String): Boolean {
        val p = prefs ?: return !isOutside(id)
        if (!isOutside(id)) return p.getBoolean(key(id), true)
        if (p.contains(key(id))) return p.getBoolean(key(id), false)
        return !p.getBoolean(seededKey(id), false) // not looked at yet since updating: as before (on)
    }

    private fun isOutside(id: String) = id.startsWith("cmd:") || id.startsWith("app:")
    private fun seededKey(id: String) = "open_keyboard_plugins_seeded_" + id.substringBefore(':')

    /** A new outside plugin that hasn't been switched on or off yet (the Plugins screen marks it "New"). */
    fun isNew(ctx: Context, id: String): Boolean { init(ctx); return isOutside(id) && prefs?.contains(key(id)) == false }

    /**
     * Called with every outside plugin found. The first time after updating, the ones already there are kept on;
     * from then on, anything new starts off.
     */
    fun seedOutside(ctx: Context, plugins: List<Pair<String, String>>) {
        init(ctx)
        val p = prefs ?: return
        val kind = plugins.firstOrNull()?.first ?: return
        if (p.getBoolean(seededKey(kind), false)) return
        // test 227: only apps that were there before this keyboard version stay on; one installed since starts off
        val pm = ctx.packageManager
        val updated = runCatching { pm.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(Long.MAX_VALUE)
        p.edit().apply {
            plugins.forEach { (id, pkg) ->
                val installed = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(Long.MAX_VALUE)
                if (!p.contains(key(id)) && installed < updated) putBoolean(key(id), true)
            }
            putBoolean(seededKey(kind), true)
        }.apply()
    }
    fun isOn(ctx: Context, id: String): Boolean { init(ctx); return isOn(id) }
    fun set(ctx: Context, id: String, on: Boolean) { init(ctx); prefs?.edit()?.putBoolean(key(id), on)?.apply() }

    // ---------- other apps as plugins ----------

    class AppAction(val component: ComponentName, val label: String, val icon: Drawable?) {
        val id get() = "app:" + component.flattenToShortString()
    }

    private fun processTextIntent() = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

    /** "Translate (Google Translate)", so an app's button can't be mistaken for the keyboard's own. */
    private fun labelWithApp(action: String, app: String) =
        if (app.isBlank() || action.contains(app, ignoreCase = true)) action else "$action ($app)"

    /** Apps that offer a text action, excluding this app. */
    fun appActions(ctx: Context, fresh: Boolean = false): List<AppAction> {
        // Open Keyboard (test 217): the top bar and the app drawer ask for this every time they're built, and looking
        // through all the phone's apps (with their names and icons) is slow. Kept for 10 minutes, or until the Plugins
        // screen asks again.
        val now = android.os.SystemClock.uptimeMillis()
        val cached = appActionsCache
        if (!fresh && cached != null && now - cached.first < 10 * 60_000) return cached.second
        return findAppActions(ctx).also { appActionsCache = now to it }
    }
    @Volatile private var appActionsCache: Pair<Long, List<AppAction>>? = null

    private fun findAppActions(ctx: Context): List<AppAction> {
        val pm = ctx.packageManager
        return runCatching { pm.queryIntentActivities(processTextIntent(), 0) }.getOrDefault(emptyList())
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.exported }
            .map { AppAction(ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                labelWithApp(it.loadLabel(pm).toString(), it.activityInfo.applicationInfo.loadLabel(pm).toString()),
                runCatching { it.loadIcon(pm) }.getOrNull()) }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
            .also { list -> if (list.isNotEmpty()) seedOutside(ctx, list.map { it.id to it.component.packageName }) }
    }

    /** App actions that are switched on, for the menu. */
    fun enabledAppActions(ctx: Context) =
        if (!isOn(ctx, "app_actions")) emptyList() else appActions(ctx).filter { isOn(it.id) }

    /** The answer from an app, waiting to be typed when the keyboard comes back. */
    @Volatile private var pending: String? = null

    /** Runs an app's text action on the selection, or on all the text if nothing is selected. */
    fun runAppAction(ctx: Context, ic: InputConnection?, action: AppAction) {
        ic ?: return
        var text = ic.getSelectedText(0)?.toString()
        if (text.isNullOrEmpty()) {
            val ex = ic.getExtractedText(ExtractedTextRequest(), 0)
            text = ex?.text?.toString()
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(ctx, "Type or select some text first", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // select everything, so the app's answer replaces it
            ic.setSelection(0, text.length)
        }
        pending = null
        ctx.startActivity(Intent(ctx, PluginActionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PluginActionActivity.EXTRA_COMPONENT, action.component)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text))
    }

    /** Called when the keyboard opens again: types the app's answer over the selection. */
    fun deliverPending(ic: InputConnection?): Boolean {
        val text = pending ?: return false
        ic ?: return false
        pending = null
        ic.commitText(text, 1)
        return true
    }

    internal fun setPending(text: String) { pending = text }
}

/** Opens another app's text action and catches its answer, which a keyboard can't do itself. */
class PluginActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val component = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_COMPONENT, ComponentName::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_COMPONENT)
        val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        if (component == null || text == null) { finish(); return }
        val target = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        runCatching { startActivityForResult(target, 1) }.onFailure {
            android.widget.Toast.makeText(this, "That app couldn't be opened", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val answer = data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (resultCode == RESULT_OK && !answer.isNullOrEmpty()) Plugins.setPending(answer)
        finish()
    }

    companion object { const val EXTRA_COMPONENT = "component" }
}
