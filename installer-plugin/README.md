# APK Installer (Open Keyboard plugin app)

Installs apps from APK files, and plugins from Open Keyboard's plugin store, with Android's own
installer. It shows what the app is, its version, whether it's new or an update, and what it can use
(internet, microphone, camera…) before you install. Android always asks you to confirm.

- Pick an APK file, or use "Open with → APK Installer" on one.
- Open Keyboard's plugin store hands it a download with the intent action
  `io.github.jrod86.openkeyboard.INSTALL` (https address, optional `sha256` extra to check the file).

The keyboard itself never gets the right to install apps; only this app does.
Build: `./gradlew assembleDebug`.
