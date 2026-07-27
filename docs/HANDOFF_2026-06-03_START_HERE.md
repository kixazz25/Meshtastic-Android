# HANDOFF — 2026-06-03 START HERE (convoy parity before routes)

Cold-start doc. Read this first, then STATE_OF_PLAY_2026-06-02_v5_EOD.md for full context. Authoritative tracker = v25_master_checklist.md.

## THE STRATEGIC RULE (Fred, EOD 06-02)
**Do NOT start route planning until the convoy map has the SAME feature level as the planning map.** Convoy currently has TWO unproven features (waypoint drop, QUEUES). Adding route planning on top of a non-functional convoy map = debugging three things at once with no stable base. Parity FIRST. Routes are a whole separate day, AFTER parity.

## STATUS SNAPSHOT
- **Committed (9377f23f7):** track import streaming fix (no more OOM), quadratic per-track fix (big tracks 3.5min→~30s), honest insert/skip logging, recap "X new / Y already in library". DB on Droid 2 reconciled to 67 tracks / 67 unique geom_hash, hash consistency proven.
- **Both devices synced:** latest APK pushed to Droid 1 (8624SBCEDF00001789) and Droid 2 (24039703201775) on 06-02 EOD. [If push to Droid 1 needed uninstall-first due to signing mismatch, note that here.]
- **NOT done:** convoy QUEUES, convoy waypoint drop. Both diagnosed (below). NO code fix landed for either today.

## TASK 1 (do first): CONVOY QUEUES — point it at the WORKING panel
**Finding:** convoy and planning have TWO SEPARATE QUEUES implementations.
- Planning (WORKS): `ConvoyMapViewerScreen.kt` lines 247/282/738/750 → state `showDownloadPanel` → opens `ConvoyDownloadPanel` (the live "DOWNLOAD QUEUES" panel).
- Convoy (DEAD): `ConvoyScreen.kt` lines 1227/1249/1270 → state `queuesOpen` → opens `ConvoyQueuesPanel` (a DIFFERENT, lesser panel in ConvoyQueuesPanel.kt). Button sits top-right on the zoom-level row.
- We patched the convoy button's tap on 06-02 (drag was eating it → added `detectTapGestures` in its own pointerInput at ConvoyScreen.kt:1244, import at line 10). Built + installed. STILL DEAD.

