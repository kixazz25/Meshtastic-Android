# GroupTrack — V2.5 Master Checklist

_Rebuilt 2026-05-31. Living spine. Legend: DONE=confirmed · PARTIAL=partly wired · OPEN=not done · VERIFY=check against live source · FLAW=defect found in testing on a completed feature._

## A. V2.5 Map & Data Infrastructure (original placeholders)
- **[x] DONE** `SP10` · Tile Download System — ConvoyTileDownloader.downloadTiles(), background download w/ progress.
  - _Fixed May 21 (Esri URL, slot filter, HTTP logging). 10 xrefs — wired._
- **[?] VERIFY** `SP11` · Planning Map Launch Integration — independent Map Manager route; state isolation from convoy; entry modes (planning/import/trailhead).
  - _Planning map runs (ConvoyMapViewerScreen). State-isolation was the May-31 map-independence work — likely satisfied. VERIFY the entry modes are all wired._
- **[~] PARTIAL** `SP12` · Import Directory Scanner — scanImportDirectory(), GPX/KML via ACTION_OPEN_DOCUMENT, validation, metadata.
  - _Tracks import works (6 xrefs). 'tracks only — expand to waypoints + routes.' Waypoint/route GPX import still OPEN (see C)._
  - **↳ [FLAW — found in testing]** Dupe-identification RULES undefined. May 30: 3 source imports (UGRC/BLM/USFS) tripled trails to 82,220. CANONICAL RULE: duplicate = same (name, geometry). NOT name alone (21K 'null'-named). NOT geometry alone (agency rounding). Rule shared by all three layers below.
  - **↳ [FLAW — found in testing]** No CLEAN PROCESS enforcing the rule after import. Need an automatic post-import dedupe pass, same (name, geometry) key (keep MIN(rowid); null-named keyed on geometry). Tonight's recovery was manual — must become an in-app step.
  - **↳ [FLAW — found in testing]** No DB-LEVEL guard. Attach the rule to the spatial DB DESIGN: UNIQUE(name, geometry) + INSERT OR IGNORE so dupes can't be written. Needs migration + one-time dedupe first. Durable fix — rule in schema, not caller discipline.
  - **↳ [FLAW — found in testing]** Add source_id (TrailArchitecture_v2 spec; actual table has none) for source-aware/lossless dedupe.
  - **↳ [FLAW — found in testing]** 'null' trail naming: literal 'null' when source has no name (21K rows). Fallback carto_code or 'Unnamed @ lat,lon'.
- **[ ] OPEN** `SP13` · Route Planning Tools Integration — auto-set map_bounds from route geometry; connect to ride-creation. PREMIUM.
  - _Route CREATION is the lead item (snap-2, see E). The ride-creation wiring is downstream and OPEN._
- **[ ] OPEN** `SP14` · Trailhead Selection Integration — map picker modal; save to trailhead_waypoint_id.
  - _trailhead_waypoint_id = 0 xrefs → genuinely unwired. Ride-form auto-populate (MapManager_Spec §6) depends on this._

## B. Lazy-Load Display (viewport query → map)
- **[x] DONE** Trail lazy load — viewport query + map display. _(queryTrailsByViewport 24 xrefs; verified.)_
- **[x] DONE** Track lazy load — viewport query + map display. _(queryTracksByViewport wired.)_
- **[x] DONE** Waypoint lazy load — viewport query + marker display. _(21 xrefs; markers render incl. new created.)_
- **[x] DONE** Route lazy load — viewport query + map display. _(queryRoutesByViewport 21, buildRouteGeoJson 11; gold dashed. 0 routes until creation ships.)_

## C. GPX Import Expansion
- **[x] DONE** Import tracks (existing). _Verified working._
- **[ ] OPEN** Import waypoints from GPX. _Not wired; ties to waypoint import remap (foreign type → 12 canonical)._
- **[ ] OPEN** Import routes from GPX. _Not wired._
- **[?] VERIFY** Import sample test data for all 3 types.
- **[ ] OPEN** GPX/KML import handler: intent reaches activity but handler not executing.
- **[ ] OPEN** Large GPX >32MB: regex → string-loop (catastrophic backtracking).

