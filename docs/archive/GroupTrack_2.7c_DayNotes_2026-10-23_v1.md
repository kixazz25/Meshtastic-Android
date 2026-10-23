# GroupTrack 2.7c — day notes 2026-10-23 (fold into living master v167 / handoff v73 at EOD)

## 2.7c items added (Fred 08:04-08:07)
1. BEFORE THE 2.7c RELEASE IS BUILT -- remove the one-time launch code still running every launch:
   StartupHousekeeping.jobCategories -> OwnershipReclass.applyMissingCategories (CATEGORYALWAYS-2026-10-10).
   Rule 3 (09-08): one-time launch code is removed when the next release is cut -- it was not, and shipped in 2.7a and 2.7b.
   What it does: every launch, trails missing category / use / land get them; land keeps any existing value and only a BLANK
   becomes PUBLIC (it never changes PRIVATE). On a converted tablet it is one COUNT.
   Also: add a release-cut check -- list every "rule 3 / NO MARKER / every launch" job before tagging, and remove them.
2. PUBLIC / PRIVATE TRAILS BREAKING (Fred: "our problem with public and private trails not working") -- investigate in 2.7c.
   Known so far: Beryl UT private community roads show by default and disappear on "public only" (so those ARE PRIVATE; the
   default is ALL -- open decision whether to default PUBLIC ONLY). Fred suspects the housekeeping cleanup; the code shows it
   only fills blanks. Next: find a trail whose land answer is wrong (Settings › Check a trail), then trace which pass set it
   (import step 8 / PBF access tags / OwnershipReclass / the launch pass).

## 08:46-09:00 Map functions organized (Fred)
Map button groups (phase 2 attach order source):
- **Maps**: Choose map type and source (map sources maintenance) · Download maps by area · by drawn box · Download queues · Remove maps by area · along a track · Rebuild maps / recovery
- **Import Map Features**: Import trails by state · by area · Import GPX (tracks, waypoints, routes) · Create trails from tracks
- **What's Available Off Grid**: Downloaded maps / archived maps · Imported trails/features by state (new view of the import list)
- Moves elsewhere: Area Search Radius -> Search; corridor download -> automatic; mileage -> open question
- Goes away: Map Features panel (after Route+); Open the planning map (with the ride map)

Map type on the map (Fred 08:54): keep the map type selectable ON the map -- a chip showing the current type, tap -> popup list -> pick switches (same gtSetMapType). Placement open: top row beside STATUS, or lower left.

