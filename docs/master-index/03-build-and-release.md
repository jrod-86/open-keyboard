# Build and release

Updated 23 November 2026 (test 316).

## Test builds (Claude in the cloud Fri–Sun, Gemini locally Mon–Thu)
1. **Check the next test number:** Always check `HANDOFF.md` and `git ls-tree origin/apk` before numbering a build. Latest is **test 316** (`OpenKeyboard-test316.apk`), so the next build is **test 317**.
2. **Build:** `./gradlew assembleDebug` (or `.\gradlew.bat assembleDebug` on Windows) → `app/build/outputs/apk/debug/OpenKeyboard_*.apk` (~100 MB; big because of offline AI native libraries).
3. **Publish APK:** Copy to the `apk` branch as `OpenKeyboard-testN.apk`, prune the oldest APK so ~15 recent builds stay on `apk`, and push.
   Download link pattern:
   `https://github.com/jrod-86/open-keyboard/raw/apk/OpenKeyboard-testN.apk`
4. **Source & handoff:** Commit and push source changes to the active branch (`claude/project-thread-1y9nzr` until PR #12 is merged, or `main`), and update `HANDOFF.md` + `docs/master-index/06-test-log.md`.
- GitHub Releases can't be created from Claude's cloud sandbox, which is why test APKs live on the `apk` branch.
- Large AI models are **not** bundled in the APK; the app downloads them on first use (voice, MADLAD, Qwen, Kokoro, Zonos, Local Image models, CLAP, Stems) and verifies their SHA-256.

## Plugin store
- Branch `apk` also holds `plugin-store.json` plus standalone plugin APKs (`OpenKeyboard-plugin-installer.apk`, `OpenKeyboard-plugin-hello.apk`).

## Sticker store
- Branch `stickers`: `catalog.json` + `<id>.zip` + `<id>-icon.png`. No app update needed to add a pack (GitHub's raw cache can lag ~5 min).
- Zip: sticker files at the top (WebP/PNG/GIF) + `LICENSE.txt`; optional `alt/<name>.gif` copies for apps that flatten animated WebP (Messenger/Meta apps).
- Animated stickers: Jarrod's PC pipeline makes 512px VP9-alpha WebM → converted with `ffmpeg` (`libvpx-vp9` to keep alpha) to animated WebP (384px, 15 fps, q70, ~730 KB) and GIF (320px, 15 fps, per-frame palette, alpha threshold 128, ~1.3 MB).
- Note: WhatsApp refuses animated stickers over ~500 KB.

## Debugging on the phone
- Keyboard: **Settings → About → Save log** (or Bug report tile → Save/Share logs zip, includes native crash reports and ImageMaker timings).
- Clipboard/paste chip problems show in the log as `"paste chip"` lines.
