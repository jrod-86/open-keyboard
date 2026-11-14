// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Open Keyboard: weekly automatic backup. You pick a file once (Google Drive works) and a password; about once a
 * week, when the keyboard opens, a fresh locked backup replaces that file. The password is kept on this phone only,
 * locked by Android's own key store, and is never part of a backup.
 */
object AutoBackup {
    const val PREFS = "open_keyboard_auto_backup" // left out of backups
    private const val KEY_ALIAS = "open_keyboard_backup_password"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    @Volatile private var running = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getString("file", null) != null
    fun lastBackup(ctx: Context) = prefs(ctx).getLong("last", 0L)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("error", null)
    fun fileName(ctx: Context): String? = prefs(ctx).getString("name", null)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    fun turnOn(ctx: Context, file: Uri, name: String?, password: CharArray) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(String(password).toByteArray())
        prefs(ctx).edit().putString("file", file.toString()).putString("name", name)
            .putString("pw", Base64.encodeToString(enc, Base64.NO_WRAP)).putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putLong("last", 0L).remove("error").apply()
        password.fill(' ')
        runNow(ctx)
    }

    fun turnOff(ctx: Context) {
        prefs(ctx).getString("file", null)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(ctx).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun password(ctx: Context): CharArray? = runCatching {
        val p = prefs(ctx)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p.getString("iv", null), Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(p.getString("pw", null), Base64.NO_WRAP))).toCharArray()
    }.getOrNull()

    /** Cheap check, called when the keyboard opens: backs up in the background if it's been a week. */
    fun maybeRun(ctx: Context) {
        if (running || !isOn(ctx)) return
        if (System.currentTimeMillis() - lastBackup(ctx) < WEEK) return
        runNow(ctx)
    }

    fun runNow(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (running) return
        val app = ctx.applicationContext
        val file = prefs(app).getString("file", null)?.let { Uri.parse(it) } ?: return
        running = true
        Thread {
            val plain = File(app.cacheDir, "auto-backup-plain.tmp")
            val ok = runCatching {
                val pw = password(app) ?: error("password lost, turn weekly backup off and on again")
                plain.outputStream().use { writeBackupZip(app, it) }
                app.contentResolver.openOutputStream(file, "wt")!!.use { BackupCrypto.encrypt(plain, it, pw) }
                pw.fill(' ')
            }
            plain.delete()
            ok.exceptionOrNull()?.let { Log.w("AutoBackup", "weekly backup failed", it) }
            prefs(app).edit().apply {
                if (ok.isSuccess) putLong("last", System.currentTimeMillis()).remove("error")
                else putString("error", ok.exceptionOrNull()?.message ?: "failed").putLong("last", System.currentTimeMillis() - WEEK + 24L * 60 * 60 * 1000) // try again tomorrow
            }.apply()
            running = false
            done?.let { d -> android.os.Handler(android.os.Looper.getMainLooper()).post { d(ok.isSuccess) } }
        }.start()
    }
}
