# Test build timeline

| Tests | Date | Highlights |
|---|---|---|
| 1–3 | 11 Nov | Fork + rebrand; in-keyboard streaming voice (sherpa-onnx). Voice confirmed on S24 in test 3 |
| 4–6 | 11 Nov | Gboard-style settings, themes, grid menu, resize; 30 s silence; noise reduction, region spelling, Whisper option |
| 20–28 | 12 Nov | Sticker store, stickers + KLIPY GIFs, Recent tab, one-handed defaults, Design studio, key sounds |
| 32–42 | 12 Nov | Design studio polish, Keys Cafe key effects, 40 presets, drag between grid and bar |
| 44–57 | 12 Nov | Swipe decoder, voice commands, smarter swipe, text shortcuts, emoji dictionary, SMS codes, offline translate, writing help, scan text, "/" commands, plugins, MCP |
| 58–77 | 13 Nov | Night test fixes: PaddleOCR scan, ABC key on number pads, paste chip, sticker order, Messenger reopen fix, MCP confirmed. Full list PASSED at test 77 |
| 78–82 | 13 Nov | Loud places mode, smart compose, learns your fingers, clipboard search/sensitive/codes, sticker maker |
| 85–89 | 13 Nov | Voice → Khmer via MADLAD (confirmed), "&#160;" fix, hold mic picker |
| 90–94 | 13–14 Nov | Grid pages of 16, nav-bar clearance, app icons as matching outlines (confirmed) |
| 95–101 | 14 Nov | My info, any tile on the bar, One UI style moving tiles (confirmed "Wow"), voice translate icon, My info cards |
| 102–108 | 14 Nov | Smooth hold-delete with speed stages + lines, media pause, Language tile, Bug report tile, mic off on send (delete stages confirmed "perfect") |
| 109–112 | 14 Nov | Clipboard catches every copy, 48 h, long paste in parts (confirmed), clipboard cards, bar keys close grid |
| 113–116 | 14 Nov | Bilingual typing row; autocorrect memory tried and dropped |
| 117 | 14 Nov | Tile names follow phone language (23 languages) |
| 118 | 14 Nov | Animated stickers sent as GIF to Messenger and other Meta apps |
| 119 | 14 Nov | Per-app settings ("This app" tile, automatic switching, Settings page) |
| 120 | 14 Nov | Custom mode: every change made in a custom app stays in that app; Normal to go back |
| 121 | 14 Nov | Fix: theme changed for a Custom app leaked into normal (old Settings screen closing cleared the custom app) |
| 122 | 14 Nov | This app panel is just Normal / Custom with an explanation; right-most top bar button stays at the right edge while suggestions show; background pictures per custom app | (crashed on open: FrameLayout params)
| 123 | 14 Nov | Fix test 122 crash |
| 124 | 14 Nov | Resize: shrinking no longer leaves an empty gap under the keys until reopened |
| 125 | 14 Nov | Right-most top bar button fixed at the far right (same spot with or without suggestions), others spread evenly to its left |
| 126 | 14 Nov | Right-hand bar button stays fixed while dragging icons onto the bar; drop onto it to replace it |
| 127 | 14 Nov | Store packs can fetch GIF copies one at a time (catalog "alt" folder). Full animated Tuxedo Cat pack (93) replaced the static Tuxedo Cat and Test packs |
| 128 | 14 Nov | Sticker send delay: GIF copies prefetched in the background when the pack opens in Messenger-type apps; tap dims the sticker |
| 129 | 14 Nov | Fancy text tile (14 styles, top-bar chip, off in other apps) |
| 130 | 14 Nov | Grid tile names no longer cut off on a shorter keyboard |
| 131 | 14 Nov | Fancy text: no top-bar chip (Jarrod found it annoying); short message when turned on/off, Normal text button in the panel |
| 132 | 14 Nov | Bug report screen: Save logs / Share logs (log.txt + crash reports zip) |
| 133 | 14 Nov | Logs section only on the Report a bug tab |
| 134 | 14 Nov | Backup & restore: optional password lock (.okbackup, AES-256 + HMAC, PBKDF2 200k); now also saves per-app settings, sticker use, translate, voice, MCP etc. (all extra settings files) |
| 135 | 14 Nov | Weekly automatic backup (Settings › Advanced): pick a file + password once, backs up when the keyboard opens if a week has passed; Back up now / Turn off |
| 136 | 14 Nov | Sticker maker tile; Tap to pick (MobileSAM, Apache 2.0, 11 MB one-time download), Erase and Restore brushes, Undo |
| 137 | 14 Nov | "+ Make" removed from the sticker panel; Sticker maker opens from its own tile |
| 138 | 14 Nov | Sticker maker: two-finger pinch to zoom and move, brush Size slider (ring shows size), finer brush when zoomed |
| 139 | 14 Nov | Image maker tile: type words, phone draws a 512px picture in 1 step (stable-diffusion.cpp MIT + SDXS-512 Q8 GGUF, OpenRAIL++, 683 MB one-time download); styles Sticker/Cartoon/Photo/Emoji/Plain; Send / Save / Make sticker |
| 140 | 14 Nov | Image maker screen redesigned: rounded prompt box, bigger style pills, blue Generate, picture card with status, outlined Send/Save/Make sticker |
| 141 | 14 Nov | Sticker maker and Image maker polished (media/UiKit.kt): own big title, no title bar hiding text, rounded outline buttons, rounded preview, blue Save/Generate |
| 142 | 14 Nov | Sticker maker tile now has a scissors icon (Material content_cut, Apache 2.0) so it differs from Stickers |
| 143 | 14 Nov | Image maker: Quick (SDXS) or Best quality (Z-Image Turbo Q3_K + Qwen3-4B Q3_K_M + FLUX ae, 5.6 GB download), 4 or 8 steps, step progress, Stop |
| 144 | 14 Nov | Image maker redesign: MODEL and STYLE dropdown cards (9 styles incl. Anime, 3D, Watercolour, Pixel art), 4/8 step switch, 🎲 idea button, purple-blue Generate, framed picture card |
| 145 | 14 Nov | Sticker maker redesign to match Image maker: purple-blue title, card buttons with emoji, gradient-framed preview, chosen tool lights up, purple slider/checkbox, gradient Save |
| 146 | 14 Nov | Image maker keeps drawing when you switch apps (progress notification, "ready" notification opens it), shows in recent apps, shows each stage with a timer, logs timings (tag ImageMaker) |
| 147 | 14 Nov | Image maker: stage list with ticks (Opening the model, Reading your words, Drawing step x of y, Finishing), "About X min left" learned from each run, progress bar by time; Bug report now includes ImageMaker timings |
| 148 | 14 Nov | Image maker progress fix: R8 was stripping the callbacks native code uses, so stages and steps never reached the screen (it sat on "Reading your words" while actually drawing) |
| 149 | 14 Nov | Image maker speed: second engine build using i8mm (newer chips like Snapdragon 8 Gen 3), Best quality now Z-Image Q4_0 (3.7 GB, old Q3_K deleted, re-download needed), Stop works part way through a step, step count shows real drawing steps (was counting model pieces: "452 of 452") |
| 150 | 14 Nov | Image maker: step label shows the step being drawn (was steps done), time-left shows after the first step even on the first run. 149 measured on S24+: about 80-90 s per Z-Image step (was 5+ min) |
| 151 | 14 Nov | Image maker on the graphics chip: OpenCL engine (Adreno kernels) for Best quality, Graphics chip / Main processor switch, falls back to main processor on failure (and after a crash), Image maker runs in its own process so a crash cannot close the keyboard, Send hands the picture over by file. Untested on device |
| 152 | 14 Nov | Fix 151 "Stopped: stack size 4107KB": engine threads get 64 MB stack (graphics chip set-up overflowed), main processor mode passes backend cpu so auto-fit cannot pick the graphics chip on its own |
| 153 | 15 Nov | Real cause of 151/152 failures: loading the engine called itself forever (StackOverflowError), fixed. Quick model gets a Stop button |
| 154 | 15 Nov | Graphics chip CONFIRMED in 153 (~30 s per Z-Image step vs 80-90 s main processor). Stop now interrupts a graphics chip step within a moment (OpenCL queue drained in small pieces) |
| 155 | 15 Nov | Graphics chip Z-Image CONFIRMED 2 min 45 s (4 steps). Image maker: 🌿 Smooth (default: low priority, graphics chip rests between small chunks) / 🚀 Fastest (warning pop-up every time: ~90% of phone power, do not use other apps). 🪄 prompt enhancer in the prompt box using the Writing help model (Qwen2.5 1.5B), tap again to undo |
| 156 | 15 Nov | Image maker: safety checks (main keyword filter + on-phone picture check, extra 88 MB download). Three speeds with info pop-ups: 🌿 Smooth (~40% graphics chip), ⚖️ Balanced (old Smooth, 3:32), 🚀 Fastest. 🪄 enhancer writes 120-180 word descriptions for Best, short ones for Quick. Tap picture for full screen (pinch/double-tap zoom). ✕ clears the prompt |
| 157 | 15 Nov | Image maker keeps drawing when you leave the screen (foreground service; 156 was cleared by Android at step 3). ⚖️ Balanced back to exact test 155 pacing (3:32). 🪄 Best prompts 70-100 words, concise. Download pop-up shows only the missing size |
| 158 | 15 Nov | Image maker: keyboard stays hidden on open, shows when the prompt box is tapped, hides on Generate. Balanced CONFIRMED 4:05 with a 🪄 prompt (157) |
| 159 | 15 Nov | Image maker: screen scrolls with a big full-width square preview; while drawing, spinning rainbow glow around the box and colour clouds, a light ring and sparkles inside. Smooth CONFIRMED ~7 min 13 s (158) |
| 160 | 15 Nov | Image maker: 🎲 ideas from mix-and-match parts, last 200 never repeat. 🪄 wand waves, status sparkles, text types itself in. Prompt box scrolls (8 lines, scroll bar). Word reader Qwen3-4B Q3_K_M -> Q4_0 (2.4 GB one-off download) for faster reading. Fastest CONFIRMED 3:54 with 🪄 prompt (159) |
| 161 | 15 Nov | Image maker renamed "Local Image". Gallery under the picture (3 across, tap to reopen, hold for full screen/reuse words/delete); ⚙️ keep latest only/25/50/100/250/no limit. 🎨 20 themes + phone default. ℹ️ help guide. Fixes 160 crash on open (prompt box scroll bar) |
| 162 | 15 Nov | Local Image: 🌙 Queue (✨ Automatic from a theme + count, ✍️ Manual), runs one after another with wake lock, screen off OK. ⬛ Shape: 9 presets + custom (~512x512 pixels, sides x64). 43 styles, popular first; Photo -> Photorealistic; 🏷️ Logo. Help updated. Roadmap: cloud API idea (discuss first) |
| 163 | 15 Nov | Local Image: 🎨 themes as 3-across preview tiles (mini mock-up of the screen). Tap a gallery picture: full screen with 📤 Export (share sheet), 💾 Save, ✏️ Reuse, 🗑️ Delete; main picture full screen gets Export/Save |
| 164 | 2026-11-15 | Local Image: 3 test models in MODEL dropdown (🧪 FLUX.2-klein 4B 5 GB, DreamShaper 8 LCM 1.9 GB, SDXL-Lightning 2.7 GB at 768 px); graphics chip option for all but Quick | waiting |
| 165 | 2026-11-15 | Local Image: 🎲/📌 SEED dropdown (random, keep last, type one); 🪄 enhancer + queue use each model's own prompt style (klein prose 50-70 words, DreamShaper comma tags, SDXL sentence + tags) | waiting |
| 166 | 2026-11-15 | Local Image: SEED always shows 🎲 Random / 📌 Fixed / ✏️ Type. Jarrod: FLUX.2-klein ~4 min, Z-Image ~4 min now | waiting |
| 167 | 2026-11-15 | Local Image: SDXL-Lightning out-of-memory fix (decode in 512 px tiles for big pictures); MODEL list ranked best to worst with Jarrod's ratings (Z-Image 8.5, DreamShaper 7, FLUX.2-klein 6, SDXL unrated, SDXS) | waiting |
| 168 | 2026-11-15 | Text to speech tile (Kokoro-82M v1.0 int8, Apache 2.0, via sherpa-onnx; ~155 MB one-time download): voices Heart, Bella, Emma (British) · Fenrir, Michael, Puck; tap a voice to hear it; speed Slower/Normal/Faster; brings along what you typed; speaks while it works on the rest; Play again / Share (WAV) / Save (Music › Open Keyboard). Numbers, money, times, dates, phone numbers and unknown names made readable | waiting |
| 169 | 2026-11-15 | Local Image: SDXL-Lightning confirmed working after 167 fix (5:42, 7.5/10); MODEL list re-ranked: Z-Image 8.5, SDXL-Lightning 7.5, DreamShaper 7, FLUX.2-klein 6, SDXS | waiting |
| 170 | 2026-11-15 | Local Image: progress map (JourneyMap.kt): winding track top-left to 🏁, stop per stage + each drawing step with labels; random animal walks it (pops from 📦, reads 📖, paints easel, frames 🖼️); stage list under card removed | waiting |
| 171 | 2026-11-15 | Text to speech: crash on Speak fixed (168: R8 rewrote the sherpa-onnx speech callback; now a kept SpeechChunks class). Download + voice preview worked in 168 | waiting |
| 172 | 2026-11-15 | Local Image map: Finishing stage now recognised (engine says vae_decode); animal dashes to 🏁 and celebrates before the picture shows. Includes TTS crash fix (171) | waiting |
| 173 | 2026-11-15 | Local Image map: track loops past Finishing onto a bottom straight, through a chequered FINISH gate; animal jumps holding 🏆 with falling confetti (~2.5 s) before the picture shows | waiting |
| 174 | 2026-11-15 | Local Image gallery: full-screen viewer swipes left/right through all pictures (counter 3 / 15); Export/Save/Reuse/Delete act on the one shown; after Delete shows the next | waiting |
| 175 | 2026-11-15 | Local Image gallery: hold a picture to start selecting, tap to add/remove (ticks), bar with N selected / ☑️ All / 🗑️ Delete (confirms) / ✕ | waiting |
| 176 | 2026-11-15 | TTS tile (renamed from "Text to speech"): ✨ Zonos engine (ZONOS3-Mini by Zyphra, Apache 2.0, ~1.1 GB download, resumable) beside ⚡ Kokoro. 10 English voices: Savannah, Imogen (British), Willow (whisper), Sakura (anime), Morgana (witch), Wyatt, Rupert (British), Cillian (Irish), Jett (energetic), Thaddeus (announcer). Streams as it speaks, 44 kHz. Needs i8mm chip (S22+). Kokoro crash fix confirmed working by Jarrod (172) | waiting |
| 177 | 2026-11-15 | TTS › Zonos › YOUR VOICES › ➕ Make a voice: record (read the words shown) or pick a sound/video file, 10-20 s, name it, tick permission; first time downloads 1.57 GB of voice-copying files; learning takes ~1-2 min; new voice is picked and says hello. Long-press your voice to rename/delete | CONFIRMED working (Jarrod: "seemed close" to his voice) |
| 178 | 2026-11-15 | TTS: no more crash ("Unable to retrieve AudioTrack pointer") when tapping another voice or Stop while a voice is still playing. Includes Make a voice (177) | CONFIRMED working (Jarrod) |
| 179 | 2026-11-15 | Make a voice: playing back your clip, the Play button becomes ⏸ Pause / ▶ Resume, with ⏹ Stop next to it; goes back to ▶ Play at the end | waiting |
| 180 | 2026-11-15 | Local Image: Jarrod's logo picture and the name "Local Image" (in the logo's purple → pink → orange) at the top left of the screen, replacing ✨; logo is also the screen's icon in recent apps. | Waiting |
| 181–186 | 2026-11-15–16 | Keyboard lag audit (free idle voice models, lag log), Silero VAD + 60/40 GTCRN denoiser, Nemotron live + Parakeet clean-up setup, AI writer from spoken/typed request | |
| 187–197 | 2026-11-16 | Writer mode (lit ✨ + glowing bar), Writing help styles (Shorter/Longer/Friendlier/Professional/Funnier/More loving/Casual/Teasing/Rhyme), 50 Fancy text styles, themes in Sticker maker/TTS/Bug report, Emoji tile removed | |
| 198–211 | 2026-11-16 | Offline Chat buddy in own `:chat` process, 15 personalities, Jarrod's 10 custom lip-synced avatars + particles, Kokoro/Zonos voice replies, opt-in Hands-free mode | |
| 212–216 | 2026-11-16 | Voice typing: pause Smart compose during mic, preload live model on keyboard open, smarter media resume after mic | |
| 217–228 | 2026-11-19–20 | Keyboard speed audit, app drawer plugin switches, speed meter, `WriterService` in `:chat`, per-voice-command switches, shared read-only `ModelProvider`, Plugin store (`plugin-store.json`), local MCP server, APK Installer plugin (`installer-plugin/`) | |
| 229–230 | 2026-11-20 | Open Waves (Music plugin, `:music` process, Media3, Apple-style Now Playing, lyrics, Up Next, sleep timer); hold app drawer tile ~1.5 s for Add to home screen | |
| 233 | 2026-11-20 | Converter plugin: offline document conversion (Word, ODT, RTF, text, Markdown, HTML, eBook, Excel, CSV, PowerPoint, PDF, images) | |
| 234–237 | 2026-11-20 | Open Waves: beat effects, Fix song info (MusicBrainz + Cover Art Archive), playlists & multi-format import (M3U/Apple/WMP/Spotify CSV), EBU R128 loudness normalisation + Customise sizes, 440/432/444 Hz tuning | |
| 238–246 | 2026-11-20 | Open Waves Samsung Music redesign, ringtone cutter, 30 colour + 30 FX themes, bouncing logo equaliser, badge-free home icon, A–Z bar, solid themed menus | |
| 247–260 | 2026-11-20 | Fix song info overhaul: AcoustID audio fingerprinting (off main thread), Artwork-only mode, pick own artwork, hide non-music, permanent tag write-back, artist album search | |
| 261–276 | 2026-11-20 | Big mini player with scrolling title & artist effects, Design studio with live beat previews, Fix song info 5-song look-ahead, play preview, copy/Google Images buttons | |
| 277–288 | 2026-11-20 | Floating player over other apps (5 styles), tab scroll memory, deduplicated Tracks & real Albums only, squircle 1×1 home widget, CapCut-style title effects | |
| 289–294 | 2026-11-20–21 | Playlist & in-playlist drag-to-reorder rebuilt with floating copy, drop line & smooth auto-scroll; Move to top/bottom | |
| 295–305 | 2026-11-21 | Sort by popularity + Popular tab (MusicBrainz/ListenBrainz + Wikipedia chart peaks, chart peaks on Now Playing); Moods tab + CLAP on-phone AI audio listener (78 MB, Apache-2.0) + crossfade ("Blend songs together") | |
| 306–310 | 2026-11-21 | Per-playlist Sort by bar, "Not music, hide it" with file mark, 20 title effects every 30 s, faster parallel Moods scan (wake lock, saves every 10 s) | |
| 311 | 2026-11-21 | Big feature drop (parts 1–5): 10-band EQ + per-device profiles, Radio from this song, star ratings, smart playlists, playback speed, resume long tracks, play on connect, Year in music, backup/restore, ListenBrainz scrobbling, home player widget, Android Auto, Car mode, tag & lyrics editors, Sing mode, AI radio announcer | Waiting |
| 312 | 2026-11-21 | Back to Now Playing, EQ styles + back to normal, big settings headings, speed slider, mood mix screen, theme brightness | Waiting |
| 313–314 | 2026-11-21 | 4-stem separation (vocals, drums, bass, other) with live sliders, quick picks, export, and background Stem mode for every song | Waiting |
| 315 | 2026-11-21 | Stem split progress on Now Playing, 5 loudness levels, hide Popular/Moods tabs, shared measuring, Zonos announcer, theme colour controls | Waiting |
| 316 | 2026-11-21 | Custom volume level (−24 to −8 LUFS), hide any tab + per-tab settings, Stem mode battery warning, Measure all progress/fix, DJ announcer timing rewrite (writes 25 s+ early, ducks 9 s before end, unloads Zonos after each line) | Waiting |
| 317 | 2026-11-23 | Open Waves: DJ fade-out/fade-in ducking + Savannah only + top-bar DJ button + immediate prep/skip-ahead support, 30 fps BeatClock/WavesLogo cool-down, Car mode symmetric buttons, Stem mode top-bar badge + warning Cancel fix, Moods 15/30/60/All fix, hold Prev/Next to scrub, wavy animated seek line (Now Playing + Mini Player), Now Playing marquee title + artist FX, Popular Top 20s & Top 20–50, Discover tab + Support artist links, last-tab hide guard fix | Waiting |
| 318 | 2026-11-23 | Open Waves: borderless top-bar DJ & Stem icons, Design studio at top of Settings, Mini Player hold Prev/Next to scrub, Popular "Before 1970s" filter (replaces 50s/60s), removed Discover tab (kept Support artist) | Waiting |
| 319 | 2026-11-23 | Open Waves: DJ announcer 10% ducking + slightly clearer Savannah + built-in Download buttons for Savannah voice & AI writing model, Stem top-bar `STEM` Accent highlight (matching DJ), removed "opens here" label in Settings > Tabs | Waiting |
| 320 | 2026-11-24 | Crash fixes from `crash_reports_(7).zip`: fixed Android 15+ `dataSync` 6-hour foreground service crash in `MoodScanService` and `PopScanService` (only call `startForeground` once, use `notify` for updates, handle `onTimeout` cleanly); fixed native `SIGABRT` (`pthread_mutex_lock on destroyed mutex`) in `Clap.kt` by synchronising ONNX `OrtSession` open/run/close | Waiting |
| 321 | 2026-11-24 | Fix player lag & `:music` OOM crash from `OpenKeyboard_logs_2026-11-24_07-34.zip`: strictly single-flight `makingJob` in `Announcer` (cancels stale prep on skip instead of piling up concurrent Qwen+Zonos runs), pause DJ prep while Stems is actively splitting, default `AI writes what the DJ says` to off (instant built-in DJ lines, zero extra RAM; opt-in toggle remains) | Waiting |
