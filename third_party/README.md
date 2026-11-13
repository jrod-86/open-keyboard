# Third-party data

- `app/src/main/assets/dicts/emoji_en.dict`: emoji keywords from Unicode CLDR annotations
  (cldr-json, `cldr-annotations-full/annotations/en`), Unicode License v3, see `unicode-cldr-LICENSE.txt`.
  Built with `tools/emoji/make_emoji_dict.py` (keyword list) and the app's own dictionary writer.

- `app/libs/foxlet-0.5.0.aar`: Foxlet Translate (MIT), built from source at
  https://github.com/yinvoke/foxlet-translate commit 3e6b7b006ff3837e45338eeac16698a504a51d02 with `./gradlew :foxlet:assembleRelease`.
  Contains Mozilla Bergamot translator (MPL-2.0; source in the same repository, engine/), Marian (MIT),
  SentencePiece and ruy (Apache-2.0) and others; full notices are inside the AAR under
  io/github/yinvoker/foxlet/licenses/. Translation models are downloaded on the phone from Mozilla (MPL-2.0).

- `app/libs/llama-android.aar`: llama.cpp (MIT) `examples/llama.android` library, built from source at
  https://github.com/ggml-org/llama.cpp commit 8ca5a8008b57aa39f2601546c0d07912045080a5 with
  `third_party/llama-android-open-keyboard.patch` applied (fresh chat per request, minSdk 28, arm64 only),
  using `./gradlew :lib:assembleRelease`. The writing model (Qwen2.5-1.5B-Instruct GGUF, Apache-2.0)
  is downloaded on the phone from Hugging Face and checked against its SHA-256.

- Tesseract4Android 4.8.0 (Apache-2.0; Tesseract Apache-2.0, Leptonica BSD-2-Clause, libjpeg/libpng permissive),
  from JitPack. `app/src/main/assets/tessdata/eng.traineddata`: tesseract-ocr/tessdata_fast (Apache-2.0),
  Scan text also downloads tesseract-ocr/tessdata_best eng.traineddata (Apache-2.0) on first use, pinned to
  commit e12c65a9 and checked by SHA-256; it is not bundled.
  sha256 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2.