**Approach for tomorrow:**
1. First decide the panel question: Fred wants convoy QUEUES to show download queues "same as planning." That means convoy should open **`ConvoyDownloadPanel`** (planning's working panel), NOT the separate `ConvoyQueuesPanel`. So the fix is to wire the convoy button to `showDownloadPanel`/`ConvoyDownloadPanel` the way planning does — porting the planning pattern, not fixing `ConvoyQueuesPanel`.
2. Then resolve why the tap is dead. Leading theory: the two-pointerInput (separate tap + drag blocks) still conflict; the drag detector wins. Likely fix: **drop the custom drag entirely → plain `.clickable`** (the button doesn't need to be draggable per Fred — it lives on the zoom row). If drag must stay, use a single `pointerInput { awaitEachGesture { ... } }` that disambiguates tap vs drag.
3. UNRULED-OUT shared cause: convoy QUEUES AND waypoint long-press are BOTH dead on the convoy screen. A full-screen touch-blocker is ruled out (maps/artifacts/NET-LOCAL all work → selective, not global). But check for a z-order/overlay over the top-right region before assuming two independent bugs. Read the convoy `Box` (opens ConvoyScreen.kt:508) child order: AndroidView/map (511), the 1002-block, QUEUES (1230), bottom elements (1503/1520). Nothing obvious is declared AFTER QUEUES to cover it — so interception is not yet proven; could be two separate bugs.

## TASK 2: CONVOY WAYPOINT DROP — pipeline present, doesn't fire
**Finding (research done, no build):** the ENTIRE pipeline exists on convoy, matching the working planning map:
- JS: `convoy_map.html:286` `map.on('contextmenu', e => Android.onMapLongPress(e.latlng.lat, e.latlng.lng))` — identical to grouptrack_map.html:675.
- Kotlin bridge HAS `onMapLongPress` (ConvoyScreen.kt:516 AND :653) → sets `pendingWaypoint`. Both convoy bridges register as `"Android"` (lines 556, 805); the surviving (second) one has the method, so the bridge-clobber theory is DEAD.
- Downstream dialog: `pendingWaypoint?.let { AlertDialog("New Waypoint", type chips + name → insertWaypoint) }` at ConvoyScreen.kt:860.
- Yet long-press on convoy → NOTHING in logcat, no dialog. `onMapLongPress` doesn't log, so silence is ambiguous (JS didn't fire? OR bridge fired but dialog didn't render?).

**Approach for tomorrow (make it observable — this is the key next step):**
1. Add a `console.log("contextmenu fired", e.latlng)` (and a log right before `Android.onMapLongPress`) in the convoy_map.html contextmenu handler. Rebuild assets, reload, long-press, watch logcat for `chromium`/`console`. This DISTINGUISHES:
   - JS event NOT firing → the carried bug (long-press fires on empty map but NOT over node markers — a Leaflet marker/overlay layer consuming `contextmenu`). Fix on the JS/Leaflet side.
   - JS fires + bridge called, but no dialog → the line-860 dialog is in a scope that doesn't compose on convoy. Its indentation is SUSPICIOUS — it sits right after the `if (showSplash) { Box {...} }` block closes (ConvoyScreen.kt ~845-858), indentation misaligned. CONFIRM the enclosing scope: is `pendingWaypoint?.let` a live sibling of the map inside the convoy `Box`, or stranded outside the composed tree? Read the parent that opens before ~835 and closes after ~915.
2. Also test on PLANNING map with same gesture for working-vs-broken comparison (planning waypoint drop works → its onMapLongPress → pendingWaypoint → dialog all fire).

## SEQUENCE AFTER PARITY
Convoy QUEUES + waypoint working → **AAB build** (`bundleGoogleRelease`; bump versionCode; signing established) → install both Droids → **route planning (dedicated day)**.
Route-planning build order (when we get there): (1) plain waypoint drop solid first, (2) introduce map-interaction STATE (pan on/off, zoom on/off, tap-meaning as independent toggles), (3) route mode composed on that rail. Waypoint + route drops = same action branched on mode; mode reinterprets TAP only, pan+pinch-zoom stay live.

## PRE-AAB CHECK (don't ship without deciding)
Is the NEW streaming parse's geometry CORRECT or over-capturing points? New geom len > old (Cedar Mtn 75573 vs old 52011; Broken Ridge 99816 vs 71199). Geometry feeds map drawing + route snapping + AWS sync, not just dedup. Verify a known track's point count is right before the AAB.

## DEVICE / PROCEDURE
- Package `com.grouptrack.android`. Droid 1 = 8624SBCEDF00001789 (Friday field-test device — will be LOCKED before Friday). Droid 2 = 24039703201775 (dev/test).
- Build APK: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~12-21 min incremental). Install `adb -s <serial> install -r -d <apk>`.
- NO sqlite3 on device. DB edits: pull → edit copy on PC → `adb shell am force-stop com.grouptrack.android` → confirm `pidof` empty → push over `/sdcard/Documents/GroupTrack/grouptrack_spatial.db` → reopen. Per-track GPX at `/sdcard/Documents/my_tracks/`. Pull MSYS_NO_PATHCONV=1.
- Patch scripts: UNIQUE filename each iteration; CRLF-aware for ConvoyScreen.kt + ConvoyTrackImportScreen.kt (CRLF) vs ConvoyTrackOps.kt + SpatialDbManager.kt (LF). `git --no-pager diff`. EYEBALL diffs before building.
- METHODOLOGY that won today: proven-vs-theorized (have a failure in logcat vs a guess at its cause); trust the DB/logcat over the UI; eliminate one change at a time; follow the symptom shape (Fred's "times keep growing" caught the quadratic). When code-reading can't resolve it, make it OBSERVABLE (a log line) and test on device — don't infer from silence.

## OTHER OPEN (filed, not blocking parity)
- File-naming smell: file write keyed on NAME, DB on GEOM_HASH → same-name/diff-geom = 1 file, N rows. "files==rows" never valid. my_tracks/ is lossy. Fix: disambiguate filenames.
- Add-core bypass: tracks/waypoints/routes use inline INSERT OR IGNORE, bypass resolveByGeom (only TrailImporter calls it). No alias-on-rename. Migrate through add-core, confirm by where-used.
- Post-import filter-list stale (need refresh trigger on import-complete). Artifact list caps at 200 (paging). Rolling per-track progress UI. KEEP/DELETE recap buttons misleading (source auto-deletes before dialog).
