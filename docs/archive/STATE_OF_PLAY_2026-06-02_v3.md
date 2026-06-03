# STATE OF PLAY — 2026-06-02 v3 (read first; supersedes v1, v2, and the 06-02 AM crash docs)

Living status. Last-written version of the day is what counts; the 06-02 set replaces 06-01 tomorrow. Authoritative tracker = v25_master_checklist.md (Section K/L). This v3 records the RESOLVED diagnosis of the track-import hang and the day's confirmed findings.

## WHAT TODAY WAS: the 87-track import hang — ROOT-CAUSED to the untested waypoint/route IMPORT path

### The arc (so we never re-chase the dead ends)
The track import hung on an 87-track onX file (no recap, no source delete, frozen spinner, eventually app self-terminated / system recovered). Over the session, suspects were raised and KILLED BY READING CODE / LIVE DATA, in order:
- Recap summary panel render — NO (the dialog renders counts only, capped lists; ConvoyTrackImportScreen).
- Trail/track field mismatch — NO (track screen calls the track function; types line up).
- Device-specific to Droid 2 — NO (rested on the false premise that "Droid 1 ran identical code"; not so).
- Dedup-core hashing in insertTrackToDb — NO (function read in full, 670-687: lean, single INSERT OR IGNORE, no txn/loop/SELECT, can't hang).
- parseGpxTrackPoints regex — NO (line 443: `lat="([^"]+)"\s+lon="([^"]+)"` is linear, no backtracking, deterministic).

### The actual cause
The file is artifact-HEAVY: 87 tracks, **311 waypoints**, and a large route block (`<rte`/`<rtept` grep = 7695 — mostly route POINTS). `importGpxAllArtifacts` processes tracks -> waypoints -> routes -> delete. GPX import of waypoints/routes is UNTESTED, separate-task code that was running on every track import as a side effect. The 2-track/2-waypoint case worked, so the mechanics MAY be fine, but at volume (311 wpts + thousands of route points) the untested path is what hangs — execution never returns from waypoint/route processing, so step 4 (delete) and the recap never fire. That matches every symptom (no delete, no recap, frozen).

### SCOPING DECISION (Fred): waypoint/route IMPORT is a separate task; in-app waypoint/route CREATION is ready and unaffected. The track importer should import tracks.

## THE FIX SHIPPED TODAY (bypass) — TEST PENDING
- Patch: patch_v25_import_tracksonly_v1.py (in /mnt outputs + Fred's Downloads). Applied to ConvoyTrackOps.kt:
  - `val waypoints = parseGpxWaypoints(text)` -> `emptyList<GpxWaypoint>()` (TEMP BYPASS 2026-06-02 marker)
  - `val routes = parseGpxRoutes(text)` -> `emptyList<GpxRoute>()` (TEMP BYPASS marker)
  - Track import, delete-on-success, and summary all intact (counts go 0 for wpt/route).
- The recap-notice edit (ConvoyTrackImportScreen.kt: "Waypoint & route import: BYPASSED (tracks only)") DID NOT APPLY — anchor whitespace mismatch; script correctly refused. Cosmetic only; deferred.
- git diff CONFIRMED clean on ConvoyTrackOps.kt (both emptyList swaps + markers present).
- BUILD: assembleGoogleRelease running (~38 min) at write time. PENDING: install on Droid 2 (serial 24039703201775), import the 87-track file WITH logcat (`logcat -v time > file`), confirm tracks roll through clean (recap + source delete + no freeze), THEN COMMIT.
- DECISION RULE: clean run -> commit the bypass as a known-good checkpoint, STOP. Still hangs -> the memory/accumulation theory (below) is confirmed and the streaming fix becomes required.

## CONFIRMED SIDE-FINDINGS (real, but NOT today's task — recorded so they're not lost)
1. ADD-CORE BYPASS / where-used scoping was skipped. The shared dedup add-core decision fn is `resolveByGeom` (SpatialDbManager.kt:764, enum AddDecision{INSERT,DROP,ALIAS} :760). Per xref, the ONLY caller of the per-type insert decision path is TrailImporter.insertFeature (TrailImporter.kt:209). insertTrackToDb / insertWaypoint / insertRoute do their OWN inline INSERT OR IGNORE and do NOT call resolveByGeom. So "all four artifacts funnel through one core" (code comment) is FALSE — only TRAILS do. Fred's lesson: "we built a function with rules that everything adding artifacts must use, and never did a where-used scoping to confirm it was used." Rule 3 (xref scope analysis before a change) was the safeguard and was skipped. CORRECTIVE: where-used is mandatory for any write-path/contract change, plus a confirming where-used AFTER a migration.
2. TRACKS DO dedup, but via the SCHEMA (tracks table has `UNIQUE(geom_hash)` — confirmed in CREATE TABLE), not via the add-core. The "Inserted track to DB" log fires on EVERY call regardless of whether INSERT OR IGNORE actually inserted — so the log is MISLEADING (it fooled the diagnosis mid-session). Row count is truth, not the log.
3. geom_hash IS NON-DETERMINISTIC for long tracks — OR the file legitimately contains 2 recordings of the same route. "St George to Bar10" exists as TWO rows (created 2.2s apart): 1762 pts / 38387 chars / hash cdfad47e... vs 1735 pts / 37803 chars / hash 9969bdf6... Different point COUNTS => either two real recordings (then dedup correctly kept both, hashing is fine) OR unstable parse/precision (then raw-WKT-no-normalization deferral is triggered). Fred to toggle-test on map to confirm which. If unstable: normalization becomes required (impacts routes + AWS sync, which depend on stable hashing). NOT resolved.
4. KEEP/DELETE recap buttons MISLEADING: importGpxAllArtifacts auto-deletes the source GPX at step 4 on success BEFORE the recap dialog shows. "KEEP FILES & CLOSE" (onDismiss only) cannot preserve the source. Fred clicked KEEP and the file was deleted. Corollary used in diagnosis: a SURVIVING source file = the import HUNG. Fix: gate auto-delete on the user's choice, or fix labels.

## NEXT-SESSION TASKS (scoped, not started)
### A. "Walk away from import forever" build (Fred's priority) — streaming + 3-type toggle
- STREAMING / large-file fix in importGpxAllArtifacts: the loop does `trkPattern.findAll(text).toList()` — materializes ALL 87 track blocks at once, plus holds the whole 28.9MB file as one `text` string. Memory pile-up on big files. Fix tiers: (1) drop `.toList()`, iterate lazily; (2) scope/release per-track strings (trkContent/singleGpx/wkt) for GC between iterations; (3) "once and for all" = stream the file read instead of readText() the whole thing. Items 1+2 = quick and likely enough for current files; item 3 = the durable big-file fix (~the "1 hour"). The PENDING test decides whether 1+2 is "required now" or "headroom".
- 3-TYPE IMPORT TOGGLE: a selector panel ALREADY EXISTS (file-selection checkboxes at ConvoyTrackImportScreen.kt ~321, select-all ~391; Checkbox infra imported). ADD three artifact-type toggles (Tracks/Waypoints/Routes), default Tracks ON, wpt/route OFF but SELECTABLE. Plumb the 3 flags into importGpxAllArtifacts (called at line ~126 in doImport()) and gate sections 1/2/3 on them — this REPLACES today's hardcoded emptyList() bypass with a real toggle. PURPOSE (Fred): the toggle is a TEST HARNESS — flip waypoints-only or routes-only, feed an isolated batch, see if that path processes, WITHOUT a rebuild each test. If a type needs mods, schedule them; if it works, done. "Never touch this code again unless a specific element (tested by itself) fails."
- Sequencing: one final build = streaming fix + 3-type toggle together (built on the committed bypass checkpoint). Code regions captured: doImport() handler (95-130), the import call site (126). Still need to read before patching: the process-button UI region + the importGpxAllArtifacts signature, to write ONE patch for ONE build.
- onX exports allow per-type export — pairs with the toggle: import track-only onX exports through the tracks-only toggle to move all track data down.

### B. Convoy QUEUES button + convoy waypoint-drop — PORT tasks (from ADDENDUM_convoy_waypoint_and_queues)
- Both work on the PLANNING map, are dead/missing on the CONVOY map; both are PORTS of existing planning-map code, not new code.
- QUEUES button: port planning wiring to BOTH convoy interfaces (lines 494, 622); same row as +/- zoom + north; watch double-accordion; don't cover NET/LOCAL.
- Convoy waypoint drop: port planning long-press->drop->insert->draw. KEY RISK ("or so I hope"): the JS->Android bridge — how the planning map signals a drop back to Kotlin. Convoy map had earlier JS binding errors (addMarker/clearMarkers undefined), so the port may need to add the same JS interface methods to the convoy map. Fold in carried bug: long-press fires only on empty map, not on node markers.

### C. Route planning (snap-2) — was the original 2-day priority, now downstream
- Still queued, but NOTE: routes ALSO bypass the add-core (finding #1), so snap-2 routes won't dedup until that's addressed. Route planning is now behind the import/add-core cleanup.

## STATUS / FACTS
- Branch feature/convoy-event-ride is PUSHED (HEAD == origin via recommit12 commit 3f311d958). P1=3339839f4, P2=eaf8508c1 committed+pushed.
- Today's bypass patch is APPLIED to working tree, NOT yet committed (pending the build + 87-track test).
- Droid 2 spatial DB: /sdcard/Documents/GroupTrack/grouptrack_spatial.db (~117MB; 49047 trails / 67 tracks / 559 waypoints / 0 routes). Pull with MSYS_NO_PATHCONV=1. Patch scripts run from repo root: `cp ~/Downloads/<patch>.py . ; python <patch>.py --selftest ; python <patch>.py`.
- Build ~32-38 min Kotlin; pre-existing non-gating lintVital ServiceKeepAlive error is EXPECTED, ignore it.

## SUPERSEDED (marked, not deleted — rule 5)
- STATE_OF_PLAY_2026-06-02 v1 (panel-render theory) and v2 (add-core-bypass-as-the-hang theory) — both superseded by this v3: the hang is the untested waypoint/route IMPORT path at volume; the add-core bypass is a real but SEPARATE finding.
- NOTE_track_crash_device_specific / _recap_exonerated / ISOLATION_PLAN_droid2_track_crash / SEVERITY_CORRECTION — all from the 06-01/06-02-AM diagnosis attempts, superseded by the resolved finding here.