Download Y/N per source (Fred 08:57-08:58): add "download maps from this source" Y/N; every screen that downloads maps uses it.
- Set in the map sources MAINTENANCE function (Maps > Choose map type and source) -- part of pruning / collapsing processes.
- Stored per slot in map_slots.json ("download": true/false), not the shipped catalog map_sources.json (replaced on catalog update).
- Single choke point: MapSourceManager.getDownloadSources() (queue estimates, worker, every download screen) filters on it.
- Default all Y (today's behaviour); at least one must stay Y; turning off stops future downloads only (existing tiles stay, Remove maps clears them); an off type shows "online only" in the chip popup; refresh-one-slot still works by name.
- Order: after the "grouptrack" persistence key, before the Maps group is attached.

## 09:04 Download functions -- where used / blast radius (Fred: "one download function with selectable options turned into many separate processes -- look at the structure and be sure our approach is supported")
ENGINE (DownloadQueueManager, worker) -- shared, NOT split, untouched by phase 2:
- submitDownload: ConvoyMapViewerScreen:4883, ConvoyScreen:2804 (one job per selected slot -> enqueueArea)
- enqueueCorridor: MapViewer:4878, ConvoyScreen:2799, ConvoyTrackOps:661 + :802 (automatic corridor after track/route import, slots = downloadMapSlots)
- enqueueCorridorBatch: ConvoyMapSourceScreen:616 + :671 (listOf("SAT") hard-coded), MapViewer:4924 (corridor picker), :5067 (migrate)
- enqueueRefresh: ConvoyMapSourceScreen:627 ("SAT"), MapViewer:5075
- submitDelete: MapViewer:5261 only (remove by area)
- holdQueue/cancelAll: DownloadQueuePanel:251/134, MapSourceScreen:570/571, MapViewer:5347/5348, ConvoySourceClear:130/134
- ConvoyCorridorDelete: MapSourceScreen:581/589, MapViewer:1074/5045/5351, TileCalculator:122
- getDownloadSources: ConvoyDownloadQueue x9 (estimates), ConvoyDownloadWorker:162 (worker filters to the job's slot)
UI PIECES and whether ONE MAP's host (ConvoyScreen + convoy_map.html) supports them:
- ConvoyDownloadPanel (THE one panel with checkboxes): MapViewer:5279 only. Phase 2 retires it; options become separate rows.
- ConvoyDownloadConfirm: MapViewer:4861, ConvoyScreen:2781 (track detail "download maps"), ConvoyTrackImportScreen:311 -> ALREADY on ONE MAP's host.
- Draw a box: JS activateDrawMode/clearAreaBoundary exist in BOTH pages; Kotlin caller only on the planner (MapViewer:5379). ConvoyScreen's onAreaSelected (883/1235) feeds an OLDER path (viewModel PendingDownload, one source = ACTIVE) -- TWO area-download flows exist; ONE MAP must use the planner's (DownloadBbox -> ConvoyDownloadConfirm).
- showDownloadedAreas: both hosts. showQueuedAreas/clearQueuedAreas: planner page only -> copy JS.
- ConvoyCorridorPicker: MapViewer:4911 only -- self-contained component, needs a small launcher (query tracks + picker + batch).
- Remove by area: inline AlertDialog in MapViewer (5229-5270) -> lift into a composable.
- Remove along a track (migrate gate): inline in MapViewer (~4942-5080 + 5340-5360: hold, cancel, scan, gate) -> lift into a composable. Heaviest.
- Recreate tiles / recovery: inline LaunchedEffect + results panel in MapViewer (~901-1060) -> lift into a composable.
- Import trails by state / by area: HomeStatePickerScreen -- already standalone (MapViewer x3, AuthorityGateV2).
- Import GPX: ConvoyTrackImportScreen -- standalone (nav route + Main overlay). Trails from tracks = RiderTrailsDialog INSIDE that screen (needs its own entry).
- Map sources: ConvoyMapSourceScreen -- standalone nav route.
- Archived / Download GTMAPS: display-only rows inside ConvoyDownloadPanel (Map Recovery section).
- Mileage: DEAD -- slider flyoverMileage is local to the panel and feeds nothing. Remove, do not move.
CORRECTION to 08:57 (download Y/N per source): getDownloadSources is NOT the choke point -- the worker downloads the job's own slot. The slot list is chosen upstream in the builders, all preSelected = true:
  MapViewer:4847 (area confirm), MapViewer:4902 (corridor picker), ConvoyScreen:2721 (area confirm), ConvoyTrackImportScreen:301 (import confirm -> downloadMapSlots -> automatic corridor), plus MapSourceScreen:616/:671/:627 ("SAT" hard-coded).
  Flag = the default tick in those builders (rider can still change it on the confirm); the SAT-hard-coded maintenance jobs stay as they are (they repair the SAT column).
CONCLUSION: the approach is supported. The split is UI-only; every option already ends in the same engine call. Real work = lifting 4 inline planner blocks (remove by area, remove along track, recreate, corridor launcher) into composables both hosts call -- one copy, no duplication -- and copying the queued-areas JS.

## 09:06 Phase 2 METHOD (Fred): no retrofitting the old functions -- breakage is harder to detect.
- Planner code stays untouched. Its inline blocks are COPIED as-is into new grouptrack/ files; ONE MAP calls the copies; both call the same engine.
- A misbehaving copy can be compared against the planner original on the same data; both misbehaving = engine, not the move.
- Cost: two copies until the ride map is removed (a fix in that window goes in both); the originals go with the ride map.
## 09:08 Order (Fred): finish MAP SOURCE SELECTION before any Maps functionality.
1 PERSISTKEY (grouptrack key) -> 2 persist map type + offline per map -> 3 map-type chip on the map -> 4 download Y/N per source (map_slots.json, switch in Map sources maintenance, default tick in the 4 confirm builders) -> build/test -> then Maps group.
Public/private: neither the new filter (classic shows nothing too, shared code) nor jobCategories (only fills blanks with PUBLIC) fits; suspect = trails stored PRIVATE by OwnershipReclass.run. Waiting on the Droid 1 re-import + Check a trail.

## 09:27 PUBLIC/PRIVATE ROOT CAUSE -- PROVEN on Droid 1 (P50, serial 8624SBCEDF00001789; the EP103B 24039703201775 had no matching log)
Log, Utah import finished 09:15 (1819 s):
  OwnershipReclass: ownership: 17388 rings, 9436 cells, 11s
  HomeStateImport: OOMGUARD-2026-09-15: step 8 ran out of memory -- treating as skipped
  java.lang.OutOfMemoryError ... 256 MB heap ... at CursorWindow.getString  (reading the trails cursor)
  HomeStateImport: step 8 skipped (no ownership data)      <- WRONG message: it ran out of memory
  HomeStateImport: Home state import COMPLETED for Utah    <- reported as completed
Cause: OwnershipReclass.run reads EVERY trail's geometry (WKT strings) into ArrayLists at once, on top of 17,388 ownership rings -> OOM on the 256 MB heap.
Effect: step 8 writes nothing -- no category, no use, no land for any trail. Trail detail shows no Land. Then on the next launch
  jobCategories (CATEGORYALWAYS-2026-10-10) fills every blank: category, use, and land = PUBLIC. So NO trail is ever PRIVATE.
  Fred's first suspicion (housekeeping moves everything to public) was right in effect: the launch job papers over a step 8 that failed.
  NOT the filter (shared code, ALL vs PUBLIC works on the values it is given).
Fix (2.7c, before the jobCategories removal):
  1. step 8 streams: trails read and written in pages (e.g. 2,000 rows by rowid), rings loaded once; memory bounded.
  2. an OOM / write failure is reported as FAILED on the import panel and in the log -- not "skipped (no ownership data)", not COMPLETED.
  3. a way to re-run land classification alone (no 30-minute re-import) -- for the test and for riders whose step 8 failed.
  4. THEN remove jobCategories (rule 3), and check every converged device carries real land values.
- 09:30 History (git): OOMGUARD 4204d71f6 (09-15 heap guard + OOM backstop, "streaming the read is the fix"), STREAMRINGS 372034976 (09-17 streamed the OWNERSHIP FILE), OWNERSHIPGUARD 2cc52edae (10-10 removed the stale heap pre-check). The TRAILS read was never paged -- today's OOM.
- 09:30 STEP8PAGED-2026-10-23 patch: run() pages 500 rows by rowid (own transaction per page, both stores, uses per page); a failed step 8 = stage "failed", run COMPLETED WITH ERRORS, log "step 8 FAILED: <why>"; Settings > "Classify trails again" runs step 8 alone (N classified, P private, or FAILED). jobCategories stays until proven.
- Build (Fred 09:30): PERSISTKEY + MAPTARGET + PERSISTVIEW + MAPCHIP + STEP8PAGED together. Download Y/N after.

## 09:37-09:57 Build 2026-10-23_0936 deployed, commit 237c27c5b pushed; map selector works (Fred 09:42). Map menu reworked (Fred):
- Map menu first level: MAP TYPE row, Map source, Map Downloads for Offline Use > (by area, drawn box, Downloads in progress, remove by area / along track, rebuild/recovery), Import Map Features > (tracks, trails, waypoints, routes), Map Features (original), Open the planning map, Area Search Radius.
- "Download queues" was the wrong name: the panel is a monitor -> "Downloads in progress"; panel title MAP DOWNLOADS IN PROGRESS; empty "No maps downloading".
- DISPLAY belongs in FILTER (09:52-09:57): MAP AREAS (Downloaded map areas, Map areas downloading -- overlays) and ON THIS PHONE (Archived maps, Imported trails/features by state) -> FILTER placeholders.
- Queue summary pill (existing collapsed DownloadQueuePanel) on ONE MAP where the classic SAT/TOPO/TOPO+ bar sat; shows only while downloading; tap -> details. Map button GREEN with a dot while maps download.
- Patch MAPTARGET3 (patch_map_target_2026-10-23_v3.py); v2 renamed _SUPERSEDED_DO_NOT_RUN (never run).

## 10:04-10:11 Map menu deliverable (canvas "GroupTrack Map Menu Build-out", one panel by section) -- Fred's corrections
- Queue summary at the TOP of the Map menu (summary line + DETAILS > the downloads panel); "No maps downloading" when idle.
- Map TYPE is not in the menu -- it is the Map: SAT button on the map. MAP SOURCE stays (with Download Y/N per slot).
- Download by area and by drawn box are ONE function: Download maps by area (draw a box).
- Download maps along tracks is a row: ALL tracks or pick track(s) (planner "Download Tiles by Corridor"); new imports still queue their own corridor.
- Removal is ALWAYS by area. Planner "Remove Tiles by Track" (08-07 corridor migration: deletes every SAT track corridor and re-downloads) -- Fred was not aware of it; NOT carried into ONE MAP (planner copy untouched, goes with the planner).
- Sections: Downloads in progress / Map source / Download maps for offline use / Remove maps / Repair (Rebuild maps, recovery) / Import Map Features > (next) / Until the move is done (Map Features, planning map, Area Search Radius).
- patch_map_target_2026-10-23_v3.py (submenu version) is ON HOLD -- replaced once the menu is agreed.
- 10:12-10:14 "Remove Tiles by Track" IS WIRED (51dfe47ab REMOVETRACK-PREVIEW, 0c1415cd4 CORRMIGRATE): deletes every track's SAT corridor and re-downloads them. Fred: leave it in place -> "Remove maps by track" stays in REMOVE MAPS.
- 10:18 AGREED (Fred): option B -- TWISTIES, one section open at a time, menu reopens on the last section used. Canvas shows A expanded / B twisties / C submenus.
- Patch MAPTARGET4 (patch_map_target_2026-10-23_v4.py): queue summary on top (DETAILS -> downloads panel; "No maps downloading"), Map source, twisty sections DOWNLOAD (by area, along tracks) / REMOVE (by area, by track) / REPAIR / IMPORT MAP FEATURES / UNTIL THE MOVE IS DONE; map type out of the menu; pill on the map + green Map button; panel title MAP DOWNLOADS IN PROGRESS. FILTER unchanged (next). v3 renamed _SUPERSEDED_DO_NOT_RUN.

## 10:18-10:33 Agreed menu applied (MAPTARGET4, FILTERAREAS2, MAPRECOVERY2) + Fred's ORDER for the rest of 2.7c
- MAP RECOVERY = the daily map recipe (ConvoyMapRecipe: coordinates of every downloaded area per slot, 60 days) so maps can be recreated after a clear as of a date. Rows: Restore maps as of a date (NOT BUILT -- replay must be AREA jobs at REFRESH priority) · Refresh maps by source (planner Recreate Tiles by Source) · Imported trails / features by state. "Archived maps" = Restore's date picker.
- FILTER: one line before Include private land -- OFF-GRID MAPS [ ] Available [ ] Downloading (placeholders until the area JS is copied).
- Launching any function closes the Map menu (Map panel does not stay).
- ORDER (Fred 10:33): 1) build out and ATTACH all EXISTING functions (copies, planner untouched) so the old maps can be pruned; 2) then persistence (start-up from ONE MAP, ride map re-read) and Route+ / Add Route; 3) Restore maps as of a date (new, not built) moved to the END of 2.7c.
- Already in from persistence: PERSISTKEY (grouptrack key) and PERSISTVIEW (map type + LOCAL/NET per map).
- 10:35 NEW 2.7c TASK (end of 2.7c, after Restore): get a standing grant to the phone's Downloads so GroupTrack can write ITS OWN file picker for our processes (GPX import, ride import, recipes, configs) instead of the system picker.
  Constraint to design around (Android 11+): ACTION_OPEN_DOCUMENT_TREE refuses the ROOT of Download/ (also storage root, Android/data, Android/obb); a SUBFOLDER (e.g. Download/GroupTrack) can be granted and kept with takePersistableUriPermission. MediaStore.Downloads shows other apps' files only through SAF; All Files Access is not an option (Play appeal rejected 09-11). Decide: one-time grant of Download/GroupTrack + "save/move files there", or another route.
