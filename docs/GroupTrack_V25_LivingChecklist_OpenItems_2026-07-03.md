# GroupTrack V2.5/V2.6 — Living Checklist / Open Items
_Updated 2026-07-03 EOD. Supersedes the 07-02 checklist._

---

## 🟩 START HERE — NEXT SESSION (07-03 EOD; Fred's stated 3-item plan for tomorrow)

Fallback 2.5/7-3 AAB is BANKED (docs committed `f6d43a75f`, bundle cut). Clear to do the risky code work with a safety net in place.

### 📋 TOMORROW — 3 ITEMS (Fred's list)
1. **ROUTE BUG A — artifact popup suppression during route-build.** Resolve the conflict between route-creation point placement and the artifact popup opening on tap. FIX (proven pattern): gate each artifact popup/tap on `__routeMode` at TAP time (`if(window.__routeMode)return;` atop each click handler). track(db) + loadTrackFile already gated; ADD to trail@439, waypoint@590, route@636 in `convoy_map.html`. Suppress the POPUP (not the refresh — prior wrong-layer attempt). Do it on BOTH map HTMLs / BOTH screens (ConvoyScreen + ConvoyMapViewerScreen both render convoy_map.html + drive setRouteMode). HIGHER RISK (map HTML) — do Kotlin item #3 first if stacking. This is also where the two-screen duplication → shared map-interaction-layer question gets decided.
2. **AUTO-RESYNC ON LAUNCH.** Fire resync automatically on first launch after install — makes the shipped release-notes "runs automatically" promise true. SAFELY: one-shot (version/`resync_done_v{N}` flag set AFTER success), idempotent (rebuild from my_tracks GPX source of truth — re-run is additive, not destructive), visible (progress UI), non-destructive + safe-fail (on failure log/surface, do NOT set the done-flag so it retries, NEVER wipe). Touches STARTUP (sensitive) — build carefully. Radio-free. Look first: manual RESYNC path (the SYNC TRACKS panel), startup DB gate (SpatialDbManager ~60-103), whether a version flag exists.
3. **TRACK DETAIL METRICS — add Duration + Avg Speed.** The live track detail panel shows only Distance / Max Spd / Elev / Points; the shipped manual/notes copy implies the fuller set. Add **Duration** and **Avg Speed** so the panel matches the manual. Both fields exist in `track_properties` (duration_minutes, avg_speed_mph). If this lands before final release, RE-SHOOT the detail-panel manual screenshot.

### ⛔ DISPOSITIONED NOT-AN-ISSUE (07-03 — removed from active list)
- **DB DELETE-GATE REINSTALL WIPE** — Fred: NOT an issue. It was specific to the V2.5 schema-conversion install and only affected users who did a full uninstall→fresh-install instead of an in-place Play update. Leave it. (Detail retained in ARCHIVE section below for history.)
- **BUG B (resumed route draws angular/chords)** — Fred: NOT an issue. Self-corrects on the next track point. Off the list.

### 🟢 ONLY REMAINING NON-BLOCKER CLEANUP
- **REMOVE ROLLBACK control from route UI** (Undo stays; decided 06-24, reaffirmed 06-30, still not done). Remove only the rollback CALLER/entry, NOT `RouteDraftStore.openDraft` (resume needs it). Low-risk; parked unless Fred wants it.

---

## ✅ DONE 07-03 (this session)

### Fallback 2.5/7-3 AAB — BANKED
- Ships as ONE 2.5 release with a 7/3 (2026-07-03) release date (same version number as live Play Store V2.5, new build). Date appends automatically; versionName footer literal "v2.5" left as-is (only bump on a real version change).
- Two finished doc assets overwrote the Jun-23 baselines in `app/src/main/assets/` (canonical names grouptrack_manual.html / grouptrack_release_notes.html — the app loads those exact names; local archive copies may be date-stamped but repo assets keep plain names). Committed only the two named files → `f6d43a75f`; versionCode auto-bumped. Manual now 6,321,871 bytes / notes 152,088 bytes.
- Cut `./gradlew bundleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` → AAB at `app/build/outputs/bundle/googleRelease/app-google-release.aab`. (Confirm BUILD SUCCESSFUL + .aab present next session if not verified.)

