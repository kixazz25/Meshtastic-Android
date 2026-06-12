# GroupTrack V2.5 — Living Checklist (CONSOLIDATED, full problem definitions) — 2026-06-11

> **2026-06-11 update:** committed + device-approved — route-draft picker refresh fix (`03c9e1a56`), cleanup batch `bcd5f8e31` (**8.5/8.8** lint gate + **1.7** picker text + **3.5** dead drag vars), **3.8** waypoint teardrop pins, **3.9** track direction arrows (tracks reliable; arrow redraw-timing follow-up). Also reported complete: **3.4** waypoint-drop, **4.6** track-import over-capture, **8.1** + **8.2** the two launch-blocking ANRs, **8.6** versionCode mechanism. Remaining pre-AAB gates: **8.7** About/Attribution, **8.9** first-launch release-notes gate. Next focus: **[2h]** artifact-detail panel + search.

_Branch feature/convoy-event-ride · Device = Droid 2 24039703201775, release APK · Builds are RELEASE builds (assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease)._

> **What this is.** A single complete problem list for finishing V2.5 — every UNRESOLVED open item pulled together from all sources, each with a detailed problem definition (not a one-liner), grouped by code area. These are KNOWN ISSUES TO RESOLVE. A formal ACCEPTANCE checklist (the walk-through that certifies the finished product) comes AFTER these are resolved — it is not this document.
>
> **No-drop pass.** Consolidated from: 06-06 v3 LivingChecklist (current spine), v25_master_checklist.md (sections A–M), 06-06 DecisionLog, GroupTrack_LeadTrackReplacement_Spec, PLANNING_V25_Backlog, V30_StubInventory, the RouteToolbar/Snap2/RouteBuilder cleanup series, the 06-04/06-07 state-of-play + handoff docs, and the 06-10 session. Each item is SOURCE-TAGGED in (parentheses) so Fred can trace it during line-by-line review.
>
> **Status legend:** OPEN = not done · PARTIAL = partly wired · VERIFY = confirm against live source/device · DONE = confirmed (kept briefly for context, struck where closed) · DEFERRED = captured, explicitly not active V2.5 work.

---

# ⚡ TRIAGE HEADER — read first (the quick-shrink pass)

> Purpose: knock out the cheap items in a batch and collapse the overlaps, before touching functional work. Tags are minimal-effort calls, not final — confirm during review.

## A. COSMETIC / MINIMAL-SCOPE (small focus, label/text/icon/placement — batch these, shrink the list fast)
- **1.7** Route cosmetics — empty picker text "No in-progress routes yet"; fix "name is taken" vs "name required" message. (text only)
- **3.5** Convoy `?` placement (CONTACT-LOST help icon) — move out of hasLost, beside QUEUES. (move one working block — genuinely cosmetic)
  - NOTE: this is the CONVOY-MAP contact-lost `?` only. The OTHER `?` — the help/manual PANEL entry point — is NOT cosmetic; it's the UI-revision half of the grouped functional task [9.4]. Don't confuse the two.
  - NOTE: QUEUES button [3.3] is FUNCTIONAL (dead button, needs panel wiring ported), NOT a placement fix — even though it sits in the same corner as 3.5.
- **3.8** Waypoint marker shape — triangle vs round pin. (decision + one value)
- **3.9** Direction arrows on lines. (display add — small, but real draw code; verify before assuming trivial)
- **6.6** Duplicate AlertDialog import — delete one line.
- **6.7** !!/safe-call warning tidy.
- **8.4** Strip diagnostic logs (3 sites).
- **8.5** SpecifyForegroundServiceType lint — one manifest attr.
- **8.6** versionCode bump.
- **8.8** lintVital ServiceKeepAlive tidy.
- **7.7** Standalone marketing copy (splash/about) — text.
- **9.6** Decision Log append (doc, not code).

## B. SUSPECTED DUPES / OVERLAPS (same function — resolve into one line, don't fix twice)
- **3.2 ⊇ 3.10** — SELECTED clear-on-leave (3.10) is a SUB-TASK of the SpatialDisplayManager consolidation (3.2). Fold 3.10 into 3.2.
- **3.2 ↔ 6.5/6.8** — "SpatialDisplayManager dead bindings" listed under dead-code (6.5/6.8) is the SAME code as 3.2. One fix; don't quarantine separately.
- **6.5 ⊂ 6.8** — scanDownloadsForGpx / showImportList orphans appear in both the dead-code sweep (6.5) and the 2.5→2.6 quarantine inventory (6.8). Same targets — 6.5 is just the named subset of 6.8.
- **6.4 ⊂ 6.8** — METHOD_SELECT / B1_DRAW_AREA remnants also re-listed in 6.8's candidates. Same.
- **4.5 ↔ 4.6** — both are "is the GPX parse correct on big files": 4.5 = >32MB backtracking, 4.6 = over-capture verify. Likely ONE verify pass on a large real file answers both.
- **3.1 ↔ 3.6** — Planning blank-on-return (3.6) is probably a SYMPTOM of the missing bbox persist/restore (3.1). Verify together; 3.1's fix may close 3.6.
- **1.5 ↔ "C-2 contamination"** — the map-switch shared-state issue is 1.5; C-2 was the symptom. Already merged — no separate C-2 item. (Confirm nothing re-adds it.)
- **9.4 ⊇ 9.5 + (8.9?) + 9.1-screenshots** — the in-app help/manual PANEL [9.4] is now ONE grouped functional task: `?`-panel UI revision + post-update release-info display (stalled, new fn) + searchable-by-function manual panels (new fn) + screenshot capture. Absorbs old 9.5; cross-check 8.9 (release-notes gate — same surface?); the screenshot-capture step moved out of 9.1 into 9.4.
- **8.9 ↔ 9.x / J** — first-launch Release-Notes gate (8.9) overlaps the post-update release-info display in 9.4. 8.9 = the must-acknowledge gate; 9.4's release-info = what's-new display. Decide if same surface or stricter variant. (Was listed as 8.9↔9.5; 9.5 is now folded into 9.4.)
- **4.13 ↔ 4.1** — Track Display Selector in-progress tabs (4.13) and the import 3-type toggle (4.1) both touch the track selector UI but are different surfaces (display vs import). NOT dupes — noted so they're not merged by mistake.
- **1.2 (sliceLine) / 1.6 (radius) / 3.2 / 3.7** — these are the "both maps, same shared code" items: fixing once fixes convoy + planning. Not dupes; flagged so they're counted ONCE across the two map screens.

