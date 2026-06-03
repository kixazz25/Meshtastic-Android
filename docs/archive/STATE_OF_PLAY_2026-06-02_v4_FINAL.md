# STATE OF PLAY — 2026-06-02 v4 FINAL (read first; supersedes v1/v2/v3 + all 06-02 AM crash docs)

End-of-session status. Last-written version of the day = the one that counts; the 06-02 set replaces 06-01 tomorrow. Authoritative tracker = v25_master_checklist.md (Section K/L). This v4 records the SOLVED track-import crash and the streaming-rewrite spec for next session.

## SOLVED: the track-import crash is an OUT-OF-MEMORY in the file LOAD phase

### Proven (hard evidence, not theory)
- Logcat of the 87-track / 28.9MB onX import shows **Android lowmemorykiller** culling other apps (vending, Google Docs, gms, search — reclaiming 137MB/149MB/213MB/247MB) to survive. It's an OOM, system-wide.
- **ZERO "Inserted track to DB" lines** before the OOM. ~24s of silence after "Opened database," then the lmkd storm. => it dies in the LOAD phase, **before processing a single track**.

### Confirmed by isolation test (no build, ~10 min, on-device)
- Split the file by track count: chunk_A = 43 tracks (~12.5MB), chunk_B = 44 tracks.
- **chunk_A imported CLEAN** (ran well past the ~24s OOM window).
- Full 87/28.9MB OOMs in load.
- Verdict: it's **cumulative LOAD SIZE**, not a poison track and not the per-track loop. Threshold between ~12.5MB and 28.9MB.

### The three memory hogs (all in importGpxAllArtifacts load phase, all hold the full file)
1. `sourceFile.readText()` — entire 28.9MB into one String
2. `text.replace(<extensions> regex, "")` — builds a 2nd full copy
3. `trkPattern.findAll(text).toList()` — materializes ALL 87 track blocks on top
Combined footprint OOMs a memory-constrained device before track 1.

### What was RULED OUT (each killed by code-read or live test — do not re-chase)
recap summary panel render · trail/track field mismatch · device-specific-to-Droid-2 (false "Droid 1 ran identical code" premise) · insertTrackToDb dedup hashing (lean fn, can't hang) · parseGpxTrackPoints regex (linear, no backtracking) · the waypoint/route IMPORT path (bypassed via patch_v25_import_tracksonly_v1 and it STILL OOM'd — so not the cause).