- 10:37-10:39 (Fred) MOVED TO THE 2.7c INSTALL / RELEASE BUILD checklist -- done together, at release time:
  1. Remove jobCategories + OwnershipReclass.applyMissingCategories -- patch READY: ~/Downloads/patch_remove_jobcategories_2026-10-23.py (NOJOBCAT; dry run passed 10:37). Do not run before then.
  2. Add a ONE-TIME housekeeping classify for upgrading phones (2.7a/2.7b + Utah import = every trail PUBLIC): runs step 8 (OwnershipReclass.run) when trails exist AND the ownership file is usable AND NOT ONE trail is PRIVATE -- a derived condition, no marker (rule 3 spirit); a COUNT afterwards; out when 2.7d is cut. Background housekeeping thread. Needs: Fred's OK on the condition + the Classify-trails-again duration on Droid 1 (launch-time vs after the map is up).
  3. Release-cut check for rule-3 jobs (from 08:04).
- 10:40 (Fred) After the trails are verified the deck is clear for the map functions build-out. AREA DRAW SIMPLIFIED: hide all the planner panel's selections/options -- ONE shared "draw an area, then launch": pick Download maps by area / Remove maps by area / Import trails by area from the menu -> bar "Draw the area -- <FUNCTION>" (CLEAR, CANCEL) -> box -> that function's own confirm -> launch. Uses activateDrawMode/clearAreaBoundary (already on convoy_map.html) and ConvoyScreen.onAreaSelected re-pointed at the waiting function (was the older one-source PendingDownload path).
  Attach order now: 1 Import trails by state; 2 draw-an-area + its three functions; 3 GPX, trails from tracks, along tracks, remove by track, refresh by source, the rest.

