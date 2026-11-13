// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.database.ClipboardDao

/**
 * Search your clipboard history. Type a few letters, tap a match, and it's typed into the app you
 * were in. Everything stays on the phone.
 */
class ClipboardSearchActivity : Activity() {
    private lateinit var list: LinearLayout
    private var all: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)
        all = ClipboardDao.getInstance(this)?.getAll()?.sorted()?.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }.orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Search clipboard"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        val search = EditText(this).apply {
            hint = "Type to search"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { show(s?.toString().orEmpty()) }
            })
        }
        root.addView(search)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        show("")
        search.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun show(query: String) {
        list.removeAllViews()
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = all.filter { t -> val l = t.lowercase(); words.all { it in l } }
        if (matches.isEmpty()) list.addView(TextView(this).apply {
            text = if (all.isEmpty()) "Your clipboard history is empty." else "Nothing matches."
            setPadding(0, dp(12f), 0, 0)
        })
        matches.take(50).forEach { t ->
            list.addView(TextView(this).apply {
                text = t.take(300)
                maxLines = 4
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12f), 0, dp(12f))
                setOnClickListener { Plugins.setPending(t); finish() }
            })
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
