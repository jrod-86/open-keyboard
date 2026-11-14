// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Open Keyboard: backups locked with the user's password.
 * File: "OKBACKUP1\n" + salt(16) + iv(16) + AES-256-CBC ciphertext + HMAC-SHA256(32) over everything before it.
 * Keys come from the password with PBKDF2-SHA256 (200 000 rounds). Without the password the file can't be read.
 */
object BackupCrypto {
    private val MAGIC = "OKBACKUP1\n".toByteArray()
    private const val ROUNDS = 200_000

    private fun keys(password: CharArray, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, ROUNDS, 512)).encoded
        return SecretKeySpec(bytes.copyOfRange(0, 32), "AES") to SecretKeySpec(bytes.copyOfRange(32, 64), "HmacSHA256")
    }

    fun isLocked(input: InputStream): Boolean {
        val head = ByteArray(MAGIC.size)
        var n = 0
        while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
        return n == head.size && head.contentEquals(MAGIC)
    }

    fun encrypt(plain: File, out: OutputStream, password: CharArray) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val (aes, hmac) = keys(password, salt)
        val mac = Mac.getInstance("HmacSHA256").apply { init(hmac) }
        val macOut = object : OutputStream() {
            override fun write(b: Int) { mac.update(b.toByte()); out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { mac.update(b, off, len); out.write(b, off, len) }
        }
        macOut.write(MAGIC); macOut.write(salt); macOut.write(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, aes, IvParameterSpec(iv)) }
        val cos = CipherOutputStream(object : OutputStream() { // keep [out] open for the MAC
            override fun write(b: Int) = macOut.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = macOut.write(b, off, len)
        }, cipher)
        plain.inputStream().use { it.copyTo(cos, 64 * 1024) }
        cos.close()
        out.write(mac.doFinal())
        out.flush()
    }

    /** Checks the password and the file, then writes the unlocked zip to [plain]. Throws WrongPassword. */
    fun decrypt(open: () -> InputStream, plain: File, password: CharArray) {
        // first pass: read salt/iv, check the MAC over everything
        val salt = ByteArray(16); val iv = ByteArray(16)
        var total = 0L
        open().use { input ->
            val head = ByteArray(MAGIC.size + 32)
            readFully(input, head)
            head.copyInto(salt, 0, MAGIC.size, MAGIC.size + 16)
            head.copyInto(iv, 0, MAGIC.size + 16, MAGIC.size + 32)
            total = head.size + input.skipAll()
        }
        val (aes, hmac) = keys(password, salt)
        val bodyEnd = total - 32
        if (bodyEnd < MAGIC.size + 32) throw WrongPassword()
        val mac = Mac.getInstance("HmacSHA256").apply { init(hmac) }
        val tag = ByteArray(32)
        open().use { input ->
            var left = bodyEnd
            val buf = ByteArray(64 * 1024)
            while (left > 0) { val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw WrongPassword(); mac.update(buf, 0, r); left -= r }
            readFully(input, tag)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw WrongPassword()
        // second pass: decrypt
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, aes, IvParameterSpec(iv)) }
        open().use { input ->
            input.skipExactly(MAGIC.size + 32L)
            var left = bodyEnd - MAGIC.size - 32
            plain.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (left > 0) { val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; out.write(cipher.update(buf, 0, r) ?: ByteArray(0)); left -= r }
                out.write(cipher.doFinal())
            }
        }
    }

    class WrongPassword : Exception("wrong password, or the file is damaged")

    private fun readFully(input: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) throw WrongPassword(); n += r }
    }
    private fun InputStream.skipAll(): Long { var t = 0L; val buf = ByteArray(64 * 1024); while (true) { val r = read(buf); if (r < 0) return t; t += r } }
    private fun InputStream.skipExactly(n: Long) { var left = n; val buf = ByteArray(8192); while (left > 0) { val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) return; left -= r } }

    // ---- our other settings files (stickers, app profiles, translate, voice…), saved as JSON in the backup ----

    private fun prefsNames(dir: File?): List<String> =
        dir?.let { File(it.parentFile, "shared_prefs") }?.listFiles()?.filter { it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }.orEmpty()

    /** (zip entry name, preferences) for every settings file the normal backup doesn't already cover. */
    fun extraPrefs(ctx: Context): List<Pair<String, SharedPreferences>> {
        val main = setOf(ctx.packageName + "_preferences", "${ctx.packageName}_preferences")
        val dp = ctx.createDeviceProtectedStorageContext()
        return prefsNames(ctx.filesDir).filter { it !in main && !it.startsWith("WebView") }.map { "extra_prefs/$it.json" to ctx.getSharedPreferences(it, Context.MODE_PRIVATE) } +
            prefsNames(dp.filesDir).filter { it !in main && !it.startsWith(ctx.packageName) }.map { "extra_prefs_dp/$it.json" to dp.getSharedPreferences(it, Context.MODE_PRIVATE) }
    }

    fun prefsForEntry(ctx: Context, entry: String): SharedPreferences? {
        val name = entry.substringAfterLast('/').removeSuffix(".json")
        if (name.isEmpty() || name.contains("..")) return null
        return when {
            entry.startsWith("extra_prefs/") -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            entry.startsWith("extra_prefs_dp/") -> ctx.createDeviceProtectedStorageContext().getSharedPreferences(name, Context.MODE_PRIVATE)
            else -> null
        }
    }
}