## 10:51-11:14 Private land FIXED (Fred 10:51: Classify trails again worked). Map functions as separate callable modules (Fred 10:53)
- Fred 10:53: break all downloads and deletes into separate screens = callable modules, WITHOUT any change to the underlying functions.
  grouptrack/MapScreens.kt: GtDownloadAreaScreen, GtRemoveAreaScreen, GtImportAreaScreen (GTAREA2), GtDownloadTracksScreen (GTTRACKS), GtQueueSummary (GTQSUM), GtOffGrid (GTOFFGRID).
  grouptrack/AreaDraw.kt: GtArea (which function waits, the box) + GtAreaHost (draw bar CLEAR/CANCEL, then the module). ConvoyScreen: 3 small hooks.
- Bench 11:08: download by area + remove by area WORK. Box alone did not say what happened -> toasts "Download queued -- N tiles from ..." / "Removing tiles in this area".
- Menu (Fred 11:08-11:11): NO twisties -- section TITLES in plain text, options indented below, everything shown; panel scrolls (GtPanel heightIn+verticalScroll). MAPOPEN.
- Fred 11:04: downloads summary must ALWAYS show and open queue details -> GtQueueSummary on the map (top left) and top of Map menu; "No maps downloading DETAILS >" when idle. DETAILS = the existing DownloadQueuePanel expanded (cancel, cancel workers, hold/resume/release, promote = reschedule, remove, concurrency, AREA/CORR/RFSH) -- unchanged (Fred 11:12).
- FILTER OFF-GRID MAPS wired (GTOFFGRID): Available = planner's SAT z16 merged coverage (showDownloadedAreas); Downloading = queued/downloading job boxes (showQueuedAreas/clearQueuedAreas COPIED to convoy_map.html), redrawn as the queue changes.
- Applied 11:13-11:14: GTQSUM, GTTRACKS, MAPOPEN, GTOFFGRID; build running.