## C. EVERYTHING ELSE = FUNCTIONAL (logic / data / DB / pipeline — the real work; not in the quick pass)
All items not in list A above. The heavy clusters: route builder logic (1.2–1.5), lead-track redesign (2.1), display consolidation + bbox (3.1, 3.2), import pipeline (4.x), DB/dedup (5.x), the two launch-blocking ANRs (8.1, 8.2), and the docs/website finish (9.1–9.4).

---

# GROUP 1 — ROUTE BUILDER (snap-2 + lifecycle)

### 1.1 — Snap-2 trace, both maps — ✅ DONE (2026-06-10)
Route line traces trail/track geometry between snapped points on BOTH maps. Convoy done 06-06 (sites 544 + 694). Planning done 06-10 (C-1): rollback 56713ab1e, resume 5630fb0b9, undo 6b1628f82; live onMapTap was already traced. (Source: 06-06 v3 §B/§C; 06-10 session.) Kept here as context; it unblocks the AAB gate.

### 1.2 — sliceLine WHOLE-TRAIL EXPLOSION — OPEN (meatiest remaining route bug)
On certain taps the snap-2 trace plots the WHOLE underlying trail (~78k chars) from the last point instead of just the A→B segment. Logcat: tracedLen jumped 1808→79861, then 79901→157466 on single taps; undo + place-elsewhere returns to normal (pair/position dependent). Diagnosis: RouteManager.sliceLine (193) / buildSegments (218) — SHARED, so a fix corrects BOTH maps. sliceLine bounds the walk with stored a.segmentIndex/b.segmentIndex (pos=segmentIndex+t @187) computed against the viewport geometry AT TAP TIME; buildSegments later resolves lineGeom(a.lineId) from a DIFFERENT viewport (panned/zoomed) → vertex count/offset differs → stored index overshoots → dumps the whole line. Possible MULTILINESTRING-flatten contributor. ROBUST FIX: re-project A and B onto the current resolved geometry, slice the contiguous run between. Read snap-side math first (sed 120,180 RouteManager.kt). Device retest across pan/zoom. NOT a one-liner. (Source: memory/06-10; 06-06 v3 behavior notes.)

### 1.3 — Armed gating (Add RED → tap PANS, not places) — OPEN
Today a map tap places a vertex regardless of the Add toggle state. Intended: Add GREEN = tap places a vertex; Add RED ("Add OFF") = tap PANS the map, no placement. This was "Build 3" in the route lifecycle plan. (Source: 06-06 v3 §E3; RouteCreation_Screen_ProcessFlow_2026-06-04.)

### 1.4 — Draft-JSON in-progress lifecycle (save-in-progress / resume / graduate / discard-rollback) — PARTIAL
Framework exists (RouteDraftStore: writeDraft/overwriteDraft/listDrafts/openDraft/loadIntoRouteManager/deleteDraft/isNameTaken; drafts persist JSON at /sdcard/Documents/GroupTrack/route_drafts/<name>.json). Resume + rollback REDRAW now trace correctly (C-1). OPEN pieces: the "In Progress" entry button is still disabled in places; graduation re-check (call routeNameExists before insertRoute at graduate = Layer 3); the full save-in-progress→resume→graduate→discard UX walked end-to-end. A route is EITHER a draft-file OR a DB-row, never both. Full spec: RouteWriteFunction_DataContract_2026-06-04. (Source: 06-06 v3 §E3/§E4; RouteWriteFunction_DataContract.)

### 1.5 — Auto-save-and-terminate on map-switch (the real fix for shared-state contamination) — OPEN
Leaving the build context with the route builder open should force SAVE-IN-PROGRESS. Two cases: (A) plain exit map→menu = just save (no kill); (B) MAP-TO-MAP SWITCH with Route+ active = save AND TERMINATE the app (clean process kill resets the shared RouteManager singleton — the ONLY contamination path; this is the root cause behind the "undo seemed broken after a map switch" symptom that C-2 chased). Guard: auto-save only if ≥1 vertex AND a name is assigned. Warn first; clean finishAffinity; FOREGROUND SERVICE keeps running so a device test confirms the FGS survives/restarts and the draft resumes. Own change with device test. (Source: memory/06-06; RouteBuilder_Blueprint_Addendum9_FINAL_exit_model.)

### 1.6 — Snap radius slider (80 m default + in-app slider) — PARKED→un-park for finished product
Currently the snap radius is a hardcoded 30.0 m real-world value. At zoom-out (~¼ mile) almost nothing snaps because 30 m is too tight. Fred's original design: 80 m default + an in-app slider so radius is tunable without recompiling. They fell back to the hardcoded 30 m commit because the shared-radius/slider work went into uncomfortable territory. Do this deliberately as polish, AFTER the slice fix (1.2). (Source: 06-06 v3 §F; master §F.)

### 1.7 — Route-builder cosmetic fixes — ✅ DONE (2026-06-11, committed `bcd5f8e31`, device-approved)
(a) Empty In-Progress picker now shows the "No in-progress routes yet" empty-state. (b) The name hint now distinguishes blank-name ("name required") from taken-name ("name is taken") instead of reusing one flag. Shipped in the V2.5 cleanup batch. (Source: 06-06 v3 §F; EOD 2026-06-11.)

### 1.8 — One-route-per-GroupTrack-session — OPEN
Version 2 rule: one route app-wide, on either map. Plan-exit handled via the GroupTrack-maps-submenu elimination (a cleanup, see 6.x), NOT a nav guard. (Source: 06-06 v3 §F.)

