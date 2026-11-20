package com.example.okplugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Adds /hello to Open Keyboard. Typing "/hello Sam" offers "Hello Sam! 👋" in the suggestion bar. */
class HelloCommands : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        // the commands this app offers, and an example of each
        "commands" -> Bundle().apply {
            putStringArray("names", arrayOf("hello", "models"))
            putStringArray("usages", arrayOf("/hello Sam", "/models"))
        }
        // arg is the command name, "args" is what was typed after it. Answer within 3 seconds.
        "run" -> if (arg == "models") Bundle().apply { putString("text", sharedModels()) } else {
            val name = extras?.getString("args").orEmpty().trim()
            if (name.isEmpty()) Bundle() // no "text": the keyboard shows the example instead
            else Bundle().apply { putString("text", "Hello $name! 👋") }
        }
        else -> null
    }

    /** Which AI models Open Keyboard already has, which this app could use without downloading them again. */
    private fun sharedModels(): String {
        val ctx = context ?: return "No context"
        val info = ctx.packageManager.queryIntentContentProviders(Intent("io.github.jrod86.openkeyboard.MODELS"), 0)
            .firstOrNull()?.providerInfo ?: return "Open Keyboard's model sharing isn't there"
        val b = ctx.contentResolver.call(Uri.parse("content://${info.authority}"), "models", null, null) ?: return "No answer"
        b.getString("error")?.let { return it }
        val names = b.getStringArray("names").orEmpty()
        val sizes = b.getLongArray("sizes") ?: LongArray(0)
        if (names.isEmpty()) return "No shared models yet"
        return "I can use: " + names.mapIndexed { i, n -> "$n (${(sizes.getOrElse(i) { 0 } / 1_000_000)} MB)" }.joinToString(", ")
    }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
