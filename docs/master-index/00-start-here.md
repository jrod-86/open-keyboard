# Open Keyboard (O-Board) — start here

Updated 23 November 2026 (test 316). This is the record of everything built for Open Keyboard and its bundled tools (including **Open Waves**), in one place inside the Git repo so both Claude and Gemini share the exact same source of truth.

Sticker *animation* production has its own local index outside the repo: `C:\AI\tools\open-keyboard\00-master-index\sticker-gif-builder-master-index\00-start-here.md`.

## What it is

An open source Android keyboard aiming for Gboard-level features and beyond — plus offline companion tools (Local Image, TTS, Chat, Converter, and the **Open Waves** local music player) — built by Jarrod with Claude and Gemini.
- Base: a fork of **HeliBoard** (GPL-3.0). Whole project is **GPL-3.0**.
- Test phone: Samsung Galaxy S24 (Australia). Android only. English first, Khmer matters (Jarrod's wife).
- Working name **Open Keyboard**. Rename to "OpenBoard / OBoard" is on hold: OpenBoard is already an existing keyboard (HeliBoard's ancestor) and a whiteboard app.

## Where things live

| What | Where |
|---|---|
| Source code | https://github.com/jrod-86/open-keyboard (`main` + active branch `claude/project-thread-1y9nzr` / [PR #12](https://github.com/jrod-86/open-keyboard/pull/12)) |
| Test APKs | branch `apk`, e.g. `https://github.com/jrod-86/open-keyboard/raw/apk/OpenKeyboard-test316.apk` (latest ~15 kept) |
| Plugin store catalog | branch `apk` → `plugin-store.json` |
| Sticker store packs | branch `stickers` (`catalog.json` + pack zips + icons), read live by the app |
| Animated sticker production | `C:\AI\tools\open-keyboard\01-stickers` (delivery), `08-research` (research) |
| This index | `docs/master-index/` (relocated into Git from `00-master-index/o-board-master-index`) |
| AI handoff & rules | Root `AGENTS.md`, `CLAUDE.md`, and `HANDOFF.md` |

## Files in this index

1. [01-features.md](01-features.md) — every feature, what it does, and its test status
2. [02-code-map.md](02-code-map.md) — which file does what, models and libraries with licences
3. [03-build-and-release.md](03-build-and-release.md) — how test builds, plugins, and sticker packs are made and published
4. [04-rules-and-decisions.md](04-rules-and-decisions.md) — Jarrod's standing rules and the decisions behind the design
5. [05-roadmap-and-open-items.md](05-roadmap-and-open-items.md) — what's next, open PRs, what's waiting on S24 testing
6. [06-test-log.md](06-test-log.md) — timeline of test builds 1–316

## Current state (23 Nov 2026, test 316)

- **Keyboard & core plugins:** Full Gboard-level feature set plus offline voice (Nemotron + Parakeet / Whisper), MADLAD translate (incl. voice → Khmer), Writing help + Writer mode, Local Image (Z-Image / SDXL-Lightning / DreamShaper / FLUX.2-klein / SDXS on Adreno OpenCL), TTS (Kokoro + Zonos + Make a voice cloning), offline Chat buddy (10 custom avatars, hands-free, own process), Converter plugin, Plugin store + APK Installer plugin, and local MCP server.
- **Open Waves (Music player, tests 229–316):** Full local-collection music player in its own `:music` process — Library tabs (Popular, Moods, Favourites, Playlists, Tracks, Albums, Artists), Apple/Samsung hybrid Now Playing, live lyrics + Sing mode + lyrics/tag editors, beat effects, Design studio (30 colour + 30 FX themes), floating player, home screen widget, Android Auto + Car mode, playlist import (M3U/Apple/WMP/Spotify CSV), EBU R128 loudness normalisation with Custom LUFS slider, 440/432/444 Hz tuning, 10-band EQ with per-device profiles, AI Moods (CLAP), real-time 4-stem separation + background stem mode, and Zonos AI DJ announcer.
- **Pending S24 verification:** Open Waves tests 311–316 (see `HANDOFF.md` for the checklist) and merging [PR #12](https://github.com/jrod-86/open-keyboard/pull/12) + older open PRs into `main`.