## NEXT SESSION — PRIMARY TASK: streaming rewrite of importGpxAllArtifacts
Well-scoped, evidence-backed. The fix:
- **Read & process ONE track at a time, release each before the next.** Never hold more than the file stream + one track block.
- Stream the file read (don't `readText()` the whole thing); extract one `<trk>...</trk>` at a time; parse -> WKT -> insertTrackToDb -> let it go; continue.
- Drop `.toList()` — iterate the `findAll` sequence lazily.
- Scope per-track strings (trkContent / singleGpx / wkt) so they're GC-eligible each iteration.
- **DESIGN POINT:** the `<extensions>` strip currently runs ONCE over the whole file. In the streaming model, strip extensions **per track block** as each is extracted (smaller ops; the regex only ever sees one track's text). Don't drop the strip — relocate it per-block.
- TO WRITE THE PATCH: read full importGpxAllArtifacts (ConvoyTrackOps.kt 479-595) verbatim first. doImport() handler (95-130) and call site (126) already captured.

## NEXT SESSION — pair it into the "WALK AWAY" build: 3-type import toggle
- A selector panel ALREADY EXISTS (ConvoyTrackImportScreen.kt: file-checkbox infra ~321, select-all ~391, Checkbox imported).
- ADD three artifact-type toggles (Tracks / Waypoints / Routes), default Tracks ON, wpt/route OFF but SELECTABLE.
- Plumb the 3 flags into importGpxAllArtifacts (called line ~126) and gate sections 1/2/3 on them — **replaces today's hardcoded emptyList() bypass with a real toggle.**
- PURPOSE (Fred): the toggle is a **TEST HARNESS** — flip waypoints-only or routes-only, feed an isolated batch, see if that path processes, WITHOUT rebuilding each test. If a type needs mods, schedule them; if it works, done. "Never touch this code again unless a specific element (tested by itself) fails."
- Today's bypass patch (patch_v25_import_tracksonly_v1) is APPLIED to the working tree, NOT committed. Fred's call: make the bypass conditional on the checkboxes (i.e. fold into this build). The recap-notice edit failed to apply (anchor whitespace) — re-do in this build.

## SECONDARY (real findings, separate from the OOM — don't lose them)
- **ADD-CORE BYPASS:** the shared dedup decision fn `resolveByGeom` (SpatialDbManager.kt:764) is called ONLY by TrailImporter.insertFeature (per xref). insertTrackToDb / insertWaypoint / insertRoute do their own inline `INSERT OR IGNORE` and bypass it. So "all four funnel through one core" (code comment) is FALSE — only trails do. Tracks DO dedup (via tracks-table `UNIQUE(geom_hash)`, confirmed in CREATE TABLE) but get NO alias-on-rename (new-name-same-geom tracks dropped, alt name lost). Routes bypass too -> snap-2 routes won't dedup until migrated. FIX = migrate the three inserts through the add-core, then CONFIRM by where-used. Fred's lesson: a contract asserted in a comment but never where-used-scoped (Rule 3 skipped). The "Inserted track to DB" log is MISLEADING — fires regardless of actual insert; trust DB row counts.
- **geom_hash stability Q (unresolved):** "St George to Bar10" has 2 rows 2.2s apart with DIFFERENT point counts (1762 vs 1735). Either two real recordings of the same route (dedup correctly kept both, hashing fine) OR unstable parse/precision (then raw-WKT-no-normalization must be revisited — impacts routes + AWS sync). Fred to toggle-test on map. Likely two real recordings.
- **KEEP/DELETE recap buttons MISLEADING:** source GPX auto-deletes at step 4 on success BEFORE the dialog shows; "KEEP FILES & CLOSE" can't preserve it (Fred clicked KEEP, file deleted). Fix: gate auto-delete on user choice, or fix labels.

## ALSO QUEUED
- **Convoy PORT tasks** (ADDENDUM_convoy_waypoint_and_queues): QUEUES button dead on convoy — port planning wiring to both convoy interfaces (494, 622). Convoy waypoint-drop — port planning long-press->drop; KEY RISK = JS->Android bridge (convoy map had addMarker/clearMarkers undefined; may need same JS interface methods). Both are PORTS, not new code.
- **Route planning (snap-2)** — was the 2-day priority; now downstream of the import/add-core work (routes bypass the core too).

## STATUS / FACTS
- Branch feature/convoy-event-ride PUSHED (HEAD==origin via recommit12 3f311d958). P1=3339839f4, P2=eaf8508c1 committed+pushed.
- Today's bypass patch applied to working tree, NOT committed.
- Droid 2 spatial DB: /sdcard/Documents/GroupTrack/grouptrack_spatial.db (~117MB; 49047 trails / 67 tracks / 559 waypoints / 0 routes). Pull MSYS_NO_PATHCONV=1.
- Build ~32-40 min Kotlin; lintVital ServiceKeepAlive error EXPECTED, ignore. After device reboot, may need to re-accept USB debugging prompt.
- A/B split tool (no-build isolation) worked great: awk split onx file by track count into chunk_A/chunk_B with valid GPX header, push + import each.

## SUPERSEDED (marked, not deleted — rule 5)
- STATE_OF_PLAY_2026-06-02 v1 (panel-render theory), v2 (add-core-bypass-as-the-hang), v3 (waypoint/route-import-as-the-hang) — ALL superseded by this v4: the crash is an OOM in the file LOAD phase (load-whole-file-at-once), proven by lmkd + zero inserts + the A/B split. The add-core bypass and the waypoint/route import gaps are real but SEPARATE findings.
- NOTE_track_crash_device_specific / _recap_exonerated / ISOLATION_PLAN_droid2_track_crash / SEVERITY_CORRECTION — earlier diagnosis attempts, superseded.
