# Open Keyboard plugin starter kit

A tiny, complete Android app that adds a `/hello` command to Open Keyboard. Copy this folder, rename
the package, and change `HelloCommands.kt` to do your own thing.

1. Open this folder in Android Studio (it's its own project, separate from the keyboard).
2. Change `com.example.okplugin` to your own package name in `app/build.gradle.kts`,
   `AndroidManifest.xml` and the Kotlin file.
3. Put your source code address in the `SOURCE_URL` line of the manifest, so the keyboard shows the
   🔓 Open source badge.
4. Run it on your phone, open the keyboard, and type `/hello Sam`.

The keyboard finds the plugin by itself. It shows up under **Settings → Plugins → Commands from your
apps**, with its trust badge, and can be switched off there. The full guide is in
[PLUGINS.md](../PLUGINS.md).

This starter kit doesn't ask for the internet permission, so it gets the 📴 Works offline badge.
Keep it that way if you can: people trust plugins that keep their typing on the phone.
