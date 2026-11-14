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
 * Open Keyboard: per-app settings ("This app" tile). Make an app "custom" and every keyboard setting you
 * change while typing in it (or in Settings opened from the keyboard there) is kept for that app only.
 * Other apps keep the normal settings. Works by wrapping the app's one SharedPreferences
 * (DeviceProtectedUtils.getSharedPreferences): reads and writes go to "<package>|<key>" in
 * open_keyboard_app_profiles while that app is the target.
 */
object AppProfiles {
    private const val TAG = "AppProfiles"
    private const val PREFS = "open_keyboard_app_profiles"
    private const val CUSTOM = "__custom"
    const val LANGUAGE = "__language"

    /** App the keyboard is typing in now, if it's custom. */
    @Volatile private var active: String? = null
    /** App whose settings are being changed in Settings opened from the keyboard. */
    @Volatile private var editing: String? = null
    val target: String? get() = active ?: editing

    private var storeCache: SharedPreferences? = null
    private fun store(ctx: Context): SharedPreferences = storeCache ?: run {
        val c = if (android.os.Build.VERSION.SDK_INT >= 24) ctx.createDeviceProtectedStorageContext() else ctx
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { storeCache = it }
    }
    private fun k(pkg: String, key: String) = "$pkg|$key"

    // things that are about the keyboard as a whole, not how it behaves in one app
    private val globalKeys = setOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE, Settings.PREF_ADDITIONAL_SUBTYPES,
        Settings.PREF_RECENT_EMOJIS, Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_ID, Settings.PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID,
        Settings.PREF_VERSION_CODE, Settings.PREF_LIBRARY_CHECKSUM, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_TOOLBAR_KEYS,
        Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, Settings.PREF_SAVE_SUBTYPE_PER_APP,
        "open_keyboard_menu_order", "open_keyboard_bar_items", "open_keyboard_my_designs", "dont_show_missing_dict_dialog")
    private fun isGlobal(key: String?) = key == null || key in globalKeys || key.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX)
            || key.startsWith("floating_pos") || key.startsWith("gesture_data")

    fun isCustom(ctx: Context, pkg: String?) = pkg != null && store(ctx).getBoolean(k(pkg, CUSTOM), false)

    fun setCustom(ctx: Context, pkg: String, on: Boolean) {
        if (on) store(ctx).edit().putBoolean(k(pkg, CUSTOM), true).apply()
        else clear(ctx, pkg)
        if (active == pkg || (on && active == null)) active = if (on) pkg else null
    }

    fun clear(ctx: Context, pkg: String) {
        val s = store(ctx)
        s.edit().apply { s.all.keys.filter { it.startsWith("$pkg|") }.forEach { remove(it) } }.apply()
        if (active == pkg) active = null
        if (editing == pkg) editing = null
    }

    /** Custom apps. */
    fun apps(ctx: Context): List<String> = store(ctx).all.keys.filter { it.endsWith("|$CUSTOM") }.map { it.substringBefore('|') }.sorted()

    fun changedCount(ctx: Context, pkg: String) = store(ctx).all.keys.count { it.startsWith("$pkg|") && !it.endsWith("|__custom") && !it.endsWith("|__language") }

    fun summary(ctx: Context, pkg: String): String {
        val n = changedCount(ctx, pkg)
        val lang = store(ctx).getString(k(pkg, LANGUAGE), null)?.let { pref ->
            SubtypeSettings.getEnabledSubtypes(true).firstOrNull { it.toSettingsSubtype().toPref() == pref }?.displayName()
        }
        return listOfNotNull(if (n == 0) "no settings changed yet" else "$n setting${if (n == 1) "" else "s"} changed", lang).joinToString(", ")
    }

    /** The keyboard started typing in [pkg]. */
    /** Returns true when the settings in use changed (so the keyboard redraws). */
    fun onStartInput(ctx: Context, pkg: String?): Boolean {
        if (pkg == null || pkg == ctx.packageName) active = null // our own screens keep "editing"
        else { active = if (isCustom(ctx, pkg)) pkg else null; editing = null; editingOwner = null }
        val changed = lastUsed != target
        lastUsed = target
        return changed
    }
    private var lastUsed: String? = null
    private var editingOwner: Any? = null

    /** Settings opened from the keyboard while typing in a custom app change that app's settings. */
    /** The custom app (if any) that Settings opened from the keyboard should change. */
    fun onOpenSettings(ctx: Context): String? = active

    /** Settings screen shown: it changes [pkg]'s own settings (null = normal settings). */
    fun onSettingsShown(ctx: Context, owner: Any, pkg: String?) {
        val p = pkg?.takeIf { isCustom(ctx, it) }
        if (p != null && editing != p)
            android.widget.Toast.makeText(ctx, "Changes you make now are for ${appName(ctx, p)} only", android.widget.Toast.LENGTH_LONG).show()
        editing = p
        editingOwner = owner
        Log.i(TAG, "settings shown, changing ${p ?: "normal"} settings")
    }

    /** Only the screen that set it can clear it (an old Settings screen can close after the new one opened). */
    fun onSettingsHidden(owner: Any) {
        if (editingOwner === owner) { editing = null; editingOwner = null }
    }

    /** Background picture file name part: pictures saved while changing a custom app are its own. */
    @JvmStatic fun backgroundSuffix(): String = target?.let { "_app_" + it.replace('.', '_') } ?: ""

    /** Whether the custom app in use has its own theme (else it shows the normal picture). */
    @JvmStatic fun hasOwnTheme(ctx: Context): Boolean = target?.let { store(ctx).contains(k(it, Settings.PREF_THEME_COLORS)) } ?: false

    fun appName(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // ---- the wrapper ----

    private var wrapped: SharedPreferences? = null

    fun wrap(ctx: Context, base: SharedPreferences): SharedPreferences =
        wrapped ?: Wrapper(ctx.applicationContext ?: ctx, base).also { wrapped = it }

    private class Wrapper(private val ctx: Context, private val base: SharedPreferences) : SharedPreferences {
        private fun t(key: String?): String? = target?.takeIf { !isGlobal(key) }
        private fun has(key: String?): String? = t(key)?.takeIf { store(ctx).contains(k(it, key!!)) }

        override fun getAll(): MutableMap<String, *> {
            val all = HashMap<String, Any?>(base.all)
            val pkg = target ?: return all
            store(ctx).all.forEach { (key, v) -> if (key.startsWith("$pkg|") && !key.substringAfter('|').startsWith("__")) all[key.substringAfter('|')] = v }
            return all
        }
        override fun getString(key: String?, defValue: String?) = has(key)?.let { store(ctx).getString(k(it, key!!), defValue) } ?: base.getString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = has(key)?.let { store(ctx).getStringSet(k(it, key!!), defValues) } ?: base.getStringSet(key, defValues)
        override fun getInt(key: String?, defValue: Int) = has(key)?.let { store(ctx).getInt(k(it, key!!), defValue) } ?: base.getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long) = has(key)?.let { store(ctx).getLong(k(it, key!!), defValue) } ?: base.getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float) = has(key)?.let { store(ctx).getFloat(k(it, key!!), defValue) } ?: base.getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean) = has(key)?.let { store(ctx).getBoolean(k(it, key!!), defValue) } ?: base.getBoolean(key, defValue)
        override fun contains(key: String?) = has(key) != null || base.contains(key)

        override fun edit(): SharedPreferences.Editor = WrapEditor(ctx, base.edit(), target)

        private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            base.registerOnSharedPreferenceChangeListener(listener)
            val forward = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key.startsWith("${target}|")) listener.onSharedPreferenceChanged(this, key.substringAfter('|'))
            }
            listeners[listener] = forward
            store(ctx).registerOnSharedPreferenceChangeListener(forward)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            base.unregisterOnSharedPreferenceChangeListener(listener)
            listeners.remove(listener)?.let { store(ctx).unregisterOnSharedPreferenceChangeListener(it) }
        }
    }

    private class WrapEditor(private val ctx: Context, private val base: SharedPreferences.Editor, private val pkg: String?) : SharedPreferences.Editor {
        private val own: SharedPreferences.Editor? = if (pkg != null) store(ctx).edit() else null
        private fun to(key: String?): Pair<SharedPreferences.Editor, String?> =
            if (own != null && !isGlobal(key)) { Log.i(TAG, "$key saved for $pkg only"); own to k(pkg!!, key!!) } else base to key
        override fun putString(key: String?, value: String?) = apply { to(key).let { it.first.putString(it.second, value) } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { to(key).let { it.first.putStringSet(it.second, values) } }
        override fun putInt(key: String?, value: Int) = apply { to(key).let { it.first.putInt(it.second, value) } }
        override fun putLong(key: String?, value: Long) = apply { to(key).let { it.first.putLong(it.second, value) } }
        override fun putFloat(key: String?, value: Float) = apply { to(key).let { it.first.putFloat(it.second, value) } }
        override fun putBoolean(key: String?, value: Boolean) = apply { to(key).let { it.first.putBoolean(it.second, value) } }
        override fun remove(key: String?) = apply { to(key).let { it.first.remove(it.second) } }
        override fun clear() = apply { base.clear() }
        override fun commit(): Boolean { own?.commit(); return base.commit() }
        override fun apply() { own?.apply(); base.apply() }
    }

    // ---- language: an app's own language, and back afterwards ----

    private var languageBefore: InputMethodSubtype? = null

    /** While typing in a custom app, the language you switch to is remembered for it. */
    fun onLanguageChosen(ctx: Context, subtype: InputMethodSubtype) {
        val pkg = active ?: return
        store(ctx).edit().putString(k(pkg, LANGUAGE), subtype.toSettingsSubtype().toPref()).apply()
    }

    /** Called when the keyboard starts in an app; returns a language to switch to, or null. */
    fun languageFor(ctx: Context, pkg: String?): InputMethodSubtype? {
        val current = runCatching { RichInputMethodManager.getInstance().currentSubtype.rawSubtype }.getOrNull()
        val pref = if (isCustom(ctx, pkg)) store(ctx).getString(k(pkg!!, LANGUAGE), null) else null
        if (pref != null) {
            val wanted = SubtypeSettings.getEnabledSubtypes(true).firstOrNull { it.toSettingsSubtype().toPref() == pref } ?: return null
            if (wanted == current) return null
            if (languageBefore == null) languageBefore = current
            Log.i(TAG, "$pkg: switching to its language")
            return wanted
        }
        if (pkg == ctx.packageName) return null
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

    private fun applyNow(ime: LatinIME, redraw: Boolean = false) {
        val sv = Settings.getValues()
        Settings.getInstance().loadSettings(ime, sv.mLocale, sv.mInputAttributes)
        if (redraw) { KeyboardMenu.hide(); KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        else KeyboardSwitcher.getInstance().reloadKeyboard()
    }

    fun show(context: Context) {
        val ime = ime(context) ?: return
        val pkg = ime.currentInputEditorInfo?.packageName ?: return
        val appName = appName(context, pkg)
        val appIcon = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        val tile = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.GESTURE_TRAIL)
        val prefs = context.prefs() // already this app's view when it's custom
        val custom = isCustom(context, pkg)

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
        fun note(s: String) = column.addView(TextView(context).apply {
            this.text = s
            setTextColor(ColorUtils.setAlphaComponent(text, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(context, 12f), dp(context, 4f), dp(context, 12f), dp(context, 4f))
        })
        fun row(title: String?, pills: List<TextView>) {
            title?.let { note(it) }
            val r = LinearLayout(context).apply { setPadding(dp(context, 10f), 0, dp(context, 10f), 0) }
            pills.forEach { r.addView(it, lp()) }
            column.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        }

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(context, 12f), 0, dp(context, 12f), dp(context, 2f)) }
        appIcon?.let { top.addView(ImageView(context).apply { setImageDrawable(it) },
            LinearLayout.LayoutParams(dp(context, 26f), dp(context, 26f)).apply { rightMargin = dp(context, 8f) }) }
        top.addView(TextView(context).apply {
            this.text = appName
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✕", false) { KeyboardMenu.hide() })
        column.addView(top)

        row(null, listOf(
            pill((if (!custom) "● " else "") + "Normal", !custom) {
                if (custom) {
                    setCustom(context, pkg, false)
                    active = null
                    languageFor(context, pkg)?.let { ime.switchToSubtype(it) }
                    android.widget.Toast.makeText(context, "$appName uses your normal settings again", android.widget.Toast.LENGTH_SHORT).show()
                    applyNow(ime, redraw = true)
                    return@pill
                }
                show(context)
            },
            pill((if (custom) "● " else "") + "Custom for $appName", custom) {
                if (!custom) {
                    setCustom(context, pkg, true)
                    active = pkg
                    android.widget.Toast.makeText(context, "Custom on: changes you make in $appName stay in $appName only", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { RichInputMethodManager.getInstance().currentSubtype.rawSubtype }.getOrNull()?.let { onLanguageChosen(context, it) }
                }
                show(context)
            },
        ))
        note(if (custom) "Custom is on. Anything you change while typing in $appName (theme, size, language, " +
                "autocorrect, sounds, even Settings opened from the keyboard here) stays in $appName only. " +
                "Every other app keeps your normal settings. Tap Normal to go back."
            else "Normal: $appName uses the same settings as every other app. Tap Custom to give it its own.")
        KeyboardMenu.showOverlay(root)
    }
}