## D. Work with Artifacts UI
- **[x] DONE** Trail/Track/Waypoint/Route toggle → viewport query → display. _ON/OFF/SELECTED both maps after May-31 filter fix._
- **[x] DONE** Per-type select/edit list (checkbox, select/deselect all). _ArtifactListPanel + queryArtifactList; SELECTED fixed May 31._
- **[?] VERIFY** Track maintenance screen (exists — verify on device).
- **[ ] OPEN** Trail maintenance screen (title click). _Not confirmed wired._
- **[ ] OPEN** Route maintenance screen.
- **[ ] OPEN** Waypoint maintenance screen. _rename/delete/changeType exist in SpatialDbManager; dedicated screen may not be wired — VERIFY._
- **[ ] OPEN** Settings filter table (CartoCode / motorized / type filtering).
- **[ ] OPEN** Trail/Route DETAIL via SELECT/Edit list (not map-tap). Trails read-only, Routes thin.

## E. Route Creation — point-to-point, SNAP-2 (tester-chosen)
> Design decision: testers surveyed on BOTH; chose point-to-point + snap-2 over freehand. Supersedes the May-29 freehand design notes. Do NOT revert to freehand.
- **[ ] OPEN** Point-to-point route line with SNAP-2. Snap references = TRAILS and TRACKS (snap a placed vertex to nearest point on a trail/track within radius).
  - _Tester-validated. Radius tune-by-testing; consider hover/preview of snap target._
- **[ ] OPEN** Snap priority + fallback: trail-first vs track-first when both in range; nearest-point-on-line snap component.
- **[ ] OPEN** Build WKT LINESTRING from snapped vertices → bbox → insertRoute → re-fire → draw (gold dashed wired). _insertRoute exists (4 xrefs)._
- **[ ] OPEN** +ROUTE button nav wiring on artifacts panel — part of this feature.
- **[ ] OPEN** Parity: shared state + path across all 3 interfaces (convoy 494, convoy 622, planning 391); both HTML; diff after.

