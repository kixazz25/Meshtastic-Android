# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-22
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `4c3541c4f` (06-22 fixes built + tested, commit pending)

---

## 🟦 START HERE — TOMORROW (2026-06-23)

**THE LESSON OF 06-21 (re-earned, burn it in):** believe the DEVICE and Fred's lived observation over the code. Today's whole grind was a stale `pending_area.json` on the device silently forcing the trail-source screen into the wrong path — NOT lost code, NOT a wrong panel. Hours of decompile + git forensics; the real cause was a leftover file. When a behavior "was there and is gone," hunt the SPECIFIC differing thing (device state, a file, one line) before theorizing. And **commit working features same-day** — uncommitted work is what gets lost.

**THE LESSON OF 06-22 (new, burn it in):** LOOK AT THE DATA before changing code that depends on it — and touch ONLY the field the task names. Today's pain: a popup-properties enrichment in `queryTrailsByViewport` was allowed to OVERWRITE `CartoCode` (the field that drives trail color) with `trail_properties.carto_code` (different format/blank). Result: OHV (code 4) drew cyan instead of blue — a regression in working behavior, introduced silently while doing an unrelated task. A 2-minute look at the two `carto_code` columns (spatial = color-driving code; properties = different) would have prevented it. RULE: the CODE drives color, the TEXT is just text — never merge one into the other; a patch changes only the field in scope; flag adjacent changes and ask first, don't fold them in.

**SESSION-START INPUTS (Fred provides):** recommit + uploads xrefs + current source files as needed. Re-pull current HEAD files before analyzing.

### ✅ DONE 06-22 (built + tested on device; COMMIT PENDING — 4 files)
Four working fixes, verified on device, not yet committed (commit by explicit path; leave .bak deletions + docs/.tmp out):
- **Viewport report on `setView`** (`convoy_map.html` + `grouptrack_map.html`): UnifiedSearch area-search repositioned via bare `map.setView()`, which doesn't fire `moveend` → `onViewportChanged` never ran → `lastViewport` stayed stale → draw queried the OLD bbox → trails didn't draw until a manual pan. FIX: `setView()` now reports its new bounds (the `getBounds()→onViewportChanged` round-trip the moveend handler does), so every programmatic move updates `lastViewport`. Also routed the inline center-on-ME `setView` through the named function. Confirmed: area search (Kanab) now draws trails + updates `convoy_panel.json` bbox without panning. Planning + convoy both fixed.
- **Trail popup properties** (`SpatialDbManager.kt` `queryTrailsByViewport`): see CLOSED bug above. Copy CartoCode + batched `trail_properties` merge (Surface/Uses/etc.).
- **Trail color regression fix** (`SpatialDbManager.kt`): the popup enrichment had OVERWRITTEN CartoCode with `trail_properties.carto_code` → OHV drew cyan. Removed CartoCode (and carto_code) from the enrichment merge; the spatial code value (drives color) is left untouched. Also removed a SHADOW `trailColor()` in `grouptrack_map.html` (a word-keyed `indexOf('ohv'/'hik')` version at ~209 that overrode the correct digit-keyed one at ~146 — it returned cyan for digit codes). Now one digit-keyed `trailColor`: 4→blue, 2→orange, 1→yellow, 5→purple, 3+default→cyan. OHV draws blue again.
- **Draggable→Popup legend** (`ConvoyMapViewerScreen.kt`, planning only): replaced the static full-width legend bar with a compact KEY-icon button (Material `Icons.Filled.VpnKey`) that opens a `Popup` (taps-outside to dismiss). Popup escapes the Column width that was forcing a full-width bar. 8 rows: OHV/Road blue, Hiking & Biking orange, Hiking Only yellow, Biking Only purple, Paved/other cyan, Track green-dash, Route magenta-dash, Waypoint pin. Colors match `trailColor`. Convoy gets NO legend (too busy / live HUD).

