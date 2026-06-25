# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-23 (EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `63ea797ab` (area-search fix; manual shipped `a658d7a00`)

---

## 🟦 START HERE — TOMORROW (2026-06-24)

**THE LESSON OF 06-23 (new, burn it in):** when every code path *reads* correct but the runtime behavior is broken, STOP theorizing and GET THE RUNTIME VALUE — read the device file / logcat. Tonight's area-search bug was solved by `cat`-ing `convoy_panel.json` on the device (byte-identical before/after a search; deleted on cold launch), after an hour of wrong code-theories (timing race, gate, transpose, stale-across-sessions, launch-restore). Anchor on the DEVICE and on Fred's observation; do NOT defend a theory — or a code placement — as new facts arrive, update to them.

**CARRY-FORWARD LESSONS (still live):** (06-21) believe the DEVICE over the code — hunt the specific differing runtime thing before decompiling. (06-22) LOOK AT THE DATA before changing dependent code; touch ONLY the field in scope — the CODE drives behavior, the TEXT is just text, never merge one into the other. **Commit working features SAME-DAY.**

**SESSION-START INPUTS (Fred provides):** recommit + uploads xrefs + current source files as needed. Re-pull current HEAD files before analyzing.

### ✅ DONE 06-23 (committed) — TWO BIG WINS
1. **User manual + release notes COMPLETE and SHIPPED into the build** — `a658d7a00`. Captured the ENTIRE app screen-by-screen via live device walk-through (both maps, every launch point, real screenshots, zero placeholders), then rebuilt it into a **drill-down / focus-on-current-branch structure** (4 collapsible levels mirroring app nav; Radio Setup → Setup/Restore options; each map section opens with its LABELED launch-point image as a visual index). Compressed 85MB→3.2MB (PNG→JPEG). 4 assets in `app/src/main/assets/`: `grouptrack_manual.html` (3.2MB drill-down, replaced old 24.9KB cookbook), `grouptrack_release_notes.html`, `convoy_map_LABELED.png`, `planning_map_LABELED.png`. The `?`→Full Manual button (ConvoyScreen.kt:1345 + ConvoyMapViewerScreen.kt:742) loads these exact filenames — no code change needed. **Big master files (NOT committed, archive to Drive/G:): `grouptrack_manual_DRILLDOWN_2026-06-23.html` ~82MB + `grouptrack_manual_LIVE_2026-06-23.html` ~87MB.**
2. **Area-search draw bug FIXED + confirmed on device** — `63ea797ab` (UnifiedSearch.kt, 11 ins). Area search now draws ALL FOUR artifact types (was: trails-only). See the dedicated section below.

### 🎯 TOMORROW (2026-06-24) — THREE PRIORITIES, in order
**1. Route-build popup conflict (KEY FOR DEPLOYMENT).** During route creation (+ Route in Work with Artifacts), tapping the map to place a route point ALSO fires the trail-tap popup — the popup and point-placement collide. Suppress the trail-tap popup while in route-build mode. Gate the trail-tap popup handler on a route-build flag in `convoy_map.html` / `grouptrack_map.html`. Quick, contained, and it unblocks deployment.

