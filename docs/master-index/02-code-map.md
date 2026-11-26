# Code map

Updated 23 November 2026 (test 316).
Repo root: `app/src/main/java/helium314/keyboard/` (plus top-level plugin modules `installer-plugin/` and `plugin-starter/`). Everything below is ours unless marked HeliBoard.

## `latin/` (keyboard features)
| File | Job |
|---|---|
| `KeyboardMenu.kt` | Tile grid, pages (`TilePager`), drag and drop, top bar items, `tr()` translated tile names, long-press Add to home screen |
| `LatinIME.java` (HeliBoard, edited) | Main IME service; top-bar chip order, FancyText hook, speed audit, auto-backup trigger |
| `inputlogic/InputLogic.java` (HeliBoard, edited) | Hold-delete speed stages (always starts slow), `onDeleteReleased` |
| `AppProfiles.kt`, `AppProfilesActivity.kt` | Per-app Custom mode (`open_keyboard_app_profiles`), This app panel, Settings page |
| `LanguagePanel.kt` | Language tile + bilingual typing row |
| `MyInfo.kt` | My info cards and smart chips |
| `LongPaste.kt` | Long paste in parts |
| `ClipboardHistoryManager.kt`, `ClipboardSearchActivity.kt` | Clipboard refresh, codes, search, smart paste chip |
| `SmartCompose.kt`, `WritingTools.kt` | Next-words chip, Writing help, Writer mode (talks to `WriterService` in `:chat` process) |
| `TextShortcuts.kt`, `SlashCommands.kt`, `CommandPlugins.kt` | Shortcuts, `/` commands, external app commands |
| `TranslatePanel.kt`, `MadladTranslator.kt` | Translate panel; MADLAD for languages Mozilla lacks |
| `ScanTextActivity.kt`, `PaddleOcr.kt` | Camera OCR scan text |
| `FancyText.kt` | 50 Unicode styles |
| `McpServers.kt`, `Plugins.kt` | MCP client, local MCP server, plugin registry, plugin store, `ModelProvider` |
| `FeedbackActivity.kt` | Bug report / feature request + log zip |
| `SmsCodes.kt`, `KeySounds.kt`, `NativeCrashes.kt` | OTP codes, sound packs, crash logs |
| `AutocorrectMemory.kt` | Only cleans up after the dropped feature |

## `music/` (Open Waves — own `:music` process)
| Files | Job |
|---|---|
| `MusicActivity.kt`, `MusicService.kt`, `MusicLibrary.kt`, `SessionLook.kt` | Main UI, Media3 playback service (`:music`), library scanner, MediaSession artwork/controls |
| `WavesUi.kt`, `WavesStudio.kt`, `WavesThemes.kt`, `WavesFx2.kt`, `WavesLogo.kt`, `MusicIcons.kt`, `PlayingRow.kt` | Samsung/Apple hybrid UI, Design studio, 30 colour + 30 FX themes, 20 title effects, bouncing logo, playing row effects |
| `FloatingPlayer.kt` | Floating player overlay over other apps (5 styles) |
| `Playlists.kt`, `PlaylistPages.kt`, `SongActions.kt` | Playlists, drag-and-drop reorder, multi-format importer (M3U/PLS/XSPF/WPL/Apple/CSV), song actions |
| `Beats.kt`, `Leveler.kt`, `Equaliser.kt`, `Crossfader.kt` | Beat detection, EBU R128 loudness + Custom LUFS + ReplayGain, 10-band EQ + per-device profiles, crossfade |
| `Stems.kt`, `StemsUi.kt` | 4-stem separation (vocals/drums/bass/other), live sliders, background Stem mode, export |
| `Lyrics.kt`, `RingtoneCutter.kt`, `TagWriter.kt` | Synced lyrics + lookup, ringtone cutter, audio tag writer |
| `SongFixer.kt`, `FileFixer.kt`, `AcoustId.kt`, `Chromaprint.kt`, `NotMusic.kt`, `NotMusicMark.java` | Fix song info (MusicBrainz + Cover Art Archive + AcoustID fingerprinting), write fixes to files, hide non-music |
| `Popularity.kt`, `PopularityScan.kt`, `PopularityUi.kt` | Popularity tab (MusicBrainz/ListenBrainz + Wikipedia chart peaks) |
| `Moods.kt`, `MoodScan.kt`, `MoodsUi.kt`, `ArtistTags.kt`, `Clap.kt`, `ClapMel.java`, `Radio.kt` | Moods tab, CLAP on-phone AI audio tagger, Radio from this song |
| `MusicExtras.kt`, `Extras311.kt`, `Extras311b.kt`, `Extras311c.kt`, `Extras311d.kt` | Star ratings, smart playlists, playback speed, Year in music, backup/restore, ListenBrainz scrobbling, widget, Android Auto, Car mode, tag/lyrics editors, Sing mode, AI DJ announcer |

