# Session Handoff (Relay Baton)

- **Last updated:** 2026-11-23 by Gemini (Antigravity)
- **Active branch:** `claude/project-thread-1y9nzr` ([PR #12](https://github.com/jrod-86/open-keyboard/pull/12))
- **Latest APK:** **test 318** (building via GitHub Actions → GitHub Pre-Release `test-318`, signed with shared `app/debug.keystore`)
- **Next build number:** **test 319**

## Confirmed Working on S24 (23 Nov)
- ✅ Custom volume level, Hide any tab, Measure All
- ✅ Wavy animated seek bar (Now Playing + Mini Player)
- ✅ Now Playing hold Prev/Next to scrub
- ✅ Now Playing scrolling title + animated artist
- ✅ Car mode symmetric buttons
- ✅ Popular Top 20s & Top 20–50 filters
- ✅ Support artist (`⋮` menu → official links)
- ✅ Equaliser & styles

## Just Finished (Test 318)
1. **Borderless top-bar DJ & Stem icons (`MusicActivity.kt`):** Removed the square box around `DJ` and `🎛️` so they match Smart View, Equaliser, and `⋮`.
2. **Design studio at top of Settings (`MusicExtras.kt`):** Moved `🎨 Design studio` right to the very top of Open Waves Settings.
3. **Mini Player hold-to-scrub (`WavesUi.kt`):** Hold Previous / Next on the in-app Mini Player to gently scrub backward / forward (tap still skips track).
4. **Popular decade filter (`PopularityUi.kt`):** Replaced `50s` / `60s` with **`Before 1970s`**, keeping `'70s`, `'80s`, `'90s`, `'00s`, `'10s`, `'20s`.
5. **Removed Discover tab (`WavesUi.kt`):** Dropped the plain Discover tab (kept **☕ Support artist**, which Jarrod liked).

## Outstanding S24 Phone Testing (Test 318)
- [ ] **New in 318:** Borderless DJ & Stem top-bar icons, Design studio at top of Settings, Mini Player hold-to-scrub, Popular `Before 1970s` filter, Discover tab removed.
- [ ] **Phone warmth** during normal playback (after model downloads finish).
- [ ] **DJ Announcer (needs Zonos):** Savannah only, smooth fade-out → Savannah → fade-in, skip-ahead announcement.
- [ ] **Moods mix length (needs Moods scan):** `15 / 30 / 60 / All` chips update the song list.
- [ ] **Stem mode (needs Stem splitter):** Battery warning Cancel doesn't start splitting; `🎛️` icon shows only when Stem mode is ON; sliders / quick picks / export.
- [ ] **Earlier 311–312 extras (if not yet checked):** Smart playlists, Playback speed slider, Tag editor, Lyrics editor, Sing mode, Back to Now Playing.
