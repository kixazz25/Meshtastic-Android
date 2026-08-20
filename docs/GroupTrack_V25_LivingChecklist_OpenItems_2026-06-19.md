# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-19
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `583b7b9df`

---

## 🟦 START HERE — TOMORROW (2026-06-20) — READ THIS FIRST, AT THE PROCESS LEVEL

**RULE FOR THE WHOLE SESSION (the lesson of 06-19):** stay at the PROCESS level, not in the weeds. Before ANY code edit on a UI element, produce and SHOW Fred a **launcher → composable truth table** built from the CURRENT files: for each thing the user taps (search FAB result, select/edit list row, map artifact-tap), which detail composable does it actually render? **No patch until that table exists and Fred has seen it.** 06-19 burned 6 builds + 2 gradle cleans patching a panel the launchers don't call, because this check was skipped. The check is a 5-second grep. Do it FIRST, every time.

**SESSION-START INPUTS (Fred provides):** runs recommit + uploads all xrefs (`field_crossref_raw`, `function_universe_raw`, `where_used_raw`, `navigation_xref`, `GroupTrack_AllDocs`) + current source files. **Re-pull CURRENT HEAD files before analyzing** — 06-19 analysis was partly done on a stale morning upload (a repeat of the same "verify first" failure). Files to re-pull: `ConvoyScreen.kt`, `ConvoyMapViewerScreen.kt`, `ArtifactListPanel.kt`, `ArtifactDetailPanel.kt`, `UnifiedSearch.kt`, plus the two map HTMLs (`convoy_map.html`, `grouptrack_map.html` — confirm names with `ls app/src/main/assets/*.html`).

### ✅ DONE 06-19 (committed): universal search
- `42dc848ce` convoy search FAB + `583b7b9df` planning search FAB + removed all 3 old search launch points. **Universal search COMPLETE, both maps, FAB-only.** This half of "clean up search + replace detail panels" is DONE.

### 🔴 NOT DONE — the other half: ONE detail panel, wired everywhere
**THE PROBLEM (Fred's exact words, verified against the June 17 record):** "Our goal was to make ONE detailed callable artifact panel and use it everywhere so we had one piece of code. We built a new detail panel, parametrized and callable as a function. We did all that work and NEVER REMOVED THE OLD FUNCTION and NEVER WIRED THE NEW FUNCTION IN PLACE."

There are **TWO detail panels** right now:
- **`ArtifactDetailPanel.kt`** — the standalone universal panel (the GOOD one; has FIT; got the CartoCode/Carto-Type work 06-19). Rendered at ConvoyScreen ~1754 + MapViewer ~1336, gated on `pendingDetailType/Id`. The **search FAB** routes here.
- **`ArtifactListPanel.kt`** — the select/edit list, which has its **OWN inline duplicate detail panel baked inside** (AlertDialog ~167-315 + `detailArtifactId` state + its own `DetailActionButton`). Rendered at ConvoyScreen ~1725 + MapViewer ~1203. The **select/edit list** shows THIS duplicate.
- This was DIAGNOSED June 17 (chat 23127897) — `onResultClick` set BOTH `pendingDetailId` AND `activeListType`, launching two surfaces. Fred's direction then: select → detail panel DIRECTLY, bypass `activeListType` (which belongs to Work-with-Artifacts filter select/deselect, NOT detail). **The June 17 wiring fix was never finished; 06-19 built CartoCode onto the unwired panel.**

**THE SOLUTION — two tasks, do NOT change what works (the panel content/FIT), only the wiring:**
- **TASK 1 — REMOVE THE OLD.** Delete the inline duplicate detail inside `ArtifactListPanel.kt` (AlertDialog ~167-315 + `detailArtifactId` state + its private `DetailActionButton` if unused elsewhere). The list keeps its list/checkbox/select-deselect job; it loses its own detail rendering.
- **TASK 2 — WIRE THE NEW.** Every artifact-select launcher invokes the ONE `ArtifactDetailPanel` (set `pendingDetailType/Id`; do NOT set `activeListType` on the detail path):
  - search FAB result-tap (already does this — confirm on fresh files)
  - select/edit list row-tap → add `onOpenDetail(type,id)` param to `ArtifactListPanel`, wired in parent to set `pendingDetailType/Id`
  - (next feature, TASK 3 below) map artifact-tap
  - BOTH convoy (`ConvoyScreen`) AND planning (`ConvoyMapViewerScreen`).
- **RESULT:** one panel, called everywhere, one piece of code = the original goal. Patch M's **Carto Type field** (already in `ArtifactDetailPanel`, UNCOMMITTED) then shows from every launcher automatically. **Verify the Carto Type row appears from BOTH the search FAB AND the select/edit list, on BOTH maps, before committing.**
- **Carto Type spec:** DETAILS-section row, value = TRANSLATED TEXT never the code (4→"OHV / Road-Concurrent" blue, 2→"Hiking & Biking" orange, 1→"Hiking-Only" yellow, 5→"Biking-Only" purple, none→"Unspecified" cyan). Cyan ONLY = unspecified. Only Utah trails carry carto_code; elsewhere cyan-by-design reads "Unspecified" (correct). Test a Utah carto trail to see a real color.

### 🔵 TASK 3 — MAP ARTIFACT-TAP → DETAIL PANEL (the original intended use; JS↔Kotlin)
Tapping an artifact ON THE MAP must open the ONE `ArtifactDetailPanel` (currently shows the OLD Leaflet popup — pure JS, never crosses to Kotlin).
- **Add a bridge method** `onArtifactTapped(type, id)` to the `@JavascriptInterface` object on BOTH maps (alongside existing `onMapTap`/`onMarkerTapped`/etc.) → sets `pendingDetailType/Id` → ArtifactDetailPanel.
- **In the map JS** (convoy_map.html + grouptrack_map.html): the trail/track/waypoint feature click handler (currently `bindPopup`/`onEachFeature`) calls `AndroidBridge.onArtifactTapped(type,id)` — the feature already carries type+id in its GeoJSON properties (used for CartoCode coloring).
- **GATE:** `onMapTap` is ALREADY route-building's vertex handler. A FEATURE click and an empty-map click fire separately in Leaflet, so feature→detail and empty-map→route-vertex coexist. **When route-build mode is ACTIVE, suppress artifact-detail-on-tap** (don't interrupt drawing). Paired edit: same change in both HTMLs.

