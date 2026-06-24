# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-18 (EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `d75572a1f`

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

---

## ⛔ LEAD-CART CONVOY-TRACKING REBUILD [2.1] — recovered settled design (MUST-SHIP)

> This is a significant, settled V2.5 design that had fallen off the active
> checklist. Authority doc: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31).
> It is NOT done. Carry it every session until it ships.
> **Full plan: `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.**

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

### Planning doc — DEMOLITION + REBUILD (written 06-18)
`GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md` has two parts:
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

## OTHER OPEN (backlog / 2.6)

- **Blank trail-name in FIT's JSON row** — id is correct and selection is id-based,
  so it works; the name writes `""` (trails get names, tracks don't — narrow
  track-name-lookup issue). Cosmetic, parked.
- [6.2] geojson asset removal; [1.2] sliceLine whole-trail; import [4.x]; queues
  [3.3]; [10.1] BLE.
- Track Display Selector filter tabs (All / Saved / In-Progress); track import moved
  to the Map Viewer settings menu; Gaia/onX standalone parity (1-second GPS option,
  trip odometer, speed warmup, post-ride track stats, waypoint marking, solo-rider
  onboarding); Map Manager Phase C. *(From the 05-07 backlog.)*
- **Tree cleanup:** remove stray files — `.bak_*` files accumulating from 06-18
  patches, `ConvoyScreen.kt.bak_move`, `utah_trails_stgeorge.geojson.bak`; never
  commit `grouptrack_spatial.db` (117MB).
- **DEFERRED:** GeoPackage national-trail architecture, V3.0, paywall.

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

## TREE STATE

- **Committed HEAD `d75572a1f`** (arrows). Chain today: `009b158aa` → `35ccccc4a`
  (FIT) → `60db85131` ("?" help) → `d75572a1f` (arrows).
- Parked (never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`,
  `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? *.bak_*`,
  `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db`. Commit only named
  files. Never `git add .`.

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
- This checklist → 06-18.
- Track-revision demolition+rebuild planning doc → written (`GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`).
- **Still to do (documentation pass):** cookbook automated screen-capture; broader doc organize/cleanup; user-manual structural rewrite where stale.
