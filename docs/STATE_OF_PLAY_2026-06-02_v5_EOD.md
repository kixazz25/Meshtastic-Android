# STATE OF PLAY — 2026-06-02 v5 EOD (read first; supersedes v1–v4 today)

v1–v4 are STALE — they were written mid-day and framed the streaming fix as "next session." It shipped. This v5 is the true end-of-day state.

## WHAT SHIPPED TODAY (committed: 9377f23f7)
Track import was crashing + had no recap. Root-caused and fixed, end to end:

1. **OOM on large files — FIXED.** The 87-track / 28.9MB onX file crashed via Android lowmemorykiller in the LOAD phase (proven: lmkd culling apps, zero inserts before crash; confirmed by A/B split — 43-track chunk imported, full 87 OOM'd = cumulative load size). Three memory hogs were `readText()` (whole file) + `text.replace(<extensions>)` (2nd copy) + `findAll().toList()` (all blocks). FIX = Tier-2 streaming rewrite of `importGpxAllArtifacts`: BufferedReader accumulates ONE `<trk>..</trk>` block at a time, processes, releases (`block.setLength(0)`); whole file never in memory; extensions stripped per-block. CONFIRMED: 28.9MB file imports past the old crash point.

2. **Quadratic per-track slowness — FIXED.** Fred noticed import times kept GROWING deeper into the file → the streaming loop's `block.contains("</trk>")` re-scanned the whole accumulating buffer every line = O(n²)/track (big tracks took 2–3.5 MIN). FIX: `ln.contains("</trk>")` — check current line only, O(1). CONFIRMED: big tracks dropped to ~5–30s.

3. **Import recap — FIXED.** `insertTrackToDb` now returns Boolean (inserted vs dropped-dupe via `SELECT changes()`) with honest "Inserted track / Skipped dupe track" logging (old log always said "Inserted" regardless — it fooled the diagnosis for hours). `ImportArtifactsSummary` gained inserted/dropped; recap dialog shows "X new / Y already in library."

4. **DB reconciled + hash consistency PROVEN.** Droid 2 (test device — nothing precious; real rides live in Fred's onX markup exports). Cleared tracks on a PC copy (no sqlite3 on device → edit copy, force-stop app, push back), reimported onX (90 processed → 67 unique landed; ~22–23 were legit same-geometry dupes correctly collapsed; the log shows same-named tracks insert-once-then-skip), then resync (the existing import-menu tool that scans my_tracks/ and adds DB-missing tracks) added the 2 device-only cart test drives. Final DB: 67 tracks, 67 unique geom_hash, zero dupes. A repeat full-file import = all-dupes → hash is deterministic/consistent.

5. **QUEUES button (convoy) — attempted, NOT fixed; see open items.**

Commit message notes: waypoint/route GPX import still bypassed (emptyList); per-track geometry-correctness vs old parser is a known follow-up.

## OPEN — CONVOY QUEUES (planning works, convoy doesn't) — KEY FINDING
There are TWO SEPARATE QUEUES implementations:
- **Planning map (`ConvoyMapViewerScreen.kt`, lines 247/282/738/750): WORKS.** Uses `showDownloadPanel` → `ConvoyDownloadPanel` (live "DOWNLOAD QUEUES").
- **Convoy screen (`ConvoyScreen.kt`, lines 1227/1249/1270): DEAD.** Different element — draggable button → `queuesOpen` → `ConvoyQueuesPanel` (a DIFFERENT, lesser panel in ConvoyQueuesPanel.kt).
- We patched the convoy button's tap (drag was eating it; added `detectTapGestures` in its own pointerInput, line 1244 — import line 10). Built + installed. STILL DEAD. So the two-pointerInput approach is insufficient AND/OR the convoy button opens the wrong panel anyway.
- **Real fix (next session): point the convoy QUEUES at the working `ConvoyDownloadPanel` the way planning does, rather than the separate `ConvoyQueuesPanel`** — and resolve the tap (likely: drop the custom drag entirely → plain `.clickable`, since the button doesn't need to be draggable; or single-pointerInput awaitEachGesture). Note Fred's observation: convoy QUEUES sits on the zoom-level row, top-right, not draggable in practice.
- Possible shared cause still unruled-out: convoy QUEUES AND waypoint long-press are BOTH dead on the convoy screen; could be a layer/z-order interception, though a full-screen blocker is ruled out (other controls — maps, artifacts, NET/LOCAL — work, so it's selective, not global).

## OPEN — CONVOY WAYPOINT DROP — pipeline present, doesn't fire
Research done (no build): the ENTIRE pipeline exists on convoy and matches the working planning map:
- `convoy_map.html` line 286: `map.on('contextmenu', ...)` → `Android.onMapLongPress(lat,lng)` (identical to grouptrack_map.html:675).
- Kotlin bridge HAS `onMapLongPress` (ConvoyScreen.kt:516 and :653) → sets `pendingWaypoint`. BOTH convoy bridges register as `"Android"` (lines 556, 805); the second (649-block) ALSO has onMapLongPress, so the clobber theory was DEAD — surviving bridge has the method.
- Downstream dialog exists: `pendingWaypoint?.let { AlertDialog(...) }` at line 860 (the "New Waypoint" type-chip + name dialog → insertWaypoint).
- Yet long-press on convoy produces NOTHING in logcat (no dialog). `onMapLongPress` doesn't log, so we can't tell from silence whether the JS contextmenu fires or the dialog doesn't render.
- NEXT (next session, the right next step): make it observable — add `console.log` (or a logging line) in the convoy_map.html contextmenu handler, rebuild, long-press, watch logcat for the JS firing. That distinguishes "JS event not firing" (carried bug: long-press fires on empty map but not over node markers — a Leaflet layer eating contextmenu) from "bridge fires but dialog doesn't render" (the line-860 dialog may be in a scope that doesn't compose on convoy — its indentation is suspicious, sits right after the `if (showSplash)` block closes; needs the enclosing-scope confirmed).

## SEQUENCE / PLAN (unchanged)
QUEUES + waypoints on convoy → install both Droids → **AAB build** (bundleGoogleRelease; bump versionCode; signing established) → **route planning (a whole dedicated day)**.
- Route planning build order: (1) plain waypoint drop on convoy working first (fix above), (2) introduce a map-interaction STATE (pan on/off, zoom on/off, tap-meaning as independent toggles), (3) route mode composed on that. Waypoint + route drops are the SAME action branched on mode. Mode reinterprets the TAP only; pan + pinch-zoom stay live.
- PRE-AAB CHECK (don't ship without deciding): is the NEW streaming parse's geometry CORRECT or over-capturing points (new geom len > old, e.g. Cedar Mtn 75573 vs old 52011)? Geometry feeds map drawing + route snapping + AWS sync, not just dedup. Verify a known track's point count before the AAB.

## OTHER OPEN ITEMS (filed, not today)
- **File-naming design smell:** per-track FILE write is keyed on NAME (`if (!dest.exists())`), DB insert keyed on GEOM_HASH → same-name/different-geom tracks make ONE file but MULTIPLE rows (why my_tracks/ had 68 files for 125 rows). "files == rows" is NEVER a valid check. my_tracks/ is a LOSSY rebuild source. FIX: disambiguate filenames (counter/timestamp/short-hash) so files are 1:1 with rows — makes resync fully reliable.
- **ADD-CORE BYPASS:** tracks/waypoints/routes use inline INSERT OR IGNORE, bypass `resolveByGeom` (only TrailImporter calls it). Tracks get DROP-on-dupe but NO alias-on-rename. Migrate the three inserts through the add-core; confirm by where-used after.
- **Post-import filter-list stale:** after import, tracks draw on map but the artifact LIST is empty until you leave + return (no refresh trigger on import-complete). Likely folds into ArtifactListPanel cleanup.
- **Rolling per-track progress UI:** import shows only a spinner; surface the per-track logcat lines ("Inserted/Skipped dupe: name") to a scrolling window so it doesn't look frozen.
- Artifact LIST caps at 200 shown — needs paging/async.
- KEEP/DELETE recap buttons misleading (source auto-deletes before dialog).

## DEVICE / PROCEDURE NOTES
- App package: `com.grouptrack.android`. NO sqlite3 on device. DB edits = pull → edit copy on PC → `adb shell am force-stop com.grouptrack.android` → confirm `pidof` empty → push over `/sdcard/Documents/GroupTrack/grouptrack_spatial.db` → reopen (FORCE-STOP FIRST or running app clobbers the push). Per-track GPX at `/sdcard/Documents/my_tracks/`. Pull with MSYS_NO_PATHCONV=1.
- Build APK: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~12–21 min incremental). Install `adb -s 24039703201775 install -r -d <apk>`.
- Patch scripts: unique filename each iteration; CRLF-aware for ConvoyScreen.kt / ConvoyTrackImportScreen.kt (CRLF) vs ConvoyTrackOps.kt / SpatialDbManager.kt (LF). git diff via `git --no-pager diff`. EYEBALL diffs before building.
