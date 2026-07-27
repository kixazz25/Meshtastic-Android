# HANDOFF — 2026-06-03 START HERE (convoy parity before routes)

Cold-start doc. Read this first, then STATE_OF_PLAY_2026-06-02_v5_EOD.md for full context. Authoritative tracker = the Living Checklist (GroupTrack_V25_LivingChecklist_<newest date>).

## THE STRATEGIC RULE (Fred, EOD 06-02)
**Do NOT start route planning until the convoy map has the SAME feature level as the planning map.** Convoy currently has TWO unproven features (waypoint drop, QUEUES). Adding route planning on top of a non-functional convoy map = debugging three things at once with no stable base. Parity FIRST. Routes are a whole separate day, AFTER parity.

## STATUS SNAPSHOT
- **Committed (9377f23f7):** track import streaming fix (no more OOM), quadratic per-track fix (big tracks 3.5min→~30s), honest insert/skip logging, recap "X new / Y already in library". DB on Droid 2 reconciled to 67 tracks / 67 unique geom_hash, hash consistency proven.
- **Both devices synced:** latest APK pushed to Droid 1 (8624SBCEDF00001789) and Droid 2 (24039703201775) on 06-02 EOD.
- **NOT done:** convoy QUEUES, convoy waypoint drop. Both diagnosed (below). NO code fix landed for either. **Both are CLICK/EVENT-HANDLER issues — confirmed by Fred. Use the debug tooling below to identify which element/panel registers the tap BEFORE patching, so we stop guessing.**

---

## ★ DEBUG TOOLING — find which panel/element registers the click (DO THIS FIRST, both tasks)

We have spent two rounds theorizing. Stop theorizing — **make the click observable.** Two different layers, two different tools:

