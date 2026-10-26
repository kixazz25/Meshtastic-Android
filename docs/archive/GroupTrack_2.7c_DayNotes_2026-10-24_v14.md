# GroupTrack 2.7c — day notes 2026-10-24 (v14)
Branch 2.7c. Start of day run (Fred 08:51). Previous day: GroupTrack_2.7c_DayNotes_2026-10-23_v4.md (last commit 8912d3c98).

## 08:28-08:38 THE ORDER OF WORK (Fred)
1. SCREEN STANDARDIZATION -- the standard applied to every screen at once and the model rebuilt (not screen by screen).
2. PROCESS SIMPLIFICATION (step 2 of screen simplification, Fred 08:36): every piece of data a process needs is captured on its FIRST
   screen and passed into the processes that follow -- no follow-on screen launched just to fetch a value.
3. ALL FUNCTION REVISIONS, including the pending 2.7c fixes (Fred 08:38: they may change screen designs).
4. THE MANUAL -- LAST. Built on the existing manual's text and description decorations (keep as much of that work as possible).
   A screen that appears in several processes is captured in EACH process it appears in (replaces the 10-01 "capture once" rule).

## THE MANUAL METHOD (Fred 08:28-08:31; prior round 09-27..10-01 documented in GroupTrack_ScreenCapture_Plan_2026-09-27_v4.html,
## GroupTrack_CaptureFolders_2026-09-27_v2.sh, GroupTrack_CaptureManifest_2026-09-30_v4.json, GroupTrack_LabelMap_2026-09-30_v4.html,
## GroupTrack_Captures_2.7a_2026-10-01_v4.tar)
- Count every list / information point on the map screen and label them A-Z (then AA, AB ...). EVERYTHING on the map is lettered --
  controls, information displays (GPS status, N - Z18, readout fields), map content (carts, routes, labels) and gestures (Fred 08:54).
- Claude builds an HTML model of the screen from Fred's Droid image with each letter and a functional description; Fred drags the
  letters to their launch points; that is the starting point for the manual.
- Function references then trace each next-level call (screen to screen). A DECISION FORK (e.g. drop pins yes/no on the AI route
  planner) splits the screen sequence; in the documentation only ONE path is open at a time, everything else collapsed.
- Capture tree: Claude sends a tar/folder structure named with PROCESS and FUNCTION names that mimics the call structure, fork paths
  included; Fred copies screen images into it and posts it back; Claude replaces the images in the manual.
- How the current manual keeps one path open (read 08:33, manual v65): nested <details> levels l1-l5; opening one closes its SIBLINGS
  (never an ancestor); an ancestor with an open child hides its own text, pictures, tables and letter list (class haschild) so only the
  trail of headings shows; links open the target and every ancestor; lettered map pictures are <div class="lm"> with <a class="lmk">
  letters at left/top % plus an <ol class="letters"> list. GAP: inside a process all steps are open together (e.g. 4.5.9.2 shows six
  steps and ~12 pictures at once). FIX: each process step becomes the next <details> level (one step open at a time); a fork becomes
  sibling <details> inside its step; a fork that changes later steps carries those steps inside itself. Same shape as the capture
  folders: chapter / letter / function / step-or-fork.
- 2.7c: manual chapters 4 (Ride Planning Map) and 5 (Ride Map) are flagged "replaced by One Map" -> ONE chapter, ONE lettered picture.
- Screen captures are taken AFTER the colour pass (positions do not move, so lettering can go first).

## ONE MAP DOCUMENTATION FOLDER (Fred 08:51-09:17)
- Fred's folder "One map documentation" now lives in My Drive/GroupTrack/One map documentation (moved 09:13; the Desktop item is a
  shortcut to it). Holds: "one map Grouptrack launch points" (Group, not checked in), "Android only", "no checked in with route add",
  GroupTrack_LabelMap_2026-10-24_v5.html (superseded) and GroupTrack_LabelMap_2026-10-24_v6.html.
