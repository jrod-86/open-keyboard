// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.utils.fitAboveKeyboard
import helium314.keyboard.media.UiKit
import helium314.keyboard.media.ImageThemes
import helium314.keyboard.media.Themed
import kotlin.concurrent.thread

/**
 * "Make a voice": record or pick a short clip of someone talking, tick that it's yours or you have permission, name
 * it, and it becomes one of your Zonos voices (everything on the phone, see VoiceClones).
 */
class MakeVoiceActivity : Activity(), Themed {
    /** Follows the TTS screen's 🎨 theme. */
    override val pageTheme by lazy { ImageThemes.current(this, ImageThemes.night(this), ImageThemes.KEY_TTS) }
    companion object {
        const val EXTRA_VOICE = "voice"
        private const val TAG = "MakeVoice"
        private const val PICK = 1
        private const val MIC = 2
        private const val READ_THIS = "Here's something to read: The sun came up over the hills, and the whole town slowly woke. " +
            "I made a cup of tea, opened the window, and listened to the birds for a while. Later, I'll call a few friends, " +
            "go for a long walk by the water, and maybe cook something nice for dinner. Honestly, days like this are the best."
    }

    private lateinit var clipText: TextView
    private lateinit var record: Button
    private lateinit var play: Button
    private lateinit var stopButton: Button
    private lateinit var readBox: TextView
    private lateinit var name: EditText
    private lateinit var consent: CheckBox
    private lateinit var make: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var remove: Button

    private var clip: FloatArray? = null
    @Volatile private var recording = false
    @Volatile private var working = false
    @Volatile private var cancelled = false
    private var track: AudioTrack? = null
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(UiKit.shinyTitle(this, "🎤 Make a voice"))
        root.addView(TextView(this).apply {
            text = "Copy a voice from a short clip, then use it in Text to speech like any other voice. It all happens on your phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.7f; setPadding(0, dp(2f), 0, pad * 3 / 4)
        })

