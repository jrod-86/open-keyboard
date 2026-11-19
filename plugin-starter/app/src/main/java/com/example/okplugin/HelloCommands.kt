package com.example.okplugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Adds /hello to Open Keyboard. Typing "/hello Sam" offers "Hello Sam! 👋" in the suggestion bar. */
class HelloCommands : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        // the commands this app offers, and an example of each
        "commands" -> Bundle().apply {
            putStringArray("names", arrayOf("hello"))
            putStringArray("usages", arrayOf("/hello Sam"))
        }
        // arg is the command name, "args" is what was typed after it. Answer within 3 seconds.
        "run" -> {
            val name = extras?.getString("args").orEmpty().trim()
            if (name.isEmpty()) Bundle() // no "text": the keyboard shows the example instead
            else Bundle().apply { putString("text", "Hello $name! 👋") }
        }
        else -> null
    }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