- LABEL MAP v6: tabs ONE MAP - Group (35 letters A-AI), ONE MAP - Android only (36), ONE MAP - recording (empty: load screenshot, Copy
  letters from ONE MAP), MENU (empty). Each letter: kind (control / display / map / gesture), What it does, While recording, Android only.
  Group letters: A MENU, B/C zoom, D GPS status (red NO GPS LOCK, green GPS LOCK n at 4+ satellites), E N - Z18 (north up, zoom),
  F CHK IN/RECORD, G GRP Awareness, H Mode, I Status (internet), J Map: SAT, K MAP KEY, L FILTER, M CENTRE ON ME, N map labels,
  O a route (magenta, arrows = direction), P another cart, Q my cart, R GPS, S send interval, T channel use, U text light/dark,
  V GROUP readout, W SPAN, X Carts/Active/Lost, Y Lead/Tail, Z Map, AA Add Route, AB Rides, AC Search, AD RADIO SETUP, AE Leaflet,
  AF long press -> waypoint, AG tap a feature, AH drag/pinch, AI Distance traveled (recording only).
  Android only differs: F REC (no check-in), G grey (no radio), H Mode: Android only, R GPS greyed (S, T still shown), no P, no W-Y,
  no AD (bottom bar re-spaces); MY CART readout AJ Heading, AK Battery (to confirm: radio or tablet), AL Altitude, AM Speed,
  AN To Lead - To Tail, AO Gap Ahead - Gap Behind (also in Group with View: My Cart).
  On a ride: AA Add Route becomes View: My Cart / View: Group (Group; nothing in Android only); H blocked while recording.
  TO CONFIRM: cart marker colours (P square, Q star) changed between Fred's screens -- role or the rider's team colour.
- Still to capture: ONE MAP while recording; MENU open.

## STD1 -- THE COLOUR PASS (applied 09:32, built 09:36 first time: 2026-10-24_0936_mac_8912d3c98_UNCOMMITTED)
- patch_std1_colours_2026-10-24.py. The standard (grouptrack/GtPanel.kt): each scheme gains its own accent / good / warn / bad
  (satellite white panel: accent 1565C0, good 1A7F37, warn 9A6700, bad CF222E; topo blue panel: accent 9CD3FF, good 7EE787,
  warn F2CC60, bad FFA198) and one accessor Gt.panel / text / dim / line / accent / good / warn / bad / button / buttonText.
- 1,394 colour uses changed in 72 files (text 438, accent 334, panel 288, dim 110, warn 78, bad 60, good 52, line 25, GtColors 9);
  23 data colours kept (MapKeysPanel LineSwatch + MapKeyRow tables, TrailFilterPanel FilterSwatch, ArtifactDetailPanel cartoStyle,
  ConvoyRiderProfileScreen TEAMS, TrailStylePanel PALETTE, ConvoyMapSourceScreen SAT/TOPO badges). Colour properties became getters
  (follow a map-type change); coloured backgrounds became tints (alpha 0.22). Every replacement: ~/Downloads/STD1_report_2026-10-24_v1.txt.
- NOT in STD1: ConvoyScreen (the ride map draws readouts straight on the map -- STD1B, with the phase-3 module moves); MaterialTheme
  colours (41 uses) and Color.Black / Transparent -- STD2.
- Test-run lessons: the patch was proved on a copy first; a fully qualified androidx.compose.ui.graphics.Color(0x..) first came out as
  androidx.compose.ui.graphics.Gt.text -- fixed before release.
- BENCH (Fred 09:40-09:41): Settings screen did not change (it uses MaterialTheme.colorScheme -- 12 onSurfaceVariant, 2 primary);
  Help / info panel did not change (GtHelpViewer shows the manual / notes / Quick Start HTML, whose look is set in those documents).
  Both -> STD2 list.

## STD2 LIST (screens that draw colour another way -- one rule each, not screen by screen)
- MaterialTheme colours: ConvoySettingsScreen and the other 41 uses.
- The in-app documents (Help viewer): the manual / release notes / Quick Start HTML styles.
- HELP X framing (GtPanel frame) for screens without it.
- ConvoyScreen ride-map panels (STD1B).

