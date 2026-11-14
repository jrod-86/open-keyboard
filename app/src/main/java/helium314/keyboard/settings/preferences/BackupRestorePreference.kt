// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips
import helium314.keyboard.latin.utils.IntentUtils

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher { error = it }
    var askBackupPassword by rememberSaveable { mutableStateOf(false) }
    var lockedRestore by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreLauncher = restoreLauncher(onLocked = { lockedRestore = it.toString() }) { error = it }
    Preference(name = setting.title, onClick = { showDialog = true })
    WeeklyBackupPreference()
    if (askBackupPassword) PasswordDialog(
        title = "Lock your backup",
        text = "Pick a password. The backup is locked with it, and you'll need it to restore. Nobody else can open the file, " +
            "so don't lose it. Save the file anywhere, for example Google Drive.",
        confirm = true,
        onSkip = { askBackupPassword = false; pendingPassword = null; launchBackup(ctx, backupLauncher) },
    ) { pw -> askBackupPassword = false; pendingPassword = pw; launchBackup(ctx, backupLauncher) }
    lockedRestore?.let { uriText ->
        PasswordDialog(title = "Unlock backup", text = "This backup is locked. Enter the password you picked when you made it.", confirm = false,
            onSkip = null, onDismiss = { lockedRestore = null }) { pw ->
            lockedRestore = null
            doRestore(ctx, android.net.Uri.parse(uriText), pw) { error = it }
        }
    }
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                // locked backups aren't zip files, so allow any file
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                restoreLauncher.launch(intent)
            },
            onConfirmed = {
                showDialog = false
                askBackupPassword = true
            }
        )
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

@Composable
private fun backupLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val wait = CountDownLatch(1)
        val password = pendingPassword
        pendingPassword = null
        val plainFile = File(ctx.cacheDir, "backup-plain.tmp")
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                val target = if (password != null) plainFile.outputStream() else ctx.getActivity()?.contentResolver?.openOutputStream(uri)
                target?.use { os ->
                    writeBackupZip(ctx, os)
                }
                if (password != null) {
                    ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.use { BackupCrypto.encrypt(plainFile, it, password) }
                }
            } catch (t: Throwable) {
                onError("b" + t.message)
                Log.w("AdvancedScreen", "error during backup", t)
            } finally {
                plainFile.delete()
                password?.fill(' ')
                wait.countDown()
            }
        }
        wait.await()
    }
}

@Composable
private fun restoreLauncher(onLocked: (android.net.Uri) -> Unit, onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val locked = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { BackupCrypto.isLocked(it) } }.getOrDefault(false)
        if (locked) onLocked(uri) else doRestore(ctx, uri, null, onError)
    }
}

