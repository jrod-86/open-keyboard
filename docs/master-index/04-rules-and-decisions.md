# Rules and decisions

Updated 23 November 2026.

## Jarrod's standing rules
- **Licence rule:** Only use code, models and assets allowed in an open source (GPL-3.0) project. Check every dependency. Nothing "pinched".
- **Keep typing fast:** Never put heavy work (AI models, image gen, music playback, stem splitting) in the keyboard's main process. Heavy tools run in their own process (`:chat`, `:music`, Image maker).
- **Easy for the average user:** Gboard-like behaviour where it makes sense; features that confuse people get dropped (e.g. autocorrect memory). Plain, friendly wording in UI and test notes.
- **Top bar rule (14 Nov):** The right-most bar button always stays visible at the right edge; suggestions sit in the middle.
- **Languages:** Keyboard menus follow the **phone's** language, not the typing language. English first; Khmer matters (Jarrod's wife).
- **Emoji vs stickers:** Emoji stay separate from stickers.
- **Voice:** One tap inside the keyboard, text streams live, no separate screen, 30 s of silence before stopping.
- **Open Waves (Music):** Local music collection only (owned files on the phone, no streaming). Online lookups (lyrics, Fix song info, Popularity) are strictly opt-in / on-demand.
- **Permissions:** Running other people's repos/builds needs Jarrod's OK.

## Claude + Gemini relay workflow (23 Nov)
- Jarrod uses **Claude (Fri–Sun)** and **Gemini / Antigravity (Mon–Thu)**, one at a time.
- **GitHub is the single source of truth** (never rely on private chat memory).
- Both AIs read `AGENTS.md` (via `CLAUDE.md` for Claude) and `HANDOFF.md` at the start of a session, and commit + push + update `HANDOFF.md` before finishing.
- Keep active branches named `claude/*` while Claude Code web is sharing the branch (Claude web can only push to `claude/*` branches), or merge to `main` before switching.

## Key decisions (date)
- **11 Nov:** Fork HeliBoard; offline voice with sherpa-onnx (Parakeet default, Whisper selectable); Android only; GPL-3.0.
- **12 Nov:** Own swipe decoder; stickers + opt-in GIFs (KLIPY); "everything's a plugin"; business model = free keyboard with free packs, paid packs in a separate web shop (Stripe/Lemon Squeezy), Play build uses Google billing. Dropped handwriting.
- **13 Nov:** Khmer via MADLAD-400 (M2M100 was poor; NLLB etc. not allowed by licence). Smart compose first in Phase 2.
- **14 Nov:** Top bar = grid + 6 movable; tiles in pages of 16; clipboard keeps 48 h; long paste in parts; autocorrect memory dropped; tiles translated; Bug report tile replaces "AI changes the keyboard"; animated stickers send WebP + GIF alt for Meta apps.
- **15 Nov:** Local Image uses Adreno OpenCL GPU (~30 s/step on S24) in own process; TTS adds Zonos3-Mini + Make a voice cloning.
- **16–19 Nov:** Chat runs in own `:chat` process; `WriterService` shares Qwen2.5-1.5B across Smart compose, Writing help, and AI writer; Plugin store + APK Installer plugin + local MCP server.
- **20–21 Nov:** Open Waves built in own `:music` process (local music only, Samsung Music + Apple Music hybrid, EBU R128 loudness, 440/432/444 Hz tuning, Moods with CLAP, 4-stem splitting, Zonos AI DJ announcer).
