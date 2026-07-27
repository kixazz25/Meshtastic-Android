# STATE OF PLAY — 2026-06-02 v2 (read first; supersedes v1 and the 06-02 AM crash docs)

Living status for today. Surgical-append only (Working Agreement rule 5). Last-written version of the day is what counts; the 06-02 set replaces 06-01 tomorrow. Authoritative tracker = v25_master_checklist.md (Section K/L = DB design).

## HEADLINE (June 2): the dedup add-core contract was never enforced — tracks (and likely waypoints/routes) bypass it and DO NOT DEDUP

This supersedes v1's "track summary panel render" diagnosis. The investigation went deeper and found a structural problem bigger than the crash.

### PROVEN LIVE (Droid 2 logcat, this morning)
- The SAME 2-track file ("Bar10 and enoch.gpx": St George to Bar10, mike s enoch rec) was imported THREE times.
- Each run logged "Inserted track to DB" for BOTH tracks and "Import complete: 2 tracks" — i.e. inserted all three times, NEVER flagged as dupe or alias.
- Conclusion: TRACKS DO NOT DEDUP. (An older "TrackImport" intent path once logged "Imported 0, skipped 2 (existed)" — so a different/older path deduped, but the live SpatialDb.insertTrackToDb path does not.)
- The 2-track pipeline otherwise runs CLEAN: recap shows, source deletes, no errors. So the import loop is healthy.

### ROOT CAUSE — where-used scoping was skipped (Working Agreement rule 3)
- The add-core's decision function is `resolveByGeom(type,name,geomHash): AddDecision{INSERT,DROP,ALIAS}` (SpatialDbManager.kt:764; enum :760).
- Per xref where_used, the ONLY caller of the per-type insert path is `TrailImporter.insertFeature` (TrailImporter.kt:209). `insertTrackToDb` / `insertWaypoint` / `insertRoute` do not appear as callers of the decision path.
- So the code comment "All four artifact types funnel through this core" (SpatialDbManager ~688-695) is FALSE in practice. ONLY TRAILS funnel through `resolveByGeom`. Tracks/waypoints/routes bypass it with their own inline `INSERT OR IGNORE`.
- `insertTrackToDb` (670-687, read in full): computes geom_hash (clean SHA-256) then does its OWN `INSERT OR IGNORE INTO tracks(...)`. No `resolveByGeom`, no `beginDedupSession`, no alias decision. It is lean — it cannot itself hang.
- WHY no dedup even with INSERT OR IGNORE: `resolveByGeom` returns INSERT unconditionally when `dedupHashToName[type]` is null (line 765), and `beginDedupSession` (which loads that map) is never called for tracks. The 3x-insert ALSO proves the tracks-table `UNIQUE(geom_hash)` is absent or the hash differs per run — VERIFY: `SELECT name,geom_hash,created_at FROM tracks WHERE name IN ('St George to Bar10','mike s enoch rec')` and `SELECT sql FROM sqlite_master WHERE type='table' AND name='tracks'`.

### Fred's framing (the lesson)
"We developed a function with specific rules that anything adding to these artifacts must use, and never did a where-used scoping to be sure the function was being used." The contract was asserted in a comment, never verified against call sites. Rule 3 (xref scope analysis before touching a change) was the safeguard and it was skipped.

## PRIMARY WORK (supersedes "just fix the hang")
1. AUDIT — build the bypass table: every artifact write, on-core (calls `resolveByGeom`) vs bypass (direct INSERT). Confirmed: trails ON-core; tracks BYPASS. Waypoints/routes PENDING — run `grep -rn "resolveByGeom" app/src/main/java/com/geeksville/mesh/convoy/` and read `insertWaypoint`/`insertRoute` bodies. (xref where_used may be stale — regen 05-31 — so verify live.)
2. MIGRATE `insertTrackToDb` / `insertWaypoint` / `insertRoute` to route through the shared add-core (resolveByGeom + the trail path's decision/alias handling), retiring the inline INSERT OR IGNORE.
3. CONFIRM by where-used AFTER the migration that the bypass inserts are gone and the only path to each table is the core. Enforce by verification, not by comment.
- Expected payoff: the add-core is proven for trails on both devices, so migrating likely fixes track dedup AND gives tracks the recap breakdown — and may resolve the 87-track hang as a side effect.

## SECONDARY — the 87-track HANG (separate issue)
- File: onx-markups-2026-04-21.gpx (the 06-01 "(1)" copy, 28.9MB, 87 tracks, last track "BARRACKS LOOP 4\30\22"). Imported on Droid 2 with NO recap, NO source delete = HANG.
- RULED OUT by code reading: device-specific theory, recap-panel-render theory, dedup-hashing theory. The 2-track pipeline runs clean, so the hang is SCALE/DATA-specific to this file, not a structural loop bug.
- Frozen spinner + un-deleted source = loop never reached step 4 (delete). Leading suspect: `parseGpxTrackPoints` on a specific track's points (catastrophic-backtracking class, same family as the >32MB known crash). NOT YET CONFIRMED — need the track-timestamp query (where the loop got to) + `grep -c "<wpt"` / `grep -c "<rte>"` on the file (track-loop vs downstream).
- Keep separate from the KNOWN >32MB crash (onXmaps-05_27_26, Section C, expected).

## UX/DATA BUGS confirmed live today
- KEEP/DELETE recap buttons are MISLEADING. `importGpxAllArtifacts` auto-deletes the source GPX at step 4 on success, BEFORE the recap dialog appears. "KEEP FILES & CLOSE" (onDismiss only) cannot preserve the source — Fred clicked KEEP and the file was deleted anyway. Fix: gate the auto-delete on the user's choice, or fix the labels. (Corollary used throughout diagnosis: a surviving source file = the import HUNG.)

## STILL TRUE / STATUS
- Branch feature/convoy-event-ride is PUSHED (recommit12 commit 3f311d958 carried it; HEAD == origin). The "push 2 commits" item is DONE. Commits: 3339839f4 (P1), eaf8508c1 (P2 dedup core + trail recap).
- CORRECTION to 06-01 "proven end-to-end, all four through the core": dedup is PROVEN FOR TRAILS ONLY. Tracks never deduped. The 06-01 "67 tracks with cross-file dupes collapsed correctly" claim is now in DOUBT — re-verify.
- Spatial DB on Droid 2: /sdcard/Documents/GroupTrack/grouptrack_spatial.db (~117MB; 49047 trails / 67 tracks / 559 waypoints / 0 routes as of today). Pull with MSYS_NO_PATHCONV=1.
- After the add-core migration: route planning (snap-2) resumes — but note routes ALSO bypass the core, so snap-2 routes won't dedup until migrated. The migration is now upstream of route planning.

## SUPERSEDED (marked, not deleted — rule 5)
- STATE_OF_PLAY_2026-06-02_v1 — "track summary panel render hang" diagnosis. SUPERSEDED: the panel renders counts only and the pipeline runs clean on 2 tracks; the real finding is the add-core bypass + a separate scale/data hang.
- NOTE_track_crash_device_specific / NOTE_track_crash_recap_exonerated / ISOLATION_PLAN_droid2_track_crash — all rested on theories code-reading + live logcat have since ruled out.
