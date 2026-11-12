# Writing a plugin for Open Keyboard

Everything extra in Open Keyboard is a plugin, and people can switch each one on or off under
**Settings → Plugins**. Other apps can be plugins in two ways.

## 1. Text actions (works with existing apps)

Any app that handles Android's standard `ACTION_PROCESS_TEXT` intent shows up in the keyboard
menu. The keyboard sends it the selected text, or all the text in the box if nothing is selected,
and replaces that text with the answer the app returns in `EXTRA_PROCESS_TEXT`. Many translators and
dictionaries already do this. There's nothing extra to add.

## 2. "/" commands

An app can add its own commands, such as `/weather sydney`. When someone types the command, the
keyboard asks your app for the answer and shows it in the suggestion bar. Tapping the answer
replaces the command.

Declare a content provider with this intent filter:

```xml
<provider
    android:name=".MyCommands"
    android:authorities="com.example.mycommands"
    android:exported="true">
    <intent-filter>
        <action android:name="io.github.jrod86.openkeyboard.COMMANDS" />
    </intent-filter>
</provider>
```

Then answer two calls in `call()`:

```kotlin
class MyCommands : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        // the commands you offer, and a usage hint for each
        "commands" -> Bundle().apply {
            putStringArray("names", arrayOf("weather"))
            putStringArray("usages", arrayOf("/weather sydney"))
        }
        // arg is the command name, extras "args" is what was typed after it
        "run" -> Bundle().apply {
            val place = extras?.getString("args").orEmpty()
            putString("text", "It's sunny in $place")
        }
        else -> null
    }
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: Uri) = null
    override fun insert(u: Uri, v: ContentValues?) = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
```

Rules:

- Command names use only the lowercase letters a to z and the digits 0 to 9.
- Answer within 3 seconds, or the keyboard gives up. For slow work, return quickly and cache.
- Return no `"text"` when you have no answer, and the keyboard shows your usage hint.
- Built-in commands (`calc`, `date`, `time`, `convert`, `shrug`, `tableflip`, `lenny`, `flip`, `roll`,
  `random`) win if your app uses the same name.

## Privacy

A command plugin only ever receives what is typed after its own command. It never sees anything else
typed in the keyboard. A text action only receives text when the person taps it in the menu.
