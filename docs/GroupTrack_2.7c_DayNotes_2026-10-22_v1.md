# GroupTrack 2.7c — day notes 2026-10-22 (to fold into tonight's living master v166 / handoff v72)

Running list of decisions and open items from the 10-22 attach session. Everything here goes INTO the living docs at end of day;
this file is only the holding place until then.

## OPEN DECISION — private trails default (Fred 14:03, Beryl UT)
- Seen: private community/residential roads show on the map in Beryl; they disappear when the filter is set to public only.
- Finding: NO functional change today. FILTER's "Include private land" calls the same TrailFilterState.setLand the old filter
  used; the 2.6h public/private classification (Utah's own determination + PBF access tags) was not touched. The roads
  disappearing on "public only" PROVES they are classified PRIVATE correctly.
- Cause: the default is ALL (Include private land ticked) — always has been (TrailFilterState.reset: land = "ALL").
- Decision needed: keep default ALL, or default to PUBLIC ONLY (private hidden until asked for). Claude leans PUBLIC ONLY.
  Riders with a saved setting keep theirs either way.

## Rules set today
- Standard: HELP ✕ top right everywhere (✕ = leave; the COMPLETE is the screen's own action button). Panels that stay add
  ☰ MOVE and HIDE/SHOW. One help viewer (GtHelpView) on top of every screen.
- Touch rule (12:50): when a function is touched, EVERY screen it opens is brought to the standard in the same patch.
- Nothing is hidden/removed without a named home in the same patch (11:47).
- One-option rule: a button whose module has one attached process runs it directly.
- Persistence (13:30-13:33): ONE GroupTrack map state file; purpose = between sessions, return to where you left off.
  Start-up ALWAYS restores the saved view (no exceptions); Centre on Me brings you to where you are. Save as you go.
  Restore: map, then layers, then stay-panels (positions, collapsed). Never reopen one-shot dialogs. Work in progress comes
  back through its own owner (draft resolver, recorder). Spec to be drafted with the Map mock.
- Route+ (13:48-13:58): very complex — do NOT take apart. ADD ROUTE passes manual or AI to the EXISTING planning-map Route+
  (all its start-up checks), method locked. Clear leftover routes = the old Settings cleanup, asks first.
- Filter after, not before (search): one box searches everything; result types are the filter.

## Hidden-controls ledger (classic controls hidden in One Map -> home)
- GROUP/MY CART -> readout + View: live | GRP Awareness -> own button: live | Help -> MENU: live | +/- -> left: live
- Apply ride to radio -> Radio Setup › Configure: live | Archive configs -> Radio Setup › Archived: live
- Rides share/edit/delete -> Rides › My rides: live | Import a ride -> Rides › Import: live
- CHECKIN/SELECT CART, VIEW CART ROLES -> Rides › carts: live | LOCAL/NET -> Status: live | Search bar -> Search: live
- Map Keys -> Map Key: live | Map Features display -> Filter: live | Map Features +ROUTE -> Add Route: live
- SAT/TOPO/TOPO+ -> Map › Map type: PLACEHOLDER | QUEUES -> Map › Queues: PLACEHOLDER | rest of Map Features -> Map: PLACEHOLDER
- CONVOY/PLANNING MAP switch: HELD (reach the planner from the classic map until Map/Route absorb it)
- HIDE (collapsed HUD + bottom row, expand icon): HELD — decide later
- HELP on old screens: done for every screen touched so far (TOUCHSTD, CLOSEX, CHKINSTD, GTRIDES).

## Next
- Map: design pass first (inventory every map function from S1; group them; downloads get one clear path) -> mock -> build.
- Persistence spec with the Map mock.
- Route combos: Fred testing (starts, pauses, in-progress, save, discard).
- Search later: keep OSM place names (osm_places / osm_natural are extracted then thrown away at archive) -> offline places.
- Waypoint symbols in MAP KEY: Fred to decide. Lead-track sample colour in MAP KEY: check against the map.
- Commit everything since c139d7a36 once the current build passes.

## Persistence — for the strategy discussion after this build is certified (Fred 14:47-14:48)
- Inventory: 27 uses of MapStateStore keys ("convoy" / "planning"): ride map 9, planner 9, shared helpers 4 (detail card and
  SELECT list default "convoy"; fit-to-feature saves by key; saved-selection redraw reads by key), TrailCheck 1 (reads both),
  MainActivity 1 (DELETES the ride map's state on every cold launch -- [Fix2]), screen-identity flags 3 (lastMapProcessed,
  search mapContext -- name the screen, not storage).
- Fred's idea (14:47): every read/write uses the value of the CURRENT MAP. Fred 14:48: add a THIRD map key and differentiate by
  map -- e.g. "grouptrack" for ONE MAP, "convoy" for the classic layout, "planning" for the planner (until it is absorbed).
- Not stored at all today: map type (resets to SAT on restart), offline mode; the ride map holds its state IN MEMORY
  (rideMapState, 13 values) so it does not re-read the file when returning from the planner.
- Rule already set: start-up ALWAYS restores where you left off; Centre on Me brings you to where you are.

## Tomorrow (Fred 14:51-14:52)
1. PERSISTENCE first -- third key ("grouptrack" for ONE MAP), every read/write by the current map; then map type, offline mode,
   the ride map re-reading its state.
2. ONE MAP START-UP -- understood WHILE doing persistence, ready to implement when the attach work is finished: the whole path
   from launch to a usable map (MainActivity start-up + the cold-launch delete, the gates -- authority / terms / profile /
   launch route, the start destination, ConvoyScreen entry, onPageFinished restore and the GPS fallback, the docs launch
   (Quick Start / release notes), route / draft recovery, start-up housekeeping, group mode load, the layout switch) -- mapped
   to what ONE MAP must do when GroupTrack.kt becomes the start screen and ConvoyScreen is retired.
3. ROUTE+ IN ONE MAP -- Add Route must launch its functions and views ON THE MAP IT IS INVOKED FROM (not the planner).
4. ADD ROUTE TESTING -- starts, pauses, in-progress, save, discard, compare -- before anything else moves.

## Remove when the attach work is finished (Fred 14:55)
- MAP FEATURES panel (ConvoyArtifactsPanel) -- no purpose any more: display -> FILTER; its import buttons and +ROUTE -> the
  Map planner rows and ADD ROUTE once those are hosted in ONE MAP. Remove the Map › "Map Features (the original panel)" row
  with it. Check nothing else opens it first (the planner's own +ROUTE path lives in it until Route+ moves).

## TOMORROW'S ORDER -- FINAL (Fred 15:02, supersedes the list above)
1. PERSISTENCE READ/WRITE IN ONE MAP -- first thing, period. ONE MAP gets its own state ("grouptrack"), read and written by the
   current map; EVERY map's state captured on EVERY map (view, layers + selections, map type, offline mode, panels) -- nothing
   left in memory only.
2. MOVE THE PLANNER FUNCTIONS into ONE MAP (Route+ first -- Add Route launches on the map it is invoked from; then the Map
   planner rows: download, flyover, show downloads, remove, repair, import trails, import GPX, trails from tracks, mileage).
3. CHANGE START-UP to launch the app from ONE MAP's persistence (the start-up path mapped while doing 1).
Add Route testing runs as 2 lands. Open from 14:59: where Fred sees FLYOVER on the ride map (code shows it only in the planner's
download panel).
