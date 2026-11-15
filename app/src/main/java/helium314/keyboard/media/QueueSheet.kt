// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private const val IDEAS_SYSTEM = "You suggest ideas for pictures. The user gives a theme and how many ideas they want. " +
    "Reply with exactly that many picture ideas, one per line, each 6 to 15 words. Make every idea clearly different: " +
    "different subjects, places, colours and moods, all fitting the theme. Keep them family-friendly. " +
    "No numbers, no bullets, no extra text: only the ideas, one per line."

/**
 * The 🌙 queue sheet: line up pictures to draw one after another (for example overnight), either ✍️ by hand or
 * ✨ automatically from a theme, where the writing helper invents the ideas (and, if wanted, detailed descriptions).
 */
class QueueSheet(
    private val a: Activity,
    private val theme: ImageTheme,
    private val best: Boolean,
    /** The 🪄 instructions and length for the model the pictures use. */
    private val enhance: Pair<String, Int>,
    private val styleName: String,
    private val settingsLabel: String,
    private val perPictureSecs: Long,
    private val mainWords: () -> String,
    private val makeItem: (String) -> ImageQueue.Item,
    private val onStart: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.resources.displayMetrics).toInt()
    private lateinit var listBox: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var autoStatus: TextView
    @Volatile private var writing = false
    @Volatile private var open = true
    private var dialog: AlertDialog? = null

    private fun text(s: String, size: Float = 15f, bold: Boolean = false, alpha: Float = 1f) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(theme.text); this.alpha = alpha
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun box(hint: String, lines: Int) = EditText(a).apply {
        this.hint = hint; setTextColor(theme.text); setHintTextColor((theme.text and 0x00FFFFFF) or 0x80000000.toInt())
        setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        maxLines = lines; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = UiKit.rounded(a, 14f, theme.card, theme.line); setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(a).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        if (filled) {
            setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.a1, theme.a2)).apply { cornerRadius = dp(22f).toFloat() }
        } else {
            setTextColor(theme.text); background = UiKit.rounded(a, 22f, theme.card, theme.line)
        }
        setOnClickListener { onClick() }
    }

    fun show() {
        val pad = dp(18f)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8f), pad, dp(8f)) }

        // two modes, picked with a switch at the top
        val autoPanel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val manualPanel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tabs = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = UiKit.rounded(a, 20f, theme.card, theme.line)
        }
        val tabViews = mutableListOf<TextView>()
        fun pick(i: Int) {
            autoPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
            manualPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
            tabViews.forEachIndexed { k, t ->
                t.background = if (k == i) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.a1, theme.a2)).apply { cornerRadius = dp(17f).toFloat() } else null
                t.setTextColor(if (k == i) Color.WHITE else theme.text)
            }
        }
        listOf("✨ Automatic", "✍️ Manual").forEachIndexed { i, label ->
            val t = TextView(a).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(9f), 0, dp(9f))
                setOnClickListener { pick(i) }
            }
            tabViews.add(t); tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)
        root.addView(autoPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        root.addView(manualPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })

        // ✨ automatic: a theme and how many
        autoPanel.addView(text("Give it a theme and it writes all the picture ideas for you.", 14f, alpha = 0.75f))
        val themeBox = box("Your theme, e.g. Christmas cards", 3)
        autoPanel.addView(themeBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        var count = 10
        val countRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val countText = text("10", 20f, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(48f) }
        countRow.addView(text("How many pictures", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countRow.addView(button("−", false) { count = (count - 1).coerceAtLeast(1); countText.text = "$count" })
        countRow.addView(countText)
        countRow.addView(button("+", false) { count = (count + 1).coerceAtMost(50); countText.text = "$count" })
        autoPanel.addView(countRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        val detailed = CheckBox(a).apply {
            text = "🪄 Detailed descriptions (better pictures, takes longer to write)"; isChecked = best; setTextColor(theme.text)
        }
        autoPanel.addView(detailed)
        autoStatus = text("", 14f, alpha = 0.8f)
        autoPanel.addView(button("✨ Write the ideas", true) { writeIdeas(themeBox.text.toString().trim(), count, detailed.isChecked) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        autoPanel.addView(autoStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })

        // ✍️ manual: type each one
        manualPanel.addView(text("Type each picture you want, then add it. Repeat as often as you like.", 14f, alpha = 0.75f))
        val manualBox = box("Describe a picture", 4)
        manualPanel.addView(manualBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        val manualRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        manualRow.addView(button("➕ Add", true) {
            val w = manualBox.text.toString().trim()
            if (w.isEmpty()) return@button
            SafePrompt.check(w)?.let { toast(it); return@button }
            ImageQueue.add(a, makeItem(w)); manualBox.setText(""); refreshList()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4f) })
        manualRow.addView(button("➕ Add the main box", false) {
            val w = mainWords().trim()
            if (w.isEmpty()) { toast("The main box is empty."); return@button }
            SafePrompt.check(w)?.let { toast(it); return@button }
            ImageQueue.add(a, makeItem(w)); refreshList()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) })
        manualPanel.addView(manualRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        // the queue itself
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listTitle = text("", 16f, bold = true)
        head.addView(listTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text("Clear all", 14f).apply {
            setTextColor(theme.a1); setPadding(dp(8f), dp(6f), 0, dp(6f))
            setOnClickListener { ImageQueue.clear(a); refreshList() }
        })
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        root.addView(text("Each one uses: $settingsLabel. Change these on the main screen before adding.", 13f, alpha = 0.65f))
        listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        root.addView(text("🔌 For a long run, plug in your charger. You can turn the screen off and go to sleep: it keeps going, " +
            "and every picture lands in your gallery.", 13f, alpha = 0.7f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        refreshList()
        pick(0)

        dialog = AlertDialog.Builder(a)
            .setTitle("🌙 Picture queue")
            .setView(ScrollView(a).apply { addView(root); setBackgroundColor(theme.bg) })
            .setPositiveButton("▶ Start", null)
            .setNegativeButton("Close", null)
            .setOnDismissListener { open = false; onClosed() }
            .create()
        dialog!!.setOnShowListener {
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    writing -> toast("Still writing the ideas, one moment.")
                    ImageQueue.items(a).isEmpty() -> toast("Add some pictures first.")
                    ImageJob.running -> toast("A picture is being drawn; start the queue when it's done.")
                    else -> { dialog?.dismiss(); onStart() }
                }
            }
        }
        dialog!!.window?.setBackgroundDrawable(UiKit.rounded(a, 24f, theme.bg))
        dialog!!.show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun fmt(secs: Long) = when {
        secs >= 3600 -> "${secs / 3600} h ${(secs % 3600 + 30) / 60} min"
        secs >= 60 -> "${(secs + 30) / 60} min"
        else -> "$secs s"
    }

    private fun refreshList() {
        val items = ImageQueue.items(a)
        listTitle.text = if (items.isEmpty()) "In the queue: nothing yet" else "In the queue: ${items.size} · about ${fmt(items.size * perPictureSecs)}"
        listBox.removeAllViews()
        items.forEachIndexed { i, item ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = UiKit.rounded(a, 12f, theme.card, theme.line); setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
            }
            row.addView(text("${i + 1}. ${item.words}", 14f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text("✕", 18f, alpha = 0.6f).apply {
                setPadding(dp(12f), dp(4f), dp(10f), dp(4f)); contentDescription = "Remove"
                setOnClickListener { ImageQueue.removeAt(a, i); refreshList() }
            })
            listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        }
    }

    /** The writing helper invents [count] ideas for [theme], then (if [detailed]) turns each into a full description. */
    private fun writeIdeas(theme: String, count: Int, detailed: Boolean) {
        if (writing) return
        if (theme.isEmpty()) { toast("Type a theme first."); return }
        SafePrompt.check(theme)?.let { autoStatus.text = it; return }
        if (ImageJob.running) { autoStatus.text = "A picture is being drawn right now. Write the ideas when it's done (both together need too much memory)."; return }
        if (!helium314.keyboard.latin.WritingTools.modelReady(a)) {
            autoStatus.text = "Automatic mode uses the keyboard's Writing help model. Open Writing help from the keyboard menu once to download it (1.1 GB), then come back."
            return
        }
        writing = true
        autoStatus.text = "✨ Waking up the writing helper…"
        val style = if (styleName == "None") "" else " The picture style will be: $styleName."
        thread(name = "image-queue-writer") {
            var added = 0
            val err = runCatching {
                kotlinx.coroutines.runBlocking {
                    helium314.keyboard.latin.WritingTools.withEngine(a) { engine ->
                        suspend fun ask(system: String, user: String, max: Int): String {
                            engine.startFresh(); engine.setSystemPrompt(system)
                            val sb = StringBuilder()
                            engine.sendUserPrompt(user, max).collect { sb.append(it) }
                            return sb.toString()
                        }
                        // first the short ideas, asking again for any that are missing
                        val ideas = mutableListOf<String>()
                        var tries = 0
                        while (ideas.size < count && tries < 3 && open) {
                            tries++
                            val need = count - ideas.size
                            ui { autoStatus.text = "✨ Thinking up $need idea${if (need == 1) "" else "s"} about \"$theme\"…" }
                            val avoid = if (ideas.isEmpty()) "" else "\nAlready have (don't repeat): " + ideas.joinToString("; ")
                            val raw = ask(IDEAS_SYSTEM, "Theme: $theme\nHow many: $need$avoid", need * 30 + 40)
                            raw.lines().map { it.trim().replace(Regex("^(\\d+[.)]|[-*•])\\s*"), "").trim('"', ' ') }
                                .filter { it.split(' ').size >= 3 && SafePrompt.check(it) == null && it !in ideas }
                                .take(need).forEach { ideas.add(it) }
                        }
                        // then each one, written in full if wanted, straight into the queue
                        for ((i, idea) in ideas.withIndex()) {
                            if (!open && !writing) break
                            var words = idea
                            if (detailed) {
                                ui { autoStatus.text = "🪄 Writing picture ${i + 1} of ${ideas.size}…" }
                                val full = cleanUp(ask(enhance.first, "Idea: $idea$style", enhance.second))
                                if (full.isNotBlank() && !full.contains("BLOCKED") && SafePrompt.check(full) == null) words = full
                            }
                            ImageQueue.add(a, makeItem(words)); added++
                            ui { if (open) refreshList() }
                        }
                    }
                    helium314.keyboard.latin.WritingTools.unloadNow(a)
                }
            }.exceptionOrNull()
            writing = false
            ui {
                autoStatus.text = when {
                    err != null && added == 0 -> "Couldn't write the ideas (${err.message}). Try again."
                    added == 0 -> "No ideas came out for that theme. Try other words."
                    else -> "✅ Added $added picture${if (added == 1) "" else "s"} to the queue. Tap ▶ Start when you're ready."
                }
                if (open) refreshList()
            }
        }
    }

    private fun ui(f: () -> Unit) = a.runOnUiThread { runCatching(f) }

    private fun cleanUp(raw: String): String {
        var t = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        for (p in listOf("Example description:", "Description:", "Prompt:")) t = t.removePrefix(p).trim()
        t = t.trim('"').trim()
        val end = t.lastIndexOf('.')
        if (end > t.length / 2) t = t.substring(0, end + 1)
        return t.take(1500)
    }
}