## F. Cleanups & Carried Bugs
- **[ ] OPEN** QUEUES button (convoy) DEAD — IDENTICAL to the working planning QUEUES. Task = PORT planning wiring to convoy, not build new. Confirm it lands in both convoy interfaces (494/622). Layout: same row as +/- zoom + north; watch double-accordion; don't cover NET/LOCAL.
- **[ ] OPEN** trailSourceCount hardcoded 0 (area-import functional bug).
- **[ ] OPEN** Area trail import API fetch hangs — needs timeout + error handling.
- **[ ] OPEN** Trail-type filtering on ArcGIS queries (exclude non-trail features at import).
- **[ ] OPEN** Planning Map blank on return from trail source screen.
- **[ ] OPEN** z12 hide-features: min display zoom = 12 all four types.
- **[ ] OPEN** SpatialDisplayManager: wire both maps to one processArtifact, delete inline copies (Phase 1); inject shared JS (Phase 2). Align convoy onSetState clear-on-leave-SELECTED.
- **[ ] OPEN** Waypoint marker shape DECISION: triangle (orig design) vs round pin (shipped).
- **[ ] OPEN** Long-press waypoint drop must fire only on empty map, not node markers (MapManager_Spec §5.1).
- **[ ] OPEN** Bounding-box query-source restore (persist + restore before first query, both HTML). _SPEC, not enhancement._
- **[ ] OPEN** Remove GPX prompt Y/N after import; remove old 'Work with Tracks'; remove node persistence (blocklist).
- **[ ] OPEN** Remove METHOD_SELECT / B1_DRAW_AREA remnants (verify dead via xref first). _Tier-2 dead-code._
- **[ ] OPEN** Track survey on stop (name + difficulty + share).
- **[ ] OPEN** Direction arrows on track/trail lines; SAT/TOPO bar + North indicator + zoom readout.
- **[ ] OPEN** Dead-code sweep: scanDownloadsForGpx (1 xref), showImportList orphans.
- **[ ] OPEN** Duplicate AlertDialog import ConvoyScreen.kt (34 & 85) — tidy.
- **[ ] OPEN** !!/safe-call warning tidy (ConvoyMapViewer ~591/604/617/663-712; ConvoyScreen ~569/586/705/718/731).
- **[ ] OPEN** Verify created waypoints survive force-stop/reopen.
- **[ ] OPEN** LEAD-TRACK RECORDING REPLACEMENT (spec'd; implement AFTER routes + planning cleanup). Replace 3-flow pipeline (routeTrailSegments + gpsTrailSegments + trackLeadOnly) with one lead-position polyline + drawTrack, gated on lead nodeId. Keep ConvoyGpsService/addMarker/drawTrack/lead-lock untouched. See lead_track_replacement.md.
  - _Surgery, not weed-whacking — high blast radius (trackLeadOnly ~43, routeTrailSegments ~44, gpsTrailSegments ~22 in field_crossref)._
  - **↳ DISCOVERY (open, never defined):** identify EXACTLY what old code is removed before cutting. Anchored sites: ConvoyViewModel.kt:141/147/219/441/741/767/768; ConvoyScreen.kt:332/333/334-336; ConvoySettingsScreen.kt:59/192/198. Trace each live + run TRACK-DBG 2-cart capture for any rogue non-lead line source.

## G. Google Play / ANR (launch gate)
- **[ ] OPEN** ANR #2 osmdroid tile-cache scan: ~60GB on main thread at onCreate (20-43s freeze P10_T). Disable osmdroid cache trimming (GroupTrack serves convoy://tiles/). MUST resolve before launch. _NEW — May-16 handoff._
- **[ ] OPEN** ANR #1 MANAGE_EXTERNAL_STORAGE startup blocks main thread — defer file-dependent init until permission confirmed.
- **[?] VERIFY** ANR Type 2 Input Dispatching Timeout (20-43s after 'Binding to mesh service'). May share osmdroid root cause.
- **[x] DONE** Package rename to com.grouptrack.android.
- **[ ] OPEN** About/Attribution screen (GPL/Leaflet/Esri); Play Console; AAB version > 29320573; lintVital ServiceKeepAlive tidy.

## H. Website (staged)
- **[ ] OPEN** Deploy V2.5: edited index.html + Release Notes + User Guide PDFs (scp → sudo mv). Snapshot index.html first. Decide old V2.4 cleanup. Retire the 'selections carry between maps' known-issue (now fixed).

## I. Closing 2.5 → First 2.6 — Dead-Code Quarantine
> Run at 2.5 release. Identify standalone functions with ZERO live refs AND no AllDocs feature mention (planned refs count as keep). Quarantine reversibly (attic package or tagged branch), preserve, never hard-delete; log source for copy-back.
- **[ ] OPEN** Inventory orphans via xref (zero live refs + no AllDocs mention).
- **[ ] OPEN** Quarantine reversibly; log source location.
- **[ ] OPEN** Candidates to re-check: scanDownloadsForGpx, showImportList orphans, SpatialDisplayManager dead bindings, METHOD_SELECT/B1_DRAW_AREA remnants.

## J. First-Launch Release-Notes Gate (NEW)
> Every launch, in-app PDF viewer of the V2.5 Release Notes, checkbox 'I have read and acknowledge…' enables an acknowledge button to enter. No persisted flag.
- **[ ] OPEN** Build in-app PDF viewer screen (PdfRenderer or lib) for the bundled Release Notes PDF.
- **[ ] OPEN** Gate app entry every launch: checkbox → enable acknowledge → proceed. No persisted flag.
- **[ ] OPEN** ANR-SAFETY: load/render PDF OFF main thread, show gate AFTER heavy init — don't compound the startup ANRs.
- **[ ] OPEN** Bundle current Release Notes PDF as asset; decide update path per release.