### For the WebView/Leaflet map (waypoint long-press, and anything in convoy_map.html)
**Tool: Chrome DevTools remote debugging via `chrome://inspect`.**
1. Debug build running on device over USB. (App must have called `WebView.setWebContentsDebuggingEnabled(true)` once — verify it's on for debug builds; if not, add it.)
2. PC Chrome → `chrome://inspect/#devices` → the convoy WebView (`convoy_map.html`) appears → click **inspect**.
3. **Elements** panel → select the map container → **Event Listeners** tab → see exactly which element has `contextmenu`/`click`/`touchstart` bound, and whether a Leaflet overlay pane or marker layer is bound ABOVE the map (eating the event).
4. **Console** trick to name the element actually under the tap (the definitive "who's registering it"):
   ```js
   map.on('contextmenu', function(e){
     console.log('LONGPRESS fired', e.latlng);
     console.log('top element:', document.elementFromPoint(e.containerPoint.x, e.containerPoint.y));
     Android.onMapLongPress(e.latlng.lat, e.latlng.lng);
   });
   ```
   - "LONGPRESS fired" never logs → Leaflet/marker layer is consuming `contextmenu` (handler bound to wrong layer, or a higher-z pane on top). **JS-side fix.**
   - Logs, but Kotlin `onMapLongPress` never logs → bridge problem.
   - Both fire but no dialog → Compose dialog scope problem (the line-860 `pendingWaypoint?.let` neighbor).

### For Compose/Kotlin UI (the QUEUES button — NOT in the WebView)
`chrome://inspect` can't see Compose. **Tool: Android Studio Layout Inspector** (View → Tool Windows → Layout Inspector) with the app running.
1. It renders the live composable tree. Find the QUEUES button: confirm its `clickable`/`pointerInput` modifier is present AND enabled, and check **whether another composable is drawn on top of the top-right region** intercepting the tap (the z-order question we couldn't settle by reading code).
2. Pair with a `Log.d("QUEUES","onClick fired")` inside the button's click lambda:
   - log fires → handler IS wired; the bug is downstream (wrong panel opens / panel renders offscreen).
   - log never fires → the tap isn't reaching the handler → an overlay or a disabled/again-consumed modifier. Layout Inspector shows what's on top.

**This is the tool you asked for.** It tells us definitively which element/panel is registering (or stealing) the click, instead of inferring from silence.

---

## TASK 1 (do first): CONVOY QUEUES — point it at the WORKING panel
**Finding:** convoy and planning have TWO SEPARATE QUEUES implementations.
- Planning (WORKS): `ConvoyMapViewerScreen.kt` lines 247/282/738/750 → state `showDownloadPanel` → opens `ConvoyDownloadPanel` (the live "DOWNLOAD QUEUES" panel).
- Convoy (DEAD): `ConvoyScreen.kt` lines 1227/1249/1270 → state `queuesOpen` → opens `ConvoyQueuesPanel` (a DIFFERENT, lesser panel in ConvoyQueuesPanel.kt). Button sits top-right on the zoom-level row.
- We patched the convoy button's tap on 06-02 (drag was eating it → added `detectTapGestures` in its own pointerInput at ConvoyScreen.kt:1244, import at line 10). Built + installed. **STILL DEAD.** ← this is why we go to Layout Inspector + Log.d next, not another blind patch.

**Approach for tomorrow:**
1. **Decide the panel question:** Fred wants convoy QUEUES to show download queues "same as planning." → convoy should open **`ConvoyDownloadPanel`** (planning's working panel), NOT the separate `ConvoyQueuesPanel`. Fix = wire the convoy button to `showDownloadPanel`/`ConvoyDownloadPanel` the way planning does — port the planning pattern, don't fix `ConvoyQueuesPanel`.
2. **Resolve why the tap is dead — use Layout Inspector + Log.d FIRST** (see Debug Tooling). Leading theory still: the two-pointerInput (separate tap + drag blocks) conflict; the drag detector wins. Likely fix: **drop the custom drag entirely → plain `.clickable`** (per Fred the button needn't be draggable; it lives on the zoom row). If drag must stay, use a single `pointerInput { awaitEachGesture { ... } }` that disambiguates tap vs drag.
3. **Shared-cause check (NOT yet ruled out):** convoy QUEUES AND waypoint long-press are BOTH dead. A full-screen touch-blocker is ruled out (maps/artifacts/NET-LOCAL all work → selective, not global). But a z-order overlay over the top-right region could explain both — **Layout Inspector will show it directly.** Box child order (ConvoyScreen.kt:508): AndroidView/map (511), 1002-block, QUEUES (1230), bottom (1503/1520). Nothing obvious declared AFTER QUEUES to cover it — interception unproven; could be two separate bugs. Let the tool decide.

## TASK 2: CONVOY WAYPOINT DROP — pipeline present, doesn't fire
**Finding (research done, no build):** the ENTIRE pipeline exists on convoy, matching the working planning map:
- JS: `convoy_map.html:286` `map.on('contextmenu', e => Android.onMapLongPress(e.latlng.lat, e.latlng.lng))` — identical to grouptrack_map.html:675.
- Kotlin bridge HAS `onMapLongPress` (ConvoyScreen.kt:516 AND :653) → sets `pendingWaypoint`. Both convoy bridges register as `"Android"` (lines 556, 805); the surviving (second) one has the method, so the bridge-clobber theory is DEAD.
- Downstream dialog: `pendingWaypoint?.let { AlertDialog("New Waypoint", type chips + name → insertWaypoint) }` at ConvoyScreen.kt:860.
- Yet long-press on convoy → NOTHING in logcat, no dialog. `onMapLongPress` doesn't log, so silence is ambiguous (JS didn't fire? OR bridge fired but dialog didn't render?).

**Approach for tomorrow (make it observable — this is the key next step):**
1. **Use `chrome://inspect` on convoy_map.html** (see Debug Tooling) — or, equivalently, add `console.log("contextmenu fired", e.latlng)` and a log right before `Android.onMapLongPress`, rebuild assets, reload, long-press, watch logcat for `chromium`/`console`. This DISTINGUISHES:
   - JS event NOT firing → the carried bug (long-press fires on empty map but NOT over node markers — a Leaflet marker/overlay layer consuming `contextmenu`). The DevTools **Event Listeners** tab shows which layer holds the binding. **JS/Leaflet-side fix.**
   - JS fires + bridge called, but no dialog → the line-860 dialog is in a scope that doesn't compose on convoy. Its indentation is SUSPICIOUS — sits right after the `if (showSplash) { Box {...} }` block closes (~845-858), misaligned. CONFIRM the enclosing scope: is `pendingWaypoint?.let` a live sibling of the map inside the convoy `Box`, or stranded outside the composed tree? Read the parent that opens before ~835 and closes after ~915.
2. Also test on PLANNING map with the same gesture for working-vs-broken comparison (planning waypoint drop works → its onMapLongPress → pendingWaypoint → dialog all fire). Diff the two map HTMLs and the two screens' dialog scopes.

---

## SEQUENCE AFTER PARITY
Convoy QUEUES + waypoint working → **AAB build** (`bundleGoogleRelease`; bump versionCode) → install both Droids → **route planning (dedicated day)**.
Route-planning build order (when we get there): (1) plain waypoint drop solid first, (2) introduce map-interaction STATE (pan on/off, zoom on/off, tap-meaning as independent toggles), (3) route mode composed on that rail. Waypoint + route drops = same action branched on mode; mode reinterprets TAP only, pan+pinch-zoom stay live.
**Route method is SETTLED: snap-2 now.** Freehand = rejected origin (testers feel drawing tracks is their value — "John Henry syndrome"). Future (not now): draw points to visit → planner generates ~5 candidate routes with key POIs.

## PRE-AAB CHECK (don't ship without deciding)
Is the NEW streaming parse's geometry CORRECT or over-capturing points? New geom len > old (Cedar Mtn 75573 vs 52011; Broken Ridge 99816 vs 71199). Geometry feeds map drawing + route snapping + AWS sync, not just dedup. Verify a known track's point count is right before the AAB.

## DEVICE / PROCEDURE
- Package `com.grouptrack.android`. Droid 1 = 8624SBCEDF00001789 (Friday field-test device — LOCKED before Friday). Droid 2 = 24039703201775 (dev/test).
- Build APK: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~12-21 min incremental). Install `adb -s <serial> install -r -d <apk>`.
- NO sqlite3 on device. DB edits: pull → edit copy on PC → `adb shell am force-stop com.grouptrack.android` → confirm `pidof` empty → push over `/sdcard/Documents/GroupTrack/grouptrack_spatial.db` → reopen. Per-track GPX at `/sdcard/Documents/my_tracks/`. Pull MSYS_NO_PATHCONV=1.
- Patch scripts: UNIQUE filename each iteration; CRLF-aware for ConvoyScreen.kt + ConvoyTrackImportScreen.kt (CRLF) vs ConvoyTrackOps.kt + SpatialDbManager.kt (LF). `git --no-pager diff`. EYEBALL diffs before building.
- **WebView debugging:** confirm `WebView.setWebContentsDebuggingEnabled(true)` is set for debug builds so `chrome://inspect` works on both map HTMLs. This is the unlock for all Leaflet/JS event debugging going forward.
- METHODOLOGY that won: proven-vs-theorized (a failure in logcat vs a guess at its cause); trust the DB/logcat over the UI; one change at a time; follow the symptom shape. When code-reading can't resolve it, make it OBSERVABLE (a log line OR the inspector) and test on device — don't infer from silence.

## OTHER OPEN (filed, not blocking parity)
- File-naming smell: file write keyed on NAME, DB on GEOM_HASH → same-name/diff-geom = 1 file, N rows. "files==rows" never valid. my_tracks/ is lossy. Fix: disambiguate filenames.
- Add-core bypass: tracks/waypoints/routes use inline INSERT OR IGNORE, bypass resolveByGeom (only TrailImporter calls it). No alias-on-rename. Migrate through add-core, confirm by where-used.
- Post-import filter-list stale (need refresh trigger on import-complete). Artifact list caps at 200 (paging). Rolling per-track progress UI. KEEP/DELETE recap buttons misleading (source auto-deletes before dialog).
- Import-Trails-by-Area returns 0 (nav wired, A3_PROCESSING bug) + needs rolling visibility. Remove-Tiles-from-Area placeholder (checkbox in bbox download-items list). (See Living Checklist §C-bis.)
