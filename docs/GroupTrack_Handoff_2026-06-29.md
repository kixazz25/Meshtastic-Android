# GroupTrack — Next-Session Handoff (cold start)
**From:** 2026-06-29 EOD · **For:** 2026-06-30
**Branch:** feature/convoy-event-ride · **HEAD:** `0191c7034` (06-29 WIP marker — V2.6 track-capture banked, builds clean, NOT field-verified) · **V2.5 LIVE on Play Store at `c603bc3f0`**
**Read with:** `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-29.md` (the checklist is the detail; this is the orientation).

---

## ⛔ FIRST TASK — WIRE TRACK ALIASES (the repair sequence)
The dupe/alias path works for TRAILS but was **never wired for tracks** (verified 06-29 on device: spatial DB has no trigger; `artifact_aliases` has 7 rows, all trail-type, zero track aliases; sync's dupe branch at `SpatialDbManager.kt:592` does rename-only and never reaches an ALIAS decision).

**Build ONE shared dupe/alias resolver, route IMPORT + SYNC + CREATE through it (all three resolve identically):**
- new hash → **INSERT** (insert track, file `<hash>.gpx`).
- hash exists, **same name** → **DROP** (delete the redundant source file).
- hash exists, **different name** → **ALIAS** (write alias → existing track_id, then **DELETE** the source file — do NOT rename; the dupe's value is its human name → an alias).

Mirror the trail ALIAS path (`resolveByGeom`/`decideAddAction` → `AddDecision.ALIAS`, alias writer ~SpatialDbManager:1188 — proven by the 7 trail rows). REUSE the existing alias writer; don't reinvent. Resolve track_id by hash for the alias FK. This is principle #2: code presents, the DB decides. It also makes dupe cleanup automatic going forward.

Then in order: (2) field-verify the metric feed (TrackPropertiesUpdater) via the cold-launch recipe; (3) field-cert CREATE on Droid 2; (4) field-cert IMPORT; (5) route-planning popup BUG A. Gate the next AAB on all of the above + popup.

---

## ⚠️ HARD-WON LESSONS (don't repeat)
- **BUILD THE REQUESTED/VISIBLE THING FIRST.** 06-29's cardinal failure: Fred asked for the sync control screen 3+ times; Claude kept building infrastructure and deferring the visible surface, then sent Fred to test with no screen and no log command. Do the asked thing first; communicate before redirecting.
- **INVENTORY each function's original-design fix BEFORE stacking new pieces.** The import extension wire was sitting on an import base fix that had never been made — would've silently no-op'd. Fred caught it.
- **COLD-LAUNCH FROM THE ICON, never recents.** Recents resumes the old WebView/code — the likely cause of the 06-29 inconclusive test (after-DB still `track_properties` rows:0). Verify deploy via APK-grep, not the in-app Settings build date (stale).
- **NO SILENT PROCESSES** + **INSTRUMENT FIRST** (disk log) + believe the device/Fred over the code + look at DATA before changing dependent code.

---

## ✅ WHAT LANDED 06-29 (committed in `0191c7034`, builds clean, field-UNVERIFIED)
- **Sync control screen** (visible feed + failures-first recap) + autoscroll — the owed surface.
- **Sync single-flight guard**; loose map-lifecycle auto-call REMOVED → **sync is now MANUAL-REQUEST ONLY** (the auto-call was masking broken create/import; a nonzero added/renamed count is now a SIGNAL of failure, not something to absorb).
- **Reporting backend v3** (`TrackSyncResult` gains propsWritten/propsTotal/failures).
- **Trash filter** (`.trashed-` files no longer re-processed; 71→210 bug).
- **TrackPropertiesUpdater** metric service (hash-keyed; distance/duration/speed/elevation/point_count) + wires from save/import/sync.
- **Import base fix** (DB-first, `<hash>.gpx` naming).
- **File-tap import disabled** (rogue legacy named-import path).
- **Save Track dialog** (blank-name → confirm-delete; non-cancelable).

**06-29 test was INCONCLUSIVE** — the resync ran on old code (recents, not a cold icon launch); metric feed never exercised. Re-verify 06-30.

---

## 🧹 DEVICE STATE (end of 06-29)
- **Droid 1** (`8624SBCEDF00001789`, field): my_tracks cleaned to **73 `<hash>.gpx` files, 0 non-hash**. 55 deleted (52 dupes + 3 junk temps), all backed up to `/sdcard/Download/mytracks_nonhash_backup_20260629`.
- **Droid 2** (`24039703201775`, dev): keeps its OWN corrected set. **Devices are NOT mirrored** — same APK on both, each resyncs its own files→DB independently.
- Both devices on the `0191c7034` build, synced and ready.
- **Test fixture (do NOT sweep):** orphan spatial row `82a5cb01` ("St George to Bar10", hash `9969bdf6`, no file, nothing attached) — kept on purpose to test the delete-track fix and the sync reconcile-leg. The 73-vs-74 row count is this one orphan, by design.

---

## 🆕 NEW OPEN TASKS captured 06-29 (detail in checklist)
- **Delete-artifact is legacy pre-spatial** — deletes the FILE only, NEVER the DB row → ongoing orphan generator. Fix = one `deleteTrack(track_id)` removing row + extension + file (the delete half of the paired add/delete contract).
- **Sync DB→file reconcile leg — DEMOTED to optional safety net.** The delete-artifact fix is the real solution (stops orphans at the source). This sweep MAY still be linked into sync as a should-never-fire tripwire — if it ever finds an orphan post-fix, that's a red flag of a new orphan source, not normal operation. Not a repair feature.
- **Recording fails on first attempt (Android mode)** — first record yields an empty 0-point track before Android "recognizes" it.
- **Track extended-DB data in artifact detail + track popups** — surface track_properties (depends on metric feed populating).
- **Track rename scope fix** — rename should touch ONLY the name field, not re-write the track.

---

## 📌 ALSO PENDING (not blocking the alias work)
- Release notes: **mandatory track-resync** tester note is QUEUED for the NEXT build's notes (TBA until the AAB ships) — re-pull Fred's exact wording from the 06-29 conversation; do NOT invent it. Current notes/manual stay 06-23 (nothing field-verified to announce).
- Multi-track splitter (`ConvoyTrackOps.kt:~230`) still `$baseName.gpx`, file-only — fold into hash/DB-first during the import deep-dive.
- Elevation m/ft assumption in the metric feed — verify against a known ride.

---

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease 2>&1 | grep -E "^e:|BUILD|FAILED"` (06-29 was 36m51s SUCCESSFUL). APK: `app/build/outputs/apk/google/release/app-google-release.apk`. AAB: `bundleGoogleRelease`.
- Install: `adb -s <serial> install -r -d <apk>` → `MSYS_NO_PATHCONV=1 adb -s <serial> shell am force-stop com.grouptrack.android` → **cold-launch from icon**.
- Droid 1 `8624SBCEDF00001789` (field/real-GPS) · Droid 2 `24039703201775` (dev). `MSYS_NO_PATHCONV=1` on REMOTE adb paths. No sqlite3 on device — pull DB, query via `python3 -c "import sqlite3 …"`. DBs: `/sdcard/Documents/GroupTrack/grouptrack_spatial.db` + `grouptrack_data.db`. my_tracks: `/sdcard/Documents/my_tracks/`. Sync log: `track_sync.log` (tag `TrackSync`; `TrackProps` for the updater).
- Patches: standalone dated python → Downloads → `python3 <name>.py`; count==1 anchor guard, runtime newline detect, all-or-nothing. Commit only named files, never `git add .`. LINE ENDINGS: SpatialDbManager/ConvoyViewModel/ConvoyTrackImportScreen/ConvoyTrackOps = LF; ConvoyMapViewerScreen/ConvoySettingsScreen/HTMLs/ConvoyGpsService/MainActivity = CRLF.