## 11:15-12:20 All existing Map functions attached; audit done
- Menu as PLAIN TEXT (Fred 11:42, MAPTEXT): bold section titles, functions as left-aligned indented text lines, no boxes; panel scrolls.
- Right column starts level with RECORD (RIGHTTOP, Fred 11:15).
- Download maps along tracks: track-name search (GTFIX1) + HELP / X header (GTFIX3, Fred 11:53 -- could not exit after a search).
- FILTER Available: kept as GTOFFGRID (z16) -- GTFIX1 changed it by mistake, GTFIX2 restored (Fred 11:36: filter was working).
- MAP RECOVERY: + Show saved map recipes (60 days) -- GTRECIPES, the planner's ARCHIVED / received GTMAPS list (Fred 11:43).
- "Imported trails / features by state" moved from MAP RECOVERY to IMPORT MAP FEATURES (Fred 11:58).
- Attached as callable modules (GTIMPORTS, GTHEAVY): Import trails by state, Import GPX, Create trails from tracks, Remove maps by track (CORRMIGRATE gate copied), Refresh maps by source (RECREATE copied).
- AUDIT (12:16): every existing planner / ride-map map function is on ONE MAP except Add Route / Route+ (moves last). Not carried: Mileage, Flyover (Area Search Radius -- SEARCH_FLY_ZOOM is written, never read), V3.0 download stubs, Import OSM by Area (retired 08-21), "show downloaded trails" (never built).
- ORDER (Fred 12:20): the TO-BE-WRITTEN items go into 2.7c AFTER the replacement of the old maps by ONE MAP is completed:
  Restore maps as of a date; Imported trails / features by state (a list: state, date, trails, sources, private count); Area Search Radius (decide); Download Y/N per source / sources on the draw bar (11:18 design); own Downloads picker (10:35).
