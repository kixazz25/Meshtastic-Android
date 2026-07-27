# NOTE — Track-crash diagnosis CORRECTED: it's device-specific (Droid 2), not code (2026-06-01 EOD)

Supersedes the suspect in NOTE_track_crash_recap_exonerated. Both code-level suspects are now ruled out by the same evidence.

## The decisive fact
The SAME tracks, through the SAME insertTrackToDb (with the SAME SHA-256 hashing), imported on **Droid 1 WITHOUT any crash**. Droid 2 crashed on identical code + identical data.

## What this rules OUT
- **Recap/summary function** — already exonerated (not in the track path; track import is ConvoyTrackOps.kt, recap patch only touched TrailImporter.kt).
- **Dedup-core hashing in insertTrackToDb** — NOW also ruled out. Droid 1 ran the identical hashing on the identical tracks cleanly. If hashing were the cause, Droid 1 would have crashed too.

## Corrected lead: DEVICE-SPECIFIC to Droid 2 (serial 24039703201775)
The crash correlates with the DEVICE, not any code change. Candidates, in order:
1. **Memory / RAM** — if Droid 2 has less RAM than Droid 1, the same import that fits Droid 1's heap could OOM on Droid 2. (Need Droid 2's RAM spec — not on record. Note tester P10_T is 3GB and has known osmdroid/ANR memory issues.)
2. **Accumulated state / memory pressure** — Droid 2 did v1->v3 upgrade -> trail import (49K+) -> track import back-to-back in one session, plus was force-stopped/reset repeatedly. A leak or unreleased resource across those sequential heavy operations.
3. **Storage / media-scan thrash** — Droid 2's tile pile + the media-scan lock that bricked devices all session is device-state, hits one device not another depending on what's in storage. (Ties to the filed tile-storage media-scan fix.)

## NEXT-SESSION DIAGNOSTIC (corrected starting point)
1. Get Droid 2's RAM spec + Android version; compare to Droid 1. If Droid 2 is low-RAM, OOM is the likely answer.
2. Full unfiltered logcat to file during a Droid 2 track import — look for OutOfMemoryError / lowmemorykiller / GC thrash / hard-lock (stops with no exception).
3. Test: does Droid 2 crash importing tracks on a FRESH boot (clean memory state) vs after back-to-back imports? Isolates accumulated-pressure vs inherent.
4. Do NOT re-chase the recap or the hashing — both ruled out by "Droid 1 ran identical code/data fine."

## Severity: LOW (no data impact)
Track import COMPLETED on Droid 2 anyway — 67 tracks, matches Droid 1. Data fully written before the crash. Stability/polish issue, not correctness.

## Method lesson (for Claude)
Floated two code suspects (recap, then hashing); user ruled out BOTH with the same fact — "Droid 1 did the identical thing fine." When a crash is one-device-but-not-another on identical code+data, the differentiator is DEVICE/STATE, not code. Weight that first.