private fun doRestore(ctx: android.content.Context, uri: android.net.Uri, password: CharArray?, onError: (String) -> Unit) {
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
            val oldPrefs = ctx.prefs().all.toMap()
            val oldProtectedPrefs = ctx.protectedPrefs().all.toMap()
            val filesDir = ctx.filesDir!!
            val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
            val filesDir2 = File(filesDir.absolutePath + "2")
            val deviceProtectedFilesDir2 = File(deviceProtectedFilesDir.absolutePath + "2")
            filesDir.renameTo(filesDir2)
            deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir2)
            try {
                var anyMatch = false
                val plainFile = File(ctx.cacheDir, "restore-plain.tmp")
                if (password != null) BackupCrypto.decrypt({ ctx.contentResolver.openInputStream(uri)!! }, plainFile, password)
                (if (password != null) plainFile.inputStream() else ctx.contentResolver.openInputStream(uri))?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        LayoutUtilsCustom.onLayoutFileChanged()
                        Settings.getInstance().stopListener()
                        while (entry != null) {
                            if (entry.name.startsWith("unprotected${File.separator}")) {
                                val adjustedName = entry.name.substringAfter("unprotected${File.separator}")
                                if (backupFilePatterns.any { adjustedName.matches(it) }) {
                                    if (!restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName)) {
                                        Log.w("AdvancedScreen", "skipping unsafe backup entry $adjustedName")
                                    }
                                }
                                anyMatch = true
                            } else if (backupFilePatterns.any { entry.name.matches(it) }) {
                                if (!restoreEntryToDir(zip, filesDir, entry.name)) {
                                    Log.w("AdvancedScreen", "skipping unsafe backup entry ${entry.name}")
                                }
                                anyMatch = true
                            } else if (entry.name == Database.NAME) {
                                anyMatch = true
                                FileUtils.copyStreamToNewFile(zip, restoredDb)
                            } else if (entry.name == PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val prefs = ctx.prefs()
                                prefs.edit { clear() }
                                anyMatch = true
                                readJsonLinesToSettings(prefLines, prefs)
                            } else if (entry.name.startsWith("extra_prefs")) {
                                BackupCrypto.prefsForEntry(ctx, entry.name)?.let { sp ->
                                    val lines = String(zip.readBytes()).split("\n")
                                    sp.edit().clear().commit()
                                    readJsonLinesToSettings(lines, sp)
                                }
                            } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val protectedPrefs = ctx.protectedPrefs()
                                protectedPrefs.edit { clear() }
                                readJsonLinesToSettings(prefLines, protectedPrefs)
                                anyMatch = true
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                plainFile.delete()
                if (!anyMatch)
                    throw Exception("nothing to restore in the given file")

                Database.copyFromDb(restoredDb, ctx)
                // Open Keyboard: keep downloaded voice models, they are too big for a backup
                File(filesDir2, "voice-models").takeIf { it.isDirectory }?.renameTo(File(filesDir, "voice-models"))
                filesDir2.deleteRecursively()
                deviceProtectedFilesDir2.deleteRecursively()
                if (Looper.myLooper() == null)
                    Looper.prepare()
                Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                filesDir.deleteRecursively()
                filesDir2.renameTo(filesDir)
                deviceProtectedFilesDir.deleteRecursively()
                deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir)
                ctx.prefs().edit {
                    clear()
                    oldPrefs.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                ctx.protectedPrefs().edit {
                    clear()
                    oldProtectedPrefs.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                onError("r" + t.message)
                Log.w("AdvancedScreen", "error during restore", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
        checkVersionUpgrade(ctx)
        transferOldPinnedClips(ctx)
        Settings.getInstance().startListener()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        ctx.getActivity()?.sendBroadcast(newDictBroadcast)
        LayoutUtilsCustom.onLayoutFileChanged()
        LayoutUtilsCustom.removeMissingLayouts(ctx)
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
        SupportedEmojis.load(ctx)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

@Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    // now write
    out.write("boolean settings\n".toByteArray())
    out.write(Json.encodeToString(booleans).toByteArray())
    out.write("\nint settings\n".toByteArray())
    out.write(Json.encodeToString(ints).toByteArray())
    out.write("\nlong settings\n".toByteArray())
    out.write(Json.encodeToString(longs).toByteArray())
    out.write("\nfloat settings\n".toByteArray())
    out.write(Json.encodeToString(floats).toByteArray())
    out.write("\nstring settings\n".toByteArray())
    out.write(Json.encodeToString(strings).toByteArray())
    out.write("\nstring set settings\n".toByteArray())
    out.write(Json.encodeToString(stringSets).toByteArray())
}

private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
    val i = list.iterator()
    val e = prefs.edit()
    try {
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
            }
        }
        e.apply()
        return true
    } catch (e: Exception) {
        return false
    }
}

private fun restoreEntryToDir(zip: ZipInputStream, baseDir: File, entryName: String): Boolean {
    val file = File(baseDir, entryName)
    val canonicalBase = baseDir.canonicalFile
    val canonicalTarget = file.canonicalFile
    if (canonicalTarget.path != canonicalBase.path
        && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)
    ) return false
    FileUtils.copyStreamToNewFile(zip, file)
    return true
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"

private val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "custom_background_image.*".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
    "clipboard/.*".toRegex(),
    // Open Keyboard: design picture and installed sticker packs
    "design_background\\.png".toRegex(),
    "sticker-packs${File.separator}.*".toRegex(),
) }

// ---- Open Keyboard: password-locked backups ----

private var pendingPassword: CharArray? = null

private fun launchBackup(ctx: android.content.Context, launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
    val locked = pendingPassword != null
    val name = ctx.getString(R.string.english_ime_name).replace(" ", "_") + "_backup_$currentDate" + if (locked) ".okbackup" else ".zip"
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType(if (locked) "application/octet-stream" else "application/zip")
        .putExtra(Intent.EXTRA_TITLE, name)
    launcher.launch(intent)
}