- Build 2026-10-23_1201 (GTFIX3, IMPORTEDMOVE, GTIMPORTS, GTHEAVY) built; bench test pending, then commit.

## 12:20-13:16 Acceptance build, fixes, launch persistence, Route+ trace
- Fixes 12:27-13:06 (Fred bench tests): GTSTD (every Map module to the standard: HELP X + scroll; Import trails frame with CANCEL IMPORT -- the real HomeStateImportController.cancelImport; startup gate too), GATEZERO (start-up home-state selector ONLY with 0 trails -- an unfinished import no longer takes over start-up), GTSYNC (RESYNC TRACKS and ADD TRAILS FROM TRACKS off the Import GPX screen; RESYNC got its own row first: Import Map Features > Resync tracks with their files), GTRADIUS (Area Search Radius made real: Z12-Z18, saved, ONE MAP search flies to it; was SEARCH_FLY_ZOOM written, never read), GPSSTD (GPS panel HELP X + scroll), RMALLTRACKS (label "Remove maps for all tracks"), NOJUMP (ONE MAP: page's late initDeviceLocation and the one-time fit-to-carts no longer move the map -- Beryl search jumped to my cart, not recording).
- The file-picker notice before Import GPX is FILEPICKNOTICE-2026-10-10 (Fred's text), not a permission grant; own Downloads picker stays on the after-replacement list.
- Launch persistence (Fred 12:53-12:56): ONE MAP online = restore saved frame; OFFLINE = centre on my cart ("easier"); first launch = my cart; recording = follow. Map type / LOCAL-NET / layers restored (PERSISTKEY, PERSISTVIEW). Return from the planning map rebuilds the page -> PERSISTVIEW re-applies ONE MAP's own type. No more code needed beyond NOJUMP.
- ROUTE+ TRACE (13:13): the real Route+ is ~40 locals + all dialogs inside ConvoyMapViewerScreen (352-728, 2250-4490). The ride map holds an OLDER copy (CS 2445-2705: no AI, no compare, no trailhead check, no notes, no auto-save, no recovery). convoy_map.html forces __routeMode=false before the tap test (C:512) -- route taps never reach onMapTap; CS onMapTap is the old 30 m snap. Missing on convoy_map.html: showAimRing, drawBatchRoute/showBatchRoute/clearBatchRoutes/fitBatchRoutes, renderPins, showProximityPopup, __pinSelect branch (+ CS onProximityTap). CS writes RouteState = null (no recovery). 10-22 stranding likely: AI mode starts the pin checklist (hides the toolbar) + back blocked in routeMode (inference).
- DECISIONS (Fred 13:13-13:16): RouteState per map (by map name); route DRAFTS stay SHARED (no map name). Manual or AI is chosen BEFORE Route+ launches (ROUTESTART 10-22, presetMethod/methodLocked) -> each run is one mode.
- PLAN: lift Route+ out of the planner into ONE module both maps call (map key, page, taps). Stage 1 Build it myself on ONE MAP; Stage 2 AI route design (+ its JS); Stage 3 remove the ride map's older copy, planner calls the module.
- 13:18 (Fred) ROUTE+ ENTRY ORDER -- the checks come BEFORE the manual / AI prompt:
  1. unresolved AI batch (compare set open) -> open the COMPARE table, stop;
  2. unresolved manual draft (in-progress route, or one left open by a crash -- this map's RouteState) -> the in-progress RESOLVER / picker (resume, rename/save, discard), stop;
  3. nothing pending -> ask Build it myself / AI route design -> Route+ opens in that mode, locked.
  "Clear leftover routes" stays its own row. (Planner's gtRoutePlus MVS 2300-2404 does 1-2 after the 10-22 menu choice; Stage 1 moves them in front.)
- 13:33 COMMITTED + PUSHED 1c4e3f86f -- every existing Map function attached to ONE MAP, screens to standard, launch persistence accepted (QSUMIDLE: downloads summary on the map only while downloading; always at top of the Map menu).
- 13:35 Route+ stage 1 design corrections (Fred): (1) NEVER delete leftover AI sets at launch -- only from the compare table (the planner's ROUTEPLUSCLEAN-2026-10-07 stays in the planner, untouched, goes with it). Entry order = open AI compare set -> unresolved manual draft / crash recovery -> launch the chosen mode. (2) One mode launched; the other is NOT displayed (toolbar methodLocked already shows one line "BUILD IT MYSELF"/"AI ROUTE DESIGN", no chips) -- the module always launches locked.
- Stage 1 = grouptrack/RouteModule.kt (GtRoute state with the map's name, start(), onTap() with per-point auto-save, GtRouteHost: locked toolbar, picker, resolver, recovery, save choice, discard, trailhead prompt -- HELP X each) copied from MVS; ONE MAP hooks: page stops forcing __routeMode=false (ONE MAP only), both tap bridges -> GtRoute.onTap while running, RouteState saved to grouptrack, back blocked while building but toolbar X always exits, Route rows -> GtRoute.start(method). 1a then 1b, one build.
