# GroupTrack — Next-Session Handoff (cold start)
**From:** 2026-06-30 EOD · **For:** next session
**Branch:** feature/convoy-event-ride · **HEAD:** `d719fbc95` (06-30 marker — V2.6 **track services layer** banked, builds clean, import **field-verified on-device**) · **V2.5 LIVE on Play Store at `c603bc3f0`**
**Read with:** `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-30.md` (the checklist is the detail; this is the orientation).

---

## ✅ WHAT LANDED 06-30 (committed in `d719fbc95`, builds clean, import VERIFIED)
The whole **track services layer** — the shared machinery all three capture processes now run on:

- **Unified add resolver** `resolveTrackAdd(name, sourceFile): AddOutcome` — the ONE shared add path. SYNC, CREATE, and IMPORT all route through it identically. Rereads the file from disk (single source of truth). Resolves to **INSERT** (new hash → materialize `<hash>.gpx` + metrics) / **DROP_NAME** (hash+name == official name → delete source) / **DROP_ALIAS** (hash+name == an existing alias → delete source) / **ALIAS** (hash + new name → write alias, delete source).
- **`insertTrackToDb`** now returns `TrackInsertResult(wasNew, trackId, geomHash, name)` (was Boolean) — race-free; on a dupe it returns the existing track_id.
- **Complete delete service** `deleteTrackFromDb(id)` — removes spatial row + track_properties + ALL aliases + the `<hash>.gpx` file (was spatial-row-only — the orphan generator). This is the detail-panel whole-track delete.
- **Alias maintenance** — `setPreferredAlias` rewritten into the **SWAP** (promote an alias to the official `tracks.name`, demote the old name into the alias row; trail-guarded; LOCAL-ONLY); new `renameAlias(aliasId, newText)`. `is_preferred` is informational list-ordering only.
- **Alias-join search** — `searchByName` matches the official name OR any alias, across all artifact types (Kotlin two-query merge since spatial + extension are separate SQLite files). Carries `matched_via_alias`/`matched_alias` for a later display pass.
- **Import rework + fix** — `importGpxAllArtifacts` routes each split track through the resolver; 4-way recap (new/aliased/duplicate/skipped) + a LIVE per-record feed on the import screen (mirrors the sync control screen). Step instrumentation kept in (logcat `ImportDiag` + feed).

**Verified on-device:** a well-formed 16-track GPX imported and correctly reported all 16 as duplicates — the resolver, import path, and recap all working end-to-end.

---

## ⛔ FIRST TASK — WIRE THE TRACK DETAIL PANEL TO THE NEW SERVICES
The services exist; the **detail-panel UI is still calling the legacy file-only ops.** Repoint them and retire the legacy generators:

- Repoint `ConvoyMapViewerScreen:~1681/1700` (rename/delete) and `ConvoyTrackExportSheet:~256/275` to the DB services (`deleteTrackFromDb`, `renameTrackInDb`).
- **RETIRE** legacy `ConvoyTrackOps.deleteTrack(File)` (~line 63, `file.delete()` only — the orphan generator) and `renameTrack(File)` (~line 50, renames the file off its hash — wrong under hash-naming).
- Build the **alias-display UI**: a "has aliases" indicator on list/result rows + a tap-to-view popup (reuse `getAliasesFor` + the detail-panel accordion).
- Build the search **"matched via alias" highlight** (2nd-pass display — the data fields `matched_via_alias`/`matched_alias` are already carried by `searchByName`).

`ArtifactDetailPanel` is a reusable callable composable (self-loads via `onLoadDetail`/`onLoadAliases`; hosted by ConvoyMapViewerScreen + ConvoyScreen) — only its injected callbacks differ per host, and that's where the file-vs-DB mismatch lives.

**Then in order:** (2) the recording show-stopper (below); (3) field-verify the metric feed; (4) field-cert CREATE on Droid 2; (5) field-cert IMPORT end-to-end (insert + alias, not just dupe); (6) route-planning popup BUG A. Confirm final order at the top of the session.

---

## 🛑 SECOND TASK — ANDROID-GPS TRACK RECORDING (CREATE via RADIO is CERTIFIED ✅)
**06-30 field result: recording a new track via RADIO GPS is PERFECT** (record → save → display → file/DB, verified). The bug is **isolated to ANDROID-only GPS mode** — Android tracks fail to record. Note we KNOW the Android fix works: it's used to set the Convoy map location when the map opens. So the Android location is available; it's just not reaching the track recorder.

**The recorder is `ConvoyGpsService.kt`. Source selection + point sink (from the xref):**
- `var useRadioGps: Boolean` (~238) — the source flag.
- `onRadioPosition(lat,lon,alt)` (~245) — RADIO feeds points here (WORKS).
- `startLocationUpdates()` (~251-277) — the ANDROID `LocationManager` subscription (**prime suspect**).
- `onGpsUpdate(lat,lon,alt)` (~288) — common sink both sources funnel into → `writeGpxPoint` (~393) writes the `<trkpt>`.