## NEW TASK (Fred 09:40): UTAH PRIVATE-PROPERTY CLEANUP -- its own task under MAP
- "In case anybody in the field has the same issue I had": the town street grids (e.g. Beryl Junction) arrive in OSM as
  highway=unclassified, indistinguishable from backcountry roads; OwnershipReclass (OWNERSHIP-2026-08-31, step 8 of the state import)
  classifies them against the land-ownership polygons (any public segment wins) into the R - Residential Roads category -- a
  CATEGORY, not a deletion; the rider turns it off and the town grid goes.
- Today it is reachable only from Settings ("Classify trails again" -- ConvoySettingsScreen ~line 210-235, OwnershipReclass.run(s, e));
  also run by HomeStateImportController (state import) and StartupHousekeeping (applyMissingCategories).
- TO DO: give it its own row on the Map menu (under IMPORT MAP FEATURES or MAP RECOVERY -- Fred to choose), named for what the rider
  sees, e.g. "Clean up private / residential roads (Utah)"; same function, no logic change; Settings row removed once it has its home
  (nothing removed without a home). Part of step 3 (function revisions).

## 09:45-10:49 THE STANDARD, CONTINUED (all applied; last build 2026-10-24_1047_mac_8912d3c98_UNCOMMITTED has STD1-STD1I + RIDESEARCH 1-3)
- STD1C (Fred 09:45 "all text blue on white, or white on blue; change all text, then pick the ones that need another colour for a
  reason"): satellite text navy -> blue; Gt.dim / accent / good / warn / bad / line all read the TEXT colour for now (the per-scheme
  values stay defined for the exceptions; every use is in STD1_report).
- STD1D (09:46 "blue boxes with black letters"; "if a box is blue the letters must be white"): GtTheme -- every GroupTrack screen route
  (33, ONE MAP included) is drawn inside a Material theme built from the scheme: SAT = white panel, blue text, blue buttons with white
  letters; TOPO = blue panel, white text, white buttons with blue letters. Uncoloured text = the text colour (LocalContentColor).
- STD1E (09:54-09:55 "washed out"; "titles darker, content paler -- titles larger, same colour"): blue 1565C0 -> 0D47A1 (8.6:1);
  content text Medium weight; panel titles 18 sp.