### 1.9 — +ROUTE button nav wiring on artifacts panel — VERIFY/OPEN
The +ROUTE button that opens the Route+ toolbar — confirm nav wiring is complete on the artifacts panel across all interfaces. (Source: master §E.)

### 1.10 — Route maintenance screen (rename / edit geometry / delete a completed route) — OPEN
Lives in Work-with-Artifacts → Routes (SEL/Edit), NOT the Route+ toolbar (toolbar builds only). Rename, edit a saved route's geometry, delete. (Source: master §D; RouteCreation_Screen_ProcessFlow.)

### 1.11 — Draw / Suggest build methods — OPEN (placeholders)
"Draw" (freehand drag) and "Suggest" (drop POIs → app proposes ~5 candidate routes) are placeholder buttons today ("soon"). Suggest is a future/3.0-leaning feature. (Source: 06-06 v3 §E3; RouteCreation_Screen_ProcessFlow.)

### 1.12 — Z-order: route magenta renders UNDER trails/tracks — OPEN
The magenta route line sometimes draws beneath the cyan trails / green tracks. Separate map-HTML draw-order fix (Leaflet pane / z-index), NOT the slice bug. (Source: memory/backlog.)

---

# GROUP 2 — LEAD-TRACK DISPLAY REDESIGN (convoy)

### 2.1 — Replace the 3-flow lead-track pipeline with one writer — OPEN (spec'd; implement after routes + planning cleanup)
**Problem:** Today the lead track is assembled from THREE parallel segment flows (routeTrailSegments, gpsTrailSegments, plus a lead-only filter), colored per-node, then drawn. The multiple segment writers produce phantom/extra lines on switchbacks (field-diagnosed: "extra lines" traced to multiple writers).
**Replacement design (GroupTrack_LeadTrackReplacement_Spec, May 31 — current authority; no newer doc supersedes it):** Collapse to ONE path. On each tick, take the LEAD cart's position only; append it to a single growing polyline (one list of LatLon); drawTrack() renders that one line. No per-segment color logic, no multi-flow merge, no lead-only filter (it's lead-only by construction; the trackLeadOnly toggle disappears).
**Sketch:** `val lead = state.nodes.firstOrNull { it.nodeId == lockedLeadNodeId } ?: return; if (lead.latitude != 0.0) { leadTrackPoints.add(LatLon(lead.latitude, lead.longitude)); pushTrackToMap(leadTrackPoints) }` — pushTrackToMap is NET-NEW (0 refs today).
**Discovery (open, do FIRST):** identify EXACTLY what old code is removed before cutting — the three flows' writers + the lead-only filter + per-node coloring. Removal targets anchored to field_crossref_raw.txt; trace each live and run a TRACK-DBG 2-cart capture to confirm. (Source: GroupTrack_LeadTrackReplacement_Spec; master §F lead-track item.)

### 2.2 — Lead-track smoothing (snap broadcast points to trail) — OPEN / design-adjacent
Separate from the replumbing above: when the lead cart's GPS arrives every 5 seconds, the angular straight lines between points can be snapped to the nearest trail geometry (same snap-to query as route planning) so the displayed track follows the trail, not the GPS interpolation. "Convoy map lead track smoothing" — was scoped to need 2–3 weeks field testing. Decide whether this is in V2.5 scope or 2.6. (Source: AllDocs lead-track smoothing notes; V25_ActionPlan_v3; Product Roadmap V9.) NOTE: historical context — stale-position rejection (rideStartTimeMs guards pos.time) and the home-line + 0.25mi jump guard were committed 17fb7635e; the 0.25mi rejection was LATER REMOVED so "every segment draws, always." Keep that principle in mind when redesigning.

---

# GROUP 3 — ARTIFACT DISPLAY / MAPS (convoy ↔ planning parity, viewport, bbox)

