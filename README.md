# Open Keyboard

An open source Android keyboard with private, offline voice typing.
Nothing you type or say leaves your phone.

Open Keyboard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) with
Gboard-style voice typing built in, powered by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

## Features

- Everything HeliBoard has: suggestions and spell check with add-on dictionaries,
  clipboard history, emoji search, themes, one-handed and split modes, custom layouts.
- Gboard-like defaults: rounded keys, number hints on the top row, taller keys, and the mic
  pinned at the top right.
- Voice typing like Gboard: tap the mic once, words appear as you speak, and each sentence
  is re-checked by an accurate model (NVIDIA Parakeet or OpenAI Whisper) when you pause.
  Tap again, or stay quiet for 30 seconds, to stop.
- Speech recognition runs fully on the phone. The internet permission is only used to
  download the speech models once.

## Setting up voice typing

1. Install the APK and enable **Open Keyboard** in Android's keyboard settings.
2. Open the keyboard settings and tap **Voice typing** (or just tap the mic once).
3. Allow the microphone, download **Live English** (73 MB) and one accuracy model:
   Parakeet (661 MB, recommended) or Whisper small.en (375 MB).
4. Tap the mic at the top right of the keyboard and start talking.

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
| sherpa-onnx (speech engine) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (inside sherpa-onnx) | MIT | https://github.com/microsoft/onnxruntime |
| GTCRN noise remover (`assets/gtcrn_simple.onnx`) | MIT (`LICENSE-MIT-GTCRN`) | https://github.com/Xiaobin-Rong/gtcrn |

Speech models are not bundled; the app downloads them when you ask it to:

| Model | Licence | Source |
| --- | --- | --- |
| Streaming Zipformer English (icefall) | Apache-2.0 | https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26 |
| NVIDIA Parakeet TDT 0.6B v2 | CC-BY-4.0, credit: NVIDIA | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2 |
| OpenAI Whisper small.en | MIT | https://github.com/openai/whisper |
| OpenAI Whisper large-v3-turbo | MIT | https://github.com/openai/whisper |

## Credits

Huge thanks to the HeliBoard, OpenBoard and AOSP keyboard contributors, the k2-fsa
sherpa-onnx team, NVIDIA for Parakeet, OpenAI for Whisper, and woheller69 for Whisper+,
which the first test build used. The original HeliBoard README is kept in `HELIBOARD_README.md`.
