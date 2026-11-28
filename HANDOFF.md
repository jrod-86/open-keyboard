# Session Handoff (Relay Baton)

- **Last updated:** 2026-11-28 by Gemini (Antigravity)
- **Brand suite:** **O-Board** (keyboard) + **Open Hub** (built-in mini app store / plugins) + **Open Waves** (music player)
- **Active branch:** `claude/sing-mode-upgrade` (branched cleanly from `main` after PR #17 merged)
- **Latest APK:** **test 382** (building via GitHub Actions → GitHub Pre-Release `test-382`, signed with shared `app/debug.keystore`)
- **Next build number:** **test 383**

## Confirmed Working on S24 (23–28 Nov)
- ✅ Custom volume level, Hide any tab, Measure All
- ✅ Wavy & 10 animated timeline seek bar styles (Now Playing + Mini Player)
- ✅ Hold Prev/Next to scrub (Now Playing + Mini Player)
- ✅ Now Playing scrolling title + animated artist
- ✅ Car mode symmetric buttons
- ✅ Popular Top 20s, Top 20–50, Before 1970s filters + Genres chip row
- ✅ Support artist (`⋮` menu → official links)
- ✅ Equaliser & styles
- ✅ Borderless top-bar DJ icon, Design studio at top of Settings, Discover tab removed
- ✅ Moods 15 / 30 / 60 / All mix length buttons
- ✅ Phone warmth improved (not getting hot during playback)
- ✅ Home screen Open Waves icon survives APK updates without re-adding (Test 332+)
- ✅ Settings → Tabs `" · opens here"` removed
- ✅ Stem Splitter + live Stem mode playback (Vocals/Drums/Bass/Other sliders, quick picks, Back to normal, Export)
- ✅ DJ Announcer (truck drive test): Heart (Kokoro · Low battery) warmth/battery, 65/35 dual-deck crossfade smoothness, Short/Medium/Long scripts
- ✅ Radio, Podcasts & Audiobooks tabs (including offline download & exact resume)
- ✅ Random music pausing resolved
- ✅ Voice typing in loud places (work truck)
- ✅ O-Board + Open Hub rebrand (Test 373 & 375)
- ✅ Home screen player widget (Play/Pause, Previous, Next) (Test 374)
- ✅ Metadata / artwork (`Save info & artwork`, `Save artwork only`, `Edit tags`) (Test 374–375)
- ✅ Backup / Restore
- ✅ Smart playlists (Popular-style chips, one-tap `⚡ Smart` in Popular, long-press delete/edit) + Star ratings removed (Tests 376–378)
- ✅ Playback speed slider (Test 379)
- ✅ Old superseded GitHub PRs (#2–#9) cleaned up

## Outstanding S24 Phone Testing
- [ ] **Open Waves optional extras (if desired):** Lyrics editor, Sing mode, Year in music. *(Android Auto skipped for now — car head unit connection issues.)*
- [ ] **Keyboard remaining items:** Khmer tile names (native-speaker check), full animated sticker packs (PC pipeline).
