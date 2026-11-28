# Roadmap and open items (23 Nov 2026)

## Waiting on Jarrod (S24 phone checks)
- **Confirmed working (28 Nov):** O-Board + Open Hub rebrand, Home player widget, Metadata/artwork + Edit tags, Backup/Restore, Smart playlists (+ Star ratings removed), Playback speed slider, DJ Announcer (Heart Kokoro · Low battery, 65/35 dual-deck crossfade, scripts), Stem mode playback, Radio/Podcasts/Audiobooks + offline resume, random pausing fixed, voice typing in loud places fixed, old PRs cleaned up.
- **Open Waves remaining optional checks:** Lyrics editor, Sing mode, Year in music. *(Android Auto skipped for now.)*
- **Native-speaker check** of Khmer tile names.
- **Full animated sticker packs** (96 yellow emojis, etc.) from PC pipeline → convert & publish to `stickers` branch.

## Open PRs to review / merge / clean up
`claude/project-thread-1y9nzr` (**PR #12**) already includes the keyboard work up through test 233 plus all Open Waves work (tests 229–316). Once Open Waves test 316+ is verified on S24:
- **PR #12** (`claude/project-thread-1y9nzr`) — Open Waves + tests 235–316 (primary active branch).
- Older open PRs (#1, #2, #4, #5, #6, #7, #8, #9) — verify which commits are already merged into PR #12 and close any superseded PRs after PR #12 lands on `main`.

## Next ideas / Phase 2+
- Translate deeper screens (Voice translate, Bug report, Open Waves settings).
- Optional: type Khmer in English letters → Khmer script (transliteration).
- Paid sticker shop (web shop + store button) when Jarrod is at his PC.
- Open Waves polish & bug fixes from S24 testing of test 316.
- Optional: move Open Waves (`music/`) to its own standalone repo later if desired.

## Known issues
- Smart compose guesses can occasionally be odd (can be toggled off in Settings → Plugins).
- Love-letter animated sticker has a green heart flash (~1.3 s) from the green-screen step.
- Khmer speech recognition not available (no good permissive offline model yet).
- DJ announcer with Zonos + AI-written lines can still warm the phone on long sessions (test 316 moved line generation earlier and unloads Zonos after each line; "AI-written lines" can be turned off in announcer settings).

## Dropped
- Handwriting (12 Nov), autocorrect memory (14 Nov), "AI chatbot changes any feature" (14 Nov, replaced by Bug report tile), Emoji tile (16 Nov, test 191), Chat Talk mode (16 Nov, test 204, replaced by opt-in Hands-free), Google mic button on Moods (21 Nov, test 304).
