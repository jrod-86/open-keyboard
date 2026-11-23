# Session Handoff (Relay Baton)

- **Last updated:** 2026-11-23 by Gemini (Antigravity)
- **Active branch:** `claude/project-thread-1y9nzr` ([PR #12](https://github.com/jrod-86/open-keyboard/pull/12))
- **Latest published APK:** `OpenKeyboard-test316.apk` on branch `apk` (next build number: **test 317**)
- **Note on local PC build env:** Jarrod's Windows PC does not currently have Java / Android SDK installed, so APK builds run in GitHub Actions / Claude's build environment (or once JDK + Android SDK are installed locally).

## Just Finished (23 Nov)
1. Relocated Jarrod's local `o-board-master-index` into Git at `docs/master-index/` and updated all 7 index files from test 118/180 right up to **test 316**.
2. Added `AGENTS.md`, `CLAUDE.md`, and this `HANDOFF.md` for seamless Claude ↔ Gemini handoffs.
3. Audited Open Waves test 316 code and fixed **3 bugs** (ready to ship in **test 317**):
   - **`WavesUi.kt`:** Fixed "hide last tab" guard counting `"Popular"` as visible even when Popularity features are turned off (`WavesTabs.count { it != tab }`).
   - **`Extras311d.kt` (`Announcer`):** Reset `failedFor` and cached `ready` line whenever announcer settings change (and reset `songsSince` when re-enabling), plus serialised `ZonosTts.speak`/`stop` with a `Mutex` so background prep and "Hear a sample" never race.
   - **`StemsUi.kt`:** Fixed "Split into stems" starting the heavy AI split *before* the battery warning dialog was confirmed (tapping Cancel now actually cancels it).

## Needs S24 Phone Testing (Open Waves tests 311–316)
- [ ] **Custom volume level (test 316):** Settings → Volume → 6th box **Custom** (−24 to −8 LUFS slider + `−`/`+` half-steps).
- [ ] **Measure all (test 316):** Shows `"X of Y songs measured"`, runs loudness-only scan, tells you when all songs are done.
- [ ] **Tabs & per-tab settings (test 316):** Settings → Tabs hide/show any tab (last visible tab stays locked), long-press tab name or tap ⚙ at end of tab bar, set "Open the app on this tab", toggle A–Z bar / Most played / Moods options.
- [ ] **Stem mode (tests 313–316):** Battery warning pops up before splitting/enabling (with "Don't warn me again"); Cancel does not start splitting; live Vocals/Drums/Bass/Other sliders, quick picks, Back to normal, Export, progress bar on Now Playing.
- [ ] **DJ announcer (tests 311, 315–316):** Needs Zonos downloaded in TTS tile first; writes line 25 s+ early, ducks music ~9 s before end, unloads Zonos after each line; check phone warmth and "AI writes what the DJ says" toggle.
- [ ] **Test 311–312 extras:** 10-band EQ + presets + per-device profiles, Radio from this song, star ratings, smart playlists, playback speed slider, Year in music, ListenBrainz scrobbling, home player widget, Android Auto / Car mode, tag & lyrics editors, Sing mode, back-to-Now-Playing navigation.

## Next Up
1. Build and publish **test 317** APK (includes the 3 test 316 bug fixes above) — either via CI / Claude or after installing JDK + Android SDK locally.
2. Address any issues found during Jarrod's S24 test pass of tests 311–317.
3. Merge [PR #12](https://github.com/jrod-86/open-keyboard/pull/12) into `main` and clean up superseded older PRs (#1, #2, #4–#9).
