# GroupTrack 2.7c — day notes 2026-10-24 (v1)
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