- STD1F SUPERSEDED (never run) -> STD1G (10:01-10:06 "same colour background and text"; "no fonts smaller than 12 pt"; "turn
  semi-transparent off"): ROOT CAUSE -- STD1 read Surface(color = X) as a TEXT colour; 107 boxes drew in the text colour. Each restored
  from what it WAS (STD1_report): grey / dark / white card -> Gt.panel (33); a STATE or a colour -> a tint (74). 571 font sizes under 12 sp
  -> 12 sp (every file, ride map included) + the theme's small styles floored. Panels solid (FFFFFF / 0D3B73, were 97%).
- STD1H (10:14 "selections of all boxed items -> a check mark, blank if empty -- we colour the box today"; 10:15 "download maps by
  corridor still transparent"): 19 more see-through panels solid; 21 selections: 8 with a checkbox stop changing colour, 13 that showed
  the choice only by colour read "✓ label" (Map Features OFF/ALL/SELECT, FILTER segments, Search scope/type chips, state list,
  queue chips, track-export tabs, trail-source method cards, enrollment type, rider-profile role, ALL RIDES).
- STD1I (10:27 "proceed with the six screen fixes", from Fred's 10:24 method "search for backgrounds not equal to the standard": 58 in 20
  files, 24 data/status): AI route design steps (the file STD1 skipped -- its @Composable is written in full), Check in (black -> panel),
  GroupTrack buttons (panel-colour letters on the blue box), Field radio (disabled text was invisible), ride-map dialogs (Location
  permission, Select lead cart, Help viewer, MAP DOWNLOADS IN PROGRESS, ride-import splash, View cart roles -- neutrals only; status
  red/green kept), GPS interval presets (a check mark). KEPT: REC / PAUSE / END, ROLE CONFLICT, role-held warning, contact-lost banner,
  the HUD (GPS row, readouts, pill, GPS light, N - Z).
- AICHECK (10:49 "AI acceptance panel shows a blue block, no checkbox"): the hand-drawn opt-out box -> the standard Checkbox. Patch
  ready (patch_ai_optout_check_2026-10-24.py), dry run passed.
- RIDESEARCH 1-3 (Fred 10-23 22:41; 10-24 10:22, 10:30, 10:34, 10:35): Rides > Create a ride opens SEARCH (its text: "This panel positions
  you by area, track, route or waypoint. The next step displays all routes in that area for you to select the route to build a ride
  from. You can turn tracks on to create a route from a track." + PROCEED TO RIDE CREATION); after Search a bar "Create a ride: move the
  map to where you will ride" -- SEARCH / PROCEED TO RIDE CREATION / CANCEL (stays while a track's card is open, so a track can be made a
  route first); the ride form as it exists lists only the routes in the map area ("Routes in the map area: n", SHOW ALL). Opening the
  form any other way is unchanged. New: grouptrack/RideStart.kt (GtRideStart).
- Method lessons: a patch is proved on a COPY of the current code before it reaches Fred (STD1 fully-qualified Color, STD1G call-span
  search, the selected-row state, the 11 sp after a multi-line string, a duplicate import -- all caught that way).

## 10:51-10:55 OPEN 2.7c FUNCTION ITEMS -- Fred's dispositions (living master v167 list)
- OPEN: Restore maps as of a date (MAP RECOVERY row exists; the replay of a daily map recipe is not built).
- OPEN: Draw bar with sources and Replace, saved defaults (also covers download Y/N per map source).
- OPEN, WIDENED (Fred 10:55): GroupTrack's own Downloads picker is for ALL IMPORT TYPES, each SPECIFIC TO ITS FUNCTION -- e.g. a ride
  picker (Import a ride), a GPX picker (Import GPX), and so on -- not one generic picker.
- DONE: Area Search Radius -- its home (Map menu > SEARCH section, 10-23 MAPMOVE).
- OPEN: Utah private-property cleanup as its own Map row (IMPORT MAP FEATURES or MAP RECOVERY -- Fred to choose).
- OPEN: Process simplification (step 2) -- data captured on the first screen and passed on, no follow-on screens.
- Also open from the list (no change today): ride map's older Route+ copy; start-up from ONE MAP; imported trails / features by state
  list; bench checks (odometer, compare SAVE / DELETE-ALL, AI opt-out not remembered, keyboard over the step panel, cart marker colours);
  Fred's open decisions (private-trails default, GPS light wording, flyover mileage slider, lead question at REC, polyline dots, waypoint
  symbols in MAP KEY, row-level HELP, HIDE, the 19 non-selection boxes that change colour, the GPS dialog's amber warning); the
  release-build checklist (NOJOBCAT, one-time classify, rule-3 cut); carried from 2.7b.

## 10:57 Fred, refining two of them
- Restore maps as of a date: ON HOLD until the last item (services).
- Draw bar with sources and Replace -> REDEFINED: Maps shows the download-map values (which map sources, Replace existing tiles) and lets
  a DEFAULT be set there. Once a default is set, the prompt NO LONGER APPEARS AT ALL -- every download uses it. This moves into the
  LAUNCHING screen's process (step 2, process simplification: the values are known up front, not asked by a follow-on prompt).
- 10:59 Fred, the design: the ONLY field on a download's launching screen is one Y/N about the download defaults.
  YES -> the download screen opens with the defaults filled in (map sources, Replace existing tiles); the values can be changed and SAVED
         as the new defaults; then the download runs.
  NO  -> the download runs with the defaults, no further screen.
  (Label wording to confirm with Fred: "Use download defaults Y/N" reads the other way round -- proposed "Change download settings? YES / NO".)
- 10:59 Fred: "you can move the defaults to the first panel and let them be changed there -- I have no preference." DECIDED (supersedes
  the Y/N field above): the download's launching panel SHOWS the defaults in one line -- "Download with: <sources> - Replace existing
  tiles: <Yes/No> -- CHANGE". Run as is = no prompt, no other screen. CHANGE opens the values IN PLACE on the same panel (tick sources,
  Replace) with SAVE AS DEFAULT. Nothing opens on top. Step 2 (process simplification); applies to every download launch (by area,
  along tracks, a route's DOWNLOAD MAPS, the AI compare set's tiles).
- 11:00 Fred: "I would not change the defaults, only change this run." FINAL: CHANGE on a launching panel changes THIS RUN ONLY (no
  SAVE AS DEFAULT there). The DEFAULTS are set in ONE place -- the Map menu (download settings: map sources, Replace existing tiles).

## 11:04-12:07 ISSUE + COMMIT
- ISSUE (Fred 11:04-11:11): HELP - ISSUE - X on every screen (GtHelpButton draws HELP and ISSUE; the GtPanel title bar too; GtTheme draws the
  report panel). ISSUE captures the screen (a dialog's own window when it is a dialog), + NEXT SCREEN > CAPTURE adds the next screens,
  "What is wrong?", SEND = the email app to support@grouptrack.org (ImprovMX -> Fred's Gmail; Fred's Gmail filter: subject "GroupTrack issue"
  -> label GROUPTRACK ISSUES REPORTS, important -> pop-up) with the screenshots; subject "GroupTrack issue - <function> - <version>"; body:
  function (the screen's help id), app version, mode, device, time, note. Settings > Issue reports > "Send issue reports by email" (on by
  default; off = saved on the tablet, app files issues/). Every report also written to issues/<stamp>.txt. grouptrack/GtIssue.kt.
- Ticket desk (Fred 11:09): free options reviewed; Zoho Desk Free (3 agents, email ticketing; sign-up starts a 15-day trial of a paid plan --
  switch to Free in Setup > Subscription before it ends). Desktop folder "GroupTrack Support": Zoho sign-up / desk / ImprovMX links + README.
  Fred moved the support docs into their own Google folder.
- AICHECK / AICHECK2 (Fred 10:49, 11:42 "you cannot tell if you requested it"): the AI overview opt-out = a bordered box with a check mark,
  blank when not ticked, and the line says "-- ON". (Not a "not remembered" issue -- a visibility issue.)
- Bench: Fred 12:07 "perfect". COMMITTED 41d48b6c1 (pushed): 79 files -- the whole screen standard (STD1-STD1I), RIDESEARCH 1-3, AICHECK 1-2,
  ISSUE. Build tested: 2026-10-24_1203_mac_8912d3c98_UNCOMMITTED (versionName 2.7.14 (29321435)).
- NEXT: the 2.7c items -- 1 download defaults (Map menu sets them; each launching panel shows them, CHANGE = this run only), 2 an import
  picker per import type, 3 the Utah cleanup row, 4 process simplification, 5 imported trails by state, 6 the ride map's older Route+ copy,
  7 start-up from ONE MAP.


## 12:10-12:21 GRPSTATUS / DLDEFAULTS / AREAIMPORT applied (Fred); UTAH PRIVATE PROPERTY
- GRPSTATUS-2026-10-24, DLDEFAULTS-2026-10-24, AREAIMPORT-2026-10-24: applied on the Mac (uncommitted), build pending.
- 12:17-12:19 Fred: "WE ALSO NEED TO ADD UTAH STATE PRIVATE PROPERTY ASSIGNMENT" -- in the trails and tracks import section of Maps.
  UTAHPRIV-2026-10-24 (~/Downloads/patch_utah_private_2026-10-24.py, proved on copy f10): Map > IMPORT MAP FEATURES >
  "Assign Utah private property -- which trails are on private land". Panel (grouptrack/UtahPrivate.kt): shows the land data on the
  tablet (size, date) or that it is missing; option "Download the latest land ownership data first"; ASSIGN runs the Utah land download
  if needed (HomeStateImportController.fetchUtahOwnership, .part then rename) and then step 8 (OwnershipReclass.run) with progress;
  the work carries on if the panel is closed. Settings > "Classify trails again" removed (its home is the Map row).
- 12:20 Fred: the private-property class reset in HOUSEKEEPING when Utah trails were imported in a SUCCESSFUL import JSON -> added to
  release prep on the living checklist (living master v168, release-build checklist item 2, the one-time housekeeping classify).
  ONE checklist: the living master (Fred 12:21).

## 12:24-13:33 COMMIT e38945584, THEN THE IMPORT FUNCTIONS
- 12:24 Fred: back to open fixes; get a TESTER version out while documentation updates proceed. 12:30 bench all perfect -> e38945584.
- ROUTECOPY-2026-10-24: the older Route+ copy cut from ConvoyScreen (its only door, +ROUTE on ConvoyArtifactsPanel, could not open:
  showArtifactsPanel never set true). 274 lines + 15 state lines + badge flag; ConvoyArtifactsPanel.kt -> retired/2.7c_dead/convoy;
  pieces in retired/2.7c_dead/pieces/convoy/ConvoyScreen.routecopy.pieces.txt. ConvoyRouteToolbar stays (RouteModule uses it).
- 12:39 Fred: the "Imported trails / features by state" row reads "Display trail, waypoint counts by state". Trails / points carry no
  state on the tablet -> counts from the import JSONs (imports/ + history/); points recorded into the JSON from now on.
- 12:40-12:45 Fred: an AREA import skips a state already imported WHOLE ("all trails exist already"; "if OSM includes the state we
  omit it from OSM processing"). Measured: the area OSM step downloads the WHOLE state PBF (~167 MB) + GeoPackage (~332 MB, Utah),
  parses it, keeps the box. AREASKIP-2026-10-24.
- 12:49 simulation, S-Utah / NV / AZ box (S36.8 W-114.5 N37.6 E-113.3): rectangles pick Arizona, CALIFORNIA NORTH (its box reaches
  -114.13 for the Needles corner), Nevada, Utah; 6 catalog sources (UGRC, AZ State Parks, BLM, USFS, USGS, NPS).
- 12:53-12:57 Fred: the list of sources to be processed -- states (OSM) with whole-imported ones unticked, untick bbox errors; the
  other sources listed too, untick to remove; it is the list the import already shows, informational until now. 12:54: UGRC is small,
  reprocess it. AREAPICK-2026-10-24 v2 (v1 SUPERSEDED, never run). START now runs as THE import (was the screen's LaunchedEffect).
- 13:06-13:17 the per-type import picker: Android has no one-time Downloads grant (All Files Access refused 09-11); a folder grant
  cannot be Download itself; Gmail saves to Download and cannot redirect by type (Fred 13:17); the picker filters by MIME only;
  onX uses the Android chooser. Fred 13:11: no share beyond Downloads; 13:16: "too complicated, leave the whole thing as is."
  CLOSED, not built (patch_gt_file_picker_2026-10-24_DECIDED_NOT_TO_BUILD_DO_NOT_RUN.py).
- 13:04 Fred: kill items while working around them, so discovery is not repeated next week.
- 13:27 bench (13:20 build): area run -- 4 states listed, Utah + California North left out, 2 states, 9 sources (6 catalog + 2 OSM +
  step 8). The import tracker did not scroll (landscape fixed it) -> IMPORTSCROLL-2026-10-24: the tracker is one LazyColumn, the
  clock held above it; the result panel scrolls. Build 2026-10-24_1332_mac_e38945584_UNCOMMITTED; commit at 13:33; deploy after the
  running AZ / NV import finishes.
- 13:19 Fred: one hour left -- break point = commit after the build, EOD docs (handoff v74, living master v173, release notes v45, day
  notes v9; the manual waits -- screens still changing).
- 13:39 committed and pushed: f2397971b (ROUTECOPY + AREASKIP + AREAPICK v2 + IMPORTSCROLL). Build 2026-10-24_1332 is that code.

## 15:08-17:18 TRAILS FROM TRACKS OUT OF THE IMPORT; STEP 8 ON EMPTY FIELDS ONLY
- 15:08 Fred: take trails from tracks out of the import -- it has its own menu option. 15:12-15:13: trails are made per track when a
  track is added (the RIDERTRAILS-2026-09-07 trigger); the full pass mattered for tracks loaded before that -> the menu row.
- 15:19 Fred: trails from tracks are written as motorized; with no public / private classification they are public.
- RIDEROUT-2026-10-24: the import's Trails from tracks row + run removed; writeOne writes rider / MOTORIZED / PUBLIC; step 8 never
  land-tests rider; the 09-07 "Unnamed n" prototype cleanup removed (could delete a real GPX track named "Unnamed 1").
- 15:44-15:48 Fred: step 8 should only populate empty fields -- "we only need to process records that are not assigned in the public
  or private field." STEP8EMPTY-2026-10-24: OwnershipReclass.run(onlyUnassigned) -- import = true, Assign Utah private property = false.
  First build failed: the marker comment sat inside a one-line lambda in UtahPrivate.kt (rest of line commented out) ->
  STEP8EMPTYFIX-2026-10-24. Rule from now on: a marker comment never goes on a code line that continues.
- Build 2026-10-24_1648 deployed (Droid 1 + 2). First Create trails from tracks run: 3 removed, 2 added (the AZ / NV import's new
  trails). Second run (expect 0 / 0) and step 8's time on the running import still to report.
- 17:00-17:02 Fred: deleted trails from tracks come back on every rebuild -> re-mark instead of delete, category "unofficial/uncertain"
  (open: per piece, per track, or both).
- 17:06-17:10 Fred: the area import's whole-state download is the killer (~4 GB even for a small area); the PBF is pulled for its
  features; features are part of how routes are created and rated. Open item: Overpass for area imports (box only, same tags), the
  state's reduced extract kept as the fallback.
- 17:14 Fred: feature allow-list -- viewpoint, picnic site, shelter, drinking water, toilets, gas (osm_layers.json filters; RouteExplorer
  VIEW += viewpoint). Small, before the tester version.
- 17:18 committed + pushed ed505bed3 (RIDEROUT + STEP8EMPTY + FIX).
- 17:21 bench: the import's Classify trails (STEP8EMPTY, empty land fields only) took 11 SECONDS (was minutes for the full pass). Second Create trails from tracks run still to report.

## 22:04-22:18 THE MANUAL'S ONE MAP OVERVIEW -- launch points (Fred)
- Fred 22:04-22:16: letters mark LAUNCH POINTS ONLY (15-20 on the map); the called functions sit underneath in twisties that expand as
  decisions on rows; old manual text and images are aligned to the new launch points, then revisited as new screenshots are captured,
  one twisty section at a time. Views: Group standard, Group on a ride (Add Route doubles as View: Group / My Cart), Android only.
- Built: One Map documentation / GroupTrack_LaunchMap_2026-10-24_v5.html (v1-v4 SUPERSEDED: callout and side-list concepts were wrong).
  20 launch points (A MENU, B Zoom, C CHK IN-RECORD, D GRP Awareness, E Mode, F Status, G Map type, H MAP KEY, I FILTER, J Centre on me,
  K GPS, L Text light/dark, M Readout, N Map, O Add Route, P Rides, Q Search, R Radio setup, S Tap a feature, T Long press); Android
  only 19 (no Radio setup). Called functions from LaunchModules.kt. Indicators unlettered under "What the screen shows". Each letter
  carries its 2.7b manual sections (32, text + pictures) collapsed under "From the 2.7b manual"; E, J, L new; retired: 5.2 Ride
  Planning, 5.12 HIDE, chapter 4 planning map. Drag letters, edit wording, load screenshot per view, Export JSON (positions + text).
- Next level: each called-function row opens to its screen's decisions (traced from the function refs).
- Noted: the 2.7b manual's Map Keys help already lists "Unofficial / uncertain -- not available for riding"; TrailClassifier lacks it.
- 22:18 Fred, the doc plan when the manual sections are done: manual sections and help text linked; Quick Start exercises stay, their manual references change; the 2.7c release notes are a trip of their own -- the new launch points, each with a description of the service it provides (no more), linked to its manual section. (Living master v183.)
