# GroupTrack V2.5/V2.6 — Living Checklist / Open Items
**Updated:** 2026-07-02
**Branch:** feature/convoy-event-ride · V2.5 LIVE on Play Store at `c603bc3f0`
**Committed HEAD (07-02):** `ccc559015` — plus the maps-follow-tracks feature (final checkbox committed 07-02; see below). Clean baseline `d719fbc95` retains all spatial/sync/save/import work.

---

## 🟩 START HERE — NEXT SESSION (re-triaged 07-02; supersedes the 06-30 START HERE below)

**⛔ STANDING PRINCIPLES (unchanged):** NO SILENT PROCESSES · INSTRUMENT BEFORE THEORIZING · re-triage the checklist against GIT before working items (several 06-30 "START HERE" items were addressed out of order during the 07-01/02 recovery — verify vs git before actioning the older block).

**⛔ NEW LESSON (07-01/02) — BLAST RADIUS ≠ DIFF SIZE.** A one-line change to *when the whole map refreshes* (the `lastMapProcessed` gate) caused days of "app unusable" symptoms. Load-bearing shared paths (refresh/identity/state/tick) need blast-radius analysis before editing, regardless of diff size. Revert-and-isolate beats stack-and-hope: one change, verified on-device, committed, before the next.

**⛔ BUILD BUDGET IS REAL.** Builds are 12–40 min; only so many per day. Design features as **create-process + execute in ≤2 builds**, batch related changes so one build tests the whole thing.

### 📋 NEXT-SESSION PLAN (07-02)
1. **JS ARTIFACT-POPUP SUPPRESSION DURING ROUTE-BUILD (Route BUG A).** Resurfaced by the 07-01 gate revert (expected). In create-route mode, tapping an artifact fires its popup → the popup refresh re-renders Leaflet → overlays the route being drawn. FIX: narrow `__routeMode` guard on the trail/track LOAD path in `app/src/main/assets/convoy_map.html` + `grouptrack_map.html` (gate artifacts loaded AFTER route-mode entry too). Suppressing the popup is the whole fix. HIGHER RISK (map HTML) — the parked "scary" item; do Kotlin wins first if stacking.
2. **REVISE THE TRACK POPUP** — surface the extension fields (distance/duration/speed/elev/points) on the on-map track popup, like the detail panel now does (07-02). Map HTML/JS; paired edit BOTH HTMLs.
3. Then: manual + release-notes fold-in (edit dated working copies in place), AAB cut. NOT before the two JS items above.

### ✅ DONE 07-01/02 (committed)
- **`acdaf7b85`** — RESTORED AUTOMATIC MAP REFRESH. Root cause of the "app unusable / tracks+artifacts don't display" crisis: commit `e0182045a` ("V2.5 map-independence: per-map MapStateStore + `lastMapProcessed` gate") converted map refresh AUTOMATIC→MANUAL; the gate suppressed reseed until a map-switch. Tracks/artifacts-not-displaying were BLAST RADIUS of the gate, not real bugs. Gate at `ConvoyScreen.kt:795` `if (lastMapProcessed != "convoy")` → `run {`. TESTED GREEN. **Correction banked:** per-map JSON persistence is rock-solid — independence comes from PERSISTENCE, not the gate; do NOT re-add a gate to protect it. The "lost tracks on save" problem was ACCIDENTAL DISMISSAL (tap-outside closed the save window), fixed by the CONFIRM PROMPT — not the gate.
- **`c74d5f8b1`** — MY-CART DEVICE-DEFAULT / RADIO-OVERRIDE identity; HOTEL-10 sim-ghost removed. `ConvoyViewModel`: default `"!phone"` (291); observer sets `_myCartId` on node info (radio `!%08x` / else `!phone`) guarded `if (!_trackActive.value)`. NO-RADIO path TESTED (HUD shows real cart pre-record). ⏳ RADIO-OVERRIDE path pending radio (next week).
- **`ccc559015`** — TRACK DETAIL PANEL: 2-column formatted metrics grid (friendly labels + units for distance/duration/speeds/elev/points/recorded). Data already read by `getArtifactDetail` (merges `track_properties`); presentation-only.
- **MAPS FOLLOW THE TRACKS (committed 07-02)** — per-track padded-bbox map-tile download. See the feature block below.

### ✅ MAPS-FOLLOW-TRACKS — COMPLETED 07-02
**What it does:** downloads the map tiles covering a track's area, so maps travel with the tracks. Two access points, one shared engine.
**Engine** — `SpatialDbManager.downloadMapsForTrackHash(context, hashFileName): Int` (~1883). Reads the track's bbox (min/max lat/lon) from the `tracks` row by **geom_hash** (the cross-device/server-STABLE key — right for future AWS reuse; track_id is device-local, names ambiguous). Pads **½ mile** (lat 0.00724° constant; lon 0.00724/cos(midLat), pole-guarded). Submits via **`DownloadQueueManager.enqueue(context, n, s, e, w, "DL $name")`** — NOT `enqueueArea`. Returns `entry.totalTiles`.
- **KEY BUG FIXED:** first cut used `enqueueArea`, whose `gridCells` split returns EMPTY for a sub-cell-sized per-track box → `totalTiles=0` job → downloaded=0, instant empty completion (confirmed on-device: 18.2mi track → "1 cells, downloaded=0"). Bbox + pad math verified CORRECT (3.0×5.9mi box, ~21,660 tiles). `enqueue` computes every tile via `calculateTiles` — no grid-cell minimum. After fix: 21,660 tiles queued, count draining, tiles landing on map. **Lesson: `enqueue` = single region of any size; `enqueueArea` = large multi-cell regions only.**
**Access point 1 — SAVE MAPS button** (detail panel): `ArtifactDetailPanel` green "SAVE MAPS" button, gated to Tracks, reads `detailFields["geom_hash"]` → `onDownloadMaps(hash)`; parents (ConvoyScreen + ConvoyMapViewerScreen) run the engine on a bg Thread + post a LENGTH_LONG Toast "Queued N map areas". TESTED WORKING.
**Access point 2 — import (opt-in)**: `ConvoyTrackOps.importGpxAllArtifacts(sourceFile, context, downloadMaps: Boolean=false, onProgress)`. Per-track INSERT branch computes `computeGeomHash(wkt)` → engine, gated `if (downloadMaps)`. Reports via onProgress ("MAPS: name → N cells queued"). Fires on INSERT only (not dupes/aliases). **Per-import CHECKBOX** in the Planning-Map import-list dialog ("Download maps for imported tracks (may be large)"), **default UNCHECKED** (opt-in — a big import = ~21k tiles × N tracks; don't auto-pull GB). `importDownloadMaps` state threaded through `runImport`. (Import UI is Planning-Map only — `ConvoyTrackImportScreen` is DEAD code, nothing navigates to it.)
**Files (all committed 07-02):** SpatialDbManager, ArtifactDetailPanel, ConvoyScreen, ConvoyMapViewerScreen, ConvoyTrackOps.
**⚠️ Checkbox review pending (Fred, this evening):** if the checkbox path shows any issue on review, log it as a NEW OPEN ISSUE.
**Volume follow-up (open, non-blocking):** ~21k tiles/track = deep zoom range. On-demand button is user-chosen (fine); import is checkbox-gated (opt-in). Possible later lever: cap the zoom range for per-track downloads.
**3.0 tie-in (documented):** AWS server track import = **stage-to-directory + reuse the local import path** — so dedup/alias/metrics AND maps-follow-tracks all apply to AWS imports automatically (write-once-reuse). Added to 3.0 scope.

### 🔻 06-30 START HERE + open items follow (RE-TRIAGE vs git before actioning — some may be done/superseded by the 07-01/02 work)

# GroupTrack V2.5/V2.6 — Living Checklist / Open Items
**Updated:** 2026-06-30
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `d719fbc95` (06-30 marker — V2.6 **track services layer** banked, builds clean, import **field-verified on-device**) · V2.5 LIVE on Play Store at `c603bc3f0`

---

## 🟦 START HERE — NEXT SESSION

**⛔ STANDING PRINCIPLE — NO SILENT PROCESSES.** Any process that does real work (sync, import, delete, upload, recording) MUST show it's running, stream progress, and report its result on a surface the user can't miss (a screen/dialog, not a drownable toast). Silence both HIDES and CAUSES failures.

**⛔ CARDINAL LESSON (reaffirmed 06-30) — INSTRUMENT BEFORE THEORIZING.** Import returned 0/0/0/0 and ~2 hours went to theorizing (splitter logic, file format) before adding step logging. The moment we logged `regexMatches` / `contains<trk>` / file length, the cause was obvious in one run. Every step must log — and **DEPLOY** the instrumentation. Perfecting code you can't observe is the trap. (Also: don't multiply failure points beyond the target — the import bug was a premature OOM "optimization" that broke a working parser.)