### ✅ DONE 06-21 (committed)
Two commits landed:
- **`791ed3b45`** — import/carto code (4 files):
  - **Patch M** — carto update-in-place on reprocess: `updateExistingFeature` writes carto to BOTH spatial `trails` + extension `trail_properties` (CARTO_ONLY default; ALL adds name/uses/etc.; never geometry). IMPORTDIFF confirmed it runs/writes. Most spatial carto was ALREADY correct, so it's a safe no-op where data is right.
  - **Patch O** — imported sources selectable: `SourceSelectCard` now always shows the RadioButton; ✅ becomes a status badge → imported source re-selectable → VALIDATE → reprocess.
  - **Patch Q1–3** — explicit `TrailImporter.LaunchMode {SELECT_SOURCE, BY_AREA}`. Trails-menu launch = SELECT_SOURCE (opens A1, never reads the JSON); by-area launch = BY_AREA (reads the JSON). Stale `pending_area.json` can no longer hijack the menu path. Enum extensible for a future 3rd method.
  - **Patch R** — `carto_code AS CartoCode` alias in `queryTrailsByViewport` (the query selected snake_case; `buildTrailGeoJson` reads PascalCase).
- **`4c3541c4f`** — docs: rebuilt manual + release notes, committed as app assets ("?" loads them) + dated `docs/` copies.

### ✅ CLOSED 06-22 — map popup carto "Unknown" + trail properties
Root cause was NOT the HTML popup JS. `queryTrailsByViewport` selected `carto_code AS CartoCode` but never COPIED cursor column 3 into the result map, AND never pulled Surface/Uses (those live in `trail_properties`, not spatial `trails`). So the popup got blanks. FIX: copy CartoCode into the row map + ONE batched `trail_properties` query (`WHERE trail_id IN (...)`, perf-safe on the hot draw path) merging Surface/Uses/etc. as PascalCase keys `buildTrailGeoJson` reads. Popup now matches the detail panel. NOTE: `motorized_allowed` is blank in source data → that field legitimately shows "Unknown" until imported (not a bug).

### 🟦 PRIMARY 06-22 TASK — HELP DOC + SCREEN CAPTURE PASS (Fred's stated plan)
Fred brings up the help doc and begins screen capture. Claude builds the **capture-companion**: embedded `mkdir` + a per-`[screenshot to be added]`-slot `adb exec-out screencap -p > /sdcard/.../NNN_name.png` command, in nav order (from `navigation_xref.txt`); Claude owns the slot filenames. Fred navigates each screen, runs each command, zips + uploads the PNGs; Claude inserts each into its slot (+PIL annotate if needed). The rebuilt manual is now ~22 V2.5 cards (3.0 chapters removed) — fewer slots than the old 41. **Claude CANNOT run adb — Fred runs every capture.**