@Composable
private fun PasswordDialog(
    title: String, text: String, confirm: Boolean,
    onSkip: (() -> Unit)?, onDismiss: () -> Unit = {}, onPassword: (CharArray) -> Unit,
) {
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val ok = pw.length >= 6 && (!confirm || pw == pw2)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(text)
                val vt = if (show) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()
                androidx.compose.material3.OutlinedTextField(pw, { pw = it }, label = { Text("Password (6 or more characters)") },
                    singleLine = true, visualTransformation = vt)
                if (confirm) androidx.compose.material3.OutlinedTextField(pw2, { pw2 = it }, label = { Text("Type it again") },
                    singleLine = true, visualTransformation = vt, isError = pw2.isNotEmpty() && pw2 != pw)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(show, { show = it })
                    Text("Show password")
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = ok, onClick = { onPassword(pw.toCharArray()); pw = ""; pw2 = "" }) { Text("OK") } },
        dismissButton = {
            if (onSkip != null) androidx.compose.material3.TextButton(onClick = onSkip) { Text("No password") }
            else androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Open Keyboard: the backup zip itself, used by the Backup button and the weekly automatic backup. */
internal fun writeBackupZip(ctx: android.content.Context, os: OutputStream) {
    // zip all files matching the backup patterns
    val filesDir = ctx.filesDir ?: return
    val filesPath = filesDir.path + File.separator
    val files = filesDir.walk().filter { it.isFile && backupFilePatterns.any { p -> it.path.replace(filesPath, "").matches(p) } }.toList()
    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val protectedFilesPath = protectedFilesDir.path + File.separator
    val protectedFiles = protectedFilesDir.walk().filter { it.isFile && backupFilePatterns.any { p -> it.path.replace(protectedFilesPath, "").matches(p) } }.toList()
    // write files to zip
    val zipStream = ZipOutputStream(os)
    files.forEach {
        val fileStream = FileInputStream(it).buffered()
        zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
        fileStream.copyTo(zipStream, 1024)
        fileStream.close()
        zipStream.closeEntry()
    }
    protectedFiles.forEach {
        val fileStream = FileInputStream(it).buffered()
        zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
        fileStream.copyTo(zipStream, 1024)
        fileStream.close()
        zipStream.closeEntry()
    }
    val dbFile = ctx.getDatabasePath(Database.NAME)
    if (dbFile.exists()) {
        val fileStream = FileInputStream(dbFile).buffered()
        zipStream.putNextEntry(ZipEntry(Database.NAME))
        fileStream.copyTo(zipStream, 1024)
        fileStream.close()
        zipStream.closeEntry()
    }
    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
    settingsToJsonStream(ctx.prefs().all, zipStream)
    zipStream.closeEntry()
    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
    settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
    zipStream.closeEntry()
    // Open Keyboard: our other settings files (per-app settings, stickers, translate, voice…)
    BackupCrypto.extraPrefs(ctx).forEach { (name, sp) ->
        zipStream.putNextEntry(ZipEntry(name))
        settingsToJsonStream(sp.all, zipStream)
        zipStream.closeEntry()
    }
    zipStream.close()
}

@Composable
private fun WeeklyBackupPreference() {
    val ctx = LocalContext.current
    var tick by rememberSaveable { mutableStateOf(0) }
    var pickedFile by rememberSaveable { mutableStateOf<String?>(null) }
    var manage by rememberSaveable { mutableStateOf(false) }
    val on = remember(tick) { AutoBackup.isOn(ctx) }
    val picker = filePicker { uri -> pickedFile = uri.toString() }
    val desc = remember(tick) {
        if (!on) "Off. Turn on to save a locked backup about once a week, for example to Google Drive."
        else {
            val last = AutoBackup.lastBackup(ctx)
            val whenText = if (last <= 0) "not yet" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(last))
            "On: " + (AutoBackup.fileName(ctx) ?: "your backup file") + ". Last backup: " + whenText +
                (AutoBackup.lastError(ctx)?.let { ". Last try failed: $it" } ?: "")
        }
    }
    Preference(name = "Weekly automatic backup", description = desc, onClick = {
        if (on) manage = true
        else {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "OpenKeyboard_weekly_backup.okbackup")
            picker.launch(intent)
        }
    })
    pickedFile?.let { uriText ->
        PasswordDialog(title = "Lock your weekly backup",
            text = "Pick a password for the weekly backup. You'll need it to restore. It stays on this phone only, so the keyboard can make backups without asking.",
            confirm = true, onSkip = null, onDismiss = { pickedFile = null }) { pw ->
            pickedFile = null
            val uri = android.net.Uri.parse(uriText)
            val name = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            AutoBackup.turnOn(ctx, uri, name, pw)
            Toast.makeText(ctx, "Weekly backup on. Making the first one now…", Toast.LENGTH_SHORT).show()
            tick++
        }
    }
    if (manage) ConfirmationDialog(
        onDismissRequest = { manage = false },
        title = { Text("Weekly automatic backup") },
        content = { Text(desc) },
        confirmButtonText = "Back up now",
        onConfirmed = {
            manage = false
            AutoBackup.runNow(ctx) { ok -> Toast.makeText(ctx, if (ok) "Backup saved" else "Backup failed", Toast.LENGTH_SHORT).show(); tick++ }
        },
        neutralButtonText = "Turn off",
        onNeutral = { manage = false; AutoBackup.turnOff(ctx); tick++ },
    )
}