### ⛔⛔ FIRST TASK — WIRE THE TRACK DETAIL PANEL TO THE NEW SERVICES ⛔⛔
The track services layer is BUILT and committed (`d719fbc95`); the **detail-panel UI still calls the legacy file-only ops.** Repoint them and retire the legacy generators:
- Repoint `ConvoyMapViewerScreen:~1681/1700` (rename/delete) and `ConvoyTrackExportSheet:~256/275` to the DB services (`deleteTrackFromDb`, `renameTrackInDb`).
- **RETIRE** legacy `ConvoyTrackOps.deleteTrack(File)` (~line 63, `file.delete()` only — the ORPHAN GENERATOR) and `renameTrack(File)` (~line 50, renames the file off its hash — wrong under hash-naming).
- Build the **alias-display UI**: a "has aliases" indicator on list/result rows + a tap-to-view popup (reuse `getAliasesFor` + the detail-panel accordion).
- Build the search **"matched via alias" highlight** (2nd-pass display — `matched_via_alias`/`matched_alias` are already carried by `searchByName`).
- `ArtifactDetailPanel` is a reusable callable composable (self-loads via `onLoadDetail`/`onLoadAliases`; hosted by ConvoyMapViewerScreen + ConvoyScreen). Only its injected callbacks differ per host — that's where the file-vs-DB mismatch lives.
- **Use the test fixture:** deleting the orphan row `82a5cb01` ("St George to Bar10", no file) through the wired panel should remove the row cleanly (no collateral) — the clean validation of `deleteTrackFromDb`.

### 🛑 SECOND TASK — ANDROID-GPS TRACK RECORDING (CREATE via RADIO is CERTIFIED ✅ 06-30)
**06-30 field result: recording a new track via RADIO GPS is PERFECT** (record → save → display → `<hash>.gpx` + DB row + properties, verified). CREATE is certified for the radio-GPS source. **The bug is ISOLATED to ANDROID-only GPS mode** — Android tracks fail to record. Key: the Android fix DOES work — it's what sets the Convoy map location when the map opens. So the Android location is available; it is NOT reaching the track recorder.

**Recorder = `ConvoyGpsService.kt`. Source selection + point sink (from the xref docs):**
- `var useRadioGps: Boolean` (~238) — source flag.
- `onRadioPosition(lat,lon,alt)` (~245) — RADIO feeds points here (WORKS).
- `startLocationUpdates()` (~251-277) — the ANDROID `LocationManager` subscription — **PRIME SUSPECT.**
- `onGpsUpdate(lat,lon,alt)` (~288) — common sink both sources funnel into → `writeGpxPoint` (~393) writes the `<trkpt>`.
- Related: `startTrack()` (~145), `stopTrack()` (~194), `finalizeTrack()` (~214), `openGpxWriter`/`closeGpx` (~379/403).

**LEADING HYPOTHESIS:** in Android mode, `startLocationUpdates()` subscribes to `LocationManager` with a provider / min-time / min-distance that delays or suppresses fixes, so no point reaches `onGpsUpdate`→`writeGpxPoint` before the track is stopped (→ empty/0-point / truncated track). The MAP-centering path gets an Android fix fine (last-known / fused / instant) — so COMPARE how the map obtains location vs how the recorder subscribes. Verify: (a) `startTrack()` actually calls `startLocationUpdates()` when `useRadioGps==false`; (b) the service holds the same location permission the map path uses; (c) the requested provider actually emits (GPS_PROVIDER cold-fix latency vs fused/last-known); (d) the min-distance/min-time filter isn't eating the points.

**FIRST COMMANDS next session:** `sed -n '145,160p' …/ConvoyGpsService.kt` (startTrack — source choice) then `sed -n '238,320p' …/ConvoyGpsService.kt` (useRadioGps, onRadioPosition, startLocationUpdates, onGpsUpdate). **INSTRUMENT `startLocationUpdates` + `onGpsUpdate` (log every provider callback + point) BEFORE theorizing.** Ties to recorder-truncation: record-stop must finalize the file (`</trkseg></trk></gpx>`) — NO SILENT PROCESSES (a failed/empty record must say so, not leave a broken file).

### 📋 NEXT-SESSION PLAN
1. **WIRE THE TRACK DETAIL PANEL** (above) — repoint file-callers to DB services, retire the legacy delete/rename, build alias-display + search highlight. Validate delete on the `82a5cb01` fixture.
2. **RECORDING SHOW-STOPPER** (above) — record-start warm-up + record-stop file finalization.
3. **FIELD-VERIFY THE METRIC FEED** (TrackPropertiesUpdater) via the cold-launch recipe — 06-29 was inconclusive (ran old code from recents). Confirm `=== properties: N tracks written ===` + real distance/duration/speed; watch elevation m/ft.
4. **FIELD-CERT CREATE** on Droid 2 (record→save→displays→`<hash>.gpx`→DB row + properties + alias if name-dupe). Depends on the recording fix.
5. **FIELD-CERT IMPORT end-to-end** — not just dupe detection (done 06-30); confirm a NEW-geometry track INSERTs (new `<hash>.gpx` lands) and a same-hash/new-name track ALIASes.
6. **ROUTE — DISABLE ARTIFACT SELECTION IN CREATE-ROUTE MODE (BUG A).** In create-route mode, tapping an artifact fires its popup → **the popup refreshes/re-renders Leaflet → that refresh OVERLAYS the route being drawn.** One causal chain: kill the popup, the refresh and overlay go with it. FIX: gate artifact selection (the pointer-events gate) on the trail/track **LOAD path** whenever `__routeMode` is true, so artifacts loaded AFTER route-mode entry are gated too (the entry-time gate alone misses them). Suppressing the popup is the whole fix — no separate overlay work. Gate the next AAB on all the above + this.