### Ordering for 06-23
1. Session-start: **commit the 4 pending 06-22 fixes** (by explicit path) if not already done; re-pull fresh files.
2. **Screen capture pass** — capture-companion manual is BUILT (`grouptrack_manual_CAPTURE_2026-06-22.html`, 18 embedded `adb exec-out screencap` commands to `/d/grouptrack_screenshots/NN.png`). Fred captures screen by screen → Claude inserts PNGs into slots → strips the `data-capture` command boxes for the final asset.
3. After captures in → re-commit docs with images → **cut the 2.5 AAB**.
4. Then resume: **read the LED cart track** + **investigate batch map tile downloads** (Fred's stated next focus).
5. Lead-cart [2.1] rebuild + tile batch formats remain the big carried items.

### Still-open big items carried (full detail in their own sections below — DO NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — settled design; MUST-SHIP; attempt LAST (after AAB banked).
- **Tile downloads — batch formats** — V2.5 interim = settable concurrency (default 4, max 6); 2.6 = batch + AWS + Esri thresholds.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized; feeds upload_queue.
- **"PROCEED TO UPDATE" mode + inline recap** — the fuller 6/13 reprocess flow (button + mode picker + live running recap). Basic reprocess-selection works now (Patch O); richer UI is a re-author target if wanted, NOT a blocker. Banked unless Fred wants it for 2.5.

---

## ⭐ COMMITTED WINS

**06-18:** FIT selection retention `35ccccc4a` · convoy "?" help `60db85131` · track arrows pixel-spacing+neon `d75572a1f`.
**06-19:** convoy universal search FAB `42dc848ce` · planning search FAB + all 3 old searches removed `583b7b9df`.
**06-20:** detail panel consolidation + Carto Type `37bc88431` · FAB + icon column `d57626f77` · FIT recenter `a43f80829`.
**06-21:** FIT final `27f493375` · arrows+touch `efc75abf4` · import/carto (M/O/Q/R) `791ed3b45` · docs rebuild `4c3541c4f`.
(Detail-panel consolidation, map-tap→detail, WWA→FAB/icon-column — all DONE and committed; they were the open 06-19 START-HERE tasks.)

---

## ⛔ LEAD-CART CONVOY-TRACKING REBUILD [2.1] — recovered settled design (MUST-SHIP)

> Significant settled V2.5 design. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31) + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`. NOT done. Attempt LAST (after AAB banked — see RELEASE STRATEGY).

**The problem:** current lead-cart tracking is a hodgepodge of evolved lead-cart rules + position projection. Unreliable — **phantom carts report in when a rebroadcast is made**. Gut and restart, don't patch.

**The rebuild — one lead cart, one lead track:**
1. Track **only** the lead cart, from its broadcasts (not projection).
2. **Snap-2, 100-yard radius:** snap the lead's broadcast position onto known trail/track geometry within 100 yards.
3. **Every other cart shows at its current position only — not tracked.** Live marker, no per-cart track line, no projected paths. (Kills phantom-cart at the root.)
4. **Each cart tracks its own progress** and, as it overtakes the lead's positions, removes the lead's path from its own map and replaces it with its own device GPS, recorded every second.

**Net:** one continuous trail from the lead that improves in accuracy as carts cover ground in the lead's wake. Lead broadcasts at best every 5s (radio limit) so the trail ahead is coarse; each cart records own GPS every 1s so the trail behind ("rear-view mirror") refines to 1-second truth.

**Open detail (confirm before building):** when an overtaking cart replaces the lead's path with own GPS — unconditional, or snap-gated (off-trail cart beyond threshold does NOT overwrite, so a wild detour doesn't corrupt the composite)? An earlier note had an off-trail guard.

**Planning doc to produce — DEMOLITION + REBUILD, two parts:**
- **Part 1 — identify + remove ALL previous track-recording methods/processes.** Catalog every flow to rip out: three parallel flows (`leadTrackSegments`/`gpsTrailSegments`/`routeTrailSegments`), live `drawTrack` path (~ConvoyScreen 345-350), `trackLeadOnly` filter, ConvoyEngine lead-lock/tick pieces (`evaluateLeadLock()`, `tick()→compute()→assignLeadTail()`, `lockedLeadNodeId`, `_leadLockedFlag`; known tick-oscillation), any dead track paths.
- **Part 2 — the new method** (one lead / one track / snap-2 100yd / per-cart per-second GPS replacement). One growing lead-position polyline gated on `lockedLeadNodeId`; `pushTrackToMap` net-new (0 refs). Discovery first: refreshed `field_crossref_raw.txt`, trace live, 2-cart field capture.

> Do not confuse lead-cart snap-2 (lead broadcast → known geometry within 100yd) with ROUTE snap-2 already shipped (route drawing follows geometry between snapped vertices). Different features.

---

## 🚢 RELEASE STRATEGY — AAB as fallback, lead-track rewrite LAST

> Sequence the highest-risk change behind a banked, complete release. The lead-track rewrite [2.1] is a demolition+rebuild (riskiest: gut 3 flows + lead-lock/tick engine, open wake-replacement question, tick-oscillation, needs 2-cart field capture). Do NOT let it gate the release.

1. Complete ALL other V2.5 work — search ✓, detail-panel consolidation ✓, map-tap→detail ✓, artifacts-FAB icon column ✓, import/carto ✓; remaining = popup-carto display, queue+survey.
2. **Manual rewrite + screen captures DONE and BUNDLED** (manual ships in-app as `grouptrack_manual.html`; AAB carries it). Manual text is rebuilt + committed (06-21); captures pending. Fallback = complete release, features AND docs.
3. **CUT THE 2.5 AAB HERE** — complete 2.5 (features + manual), WITHOUT lead-track rewrite. **Banked FALLBACK.**
4. **Lead-track rewrite — attempt LAST, on top of banked AAB.** Clean → folds into 2.5. Hairy → defer that piece to 2.6, ship banked AAB. No-downside attempt.

**Manual across the AAB boundary (manageable, not a rebuild):** bundled manual documents the app AS THE AAB SHIPS (old track methods, since rewrite isn't in the AAB) — never wrong relative to what shipped. IF rewrite later lands, it changes only the Settings track-parameter fields — a contained edit-in-place + possibly one Settings re-capture. If rewrite defers to 2.6, the 2.5 bundled manual already matches the shipped AAB exactly.

---

## 🎯 TRACK SURVEY ON STOP [7.5] — V2.5 collect-now

> Build this — start collecting trail-rating data ASAP with field testers. Fully specced in AllDocs.

**GOAL:** accumulate real trail-rating data from V2.5 testers NOW, held locally, ready to populate 3.0 trail ratings when the cloud pipe exists.

**TRIGGER (RideState-driven):** on STOP after recording — ORGANIZED (real ride) → full survey+save; SOLO/CONVOY → name-only. Owned by TrackManager, not a separate screen.

**SURVEY UI:** SAVE TRACK (name, shows distance/duration) → SHARE? Y/N → RATE THIS RIDE 1-5 → RIDE AGAIN? Y/N → DONE.

**WRITES (schema FINALIZED, EXTENSION db `grouptrack_data.db`, NOT spatial):**
```sql
CREATE TABLE track_surveys (
    survey_id    TEXT PRIMARY KEY,
    track_id     TEXT NOT NULL,
    enjoyment    INTEGER,          -- 1-5
    ride_again   INTEGER,          -- 0=no, 1=yes
    submitted_at TEXT NOT NULL,
    FOREIGN KEY (track_id) REFERENCES tracks(track_id) ON DELETE CASCADE
);
```
Share Yes → `shared=1` + an `upload_queue` entry. **SUPERSEDES the 3/26 difficulty/scenery/fun variant — FINAL survey is enjoyment 1-5 + ride_again Y/N only.**

**COLLECT-NOW:** `upload_queue` — V2.5 collect only; 2.6/3.0 processes (`ConvoySurveyUploader` drains to AWS). **Survey + upload_queue + queue-panel upload/download toggle are one connected area — build together.**

**DEDUP rule:** track identity = `(artifact_type, geom_hash, creation_date)` — same geometry + same day → ONE (a 10-rider group ride = one shared track). Track aliases carry `creation_date` where trails don't.

---

## 🎯 TRAIL FINDABILITY — selecting the right trail among hundreds of same-named segments

> Framed by the PROBLEM. "Jordan River Trail" = 314 distinct segments (each unique geometry, NOT a dupe).

- **Blank startup map** — below z11 show "zoom in to see artifact info" so an empty-looking map self-explains.
- **Silent truncation at the cap** — raise cap 200→400; when hit, explicit message "Maximum artifacts reached for this map area. Zoom in to ensure you have all artifacts." No silent truncation. (Real long-term fix = paging [11.1].)
- **"Why so many times?"** — on the select-list row surface the unique geom-hash as "this is a unique trail segment." DECISION: do NOT add a `section` field (compromises spatial design; identity is `UNIQUE(geom_hash)`). Show the existing hash. The AllDocs `section`-field plan is RETIRED.
- **Can't tell map lines apart** — trail name positioners/labels on the map. (Net-new Leaflet labeling in BOTH HTMLs, hot draw path, perf concern at hundreds — needs own design pass.)
- **Detail panel IS the disambiguation tool** — reached identically from select-list row-tap, search result, and map artifact-tap (DONE 06-20).
- **Detail content (schema-safe):** trail type via CartoCode (Carto Type field — DONE); length (`distance_miles` — verify stored else derive); unique-hash indicator. CartoCode line recoloring on the MAP is a separate 2.6 question, PARKED.

---

## TILE DOWNLOAD SPEED — V2.5 interim / 2.6 redesign

**V2.5 interim (executable now):** replace hardcoded 2-at-a-time cap with **user-settable max concurrent transfers — default 4, max 6**, user can throttle down. Single settable value + Settings control. **Queue-panel guidance:** speed depends on network; if transfers fail, advise lowering the simultaneous-transfer count until failures stop. *(Prior field obs: CRASH past 3 concurrent. Default 4/max 6 sit above that — user throttle + guidance are mitigation; crash root-cause deferred to 2.6. If 4 unstable in test, fall back to 3.)*

**2.6 full redesign (captured so it stops living only in Fred's head — do NOT re-derive):**
- **Batch tile transfer** — Esri batch/bundle instead of per-tile `{z}/{x}/{y}`. Formats: **.tpkx** (Esri-native Map Tile Package) and **PMTiles** (open single-file, HTTP-range-readable). Collapse hundreds of per-tile requests into few bundle transfers → faster + stable (sidesteps concurrency crash) + threshold-economical. Needs on-device unpacker into the Leaflet `{z}/{x}/{y}` cache; .tpkx readable via GDAL `esric` driver as reference.
- **AWS staged/hosted** — EC2 caches tiles by bbox (30-day TTL), pre-assembles a tile_manifest per area (`map_status=READY`), two-tier dedup (manifest − local_cache = delta), parallel delta pull (4 threads). First-occurrence-wins: Esri hit once per tile then served from your server — protects Esri threshold. EC2 also merges trail geometry into one GeoJSON by bound_hash.
- **Esri developer account / thresholds** — Fred registered a FREE Esri dev account with API-level service access + monthly thresholds (~2M tiles/mo free tier referenced). Account-tier/threshold-vs-volume choice deferred pending transfer-method design. Open: metering model (per-tile transaction vs batch-export/credit). **Account terms live only in Fred's recollection — capture as provided; do NOT reconstruct.**
- **Download-crash-past-3-concurrent** — root-cause in `ConvoyTileDownloader` concurrent path (likely resource exhaustion). Gates safe high concurrency for ANY approach.

---

## 📖 DOCUMENTATION WORK REMAINING (user manual + release notes)

**Sequencing:** documentation done LAST among feature work, AFTER all screens final, BEFORE the AAB cut (manual ships in-app, must be in the AAB).

**STATUS 06-21:** Manual + release notes TEXT rebuilt and committed as app assets (`4c3541c4f`). Remaining = the SCREEN CAPTURE pass (tomorrow) + popup-carto display fix, then re-commit docs with images.

**USER MANUAL (06-21 rebuilt):**
- `app/src/main/assets/grouptrack_manual.html` — rebuilt from the pristine cookbook, edit-in-place lineage, **Chapters 1–3 (account/dashboard/ride mgmt) REMOVED** per direction (V2.5 only). ~22 screen cards across 3 chapters (Maps/Trails/Tracks · Offline Maps/Downloads · Radio Setup) + appendix. Folds in: icon-nav WWA, universal search, the ONE detail panel + Carto Type, carto-in-popup, the 8-code CartoCode legend with swatches, FIT, "?" help, neon arrows, snap-2 routes, viewport persistence, Trail-Sources Select-Source/By-Area reprocess + recap. `[screenshot to be added]` slots preserved.
- Dated copy `docs/grouptrack_manual_WORKING_2026-06-21.html`. Pristine base (Drive `1ibNIPW0Nb7bcZh1gmIPAfh9ZZnuMaxPU`) NEVER overwritten.
- **Screen captures (workflow proven):** Claude builds capture-companion with embedded `mkdir` + per-slot adb `screencap` commands (Claude owns filenames=slot names); Fred navigates + runs each; Fred zips + uploads; Claude inserts by filename + PIL-annotates. **Claude CANNOT run adb.** Capture once, screens final.

**RELEASE NOTES (06-21 rebuilt):**
- `app/src/main/assets/grouptrack_release_notes.html` — rebuilt in **what changed → what it means to you → benefit** format, brief, each entry links into the manual. Install-as-update warning preserved VERBATIM at top (protects ~18 tester DBs). Dated copy `docs/grouptrack_release_notes_WORKING_2026-06-21.html`.
- Both ship as app assets (the "?" button's two options open them); cross-links use bare filenames so they resolve in-app.

**Revision authority:** `GroupTrack_MANUAL_and_RELEASENOTES_revision_instructions_2026-06-19.md` (pristine base, edit-in-place, two homes). Both docs republished each session as downloadable artifacts; Fred collects EOD into G:/Drive + recommit pulls into repo `docs/`.

---

## OTHER OPEN (backlog / 2.6)

- **[6.2] Remove leftover geojson asset + JS-injection code** — `utah_trails_stgeorge.geojson` is already the trimmed 3-feature stub (3KB, no distro bloat), but it's still LIVE-LOADED at `ConvoyScreen.kt:1912` (`context.assets.open("utah_trails_stgeorge.geojson")`). `grouptrack_map.html:774` is just a dead comment. To close: remove the ConvoyScreen.kt:1912 loader block FIRST (else FileNotFound), then `git rm` the asset, tidy the comment. Trails now come from the spatial DB. Own commit, NOT folded into a feature commit. Backlogged 06-22 (don't touch near a release).
- **[3.3] Queue panel** — restore upload placeholder + add upload/download activity selector at top of panel. (Backend hold/resume/cancel done; this is UI — connects to survey/upload_queue.)
- **Blank trail-name in FIT's JSON row** — id correct, selection id-based so it works; name writes "". Cosmetic, parked.
- **[1.2] sliceLine whole-trail — VERIFY OBSOLETE** — confirm dead (xref `sliceLine` callers) and remove, or re-scope.
- **DONE (recovered):** import trails ✓ · [10.1] BLE timeout fix ✓ · Gaia/onX standalone parity ✓.
- **Backlog:** [11.1] paging (real fix behind artifact cap); Map Manager screen items.
- **Tree cleanup:** remove `.bak_*` files, `utah_trails_stgeorge.geojson.bak`; never commit `grouptrack_spatial.db` (117MB), `docs/.tmp.driveupload/`.

---

## DESIGN CONTEXT (carried forward)

- **Two draw paths BY DESIGN:** (A) `drawPersistedState` = saved/restore from JSON; (B) onViewportChanged = in-memory live vars preserving selections across zoom/pan. New actions update the LIVE side via the existing select mechanism, not bypass it.
- **Map-purpose model:** Convoy = live/location (GPS, proximity, session-only). Planning = deliberate/identity (search, fit, persisted frame).
- **Reusability principle:** own behavior in the SHARED component; callers pass DATA not BEHAVIOR (`mapContext` routes it). This is why search is shared `UnifiedSearch.kt` and the detail panel is ONE shared `ArtifactDetailPanel`.
- **Boy-scout cleanup:** clean what you touch, but only when you can see the blast radius — Kotlin (grep+compiler+xref) bounded; JS (grep only, silent runtime failure) not — be conservative, device-test.
- **Two front-ends, one backend (06-21):** the trail-source SELECT_SOURCE and BY_AREA paths share the same import process; the screen's starting step is driven by an EXPLICIT launch mode passed at the call site, NOT by reading a device file. A device file as a message bus between two in-process composables is the anti-pattern that caused the stale-JSON hijack.

---

## PROCESS NOTES — the recurring failure modes

1. **Settled designs keep getting lost** — each EOD doc rewritten fresh, dormant items not re-typed. Mitigation: this checklist + memory carry open items (append, don't drop); doc folder holds full specs; settled designs go on the list the same day; search the record before declaring a task "doesn't exist." **Commit working features same-day** — 06-21 proved uncommitted work gets reverted/lost.
2. **Believe the device + Fred's lived observation over the code (06-21).** When a behavior "was there and is gone," or a screen "opens wrong," hunt the SPECIFIC differing thing — device state, a leftover file, one line — before theorizing from the code or decompiling. Reading code can't reveal a wrong RUNTIME state. Today's stale `pending_area.json` cost hours because we theorized about code instead of checking the device file first.
3. **Stay at the process level, not in the weeds.** Before any UI patch, verify which composable a launcher actually renders / what device state a screen reads, on fresh files. Use the blast-radius / where-used tools FIRST.
4. **Verify file freshness** — re-pull current HEAD files before analyzing.
5. **Carry mid-diagnosis threads forward** — open diagnostic threads get FINISHED before new work stacks on them.

---

## DEVICE / BUILD QUICK-REF

- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm-daemon incremental ~12-23 min; cold daemon / clean ~38-60 min).
- **If incremental changes don't appear (same APK timestamp, "975 up-to-date"): add `--rerun-tasks` to force a full recompile.** Confirm APK timestamp moved before trusting a test.
- **GREP-CONFIRM a patch is on disk before building** (`grep -n "<marker>" <file>`).
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 = `8624SBCEDF00001789` field/real-GPS · Droid 2 = `24039703201775` dev).
- Device shell paths in Git Bash need `MSYS_NO_PATHCONV=1` prefix.
- **LINE ENDINGS:** TrailImporter.kt / ConvoyTrailSourceScreen.kt / SpatialDbManager.kt = LF; ConvoyMapViewerScreen.kt + both HTMLs = CRLF. Patches detect newline at runtime + count==1 guard.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 <name>.py`. UNIQUE versioned filenames (`_v2`, `_v3`) so the browser doesn't serve a stale download. Windows path INSIDE the script = `C:/Users/...` not `/c/Users/...`.
- NO sqlite3 on device; `run-as` blocked on release build (use debug build on Droid 2 to pull DB if needed).
- Logcat: `adb -s 8624SBCEDF00001789 logcat -s IMPORTDIFF` (carto BEFORE/AFTER per record).

## EOD DOCS — status

- This checklist → **06-21** (today's wins marked done; START-HERE-TOMORROW = help doc + capture pass).
- Handoff → `GroupTrack_NEXT_SESSION_HANDOFF_2026-06-21.md` (today's commits, stale-JSON resolution, popup-Unknown lead, capture-pass plan).
- Manual → `app/src/main/assets/grouptrack_manual.html` (committed) + `docs/grouptrack_manual_WORKING_2026-06-21.html`.
- Release notes → `app/src/main/assets/grouptrack_release_notes.html` (committed) + `docs/grouptrack_release_notes_WORKING_2026-06-21.html`.
- Lead-track demolition+rebuild doc → `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **Today's code committed (`791ed3b45`) + docs committed (`4c3541c4f`). Nothing left uncommitted.**
