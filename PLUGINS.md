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

## 3. MCP servers

Under **Settings → Plugins → MCP servers**, people can add any MCP server that uses the Streamable
HTTP transport. They enter its `https://` address, plus a key if it needs one, which is sent as a
`Bearer` token. Each tool on the server becomes a "/" command named after the tool. What is typed
after the command goes to the tool's first text argument, or to named arguments written as
`/tool city=Sydney days=3`. Nothing is sent until the person taps **Ask**, and the text answer
replaces the command when tapped.

## Starter kit

The [`plugin-starter`](plugin-starter/) folder is a complete little app that adds `/hello`. Copy it,
rename the package, and build your own command from there.

## New plugins start switched off

When someone installs your app, its commands and text actions show up in **Settings → Plugins** marked
🆕, switched off. Nothing reaches your app until the person switches it on. Tell people where to do
that, for example on your app's first screen.

## Trust badge

**Settings → Plugins** shows a badge on every outside plugin, so people can see at a glance whether to
trust it:

- **🔓 Open source** when your app has this line in its manifest (or was installed from F-Droid):
  ```xml
  <meta-data android:name="io.github.jrod86.openkeyboard.SOURCE_URL" android:value="https://github.com/you/your-plugin" />
  ```
- **📴 Works offline** when your app doesn't ask for the `INTERNET` permission, otherwise
  **🌐 Can use the internet**.
- **👁 Sees…** what the keyboard gives it (for a command, only what is typed after it).

## Keeping the keyboard fast

The keyboard comes first. A plugin must never slow down typing:

- Answer quickly (3 seconds at most) and do slow work ahead of time.
- Run heavy work (AI models, images) in your own app, not on every key press.
- Free big things (models, pictures) when you're not using them, and when Android says memory is low
  (`onTrimMemory`).

**Settings → Plugins → ⚡ Keyboard speed** shows how fast the keyboard opens, any freezes, and how much
memory each part uses, so you can check your plugin doesn't slow anything down.

## AI models: use the ones already downloaded

**Settings → Plugins → 🧠 AI models** lists every model the keyboard has downloaded, its size and which
apps use it. Each model is downloaded once and shared, like Pinokio. Your plugin app can use these
models too, so people never download the same model twice. This works once the person has switched your
plugin on, and it is read only: you can't change or delete a model. Voices people made themselves are
never shared.

Find the keyboard's model door, and add this to your manifest so Android lets you see it:

```xml
<queries>
    <intent><action android:name="io.github.jrod86.openkeyboard.MODELS" /></intent>
</queries>
```

```kotlin
val info = packageManager.queryIntentContentProviders(Intent("io.github.jrod86.openkeyboard.MODELS"), 0)
    .firstOrNull()?.providerInfo ?: return // Open Keyboard isn't installed
val base = Uri.parse("content://${info.authority}")
val models = contentResolver.call(base, "models", null, null) ?: return
// "error" is set if the person hasn't switched your plugin on yet
val ids = models.getStringArray("ids")       // e.g. "voice-models", "llm_qwen2.5-1.5b-instruct-q4_k_m.gguf"
val names = models.getStringArray("names")   // "Voice typing", "Writing model (Quick)"…
val kinds = models.getStringArray("kinds")   // what the keyboard uses each one for
val sizes = models.getLongArray("sizes")     // bytes
val files = models.getStringArray("files_${ids!![0]}") // the files in that model

// read one file (no copy needed: llama.cpp and ONNX can open "/proc/self/fd/<fd>")
val fd = contentResolver.openFileDescriptor(Uri.withAppendedPath(base, "${ids[0]}/${files!![0]}"), "r")
```

The models list in the keyboard shows your app under "Used by" once it has opened a model.

## The plugin store

**Settings → Plugins → 🛍️ Plugin store** lists free, open source plugin apps. The list is the file
[`plugin-store.json`](https://github.com/jrod-86/open-keyboard/blob/apk/plugin-store.json), so it grows
without a keyboard update. To add yours, open a pull request with an entry like this:

```json
{
  "package": "com.you.yourplugin",
  "name": "Your plugin",
  "emoji": "🧩",
  "description": "One or two plain sentences on what it does.",
  "author": "You",
  "source": "https://github.com/you/your-plugin",
  "download": "https://github.com/you/your-plugin/releases/latest",
  "fdroid": "com.you.yourplugin",
  "offline": true,
  "size": "3 MB",
  "uses_models": "voice typing models"
}
```

`source` is required (open source only). `fdroid` is optional: when it's there and F-Droid is installed,
**Get** opens F-Droid, otherwise it opens `download`. `uses_models` says which of the shared AI models
your app uses, if any.
