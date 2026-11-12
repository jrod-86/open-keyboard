# Third-party data

- `app/src/main/assets/dicts/emoji_en.dict`: emoji keywords from Unicode CLDR annotations
  (cldr-json, `cldr-annotations-full/annotations/en`), Unicode License v3, see `unicode-cldr-LICENSE.txt`.
  Built with `tools/emoji/make_emoji_dict.py` (keyword list) and the app's own dictionary writer.

- `app/libs/foxlet-0.5.0.aar`: Foxlet Translate (MIT), built from source at
  https://github.com/yinvoke/foxlet-translate commit 3e6b7b006ff3837e45338eeac16698a504a51d02 with `./gradlew :foxlet:assembleRelease`.
  Contains Mozilla Bergamot translator (MPL-2.0; source in the same repository, engine/), Marian (MIT),
  SentencePiece and ruy (Apache-2.0) and others; full notices are inside the AAR under
  io/github/yinvoker/foxlet/licenses/. Translation models are downloaded on the phone from Mozilla (MPL-2.0).