## Other packages & modules
| Path | Job |
|---|---|
| `voice/` | `VoiceInputController` (mic, streaming, media pause/resume, translate), `VoiceEngine` (sherpa-onnx), `VoiceCommands`, `VoiceSpelling` (AU/UK/US), `VoiceSetupActivity` |
| `tts/` | `TextToSpeechActivity`, `KokoroTts`, `ZonosTts`, `MakeVoiceActivity`, `VoiceClone`, `ChatSpeaker`, `Pronounce` |
| `media/` | `MediaPanel`, `Media.kt`, `StickerStoreActivity`, `StickerMakerActivity`, `Sam.kt` (MobileSAM), `ImageGen.kt`, `ImageMakerActivity.kt`, `JourneyMap.kt`, `UiKit.kt`, `GifSetupActivity` |
| `convert/` | `Converter.kt`, `ConverterActivity.kt`, `DocModel.kt`, `Readers.kt`, `Writers.kt` (offline document converter) |
| `settings/` | `DesignScreen.kt`, `BackupCrypto.kt` (`.okbackup`), `AutoBackup.kt` |
| `third_party/` | `llama.cpp` patch (incl. MADLAD JNI), `sdjni/` (`stable-diffusion.cpp` + Adreno OpenCL), `zonos3/` |
| `installer-plugin/` | Standalone APK Installer plugin app |
| `plugin-starter/` | Starter kit for third-party Open Keyboard plugins |

## Models and libraries (all licence-checked)
| Thing | Licence | Notes |
|---|---|---|
| HeliBoard | GPL-3.0 | Keyboard base |
| AndroidX Media3 | Apache-2.0 | Open Waves playback & MediaSession |
| sherpa-onnx | Apache-2.0 | Voice & Kokoro TTS engine |
| Streaming Zipformer, Nemotron, Parakeet TDT 0.6B | Apache-2.0 / CC-BY-4.0 | Voice recognition |
| Whisper large-v3-turbo | MIT | Optional voice recognition |
| GTCRN / Silero VAD | MIT | Noise reduction & speech detection |
| Foxlet / Mozilla Bergamot models | MPL-2.0 | Offline translate (53 languages) |
| MADLAD-400 3B MT (GGUF q4_k_m, 1.86 GB) | Apache-2.0 | Khmer + 38 more languages |
| llama.cpp | MIT | Local LLM engine |
| Qwen2.5-1.5B-Instruct | Apache-2.0 | Writing help, smart compose, Chat, Moods prompt, DJ lines |
| Kokoro-82M v1.0 int8 | Apache-2.0 | Fast offline TTS (~155 MB) |
| ZONOS3-Mini (Zyphra) | Apache-2.0 | High-quality streaming TTS + voice cloning (~1.1 GB + 1.57 GB clone) |
| stable-diffusion.cpp | MIT | Local Image engine (CPU + Adreno OpenCL) |
| SDXS-512, Z-Image Turbo, SDXL-Lightning, DreamShaper 8, FLUX.2-klein | OpenRAIL++ / Apache-2.0 / MIT | Local Image models (downloaded on demand) |
| PaddleOCR PP-OCRv4/v5 | Apache-2.0 | Scan text (Tesseract fallback) |
| u2netp / MobileSAM | Apache-2.0 | Sticker maker cutout |
| CLAP (audio mood tagger, 78 MB) | Apache-2.0 | Open Waves Moods audio analysis |
| Demucs / stem separation model | MIT | Open Waves 4-stem splitter |
| Chromaprint / AcoustID | MIT / LGPL | Audio fingerprinting in Fix song info |
| Microsoft Fluent Emoji / Unicode CLDR / Material icons | MIT / Unicode / Apache-2.0 | Stickers, emoji dict, icons |
| Jarrod's sticker packs & chat avatars | CC0 / Jarrod's own | Stickers & Chat avatars |

**Rejected for licence:** FUTO Keyboard, HeliBoard's Google swipe lib, NLLB/Seamless/Aya (non-commercial), Neofox, ptkdev, Telegram/Giphy assets.
