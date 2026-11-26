# Features

Updated 23 November 2026 (test 316).
Status key: ✅ confirmed on Jarrod's S24 · 🟡 built, not yet confirmed · ❌ dropped

## Typing
| Feature | What it does | Status |
|---|---|---|
| Keyboard base | HeliBoard layouts, dictionaries, autocorrect, 80+ languages | ✅ |
| Swipe typing | Our own from-scratch decoder (HeliBoard's swipe needs a closed Google library); learns personal words and uses the previous word (tests 45, 47) | ✅ |
| Learns your fingers | Shifts each key's touch area to where you actually tap (test 80, plugin "fingers") | 🟡 |
| Smart compose | Offline AI guesses the next few words as a top-bar chip (test 79); runs via Chat's `WriterService` process (test 221) | ✅ |
| Text shortcuts | "omw" + space → "On my way!", delete straight after undoes (test 48) | ✅ |
| My info | Tile with saved details as small cards (3 across, swipe pages); offers the right one in email/phone/address/name boxes; completes a saved item after 3 typed letters (tests 95, 99, 101) | 🟡 |
| "/" commands | e.g. `/askwikiquestion`, `= sum` calculator (test 54) | ✅ |
| Bilingual typing | Language tile row "Type in two languages at once" (HeliBoard secondary dictionaries, same alphabet only) (test 113) | 🟡 |
| Language tile | Switch keyboard language with one tap, + Add language (tests 104, 114) | 🟡 |
| Hold delete | Always starts slow; letters (0–2.5 s), words (2.5–4 s), fast words (4–8 s), whole lines (8 s+); no "fighting back" (tests 102–108, 222) | ✅ |
| Fancy text | 50 Unicode styles, most popular first; Normal text button in panel (tests 129, 131, 194) | 🟡 |
| Speed audit & meter | Heavy work kept off typing, speed meter, low-memory freeing, lag log (tests 181, 217, 219) | 🟡 |
| Autocorrect memory | Remember words you put back | ❌ dropped test 116 (too confusing) |

## Voice
| Feature | What it does | Status |
|---|---|---|
| Voice typing | Tap mic once, words stream in live (Nemotron live + Parakeet clean-up, or Whisper); preloads live model on keyboard open if used in last week; 30 s silence stop; fully offline (tests 3, 184, 186, 212–214) | ✅ |
| Voice commands | "delete that" (with variants), "send message" (never presses Done), "new line", "clear all", "stop listening"; individual on/off switches in Settings → Plugins (tests 46, 220, 222, 227) | ✅ |
| Loud places mode / noise reduction | Phone call mic processing, GTCRN denoiser (60/40 blend), Silero VAD sentence cutting, "keep my last recording" for tuning (tests 6, 78, 183) | 🟡 truck test pending |
| Voice → another language | Speak English, it types Khmer (or 38 other languages) via MADLAD-400; hold the mic to pick/turn off (tests 85–88) | ✅ |
| Pause media while talking | Pauses YouTube/music while mic is on, watches media after and only presses play if it isn't playing (tests 103, 184, 215–216) | 🟡 |
| Mic off on send | Mic stops when you press send/key; first sentence into empty box no longer turns mic off (tests 107, 213) | 🟡 |

## Tiles, top bar & plugins
| Feature | What it does | Status |
|---|---|---|
| Tile grid & move tiles | Pages of 16, swipe between pages; One UI style drag-and-drop; hold an app tile ~1.5 s for **Add to home screen** (tests 90–100, 230) | ✅ |
| Top bar | Grid button fixed + 6 movable spots; right-most button stays fixed at right edge even with suggestions (tests 96–100, 125–126) | ✅ |
| App tiles | Other apps' text actions as matching one-colour icons; on/off switches for built-in app drawer tools (tests 56, 93–94, 218) | ✅ |
| Tile names translated | Follow the phone's language, 23 languages incl. Khmer (machine-translated, need native check) (test 117) | 🟡 |
| This app: Normal / Custom | Per-app settings (size, design/colours, background picture, language, autocorrect, sounds) stay in that app only (tests 119–123) | 🟡 |
| Bug report tile | Report a bug / Request a feature → pre-filled GitHub issue + Save/Share logs zip (tests 106, 132–133, 191–192) | 🟡 |
| Plugin store & APK Installer | In-settings plugin store (`plugin-store.json` on GitHub), shared read-only `ModelProvider`, trust badges, plugin starter kit, external `installer-plugin/` APK installer; new external plugins start off (tests 219, 223–225, 227–228) | 🟡 |
| Local MCP server | Keyboard exposes localhost MCP server (off by default, API key) for AI apps on the phone (`write`, `rewrite`, `list_models`) (test 226) | 🟡 |

## Clipboard
| Feature | What it does | Status |
|---|---|---|
| History, pins, search, cards | 3-across cards, swipe up to delete, 48 h rolling keep, catches every copy on open, smart paste chip, password-manager copies excluded, OTP codes vanish after 5 min (tests 68–72, 81, 109–112, 219) | ✅ |
| Long paste | Pages of text sent in pieces; learns app limit and offers "Paste part 2 of N" (test 110) | ✅ |

## Stickers, GIFs, Local Image & TTS
| Feature | What it does | Status |
|---|---|---|
| Sticker panel & store | Live store from `stickers` branch (Tuxedo Cat animated 93, Cheeky Dog v2 001–055, Yellow Emoji Story); GIF alt copies prefetched for Meta apps (tests 20–42, 118, 127–128) | ✅ |
| Sticker maker | Tap-to-pick subject (MobileSAM), Erase/Restore brushes, pinch zoom, outline, caption, themes, own task (tests 82, 136–138, 145, 191–192) | 🟡 |
| Local Image | Offline image generation via `stable-diffusion.cpp` (Adreno OpenCL GPU ~30 s/step or CPU) in own process; models: Z-Image Turbo (8.5), SDXL-Lightning (7.5), DreamShaper 8 (7), FLUX.2-klein (6), SDXS; 43 styles, 9 shapes, Smooth/Balanced/Fastest, 🪄 prompt enhancer, overnight Queue, JourneyMap progress, gallery multi-select, 20 themes (tests 139–180) | ✅ |
| TTS & Make a voice | Offline TTS tile: Kokoro-82M (fast) + Zonos3-Mini (44 kHz streaming, 10 voices) + **Make a voice** 10–20 s voice cloning with Pause/Resume/Stop (tests 168, 171, 176–179) | ✅ |
| GIFs & Emoji | KLIPY GIF search (opt-in free key); built-in CLDR emoji dictionary (Emoji tile removed test 191) | ✅ |

## Offline AI tools
| Feature | What it does | Status |
|---|---|---|
| Translate panel | Mozilla/Foxlet models (53 languages) + MADLAD-400 (39 more incl. Khmer), both ways | ✅ |
| Writing help & Writer mode | Qwen2.5-1.5B via `WriterService`; Shorter, Longer, Friendlier, Professional, Funnier, More loving, Casual, Teasing, Rhyme, Add emojis; Writer mode (lit ✨ + glowing bar) writes new messages from spoken/typed prompt (tests 185–197, 221) | ✅ |
| Chat buddy | Offline chat in own `:chat` process with Jarrod's 10 custom avatars (lip-synced jaw/robot mouth + particles), 15 personalities, saved chats sidebar, Kokoro/Zonos voice replies, opt-in Hands-free mode (tests 198–211) | 🟡 |
| Scan text | Camera OCR with PaddleOCR, Samsung-style text select (tests 64–67) | ✅ |
| Converter plugin | Offline document converter on the phone: Word, OpenDocument, RTF, text, Markdown, HTML, eBook, Excel, CSV, PowerPoint, PDF, images (test 233) | 🟡 |

## Open Waves (Local Music Player — tests 229–316, PR #12)
Runs in its own `:music` process so playback never slows typing. Local collection only (no streaming).

| Feature | What it does | Status |
|---|---|---|
| Core playback & Library | Media3 player, tabs (Popular, Moods, Favourites, Playlists, Tracks, Albums, Artists — any tab can be hidden + per-tab settings), deduplicated Tracks, real Albums only, "Not music, hide it", search, Up Next, shuffle, repeat, sleep timer, resume last spot, play on Bluetooth connect (tests 229, 255, 282–284, 300, 307, 311, 316) | 🟡 |
| Now Playing & Design studio | Apple/Samsung hybrid Now Playing, animated cover & 20 CapCut-style title effects, beat effects (cover + background), 30 colour + 30 FX themes with brightness/colour controls, customisable cover & play button sizes, floating player over other apps (5 styles), squircle badge-free 1×1 home icon & player widget (tests 229, 234, 236, 238–246, 261–265, 277, 280–281, 286–288, 308–309, 312, 315) | 🟡 |
| Lyrics, Sing mode & Editors | Scrolling synced lyrics (tap to seek, on-demand online lookup), Sing mode, in-app lyrics editor, tag editor, ringtone cutter (tests 229, 239, 308–309, 311) | 🟡 |
| Playlists & Import | Make, rename, drag-to-reorder (floating copy + auto-scroll), Move to top/bottom, per-playlist Sort by bar, Smart playlists, star ratings; import from Android MediaStore, M3U/M3U8, PLS, XSPF, WPL/ZPL, Apple Music/iTunes text & XML, Spotify CSV (tests 235, 243, 279, 289–294, 306, 311) | 🟡 |
| Fix song info | MusicBrainz + Cover Art Archive + AcoustID audio fingerprinting (Chromaprint), artwork-only mode, play preview, 5-song look-ahead, permanent tag write-back (with prompt) (tests 234, 247–260, 267–276) | 🟡 |
| Loudness, Tuning, EQ & Crossfade | EBU R128 loudness normalisation (Song or Album, ReplayGain tags, Quiet/Normal/Loud/Louder/Max + **Custom −24 to −8 LUFS** slider, Measure all), 440/432/444 Hz pitch-only tuning, 10-band EQ with presets & per-device profiles, playback speed slider, crossfade ("Blend songs together") (tests 236–237, 299, 311–312, 315–316) | 🟡 |
| Popular & Moods tabs | **Popular:** MusicBrainz/ListenBrainz play counts + Wikipedia chart peaks, filters, hidden gems, chart peaks on Now Playing. **Moods:** on-phone audio feel + MusicBrainz genres + **CLAP** on-phone AI listener (78 MB, Apache-2.0), mood tiles, "How are you feeling?", calm-hype slider, Radio from this song (tests 295–305, 310–312) | 🟡 |
| Stems (Vocals/Drums/Bass/Other) | Live 4-stem sliders, quick picks, export, and background Stem mode (splits ahead of play, progress on Now Playing, battery warning) (tests 313–316) | 🟡 |
| AI DJ Announcer | Introduces next song using Qwen writing model + Zonos TTS (Savannah); writes line 25 s+ early, ducks music 9 s before end, unloads Zonos after each line (tests 311, 315–316) | 🟡 |
| Car, Auto, Stats & Backup | Android Auto library, Car mode, Year in music, ListenBrainz scrobbling, Open Waves backup/restore (test 311) | 🟡 |

## Look, feel & Backup
- Design studio (40 presets, custom & per-part colours, "My designs"), key sounds, Keys Cafe key effects, one-handed, floating, split, resize. ✅
- Password-locked keyboard backup (`.okbackup`, AES-256 + HMAC) and weekly automatic backup (tests 134–135). 🟡
