# Open Keyboard (O-Board) & Open Waves — Agent Instructions

This repo is worked on by **two AIs taking turns**:
- **Claude** (typically Fri–Sun)
- **Gemini / Antigravity** (typically Mon–Thu)

GitHub is the **single source of truth** — never rely on private chat memory.

## Session Start & End Routine
1. **Start of every session:** `git pull` on the active branch, then read **`HANDOFF.md`** first.
2. **End of every session:** Commit and push all changes, update **`HANDOFF.md`** (and `docs/master-index/06-test-log.md` if a new test APK was built), and leave the working tree clean.
3. **Branch rule:** While Claude Code web shares a branch, keep the active branch named `claude/*` (e.g. `claude/project-thread-1y9nzr` until PR #12 is merged), or merge finished work to `main` before switching AIs.

## Where Documentation Lives
- `HANDOFF.md` — live relay baton (latest `testN`, what changed, what needs S24 phone testing, next priorities)
- `docs/master-index/00-start-here.md` — project overview
- `docs/master-index/01-features.md` — feature inventory & S24 test status
- `docs/master-index/02-code-map.md` — package map & licence-checked dependencies
- `docs/master-index/03-build-and-release.md` — APK build & `apk` / `stickers` branch publishing
- `docs/master-index/04-rules-and-decisions.md` — Jarrod's standing rules & design history
- `docs/master-index/05-roadmap-and-open-items.md` — open PRs, known issues, next ideas
- `docs/master-index/06-test-log.md` — test build timeline (test 1 → latest)

## Jarrod's Standing Rules (Non-Negotiable)
- **Strict open-source licence rule (GPL-3.0):** Check every dependency, model, and asset. Nothing non-commercial or closed-source.
- **Keep keyboard typing fast:** Heavy work (LLMs, image generation, TTS, music playback, stem splitting) must stay out of the main IME process (`:chat`, `:music`, Image maker own process).
- **Plain, user-friendly UX:** Gboard-like defaults where helpful; drop confusing features; explain changes in plain language.
- **Languages:** UI menus follow the **phone's** language, not the typing language. English first; Khmer matters.
- **Open Waves (Music):** Local music collection only (no streaming). Online lookups (lyrics, Fix song info, Popularity) are strictly opt-in / on-demand.
- **Test device:** Samsung Galaxy S24 (Australia).

## Test APK Numbering & Publishing
- Always check `HANDOFF.md` and `origin/apk` before picking a build number — never reuse a number.
- Build: `./gradlew assembleDebug` → publish as `OpenKeyboard-testN.apk` on branch `apk`, keeping ~15 recent APKs.
- Download URL format: `https://github.com/jrod-86/open-keyboard/raw/apk/OpenKeyboard-testN.apk`
