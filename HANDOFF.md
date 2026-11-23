# Session Handoff (Relay Baton)

- **Last updated:** 2026-11-23 by Gemini (Antigravity)
- **Active branch:** `claude/project-thread-1y9nzr` ([PR #12](https://github.com/jrod-86/open-keyboard/pull/12))
- **Latest APK:** **test 319** (building via GitHub Actions → GitHub Pre-Release `test-319`, signed with shared `app/debug.keystore`)
- **Next build number:** **test 320**

## Confirmed Working on S24 (23 Nov)
- ✅ Custom volume level, Hide any tab, Measure All
- ✅ Wavy animated seek bar (Now Playing + Mini Player)
- ✅ Hold Prev/Next to scrub (Now Playing + Mini Player)
- ✅ Now Playing scrolling title + animated artist
- ✅ Car mode symmetric buttons
- ✅ Popular Top 20s, Top 20–50, Before 1970s filters
- ✅ Support artist (`⋮` menu → official links)
- ✅ Equaliser & styles
- ✅ Borderless top-bar DJ icon, Design studio at top of Settings, Discover tab removed
- ✅ Moods 15 / 30 / 60 / All mix length buttons
- ✅ Phone warmth improved (not getting hot during playback so far)

## Just Finished (Test 319)
1. **DJ Announcer deeper ducking (`Extras311d.kt`):** Drops music smoothly to **10%** (`0.10f` over 700 ms) while Savannah speaks (1.25× soft-limited) so her voice cuts through cleanly, then smoothly fades back up over 1 s into the next song.
2. **DJ Announcer built-in download buttons (`Extras311d.kt`):** Added direct **Download DJ voice (Savannah)** (with live progress bar) and **Download AI writing model** buttons right inside DJ settings whenever either model isn't installed yet (hidden automatically once downloaded).
3. **Stem mode top-bar highlight (`MusicActivity.kt`):** Changed `🎛️` to crisp borderless **`STEM`** highlighted in `Accent` (matching `DJ` when on; disappears when off).
4. **Settings → Tabs cleanup (`WavesUi.kt`):** Removed `" · opens here"` text after the start tab name.

## Outstanding S24 Phone Testing (Test 319)
- [ ] **DJ ducking (10%) & clarity:** Verify music ducks clearly down to ~10% under Savannah and fades smoothly back up; also check skip-ahead announcement.
- [ ] **Stem mode:** Verify highlighted `STEM` top-bar indicator (matching `DJ`), live Vocals/Drums/Bass/Other sliders, quick picks, Back to normal, Export.
- [ ] **Settings → Tabs:** Confirm `" · opens here"` is gone after Tracks.
- [ ] **Earlier 311–312 extras (if you want to check them):** Smart playlists, Playback speed slider, Tag editor, Lyrics editor, Sing mode, Back to Now Playing.
