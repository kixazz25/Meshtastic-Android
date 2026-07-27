# STATE OF PLAY — 2026-06-02 v1 (read first; supersedes the 06-02 AM crash docs)

Living status for today. Surgical-append only (Working Agreement rule 5). The last-written version of the day is the one that counts; these get retired/replaced tomorrow. Authoritative tracker remains v25_master_checklist.md (Section K/L = DB design).

## TODAY'S HEADLINE: track-crash cause RE-IDENTIFIED — it's the new track summary panel, NOT the device, NOT the dedup core

Diagnosis corrected this morning in conversation with Fred. The 06-01 EOD / 06-02 AM notes concluded the crash was DEVICE-SPECIFIC to Droid 2. That conclusion was built on a FALSE PREMISE and is now superseded.

### The corrected finding
- The crash is in the NEW track-import SUMMARY PANEL (the recap UI that reports: tracks processed / added / skipped-as-dupe / added-as-alias). The panel NEVER DISPLAYED — the import completed (last track of the GPX written, DB record created), then the app hung and eventually died at the point the panel should have rendered.
- Symptoms all fit a failed panel render, not device pressure: (a) POST-completion — all tracks written before the hang; (b) hang-then-die with the panel never appearing — the hang IS the failed render; (c) EMPTY crash log (no Java FATAL) — consistent with the panel reading track-path data that was never populated (the track path collapses via INSERT OR IGNORE, NOT the AddDecision enum the panel's buckets expect).

### WHY the earlier "device-specific" conclusion was wrong (the false premise)
- The notes said "Droid 1 ran identical code + identical data with NO crash," and used that to rule out both the recap and the hashing, leaving "the device" as the only suspect.
- BUT the summary panel was ADDED AFTER the Droid 1 track run — it was built precisely because there were unanswered questions about what happened during that Droid 1 import. So Droid 1 ran a build WITHOUT the panel; Droid 2 ran a LATER build WITH the panel. That is NOT identical code. The real differentiator everyone attributed to "the device" is "the panel exists in the Droid 2 build and did not exist in the Droid 1 build."
- Prediction from the corrected model: put the current (panel-bearing) build on Droid 1 and it should crash there too. The bug is not device-bound.

### Two distinct track crashes — keep them separate (Fred clarified)
1. OVERSIZED GPX (>32MB, 50+ concatenated downloads): a KNOWN, pre-existing crash (checklist Section C large-file item). Never addressed, not on the active task list, failed as expected. NOT the bug we are chasing. Handled later by the split / parser string-loop fix (see ADDENDUM_large_gpx_split + NOTE_large_gpx_crash_confirmed).
2. THE REAL BUG: a SUB-32K file that parsed completely, wrote its last track + DB record, then hung and died at the summary-panel render. THIS is the post-completion panel crash described above.

## WHY Fred's original isolation plan would have MISLED (recorded so we don't repeat it)
- Original plan: remove the two changes in eaf8508c1 (dedup add-core + trail recap), rerun, see if old totals return — to isolate the revisions from the crash.
- Problem: neither of those two is the panel. The trail recap only touched TrailImporter.kt (not the track path). The dedup core's per-track work (SHA-256 geom_hash + alias logic) all RAN TO COMPLETION before the crash (the last track's DB record was created), so the per-insert work is not the killer. Neutralizing both would have left the PANEL code in place — it would crash again — sending us back to "must be the device." The thing to fix is the PANEL RENDER on the track completion path.

## NEXT ACTIONS (start here)
1. Confirm what build is on Droid 2 and whether the panel is committed or sitting in the working tree:
   `git status` · `git log --oneline -5` · (if uncommitted) `git diff`
2. Read the panel code + what it reads on the track path:
   `grep -rn "processed\|aliased\|skipped\|dupe\|ImportResult\|ImportProgress" app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt`
   `sed -n '479,600p' app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt` (completion path; log line ~594)
3. Expected fix shape (diff decides which):
   - (a) TRACK path doesn't populate the buckets the panel reads -> panel renders null/garbage. Fix = make insertTrackToDb report inserted-vs-collapsed (the FOLLOW-UP already filed), OR guard the panel to render the track path's actual data. This ALSO closes the open "track-import recap breakdown" follow-up — same root.
   - (b) the panel render itself is buggy (wrong context/thread for showing the dialog, layout/inflation error) -> fix the render.
4. SEVERITY: HIGH (per SEVERITY_CORRECTION_2026-06-02). A crash that hangs the device in the field is launch-blocking-class. Give it the focused session before route planning — do NOT punt as "low/polish."

## STILL TRUE FROM 06-01 (unchanged)
- COMMITTED on branch feature/convoy-event-ride, ahead of origin by 2, NOT pushed: 3339839f4 (P1 migration), eaf8508c1 (P2 dedup core + trail recap). Push when ready (`git push`).
- Dedup core + TRAIL recap PROVEN on Droid 1 (clean install) and Droid 2 (real v1->v3 upgrade): zero geom_hash collisions, 7 aliases consistent across both devices, track cross-file dupes collapse correctly. (This is about the dedup/trail-recap work — independent of the track PANEL bug above.)
- After the track-panel fix: per the SEQUENCE DECISION, ROUTE PLANNING first (2-day pass, snap-2 point-to-point on trails+tracks), then AWS mirror + cleanup. See NEXT_SESSION_HANDOFF_aws_and_routes.
- OPEN snap-priority design Q (decide at route-planning start): trail-first vs track-first when both in snap range.

## SUPERSEDED BY THIS DOC (marked, not deleted — rule 5)
- NOTE_track_crash_device_specific_2026-06-01 — "device-specific to Droid 2" conclusion. SUPERSEDED: rested on the false "Droid 1 ran identical code" premise; Droid 1's build lacked the panel.
- NOTE_track_crash_recap_exonerated_2026-06-01 — exonerated the recap on the grounds it wasn't on the track path. PARTIALLY SUPERSEDED: the recap COUNTING isn't on the track path, but the recap PANEL is, and it's the crash.
- ISOLATION_PLAN_droid2_track_crash_2026-06-02_AM — its "isolate the dedup core vs the device" framing targets the wrong code (the panel is neither the dedup core nor on the device axis); its STEP 1 (read the diff first, no build) is still the right method. SEVERITY framing in it already corrected by SEVERITY_CORRECTION.
- NOTE_droid2_tracks_completed_2026-06-01 — still accurate that the import COMPLETED with no data loss (67 tracks); only the severity/"minor" framing is corrected (see SEVERITY_CORRECTION).
