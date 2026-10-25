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
