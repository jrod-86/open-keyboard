// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.utils.fitAboveKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The AI writer's talk box: the mic is already listening, say (or type) what you want written,
 * tap Write it (or say "send"), check the text, then Insert puts it in the app you were in.
 */
class AiWriterActivity : Activity() {
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var request: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var resultCard: LinearLayout
    private var written: String? = null
    private var askedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "✨ Write for me"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Close", false) { finish() })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "Say or type what you want, like \"a happy birthday message for my wife\". Then tap Write it, or say \"send\"."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.8f
            setPadding(0, dp(6f), 0, dp(8f))
        })

        request = EditText(this).apply {
            hint = "What should I write?"
            // wraps over a few lines, while Enter means "write it"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            privateImeOptions = AiWriter.MIC_OPTION
            setOnEditorActionListener { _, _, _ -> startWriting(null); true }
        }
        root.addView(request)
        root.addView(button("Write it", true) { startWriting(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.8f; setPadding(0, dp(10f), 0, 0); visibility = View.GONE }
        root.addView(status)

        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0x227C4DFF) ; setStroke(dp(1f), 0x557C4DFF) }
        }
        result = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextIsSelectable(true) }
        resultCard.addView(result)
        val scroll = ScrollView(this).apply { addView(resultCard) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10f) })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Insert", true) { insert() }, weight())
        row1.addView(button(AiWriter.Tweak.AGAIN.label, false) { startWriting(AiWriter.Tweak.AGAIN) }, weight())
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(AiWriter.Tweak.SHORTER, AiWriter.Tweak.LONGER, AiWriter.Tweak.WARMER, AiWriter.Tweak.FUNNIER).forEach { t ->
            row2.addView(button(t.label, false, small = true) { startWriting(t) }, weight())
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "actions"; addView(row1); addView(row2) }
        root.addView(actions)
        this.actions = actions

        setContentView(root)
        root.fitAboveKeyboard()
        request.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private lateinit var actions: LinearLayout

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3f), dp(6f), dp(3f), 0) }

    private fun button(label: String, primary: Boolean, small: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 14f else 16f)
        if (primary) { setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD) }
        setPadding(dp(12f), dp(if (small) 9f else 11f), dp(12f), dp(if (small) 9f else 11f))
        background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            if (primary) setColor(0xFF7C4DFF.toInt()) else { setColor(0x00000000); setStroke(dp(1f), 0x887C4DFF.toInt()) }
        }
        setOnClickListener { onClick() }
    }

    private fun startWriting(tweak: AiWriter.Tweak?) {
        if (job?.isActive == true) return
        val typed = request.text.toString().trim()
        val ask = if (tweak != null) askedFor ?: typed else typed
        if (ask.isEmpty()) { status.visibility = View.VISIBLE; status.text = "Say or type what you'd like written first."; return }
        val previous = if (tweak != null) written else null
        askedFor = ask
        // put the keyboard away: stops the mic and makes room for the text
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(request.windowToken, 0)
        status.visibility = View.VISIBLE
        status.text = "✨ Writing… (the first one after a while takes a few extra seconds)"
        actions.visibility = View.GONE
        resultCard.visibility = View.VISIBLE
        result.text = ""
        job = scope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    AiWriter.write(applicationContext, ask, previous, tweak) { soFar ->
                        result.post { result.text = soFar.trim() }
                    }
                }.getOrNull()
            }
            if (out == null) {
                status.text = "Couldn't write that. Please try again."
                if (written != null) { result.text = written; actions.visibility = View.VISIBLE } else resultCard.visibility = View.GONE
                return@launch
            }
            written = out
            result.text = out
            status.text = "Happy with it? Tap Insert. Or change it below."
            actions.visibility = View.VISIBLE
        }
    }

    private fun insert() {
        val text = written ?: return
        Plugins.setPending(text)
        WritingTools.rememberForUndo("", text)
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