### Docs — folded, illustrated, shipped
- Release notes: title V2.5, subtitle "Updated 2026-07-03". Two DATED sections (not a version split): "New in this build · 2026-07-03" + "Earlier in this release · 2026-06-23". Upgrade notice resync line concrete + bold: "If it did NOT run automatically on your install, you must run it yourself — open the track import screen and tap RESYNC TRACKS — or your tracks will not display." The "Resync shows its work" item carries BOTH resync screenshots (progress + summary) with auto-launch-vs-run-it-yourself guidance. HELD items (Track aliases, Search-finds-every-name) remain HTML comments — publish when alias UI ships + is field-verified. All 24 dead manual deep-links (#maps/#downloads) retargeted to the real #planning anchor.
- Manual (6 embedded screenshots, all downscaled ~600px JPEG): detail-panel two-column metrics + SAVE MAPS + offline-maps benefit + import-checkbox cross-ref; both gesture sections state tracks open the detail panel directly on map-tap; Import section corrected to "Import Tracks from Downloads" with IMPORT/MAPS/FILE columns + RESYNC at top + per-file MAPS checkbox (off by default); resync rewritten to the real SYNC TRACKS flow.
- REAL UI VOCABULARY captured (supersedes earlier guesses): IMPORT summary buckets = **new / aliased / duplicate / skipped** (NO "updated"). RESYNC = separate **SYNC TRACKS** panel: START/CLOSE gate → total to sync → live rolling feed (`IGNORE no-geometry: <file> (N bytes, 0 pts)`, `DUPE: <hash> … already known, source removed`) + `running…` → final summary leading with `⚠ N FAILED — research these:` (name+reason, e.g. "file missing"), then `processed N · renamed N`, `properties N/M written`, full per-track `props:` list, `=== sync complete: processed=… skipped=… added/renamed=… renamed=… ===`, CLOSE / RE-RUN.
- BUG FIXED: earlier Python-heredoc edits had leaked 7 literal `\u2019`/`\u2014` escapes into the manual's rendered text (would have shipped as visible gibberish). All converted to real characters. LESSON #17: heredoc edits can write escape sequences literally — `grep -c '\u20'` + visual review after such edits.

### Doc follow-ups (NOT blockers)
- Import-summary screenshot is all-zero (re-import of an existing file — truthful but not illustrative; swap for a non-zero shot later if wanted).
- Save the finished docs to Fred's two archive homes (Drive GroupTrack_docs + G:) and repo `docs/` per standing workflow.
- If the metrics-panel fix (Duration+Avg Speed) lands before final release, re-shoot the detail-panel image.

---

## ✅ DONE 07-01/02 (committed — carried)
- **Crisis recovery:** `acdaf7b85` restored auto artifact refresh (the `e0182045a` lastMapProcessed gate had made it manual = the "tracks don't display" crisis). Persistence solid — don't re-add a broad gate; correct end-state is narrow mode-scoped suppression (route-mode/recording only). `c74d5f8b1` my-cart device-default/radio-override (HOTEL-10 removed; radio-override pending radio). `ccc559015` detail-panel 2-col metrics grid (presentation-only).
- **Maps-follow-tracks — COMPLETE + committed:** engine `downloadMapsForTrackHash` (geom_hash → row → pad ½mi → `enqueue`). KEY BUG FIXED: `enqueueArea` gridCells() returns empty for a sub-cell box → totalTiles=0; use `enqueue` (single region any size). SAVE MAPS button (detail panel) tested + committed. Import per-file MAPS checkbox committed. **⚠️ OPEN ISSUE: MAPS checkbox IGNORED when track ALREADY EXISTS** (fires only in INSERT branch; move the maps call out of `AddOutcome.INSERT ->` and gate on the checkbox for any non-error outcome).
- **Track tap → detail panel — DONE both screens.** Two bugs solved (do NOT re-troubleshoot): wrong render fn (tracks drawn by `loadTracks`→trackLayer, confirmed via ARTIFACTTAP instrumentation) and bridge-injection timing race (decide bindPopup-vs-onTrackTap at TAP time, not layer-build time). Tap tolerance raised 18→44 / tapTolerance 20→30 in both HTMLs.

---

## ✅✅ 06-30 — TRACK SERVICES LAYER (committed `d719fbc95`, clean baseline)
- **Unified add resolver** `resolveTrackAdd(name, sourceFile): AddOutcome` — the ONE shared add path (SYNC/CREATE/IMPORT route through it). INSERT / DROP_NAME / DROP_ALIAS / ALIAS / NO_GEOMETRY / ERROR. `insertTrackToDb` returns `TrackInsertResult(wasNew, trackId, geomHash, name)`.
- **`deleteTrackFromDb(id)`** — spatial row + track_properties + all aliases + `<hash>.gpx`. **`setPreferredAlias`** = SWAP (local-only, trail-guarded). **`searchByName`** matches name OR alias (two-query Kotlin merge — separate SQLite files, no JOIN).
- **Import rework + fix:** regex splitter restored (`Regex("""<trk>([\s\S]*?)</trk>""").findAll(text)` — RAW triple-quoted); the 06-29 bufferedReader streamer found 0 `<trk>` on valid files. Live per-record feed + 4-way recap.
- **TEST FIXTURE — DO NOT SWEEP:** orphan spatial row `82a5cb01-…` ("St George to Bar10", hash `9969bdf6…`, real geometry, NO file) — validates `deleteTrackFromDb`. (This is the same track that shows as the `⚠ 1 FAILED — file missing` line in the resync summary — expected.)

### 🆕 open tasks surfaced 06-30 (carried)
- **Recorder truncation** — produces GPX with no closing tags on interrupt (ties to the recording show-stopper; fix at source: record-stop must finalize the file).
- **Import tolerance (optional)** — salvage truncated files like sync does, or surface "malformed: no </trk>" (instrumentation now reports it).
- **`import_tracks` staging dir — PARKED.** Current import writes to my_tracks via the resolver and works; staging is a refinement, not scheduled.

---

## 🎯 CARRIED OPEN TASKS (RE-TRIAGE vs git before actioning)
- **ANDROID-GPS TRACK RECORDING bug** (RADIO create certified 06-30; Android-only fails). ConvoyGpsService.kt (CRLF): `startLocationUpdates` (~251-277 prime suspect), `onGpsUpdate` (~288). Instrument first. Pending radio / next week.
- **DETAIL-PANEL WIRING CLUSTER (partly done):** repoint file-based rename/delete callers (ConvoyMapViewerScreen ~1681/1700, ConvoyTrackExportSheet ~256/275) to DB services; retire legacy `deleteTrack(File)` (~63) + `renameTrack(File)` (~50). Alias-display UI + "matched via alias". Verify vs git.
- **FIELD-VERIFY THE METRIC FEED** (TrackPropertiesUpdater) — confirm `=== properties: N tracks written ===`. NOTE: the 07-03 resync summary showed `properties 80/81 written` with 1 FAILED — the feed IS running. Still gut-check distance/speed vs a known ride; watch elevation m/ft (`<ele>` assumed METERS ×3.281→ft).

---

## ⭐ TRACK MODEL — CREATE / IMPORT / SYNC + V3.0 SHARING (locked rules)
- SPATIAL tables PURE OGC; properties/surveys/aliases/queues in EXTENSION db by id. Separate SQLite files — NO cross-DB JOIN. Boundary change = stop-and-check.
- `tracks`: track_id PK, geom_hash NOT NULL UNIQUE (SHA-256 of WKT). `track_properties`: track_id PK, filename UNIQUE, distance_miles, duration_minutes, max/avg_speed_mph, elevation_gain_ft, point_count, shared, ride_id, area_id. `artifact_aliases`: artifact_type (singular), artifact_id, alias, is_preferred (local), source, geom_hash.
- Storage: my_tracks GPX (`/sdcard/Documents/my_tracks`) = SOURCE OF TRUTH; DBs at `/sdcard/Documents/GroupTrack/`. Track files `<track_id>.gpx` (filename IS track_id). DELETE-AND-RESYNC off the table for deletes; forced resync on install is a rebuild-from-source (additive) — different.
- **V3.0 SERVER SHARING:** LOCAL = preferred name. UNIVERSAL/AWS = first-in for hash sets canonical name; first-in for (hash,date) sets alias. Group-ride collapse needs fuzzy/spatial matching. AWS import = stage-to-dir + reuse local import path. OPEN DESIGN PROBLEM: same-ride cross-device matching (unsolved).
- **"Artifact builds, not process builds":** complete tracks fully, test as a unit, THEN routes, THEN waypoints.

---

## 🗄️ ARCHIVE — DB DELETE-GATE (dispositioned NOT-AN-ISSUE 07-03; kept for history)
Root-caused 06-24. `SpatialDbManager.init()` "v3 one-time delete-gate" deletes both DB files and rebuilds empty, gated on a SharedPreferences marker `db_schema_marker < 3`. The DBs live in public storage and survive uninstall, but the prefs marker does NOT — so a full reinstall/clear-data resets the marker → gate re-fires → wipes surviving populated DBs. Fred's disposition: this only bit users who did uninstall→fresh-install instead of an in-place Play update during the V2.5 schema conversion; not a live concern. (If ever revisited: gate on the DB's own `schema_version` table instead of the prefs marker.)

