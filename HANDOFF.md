# Session Handoff (Relay Baton)

- **Last updated:** 2026-11-23 by Gemini (Antigravity)
- **Active branch:** `claude/project-thread-1y9nzr` ([PR #12](https://github.com/jrod-86/open-keyboard/pull/12))
- **Latest published APK:** `OpenKeyboard-test316.apk` on branch `apk` (next APK build: **test 317**)
- **Local PC build env:** Windows PC does not have JDK / Android SDK installed; use GitHub Actions (`Build debug APK` workflow) or Claude's cloud build env to compile APKs.

## Confirmed Working on S24 (23 Nov)
- ✅ Custom volume level (−24 to −8 LUFS)
- ✅ Hide any tab feature
- ✅ Measure All

## Just Finished (23 Nov — Ready for Test 317)
1. **DJ Announcer overhaul (`Extras311d.kt`, `MusicActivity.kt`):**
   - Smooth **fade-out (800 ms → 22%) → Savannah speaks (1.15× soft-limited) → fade-in (1000 ms → 100%)** into the next song.
   - Replaced unused top **Volume** button on Now Playing with permanent **DJ** toggle pill (always visible, lit when on).
   - **Savannah only** (removed other DJ voice options).
   - Prepares immediately after a 3 s grace window with no late cutoff, so skipping ahead near the end still triggers the announcement.
   - Serialised `ZonosTts` with a `Mutex` and reset `failedFor`/`ready` on settings change.
2. **Phone heat reduction (`MusicExtras.kt`, `WavesLogo.kt`):**
   - Capped `BeatClock` and `WavesLogo` animations from 120 fps `withFrameNanos` down to ~30 fps (`delay(33)`), cutting GPU/CPU frame work ~75%.
3. **Car mode fix (`Extras311c.kt`):** Responsive Previous / Play / Next button sizing so right-hand Next is never squished on 360 dp screens.
4. **Stem mode indicator & battery warning fix (`MusicActivity.kt`, `StemsUi.kt`):**
   - Shows `🎛️` indicator in Now Playing top bar only when Stem mode is ON (tap to open Stems; disappears when off).
   - Tapping Cancel on the battery warning no longer leaves a stem split running.
5. **Moods mix length fix (`MoodsUi.kt`):** Fixed `15 / 30 / 60 / All` chips (removed `-1` overwrite bug + raised mix pool from 50 to 500).
6. **Now Playing & Mini Player polish (`MusicActivity.kt`, `WavesUi.kt`):**
   - **Press & hold Previous / Next** to smoothly scrub backward / forward (tap still skips track).
   - **Wavy animated progress line** (`WavyProgressLine`) on both Now Playing and the in-app Mini Player (flattens when paused).
   - **Scrolling title (`RollText`) + animated artist (`ShineText`)** on Now Playing (matching Mini Player).
7. **Popular tab filters (`PopularityUi.kt`):** Changed `Top 10s` → **`Top 20s`**, added **`Top 20–50`**.
8. **Discover tab + Support Artist (`Discover.kt`, `WavesUi.kt`, `MusicActivity.kt`):**
   - New **Discover** tab: ListenBrainz Fresh Releases + MusicBrainz genre feeds (`Fresh`, `Hip-Hop`, `Rock`, `Electronic`, `Indie`, `Pop`, `R&B`, `Jazz`, `Metal`, `Country`, `Lo-fi`), **▶ YouTube** preview, and **☕ Support** button.
   - **☕ Support artist** in Now Playing ⋮ menu and Discover cards — looks up verified Bandcamp / Ko-fi / Patreon / official links via MusicBrainz (with Bandcamp search fallback).
9. **CI & Docs:** Fixed `build-test-auto.yml` task name (`:app:testDebugUnitTest`), relocated master index to `docs/master-index/` (updated through test 316), added `AGENTS.md` + `CLAUDE.md`.

## Needs S24 Phone Testing (Test 317)
- [ ] DJ fade-out / Savannah voice level / fade-in + top **DJ** button + skip-ahead announcement
- [ ] Phone warmth check during normal playback & DJ
- [ ] Car mode symmetric buttons
- [ ] Stem mode `🎛️` top-bar icon (shows when on, hides when off) + Cancel on battery warning
- [ ] Moods `15 / 30 / 60 / All` buttons actually change the list
- [ ] Hold Previous / Next to gently scrub; tap to skip
- [ ] Wavy animated seek bar on Now Playing + Mini Player
- [ ] Now Playing scrolling title + animated artist
- [ ] Popular `Top 20s` and `Top 20–50` filters
- [ ] New **Discover** tab + **☕ Support artist** links

## Next Up
1. Publish **test 317** APK to `apk` branch once GitHub Actions `Build debug APK` succeeds.
2. Optional future feature: Organic Weekly Top-100 Voting (Last Year + All-Time) on Cloudflare Workers + D1 (\$0/mo free tier) — awaiting Jarrod's go-ahead.
3. Merge [PR #12](https://github.com/jrod-86/open-keyboard/pull/12) into `main` once S24 pass is green.