        root.addView(label("1 · THE CLIP"))
        root.addView(TextView(this).apply {
            text = "10 to 20 seconds of one person talking clearly works best: a quiet room, no music, no other voices."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.8f; setPadding(dp(4f), 0, 0, dp(10f))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        record = UiKit.chip(this, "🎙 Record") { if (recording) recording = false else startRecording() }
        row.addView(record, LinearLayout.LayoutParams(0, dp(48f), 1f))
        row.addView(UiKit.chip(this, "📁 Pick a file") { pick() }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(8f) })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        readBox = TextView(this).apply {
            text = READ_THIS; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); visibility = View.GONE
            val p = dp(14f); setPadding(p, p, p, p); background = UiKit.card(this@MakeVoiceActivity, 16f)
        }
        root.addView(readBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        val clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clipText = TextView(this).apply { text = "No clip yet."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(4f), 0, 0, 0) }
        clipRow.addView(clipText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        play = UiKit.chip(this, "▶ Play") { playPause() }.apply { visibility = View.GONE }
        clipRow.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)))
        stopButton = UiKit.chip(this, "⏹ Stop") { stopPlayback(); refresh() }.apply { visibility = View.GONE }
        clipRow.addView(stopButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginStart = dp(8f) })
        root.addView(clipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        root.addView(label("2 · NAME IT"))
        name = UiKit.field(this, "Like \"My voice\" or \"Narrator\"").apply { setSingleLine() }
        root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("3 · PERMISSION"))
        consent = CheckBox(this).apply {
            text = "This is my own voice, or I have permission from the person whose voice it is."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnCheckedChangeListener { _, _ -> refresh() }
        }
        root.addView(consent)
        root.addView(TextView(this).apply {
            text = "Please don't use a copied voice to trick people or to pretend to be someone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.6f; setPadding(dp(4f), dp(4f), 0, 0)
        })

        make = UiKit.glow(this, "✨ Make my voice") { if (working) { cancelled = true } else makeVoice() }
        root.addView(make, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)).apply { topMargin = pad })
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(4f)) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(pageTheme.a1)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        remove = UiKit.outline(this, "") { removeModels() }
        root.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)).apply { topMargin = pad * 2 })

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        ImageThemes.applyPage(this, root, pageTheme)
        setContentView(scroll)
        scroll.fitAboveKeyboard(includeTop = true)
        refresh()
    }

    private fun refresh() {
        val c = clip
        clipText.text = if (c == null) "No clip yet." else "✅ Clip ready · ${c.size / VoiceClones.RATE} s"
        play.visibility = if (c != null && !recording) View.VISIBLE else View.GONE
        play.text = when { track == null -> "▶ Play"; paused -> "▶ Resume"; else -> "⏸ Pause" }
        stopButton.visibility = if (track != null && !recording) View.VISIBLE else View.GONE
        record.text = if (recording) "⏹ Stop" else if (c != null) "🎙 Record again" else "🎙 Record"
        readBox.visibility = if (recording) View.VISIBLE else View.GONE
        val ready = VoiceClones.installed(this)
        make.text = when {
            working -> "Cancel"
            ready -> "✨ Make my voice"
            else -> "✨ Make my voice (first gets ${mb(VoiceClones.missingBytes(this))} MB)"
        }
        make.isEnabled = working || (c != null && consent.isChecked && !recording)
        make.alpha = if (make.isEnabled) 1f else 0.5f
        remove.visibility = if (ready && !working) View.VISIBLE else View.GONE
        remove.text = "Remove the voice-copying files (${mb(VoiceClones.TOTAL_BYTES)} MB)"
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = android.graphics.Typeface.DEFAULT_BOLD; alpha = 0.6f
        letterSpacing = 0.08f; setPadding(dp(4f), dp(20f), 0, dp(8f))
    }

    // ---- getting a clip ----

    private fun pick() {
        if (recording || working) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        runCatching { startActivityForResult(i, PICK) }.onFailure { toast("No file picker on this phone") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Reading the clip…"
        thread(name = "voice-decode") {
            val r = runCatching { VoiceClones.decode(this, uri) }
            runOnUiThread {
                r.onSuccess { setClip(it) }.onFailure {
                    Log.w(TAG, "decode failed", it); status.text = "Couldn't read that file. Try a different sound or video file."
                }
            }
        }
    }

    private fun startRecording() {
        if (working) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC); return
        }
        stopPlayback()
        recording = true; status.text = "Recording… read the words below, then tap Stop."
        refresh()
        thread(name = "voice-record") {
            val rate = VoiceClones.RATE
            val all = VoiceClones.FloatList()
            val r = runCatching {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
                try {
                    rec.startRecording()
                    val buf = ShortArray(rate / 10)
                    val t0 = System.currentTimeMillis()
                    while (recording && all.size < (VoiceClones.MAX_SECONDS + 2) * rate) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) throw IllegalStateException("mic error $n")
                        for (i in 0 until n) all.add(buf[i] / 32768f)
                        val s = ((System.currentTimeMillis() - t0) / 1000).toInt()
                        runOnUiThread { if (recording) record.text = "⏹ Stop · 0:%02d".format(s) }
                    }
                } finally { runCatching { rec.stop() }; rec.release() }
            }
            recording = false
            runOnUiThread {
                r.onSuccess { setClip(VoiceClones.tidy(all.toArray())) }.onFailure {
                    Log.w(TAG, "recording failed", it); status.text = "The mic didn't work. Is another app using it?"; refresh()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else if (requestCode == MIC) toast("The mic is needed to record. You can pick a file instead.")
    }

    private fun setClip(a: FloatArray) {
        val secs = a.size / VoiceClones.RATE.toFloat()
        when {
            secs < VoiceClones.MIN_SECONDS -> { status.text = "That's only ${"%.0f".format(secs)} s of talking. Try at least 10 seconds."; refresh(); return }
            VoiceClones.rms(a) < 0.02f -> { status.text = "That clip is very quiet. Try again closer to the mic."; refresh(); return }
        }
        clip = a
        status.text = if (secs >= VoiceClones.MAX_SECONDS - 1) "Using the first ${VoiceClones.MAX_SECONDS} seconds." else ""
        refresh()
    }

    /** Play, then the same button pauses and resumes; Stop sits next to it while the clip is playing or paused. */
    private fun playPause() {
        val t = track
        when {
            t == null -> playClip()
            paused -> { runCatching { t.play() }; paused = false }
            else -> { runCatching { t.pause() }; paused = true }
        }
        refresh()
    }

    private fun playClip() {
        val a = clip ?: return
        stopPlayback()
        val rate = VoiceClones.RATE
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(a.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(a, 0, a.size, AudioTrack.WRITE_BLOCKING)
        // back to "▶ Play" once the clip has played to the end
        t.notificationMarkerPosition = a.size
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(at: AudioTrack) { if (track === at) { stopPlayback(); refresh() } }
            override fun onPeriodicNotification(at: AudioTrack) {}
        })
        t.play(); track = t; paused = false
    }

    private fun stopPlayback() { track?.let { runCatching { it.stop(); it.release() } }; track = null; paused = false }

    // ---- making it ----

    private fun makeVoice() {
        val a = clip ?: return
        if (!consent.isChecked) return
        val voiceName = name.text.toString().trim().ifEmpty { "My voice" }.take(24)
        stopPlayback()
        working = true; cancelled = false
        progress.visibility = View.VISIBLE
        refresh()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(name.windowToken, 0)
        thread(name = "voice-make") {
            val r = runCatching {
                if (!VoiceClones.installed(this)) {
                    runOnUiThread { status.text = "Getting the voice-copying files (one time)…"; progress.isIndeterminate = false }
                    VoiceClones.download(this, { cancelled || isDestroyed }) { p ->
                        runOnUiThread { progress.progress = (p * 1000).toInt(); status.text = "Getting the voice-copying files… ${(p * 100).toInt()}%" }
                    }
                }
                if (cancelled) throw InterruptedException()
                runOnUiThread { status.text = "Learning the voice… this takes a minute or two."; progress.isIndeterminate = true }
                val codes = VoiceClones.encode(this, a)
                if (cancelled) throw InterruptedException()
                VoiceClones.save(this, voiceName, codes, a.size / VoiceClones.RATE.toFloat())
            }
            runOnUiThread {
                working = false
                progress.visibility = View.GONE; progress.isIndeterminate = false
                r.onSuccess { v ->
                    toast("\"${v.name}\" is ready")
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VOICE, v.id))
                    finish()
                }.onFailure {
                    Log.w(TAG, "make voice failed", it)
                    status.text = if (it is InterruptedException) "Stopped. Tap Make my voice to carry on."
                    else if (!VoiceClones.installed(this)) "Download didn't finish. Check the internet and try again."
                    else "Something went wrong making the voice. Try again, or send a Bug report."
                }
                refresh()
            }
        }
    }

    private fun removeModels() {
        AlertDialog.Builder(this).setMessage("Remove the voice-copying files (${mb(VoiceClones.TOTAL_BYTES)} MB)? The voices you've made stay, and you can download the files again any time.")
            .setPositiveButton("Remove") { _, _ -> VoiceClones.deleteModels(this); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun mb(bytes: Long) = (bytes + 500_000) / 1_000_000
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float) = UiKit.dp(this, v)

    override fun onDestroy() {
        recording = false; cancelled = true
        stopPlayback()
        super.onDestroy()
    }
}
