# Open Keyboard

An open source Android keyboard with private, offline voice typing.
Nothing you type or say leaves your phone.

Open Keyboard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) with the
offline Whisper voice input from [Whisper+](https://github.com/woheller69/whisperIMEplus)
built into the same app.

## Features

- Everything HeliBoard has: suggestions and spell check with add-on dictionaries,
  clipboard history, emoji search, themes, one-handed and split modes, custom layouts.
- Voice typing with OpenAI Whisper running fully on-device (ONNX Runtime).
- No internet permission. You download the speech model yourself and import it from a file.

Planned: NVIDIA Parakeet as a second, faster speech model, a model switcher, and live
results while you speak.

## Setting up voice typing

1. Install the APK and enable **Open Keyboard** in Android's keyboard settings.
2. Also enable **Open Keyboard voice typing** in the same list.
3. Open the **Voice typing setup** app, tap download to get the Whisper model zip
   (`whisper_small_int8.zip` from Hugging Face), then install it from the file.
4. Tap the mic key on the keyboard to dictate.

## Building

```
./gradlew assembleDebug
```

The APK appears in `app/build/outputs/apk/debug/`. GitHub Actions also builds a debug
APK on every push to `main` (see the Actions tab).

## License

Open Keyboard is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

It includes code under other compatible licences, kept with their notices:

| Component | Licence | Source |
| --- | --- | --- |
| HeliBoard (keyboard) | GPL-3.0 | https://github.com/HeliBorg/HeliBoard |
| AOSP LatinIME (HeliBoard's base) | Apache-2.0 (`LICENSE-Apache-2.0`) | https://android.googlesource.com/platform/packages/inputmethods/LatinIME |
| HeliBoard icon artwork | CC-BY-SA-4.0 (`LICENSE-CC-BY-SA-4.0`) | https://github.com/HeliBorg/HeliBoard |
| Whisper+ (`voice/` module) | GPL-3.0 | https://github.com/woheller69/whisperIMEplus |
| RTranslator speech code (inside Whisper+) | Apache-2.0 | https://github.com/niedev/RTranslator |
| ONNX Runtime | MIT | https://github.com/microsoft/onnxruntime |
| android-vad (WebRTC VAD) | MIT | https://github.com/gkonovalov/android-vad |
| OpenAI Whisper model weights | MIT | https://github.com/openai/whisper |

## Credits

Huge thanks to the HeliBoard, OpenBoard and AOSP keyboard contributors, and to
woheller69 for Whisper+. The original HeliBoard README is kept in `HELIBOARD_README.md`.
