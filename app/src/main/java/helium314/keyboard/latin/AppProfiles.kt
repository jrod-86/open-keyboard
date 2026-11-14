// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import android.view.inputmethod.InputMethodSubtype

/**
 * Open Keyboard: settings for one app ("This app" tile). They switch in automatically whenever the
 * keyboard opens in that app, and every other app keeps the normal settings.
 * Stored as "<package>|<field>" in prefs file open_keyboard_app_profiles.
 */
object AppProfiles {
    private const val TAG = "AppProfiles"
    private const val PREFS = "open_keyboard_app_profiles"
    const val HEIGHT = "height"         // float multiplier on the normal keyboard height
    const val LANGUAGE = "language"     // settings subtype (pref form)
    const val AUTOCORRECT = "autocorrect"
    const val SUGGESTIONS = "suggestions"
    const val COMPOSE = "compose"       // AI next-word guesses
    const val SOUND = "sound"
    const val VIBRATE = "vibrate"

    private fun store(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun k(pkg: String, field: String) = "$pkg|$field"

    fun has(ctx: Context, pkg: String?, field: String) = pkg != null && store(ctx).contains(k(pkg, field))
    fun bool(ctx: Context, pkg: String?, field: String): Boolean? =
        if (has(ctx, pkg, field)) store(ctx).getBoolean(k(pkg!!, field), true) else null
    fun float(ctx: Context, pkg: String?, field: String): Float? =
        if (has(ctx, pkg, field)) store(ctx).getFloat(k(pkg!!, field), 1f) else null
    fun string(ctx: Context, pkg: String?, field: String): String? =
        if (pkg == null) null else store(ctx).getString(k(pkg, field), null)

    fun set(ctx: Context, pkg: String, field: String, value: Any?) {
        store(ctx).edit().apply {
            when (value) {
                null -> remove(k(pkg, field))
                is Boolean -> putBoolean(k(pkg, field), value)
                is Float -> putFloat(k(pkg, field), value)
                else -> putString(k(pkg, field), value.toString())
            }
        }.apply()
    }

    fun clear(ctx: Context, pkg: String) {
        val s = store(ctx)
        s.edit().apply { s.all.keys.filter { it.startsWith("$pkg|") }.forEach { remove(it) } }.apply()
    }

    /** Apps that have their own settings. */
    fun apps(ctx: Context): List<String> = store(ctx).all.keys.map { it.substringBefore('|') }.distinct().sorted()

    fun summary(ctx: Context, pkg: String): String {
        val parts = mutableListOf<String>()
        float(ctx, pkg, HEIGHT)?.let { parts += if (it < 1f) "smaller" else "bigger" }
        string(ctx, pkg, LANGUAGE)?.let { pref ->
            SubtypeSettings.getEnabledSubtypes(true).firstOrNull { it.toSettingsSubtype().toPref() == pref }?.let { parts += it.displayName() }
        }
        bool(ctx, pkg, AUTOCORRECT)?.let { parts += "autocorrect " + if (it) "on" else "off" }
        bool(ctx, pkg, SUGGESTIONS)?.let { parts += "suggestions " + if (it) "on" else "off" }
        bool(ctx, pkg, COMPOSE)?.let { parts += "AI guesses " + if (it) "on" else "off" }
        bool(ctx, pkg, SOUND)?.let { parts += "sound " + if (it) "on" else "off" }
        bool(ctx, pkg, VIBRATE)?.let { parts += "vibration " + if (it) "on" else "off" }
        return parts.joinToString(", ")
    }

    /** The normal settings with this app's changes on top. Writes go to the normal settings. */
    fun overlay(ctx: Context, base: SharedPreferences, pkg: String?): SharedPreferences {
        if (pkg == null || apps(ctx).none { it == pkg }) return base
        return Overlay(ctx, base, pkg)
    }

    private class Overlay(private val ctx: Context, private val base: SharedPreferences, private val pkg: String) : SharedPreferences by base {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val field = when (key) {
                Settings.PREF_AUTO_CORRECTION -> AUTOCORRECT
                Settings.PREF_SHOW_SUGGESTIONS -> SUGGESTIONS
                Settings.PREF_SOUND_ON -> SOUND
                Settings.PREF_VIBRATE_ON -> VIBRATE
                else -> null
            }
            return field?.let { bool(ctx, pkg, it) } ?: base.getBoolean(key, defValue)
        }
        override fun getFloat(key: String?, defValue: Float): Float {
            val v = base.getFloat(key, defValue)
            if (key == null || !key.startsWith(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX)) return v
            return v * (float(ctx, pkg, HEIGHT) ?: 1f)
        }
    }

    // ---- language: switch to this app's language, and back afterwards ----

    private var languageBefore: InputMethodSubtype? = null

    /** Called when the keyboard starts in an app; returns a language to switch to, or null. */
    fun languageFor(ctx: Context, pkg: String?): InputMethodSubtype? {
        val current = runCatching { RichInputMethodManager.getInstance().currentSubtype.rawSubtype }.getOrNull()
        val pref = string(ctx, pkg, LANGUAGE)
        if (pref != null) {
            val target = SubtypeSettings.getEnabledSubtypes(true).firstOrNull { it.toSettingsSubtype().toPref() == pref } ?: return null
            if (target == current) return null
            if (languageBefore == null) languageBefore = current
            Log.i(TAG, "$pkg: switching to its language")
            return target
        }
        // left an app with its own language: back to the one used before
        val back = languageBefore ?: return null
        languageBefore = null
        return back.takeIf { it != current && SubtypeSettings.getEnabledSubtypes(true).contains(it) }
    }