**LEADING HYPOTHESIS:** in Android mode `startLocationUpdates()` subscribes to `LocationManager` with a provider/min-time/min-distance that delays or suppresses fixes, so no point reaches `onGpsUpdate`→`writeGpxPoint` before the track is stopped (→ empty/0-point / truncated track). The MAP-centering path gets an Android fix fine (last-known / fused / instant) — so compare how the map obtains location vs how the recorder subscribes. Confirm `startTrack()` actually calls `startLocationUpdates()` when `useRadioGps==false`, that permissions the service holds match the map path, and that the provider + filter actually emit points.

**FIRST COMMANDS next session:**
`sed -n '145,160p' …/ConvoyGpsService.kt` (startTrack — source choice / does it call startLocationUpdates?) and `sed -n '238,320p' …/ConvoyGpsService.kt` (useRadioGps, onRadioPosition, startLocationUpdates, onGpsUpdate — the whole Android path + common sink). Instrument `onGpsUpdate`/`startLocationUpdates` (log every provider callback) BEFORE theorizing. Also ties to recorder-truncation: record-stop must finalize the file (`</trkseg></trk></gpx>`).

---

## ⚠️ HARD-WON LESSONS (don't repeat)
- **INSTRUMENT BEFORE THEORIZING.** 06-30's rabbit hole: import returned 0/0/0/0 and ~2 hours went to theorizing (splitter logic, file format) before adding step logging. The moment we logged `regexMatches` / `contains<trk>` / file length, the cause was obvious in one run. Every step must log — and **DEPLOY the instrumentation**; perfecting code you can't observe is the trap.
- **DON'T MULTIPLY FAILURE POINTS BEYOND THE TARGET.** The import bug was a line-by-line `<trk>` streamer added earlier as premature OOM protection — it broke a working regex parser to solve a problem that may not exist. We reverted to the proven regex. Scope discipline.
- **BELIEVE THE DEVICE/DATA.** Sync parsed the same file fine while import failed — that divergence (sync scrapes points, import needs `<trk>...</trk>` pairs) led straight to the truncated-file root cause.
- **THE LOGCAT REDIRECT TRAP:** `logcat -d > C:\...` (backslash path) **silently writes no file** in Git Bash — the `>` is a Bash op needing a forward-slash path (`> /c/Users/kixaz/...`). An hour was lost grepping a file that never existed. (adb *pull* destinations, by contrast, need the `C:\...` Windows path with `MSYS_NO_PATHCONV=1`.)
- **COLD-LAUNCH FROM THE ICON, never recents** (recents resumes old code — the 06-29 inconclusive test). **GREEN BUILD + ON-DEVICE VERIFICATION before claiming done.** **BANK A MARKER before a long uncommitted stretch.**

---