### 🟢 TASK 4 — WORK-WITH-ARTIFACTS → FAB + accordion-close (removes the on-map WWA bar)
Convert the on-map "Work with Artifacts" panel BAR into a FAB in the right-edge icon column (search · artifacts · help).
- **FAB launches the panel ALREADY EXPANDED** (accordion open).
- **The accordion-collapse control becomes CLOSE** — dismisses the whole panel back to map + FAB (calls the panel's EXISTING `onDismiss`, `ConvoyArtifactsPanel.kt:64`).
- **Removes the always-on WWA bar from the map** (the `> WORK WITH ARTIFACTS [+ROUTE]` bar). Drag handle gone. Panel CONTENT (grid/rows/+ROUTE/import) unchanged.
- **SAME on BOTH convoy AND planning** — shared, not duplicated.

### Ordering for tomorrow
1. Session-start: recommit + xrefs + re-pull fresh files.
2. **Build the launcher→panel truth table, SHOW Fred. No code before this.**
3. TASK 1 (remove old inline detail) + TASK 2 (wire all launchers to the one panel) → one build → verify Carto Type shows from search FAB AND select/edit list on BOTH maps → commit.
4. TASK 3 (map artifact-tap → detail, JS bridge) → build → verify → commit.
5. TASK 4 (WWA → FAB + accordion-close, remove WWA bar, both maps) → build → verify → commit.
6. Then the rest of the V2.5 build sequence (queue+survey, etc.), manual + captures, AAB cut, lead-track rewrite LAST.

### Still-open big items carried from yesterday (full detail in their own sections below — DO NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — recovered settled design; MUST-SHIP; attempt LAST (after AAB banked). See "LEAD-CART CONVOY-TRACKING REBUILD" section + `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31) + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **Tile downloads — batch formats for performance** — V2.5 interim = settable concurrency (default 4, max 6, throttle guidance); 2.6 = batch transfer (.tpkx / PMTiles) + AWS staged/hosted + Esri thresholds (terms in Fred's recollection, recorded). See "TILE DOWNLOAD SPEED" section. App crashes past 3 concurrent → root-cause is 2.6.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized (extension db, enjoyment+ride_again); feeds upload_queue. See "TRACK SURVEY" section.
- **Manual + Release Notes** — see "EOD DOCS" + documentation sections below.

---

> **06-19 session: backlog recovery + trail-findability problem-framing + tile-transfer 2.6 capture (design/recovery, no code yet).** Audited the bare `[N.x]` backlog codes against AllDocs — most were DONE or stale. Reshaped the trail-selection items around the PROBLEM each solves. Captured the full tile-download-speed discussion: V2.5 interim = settable concurrency (default 4, max 6); 2.6 = batch transfer + AWS staged/hosted + Esri thresholds (these terms live only in Fred's recollection — now recorded). Retired the `section`-field spec (protects spatial design). Recovered: the pristine cookbook manual is the canonical base.

---

## ⭐ TODAY'S OUTCOME (2026-06-18) — THREE COMMITTED WINS; FIT SOLVED

A strong day that redeemed 06-17. The FIT-selection-retention bug that ended
yesterday unsolved is **fixed and committed** on both maps. Also shipped the
convoy "?" help button and the pixel-spacing/neon track arrows. Universal search
is fully designed (doc + mockup) and queued to build. The lead-cart tracking
rebuild — a settled design that had dropped off the checklist — was recovered and
is back on the scope.

### ✅ COMMITTED TODAY
- **FIT selection retention — `35ccccc4a`** (tested on device). FIT now emulates a
  manual row-select on the **live** in-memory vars: on detail-panel dismiss with a
  fitted (type, id), it sets that type's state to SELECTED and its checked-ids to
  the fitted id (the same thing a manual filter-tap does), then saves and fires the
  normal redraw. Because FIT = one artifact, it first clears **all four types to
  OFF**, then sets the fitted type SELECTED. This fixes both persistence (the
  `saveConvoyState` clobber) and the panel display together, since both read the
  live vars. Verified: JSON shows `Tracks state:2` + one fitted row, other types
  `state:0`, only the fitted artifact draws, SEL/Edit shows it selected. Both maps
  (planning uses `savePlanningState()` + its plain `webViewRef`).
- **Convoy "?" help button — `60db85131`** (tested). Ported the Planning Map's "?"
  help (button + chooser + full-screen doc viewer) to the Convoy Map. Both maps
  load the **same** `grouptrack_release_notes.html` / `grouptrack_manual.html`, so
  doc updates reach both. Button at right-edge center (CenterEnd), clear of REC
  (top-left), DirectionalN/zoom (top-center), and QUEUES (top-right).
- **Track arrows → pixel spacing + neon green — `d75572a1f`** (tested). Arrow
  spacing changed from 1/12-of-track-length to a fixed 80px on-screen spacing that
  re-spaces as you zoom, and color from black to neon green (#39FF14) to match the
  track. Fixes the real problem: a long track zoomed in used to show almost no
  arrows. Identical change in both map HTMLs.

### Commit chain today
`009b158aa` (06-17) → **`35ccccc4a`** (FIT) → **`60db85131`** ("?" help) →
**`d75572a1f`** (arrows, = HEAD).

---

## 🎯 KNOWN REMAINING V2.5 SCOPE — FINISH THIS LIST

Per Fred 06-18: **finish what is known** (do not reconstruct a list from scattered
docs). The known must-ship items:

1. **Universal search (magnifying-glass FAB)** — designed 06-18, build next. See
   `GroupTrack_UnifiedSearch_DESIGN_2026-06-18.md` + mockup. Self-contained shared
   `UnifiedSearch.kt` parameterized by `mapContext`; wiring + deletion of the three
   old search launch points; convoy gains Area mode. Build incrementally (convoy →
   planning → remove old launch points), commit each green step.
2. **Lead-cart convoy-tracking REBUILD [2.1]** — the recovered settled design (see
   below). Includes identifying and removing ALL previous track-recording methods
   and processes.
3. **Documentation pass** — help screen + user-manual rewrite (the structure is
   weeks old in places) + release-notes realignment + automated screen-capture for
   the cookbook how-to sections + general organize/cleanup. (Today's docs updated:
   release notes, manual, this checklist.)

**Moved back (low priority — internal testing):** [8.7] Play Store
attribution / About screen.

### V2.5 PLACEHOLDER PRE-FLIGHT (06-19) — no V2.5-screen placeholder may survive into the final manual pass
Recovered from navigation_xref. 3.0 account/cloud screens keep their placeholders (collapsed/hidden in the V2.5 manual — fine). **V2.5-screen placeholders to resolve:**
- `ConvoySearchByAreaScreen` "Coming in V2.5 / REQUIRES V2.5 MAP FUNCTIONS" → **resolved by universal search (step 1)** — the FAB's Area mode IS this function.
- `ConvoyQueuesPanel` "[ Pass 1 scaffold ]" → **resolved by the queue update (step 3)**.
- `ConvoyCompletedRideDetailScreen` survey → **the V2.5 STOP-flow survey [7.5] builds it** (3.0 ride-context survey stays deferred).
- `ConvoyRouteCreateScreen` "[ Pass 1 scaffold ]" → **REMOVE (dead orphan).** Route creation is the **+ROUTE button in Work with Artifacts** (on-map Route+ toolbar) — done. This standalone scaffold screen has no live entry point; remove the launcher + screen function together (reversibly, per dead-code methodology).
- `ConvoyTracksScreen` "Tracks — Phase C" → **KEEP — it's the shared-track UPLOAD worker, correctly deferred to 2.6/3.0.** Drains `upload_queue` (the share-Yes tracks from the survey) to AWS. **"Phase C" is correct:** V2.5 *collects* (survey + queue), 2.6/3.0 *uploads* (this worker). NOT a dead orphan, NOT a V2.5 build — exclude/hide in the V2.5 manual like the other deferred upload mechanisms. **Dedup rule (AllDocs 18888):** track identity = `(artifact_type, geom_hash, creation_date)` — same geometry + same day collapses to ONE (a 10-rider group ride contributes one shared track, not ten). This is why track aliases carry `creation_date` where trails don't.

**PRE-FLIGHT RESULT: no unresolved V2.5 placeholders remain.** Every nav placeholder is either built by planned V2.5 work (SearchByArea, QueuesPanel, survey), removed as dead (RouteCreate), or correctly deferred to 2.6/3.0 (Tracks upload worker + all the 3.0 account/cloud screens). Clean for the manual pass.

### Build sequence (Fred 06-19)
1. **Universal search — COMPLETE end-to-end before anything else.** Patch A create `UnifiedSearch.kt` → Patch B mount convoy (old search stays) → test 5 modes → mount planning → test → **REMOVE all 3 old search launch points** (convoy dead `locationSearch*`; planning area-search field+geocode; the artifact-search box `SearchBlock`+`ResultsList` in `ConvoyArtifactsPanel`, both maps) → test → commit each green. Search fully done (new FAB in, all old search out) before step 2.
2. **Work-with-Artifacts → tool FAB (the icon-navigation column).** Convert the on-map "Work with Artifacts" panel BAR into a FAB in the right-edge tool column (with search + "?"). The right edge becomes an **icon navigation column**: search · artifacts · help. **OPEN/CLOSE MODEL (Fred-confirmed 06-19):** the **map icon (FAB) launches Work-with-Artifacts with the accordion ALREADY OPEN (expanded)**; the **accordion-collapse control becomes CLOSE** (dismisses the whole panel back to map + FAB). So FAB = open-expanded, collapse-control = close. **Mechanism (small — mostly subtraction):** remove the always-on draggable bar + the drag handle; keep the expanded grid; the former chevron/title collapse calls the panel's EXISTING `onDismiss` param (already in the signature, ConvoyArtifactsPanel.kt:64). Panel CONTENT (grid/rows/+ROUTE/import) unchanged. **SAME implementation on BOTH convoy AND planning maps** — shared, not duplicated, so they can't drift. **NOTE:** after the search-box removal (583b7b9df), the panel currently opens/closes only via the accordion bar — this step replaces that with the FAB.
3. **Detail panel + CartoCode footer** — colored type-footer (carto color + type text) + schema-safe detail enhancements.
4. **Queue panel update** — settable simultaneous-transfer control (default 4 / max 6) + upload/download toggle (download active) + survey + upload_queue (one connected area).
5. **Replace artifact-click → detail panel** — wire map artifact-tap to the universal `ArtifactDetailPanel` (AFTER detail redesign).
6. **User manual completion + screen captures — before the AAB cut.** Only now are screens final: the icon-navigation column exists, artifacts is a FAB-launched expanded panel, search is a FAB. The whole "Work with Artifacts" manual section gets **rewritten around icon navigation** (it stops being "panel bar + accordion"). Capture final screens once. Manual must be DONE and BUNDLED in the AAB (it ships in-app). **THEN cut the 2.5 AAB (banked fallback) → THEN lead-track rewrite last.** See RELEASE STRATEGY section.

### SCREEN-CAPTURE WORKFLOW (Fred 06-19)
adb CAN capture screens (`adb exec-out screencap -p > file.png`). **Constraint:** Claude's sandbox has NO connection to Fred's machine/devices — **Claude cannot run adb or pull from the device.** Division of labor: Claude produces the **capture script + shot list** (which screen → which slot filename); Fred navigates to each screen and triggers the capture (only he can drive the app + run adb); Fred hands images back (upload/folder); Claude **inserts them into the manual** at the matching `[screenshot to be added]` slots and can annotate via PIL. Net: Claude scripts + directs + assembles; Fred executes the capture; the device stays on Fred's side of the wall.

---

## ⛔ LEAD-CART CONVOY-TRACKING REBUILD [2.1] — recovered settled design (MUST-SHIP)

> This is a significant, settled V2.5 design that had fallen off the active
> checklist. Authority doc: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31).
> It is NOT done. Carry it every session until it ships.

**The problem:** the current lead-cart tracking is a hodgepodge of evolved
lead-cart rules + position projection. It's unreliable — **phantom carts report in
when a rebroadcast is made** (other carts post positions that can't be explained).
Gut and restart, don't patch.

**The rebuild — one lead cart, one lead track:**
1. Track **only** the lead cart, from its broadcasts (not projection).
2. **Snap-2, 100-yard radius:** snap the lead's broadcast position onto known
   trail/track geometry when within 100 yards (corrects scatter onto real lines).
3. **Every other cart shows at its current position only — not tracked.** A live
   marker, no per-cart track line, no projected paths. (This kills the phantom-cart
   problem at the root.)
4. **Each cart tracks its own progress** and, as it overtakes the lead's positions,
   removes the lead's path from its own map and replaces it with its own device
   GPS, recorded every second (the same stream used for follow-now / GPX recording).

**Net result:** one continuous trail from the lead that improves in accuracy as
carts cover ground in the lead's wake. **Rationale:** the lead broadcasts at best
every 5 seconds (radio limit), so the trail *ahead* is inherently coarse; each cart
records its own GPS every second, so the trail *behind* ("the rear-view mirror") is
refined to 1-second truth as wheels physically cover the ground. The trail you
drive over is always better-known than the trail ahead — exactly right for a convoy.

**Open implementation detail (confirm before building):** when an overtaking cart
replaces the lead's path with its own GPS — is the replace unconditional (each cart
always owns its wake) or snap-gated (an off-trail cart beyond a threshold does not
overwrite, so a wild detour doesn't corrupt the composite)? An earlier note had an
off-trail guard.

### Planning doc to produce — DEMOLITION + REBUILD
Write a track-revision planning doc with two parts:
- **Part 1 — identify and remove ALL previous track-recording methods/processes.**
  Catalog every existing flow/file to rip out, with discovery steps to find them
  all: the three parallel flows (`leadTrackSegments` / `gpsTrailSegments` /
  `routeTrailSegments`), the live `drawTrack` path (~ConvoyScreen 345-350), the
  `trackLeadOnly` filter, the ConvoyEngine lead-lock/tick pieces
  (`evaluateLeadLock()`, `tick()→compute()→assignLeadTail()`, `lockedLeadNodeId`,
  `_leadLockedFlag`; note the known tick-oscillation), and any dead track paths.
- **Part 2 — the new method** (one lead / one track / snap-2 100yd / per-cart
  per-second GPS replacement). One growing lead-position polyline gated on
  `lockedLeadNodeId`; `pushTrackToMap` is net-new (0 refs). Discovery first:
  refreshed `field_crossref_raw.txt`, trace live, 2-cart field capture.

Demolition and rebuild traceable to each other — don't remove a flow without
knowing what takes its job.

> Note: do not confuse this lead-cart snap-2 (lead broadcast snapped to known
> trail geometry within 100 yd) with the ROUTE snap-2 already shipped (route
> drawing follows trail/track geometry between snapped vertices). Different
> features.

---

## ⛔ UNIVERSAL SEARCH ([2h.1]) — designed 06-18, build next

Design: `GroupTrack_UnifiedSearch_DESIGN_2026-06-18.md` + mockup
`GroupTrack_UnifiedSearch_mockup_2026-06-18.html`. (Supersedes the design portion
of the 06-16 handoff.)

- **Architecture:** self-contained shared `UnifiedSearch.kt` (not inlined in either
  screen), parameterized by `mapContext` ("convoy"/"planning") which routes
  behavior, plus webView + context + `onOpenDetail(type,id)`. Lift `ResultsList` /
  `ArtifactResult` (and maybe `SearchBlock`) out of `ConvoyArtifactsPanel.kt` into
  the shared file — common display/selector; only the on-select action differs.
  This is wiring + deletion, not new search logic.
- **Flow:** tap the magnifying-glass beacon (draggable, resets per session) → search
  bar with 5 type chips on one line (**Area · Track · Route · Trail · Waypoint**) +
  text field → Enter executes and closes the bar → results list (both Area and
  artifact may return multiple) → tap a result closes the list → Area recenters the
  map (setView + showSearchCenter); an artifact opens its detail card (where FIT
  lives). Duplicate names are numbered (#1/#2) via the existing name-sequence.
- **Convoy gains Area mode** (it lacked a place-positioner). All five modes on both
  maps.
- **Engines kept:** `searchByName` → name-sequence (convoy ~1356, planning ~870);
  `Geocoder.getFromLocationName` → setView + showSearchCenter (planning ~365-388;
  convoy needs the geocode call added); `ArtifactDetailPanel`. Both map HTMLs
  already define setView + showSearchCenter — no HTML change for search.
- **Launch points to remove:** (1) convoy dead `locationSearch*` vars (@268-270,
  declarations only); (2) planning area-search field + geocode handler (~345-405)
  and `searchText`; (3) the artifact-search box (`SearchBlock`+`ResultsList` mounts
  in `ConvoyArtifactsPanel`) — keep the panel's toggles/+ROUTE/import; lift
  `ResultsList`, don't delete it.
- **Build incrementally:** UnifiedSearch.kt + mount convoy (old search still in
  place) → test 5 modes → mount planning → test → remove the 3 old launch points →
  test both. Commit each green step.

---

## 🚢 RELEASE STRATEGY (Fred 06-19) — AAB as fallback, lead-track rewrite LAST

> **Sequence the highest-risk change behind a banked, complete release.** The lead-track rewrite [2.1] is a demolition+rebuild (riskiest item: gut 3 flows + the lead-lock/tick engine, open wake-replacement question, known tick-oscillation, needs 2-cart field capture). Do NOT let it gate the whole release.

**Plan:**
1. Complete ALL other V2.5 work — search, artifacts-FAB icon column, detail+CartoCode, queue+survey, artifact-click→detail, remove RouteCreate orphan.
2. **Manual rewrite + screen captures DONE and BUNDLED** (the manual ships in-app as `grouptrack_manual.html`; the AAB must carry the finished manual). WWA section rewritten around icon navigation; screens are final because all feature work above has landed. **Fred 06-19: manual must be in the AAB — the fallback is a complete release, features AND docs, not release-minus-manual.**
3. **CUT THE 2.5 AAB HERE** — complete 2.5 (all features + finished manual), WITHOUT the lead-track rewrite (old track methods still in place, no snap-2 on tracks). **This AAB is the banked FALLBACK.**
4. **Lead-track rewrite [2.1] — attempt LAST, on top of the banked AAB.** Clean → folds into 2.5. Hairy → **defer just that piece to 2.6 and ship the banked AAB.** Fred's preference: land it in 2.5 if it goes well; the AAB exists so the rewrite is pure upside with zero downside to the release.

**Why this works:** the banked AAB makes the rewrite a no-downside attempt — failure just means ship the AAB + defer to 2.6, no scramble, no half-finished state in the release branch. Highest-risk change behind a known-good complete release.

**Manual across the AAB boundary (Fred 06-19 — manageable, not a rebuild):** the bundled manual documents the app AS THE AAB SHIPS (old track methods, since the rewrite isn't in the AAB) — so it's never wrong relative to what shipped. IF the lead-track rewrite later lands, it changes only the **Settings track-parameter fields** (remove old method's controls e.g. "Lead Cart Only"/"Multicolor Track" as they evolve, insert new method's) — a small, contained **edit-in-place to one screen's field list + possibly one re-capture of the Settings screen.** NOT a structural rewrite. The cookbook structure + edit-in-place discipline keep this a surgical touch-up. If the rewrite defers to 2.6, the 2.5 bundled manual already matches the shipped AAB exactly — no 2.5 manual edit needed.

---

## 🎯 TRACK SURVEY ON STOP [7.5] — recovered settled spec, V2.5 collect-now (06-19)

> **Fred 06-19: build this — start collecting trail-rating data ASAP with the field testers.** Fully specced in AllDocs (not net-new): `track_surveys` schema + `upload_queue` + collect-now/upload-later all designed. Listed repeatedly as "OPEN — unshipped 2.5 feature" ([7.5]).

**THE GOAL:** start accumulating real trail-rating data from V2.5 testers NOW, held locally, so it's ready to populate the 3.0 app's trail ratings when the cloud pipe exists. The 18 testers riding real trails become the initial 3.0 rating dataset instead of starting cold at 3.0 launch.

**TRIGGER (RideState-driven, AllDocs 23036/3203):** on STOP after recording, two dialog paths by RideState:
- **ORGANIZED** (real ride) → full **survey + save** dialog.
- **SOLO / CONVOY** → simple **name-only** dialog.
(Survey is part of the track-save-on-STOP flow, owned by TrackManager — not a separate screen.)

**THE SURVEY UI (AllDocs 21602):** SAVE TRACK (name, shows distance/duration) → **SHARE? Y/N** → **RATE THIS RIDE 1-5** → **RIDE AGAIN? Y/N** → DONE.

**WRITES (schema FINALIZED in the 2026-05-19 schema-extension session, lives in the EXTENSION db `grouptrack_data.db` — NOT the spatial db):**
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
Share **Yes** → `shared=1` + an **`upload_queue`** entry. **ARCHITECTURE RULE (Fred, 05-19):** spatial tables stay pure OGC (geometry+identity+timestamps, no FKs/custom columns); surveys/properties/aliases/queues live in the extension db, relationships synced via triggers. **SUPERSEDES the earlier 3/26 difficulty/scenery/fun 3-question variant — the FINAL survey is `enjoyment 1-5` + `ride_again Y/N` only. Do NOT build the difficulty/scenery/fun version.**

**COLLECT-NOW ARCHITECTURE (AllDocs 21640 — this IS the deferred-collection design):** **`upload_queue` — V2.5: collect only. V2.6/3.0: processes.** Share-Yes entries accumulate locally in `upload_queue` in V2.5; the queue processor (`ConvoySurveyUploader`) drains them to the AWS survey store in 2.6/3.0. Held locally now, uploaded when the pipe exists.

**3.0 PAYOFF (specced, AllDocs 22969-22970):** aggregates to trail "Difficulty score (Easy=1/Mod=2/Hard=3, averaged across surveys)" + "Would-ride-again % = COUNT(recommend=Yes)/COUNT(*)." The V2.5-collected surveys become the 3.0 trail-rating dataset.

**CONNECTED TO QUEUE WORK (step 3):** the `upload_queue` is one of the three queues (tile/upload/download); the "Work with Queues" spec (AllDocs 21630) shows an ALL/TILE/UPLOAD/DOWNLOAD filter with "Upload Queue — V2.5 collect only" — which IS the upload/download toggle from step 3. **Survey + upload_queue + the queue-panel toggle are one connected area** — build them together. *(Resolves the `ConvoyCompletedRideDetailScreen` survey placeholder for the V2.5 STOP-flow version; the 3.0 ride-context survey stays deferred.)*

---

## 🎯 TRAIL FINDABILITY — selecting the right trail among hundreds of same-named segments (recovered/clarified 06-19)

> **These items are framed by the PROBLEM they solve. Method evolves as we discuss — the problem is the durable record.** This is one connected workflow, not loose ideas.

**THE CORE PROBLEM.** Real trail data has huge same-name density — "Jordan River Trail" alone is **314 distinct segments** (verified; each a unique geometry, NOT a dupe — dedup via `UNIQUE(geom_hash)` is working correctly). A rider trying to display/select the trail they want faces hundreds of identically-named entries and a map of indistinguishable lines, and can't reliably (a) tell the segments apart, (b) reach all of them, or (c) know whether they're even seeing all of them. Each sub-item below is one facet of this.

- **Blank/featureless startup map — riders see nothing and don't know why.** On launch / wide zoom, the artifact limit is so exhausted that nothing renders — the map looks broken/empty with no explanation. PROBLEM: the user can't tell "nothing here" from "too much to show." NEEDED: **below z11, the map must say "zoom in to see artifact info"** (and the over-limit message below), so an empty-looking map is self-explaining instead of looking broken. *(Method: state-driven message off viewport zoom/count; evolving.)*

- **Silent truncation at the display cap — riders miss segments without knowing.** With the cap at 200, ~36% of Jordan River's 314 segments are silently dropped from the SEL/Edit list; the rider has no idea they're not seeing everything. NEEDED: (1) **raise the cap 200 → 400** so dense same-named sets are reachable; (2) when the cap is hit, an explicit message: **"Maximum artifacts reached for this map area. Zoom in to ensure you have all artifacts that belong on the map."** No silent truncation, ever. *(A settled spec exists for the message — AllDocs ~19843, state-driven off the existing viewport count, no new query; this refines the number to 400 and the wording. The real long-term fix behind the cap is paging [11.1]; 400 is the now-fix.)*

- **"Why does this trail appear so many times?" — the recurring rider question, asked at the SELECT LIST.** PROBLEM: same-named segments look like duplicates to the user; they don't trust the list. NEEDED: on the **select-list row**, surface the **unique geom-hash** as a plain indicator that says *"this is a unique trail segment, period."* The hash is already on the row — just show it. **DECISION (06-19, supersedes the AllDocs `section`-field spec ~19829-19834):** do NOT add a `section`/numbering field — that compromises the spatial data design (identity is `UNIQUE(geom_hash)`, mirrored to AWS in 2.6). Showing the existing hash answers the question with zero schema change. The earlier "assign section at import via trigger" plan is **retired**; reason it was never built is exactly this schema-cost-vs-benefit mismatch.

- **Can't tell map lines apart — no trail name positioners on the map.** PROBLEM: a dense viewport is a wall of indistinguishable lines; the rider can't see which line is which trail. NEEDED: **trail name positioners/labels on the map** (needed even at 200). *(This is the one piece with real cost — RECOVERY 06-19 confirmed trail labels are NOT drawn today at all; this is net-new Leaflet labeling in BOTH map HTMLs, on the hot draw path, with a known performance concern at hundreds of labels. Needs its own design pass; not a quick tweak. The other items here are bounded/low-risk.)*

- **The detail panel IS the disambiguation+selection tool — and it must be the ONE universal detail function.** With 400 trails/segments in play, the user picks the right one by opening its **detail** then **FIT**-ing to it. PRINCIPLE: there is ONE shared `ArtifactDetailPanel` (owns FIT), reached identically from every entry point — select-list row-tap, search result, and **map artifact-tap/popup**. Not per-caller detail variants. The "map-popup → detail" item (below) is just another entrance to this same universal panel. *(Open: replace the on-map popup entirely with tap→detail, vs. keep popup as a launcher → detail. Decide in design.)*

- **Detail-panel content enhancements (schema-safe, bounded to `ArtifactDetailPanel`).** While the detail panel is the planning surface, surface what a planner needs on it: **trail type via CartoCode** (color the panel + bold type footer — per-trail, honest, no trail-line redraw); **trail/segment length** (`distance_miles` may already be a stored property — verify; else derive from geometry at open); and the **unique-segment hash indicator** (above). All read-only, no schema change. *(CartoCode line coloring on the MAP — recoloring trail lines by type — is a separate, heavier 2.6 question and is PARKED for the 2.6 discussion; only the detail-panel color/footer is in scope here.)*

---

## ⏳ DEFERRED — separate items, do NOT combine

- **Map-popup / artifact-tap → detail** — tap an artifact on the map to open the
  standalone `ArtifactDetailPanel` (which owns FIT). Needs a JS click handler on the
  artifact in BOTH map HTMLs + an Android `@JavascriptInterface` bridge to open
  detail with (type, id), passing mapKey + webView. Own item (touches both HTMLs).
- **Shared-JS consolidation** — dedupe `setView` / `showSearchCenter` (and other
  shared map functions) out of the two HTMLs into a shared local JS file (precedent:
  both already include `leaflet.polylineDecorator.js` via `<script src>`).
  **Blast-radius caution:** grep alone cannot bound this — `setView` grep conflates
  the wrapper function with native Leaflet `map.setView([...])` calls and with the
  Kotlin `evaluateJavascript("setView(...)")` string calls. JS has no compiler
  safety net. Regenerate a fresh cross-reference first, grep both languages, and
  device-test. Not an end-of-day "while we're here."
- **Detail-extraction cleanup** — `ArtifactListPanel` still embeds its own detail
  for the sel/edit row-tap; route that row-tap to the shared `ArtifactDetailPanel`
  too. Implement the `ConvoyArtifactOps` Pass-1 log stubs (rename / delete / toRoute
  / toTrack / upload / download / changeType / editPoints / addAlias / setTrailhead);
  `fit()` is the only real op today.

---

## OTHER OPEN (backlog / 2.6) — RECOVERED & TRIAGED 06-19

> Backlog audited against Fred's recall + AllDocs 06-19. Bare `[N.x]` codes resolved to real status. Most of the old list was either DONE or stale.

**Still genuinely open:**
- **[6.2] Remove leftover geojson asset + JS-injection code.** PROBLEM: distributed trail content was cut from 27MB to 2 trails to shrink the distro, but the geojson asset and the JS-injection code that loads it were never removed — dead weight still in the build. NEEDED: remove both the asset and the injection code. *(Also the root of the stale CartoCode legend in the manual — connected.)*
- **[3.3] Queue panel — restore upload placeholder + add upload/download activity selector.** PROBLEM: the AWS-upload placeholder was removed; the button is Upload/Download but there's no way to say which activity you're doing. NEEDED: put the upload placeholder back, and add a **selector at the top of the panel** specifying upload vs. download. *(Queue backend — hold/resume/cancel — is done; this is the panel UI.)*
- **TILE DOWNLOAD SPEED — split V2.5 interim / 2.6 redesign (discussed 06-19).**

  **V2.5 interim (executable now):** replace the hardcoded 2-at-a-time queue cap with a **user-settable max concurrent transfers** — **default 4**, range **up to 6**, user can throttle down. Single settable value + a Settings control. **Queue-panel guidance (user-facing):** transfer speed depends on the user's network; **if transfers fail, advise lowering the simultaneous-transfer count until failures/crashes stop.** This makes the throttle the user's remedy for failures, and sets the expectation that the right number is network-dependent. *(NOTE: prior field observation was a CRASH past 3 concurrent. Default 4 / max 6 sit above that line — the user throttle + the failure guidance serve as mitigation; the crash root-cause is deferred to 2.6. If 4 proves unstable in test, fall back to 3 as the safe default.)*

  **2.6 full redesign (batch transfer + AWS staged/hosted — captured so it stops living only in Fred's head; do NOT re-derive from scratch):**
  - **Batch tile transfer** — Esri batch/bundle instead of per-tile `{z}/{x}/{y}` fetching (current: per-tile, OkHttp 10s/15s, no caching). Candidate formats: **.tpkx** (Esri-native Map Tile Package) and **PMTiles** (open single-file, HTTP-range-readable). Goal: collapse hundreds of per-tile requests into few bundle transfers → faster + stable (sidesteps the concurrency crash entirely) + threshold-economical. *(Needs an on-device unpacker into the Leaflet `{z}/{x}/{y}` cache; .tpkx readable via GDAL `esric` driver as reference.)*
  - **AWS staged/hosted design** — AllDocs spec (lines ~3149-3160, 3334): EC2 caches tiles by bbox (30-day TTL), pre-assembles a **tile_manifest** per area (`map_status=READY`), two-tier dedup (manifest − local_cache = delta), **parallel delta pull (4 threads)** from AWS. First-occurrence-wins: Esri is hit once per tile, then served from your own server — protects the Esri threshold. EC2 also merges trail geometry (ArcGIS + USFS) into one GeoJSON by bound_hash.
  - **Esri developer account / thresholds** — Fred registered a **FREE Esri developer account** with **API-level service access and specific monthly thresholds**. The account-tier / threshold-vs-volume choice was deliberately deferred pending the transfer-method design. **Open: metering model** — per-tile transaction vs. batch-export/credit — which determines whether batch transfer also preserves the threshold (likely) and which tier fits real volume. **These account terms/options live only in Fred's recollection — capture as he provides; do NOT reconstruct.** Esri dev account headline: ~2M tiles/mo free tier referenced earlier; key config deferred until transfer method framed.
  - **Download-crash-past-3-concurrent (stability bug)** — root-cause in `ConvoyTileDownloader` concurrent path (likely resource exhaustion: connection pool / buffering / file handles / coroutine pressure). Gates safe high concurrency for ANY approach; understand before the batch build.
  - **Map Manager SCREEN spec** — the April-1 "Map Manager — Complete Specification" in AllDocs (~line 1920) is the screen architecture, PARTLY superseded by the shipped Convoy/Planning split + Work-with-Artifacts. The V3.0 Map Functions doc (`grouptrack_v3_map_functions.html`) carries the forward map-function definitions (AWS coverage display, route planner, download region draw). Reconcile both when building 2.6.
- **Blank trail-name in FIT's JSON row** — id is correct and selection is id-based, so it works; the name writes `""` (trails get names, tracks don't — narrow track-name-lookup issue). Cosmetic, parked.
- **[1.2] sliceLine whole-trail — VERIFY OBSOLETE.** The trail-source / trail-type / route-capture area was rewritten; Fred is unsure this still applies. Do NOT carry as active — confirm dead (quick xref check on `sliceLine` callers) and remove, or re-scope if it surfaces.

**DONE (recovered 06-19 — remove from open):**
- ~~[4.x] import trails from external sources~~ — DONE.
- ~~[10.1] BLE~~ — DONE. Was the timeout fix: pause recording + disconnect device cleanly before BT times out (which previously required a device power-cycle to reconnect); driving away triggers reconnect.
- ~~Gaia/onX standalone parity~~ — DONE (distance traveled, speed, waypoint adding, etc. all completed).

**Fold into Work with Artifacts (not standalone items):**
- Track Display Selector (filter tabs) — part of Work with Artifacts.
- Track importer — part of Work with Artifacts.

**Still backlog (unchanged):** [11.1] paging (the real fix behind the artifact cap); Map Manager screen items not yet realized. *(From the 05-07 backlog.)*

**PARKED direction (not active — let it lie):** icon-navigation map surface — more on-map chrome could collapse into the right-edge FAB column (QUEUES, NET are small real-estate-only candidates). No urgency; revisit after search + artifacts-FAB land. **REC stays visible** (live ride control, must be fast mid-ride — not a collapse candidate). Source selector (SAT/TOPO/TOPO+) is a judgment call (adds a tap per layer switch). Capture only; do not pursue now.

**Tree cleanup:** remove stray files — `.bak_*` files accumulating from 06-18 patches, `ConvoyScreen.kt.bak_move`, `utah_trails_stgeorge.geojson.bak`; never commit `grouptrack_spatial.db` (117MB).

**DEFERRED (later releases):** GeoPackage national-trail architecture, V3.0, paywall.

---

## ✅ DONE (removed from open)

- Bundle config (versionCode / signing) ✓
- [1.5] auto-save + terminate on map-switch — this is the persistence work, ✓
- FIT (`35ccccc4a`) ✓ · convoy "?" help (`60db85131`) ✓ · [3.9a] arrows
  (`d75572a1f`) ✓
- Search → detail separation (`009b158aa`, 06-17) ✓

## ❌ CUT FROM SCOPE (Fred 06-18)

- [3.1b] Planning GPS-recenter button.
- convoy_map.html `drawTrack` / `clearMarkers` "not defined" — harmless JS
  load-race log noise; displayed DB tracks use `loadTracks` / `trackLayer` (which
  work), and the erroring `drawTrack` is the live lead trail. Not a real bug.

---

## DESIGN CONTEXT (carried forward)

- **Two draw paths are BY DESIGN.** (A) `drawPersistedState` = saved/restore from
  JSON (golden for saved state). (B) the onViewportChanged path = in-memory live
  vars, preserving user selections across zoom/pan. New actions (like FIT) update
  the LIVE side via the existing select mechanism, not bypass or collapse it.
- **Map-purpose model:** Convoy = live/location (GPS, proximity, session-only).
  Planning = deliberate/identity (name search, fit, persisted frame across launches).
- **Reusability principle:** own behavior in the SHARED component; callers pass DATA
  not BEHAVIOR (e.g. `mapContext` routes it). The convoy↔planning duplication is the
  underlying pain — shared components cure it. This is why search is a shared
  `UnifiedSearch.kt` and why FIT joins the existing select mechanism rather than
  forking a new path.
- **Boy-scout cleanup:** clean up what you touch, but only when you can see the
  blast radius — Kotlin (grep + compiler + xref) is bounded; JS (grep only, no
  compiler, silent runtime failure) is not, so be conservative and device-test.

---

## PROCESS NOTE — settled designs keep getting lost

Recurring problem: settled designs (with their own spec docs) fall off the active
checklist because each EOD doc is rewritten fresh and dormant items aren't re-typed.
The lead-cart rebuild [2.1] is the latest example — recovered 06-18. The mitigation
is two-layer: this checklist + the persistent memory file carry the open items
(append, don't silently drop), and the doc folder holds the full specs. When a
settled design is made, it goes on the list the same day. Before declaring any task
"doesn't exist," search the record first.

---

## 📖 DOCUMENTATION WORK REMAINING (user manual + release notes) — detailed

**Sequencing rule:** documentation is done LAST among feature work, AFTER all screens are final (search FAB, artifacts icon-nav, one detail panel + Carto Type, queue), and BEFORE the AAB cut (the manual ships in-app, must be in the AAB). See RELEASE STRATEGY.

**USER MANUAL:**
- **Canonical base = `app/src/main/assets/grouptrack_manual.html`** (the pristine cookbook, modified 06-07, never git-committed so untouched by same-name overwrites). 41 screen cards, all 40 nav destinations, Reached-from / What-you-do / Leads-to format, 3.0-vs-V2.5 marking, `[screenshot to be added]` slots. Drive copy `grouptrack_manual_PRISTINE_BASE_recovered_2026-06-18.html`. **RULE: edit IN PLACE — never rebuild from a thinner variant.** The 4-section "06-17/06-18" variants are the DRIFTED branch; do not merge.
- **Edits needed (in place):** (1) **Rewrite the "Work with Artifacts" section around ICON NAVIGATION** — it stops being "panel bar + accordion," becomes "tap the artifacts FAB → panel opens expanded → accordion-collapse closes it." (2) Add the **universal search FAB** (Area + 4 artifact modes, both maps). (3) Add the **one detail panel** + the **Carto Type** field. (4) Document **map artifact-tap → detail**. (5) Update the **CartoCode legend** (stale since JS-injected trail sources dropped, [6.2]). (6) Fold in features newer than 06-07 (FIT, "?" help, pixel arrows, snap-2 routes, persistence). (7) Hide/collapse 3.0 account/cloud screens.
- **Screen captures (workflow proven 06-19):** adb `exec-out screencap -p > /d/grouptrack_screenshots/NNN_name.png` (Droid 1). Claude builds a capture-companion manual with embedded `mkdir` + per-slot adb commands (Claude owns filenames=slot names, 3.0 screens collapsed); Fred navigates each screen + runs the embedded command; Fred zips + uploads; Claude inserts into the real manual by filename + annotates via PIL. **Claude CANNOT run adb — Fred runs every capture command.** Build the capture-companion manual AFTER all code lands (screens final), then capture once.

**RELEASE NOTES / NEW RELEASE ANNOUNCEMENT:**
- Base = `app/src/main/assets/grouptrack_release_notes.html` (ships in-app alongside the manual; the "?" help button opens these).
- **Add for V2.5:** universal search (both maps), one unified artifact detail panel with Carto Type display, map artifact-tap → detail, Work-with-Artifacts icon navigation, track survey collection (rate + share), settable transfer concurrency, FIT, "?" help, track directional arrows, persistence, snap-2 routes.
- Realign so the in-app release notes match what the AAB actually ships (no lead-track rewrite if deferred).
- Must be bundled in the AAB (ships in-app).

**Both docs republished each session as downloadable artifacts** (Drive connector intermittent — Fred collects EOD into G: drive, runs recommit to pull into repo `docs/`). Pull each forward from prior version, edit surgically, never shorter.

## TREE STATE

- **Committed HEAD `42dc848ce`** — "feat: unified search FAB on convoy map (Area + 4 artifact modes), icon column above help" (UnifiedSearch.kt +274, ConvoyScreen.kt +15; 2 files, 289 insertions). **Convoy universal search DONE & committed green** — Area mode (new convoy place-positioner) + Track/Route/Trail/Waypoint name search → detail → FIT, FAB in the right-edge icon column above "?". Old search still present (removed later, all 3 launch points together). Device-tested: modes 2-5 perfect; spacing fixed (Patch C folded in). 48 commits ahead of origin.
- Chain: `d75572a1f` (arrows) → `60db85131` ("?" help) → … → `42dc848ce` (convoy search FAB) → **`583b7b9df`** (planning search FAB + ALL 3 old searches removed; stackDown; 4 files, +169/-233). **UNIVERSAL SEARCH COMPLETE end-to-end** — one shared UnifiedSearch.kt, both maps, FAB-only, old chrome gone. HEAD.
- **IN PROGRESS at session end (2026-06-19):** CartoCode footer (Patch J) APPLIED to ArtifactDetailPanel.kt (git status = modified, NOT yet committed). A CLEAN build was running at exit to get it into the APK — two prior incremental builds came out STALE (same 12:25 APK timestamp, footer never compiled in), so `./gradlew clean` + rebuild was started. **RESUME TOMORROW:** (1) confirm the clean build's APK timestamp is current, install, open a trail WITH a carto_code (one that draws a non-cyan colored line on the map) → the colored CartoCode band should show at the panel bottom. Trails with null carto_code (cyan lines) correctly show NO band; tracks/waypoints/routes show no band. (2) If it shows correctly → commit Patch J. (3) If a clearly-colored trail STILL shows no band after a confirmed-fresh APK → real footer bug to debug.
- **CARTOCODE FALSE ALARM RESOLVED:** the panel was NEVER blank/broken — it shows the standard trail detail fine. The footer just wasn’t in the running build (stale APK). carto_code IS populated (it lives on the SPATIAL trails table via v4 migration `ALTER TABLE trails ADD COLUMN carto_code`, SpatialDbManager.kt:154; read for map color at :269 and detail at getArtifactDetail :864 via SELECT *). getArtifactDetail returns carto_code in detailFields under key `carto_code`. Footer logic verified structurally (braces/parens balanced 90/90, 210/210). NOT a data problem, NOT a loader problem — was purely a stale-build problem.
- **NEXT after CartoCode commits:** attach detail panel to MAP ARTIFACT-TAP (replace the old Leaflet spatial popup with ArtifactDetailPanel) — the "second entrance," its own item. Then (step 2) artifacts panel → map FAB icon column. **KNOWN:** artifacts panel open/close currently only via the accordion bar (after search-box removal 583b7b9df) — becomes the FAB in step 2 (FAB launches expanded; accordion-collapse → CLOSE via existing onDismiss; both maps).
- Parked (never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`,
  `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? *.bak_*`,
  `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db`. Commit only named
  files. Never `git add .`. **Tree-cleanup pending:** sweep the `.bak_*` files + `docs/.tmp.driveupload/10630`.

## DEVICE / BUILD QUICK-REF

- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~14–42 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 = `8624SBCEDF00001789` field/real-GPS · Droid 2 = `24039703201775` dev)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json` (or `planning_panel.json`)
- Logcat (non-blocking dump): `adb -s 8624SBCEDF00001789 logcat -d -s <tags> | tail -N` (`-c` to clear). LIVE logcat BLOCKS — use `-d` dump or a 2nd window.
- **LINE ENDINGS:** .kt files MIXED CRLF/LF, even within one file. Patches detect newline at runtime + count==1 guard.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 /c/Users/kixaz/Downloads/<name>.py`.
- Revert one file: `git checkout <hash> -- <file>` — **run `git diff <hash> -- <file>` FIRST to know the scope.** NO sqlite3 on device.

## EOD DOCS — status

- Release notes → updated to 06-18 (`grouptrack_release_notes_2026-06-18.html`): FIT, convoy "?" help, arrows.
- User manual → updated to 06-18 (`GroupTrack_V25_UserManual_2026-06-18.html`): FIT documented, convoy "?" help, arrows note.
- This checklist → **06-19** (this file, regenerated through today's session).
- **2026-06-19 SESSION SUMMARY (code milestones):** committed `42dc848ce` (convoy universal search FAB) + `583b7b9df` (planning search FAB + removed all 3 old search launch points; stackDown param). **UNIVERSAL SEARCH COMPLETE end-to-end, both maps, FAB-only.** CartoCode footer (Patch J) applied to ArtifactDetailPanel.kt, UNCOMMITTED, clean build running at exit (see TREE STATE resume note). Heavy design/recovery: survey finalized, dedup rule, tile-transfer split, release strategy (AAB fallback), placeholder pre-flight complete, screen-capture pipeline proven end-to-end. HEAD `583b7b9df`, 49 commits ahead of origin (+ uncommitted Patch J).
- Track-revision demolition+rebuild planning doc → written (`GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`).
- **MANUAL — pristine cookbook RECOVERED (06-18 PM).** The canonical manual is `app/src/main/assets/grouptrack_manual.html` (last modified 06-07, never git-committed = untouched by the same-name overwrites that destroyed the dated copies). It is the full screen-by-screen cookbook: 41 screen cards covering all 40 nav destinations, Reached-from / What-you-do / Leads-to format, 3.0-vs-V2.5 marking, search box, `[screenshot to be added]` slots, feature-status appendix. Saved to Drive as `grouptrack_manual_PRISTINE_BASE_recovered_2026-06-18.html`. **RULE: edit THIS in place — never rebuild from a thinner version.** The 4-section "06-17/06-18" manual variants are the DRIFTED branch; do not merge them. The navigation_xref was purpose-built to drive this cookbook — they're a matched pair; consult the xref to keep the cookbook honest against real nav.
- **Manual reconciliation needed (next manual pass, edits-in-place):** update the **CartoCode legend** (stale since the JS-injected trail sources were dropped — see [6.2]); fold in features newer than 06-07 (FIT, convoy "?" help, pixel arrows, snap-2 routes, persistence) as edits to existing cards; optionally re-order toward rider-first; then capture into the existing `[screenshot to be added]` slots.
- **Still to do (documentation pass):** the manual reconciliation above; cookbook screen-capture (Fred captures via scrcpy/adb into the existing slots; Claude can annotate via PIL); broader doc organize/cleanup.
