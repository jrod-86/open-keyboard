package io.github.jrod86.okinstaller

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * APK Installer, an Open Keyboard plugin app: pick an APK file (or open one with it, or let the keyboard's plugin
 * store hand it a download), see what it is, and install it with Android's own installer. The keyboard itself never
 * needs the right to install apps.
 */
class InstallActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var icon: ImageView
    private lateinit var install: Button
    private var apk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        col.addView(TextView(this).apply { text = "📦 APK Installer"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "Install apps from APK files, and plugins from Open Keyboard's plugin store. Android always asks you before anything is installed."
            setPadding(0, pad / 2, 0, pad)
        })
        col.addView(Button(this).apply { text = "Pick an APK file"; setOnClickListener { pick() } })
        icon = ImageView(this).apply { setPadding(0, pad, 0, 0) }
        col.addView(icon, LinearLayout.LayoutParams((64 * resources.displayMetrics.density).toInt(), (80 * resources.displayMetrics.density).toInt()))
        details = TextView(this).apply { textSize = 16f }
        col.addView(details)
        install = Button(this).apply { text = "Install"; isEnabled = false; setOnClickListener { installNow() } }
        col.addView(install)
        status = TextView(this).apply { setPadding(0, pad, 0, 0); gravity = Gravity.START }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        when {
            intent.action == ACTION_INSTALL && data.scheme == "https" -> download(data.toString(), intent.getStringExtra("sha256"))
            data.scheme == "content" -> load(data)
        }
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive"), PICK)
    }

    @Deprecated("plain Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    /** Copies the APK here first, so what gets checked is exactly what gets installed. */
    private fun load(uri: Uri) {
        say("Reading the file…")
        thread {
            val out = File(cacheDir, "picked.apk")
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }.isSuccess
            runOnUiThread { if (ok) show(out) else say("Couldn't read that file.") }
        }
    }

    private fun download(url: String, sha256: String?) {
        say("Downloading…")
        install.isEnabled = false
        thread {
            val out = File(cacheDir, "download.apk")
            val error = runCatching {
                var link = url
                var c: HttpURLConnection
                var hops = 0
                while (true) { // GitHub sends you on to where the file really is
                    c = URL(link).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    if (c.responseCode in 300..399 && hops++ < 5) { link = c.getHeaderField("Location"); c.disconnect(); continue }
                    break
                }
                if (c.responseCode != 200) error("the server said ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var shown = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0 && done - shown > 512 * 1024) { shown = done; val pct = done * 100 / total; runOnUiThread { say("Downloading… $pct%") } }
                    }
                } }
                if (sha256 != null && !sha256.equals(hash(out), true)) error("the download doesn't match what the store expected")
            }.exceptionOrNull()
            runOnUiThread { if (error == null) show(out) else say("Couldn't download it: ${error.message}") }
        }
    }

    private fun hash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun show(file: File) {
        @Suppress("DEPRECATION")
        val info: PackageInfo? = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_PERMISSIONS)
        if (info == null) { say("That isn't an app (APK) file Android can read."); details.text = ""; install.isEnabled = false; return }
        val app = info.applicationInfo!!.apply { sourceDir = file.path; publicSourceDir = file.path }
        val label = app.loadLabel(packageManager)
        icon.setImageDrawable(app.loadIcon(packageManager))
        val installed = runCatching { packageManager.getPackageInfo(info.packageName, 0) }.getOrNull()
        val perms = info.requestedPermissions.orEmpty()
        val notable = listOfNotNull(
            "the internet".takeIf { "android.permission.INTERNET" in perms },
            "the microphone".takeIf { "android.permission.RECORD_AUDIO" in perms },
            "the camera".takeIf { "android.permission.CAMERA" in perms },
            "your location".takeIf { perms.any { it.contains("LOCATION") } },
            "your contacts".takeIf { perms.any { it.contains("CONTACTS") } },
            "your text messages".takeIf { perms.any { it.contains("SMS") } },
            "installing other apps".takeIf { "android.permission.REQUEST_INSTALL_PACKAGES" in perms },
        )
        details.text = buildString {
            append("$label\n")
            append("Version ${info.versionName ?: "?"} · ${"%.1f".format(file.length() / 1e6)} MB\n")
            append(info.packageName + "\n")
            append(when {
                installed == null -> "New app\n"
                @Suppress("DEPRECATION") installed.versionCode > info.versionCode -> "⚠️ Older than the one you have (${installed.versionName})\n"
                else -> "Update for the one you have (${installed.versionName})\n"
            })
            append(if (notable.isEmpty()) "Asks for nothing special 👍" else "Can use: " + notable.joinToString(", "))
        }
        apk = file
        install.text = if (installed == null) "Install" else "Update"
        install.isEnabled = true
        say("")
    }

    private fun installNow() {
        val file = apk ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            say("First allow APK Installer to install apps, then come back and tap Install again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        install.isEnabled = false
        say("Installing…")
        thread {
            val error = runCatching {
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    file.inputStream().use { input -> session.openWrite("app.apk", 0, file.length()).use { input.copyTo(it); session.fsync(it) } }
                    val back = PendingIntent.getBroadcast(this, id, Intent(this, InstallResult::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(back.intentSender)
                }
            }.exceptionOrNull()
            runOnUiThread { if (error != null) { say("Couldn't install: ${error.message}"); install.isEnabled = true } }
        }
    }

    private fun say(s: String) { status.text = s }

    override fun onResume() {
        super.onResume()
        lastResult?.let { say(it); lastResult = null; install.isEnabled = apk != null }
    }

    companion object {
        const val ACTION_INSTALL = "io.github.jrod86.openkeyboard.INSTALL"
        private const val PICK = 1
        @Volatile var lastResult: String? = null
    }
}

/** Android's answer to an install: ask you to confirm, or say how it went. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                InstallActivity.lastResult = "✅ Installed. If it's a keyboard plugin, switch it on in Open Keyboard's Settings, Plugins."
                context.startActivity(Intent(context, InstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> {
                InstallActivity.lastResult = "Not installed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled")
                context.startActivity(Intent(context, InstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
