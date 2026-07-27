# DROID-2 TRACK-CRASH — ISOLATION PLAN (start here AM 2026-06-02)

Read this FIRST. It is an EXECUTE plan, not a discussion. Goal: isolate the Droid-2 track-import crash in ONE focused build cycle, hit the ground running, do NOT burn the day re-diagnosing. The crash is LOW severity (no data lost — 67 tracks landed, matches Droid 1) so if isolation stalls, drop it and go to route planning.

## THE PROBLEM (one paragraph)
On Droid 2 (serial 24039703201775), the GPX track import COMPLETES (67 tracks written, matches Droid 1) but the app CRASHES/freezes at or just after completion. Crash logs are EMPTY (logcat -b crash and main buffer both) = hard lock / process-kill, not a Java exception.

## WHAT WE KNOW (do not re-derive)
- Same tracks + same code imported on **Droid 1 with NO crash**. So it is one-device-not-the-other.
- Commit history: ONE commit `eaf8508c1` (P2) contains BOTH the dedup add-core AND the trail recap. Prior commit `3339839f4` (P1) = v3 schema + delete-gate only, NO dedup add-core, NO recap.
- `eaf8508c1 --stat` changed: schema_extension_v3.sql, schema_spatial_v3.sql, **SpatialDbManager.kt** (+166), **TrailImporter.kt** (-46/+). It did NOT change ConvoyTrackOps.kt.
- TRACK import path = **ConvoyTrackOps.kt** `importGpxAllArtifacts` (line 479; completion log line 594; calls `SpatialDbManager.insertTrackToDb` at line 544). The RECAP is in TrailImporter.kt — NOT on the track path. The shared code on the track path that DID change is **SpatialDbManager.insertTrackToDb** (dedup core added SHA-256 geom_hash + alias logic to it).
- RULED OUT (by "Droid 1 ran identical code/data fine"): the recap function itself, and "hashing is inherently fatal." The differentiator is Droid 2 (state/memory) OR our new track-path insert code interacting with Droid 2 specifically. Fred's directive: STOP defaulting to hardware — isolate the variable by rolling our new code out and testing.

## OPEN QUESTION TO RESOLVE FIRST (5 min, before any build)
The recap is NOT on the track path per the stat — yet the crash is on tracks. So pulling JUST the recap likely changes nothing on tracks. The real new code on the track path is `insertTrackToDb` (in SpatialDbManager). Confirm by reading it (Step 1). This decides whether we isolate the recap or the dedup-core track insert.

## ISOLATION PLAN — execute in order

### STEP 1 — SEE what changed on the track path (no build; ~2 min)
```
cd ~/Meshtastic-Android
git show eaf8508c1 -- app/src/main/java/com/geeksville/mesh/convoy/SpatialDbManager.kt | sed -n '1,200p'
sed -n '670,740p' app/src/main/java/com/geeksville/mesh/convoy/SpatialDbManager.kt
```
Look at what the commit added to `insertTrackToDb` (line 670). That is the ONLY new code on the track path. Decide: is there anything there that could hard-lock on a low-RAM/stressed device (big in-memory build, a loop, a synchronous heavy op, a transaction held open)?

### STEP 2 — ISOLATE by neutralizing the new track-path code, then build ONCE
Two options — pick based on Step 1:
- **2A (surgical, preferred — keeps v3 schema intact):** in `insertTrackToDb`, comment out / bypass the NEW dedup-core code (the geom_hash compute + alias resolve) and insert the track the OLD way. CAUTION: v3 schema has `geom_hash NOT NULL` on tracks — so a bypass must still write SOME geom_hash (e.g. keep the hash line but remove whatever else was added), or the insert fails the constraint. Read Step 1 first to see exactly what to keep vs cut.
- **2B (clean rollback):** build from `3339839f4` (P1 only). BUT P1 has the v3 schema with dedup constraints while P1's code predates the add-core — verify old insertTrackToDb satisfies geom_hash NOT NULL before trusting this. If it doesn't, 2A is the only clean option.
Then: ONE build (~38 min, Kotlin path). `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`

### STEP 3 — TEST on Droid 2, READ THE RESULT
- Install: `adb -s 24039703201775 install -r -d app/build/outputs/apk/google/release/app-google-release.apk`
- BEFORE importing, start a FULL unfiltered logcat to a file (the empty crash log means we need the live buffer at lock time):
```
adb -s 24039703201775 logcat -c
adb -s 24039703201775 logcat -v time > "C:/Users/kixaz/Downloads/d2_trackimport.log" 2>&1
```
- Run the track import. Let it finish/lock. Wait ~15s. Ctrl-C the logcat. `tail -120` the file.
- DECISION:
  - Track import completes WITHOUT crash → the new track-path insert code is the cause. Add it back piece by piece (hash first, then alias logic) to pinpoint, or accept the old insert for tracks. CONFIRMED.
  - Still crashes → our track-path code is NOT the cause. It is Droid 2 (memory/state/storage-thrash). Then: get Droid 2 RAM + Android version, compare to Droid 1; test track import on a FRESH BOOT (clean memory) vs after back-to-back imports; tie to the tile/media-scan storage fix. Stop chasing code.

## IF ISOLATION STALLS
This is LOW severity — data is not lost. Do NOT sink the whole day. Cap it: if Step 3 doesn't give a clean answer in one build cycle, file the logcat tail, move to ROUTE PLANNING (the actual 2-day priority), come back to this later.

## DO NOT
- Do NOT re-chase the recap as the track-crash cause without first confirming via Step 1 whether it even touches the track path (the stat says it does not).
- Do NOT default to "it's the hardware" before running the isolation build. (That was the mistake to avoid — isolate first.)
- Do NOT re-run the full track import blindly hoping it works — it will reproduce. Capture logcat when you run it.