## 🆕 NEW OPEN TASKS captured 06-30
- **Recorder truncation** (above) — produces GPX with no closing tags on interrupt; ties to the recording show-stopper. Fix at source.
- **Import tolerance (optional)** — import could salvage truncated files like sync does (parse points instead of requiring the `<trk>...</trk>` pair), OR surface "malformed: no </trk>" (the instrumentation now reports this; it's no longer silent).
- **`import_tracks` staging dir (Fred's design, NOT built)** — import should write split files to a separate `import_tracks/` inbox; a sync-variant drains it → `my_tracks/<hash>.gpx`, then empties it. Rationale: process only new arrivals (O(new)), not re-walk the whole library every sync (O(library)); clean inbox vs reconciled-library separation; failed files stay visible for retry. Current import writes to my_tracks via the resolver (works) — staging is the refinement. Cleanup policy (whole-dir vs per-file-on-success vs leave-failures) = decide at build time.

---

## 🟡 CARRIED OPEN TASKS (still relevant)
- **Field-verify the metric feed** (TrackPropertiesUpdater) — 06-29 was inconclusive (ran on old code from recents). Cold-launch recipe → RESYNC → `tail track_sync.log` for `=== properties: N tracks written ===` → pull DB → confirm track_properties populated. Watch elevation m/ft (`<ele>` assumed METERS ×3.281→ft; if 3× high, one-line fix).
- **Track rename = part of the alias/detail functionality** (delete, rename, alias swap/rename live together on the detail panel). `renameTrackInDb` already edits only `tracks.name`; the work is wiring the detail-panel caller to it (away from the legacy file-rename). Covered by the FIRST TASK above.
- **DB delete-gate fix (HIGH)** — `SpatialDbManager.init` wipes both DBs when prefs `db_schema_marker < 3`; marker resets on reinstall → re-wipes ("lost 110 tracks"). Gate on the DB's own `schema_version` table.
- **Route BUG A — disable artifact selection in create-route mode.** Tapping an artifact in route mode fires its popup → the popup refreshes Leaflet → that refresh OVERLAYS the route being drawn. One causal chain — suppressing the popup kills the overlay too. Re-apply the pointer-events gate on the trail/track LOAD path when `__routeMode` true (artifacts loaded after route-mode entry escape the entry-time gate). convoy_map.html:288 `tolerance:18, tapTolerance:20`.
- **Route BUG B** — resumed route draws chords (separate technical issue; `patch_resume_diag_log` written NOT run). SEPARATELY: **remove the ROLLBACK control** — Undo is fine, rollback is the issue; remove the rollback caller/entry only, keep `RouteDraftStore.openDraft` (resume needs it).
- **Track extended-DB data in detail + popups** — surface track_properties read-only; depends on the metric feed populating. Part of the detail-panel work.
- **`import_tracks` staging dir (Fred's design) — ON HOLD / parked.** (Idea: import writes split files to a separate `import_tracks/` inbox; a sync-variant drains it → `my_tracks/<hash>.gpx`. Process only new arrivals, not re-walk the whole library.) Current import writes to my_tracks via the resolver and works — staging is an unscheduled refinement.
- **Maps follow the artifacts** — per-artifact bbox tile download (19-track GPX → 19 separate requests, not a union). **NOT gated on the tile-queue redesign** — it simply submits each per-artifact bbox into the existing download queue. Opt-in; no silent process.

---

## 🧹 DEVICE STATE (end of 06-30)
- **Droid 1** (`8624SBCEDF00001789`, field) and **Droid 2** (`24039703201775`, dev) both on `d719fbc95`. **Devices are NOT mirrored** — same APK, each resyncs its own files independently.
- Droid 1 my_tracks: the 06-29 cleaned hash-file set (73) + today's import-test adds.
- In Droid 1 `/sdcard/Download/`: **"mike ride June 5.gpx"** (TRUNCATED — no `</trk>`; KEEP for import-tolerance testing) + a **well-formed 16-track GPX** (imported as all-dupes).
- **Test fixture (do NOT sweep):** orphan spatial row `82a5cb01` ("St George to Bar10", hash `9969bdf6`, no file, nothing attached) — kept to test `deleteTrackFromDb` once the detail panel is wired to it.

---

## 📌 ALSO PENDING (not blocking the detail-panel work)
- Release notes: **mandatory track-resync** tester note is QUEUED for the NEXT build's notes (TBA until the AAB ships) — re-pull Fred's exact wording from the 06-29 conversation; do NOT invent it. Current notes/manual stay 06-23 (nothing field-verified to announce yet).
- New manual/release sections drafted 06-30 (import live feed, 4-way recap, alias display) carry **`[SCREENSHOT NEEDED]`** markers — Fred captures the screens when ready.
- Multi-track splitter (`splitMultiTrackFile`, separate from importGpxAllArtifacts) still `$baseName.gpx`, file-only — fold into the resolver during the import deep-dive.

---

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease 2>&1 | grep -E "^e:|BUILD|FAILED"` (warm ~10-14min; cold ~31-50min). APK: `app/build/outputs/apk/google/release/app-google-release.apk`. AAB: `bundleGoogleRelease`.
- Install: `adb -s <serial> install -r -d <apk>` → `MSYS_NO_PATHCONV=1 adb -s <serial> shell am force-stop com.grouptrack.android` → **cold-launch from icon**.
- Droid 1 `8624SBCEDF00001789` (field/real-GPS) · Droid 2 `24039703201775` (dev). `MSYS_NO_PATHCONV=1` on REMOTE adb paths.
- **Logcat:** clear `logcat -c` → action → `logcat -d | grep <TAG>` (not `-t N` — stale tail). Redirect to a file needs a **Bash path** `> /c/Users/kixaz/Downloads/x.txt`; adb **pull** dest needs the **Windows path** `C:\Users\kixaz\Downloads\x` with `MSYS_NO_PATHCONV=1`. Tags: `ImportDiag`, `TrackAdd`, `TrackDelete`, `TrackSync`, `TrackProps`, `AliasSwap`/`AliasRename`.
- No sqlite3 on device — pull DB, query via `python3 -c "import sqlite3 …"`. DBs: `/sdcard/Documents/GroupTrack/grouptrack_spatial.db` (~117MB) + `grouptrack_data.db`. my_tracks: `/sdcard/Documents/my_tracks/`.
- Patches: standalone dated python → Downloads → `python3 <name>.py`; **unique versioned filenames**; count==1 anchor guard, runtime newline detect, all-or-nothing; **raw-string anchors** (`r'''...'''`); include blank lines inside multi-line anchors exactly (`cat -A` to reveal). Commit only named files, never `git add .`. LINE ENDINGS: SpatialDbManager/ConvoyViewModel/ConvoyTrackImportScreen/ConvoyTrackOps = LF; ConvoyMapViewerScreen/ConvoySettingsScreen/HTMLs/ConvoyGpsService/MainActivity = CRLF.