---

## 🧭 LESSONS (burn in)
1 build the visible/requested thing first · 2 inventory the original-design fix before stacking · 3 fix the generator · 4 instrument before theorizing (deploy it) · 5 don't multiply failure points · 6 believe the device/data · 7 blast radius ≠ diff size · 8 green build + on-device verify before "done"; cold-launch from icon; bank a marker before long uncommitted stretches · 9 re-triage vs git · 10 build budget ≤2 builds/feature (~12-22min warm) · 11 use the nav xref for reachability · 12 fix the cause on the right layer (BUG A: popup not refresh) · 13 state test preconditions · 14 forced startup DB actions: one-shot + idempotent + visible + non-destructive + safe-fail · 15 believe the render call-chain (instrument which JS fn draws it) · 16 webview bridge calls: decide at EVENT time not layer-build time · 17 heredoc HTML edits leak escapes literally — grep + visual-review after.

---

## 🛠️ DEVICE / BUILD QUICK-REF
- **Droid 1** `8624SBCEDF00001789` (field / real-GPS — all 07-03 screenshots) · **Droid 2** `24039703201775` (dev). Fred TRAVELING — phone-test only, no radio till next week.
- Screenshots → fixed folder `D:\grouptrack_screenshots` (`/d/grouptrack_screenshots`): `adb -s 8624SBCEDF00001789 exec-out screencap -p > /d/grouptrack_screenshots/<name>.png`. Downscale to ~600px JPEG before embedding.
- BUILD `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease 2>&1 | grep -E "^e:|BUILD|FAILED"` (warm ~12-22min). APK `app/build/outputs/apk/google/release/app-google-release.apk`; AAB `bundleGoogleRelease` → `app/build/outputs/bundle/googleRelease/app-google-release.aab`. Install `adb -s <serial> install -r -d <apk>`. COLD-LAUNCH FROM ICON. No two Gradle builds at once. Commit only named files, never `git add .`. versionName footer = hardcoded "v2.5" (ConvoySettingsScreen.kt ~254) — bump only on a real version change; versionCode git-derived (auto-bumps on commit).
- **Working-dir clutter to leave / consider gitignoring:** d1.db, d1_import_maps_checkbox.png, d1_track_detail.png, legacy_sample.gpx (test artifacts, not staged).
