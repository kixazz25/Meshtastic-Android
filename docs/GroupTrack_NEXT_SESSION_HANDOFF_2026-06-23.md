# GroupTrack — Next Session Handoff (resume 2026-06-24)
*Written 2026-06-23 EOD. Pairs with `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-23.md`.*

## ⚠️ READ FIRST — the process rule re-earned today
**When the code reads correct but the behavior is broken, GET THE RUNTIME VALUE — don't keep theorizing.** Tonight's area-search bug was cracked by reading `convoy_panel.json` on the device (before/after a search = byte-identical; deleted on cold launch), after an hour of wrong code-theories. Anchor on the DEVICE and on Fred's lived observation; do not defend a theory — or a code placement — as new facts come in, update to them. (Carry-forward: 06-21 "believe the device over the code"; 06-22 "look at the data before changing dependent code, touch only the field in scope." Commit working features SAME-DAY.)

## WHERE WE ARE
HEAD = **`63ea797ab`**.
**Two wins committed today:**
- **`a658d7a00`** — docs: the FULL V2.5 user manual + release notes shipped as in-app assets. Captured the entire app screen-by-screen on device, rebuilt into a 4-level **drill-down / focus-on-current-branch** structure (each map opens with its labeled launch-point image; Radio Setup splits into Setup/Restore), compressed 85MB→3.2MB. 4 files in `app/src/main/assets/`: `grouptrack_manual.html` (replaced the old 24.9KB cookbook), `grouptrack_release_notes.html`, `convoy_map_LABELED.png`, `planning_map_LABELED.png`. The `?`→Full Manual button loads these exact filenames — no code change.
- **`63ea797ab`** — fix: area search seeds the viewport frame so all four artifact types draw (UnifiedSearch.kt, 11 ins). Confirmed on device (NH→Utah).

## WHAT GOT FIXED TODAY (committed, device-tested)
**Area-search draw bug (haunted the project) — FIXED.** Universal AREA search repositioned the map via `setView` but never seeded `lastViewport*`, so the draw queried a STALE frame — only Trails drew; Tracks/Waypoints/Routes were empty; Select All populated nothing; zoom/pan didn't recover. Proven on device by reading the persisted JSON before/after a search (identical) and confirming the file is deleted on cold launch. Root: area worked before it went universal (`583b7b9df`); the area bug == the FIT bug (reposition not seeding the draw frame), and FIT had already been fixed — area lost the equivalent and nobody caught it until field-testing today. **Fix:** after the area `setView`, post (~550ms) the standard `getBounds → onViewportChanged` round-trip (the same seed every working reposition uses) so `lastViewport*` becomes the new searched frame.

> This is the TARGETED band-aid. The durable fix (the two-role write) is tomorrow's priority #2.

## 🎯 ORDER FOR TOMORROW (three priorities, in order)

**1. Route-build popup conflict — KEY FOR DEPLOYMENT.** During route creation (+ Route in Work with Artifacts), tapping the map to place a route point also fires the trail-tap popup; they collide. Suppress the trail-tap popup while in route-build mode — gate the trail-tap popup handler on a route-build flag in `convoy_map.html` / `grouptrack_map.html`. Quick, contained, unblocks deployment. Good warm-up.