    // ---- the "This app" panel ----

    private fun ime(context: Context): LatinIME? {
        var c: Context? = context
        while (c is ContextWrapper && c !is LatinIME) c = c.baseContext
        return c as? LatinIME
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    @SuppressLint("ViewConstructor")
    private class Root(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun applyNow(ime: LatinIME) {
        val sv = Settings.getValues()
        Settings.getInstance().loadSettings(ime, sv.mLocale, sv.mInputAttributes)
        KeyboardSwitcher.getInstance().reloadKeyboard()
    }

    fun show(context: Context) {
        val ime = ime(context) ?: return
        val pkg = ime.currentInputEditorInfo?.packageName ?: return
        val pm = context.packageManager
        val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val appIcon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        val tile = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.GESTURE_TRAIL)
        val prefs = context.prefs()

        val root = Root(context).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255))
            isClickable = true
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(context, 8f), 0, dp(context, 8f)) }
        root.addView(ScrollView(context).apply { addView(column) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun pill(s: String, on: Boolean, onClick: () -> Unit) = TextView(context).apply {
            this.text = s
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16f).toFloat()
                setColor(if (on) ColorUtils.setAlphaComponent(accent, 120) else tile)
            }
            setOnClickListener { onClick() }
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(context, 6f); bottomMargin = dp(context, 6f)
        }
        fun refresh() { show(context) }
        fun row(title: String, pills: List<TextView>) {
            column.addView(TextView(context).apply {
                this.text = title
                setTextColor(ColorUtils.setAlphaComponent(text, 180))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 3f))
            })
            val r = LinearLayout(context).apply { setPadding(dp(context, 10f), 0, dp(context, 10f), 0) }
            pills.forEach { r.addView(it, lp()) }
            column.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        }

        // header: app icon, name, ✕
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(context, 12f), 0, dp(context, 12f), dp(context, 2f)) }
        appIcon?.let { top.addView(ImageView(context).apply { setImageDrawable(it) },
            LinearLayout.LayoutParams(dp(context, 26f), dp(context, 26f)).apply { rightMargin = dp(context, 8f) }) }
        top.addView(TextView(context).apply {
            this.text = "Settings for $appName"
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✕", false) { KeyboardMenu.hide() })
        column.addView(top)
        column.addView(TextView(context).apply {
            this.text = "Only used in $appName. Switches in by itself every time you type here."
            setTextColor(ColorUtils.setAlphaComponent(text, 150))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(context, 12f), 0, dp(context, 12f), dp(context, 2f))
        })

        // height
        val h = float(context, pkg, HEIGHT)
        row("Keyboard height", listOf(
            pill("Smaller", h != null && h < 1f) { set(context, pkg, HEIGHT, 0.88f); applyNow(ime); refresh() },
            pill("Normal", h == null) { set(context, pkg, HEIGHT, null); applyNow(ime); refresh() },
            pill("Bigger", h != null && h > 1f) { set(context, pkg, HEIGHT, 1.12f); applyNow(ime); refresh() },
        ))

        // language
        val langPref = string(context, pkg, LANGUAGE)
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size > 1 || langPref != null) {
            row("Start in this language", listOf(pill("Normal", langPref == null) {
                set(context, pkg, LANGUAGE, null); refresh()
            }) + subtypes.map { st ->
                val pref = st.toSettingsSubtype().toPref()
                pill(st.displayName(), pref == langPref) {
                    set(context, pkg, LANGUAGE, pref)
                    KeyboardMenu.hide()
                    languageFor(context, pkg)?.let { ime.switchToSubtype(it) }
                }
            })
        }

        // on/off switches, showing what's in effect here
        fun toggle(label: String, field: String, normal: Boolean): TextView {
            val override = bool(context, pkg, field)
            val now = override ?: normal
            return pill((if (now) "✓ " else "✕ ") + label + if (override != null) " •" else "", override != null) {
                // first tap flips it for this app; tapping back to the normal value clears it
                val next = !now
                set(context, pkg, field, if (next == normal) null else next)
                applyNow(ime); refresh()
            }
        }
        row("Turn on or off here (• = different from normal)", listOf(
            toggle("Autocorrect", AUTOCORRECT, prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)),
            toggle("Suggestions", SUGGESTIONS, prefs.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS)),
            toggle("AI guesses", COMPOSE, Plugins.isOn(context, "compose")),
            toggle("Key sounds", SOUND, prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)),
            toggle("Vibration", VIBRATE, prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)),
        ))

        if (apps(context).contains(pkg)) {
            val r = LinearLayout(context).apply { setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), 0) }
            r.addView(pill("Back to normal for $appName", false) {
                clear(context, pkg)
                languageFor(context, pkg)?.let { ime.switchToSubtype(it) }
                applyNow(ime); refresh()
            }, lp())
            column.addView(r)
        }
        KeyboardMenu.showOverlay(root)
    }
}