### 3.1 — Persist bounding-box (viewport) with represented data for map refresh — OPEN (SPEC, not enhancement)
**Problem (Fred's headline cleanup item):** Save the bounding-box (viewport bounds) info TOGETHER WITH the represented data so the convoy map and planning map can REFRESH/REDRAW their display from that saved state, rather than recomputing or blanking on return / map-switch. Today the maps track lastViewportSouth/West/North/East (used by queryTrailsByViewport / queryTracksByViewport) but the bbox + what's shown for it isn't persisted-and-restored before the first query. Both HTML maps. The master checklist phrases it: "Bounding-box query-source restore (persist + restore before first query, both HTML) — SPEC, not enhancement." Likely rides on MapStateStore.saveMap("convoy"/"planning", MapSnapshot(types,panel)); the bbox would extend MapSnapshot. VERIFY MapStateStore/MapSnapshot live fields before designing. Open question to settle with Fred: (a) restore on return/map-switch, (b) manual refresh button, or (c) keep the two maps in sync — and whether to persist just bbox+toggles (re-query on restore) or cache the actual artifact set. (Source: master §F "bounding-box query-source restore"; 06-10 session.)

### 3.2 — SpatialDisplayManager: one shared display process (collapse the 3 inline copies) — OPEN (Phase 1 + Phase 2)
**Problem:** Convoy and planning render artifacts through THREE near-duplicate inline code paths: ConvoyMapViewer (planning, working) calls update<Type>() then show<Type>(); ConvoyScreen interface #1 (~476/494) and #2 (~647/622) call update<Type>() but historically NEVER show<Type>() (layer filled but never added to map — why convoy rendered nothing), plus interface #1 had a routesRaw filter bug and interface #2 did no DS_SELECTED filtering. The May-31 commit (e0182045a) mirrored planning's DS_SELECTED filter into convoy's four inline branches — fixed the FILTER divergence but by editing the inline copies, NOT collapsing them. So three copies still exist, merely more consistent. **Phase 1 (drift-prevention):** wire both maps to one processArtifact, delete the inline copies; align convoy onSetState clear-on-leave-SELECTED. **Phase 2:** inject the shared JS. **Before wiring:** confirm against current source whether show*() is now present everywhere (if so the render bug is closed and this becomes pure consolidation), and whether interface #1 routesRaw + interface #2 filtering were also covered. (Source: GroupTrack_OpenItems_Expanded; master §F.)

### 3.3 — Convoy QUEUES button DEAD — OPEN (port planning's wiring, do not build new)
The convoy-map QUEUES button does not work. Two SEPARATE implementations exist: planning (ConvoyMapViewerScreen.kt 247/282/738/750) WORKS via showDownloadPanel → ConvoyDownloadPanel; convoy (ConvoyScreen.kt 1227/1249/1270) is DEAD — a different draggable element → queuesOpen → ConvoyQueuesPanel (a lesser, different panel). A prior patch added detectTapGestures in its own pointerInput but it stayed dead. FIX: point the convoy QUEUES at the working ConvoyDownloadPanel the way planning does, and resolve the tap collision (likely drop the custom drag → plain .clickable, since it sits on the zoom-level row top-right and doesn't need to drag). Lands in both convoy interfaces (494/622). Watch double-accordion; don't cover NET/LOCAL. (Source: STATE_OF_PLAY_2026-06-02_v5; master §F.)

### 3.4 — Convoy waypoint-drop pipeline present but doesn't fire — ✅ DONE (2026-06-11, reported complete — verify hash in git log)
Convoy long-press waypoint drop now fires. (Source: EOD 2026-06-11.)
<!-- original problem note retained below -->
Long-press waypoint drop works on the PLANNING map but not convoy. The entire pipeline EXISTS on convoy and matches planning: convoy_map.html line 286 map.on('contextmenu') → Android.onMapLongPress(lat,lng); the Kotlin bridge has onMapLongPress (ConvoyScreen.kt:516 and :653) → sets pendingWaypoint; the dialog exists (pendingWaypoint?.let { AlertDialog } at ~860 → insertWaypoint). Both convoy bridges register as "Android" (556, 805); the surviving bridge has the method, so the clobber theory is dead. Yet long-press on convoy produces nothing. Diagnose why the event doesn't reach the bridge (likely same tap/gesture interception family as QUEUES). Related: long-press must fire only on EMPTY map, not on node markers. (Source: STATE_OF_PLAY_2026-06-02_v5; master §F.)

### 3.5 — Convoy `?` help button misplaced — ✅ DONE (2026-06-11, committed `bcd5f8e31`, device-approved)
Resolved in the cleanup batch (the leftover dead QUEUES drag vars were removed; QUEUES was already locked top-right from 06-03). NOTE: the convoy `?`-help relocation itself is now tracked with the [9.4] help-panel work; the dead-var cleanup that shipped under this number is complete. (Source: 06-07 session/memory; EOD 2026-06-11.)

### 3.6 — Planning Map blank on return from trail-source screen — OPEN
Returning to the Planning Map from the trail source screen leaves it blank. (Likely related to 3.1 bbox-restore.) (Source: master §F.)

### 3.7 — z12 hide-features (min display zoom = 12, all four types) — OPEN
Set a minimum display zoom of 12 for trails/tracks/waypoints/routes so the map doesn't try to draw everything when zoomed far out. (Source: master §F.)

### 3.8 — Waypoint marker shape DECISION — ✅ DONE (2026-06-11, committed, device-confirmed)
Decided: **teardrop pins** (one shape for all waypoint types), so the pin's point marks the exact location (the old round circle was centered/ambiguous). Implemented in both map HTML assets (convoy_map.html + grouptrack_map.html) in the waypoint `L.divIcon` (`border-radius:50% 50% 50% 0` + rotate, symbol counter-rotated upright, iconAnchor at the bottom tip). Per-type color + symbol unchanged. (Source: master §F; EOD 2026-06-11.)

### 3.9 — Direction arrows on track/trail lines — ✅ DONE (2026-06-11, committed) · ⏳ arrow redraw-timing follow-up
Direction-of-travel arrows on the **displayed DB tracks** (diagnostic for the multi-track question: a self-crossing loop shows continuous one-way arrows = one track; conflicting arrows at an overlap = stacked tracks). Implemented via the `leaflet-polylineDecorator` plugin **vendored locally** into `app/src/main/assets/leaflet.polylineDecorator.js` (the CDN copy failed to load at runtime, so it's bundled for offline use), decorating `trackLayer.getLayers()` in loadTracks on both maps, guarded so a missing plugin can never break track display. **Tracks display reliably.** KNOWN FOLLOW-UP (~20 min, next session): the decorator only redraws on the map's `moveend`, so on first toggle the arrows render late / only one appears until a pan nudges the map — fix by forcing a redraw in `showTracks` (`map.fire('moveend')` / decorator redraw). Secondary suspect if one-arrow persists: MULTILINESTRING track geometry (ties to 3.7). (Source: master §F; EOD 2026-06-11.)

### 3.10 — SELECTED clear-on-leave alignment (convoy onSetState) — OPEN
Align convoy's onSetState so SELECTED clears on leave, matching planning. (Folds into 3.2 Phase 1.) (Source: master §F.)

---

# GROUP 4 — IMPORT (GPX + trail sources)

### 4.1 — Walk-away import 3-type toggle (Tracks / Waypoints / Routes) — OPEN
The import selector should offer three artifact-type toggles: Tracks (default ON), Waypoints, Routes (off but selectable). Today all GPX imports = tracks only; wpt/route are bypassed via emptyList() (the bypass that fixed the 87-track OOM hang). The selector infra exists (ConvoyTrackImportScreen file-checkbox ~321, select-all ~391). Plumb the 3 flags into importGpxAllArtifacts and gate sections 1/2/3 on them — replacing the hardcoded emptyList() bypass with a real toggle. PURPOSE (Fred): the toggle is a TEST HARNESS — flip waypoints-only or routes-only, feed an isolated batch, see if that path processes, without rebuilding each test. (Source: 06-06 v3 §G; STATE_OF_PLAY_2026-06-02_v4; master §C.)

### 4.2 — Import waypoints from GPX — OPEN
Currently skipped. Ties to waypoint import remap (foreign type → 12 canonical types). (Source: master §C.)

### 4.3 — Import routes from GPX — OPEN
Currently skipped. (Source: master §C.)

### 4.4 — GPX/KML open-with handler not executing — OPEN
The intent reaches the activity but the handler doesn't execute. (Source: master §C.)

### 4.5 — Large GPX >32MB catastrophic backtracking — OPEN/VERIFY
Regex parse on >32MB files can catastrophically backtrack → crash. Move to a string-loop parse. NOTE: the streaming BufferedReader rewrite (committed 9377f23f7) fixed the 87-track/28.9MB OOM; verify whether the >32MB regex-backtracking case is fully covered by that or still needs the string-loop. (Source: master §C; track-import history.)

### 4.6 — Streaming-parse geometry over-capture — ✅ DONE / VERIFIED (2026-06-11, reported complete)
Track-import over-capture checked; streaming parse geometry confirmed correct. (Source: EOD 2026-06-11.)
<!-- original verify note retained below -->
Confirm the new streaming GPX parse captures geometry CORRECTLY and isn't OVER-capturing vs the old parser — it feeds map draw, ROUTE SNAPPING, and AWS sync, so wrong geometry propagates. Confirm point counts for a known track (St George → Bar 10). (Source: 06-06 v3 §G; master.)

### 4.7 — KEEP/DELETE recap buttons misleading — OPEN
The import recap dialog's KEEP/DELETE buttons are misleading because the source GPX auto-deletes BEFORE the dialog shows. Gate auto-delete on the user's choice, or fix the labels. (Source: 06-06 v3 §G.)

### 4.8 — Remove GPX prompt Y/N after import; remove old 'Work with Tracks'; remove node persistence — OPEN
Cleanup of import-flow leftovers. (Source: master §F.)

### 4.9 — trailSourceCount hardcoded 0 (area-import bug) — OPEN
Area trail import returns 0 because trailSourceCount is hardcoded 0. (Source: master §F.)

### 4.10 — Area trail import API fetch hangs — OPEN
The area-import API fetch hangs; needs a timeout + error handling. (Source: master §F.)

### 4.11 — Trail-type filtering on ArcGIS queries — OPEN
Exclude non-trail features at import time (filter on the ArcGIS query). (Source: master §F.)

### 4.12 — Import sample test data for all 3 types — VERIFY
Confirm sample GPX with tracks + waypoints + routes imports correctly once 4.1–4.3 land. (Source: master §C.)

### 4.13 — Track Display Selector: filter tabs + in-progress files — OPEN (UI only)
Extend the track display selector (ConvoyScreen.kt + ConvoyMapViewerScreen.kt) with filter tabs All / Saved / In-Progress (mirroring Work With Tracks). Reuses ConvoyTrackOps.isInProgress() (exists). In-progress files become selectable AND renameable from the selector — solves "the only way to rename unsaved files is to display them." Backend complete; UI work only. (Source: PLANNING_V25_Backlog.)

### 4.14 — Track Import: move to Map Viewer settings — OPEN (small refactor)
Move "Import Tracks from Downloads" out of ConvoySubMenu.kt MAP SETTINGS into the Map Viewer settings menu (Map Viewer becomes the hub for all track management). Same composable, different placement. (Source: PLANNING_V25_Backlog.)

---

# GROUP 5 — DATABASE / DEDUP (add-core + invariants)

### 5.1 — Add-core bypass: migrate the three inserts through resolveByGeom — OPEN
insertTrackToDb / insertWaypoint / insertRoute do their OWN inline INSERT OR IGNORE and BYPASS the shared dedup add-core (resolveByGeom, SpatialDbManager). Only TrailImporter.insertFeature routes through it. So "all four artifacts funnel through one core" (code comment) is FALSE — only trails do. Result: tracks dedup via the tracks-table UNIQUE(geom_hash) schema constraint but get NO alias-on-rename (a new-name-same-geom track is dropped, the alt name lost); routes (insertRoute) inherit the bypass → snap-2 routes won't dedup until migrated. FIX: migrate the three inserts through the add-core (resolveByGeom + the trail path's decision/alias handling), retire the inline INSERT OR IGNORE, and CONFIRM via where-used afterward that the only path to each table is the core. (Source: STATE_OF_PLAY_2026-06-02_v4 side-findings; 06-06 v3 §G; master.)

### 5.2 — geom_hash normalization VERIFY (load-bearing) — VERIFY
The dedup key is geom_hash = SHA-256 of WKT. If the same physical trail arrives with WKT differing by coordinate precision/rounding, hashes differ and the UNIQUE constraint misses the dup. The design calls for normalizing (rounding coords to fixed precision) BEFORE hashing. VERIFY the live hash actually normalizes, tested against REAL duplicate agency data. NOTE: a related observation — "St George to Bar10" once existed as TWO rows created 2.2s apart with different point counts/hashes; confirm whether that's non-deterministic hashing or two legitimate recordings. (Source: master §L verification point #1; STATE_OF_PLAY_2026-06-02_v4.)

### 5.3 — NULL trail names → 'Not Named' / 'Unnamed @lat,lon' — OPEN
~21K trails import with null/blank names. notNamed() applies "Not Named" in the add path; the master spec also mentions a carto_code / 'Unnamed @lat,lon' fallback. Confirm the fallback is applied consistently across the import + display paths. (Source: master §A FLAW / §K; 06-06 v3 §F.)

### 5.4 — source_id column (spec'd, not implemented) — OPEN
The v3 schema spec calls for a source_id on trails (per TrailArchitecture_v2); the actual table has none. Add it. (Source: master §A FLAW / §K.)

### 5.5 — init() migration-mechanism conflict — OPEN (resolve in the regenerate path)
init() runs inline ALTER migrations (v2 tracks, v3 type/wpt-bbox, v4 carto_code) but NEVER updates schema_version; applyMigrationIfNeeded (TrailImporter) reads schema_version, sees ≥2, and skips. The two mechanisms disagree. Unify into the regenerate-not-migrate path. (Source: master §K.)

### 5.6 — beginDedupSession type-scope — OPEN (perf)
A track-only import still loads the full ~49K trail geom_hash map it doesn't need (dedup is type-scoped). Load only the type(s) being imported. Not urgent; track imports are slow mainly due to SHA-256 over long track WKT. (Source: master §L / STATE_OF_PLAY.)

### 5.7 — DB-upgrade artifact / regenerate-not-migrate hardening — VERIFY
The regenerate-not-migrate gate (version marker <3 → one-time delete of both DB files → init recreates empty v3) is CONFIRMED LIVE. Keep the golden v2 fixtures test loop (restore golden v2 → install -r -d → launch → gate → delete → recreate → repopulate → verify) bulletproof before the Play Store AAB. Tester upgrade HAZARD (proven): a tester who UNINSTALLS 2.4 and clean-installs 2.5 breaks (public /sdcard DBs survive uninstall, collide). Keep the "install as an UPDATE, do not uninstall" callout in the bundled release notes until regenerate-not-migrate is retired. (Source: master §K; tester-hazard memory/06-07.)

### 5.8 — AWS MySQL structural mirror (closing task) — OPEN (correctness-critical)
Mirror the final v3 spatial schema to the EMPTY AWS RDS MySQL as a structural equivalent (geometry_json LONGTEXT, DECIMAL lat/lon; same tables/cols including the new alias tables). Local SQLite and AWS models MUST match structurally — if the UNIQUE keys differ, the alias model breaks at sync (device→AWS, AWS→device, device→device). This is correctness-critical, not cosmetic, because ~100 contributors feed AWS in different arrival orders and the {primary + all aliases} name-set must be identical across DBs even when which name is flagged primary differs locally. (Source: master §K closing task / §L invariants.)

---

# GROUP 6 — CLEANUP / DEAD-CODE QUARANTINE

### 6.1 — Cleanup methodology v2 (the rule for this whole group) — METHOD
Cleanup = xref-prove the function dead (zero live refs AND no AllDocs mention), then remove the LAUNCHER and the FUNCTION together, reversibly, area by area — NOT links-only, NOT one mega-sweep. (Source: CleanupMethodology_v2_RemoveLinkAndFunction_2026-06-04.)

### 6.2 — geojson functional removal (finish the install-size cleanup) — OPEN
The bundled utah_trails_stgeorge.geojson was SHRUNK (22MB → 3KB) but the legacy load path is STILL LIVE: ConvoyScreen.kt:1706 (inside onTrailsToggle, reads the asset on first Trails-ON). Rewire 1706 to the DB-viewport path (queryTrailsByViewport), then git rm the asset. Proven exactly ONE reference (1706). RISK: deleting with 1706 live → FileNotFoundException. GATING: confirm convoy has a working DB-viewport trail path first. Intended behavior change: fresh install shows no trails until import. LESSON: a feature isn't complete until the OLD path is removed (the doc said "no longer bundled" while the asset + read stayed live). Other trail assets STAY: utah_trailheads.json (345KB), trail_sources.json (32KB). (Source: memory/06-07; master.)

### 6.3 — MAP SETTINGS submenu dead-code quarantine — OPEN (verify-first)
The MAP SETTINGS submenu is a dead-code quarantine candidate. Verify-first via xref before removing. (Source: 06-06 v3 / master §I.)

### 6.4 — Remove METHOD_SELECT / B1_DRAW_AREA remnants — OPEN (verify dead first)
Remove these remnants once xref confirms they're dead. (Source: master §F/§I.)

### 6.5 — Dead-code sweep: scanDownloadsForGpx, showImportList orphans — OPEN
scanDownloadsForGpx (1 xref) and showImportList orphans are quarantine candidates. (Source: master §F/§I.)

### 6.6 — Duplicate AlertDialog import — ✅ DONE (2026-06-10, committed `2d12a81fd`)
Duplicate `import androidx.compose.material3.AlertDialog` removed (live lines 35 & 87). (Source: 06-10.)
<!-- original tidy note retained below -->
Two AlertDialog imports; tidy to one. (Source: master §F.)

### 6.7 — !!/safe-call warning tidy — OPEN
Clean up the !! / safe-call warnings. (Source: master §F.)

### 6.8 — Dead-code quarantine inventory + reversible removal (the 2.5→2.6 closing pass) — OPEN
Run at 2.5 release: inventory orphans via xref (zero live refs AND no AllDocs mention), quarantine reversibly (never hard-delete), log each source location. Candidates: scanDownloadsForGpx, showImportList orphans, SpatialDisplayManager dead bindings, METHOD_SELECT/B1_DRAW_AREA remnants. (Source: master §I.)

### 6.9 — GroupTrack-maps-submenu elimination (plan-exit path) — OPEN
Eliminate the GroupTrack-maps-submenu as the plan-exit path (rather than a nav guard) — ties to route one-per-session (1.8) and the 3.0 menu gateway architecture. (Source: 06-06 v3 §F; V25_Note_GroupTrackMenu_Architecture_3.0Gateway.)

---

# GROUP 7 — STANDALONE / SOLO-RIDER POLISH (Gaia/onX parity)

### 7.1 — Higher-resolution GPS recording (1-second option) — OPEN
Currently fixed at 3s. Add a 1-second interval option. (Source: PLANNING_V25_Backlog.)

### 7.2 — Mileage / trip odometer display — OPEN
Referenced in V2.4 known limitations as "planned for future." (Source: PLANNING_V25_Backlog.)

### 7.3 — Speed warmup tightening — OPEN
Currently shows 0 mph for ~60 seconds while GPS samples accumulate. Tighten the warmup. (Source: PLANNING_V25_Backlog.)

### 7.4 — Track stats display (post-ride summary) — OPEN
Distance, elevation gain, moving time, max speed. (Source: PLANNING_V25_Backlog.)

### 7.5 — Track survey on stop (name + difficulty + share) — OPEN (unshipped 2.5 feature)
On track stop, prompt for name + difficulty + share. (Source: master §F; PLANNING_V25_Backlog "waypoint marking / survey".)

### 7.6 — Solo-rider onboarding (first-launch tutorial) — OPEN
First-launch tutorial that doesn't assume a radio. (Source: PLANNING_V25_Backlog.)

### 7.7 — Standalone marketing copy (splash / about) — OPEN
Position the app as standalone-friendly on splash / about screens. (Source: PLANNING_V25_Backlog.)

### 7.8 — Verify created waypoints survive force-stop/reopen — VERIFY
Confirm user-created waypoints persist across a force-stop/reopen. (Source: master §F.)

---

# GROUP 8 — GOOGLE PLAY / ANR / LAUNCH GATE

### 8.1 — ANR #2 osmdroid tile-cache scan (MUST resolve before launch) — ✅ DONE (2026-06-11, reported complete — verify hash in git log)
The main-thread osmdroid tile-cache scan at onCreate is resolved (one of the two launch-blocking ANRs gating the AAB — now cleared). (Source: master §G; EOD 2026-06-11.)

### 8.2 — ANR #1 MANAGE_EXTERNAL_STORAGE startup blocks main thread — ✅ DONE (2026-06-11, reported complete — verify hash in git log)
Startup no longer blocks the main thread on the storage permission (file-dependent init deferred). Second of the two launch-blocking ANRs — **both ANR launch gates now cleared.** (Source: master §G; EOD 2026-06-11.)

### 8.3 — ANR Type 2 Input Dispatching Timeout — VERIFY
May share the osmdroid root cause; verify after 8.1. (Source: master §G.)

### 8.4 — Strip diagnostic logs before AAB — ✅ DONE (2026-06-10, committed `2d12a81fd`)
All three snap-2 diagnostic Log.d sites stripped (ConvoyScreen tracedLen + S2 tracedLen; ConvoyMapViewerScreen S2P tracedLen). (Source: 06-06 v3 §E; 06-10.)

### 8.5 — SpecifyForegroundServiceType lint — ✅ DONE (2026-06-11, committed `bcd5f8e31`, device-approved)
Added `android:foregroundServiceType` (connectedDevice) to the SystemForegroundService in AndroidManifest.xml — the lint Error-gate now passes. (Same fix as 8.8 — one manifest attribute clears both.) (Source: RouteToolbar_CleanupItems_v3; master §G; EOD 2026-06-11.)

### 8.6 — versionCode bump + signing confirm — ✅ DONE mechanism (2026-06-11) · signing confirm at bundle time
Resolved: versionCode is COMPUTED in build.gradle.kts (lines ~65-70) — pass `-Pandroid.injected.version.code=NNNNN` at bundleGoogleRelease (honored first), with N > Play-live AND > 29320600. No source edit needed; it's a build-flag at AAB time. Signing (Play vs local APK use different keys) still confirmed at the actual bundle step. (Source: master §G; 06-06 v3 §E; memory; EOD 2026-06-11.)

### 8.7 — About / Attribution screen — OPEN
GPL / Leaflet / Esri attribution screen for Play compliance. (Source: master §G.)

### 8.8 — lintVital ServiceKeepAlive tidy — ✅ DONE (2026-06-11, committed `bcd5f8e31` = same fix as 8.5)
Same root as 8.5 — the foregroundServiceType manifest attribute clears the lintVital ServiceKeepAlive gate. (Source: master §G; EOD 2026-06-11.)

### 8.9 — First-launch Release-Notes acknowledgment gate — OPEN
Every launch, show an in-app PDF viewer of the V2.5 Release Notes with a checkbox "I have read and acknowledge…" that enables an Acknowledge button (no persisted flag). Build the PDF viewer screen (PdfRenderer or lib); gate app entry every launch; ANR-safety: load/render the PDF OFF the main thread, show the gate AFTER heavy init; bundle the current Release Notes PDF as an asset and decide the per-release update path. (Source: master §J.)

---

# GROUP 9 — DOCUMENTATION / WEBSITE / RELEASE (finish-the-product)

### 9.1 — Cookbook user manual with screenshots, published online — OPEN
Finish the V2.5 user manual as the cookbook, WITH real device screenshots, published online. **(Screenshot CAPTURE is grouped into the in-app manual-panel task [9.4]; this item [9.1] is the manual CONTENT + online publish.)** Current state: the screen-anchored living manual is GroupTrack_V25_UserManual_2026-06-05.html (Planning Map / Convoy Map / Radio-Write / Work with Artifacts; route content in Section 4 → Creating a Route). A separate 28,050-byte all-functions grouptrack_manual.html exists in assets with 37 "[screenshot to be added]" placeholders. Capture per-screen via `adb -s 24039703201775 exec-out screencap -p > shot.png` (exec-out avoids Git-Bash CRLF PNG corruption). Live device screenshots are the correct source (not the V3 HTML mockups, which are 3.0 prototypes; any mockup image must be labeled "interface preview"). EDIT the manual IN PLACE at each placeholder — never regenerate. Don't screenshot a screen that's about to change (do this AFTER the cleanup items settle). The "Creating a Route" section needs the snap-now-live edit (see GroupTrack_Manual_CreatingARoute_update_2026-06-10.md). (Source: 06-05 manual; memory/06-07; 06-10.)

### 9.2 — Release notes finalized + published online — OPEN
Finalize the V2.5 release notes (current: GroupTrack_V25_ReleaseNotes_2026-06-05.html + the 11,290-byte assets grouptrack_release_notes.html with the upgrade-hazard callout) and publish online. Needs the snap-now-live edit (see GroupTrack_ReleaseNotes_update_2026-06-10.md). (Source: 06-05 release notes; 06-10.)

### 9.3 — Website V2.5 deploy — OPEN
Deploy V2.5: edited index.html + Release Notes + User Guide PDFs. Snapshot index.html first. Retire the "selections carry between maps" known-issue (fixed). grouptrack.org/manual currently 404s (site still serves V2.4 PDFs; V2.5 manual never published). (Source: master §H; memory.)

### 9.4 — In-app help / manual / release-info PANEL — OPEN (FUNCTIONAL — ONE grouped task: 2 features + 1 UI revision + screenshot capture)
**Grouped per Fred (2026-06-10): treat as one functional task because they share the same panel surface + entry point.** Four parts:
- **(UI revision) `?` placement → panel access.** Relocate/wire the `?` icon to be the entry point to the help panel. (The placement piece is light; it's the doorway to the two features below. This is the UI-revision half of the grouped task — NOT a standalone cosmetic item.)
- **(NEW FUNCTION) New-release info display after update — currently STALLED.** After an app update, show the user what changed (release notes / "what's new"). NEW function (the display is stalled today, not just unwired). Likely overlaps/absorbs the first-launch Release-Notes acknowledgment gate [8.9] — decide whether 8.9's must-acknowledge gate is the same surface or a stricter variant. Cross-check 8.9 before building.
- **(NEW FUNCTION) User-manual display BY PANELS, searchable by FUNCTION NAME.** Panel-based manual browser; user searches by function name to jump to that function's cookbook entry. In-app form of the HTML manual; supersedes the old "?help=<anchor> / ?section=<sec> / GroupTrackManual.open()" bridge framing — same goal, new design (panels + function-name search, not just anchor deep-links).
- **(ACCESS TASK) Capturing screen images into the cookbook.** Per-screen `adb -s 24039703201775 exec-out screencap -p`. Pulled in here (the capture half of [9.1]) so the manual feature and its screenshots move together.
(Source: V25_OpenItem_FirstLaunchNotes_OnlineManual_HelpIcon; INDEX.md help_system_bridge; 9.1 screenshot task; 8.9 gate; grouped 2026-06-10.)
**Absorbs:** old 9.5 (first-launch notes / online-manual icon). **Overlaps:** 8.9 (release-notes gate — cross-check), 9.1 (screenshot capture now lives here).

### 9.6 — Decision Log: append 06-07 + 06-10 blocks — OPEN (doc hygiene)
The 06-07 and 06-10 sessions were never written into a dated Decision Log block. Append them (append-only: new dated block on top, prior blocks verbatim). (Source: memory; NEW_CONVERSATION_INSTRUCTIONS end-of-day ritual.)

---

# GROUP 10 — HARDWARE / FIELD (not code)

### 10.1 — BLE budget-device supervision timeout — OPEN (hardware → tester compat list)
Budget Android devices can't hold a BLE link to the T1000-E (supervision timeout 0x0008, 8–10s). Not a code fix — produce a device-compatibility list for testers. (Source: 06-06 v3 §G; master.)

---

# GROUP 11 — ARTIFACT LIST / MISC

### 11.1 — Artifact list caps at 200 (paging) — OPEN
The artifact list shows up to 200 entries; larger lists need paging. (Source: v2.5 release notes known-issues; backlog.)

### 11.2 — Trail / Route DETAIL via SELECT/Edit list (not map-tap) — OPEN
Provide trail/route detail from the SELECT/Edit list, not only via map-tap. (Source: master §D.)

### 11.3 — Settings filter table (CartoCode / motorized / type filtering) — OPEN
Build the settings filter table for filtering artifacts by carto_code / motorized / type. (Source: master §D.)

### 11.4 — Track / Trail / Waypoint maintenance screens — OPEN/VERIFY
Track maintenance screen exists (VERIFY on device). Trail maintenance (title click) OPEN. Waypoint maintenance: rename/delete/changeType exist but a dedicated screen may not be wired (VERIFY). Route maintenance OPEN (see 1.10). (Source: master §D.)

### 11.5 — SP11 Planning Map entry modes — VERIFY
Planning map runs and state-isolation is likely satisfied (May-31 map-independence work); VERIFY the entry modes (planning / import / trailhead) are wired. (Source: master §A.)

### 11.6 — SP13 Route Planning Tools Integration (ride-creation wiring) — OPEN (downstream)
Auto-set map_bounds; connect route creation to ride-creation. PREMIUM; downstream of route creation. (Source: master §A.)

### 11.7 — SP14 Trailhead Selection Integration — OPEN
Map picker modal; save to trailhead_waypoint_id (0 xrefs → unwired). Ride-form auto-populate depends on this. (Source: master §A.)

---

# DEFERRED (captured, explicitly NOT active V2.5 work — do not service for 2.5)

- **National Trail Model / GeoPackage architecture** — replace the Utah GeoJSON with grouptrack.gpkg + three engines (ingestion / distribution / local display); Utah pilot → AZ/NV/ID/CO → national. Multiple weeks. (Source: PLANNING_V25_Backlog; trail-architecture memory.) NOTE: the trail_sources.json catalog (8 ArcGIS sources, viewport-import) is the SHIPPED V2.5 approach; the GeoPackage model is the larger future architecture. NH trails ride on the catalog approach for now (parked this session).
- **V3.0 Stub Inventory (SP01–SP29)** — 29 atomic stubbed processes, deferred until 2.5 ships + Phase B sign-in. Captured so nothing is lost. (Source: V30_StubInventory.)
- **Map Manager Phase C** — auto-tile-download, PROTECTED/PURGEABLE, storage dashboard. (Source: PLANNING_V25_Backlog; roadmap.)
- **V3.0 subscription / paywall / ride-engine / AWS collective** — $3/mo, registration, ride enrollment. (Source: roadmap; V3 specs.)

---

_End. Every item above is source-tagged for line-by-line review. Items marked DONE (1.1) are kept only for context. Grouping is by code area so related fixes can be batched. Nothing from the master checklist, 06-06 v3, the backlog, the cleanup series, the lead-track spec, or the recent state-of-play docs was intentionally dropped; where AllDocs held only an excerpt (TodoCleanup_Checklist, OpenIssues_AWS_Plan section bodies), those headers' items were recovered from the master checklist and backlog which carry the same content forward._
