// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * Open Keyboard (test 219, ideas 4 and 5): the shared Model Manager. Every AI model the keyboard and its app drawer
 * apps have downloaded, in one list: how big it is, which apps share it, and a way to delete it. Each model is
 * downloaded once and used by every part that needs it (voice models by voice typing and Chat's Talk mode, the
 * writing model by Smart compose, Writing help, AI writer and Chat).
 *
 * Also the trust badge for outside plugin apps: open source or not, works offline or not, and what it gets to see.
 */
object ModelManager {
    class Entry(val name: String, val usedBy: String, val file: File, val bytes: Long, val userMade: Boolean = false) {
        /** Stable name for plugin apps (test 224): the folder or file name, e.g. "voice-models" or "qwen2.5-1.5b-instruct-q4_k_m.gguf". */
        val id: String get() = file.path.substringAfter("/files/").replace('/', '_')
    }

    private class Known(val path: String, val name: String, val usedBy: String, val external: Boolean = false, val userMade: Boolean = false)

    private val known = listOf(
        Known("voice-models", "Voice typing", "Voice typing, Chat Talk mode, voice commands"),
        Known("tts-models/kokoro-v1", "Kokoro voices", "TTS, Chat replies, Open Waves DJ"),
        Known("tts-models/zonos3-mini", "Zonos voices", "TTS, Open Waves DJ"),
        Known("tts-models/zonos3-clone", "Make a voice (cloning)", "TTS"),
        Known("tts-voices", "Voices you made", "TTS, Chat", userMade = true),
        Known("models/clap", "AI music listener (CLAP)", "Open Waves Moods"),
        Known("stem-models/spleeter4", "Stem splitter (Spleeter)", "Open Waves Stem mode"),
        Known("sing-model", "HQ vocal remover (UVR-MDX)", "Open Waves Sing mode"),
        Known("ocr", "Scan text (basic)", "Scan text"),
        Known("ocr-paddle", "Scan text", "Scan text"),
        Known("ocr-best", "Scan text (best)", "Scan text"),
        Known("sticker_sam", "Sticker cut-out", "Sticker maker"),
        Known("image_maker", "Local Image models", "Local Image"),
    )

    // the user's own things or small bits that aren't models
    private val notModels = setOf("dicts", "clipboard", "chat", "sticker-packs", "image_maker_gallery", "crash_report",
        "blacklists", "temp_clip", "tts-models", "llm", "models", "stem-models", "stems", "music", "Download", "Pictures", "Music")

    /** Everything downloaded, biggest first. Walks folders: call off the main thread. */
    fun list(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        val files = ctx.filesDir
        val ext = ctx.getExternalFilesDir(null)
        known.forEach { k ->
            val f = File(if (k.external) ext ?: files else files, k.path)
            val size = sizeOf(f)
            if (size > 0) out += Entry(k.name, withApps(ctx, k.usedBy, f), f, size, k.userMade)
        }
        // writing and translation models, one file each
        listOfNotNull(ext, files).distinct().forEach { root ->
            File(root, "llm").listFiles()?.filter { it.isFile && !it.name.endsWith(".ok") && it.length() > 0 }?.forEach { f ->
                val (name, usedBy) = when {
                    f.name == WritingTools.QUICK.fileName -> "Writing model (Quick)" to "Smart compose, Writing help, AI writer, Chat, Open Waves DJ"
                    f.name == WritingTools.BETTER.fileName -> "Writing model (Better)" to "Smart compose, Writing help, AI writer, Chat, Open Waves DJ"
                    f.name.startsWith("madlad") -> "Khmer translator" to "Translate"
                    else -> f.name to "?"
                }
                out += Entry(name, withApps(ctx, usedBy, f), f, f.length())
            }
        }
        // anything else big that we don't know by name (Translate's language models, for one)
        listOfNotNull(files, ext).distinct().forEach { root ->
            root.listFiles()?.filter { it.name !in notModels && known.none { k -> k.path == it.name } }?.forEach { f ->
                val size = sizeOf(f)
                if (size > 20_000_000) {
                    val (name, usedBy) = when {
                        f.name.contains("transl", true) || f.name.contains("fox", true) -> "Translate languages" to "Translate"
                        // test 222: left from the old Whisper voice typing (removed in test 186)
                        f.name.contains("whisper", true) -> "Old Whisper voice model (${f.name})" to "Nothing any more, safe to delete"
                        else -> f.name to "Not sure, probably left over from an older test"
                    }
                    out += Entry(name, usedBy, f, size)
                }
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    // ---------- sharing with plugin apps (test 224) ----------

    private fun sharePrefs(ctx: Context) = ctx.getSharedPreferences("open_keyboard_model_sharing", Context.MODE_PRIVATE)

    /** A plugin app opened this model: remember it, so the list can say who shares it. */
    fun noteSharedWith(ctx: Context, e: Entry, appLabel: String) {
        val p = sharePrefs(ctx)
        val now = p.getStringSet(e.file.path, emptySet()).orEmpty()
        if (appLabel !in now) p.edit().putStringSet(e.file.path, now + appLabel).apply()
    }

    private fun withApps(ctx: Context, usedBy: String, f: File): String {
        val apps = sharePrefs(ctx).getStringSet(f.path, emptySet()).orEmpty()
        return if (apps.isEmpty()) usedBy else usedBy + ", " + apps.sorted().joinToString(", ") + " (plugin app)"
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes one model. Returns null when done, or why it can't be deleted right now. */
    fun delete(ctx: Context, e: Entry): String? {
        if (e.usedBy.startsWith("Voice typing") && helium314.keyboard.voice.VoiceEngine.loaded) return "Voice typing is using it. Close the keyboard and try again."
        if (e.usedBy.startsWith("Smart compose")) {
            if (WritingTools.loaded) {
                WritingTools.freeIfIdle(ctx)
                return "The writing model is in use. It's being put away now, so try again in a few seconds (close Chat first if it's open)."
            }
        }
        if (e.file.path.contains("kokoro")) runCatching { helium314.keyboard.tts.KokoroTts.release() }
        if (e.file.path.contains("zonos")) runCatching { helium314.keyboard.tts.ZonosTts.stop() }
        val ok = if (e.file.isDirectory) e.file.deleteRecursively() else e.file.delete().also { File(e.file.path + ".ok").delete() }
        WritingTools.forgetReady()
        return if (ok) null else "Couldn't delete it all."
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1000} KB"
    }

    /** Trust badge for an outside plugin app: open source, works offline, and what it gets to see. */
    fun trust(ctx: Context, packageName: String, sees: String): String {
        val pm = ctx.packageManager
        val info: PackageInfo? = runCatching {
            @Suppress("DEPRECATION") pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        }.getOrNull()
        val online = info?.requestedPermissions?.contains(android.Manifest.permission.INTERNET) ?: true
        val source = info?.applicationInfo?.metaData?.getString("io.github.jrod86.openkeyboard.SOURCE_URL")
        val installer = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
        }.getOrNull()
        val open = source != null || installer in setOf("org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "dev.imranr.obtainium")
        return listOf(
            if (open) "🔓 Open source" else "🔒 Not known to be open source",
            if (online) "🌐 Can use the internet" else "📴 Works offline",
            "👁 Sees $sees",
        ).joinToString(" · ")
    }
}
