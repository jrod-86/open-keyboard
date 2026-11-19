// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open Keyboard (test 225, Jarrod's plugin store): free, open source plugin apps you can add to the keyboard. The list
 * lives on GitHub (one JSON file anyone can suggest additions to), so it can grow without a keyboard update. Getting
 * one opens F-Droid or its download page; Android's own installer does the rest, and a new plugin starts switched off.
 */
object PluginStore {
    const val LIST_URL = "https://raw.githubusercontent.com/jrod-86/open-keyboard/apk/plugin-store.json"

    class Item(
        val packageName: String, val name: String, val emoji: String, val description: String, val author: String,
        val source: String?, val download: String?, val fdroid: String?, val offline: Boolean, val size: String?,
        val usesModels: String?,
    )

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "plugin-store.json")

    /** The store list: fresh from GitHub, or the last one saved if the phone is offline. Call off the main thread. */
    fun load(ctx: Context): Pair<List<Item>, String?> {
        val fresh = runCatching {
            val c = URL(LIST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }.getOrNull()
        if (fresh != null && runCatching { parse(fresh) }.isSuccess) {
            runCatching { cacheFile(ctx).writeText(fresh) }
            return parse(fresh) to null
        }
        val saved = runCatching { cacheFile(ctx).readText() }.getOrNull()
        return if (saved != null) (runCatching { parse(saved) }.getOrDefault(emptyList()) to "Showing the list from last time (no internet).")
        else emptyList<Item>() to "Couldn't get the store list. Check the internet and try again."
    }

    private fun parse(json: String): List<Item> {
        val arr = JSONObject(json).getJSONArray("plugins")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun opt(k: String) = o.optString(k, "").ifBlank { null }
            Item(o.getString("package"), o.getString("name"), o.optString("emoji", "🧩"), o.optString("description", ""),
                o.optString("author", ""), opt("source"), opt("download"), opt("fdroid"), o.optBoolean("offline", false),
                opt("size"), opt("uses_models"))
        }
    }

    fun isInstalled(ctx: Context, item: Item) = runCatching { ctx.packageManager.getPackageInfo(item.packageName, 0) }.isSuccess

    /** F-Droid if it's there (and F-Droid is installed), otherwise the download page. */
    fun get(ctx: Context, item: Item) {
        val fdroid = item.fdroid?.let { Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")).setPackage("org.fdroid.fdroid") }
        val intent = fdroid?.takeIf { it.resolveActivity(ctx.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(item.download ?: item.source ?: return))
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openSource(ctx: Context, item: Item) {
        val url = item.source ?: return
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun uninstall(ctx: Context, item: Item) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
