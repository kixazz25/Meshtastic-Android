# NOTE — Track-crash diagnosis: recap function EXONERATED (2026-06-01 EOD)

Follow-up to NOTE_droid2_tracks_completed. Traced the code paths to test the "summary function caused the crash" theory.

## Finding: the recap patch is NOT in the track-import path
- Track import lives in **ConvoyTrackOps.kt** -> `importGpxAllArtifacts` (line 479), completion log at line 594 ("Import complete: N tracks...").
- The import-recap patch (patch_v25_import_recap_v1.py) ONLY touched **TrailImporter.kt** (importFromSource). It never went near ConvoyTrackOps.
- Therefore the track-import COMPLETION/summary code is UNCHANGED from before the recap patch. The recap function is exonerated for the track crash — it's not in that code path. (Confirmed also by: the trail import on Droid 2 ran the new recap and completed fine.)

## Revised lead suspect for the post-import track crash
- What DID change on the track path: ConvoyTrackOps.kt:544 calls `SpatialDbManager.insertTrackToDb(...)`, and the DEDUP CORE patch (patch_v25_db_dedup_core_v1.py) modified insertTrackToDb to compute SHA-256 geom_hash + 'Not Named' fallback on every track insert.
- This fits the observed symptoms: track imports got noticeably SLOWER after the dedup patch (SHA-256 over long track WKT — a 144-mile track is a huge string), and the crash was at/after completion with EMPTY crash logs (hard lock, not a Java exception).
- So the post-import track crash most likely ties to: (a) hashing load/memory on large tracks in insertTrackToDb, and/or (b) the device storage/media-scan thrash that locked devices all session — NOT the recap summary.

## Severity: LOW (no data impact)
Track import COMPLETED — 67 tracks, matches Droid 1. Data fully written before the crash. This is a stability/polish issue at completion, not a correctness/data issue.

## NEXT-SESSION DIAGNOSTIC (start here, not at the recap)
1. Capture a fresh FULL unfiltered logcat to a file during a track import; watch the last lines before the lock (look for OOM, GC thrash, lowmemorykiller, or it just stopping = hard lock).
2. Look at ConvoyTrackOps.kt importGpxAllArtifacts (479-594) — how it loops/holds tracks in memory, whether it accumulates all WKT before insert, and the insertTrackToDb hashing cost on large tracks.
3. Tie to the tile-storage media-scan fix (already filed) — if the lock is storage thrash, that fix addresses it.
4. Consider the beginDedupSession optimization (load only the type being imported) to cut track-import memory/time.
