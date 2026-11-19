// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * Open Keyboard (test 224, Jarrod's Pinokio idea): the shared Model Manager door for plugin apps. A plugin app that
 * you've switched on in Settings, Plugins can see which AI models are already downloaded and read them, so it never
 * has to download the same model again. Read only: a plugin can't change or delete anything. Your own things (voices
 * you made) are never shared.
 *
 * Plugin apps find this with the intent action [ACTION] (see PLUGINS.md):
 * - call("models") returns "ids", "names", "kinds" (what it's for), "sizes" and, per model, "files_<id>" (the files in it).
 * - openFile("content://<authority>/<id>/<file>", "r") gives read access to one file.
 */
class ModelProvider : ContentProvider() {
    override fun onCreate() = true

    /** The app asking, if it's a plugin app you've switched on (a / command or text action). */
    private fun callerLabel(): String? {
        val ctx = context ?: return null
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return "Open Keyboard"
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid).orEmpty()
        val allowed = pkgs.any { pkg ->
            (Plugins.isOn(ctx, "app_commands") && CommandPlugins.providers.any { it.packageName == pkg && Plugins.isOn(ctx, it.id) }) ||
                (Plugins.isOn(ctx, "app_actions") && Plugins.appActions(ctx).any { it.component.packageName == pkg && Plugins.isOn(ctx, it.id) })
        }
        if (!allowed) {
            Log.i(TAG, "model sharing refused for ${pkgs.joinToString()}: not a plugin that's switched on")
            return null
        }
        return pkgs.firstNotNullOfOrNull { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
            ?: pkgs.firstOrNull()
    }

    private fun shared() = ModelManager.list(context!!).filter { !it.userMade }

    private fun filesIn(e: ModelManager.Entry): List<String> =
        if (e.file.isFile) listOf(e.file.name)
        else e.file.walkTopDown().filter { it.isFile }.map { it.relativeTo(e.file).path }.toList()

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "models") return null
        // asking for the list is allowed for switched-on plugins only, like reading
        callerLabel() ?: return Bundle().apply { putString("error", "Switch this app on in Open Keyboard's Settings, Plugins first") }
        // a command plugin can only be known after the keyboard has looked; make sure it has
        if (CommandPlugins.providers.isEmpty()) context?.let { CommandPlugins.refresh(it) }
        val list = shared()
        return Bundle().apply {
            putStringArray("ids", list.map { it.id }.toTypedArray())
            putStringArray("names", list.map { it.name }.toTypedArray())
            putStringArray("kinds", list.map { it.usedBy.substringBefore(" (plugin app)") }.toTypedArray())
            putLongArray("sizes", list.map { it.bytes }.toLongArray())
            list.forEach { putStringArray("files_${it.id}", filesIn(it).toTypedArray()) }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Models are read only")
        val who = callerLabel() ?: throw SecurityException("Switch this app on in Open Keyboard's Settings, Plugins first")
        val segments = uri.pathSegments
        if (segments.size < 2) throw java.io.FileNotFoundException(uri.toString())
        val entry = shared().firstOrNull { it.id == segments[0] } ?: throw java.io.FileNotFoundException("No model ${segments[0]}")
        val root = entry.file.canonicalFile
        val file = if (root.isFile) root.takeIf { segments.size == 2 && segments[1] == root.name }
            else File(root, segments.drop(1).joinToString("/")).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
        if (file == null || !file.isFile) throw java.io.FileNotFoundException(uri.toString())
        ModelManager.noteSharedWith(context!!, entry, who)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private const val TAG = "ModelProvider"
        const val ACTION = "io.github.jrod86.openkeyboard.MODELS"
    }
}