**2. Durable convoy/planning map JSON write — the REAL fix for the search/draw issues (the "two-role write").** Today's `63ea797ab` is a band-aid (seeds in-memory `lastViewport*` on the area path only). The durable fix makes the persistent frame write happen on EVERY reposition for BOTH maps via one mechanism. **The write has TWO ROLES:**
- **BEFORE the draw** — the bbox is the INPUT that DRIVES the queries; stale bbox → query resolves the OLD/empty container (the area bug).
- **AFTER the draw** — the query has resolved the NEW container (what's in view); that settled snapshot is what to persist.
- There's no single write point (the snapshot needs built filters but can't gate on the failing draw → two roles). The failure lives in the HOLE when a path goes out-of-line; the fix keeps every reposition IN LINE through the same sequence.
- **FIRST TASK — READ, don't assume placement:** read `onViewportChanged` → `processViewport` → `processArtifact` IN ORDER; mark (a) where the query takes the bbox as input (before-write point), (b) where it resolves content (after-write point), (c) where the draw fires. Place the writes there; route area / FIT / gesture (moveend) / cold-launch GPS-center (ConvoyScreen 670) / filter-change (onSetState) through the same mechanism. Verify each path with the device JSON read. Do BOTH convoy and planning.
- Replaces the scattered 7 `lastViewport*` writers + dozen+ ad-hoc getBounds round-trips (incl. today's band-aid) with one tested path.

**3. Generate the AAB for Google Play — THE MILESTONE.** With route-popup fixed and the durable map write in, V2.5 is feature-complete and the manual is shipped. Cut the Google Play AAB. (Lead-track [2.1] rewrite stays LAST, on top of a banked AAB.)

### Also queued (not blocking the three)
- Upload the two big manual masters (`grouptrack_manual_DRILLDOWN_2026-06-23.html` ~82MB, `grouptrack_manual_LIVE_2026-06-23.html` ~87MB) to Drive/G: as archives. (Claude can re-share for download.)
- While testing, verify artifact search (routes through FIT, which already seeds `lastViewport*` — should be OK; watch for the FIT-pinhole symptom where only the fitted artifact draws because the FIT box is sized to one artifact).
- MY-CART HUD raw values (heading `28954000°` should be 0–360°, battery `101%`) — likely a raw-value/clamp issue in the HUD render path; KEEP DISTINCT from the bbox bug unless data links them.

## ⛔ ONE STANDING REMINDER ON THE TWO-ROLE WRITE
Do not place the JSON write by assumption. The snapshot (`saveConvoyState`, ConvoyScreen 248) writes the FULL set — bbox AND all artifact filter values (states + checked rows) — so it can't run before filters are built, and it can't gate on the draw (the draw is the failing step). Read the pipeline first; let the code show where the bbox is consumed and where content resolves.

## DOCS STATUS (shipped today)
- **Manual** `app/src/main/assets/grouptrack_manual.html` — full drill-down structure, real device screenshots, both maps, every launch point, zero placeholders. Compressed 3.2MB. Loaded by the `?`→Full Manual button (ConvoyScreen.kt:1345 + ConvoyMapViewerScreen.kt:742).
- **Release notes** `app/src/main/assets/grouptrack_release_notes.html` — refreshed; install-as-update warning preserved (protects ~18 tester DBs).
- **Labeled map images** `convoy_map_LABELED.png` / `planning_map_LABELED.png` — bundled as in-app reference indices.
- **Master files (NOT committed, too big — archive on Drive/G:):** `grouptrack_manual_DRILLDOWN_2026-06-23.html` (~82MB full-res drill-down), `grouptrack_manual_LIVE_2026-06-23.html` (~87MB flat content master). The committed 3.2MB version = `grouptrack_manual_INAPP_2026-06-23.html`.
- **Pristine sources — NEVER overwrite:** `grouptrack_manual_PRISTINE_BASE_recovered_2026-06-18.html`; revision authority `GroupTrack_MANUAL_and_RELEASENOTES_revision_instructions_2026-06-19.md` (Drive id `1ibNIPW0Nb7bcZh1gmIPAfh9ZZnuMaxPU`).

## CARRIED ITEMS (full detail in the Living Checklist — do NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — one lead / one track / snap-2 100yd / per-cart per-second GPS replacement. MUST-SHIP; attempt LAST after AAB banked. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized (extension db, enjoyment 1-5 + ride_again); feeds upload_queue.
- **Queue panel [3.3]** — backend done; live-data panel UI wiring.
- **Tile concurrency** — V2.5 interim settable (default 4, max 6); 2.6 = batch (.tpkx/PMTiles) + AWS + Esri thresholds.
- **"PROCEED TO UPDATE" mode + inline recap** — basic reprocess-selection works (Patch O); richer mode/recap UI is a re-author target if wanted, NOT a blocker.
- **[6.2]** remove dead `utah_trails_stgeorge.geojson` — still LIVE-LOADED at `ConvoyScreen.kt:1912`; remove the loader FIRST (else FileNotFound), then `git rm` the asset, tidy `grouptrack_map.html:774`. Own commit. (Backlog — don't touch near release.)

## BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`. Warm ~12-23 min; **today's build ran ~53 min (cold / manual assets changed)** — budget for it. `--rerun-tasks` if incremental changes don't appear ("up-to-date").
- **GREP-CONFIRM a patch is on disk before building** (`grep -n "<marker>" <file>`) — a silently-aborted patch wastes a full build.
- Install: `adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/release/app-google-release.apk` THEN `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell am force-stop com.grouptrack.android` then cold-launch. (Droid 1 = `8624SBCEDF00001789`, field GPS, pkg `com.grouptrack.android`; Droid 2 = `24039703201775`.)
- Device paths in Git Bash need `MSYS_NO_PATHCONV=1`. State JSON: `/sdcard/Documents/GroupTrack/state/<map>_panel.json` (deleted on cold launch).
- Logcat: `adb -s 8624SBCEDF00001789 logcat -c` → action → `adb -s 8624SBCEDF00001789 logcat -d -s GTVP:*` (or `JSONDIAG:*`).
- Patch files: unique versioned names; Windows path INSIDE the script = `C:/Users/...`. count==1 guard + runtime newline detect; identical lines (the two `onViewportChanged` at 575/769) need collision-proof anchors.
- Line endings: TrailImporter.kt / ConvoyTrailSourceScreen.kt / SpatialDbManager.kt = LF; ConvoyMapViewerScreen.kt + both HTMLs = CRLF; UnifiedSearch.kt = LF (Git warns LF→CRLF on commit — harmless). Commit only named files, never `git add .`.

## TREE — parked (never `git add .`)
`.bak_*` files, `utah_trails_stgeorge.geojson`(+.bak), `grouptrack_spatial.db` (117MB), state JSON under `/sdcard/Documents/GroupTrack/`, `docs/.tmp.driveupload/`.