### ✅✅ 06-30 RESULTS — THE TRACK SERVICES LAYER (committed `d719fbc95`, builds clean, import VERIFIED on-device)
The shared machinery all three capture processes now run on:
- **Unified add resolver** `resolveTrackAdd(name, sourceFile): AddOutcome` — the ONE shared add path; SYNC, CREATE, IMPORT route through it identically. Rereads the file from disk (single source of truth — no content-passing). Resolves to **INSERT** (new hash → materialize `<hash>.gpx` + metrics, keep file) / **DROP_NAME** (hash + name == official `tracks.name` → delete source) / **DROP_ALIAS** (hash + name == an existing alias → delete source, no new alias row — Fred's catch) / **ALIAS** (hash + new name → addAlias(existingId,name) + delete source). `enum AddOutcome { INSERT, DROP_NAME, DROP_ALIAS, ALIAS, NO_GEOMETRY, ERROR }`.
- **`insertTrackToDb`** now returns `TrackInsertResult(wasNew, trackId, geomHash, name)` (was Boolean) — race-free (result on the caller's stack); on a dupe returns the existing track_id (no second lookup needed).
- **Complete delete service** `deleteTrackFromDb(id)` — removes spatial row + track_properties + ALL aliases + the `<hash>.gpx` file, ordered/logged/best-effort (was spatial-row-only — the orphan generator). The detail-panel whole-track delete, SEPARATE from the add-time source cleanup inside the resolver.
- **Alias maintenance** — `setPreferredAlias` rewritten flag-flip → **SWAP** (promote an alias to the official `tracks.name`, demote the old name into the alias row; trail-guarded; generic via `spatialTableFor`; **LOCAL-ONLY** — AWS keeps the first-identified canonical name). New `renameAlias(aliasId, newText)`. `is_preferred` is informational list-ordering only.
- **Alias-join search** — `searchByName` (generic, all artifact types) matches the official `name` OR any alias. Two-query Kotlin MERGE (spatial + extension are SEPARATE SQLite files → no SQL JOIN). Distinct per artifact_id; name-hit wins over alias-hit; always shows the official name. Carries `matched_via_alias`/`matched_alias` for the display pass.
- **Import rework + fix** — `importGpxAllArtifacts` routes each split track through the resolver (no hash computed in import — resolver owns it). 4-way counters (inserted/aliased/duplicate/skipped); per-record `onProgress` feed (INSERT/ALIAS/DUPLICATE/SKIP/ERROR); `ImportArtifactsSummary` gained `aliased`+`skipped`; the import SCREEN got a LIVE per-record feed + a 4-way recap dialog (mirrors the sync control screen). Step instrumentation kept IN (logcat `ImportDiag` + feed): ENTRY / READ (with `regexMatches`) / TRACK.

**THE IMPORT BUG, ROOT-CAUSED (instrument-driven, ~2hr):** the multi-track splitter had been changed (06-29 "import base fix") from a **regex** to a line-by-line `bufferedReader` **streamer** for OOM protection — the streamer found 0 `<trk>` blocks on valid files. Reverted to the PROVEN regex `Regex("""<trk>([\s\S]*?)</trk>""").findAll(text)` (RAW triple-quoted — a non-raw `"<trk>([\\s\\S]*?)</trk>"` mis-escaped and matched 0). Patches: `patch_import_regex_parse_v3`, `patch_import_regex_fix_v1`, `patch_import_instrument_v2`.

**THE TEST FILE WAS A RED HERRING.** "mike ride June 5.gpx" (1893 bytes) is **TRUNCATED — has `<trk>` but NO `</trk>`** (recorder stopped mid-track, no closing tags; `grep -c '</trk>'` = 0, ends on a bare `<trkpt>`). Import correctly rejects it (regex needs the pair); SYNC parses it fine because sync uses `parseGpxTrackPoints` (scrapes `<trkpt>` points, tolerant of missing close tags) — exactly why sync-works-but-import-fails on the SAME file. **Import IS WORKING** — proven by a separate well-formed 16-track GPX that imported and correctly reported all 16 as duplicates.

### 🆕 NEW OPEN TASKS surfaced 06-30
- **Recorder truncation** — produces GPX with no closing tags on interrupt; ties to the recording show-stopper. Fix at source (record-stop must finalize the file).
- **Import tolerance (optional)** — salvage truncated files like sync does (parse points instead of requiring the `<trk>...</trk>` pair), OR surface "malformed: no </trk>" (the instrumentation now reports it — no longer silent).
- **`import_tracks` staging dir (Fred's design) — ON HOLD / PARKED.** (Idea: import writes split files to a SEPARATE `import_tracks/` inbox; a sync-variant drains it → `my_tracks/<hash>.gpx`, then empties it — process only new arrivals O(new), not re-walk the whole library O(library); inbox vs reconciled separation; failed files stay visible.) Current import writes to my_tracks via the resolver and WORKS — staging is a refinement, NOT scheduled. Parked unless revisited.

### 🟢 BUILD STRATEGY NOTE (Fred, 06-30) — "artifact builds, not process builds"
Complete ONE artifact type fully (tracks) across ALL its processes + maintenance, test as a unit, THEN routes, THEN waypoints. The three capture processes within a type must change TOGETHER (they share `insertTrackToDb` / `resolveTrackAdd`) — wiring them "one at a time" means a broken build. Aliases/search are shared/common, handled within each type. INVARIANT: the source file always exists on disk before `resolveTrackAdd` (sync walks files; create finalizes first; import is batch — writes all files then adds).

### 🧹 DEVICE STATE (end of 06-30)
- **Droid 1** (`8624SBCEDF00001789`, field) + **Droid 2** (`24039703201775`, dev) both on `d719fbc95`. **Devices NOT mirrored** — same APK, each resyncs its own files independently.
- Droid 1 my_tracks: the 06-29 cleaned hash-file set (73) + today's import-test adds.
- Droid 1 `/sdcard/Download/`: **"mike ride June 5.gpx"** (TRUNCATED — no `</trk>`; KEEP for import-tolerance testing) + a **well-formed 16-track GPX** (imported as all-dupes).
- **TEST FIXTURE — DO NOT SWEEP:** orphan spatial row `82a5cb01-23d9-4df3-9832-1fcb4da8b0f8` ("St George to Bar10", hash `9969bdf6…`, real geometry, NO file, nothing attached) — kept to validate `deleteTrackFromDb` once the detail panel is wired.

### 🔴 CARRIED — 06-29 METRIC-FEED TEST WAS INCONCLUSIVE (re-verify)
- 06-29 RESYNC ran on OLD code (app resumed from recents, not a cold icon launch); `track_properties` stayed rows:0; no `=== properties: N tracks written ===` line. Nothing disproven — UNTESTED.
- **Verification recipe:** confirm my_tracks holds the files; pull before-DB; install rebuilt APK → force-stop → **COLD-LAUNCH FROM ICON, not recents**; tap RESYNC ONCE; `tail -40 track_sync.log` must show ONE `=== sync start ===`, real lines, AND `=== properties: N tracks written ===`; pull after-DB → `track_properties` populated with real metrics. Gut-check distance/speed vs a known ride; watch elevation_gain for the m/ft assumption (`<ele>` assumed METERS ×3.281→ft; if 3× high, one-line fix).

---

## ⭐ TRACK MODEL — CREATE / IMPORT / SYNC + V3.0 SHARING (designed 06-28; ACTIVE — these are our rules)

> Root cause (06-25, verified in code 06-28): **record-save never writes a spatial DB record.** `ConvoyViewModel.finalizeTrack` (484) only calls `gpsService?.finalizeTrack` → renames the temp file to `{name}_{timestamp}.gpx` (`ConvoyGpsService.finalizeTrack:214-219`, the date/time append is line 219) and stops. No `insertTrackToDb`. `TrackManager` is a stub. **Import is the ONLY caller of `insertTrackToDb`** (`ConvoyTrackOps.kt:569`). Sync (`syncTracksFromFiles:479`) runs its OWN inline insert keyed on `file.nameWithoutExtension` with a `SELECT name` skip-gate → no-ops. Confirmed NOT a DB constraint: re-importing the same recorded GPX succeeds (differential test).

### Governing principles
1. **Hash is the identity.** A track's identity is its `geom_hash` (SHA-256 of geometry WKT), not its name or filename. Names are free human labels; the hash is the key. `insertTrackToDb` already computes geom_hash (`SpatialDbManager.kt:793`) and the `tracks` table enforces `UNIQUE(geom_hash)`.
2. **The DB controls every write, in all cases.** Create, import, sync all just **identify and present** a record to the DB; the DB enforces dedup/uniqueness/aliasing/rejection via its own schema/triggers. Code never decides "should this go in?" — it presents, the DB decides. This makes every path idempotent.
3. **Code owns:** identifying entities, computing hash/wkt/bbox/name, presenting records to the DB, and — tracks only — naming the file `<hash>.gpx`. **The DB owns:** all write enforcement.
4. **Tracks are the only file-backed entity.** Tracks = spatial DB record + a my_tracks file. Waypoints + routes = spatial DB records only (no file). So `<hash>.gpx` naming / rename-to-hash applies to TRACKS ONLY.

### The shared track-create core
_identify → present record to DB (DB enforces) → write file as `<hash>.gpx` with the human name inside `<trk><name>`._ Called by all three processes; they differ only in the front half.

- **CREATE (record-save):** name source = **keyed** (user types it). points (from temp file) → wkt → geom_hash → bbox; human name → `<trk><name>`; DB record FIRST, then file `<hash>.gpx`. FIX: add the missing `insertTrackToDb` call + name the file by hash + write the typed name into `<trk><name>` (recording currently leaves the default "Convoy Track") + drop the timestamp append.
- **IMPORT:** name source = **derived** (read from source `<trk><name>`). One GPX → many entities; import **identifies and recreates each**: each `<trk>` → track-create (record + `<hash>.gpx`); each `<wpt>` → waypoint record only (`insertWaypoint`, no file); each `<rte>` → route record only (`insertRoute`/`tracks` type=ROUTE, no file). Entry: `importGpxAllArtifacts`. DB record FIRST; tracks also get the file. (This is the only path that works today.)
- **SYNC (tracks-only, file-driven reconcile):** invariant on exit = **every track file in my_tracks has a spatial DB record AND is hash-named.** Per file: read → geom_hash (+ read `<trk><name>`); if no record for that hash → present it to the DB; if filename ≠ `<hash>.gpx` → **rename to hash**. Sync is tracks-only because only tracks are file-backed. FIX: replace its inline name-keyed insert with the DB-controlled path + add the rename-to-hash. **Bonus:** because the DB controls writes and sync presents every file, **sync becomes the rebuild-from-files recovery path** — a wiped spatial DB is fully reconstructable by syncing the files (what the 06-24 tester's manual reimport was doing).

### Filename ↔ name (tracks)
Once a file is `<hash>.gpx`, the only on-disk place the human name lives is inside the file (`<trk><name>`). Create writes the keyed name there; import preserves the derived name; sync ensures `<trk><name>` is populated before/at rename. **Legacy filename-only-name files:** do NOT rescue — treat `<trk><name>` as authoritative going forward; sync renames freely (pre-AAB, ~18 testers, clean reimport recovery). *(leaning (c); CONFIRM)*

### Name collision / uniqueness
**Name uniqueness is NOT a code rule.** Dupes of name are allowed (hash is the key). The ONLY hard uniqueness is the **filename** on disk — and `<hash>.gpx` makes filename collisions impossible by construction (unique hash = unique filename). So no name-increment/timestamp/alias-DB-lookup is needed locally. The DB dedups true geometry-dupes via `UNIQUE(geom_hash)`.

### V3.0 SERVER SHARING MODEL — two layers (these are the rules)
- **LOCAL:** a user MAY call a track by their **own preferred name** — private view, flexible, does NOT override the universal.
- **UNIVERSAL (server), decided by FIRST-IN:**
  - **Canonical track name = FIRST-IN for the hash.** The first source ever to submit that geometry sets the universal track name. Everyone else's recording of the same geometry resolves to that one track.
  - **Alias = FIRST-IN for `(hash, date)` → one human-readable name.** For each distinct date, the first source to submit that hash on that date claims the alias slot.
  - **One alias per hash per date.** Same hash + same date, later submission → omitted (slot claimed). Same hash + different date → eligible to claim that date's slot.
  - **Everything else is OMITTED** — a later submission of an already-claimed hash (canonical set) or `(hash, date)` (alias set) is dropped, no duplicate.
  - **The date that keys an alias** = track/ride **creation** date (NOT upload/processed date), so the same ride re-uploaded later doesn't claim a new slot. *(leaning; CONFIRM)*
- **Group-ride collapse (the purpose):** N riders record one ride; each phone saves its own track. If geometries hash-match → ONE server track (first-in name wins) + aliases (each other rider's name, one-per-date). **The date qualifier is the CORRECT job of the timestamp that was previously appended to filenames** — its real purpose was always alias-dedup scoping (same source, same day, same hash = one), not filename uniqueness. Hash does uniqueness; date does alias-dedup.
- **Enforcement:** consistent with principle #2 — code just PRESENTS a track/alias; the DB decides first-in vs omit. No first-in logic in app code.

### V3.0 OPEN DESIGN PROBLEM — same-ride cross-device matching (the one unsolved piece)
`geom_hash` is EXACT (SHA-256 of WKT). Two different phones recording one ride will NOT produce identical geometry (GPS jitter) → they will NOT hash-match. So:
- **Exact hash** correctly dedups the SAME file re-processed (re-import/re-sync/same device) — this is what the LOCAL V2.6 fix needs and delivers.
- **Cross-device "same ride" collapse** needs **fuzzy/spatial matching** (bbox overlap + Hausdorff/Fréchet distance, or a rounded/simplified-geometry hash). This is a **V3.0 server design block**, separate from the local fix. **Lead-cart snap-2 may help** — snapped tracks converge toward identical geometry, making same-ride recordings more hash-matchable. Revisit once snap-2 lands.

### Scope split
- **V2.6 (local, buildable now):** the three processes with hash-named track files, DB-first writes, DB-controlled enforcement. Solves: recorded tracks not appearing, sync no-op, filename collisions, timestamp-in-name. Exact-hash dedup.
- **RULE — GroupTrack writes GPX only.** KML is ELIMINATED as a write/export option from the recorder; new track save is always `.gpx` (drop the `TRACK_EXPORT_FORMAT` KML branch in `finalizeTrack`). GroupTrack writes GPX, reads GPX — one internal format. Record-save and sync are GPX-only.
- **V2.6 — KML→GPX PREPROCESS (part of IMPORT only):** KML is an inbound foreign format handled inside the **import** process. When import ingests a foreign source that is KML, it converts KML → GPX (coords → `<trkpt>`, name → `<trk><name>`) as a preprocess step, then decomposes the resulting GPX (tracks/waypoints/routes) as normal. NOT a standalone Download-scanning process — it lives in import, the one entry point for foreign formats. Record-save and sync never see KML.
- **THREE INDEPENDENT TRACK-CAPTURE PROCESSES (keep separate — redundancy is the point):** (1) **add a new track** (record-save) does its OWN inline insert + hash + rename; (2) **sync all existing tracks** walks my_tracks and reconciles; (3) **import a track from a foreign source** decomposes a GPX. None calls another. They share only the `insertTrackToDb` primitive, `updateTrackPropertiesForHash` (metric feed, 06-29), and — 06-30 — the shared dupe/alias resolver below. If one has an issue, the others still capture tracks — nothing is lost.
- **⭐ SHARED DUPE/ALIAS RESOLVER (06-30 build — the one identical decision for all three paths):** the alias path works for TRAILS but was NEVER wired for tracks (verified 06-29: no trigger; 0 track aliases; sync's dupe branch at `SpatialDbManager.kt:592` does rename-only). Build ONE resolver, mirror the trail ALIAS path (`resolveByGeom`/`decideAddAction` → ALIAS, alias writer ~1188), and route sync + import + create through it:
  - new hash → **INSERT** (insert track, file `<hash>.gpx`).
  - hash exists, **same name** → **DROP** (delete the redundant source file).
  - hash exists, **different name** → **ALIAS** (write alias → existing track_id, then **DELETE** the source — do NOT rename). The dupe's value is its human name → an alias.
  This is principle #2 in action: code presents, the DB (via UNIQUE(geom_hash) + the alias writer) decides. It also makes dupe cleanup automatic (no more lingering human-named originals).
- **V3.0 (server, design block):** the sharing model above + the same-ride fuzzy-matching problem.

### [CONFIRM] before/at execution
1. Alias date = creation date (leaning) vs upload date.
2. Legacy filename-only names = don't rescue (leaning (c)).
3. Same-ride cross-device matching = fuzzy method TBD (V3.0).
4. Recovery available NOW for any stuck recorded track: move the GPX to Downloads → re-import.

### Verified anchors (06-28)
- `ConvoyViewModel.finalizeTrack` ConvoyViewModel.kt:484 (only renames, no insert).
- `ConvoyGpsService.finalizeTrack` ConvoyGpsService.kt:214-232 (rename + timestamp append at 219).
- `insertTrackToDb` SpatialDbManager.kt:787 (the working insert; geom_hash at 793).
- import track loop ConvoyTrackOps.kt:519-569 (name from `<trk><name>` at 541; `insertTrackToDb` at 569).
- `importGpxAllArtifacts` ConvoyTrackOps.kt:481; `parseGpxWaypoints` 391; `parseGpxRoutes` 443.
- `syncTracksFromFiles` SpatialDbManager.kt:479 (name = `file.nameWithoutExtension` at 499; own inline insert, no `insertTrackToDb`).
- `TrackManager` = stub (TrackManager.kt:10).
- Still TO PULL before patching: full `insertTrackToDb` body (787-825), recorder GPX-write/header (ConvoyGpsService 1-120 — does it write `<trk><name>`?), `tracks` columns + geom_hash index.

---



> Both fixes are in `d31202fcb` and live in the shipped AAB. Each STILL fails on device; each has a specific diagnostic-first next step. Documented as tester-facing known-issues for V2.5. Fix properly in V2.6.

**BUG A — route-build popup overdraw (now NON-DESTRUCTIVE, still appears).** Tapping near a trail line during route build hits the Leaflet layer (map init `renderer:L.svg({tolerance:18}), tapTolerance:20` at `convoy_map.html:288` = ~18-20px catch radius, so nearly every along-trail tap lands on a layer). Leaflet opens the layer popup AND redraws the trail layer ON TOP of the route build line — the route looked "blanked"/taken over (it was OVERDRAWN, never deleted). `onMapTap` STILL fires on layer taps (verified on device — real tap coords logged), so points DO place.
- **FIX DEPLOYED:** `setRouteMode(on)` toggles each artifact layer's SVG `._path.style.pointerEvents = on?'none':''` for trailLayer/trackLayer/waypointLayer/routeLayer (`convoy_map.html` ~331). Re-enable verified airtight: all 4 route-exit paths call `setRouteMode(false)` (`ConvoyMapViewerScreen` 948 save / 1037 onExit / 1068 + 1105 discard variants). Result: stopped the destructive overdraw (popup no longer covers the route) but the popup still SHOWS.
- **NEXT (theory):** the gate runs ONCE at `setRouteMode(true)` entry; trails loaded/reloaded AFTER entry (pan re-runs `loadTrails` interactive, or trails weren't loaded at entry so `eachLayer` gated nothing) escape it. FIX DIRECTION: also re-apply the pointer-events gate on the trail/track LOAD path whenever `__routeMode` is true — not only on route-mode entry.

**BUG B — resumed in-progress route draws ANGULAR (chords); only a new add/undo snaps the whole line (pan does NOT).** Vertices ARE loaded (undo works on prior-saved points, proving `building` true and the data intact).
- **APPROACH (supersedes the earlier bbox-in-draft plan):** fetch the snap-referenced trail/track geometry BY the lineIds the vertices already carry (viewport-independent) instead of by viewport. Written geometry is final; only NEW points need snap; reload shouldn't re-fetch trails by viewport (needs map on the route → chords).
- **FIX DEPLOYED:** `SpatialDbManager.queryGeomByIds(ids, type)` (~1237) — `SELECT idcol,geometry FROM (trails|tracks) WHERE idcol IN (...)`; trail→trails/trail_id else tracks/track_id (routes are tracks type='ROUTE'). SQL VERIFIED CORRECT via pulled spatial.db (route's `trail_id c45c5f06-…` exists with valid LINESTRING; the exact `IN(...)` query returns it). Resume block (`ConvoyMapViewerScreen` ~1132-1147) rewired to build `byId` via queryGeomByIds. Plus a 400ms second `drawBuildLine(rsPts)` via `postDelayed` (~1148) to mimic an edit redraw after the map settles.
- **NEXT (theory):** byId is likely EMPTY at resume despite correct SQL → `buildSegments` chords; the 400ms redraw redraws the same chords. The live add path (`445`) works because it uses the VIEWPORT byId and by then the map is ON the route. **RUN `patch_resume_diag_log` FIRST** (logs `verts/trailIds/trackIds/byId` sizes, tag `RouteResumeDiag`; written, NOT yet run) → if byId=0 with non-empty trailIds, the Kotlin runtime call returns empty though standalone SQL works (init/context/db-handle/ordering); if verts=0, vertices not loaded when the block runs. FOUR `buildSegments→drawBuildLine` sites: 445 (live add, viewport — WORKS), ~1024, ~1093, ~1138 (resume by-ID — chords).

**EXPERIMENTAL (not committed):** `patch_resume_diag_log` (diag, not run). `patch_route_popup_suppress` is SUPERSEDED by the pointer-events gate — do NOT use it.

---

## 🟢 OPEN — REMOVE ROLLBACK ENTIRELY (Undo covers it) — decided 06-24, REAFFIRMED 06-30, NOT yet done

**Fred 06-30: Undo is fine — ROLLBACK is the issue.** Undo stays untouched; only the rollback function/control is removed.

Rollback (reload-unchanged-draft / restore-to-pre-session) is IMPOSSIBLE BY CONSTRUCTION: the per-point auto-checkpoint (`2b1ac101c`) rewrites the draft after EVERY point, so there is no preserved prior version to roll back to. It is also redundant — UNDO steps back through points. **DECISION: REMOVE the rollback CONTROL/entry from the route UI entirely (not grey-out, not defer).** ⚠️ BOUNDARY: `RouteDraftStore.openDraft` is shared by BOTH resume AND rollback — remove only the rollback CALLER/entry, NOT `openDraft` (resume needs it).

---

## 🟡 OPEN — DB DELETE-GATE REINSTALL WIPE (root-caused 06-24; V2.6 HIGH-priority fix)

> This is the actual cause of the tester "track sync failed / lost 110 real tracks" report. Sync was never the bug. The only data-loss risk in V2.5 — fix before any broad re-distribution.

**WHAT HAPPENS:** `SpatialDbManager.init()` has a "V2.5 DB REVISION v3: one-time delete-gate (regenerate-not-migrate)" (`SpatialDbManager.kt` ~60-103) that DELETES both DB files (`SPATIAL_DB` + `EXTENSION_DB` + their `-journal/-wal/-shm` sidecars) and rebuilds empty from v3 assets, gated on a **SharedPreferences marker** `grouptrack_db / db_schema_marker < 3`.

**THE TRAP:** the DBs live in PUBLIC storage and SURVIVE uninstall/clear-data, but the SharedPreferences marker does NOT survive uninstall/clear-data. So on a REINSTALL (or clear-data, or an early version that never set the marker): marker resets to 0 → `0 < 3` → the gate FIRES AGAIN → deletes the surviving POPULATED DBs → rebuilds empty. The "one-time" wipe re-fires on every reinstall because the marker is reinstall-fragile while the DBs persist.

**WHY SYNC DIDN'T RESTORE (resolved):** the gate deletes only DB files, NOT the `my_tracks` GPX files; sync is NOT auto-called on init. After the wipe the DB is empty until manual action. If the tester's `my_tracks` held onX-GROUPED files, sync's single-track parser yields empty (sync does NOT split; only IMPORT splits) → 0 inserted. He re-imported per instructions (re-import splits+inserts) → fixed. Sync code itself is fine (works on Fred's device: 67 tracks, all valid bbox).

**EXPOSURE / RECOVERY:** testers who UPDATE IN PLACE via Play keep marker=3 → gate won't fire → safe. Only reinstall/clear-data/stale-marker installs get wiped; only at-risk data = device-recorded tracks (everything else re-importable from source). Tester-executable recovery: select-all → copy to Downloads (backup) → select-all delete → import with select-all.

**THE FIX (V2.6):** gate the wipe on the DB's OWN `schema_version` table, NOT the SharedPreferences marker. The DB survives reinstall AND carries its version → "already v3 → skip the delete (and heal the marker)" is correct no matter how anyone installs. Constants: `SPATIAL_SCHEMA_VERSION=3` / `EXTENSION_SCHEMA_VERSION=3` (`SpatialDbManager.kt:36-37`); both DBs have a `schema_version` table. Belt-and-suspenders: before deleting, read the surviving DB's schema_version; if ≥3, set the prefs marker to 3 and SKIP the delete.

---

## ⛔ LEAD-CART CONVOY-TRACKING REBUILD [2.1] — recovered settled design (NEXT FOCUS, MUST-SHIP)

> Significant settled V2.5 design. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31) + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`. NOT done. Now the active next task, on top of the banked/shipped AAB.

**The problem:** current lead-cart tracking is a hodgepodge of evolved lead-cart rules + position projection. Unreliable — **phantom carts report in when a rebroadcast is made**. Gut and restart, don't patch.

**The rebuild — one lead cart, one lead track:**
1. Track **only** the lead cart, from its broadcasts (not projection).
2. **Snap-2, 100-yard radius:** snap the lead's broadcast position onto known trail/track geometry within 100 yards.
3. **Every other cart shows at its current position only — not tracked.** Live marker, no per-cart track line, no projected paths. (Kills phantom-cart at the root.)
4. **Each cart tracks its own progress** and, as it overtakes the lead's positions, removes the lead's path from its own map and replaces it with its own device GPS, recorded every second.

**Net:** one continuous trail from the lead that improves in accuracy as carts cover ground in the lead's wake. Lead broadcasts at best every 5s (radio limit) so the trail ahead is coarse; each cart records own GPS every 1s so the trail behind ("rear-view mirror") refines to 1-second truth.

**Open detail (confirm before building):** when an overtaking cart replaces the lead's path with own GPS — unconditional, or snap-gated (off-trail cart beyond threshold does NOT overwrite, so a wild detour doesn't corrupt the composite)? An earlier note had an off-trail guard.

**Planning doc — DEMOLITION + REBUILD, two parts:**
- **Part 1 — identify + remove ALL previous track-recording methods/processes.** Catalog every flow to rip out: three parallel flows (`leadTrackSegments`/`gpsTrailSegments`/`routeTrailSegments`), live `drawTrack` path (~ConvoyScreen 345-350), `trackLeadOnly` filter, ConvoyEngine lead-lock/tick pieces (`evaluateLeadLock()`, `tick()→compute()→assignLeadTail()`, `lockedLeadNodeId`, `_leadLockedFlag`; known tick-oscillation), any dead track paths.
- **Part 2 — the new method** (one lead / one track / snap-2 100yd / per-cart per-second GPS). One growing lead-position polyline gated on `lockedLeadNodeId`; `pushTrackToMap` net-new (0 refs). Discovery first: refreshed `field_crossref_raw.txt`, trace live, 2-cart field capture.

> Do not confuse lead-cart snap-2 (lead broadcast → known geometry within 100yd) with ROUTE snap-2 already shipped (route drawing follows geometry between snapped vertices). Different features.

---

## 🚢 RELEASE STRATEGY — V2.5 SHIPPED; lead-track on top of the banked release

> The sequencing worked: V2.5 (features + manual) is cut, uploaded, and LIVE. The highest-risk change (lead-track rewrite) now sits on top of a banked release exactly as planned.

1. ✅ ALL other V2.5 work complete — search, detail-panel consolidation, map-tap→detail, artifacts-FAB icon column, import/carto, area-search fix, HUD heading, route resume.
2. ✅ Manual rewritten + captured + bundled (`a658d7a00`); release notes refreshed.
3. ✅ **2.5 AAB CUT + uploaded + LIVE** (`c603bc3f0`). versionCode git-derived (`gitVersionProvider + VERSION_CODE_OFFSET 29314197`, ~29320679).
4. **Lead-track rewrite — attempt NOW, on top of the banked AAB.** Clean → folds into a 2.5.x / 2.6. Hairy → defer that piece, the live AAB stands. No-downside attempt.

**Tester-facing V2.5 known-issues (documented in release):** (1) route-build may pop an artifact info-popup every few taps — harmless, close it, route not lost. (2) resuming an in-progress route may show straight angular lines until you add/undo one point, which snaps the whole route. (3) **UPDATE IN PLACE — do NOT uninstall/reinstall** (DB delete-gate). (4) if device-recorded tracks go missing after an install: recover via select-all → copy to Downloads → select-all delete → import select-all.

---

## ⭐ COMMITTED WINS

**06-18:** FIT selection retention `35ccccc4a` · convoy "?" help `60db85131` · track arrows pixel-spacing+neon `d75572a1f`.
**06-19:** convoy universal search FAB `42dc848ce` · planning search FAB + all 3 old searches removed `583b7b9df`.
**06-20:** detail panel consolidation + Carto Type `37bc88431` · FAB + icon column `d57626f77` · FIT recenter `a43f80829`.
**06-21:** FIT final `27f493375` · arrows+touch `efc75abf4` · import/carto (M/O/Q/R) `791ed3b45` · docs rebuild `4c3541c4f`.
**06-22:** viewport report on setView + trail popup props + trail color regression + planning legend popup `7c7009d48` + `df7635528` + `f944dee25`.
**06-23:** drill-down manual + labeled images `a658d7a00` · area-search viewport seed `63ea797ab`.
**06-24:** HUD heading `c3e6ca33f` · route resume re-arm nonce + auto-checkpoint `2b1ac101c` · route popup gate + by-ID resume + redraw `d31202fcb` · footer v2.5 `c603bc3f0` · **V2.5 AAB SHIPPED LIVE.**
**06-29:** V2.6 track-capture WIP marker `0191c7034` (sync control screen, single-flight guard, sync→manual-only, reporting v3, trash filter, TrackPropertiesUpdater + 3 wires, import base fix, disable file-tap, unnamed-track-confirm; builds clean, field-unverified).
**06-30:** V2.6 **track services layer** marker `d719fbc95` (4 files, +410/−108): unified `resolveTrackAdd` (INSERT/DROP/ALIAS across sync+create+import) · complete `deleteTrackFromDb` (spatial+ext+aliases+file) · `setPreferredAlias` swap + `renameAlias` · alias-join `searchByName` · import regex parse + live feed + 4-way recap + step instrumentation. Builds clean; **import field-verified on-device** (16-track dupe detection).

---

## 🎯 TRACK SURVEY ON STOP [7.5] — V2.5 collect-now

> Build this — start collecting trail-rating data ASAP with field testers. Fully specced in AllDocs.

**GOAL:** accumulate real trail-rating data from V2.5 testers NOW, held locally, ready to populate 3.0 trail ratings when the cloud pipe exists.

**TRIGGER (RideState-driven):** on STOP after recording — ORGANIZED (real ride) → full survey+save; SOLO/CONVOY → name-only. Owned by TrackManager, not a separate screen.

**SURVEY UI:** SAVE TRACK (name, shows distance/duration) → SHARE? Y/N → RATE THIS RIDE 1-5 → RIDE AGAIN? Y/N → DONE.

**WRITES (schema FINALIZED, EXTENSION db `grouptrack_data.db`, NOT spatial):**
```sql
CREATE TABLE track_surveys (
    survey_id    TEXT PRIMARY KEY,
    track_id     TEXT NOT NULL,
    enjoyment    INTEGER,          -- 1-5
    ride_again   INTEGER,          -- 0=no, 1=yes
    submitted_at TEXT NOT NULL,
    FOREIGN KEY (track_id) REFERENCES tracks(track_id) ON DELETE CASCADE
);
```
Share Yes → `shared=1` + an `upload_queue` entry. **FINAL survey = enjoyment 1-5 + ride_again Y/N only.**

**COLLECT-NOW:** `upload_queue` — V2.5 collect only; 2.6/3.0 processes (`ConvoySurveyUploader` drains to AWS). **Survey + upload_queue + queue-panel upload/download toggle are one connected area — build together.**

**DEDUP rule:** track identity = `(artifact_type, geom_hash, creation_date)` — same geometry + same day → ONE.

---

## 🎯 TRACK SYNC — 06-29 state + remaining gaps

> Sync was rebuilt 06-28 (hash-keyed, instrumented) and on 06-29 was redefined as **MANUAL-REQUEST ONLY**, given a single-flight guard, a CONTROL SCREEN (visible feed + failures-first recap), a trash filter, and a metric-feed sweep. The loose map-lifecycle auto-call is REMOVED. See 06-29 RESULTS up top. Remaining gaps below.

`SpatialDbManager.syncTracksFromFiles(context, onProgress?)` walks `my_tracks/*.gpx`, parses each, checks existence BY HASH (`trackHashExists`), inserts new via `insertTrackToDb`, renames to `<hash>.gpx`, then runs one `backfillAllTrackProperties()` sweep. Single-flight guarded. Reports processed/added/renamed/props/failures to the control screen.
- **⏸️ DEMOTED — GAP 1b — ORPHAN SPATIAL ROW removal (DB→file reconcile leg).** The REAL fix is the DELETE-ARTIFACT fix (remove row + extension + file together) — that stops orphans being GENERATED. With that in place, this reconcile sweep is OPTIONAL and becomes a **should-never-fire safety net**: it MAY still be linked into sync/the list path as a defensive check, but in a healthy system it should find NO orphan to remove (orphans only ever came from the broken delete). **If it ever DOES fire post-fix, that's a RED FLAG pointing at a new orphan source — not normal operation.** So: not a repair feature, an optional tripwire. Existing orphan(s) like `82a5cb01` get cleaned by the FIXED delete-artifact function itself. Implementation (if kept): a row whose geom_hash has no `<hash>.gpx` file → remove with logging, guard the extension-data cascade.
- **GAP 2 — multi-track / onX split.** Sync's single-track parser does NOT split grouped/onX files (only IMPORT splits). The `splitMultiTrackFile` path (`ConvoyTrackOps.kt:~230`) still names `$baseName.gpx`, file-only, no extension wire — fold into the hash/DB-first model during the import deep-dive.
- **GAP 3 — m/ft elevation assumption** in the metric feed (UNVERIFIED — see RESULTS). Verify against a known ride.

Track storage (confirmed): ONE store. `my_tracks` GPX (`/sdcard/Documents/my_tracks`) = source of truth. Spatial `tracks` (type='TRACK') = geometry rows; needs valid bbox to appear. Extension `grouptrack_data.db` has `track_properties` + `track_surveys` + `artifact_aliases` (by id), NO tracks table. IMPORT splits onX-grouped files → individual GPX + inserts via `insertTrackToDb`.

---

## 🎯 TRAIL FINDABILITY — selecting the right trail among hundreds of same-named segments

> Framed by the PROBLEM. "Jordan River Trail" = 314 distinct segments (each unique geometry, NOT a dupe).

- **Blank startup map** — below z11 show "zoom in to see artifact info" so an empty-looking map self-explains.
- **Silent truncation at the cap** — raise cap 200→400; when hit, explicit message "Maximum artifacts reached for this map area. Zoom in to ensure you have all artifacts." No silent truncation. (Real long-term fix = paging [11.1].)
- **"Why so many times?"** — on the select-list row surface the unique geom-hash as "this is a unique trail segment." DECISION: do NOT add a `section` field (compromises spatial design; identity is `UNIQUE(geom_hash)`). Show the existing hash. The AllDocs `section`-field plan is RETIRED.
- **Can't tell map lines apart** — trail name positioners/labels on the map. (Net-new Leaflet labeling in BOTH HTMLs, hot draw path, perf concern at hundreds — needs own design pass.)
- **Detail panel IS the disambiguation tool** — reached identically from select-list row-tap, search result, and map artifact-tap (DONE 06-20).
- **Detail content (schema-safe):** trail type via CartoCode (Carto Type field — DONE); length (`distance_miles` — verify stored else derive); unique-hash indicator. CartoCode line recoloring on the MAP is a separate 2.6 question, PARKED.

---

## TILE DOWNLOAD SPEED — V2.5 interim / 2.6 redesign

**V2.5 interim (executable):** replace the hardcoded 2-at-a-time cap with **user-settable max concurrent transfers — default 4, max 6**. Single settable value + Settings control. **Queue-panel guidance:** speed depends on network; if transfers fail, advise lowering the simultaneous-transfer count until failures stop. *(Prior field obs: CRASH past 3 concurrent. Default 4/max 6 sit above that — user throttle + guidance are mitigation; crash root-cause deferred to 2.6. If 4 unstable in test, fall back to 3.)*

**2.6 full redesign (do NOT re-derive):**
- **per-artifact bbox download (maps follow the artifacts)** — NOTE: this feature is NOT gated on this redesign; it simply submits each per-artifact bbox request into the existing download queue (see "MAPS FOLLOW THE ARTIFACTS" in OTHER OPEN). The redesign can still improve throughput for the resulting requests, but maps-follow-artifacts does not wait on it.
- **Batch tile transfer** — Esri batch/bundle instead of per-tile `{z}/{x}/{y}`. Formats: **.tpkx** (Esri-native Map Tile Package) and **PMTiles** (open single-file, HTTP-range-readable). Collapse hundreds of per-tile requests into few bundle transfers → faster + stable (sidesteps concurrency crash) + threshold-economical. Needs an on-device unpacker into the Leaflet `{z}/{x}/{y}` cache; .tpkx readable via GDAL `esric` driver as reference.
- **AWS staged/hosted** — EC2 caches tiles by bbox (30-day TTL), pre-assembles a tile_manifest per area (`map_status=READY`), two-tier dedup (manifest − local_cache = delta), parallel delta pull (4 threads). First-occurrence-wins protects Esri threshold. EC2 also merges trail geometry into one GeoJSON by bound_hash.
- **Esri developer account / thresholds** — FREE Esri dev account, API-level access + monthly thresholds (~2M tiles/mo free tier referenced). Metering model open. **Account terms live only in Fred's recollection — capture as provided; do NOT reconstruct.**
- **Download-crash-past-3-concurrent** — root-cause in `ConvoyTileDownloader` concurrent path (likely resource exhaustion). Gates safe high concurrency for ANY approach.

---

## OTHER OPEN (backlog / 2.6)

- **🆕🔴 [06-29] DELETE-ARTIFACT IS LEGACY PRE-SPATIAL — deletes the FILE only, NEVER the DB row.** Confirmed 06-29: even when the file IS present, delete-artifact removes the `<hash>.gpx` file but leaves the spatial `tracks` row (and extension data) behind. Fred's read: this is legacy code from BEFORE the spatial DB — back when a track was just a file, so deleting the file = deleting the track. Post-spatial a track is a (row + file) PAIR, but delete was never updated. **SEVERITY: this is an ongoing ORPHAN GENERATOR** — every track deleted via this function leaves a dangling spatial row (and the orphan persists on the map, since the map draws from DB geometry not the file). The current 74-vs-73 orphan (`82a5cb01` / `9969bdf6`) is one such leftover; the file-vs-row diff found exactly ONE today, but every future delete makes another until fixed. FIX: delete must remove DB row + extension data (`track_properties`/`track_surveys`/`artifact_aliases` by track_id) AND the file if present (missing file = no-op). One `deleteTrack(track_id)` that removes BOTH — pairs with the add-side paired contract. Find ALL callers of the delete-artifact path. (Was mis-described earlier as "file-first fails on orphans" — the real bug is it NEVER touches the row.) First record attempt produces an EMPTY track (0 trackpoints) before Android "recognizes" recording; a second attempt records normally. Evidence: `convoy_track_temp_20260629_135730.gpx` had `<trkseg>` with 0 `<trkpt>`. Likely a location-permission/foreground-service or first-fix warm-up gap in `ConvoyGpsService` start. Investigate the record-start path; ensure the first attempt either waits for a fix or surfaces "acquiring GPS…" rather than silently producing an empty track. (Ties to NO-SILENT-PROCESSES — a failed recording should say so.)
- **🆕 [06-29] TRACK EXTENDED-DB DATA in artifact detail + track popups.** Surface `track_properties` fields (distance_miles, duration_minutes, avg/max speed, elevation_gain_ft, point_count, recorded_at) in the ArtifactDetailPanel and the on-map track popup. DEPENDS on the metric feed actually populating `track_properties` (verify 06-30 via the cold-launch recipe). Read-only display; pull by track_id.
- **🆕 TRACK RENAME — PART OF THE ALIAS/DETAIL FUNCTIONALITY (not a standalone task).** Rename lives with the artifact-detail operations (delete, rename, alias swap/rename) on the detail panel — wire/test them together. Rename touches ONLY the track NAME field (`renameTrackInDb` already does this — edits only `tracks.name`, not geometry/hash); the remaining work is wiring the detail-panel caller to it (away from the legacy file-rename). Covered by the FIRST TASK (detail-panel wiring) above.

- **⭐ MAPS FOLLOW THE ARTIFACTS — per-artifact bbox tile download (V2.6, Fred 06-28).** PRINCIPLE: whatever you bring to the field (imported tracks, trails, or a route planned online) brings its supporting map tiles with it — same bbox design for all three artifact types. Plan a route on wifi → the tiles to support it download with it. SPEC (keep simple, ship, then re-evaluate): for each artifact, take its bbox, expand ~½ mile each side, queue a tile-download request into the download queue. **19-track GPX → 19 separate per-track bbox requests** (NOT a union bbox). RATIONALE: Fred's field experience — riders download ~120GB of map data for terrain they rarely ride; a union/region box wastes enormous tile area in the gaps between thin trail corridors. Per-track tight boxes download only the corridors actually ridden. DOUBLE SAVING (Fred 06-28): it's not just empty gaps — OHV riding is backcountry (desert/forest/mountain), which is TILE-LIGHT (few structures/features). The DATA-DENSE tiles are cities/developed communities (building footprints, street grids) — exactly the terrain riders go AROUND, not through. A region bbox clips the edges of towns and sweeps up those heavy urban tiles (a few dense city tiles can outweigh hundreds of backcountry tiles). Per-track corridors thread BETWEEN the developed areas and stay in sparse terrain → less area AND lighter tiles. The 120GB is likely dominated by developed-area tiles the big download boxes incidentally swept up, not the trail miles themselves. APPROACH: ship the per-track-bbox version, then MEASURE actual GB pulled vs the 120GB baseline before adding complexity. Possible later refinement (only if still heavy): polyline-buffer corridor instead of bbox (drops empty bbox corners for diagonal/curvy tracks) — NOT now, don't overcomplicate. OPEN (defer until after first measurement): zoom-level range (biggest volume lever — likely part of the over-download problem); ½-mile expansion math (lat constant ~0.0072°, lon scales by cos(lat)); whether to spawn into the CURRENT per-tile queue or gate on the V2.6 batch-download redesign — RESOLVED (Fred): **NOT gated on the redesign — it simply SUBMITS each per-artifact bbox request into the existing download queue.** No dependency. Zoom-level range + ½-mile expansion math still open as tuning. Must obey NO-SILENT-PROCESSES: show "queuing N tile downloads for imported tracks," visible in the queue panel. Likely opt-in toggle at import/route-save (don't auto-pull GB on cellular).

- **[6.2] Remove leftover geojson asset + JS-injection code** — `utah_trails_stgeorge.geojson` still LIVE-LOADED at `ConvoyScreen.kt:1912`. Remove the loader block FIRST (else FileNotFound), then `git rm` the asset, tidy `grouptrack_map.html:774` (dead comment). Trails come from the spatial DB now. Own commit, NOT folded into a feature commit. Don't touch near a release.
- **Remove obsolete map-instructions submenu (Map Settings)** — content now in the panel. Own data-first patch + commit.
- **[3.3] Queue panel** — restore upload placeholder + add upload/download activity selector at top of panel. (Backend hold/resume/cancel done; this is UI — connects to survey/upload_queue.)
- **Separate empty `routes` TABLE** — the spatial DB has a `routes` table distinct from tracks-with-type-ROUTE; routes currently live in `tracks` (type='ROUTE'). Note for later; reconcile if it matters.
- **Move completed route to in-progress / Copy completed route to new** — artifact-detail actions under Fit (V2.6). Move: copy → in-progress draft → verify → delete route from spatial DB → open via resume (verify-before-delete; WKT geometry only, lossy interior snap). Copy: non-destructive duplicate to a draft.
- **Blank trail-name in FIT's JSON row** — id correct, selection id-based so it works; name writes "". Cosmetic, parked.
- **[1.2] sliceLine whole-trail — VERIFY OBSOLETE** — confirm dead (xref `sliceLine` callers) and remove, or re-scope.
- **Backlog:** [11.1] paging (real fix behind artifact cap); Map Manager screen items.
- **Tree cleanup:** remove `.bak_*` files, `utah_trails_stgeorge.geojson.bak`; never commit `grouptrack_spatial.db` (~117MB), `docs/.tmp.driveupload/`.

---

## DESIGN CONTEXT (carried forward)

- **Two draw paths BY DESIGN:** (A) `drawPersistedState` = saved/restore from JSON; (B) onViewportChanged = in-memory live vars preserving selections across zoom/pan. New actions update the LIVE side via the existing select mechanism, not bypass it.
- **The durable two-role write (deferred from 06-23; still the real fix for the search/draw class):** make the persistent frame write happen on EVERY reposition for BOTH maps via one mechanism. The write has TWO ROLES — BEFORE the draw the bbox is the INPUT driving the queries (stale → resolves the old/empty container = the area/chord bug); AFTER the draw the query has resolved the new container (worth persisting). Read `onViewportChanged → processViewport → processArtifact` IN ORDER; place the writes where the bbox is consumed and where content resolves; route area / FIT / gesture (moveend) / cold-launch GPS-center (`ConvoyScreen 670`, currently leaves `lastViewport*` at 0.0) / filter-change (onSetState) through it. `63ea797ab` is the area-only band-aid this replaces. The route-resume by-ID fix is the same bug class (another caller that needs the right geometry before the draw).
  - *Pipeline reference:* JS `onViewportChanged(getNorth,getSouth,getEast,getWest,z)` → ConvoyScreen handler (575 unconditional draw / 769 gated on `lastMapProcessed`) → `SpatialDisplayManager.processViewport(s,w,n,e,zoom,states,selectLists,wv,ctx)` → loops 4 types → `processArtifact` (per-type identical; `queryXByViewport` → filter by checkedIds if SELECTED → `buildGeoJson` → `updateX()` + `showX()`). `SpatialDisplayManager` holds NO state (pure consumer of the bbox passed in). `saveConvoyState()` (ConvoyScreen 248) writes the FULL JSON snapshot (states + checked rows + bbox) from `lastViewport*` + per-type vars. FIT seeds `lastViewport*` at 665-666 / 1841. Cold-launch GPS-center (670) does NOT seed. Area seeds via the `[AREA FIX]` round-trip (UnifiedSearch ~114).
- **Map-purpose model:** Convoy = live/location (GPS, proximity, session-only). Planning = deliberate/identity (search, fit, persisted frame).
- **Reusability principle:** own behavior in the SHARED component; callers pass DATA not BEHAVIOR (`mapContext` routes it). This is why search is shared `UnifiedSearch.kt` and the detail panel is ONE shared `ArtifactDetailPanel`.
- **Two front-ends, one backend:** trail-source SELECT_SOURCE and BY_AREA share the import process; the starting step is an EXPLICIT launch mode at the call site, NOT a device file. A device file as a message bus between two in-process composables is the anti-pattern that caused the stale-JSON hijack.
- **Data-integrity:** SPATIAL tables PURE OGC; properties/surveys/aliases/queues in EXTENSION db by id. Spatial `trails.carto_code` = COLOR-DRIVING; `trail_properties.carto_code` (ext) = DISPLAY — the two are DIFFERENT. Any spatial/extension boundary change = STOP-AND-CHECK.

---

## PROCESS NOTES — the recurring failure modes

1. **Get the runtime fact EARLY (06-24).** When code reads correct but behavior is broken, read the runtime value — device file / logcat / pulled DB / **pull-and-grep the installed APK**. 06-24 burned 3 rebuilds before the APK-grep proved deployment fine and the fixes wrong. The in-app Settings build date is a STALE cached field — never a deploy check.
2. **Don't commit to a root cause until the OBSERVATION is pinned — especially secondhand (06-24).** The track-sync scare produced two wrong sync theories before the real cause (the DB delete-gate) surfaced. Let incremental facts lead; don't over-engineer off one data point.
3. **Believe the device + Fred's lived observation over the code (06-21).** Hunt the SPECIFIC differing thing before theorizing from code.
4. **Look at the DATA before changing dependent code; touch only the field in scope (06-22).** The CODE drives behavior, the TEXT is just text — never merge one into the other.
5. **Settled designs keep getting lost** — this checklist + memory carry open items (append, don't drop). Settled designs go on the list the same day; search the record before declaring a task "doesn't exist." **Commit working features same-day.**
6. **Carry mid-diagnosis threads forward** — open diagnostic threads get FINISHED before new work stacks on them.

---

## DEVICE / BUILD QUICK-REF

- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm ~12-23 min; cold ~50 min; `--rerun-tasks` forces a FULL ~43-46 min / ~1006-task build — needed when an HTML asset must re-bundle, but heavy). AAB: `./gradlew bundleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` → `app/build/outputs/bundle/googleRelease/app-google-release.aab`.
- **versionCode is git-derived** (`gitVersionProvider + VERSION_CODE_OFFSET 29314197`); committing bumps it automatically. **versionName "v2.5" is a HARDCODED footer literal** at `ConvoySettingsScreen.kt:254` — bump by hand per release. `config.properties VERSION_NAME_BASE=2.7.14` is the upstream Meshtastic fork version, NOT GroupTrack's.
- **⛔ DEPLOY-VERIFY:** the in-app Settings build date/time is STALE — do NOT use it to confirm a deploy. Verify: `adb shell pm path com.grouptrack.android` → `adb pull "<quoted ~~.../base.apk>" installed.apk` (QUOTE the path — `~~`/`==` mangle in Git Bash) → `unzip -o -q installed.apk "assets/convoy_map.html" -d apk_check && grep -c "<marker>" apk_check/assets/convoy_map.html`. `dumpsys package … | grep lastUpdateTime` + APK byte-size match are also ground truth.
- **GREP-CONFIRM a patch is on disk before building** (`grep -n "<marker>" <file>`) — a silently-aborted patch wastes a full build.
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`.
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` THEN `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell am force-stop com.grouptrack.android` then COLD-LAUNCH FROM THE ICON (not recents — recents resumes the old WebView/code). Droid 1 = `8624SBCEDF00001789` (field/real-GPS, pkg `com.grouptrack.android`); Droid 2 = `24039703201775` (dev).
- Device shell/pull paths in Git Bash need `MSYS_NO_PATHCONV=1` on the REMOTE path. State JSON: `/sdcard/Documents/GroupTrack/state/<map>_panel.json` (deleted on cold launch). Route drafts: `/sdcard/Documents/GroupTrack/route_drafts/<name>.json`. my_tracks GPX: `/sdcard/Documents/my_tracks/`. NO sqlite3 on device — pull the DB and query locally via `python3 -c "import sqlite3 …"`. Pulled DBs: spatial `grouptrack_spatial.db`, extension `grouptrack_data.db`.
- Logcat: `adb -s 8624SBCEDF00001789 logcat -c` → action → `adb -s 8624SBCEDF00001789 logcat -d -s RouteBridge:* convoyLog:* RouteResumeDiag:* TrackSync:* SpatialDbMgr:*`.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 <name>.py`. UNIQUE versioned filenames; Windows path INSIDE the script = `C:/Users/...`. count==1 guard + runtime newline detect; identical lines need collision-proof anchors. **No stray `</parameter>` or tags on bash commands** (causes `bash: syntax error`).
- **LINE ENDINGS:** SpatialDbManager.kt / ConvoyViewModel.kt / ConvoyTrackImportScreen.kt / ConvoyTrackOps.kt = LF (ConvoyTrackOps confirmed LF 06-29); TrailImporter.kt / ConvoyTrailSourceScreen.kt = LF; ConvoyMapViewerScreen.kt + ConvoyRouteToolbar.kt + ConvoySettingsScreen.kt + both HTMLs = CRLF; ConvoyGpsService.kt + MainActivity.kt = CRLF; RouteDraftStore.kt = LF; ConvoyScreen.kt = check before patching. Commit only named files, never `git add .`.

## EOD DOCS — status

- This checklist → **06-30 EOD** (the V2.6 **track services layer** landed + committed `d719fbc95`, builds clean, import field-verified on-device: unified `resolveTrackAdd`, complete `deleteTrackFromDb`, alias swap/rename, alias-join search, import regex-parse + live feed + 4-way recap + instrumentation. NEXT SESSION FIRST TASK: wire the track detail panel to the new services + retire the legacy file-only delete/rename, then the recording show-stopper. STANDING RULES: no silent processes; instrument before theorizing; don't multiply failure points beyond the target.).
- Handoff → `GroupTrack_Handoff_2026-06-30.md`; pairs with this checklist; cold-start orientation for the detail-panel wiring + recording fix on top of the shipped V2.5 AAB.
- Manual → `app/src/main/assets/grouptrack_manual.html` (committed `a658d7a00`). Masters (NOT committed, archive on Drive/G:): `grouptrack_manual_DRILLDOWN_2026-06-23.html` (~82MB), `grouptrack_manual_LIVE_2026-06-23.html` (~87MB). **06-30: NEW DRAFT SECTIONS added for the import live feed + 4-way recap + alias display (see `GroupTrack_Manual_NewSections_2026-06-30.md`) — carry `[SCREENSHOT NEEDED]` markers; Fred captures the screens when ready, then they fold into the live manual.** No tester-facing manual change is committed yet (nothing field-verified to ship).
- Release notes → `app/src/main/assets/grouptrack_release_notes.html` (committed, 06-23). Install-as-update warning preserved. **06-30: a "next release (TBA)" draft block was added for the track services work (see `GroupTrack_ReleaseNotes_NextBlock_2026-06-30.md`) — NOT yet merged into the shipped asset.** Current asset = 11,686 bytes, manual = 3,371,012 bytes — both unchanged in the committed app.
- **⏭️ QUEUED for the NEXT build's release notes — MANDATORY TRACK RESYNC tester instruction.** When the track work is field-verified and SHIPPED, the next build's notes must tell testers to run a track resync to reconcile (sync is now manual-request-only). **Exact wording = Fred's draft from the 06-29 session (to be re-pulled from that conversation) — do NOT invent it.** Does NOT apply to the LIVE V2.5 build.
- Lead-track authority → `GroupTrack_LeadTrackReplacement_Spec.docx` + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **V2.5 AAB LIVE on Play Store at `c603bc3f0`. 06-30 track services layer COMMITTED as marker `d719fbc95` (4 files, +410/−108; builds clean; import field-verified). This is the current recovery point. Prior marker `0191c7034` (06-29 track-capture) is its parent. Untracked `d1.db`/`legacy_sample.gpx` + debug junk (`import_trace.txt`, `data_check.db`, `spatial_check.db`) deliberately NOT committed.**

### 06-30 PATCH SET (in Downloads)
`patch_import_regex_parse_2026-06-30_v3` (streamer→regex) · `patch_import_regex_fix_2026-06-30_v1` (raw triple-quote) · `patch_import_instrument_2026-06-30_v2` (ENTRY/READ/TRACK logging). Plus the resolver/delete/alias/search patches folded into the marker. (Earlier v1/v2 of the regex/instrument patches superseded — anchor/escaping fixes.)

### 06-29 PATCH SET (in Downloads, applied)
`patch_sync_singleflight_guard` · `patch_remove_loose_autosync` · `patch_fix_sync_var_scope` · `patch_sync_control_screen_v1` · `patch_sync_autoscroll_v1` · `patch_sync_reporting_backend_v3` (v1/v2 superseded) · `patch_sync_trash_filter_v1` · `patch_track_properties_updater_v2` (v1 superseded) · `patch_wire_track_properties_v1` · `patch_import_base_fix_v1` · `patch_disable_filetap_import_v1` · `patch_unnamed_track_confirm_v1`. (All dated 2026-06-29.)