**2. Durable convoy/planning map JSON write — the REAL fix for the search/draw issues (the "two-role write").** Today's area fix (`63ea797ab`) is a targeted band-aid: it seeds the in-memory `lastViewport*` after the area `setView`. The durable fix is to make the persistent frame write happen on EVERY map reposition, for BOTH maps, via one consistent mechanism — so disk + memory never desync and this whole class of bug can't recur.
   - **Design (Fred's, reasoned out 06-23):** the JSON is the DECIDED CARRIER for these values → every map-positioning action must write it. On a reposition the value that changes is the **bbox**. **The write has TWO ROLES, not one:**
     - **(1) BEFORE the draw — the bbox is the INPUT that drives the queries** that resolve content. It must be current/written here; if it's stale (the area bug), the query resolves the OLD/empty container and nothing past Trails draws.
     - **(2) AFTER the draw — the query has resolved the NEW container** (the artifacts actually in view); THAT settled result is what's worth persisting.
   - One-write framing fights itself ("the snapshot needs built filters, but it can't gate on the failing draw") — because there isn't one spot, there are TWO roles. Fred's deepest framing: the failure lives in the HOLE that opens when a path goes OUT OF LINE (the universal area search became a side-entry that moves the map without passing the bbox down the line). **Fix = keep every reposition IN LINE through the same sequence so the value is always passed, never fetched stale.**
   - **FIRST TASK (READ, don't assume placement):** read the `onViewportChanged` handler + `processViewport` / `processArtifact` IN ORDER and mark (a) where the query TAKES the bbox as input (= the BEFORE-write point), (b) where the query resolves the new content (= the AFTER-write point), (c) where the draw fires. Place the two writes accordingly. Then route every reposition — area, FIT, gesture (moveend), cold-launch GPS-center (ConvoyScreen 670, currently leaves `lastViewport*` at 0.0), filter change (onSetState) — through the same two-role mechanism. Verify each path with the device JSON read. Do this for BOTH convoy and planning. Replaces the scattered 7 `lastViewport*` writers + dozen+ ad-hoc getBounds round-trips (incl. the `63ea797ab` band-aid) with one tested path.
   - **Device-read to verify:** `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json` (planning_panel.json for planning). File is DELETED on cold launch, created on first `saveConvoyState`.

**3. Generate the AAB for Google Play.** With route-popup fixed and the durable map write in, V2.5 is feature-complete and the manual is shipped — cut the Google Play AAB. This is THE milestone. (Lead-track [2.1] rewrite stays LAST, on top of a banked AAB — see RELEASE STRATEGY.)

### Also queued (not blocking the three above)
- Upload the two big manual masters (`_DRILLDOWN_` ~82MB, `_LIVE_` ~87MB) to Drive/G: as archives.
- Verify artifact search while testing — it routes through FIT (which already seeds `lastViewport*`), so it should be OK, but watch for the FIT-pinhole symptom (draws only the fitted artifact because the FIT box is sized to one artifact, not a viewport). Fold into the two-role write if it shows.
- MY-CART HUD raw values (heading `28954000°` should be 0–360°, battery `101%`) — likely a raw-value/clamp issue in the HUD render path; KEEP DISTINCT from the bbox bug unless data links them.

### Still-open big items carried (full detail in their own sections below — DO NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — settled design; MUST-SHIP; attempt LAST (after AAB banked).
- **Tile downloads — batch formats** — V2.5 interim = settable concurrency (default 4, max 6); 2.6 = batch + AWS + Esri thresholds.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized; feeds upload_queue.
- **"PROCEED TO UPDATE" mode + inline recap** — basic reprocess-selection works now (Patch O); richer UI is a re-author target if wanted, NOT a blocker.

---
## ⭐ COMMITTED WINS

**06-18:** FIT selection retention `35ccccc4a` · convoy "?" help `60db85131` · track arrows pixel-spacing+neon `d75572a1f`.
**06-19:** convoy universal search FAB `42dc848ce` · planning search FAB + all 3 old searches removed `583b7b9df`.
**06-20:** detail panel consolidation + Carto Type `37bc88431` · FAB + icon column `d57626f77` · FIT recenter `a43f80829`.
**06-21:** FIT final `27f493375` · arrows+touch `efc75abf4` · import/carto (M/O/Q/R) `791ed3b45` · docs rebuild `4c3541c4f`.
**06-22:** 4 fixes (viewport report on setView, trail popup properties, trail color regression, planning legend→KEY popup) `7c7009d48` · junk-file cleanup `df7635528` · `.gitignore` `f944dee25`.
**06-23:** drill-down manual + labeled map images shipped as in-app assets `a658d7a00` · area-search viewport-seed fix (all 4 artifact types draw — confirmed on device) `63ea797ab`.
(Detail-panel consolidation, map-tap→detail, WWA→FAB/icon-column — all DONE and committed; they were the open 06-19 START-HERE tasks.)

---

## 🗺️ AREA-SEARCH DRAW BUG — FIXED `63ea797ab` + the DURABLE two-role write (next)

**The bug (haunted the project; cracked + fixed 06-23):** universal AREA search repositioned the map via `setView` but never seeded `lastViewport*`, so the draw queried a STALE frame. Only Trails (first type processed, the one inside the old/empty frame) rendered; Tracks/Waypoints/Routes came back empty; Select All populated nothing; zoom/pan didn't recover; even reset didn't truly fix it.

**How it was proven (the method that worked):** read the persisted frame on device BEFORE and AFTER an area search — byte-for-byte identical (`MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json`). The frozen bbox was a ~300m pinhole (one artifact's FIT box), not a viewport. Force-close confirmed the JSON is DELETED on cold launch (not stale-across-sessions); the bad frame is written during the session and the area search never refreshes it.

**Historical root:** area search WORKED before it went universal (`583b7b9df`). The area bug and the FIT bug are the SAME bug — a reposition not seeding the draw frame. Fred had already found + fixed it in FIT (the seed at ConvoyScreen 665-666 / 1841 / 1846). AREA lost the equivalent when it went universal; nobody caught it until field-testing 06-23. **Area is Fred's critical test path** — no artifacts in NH, so every test starts with an area-search to Utah.

**The committed fix (`63ea797ab`, band-aid):** after the area `setView`, post (~550ms, to let the map settle) the standard `getBounds → Android.onViewportChanged` round-trip — the same seed every working reposition uses — so `lastViewport*` becomes the new searched frame. Confirmed on device: NH→Utah draws all four types.

**THE DURABLE FIX (do next — the "two-role write"):** make the persistent frame write happen on EVERY reposition for BOTH maps, via one mechanism, as TWO roles:
- **BEFORE the draw** — write/seed the bbox: it's the INPUT that DRIVES the query. Stale bbox → query resolves the old/empty container.
- **AFTER the draw** — persist the resolved snapshot: the query has resolved the NEW container (what's actually in view).
- There is no single write point because the snapshot needs built filters but can't gate on the (failing) draw — hence two roles. The failure lives in the HOLE when a path goes out-of-line; the fix keeps every reposition IN LINE through the same sequence.
- **First step is to READ the pipeline** (`onViewportChanged` → `processViewport` → `processArtifact`) and locate where the query takes the bbox (before-point) and where it resolves content (after-point); place the writes there; route area / FIT / gesture / cold-launch GPS-center / filter-change through it. Verify each with the device JSON read.

> Pipeline reference: JS `onViewportChanged(getNorth,getSouth,getEast,getWest,z)` → ConvoyScreen handler (575 unconditional draw / 769 gated on `lastMapProcessed`) → `SpatialDisplayManager.processViewport(s,w,n,e,zoom,states,selectLists,wv,ctx)` → loops 4 types → `processArtifact` (per-type identical; `queryXByViewport` → filter by checkedIds if SELECTED → `buildGeoJson` → `updateX()` + `showX()`). `SpatialDisplayManager` holds NO state (pure consumer of the bbox passed in). `saveConvoyState()` (ConvoyScreen 248) writes the FULL JSON snapshot (states + checked rows + bbox) from `lastViewport*` + the per-type vars. FIT seeds `lastViewport*` at 665-666 / 1841. Cold-launch GPS-center (670) does NOT seed `lastViewport*`. Area now seeds via the `[AREA FIX]` round-trip (UnifiedSearch ~114).

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
2. **Manual rewrite + screen captures DONE and SHIPPED** (`a658d7a00`) — full drill-down manual with real device screenshots ships in-app as `grouptrack_manual.html`; the AAB carries it. Fallback = complete release, features AND docs.
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

**STATUS 06-23:** Manual + release notes COMPLETE and SHIPPED into the build (`a658d7a00`) — full drill-down structure with real device screenshots (both maps, every launch point), compressed 85MB→3.2MB, replacing the old text-only cookbook. Screen-capture pass DONE. No further doc work before the AAB; the bundled manual matches what ships. (Big master files `_DRILLDOWN_` ~82MB + `_LIVE_` ~87MB to archive on Drive/G:.)

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
