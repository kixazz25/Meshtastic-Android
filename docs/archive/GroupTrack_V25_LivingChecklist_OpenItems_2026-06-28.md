# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-28
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `c603bc3f0` (V2.5 shipped — AAB LIVE on Play Store)

---

## 🟦 START HERE — NEXT SESSION (06-29 plan)

**⛔ STANDING PRINCIPLE — NO SILENT PROCESSES.** Any process that does real work (especially data-touching: sync, import, delete, upload, recording) MUST show it's running, stream progress, and report its result on a surface the user can't miss (a screen/dialog, not a toast that can be drowned). Silence both HIDES and CAUSES failures — proven 06-28: sync gave no feedback → Fred pressed RESYNC twice → concurrent overlapping runs → an hour of debugging a storm that a visible process would have prevented. No "miracle happens" steps.

### ✅ 06-28 RESULTS (what we verified)
- **CREATE (record-save) FIX — BUILT + APPLIED.** `ConvoyGpsService.finalizeTrack` drops the timestamp + forces gpx; `ConvoyViewModel.finalizeTrack` injects the typed name into `<trk><name>`, computes geom_hash, calls `insertTrackToDb`, renames file to `<hash>.gpx`. Self-contained inline (does NOT call sync). Compiles clean. (Field-cert on Droid 2 still pending — see plan.)
- **SYNC REWRITE — BUILT + APPLIED + VERIFIED WORKING via disk log.** Rewrote `syncTracksFromFiles` to the hash-keyed model with full per-file instrumentation. The 06-28 disk log (`/sdcard/Documents/GroupTrack/track_sync.log`, also logcat tag `TrackSync`) PROVED the core logic is correct: every file parses (e.g. pts=2746/2958/447…), computes its hash, is found in the DB, and is correctly skipped/renamed. Droid 1: all 71 files now hash-named, 69 DB rows, NOTHING LOST (backup of 71 in `/sdcard/Download/mytracks_backup`). The earlier "15 of 71 renamed" scare was a STALE SNAPSHOT — files were progressively renamed across the day; the final state is all-hash-named.
- **RECAP TOAST wired** at both RESYNC buttons (`ConvoyMapViewerScreen` 1318, `ConvoyTrackImportScreen` 241) — shows processed/added/renamed counts. (Toast proved unreliable — drowned by concurrent runs; replace with a control screen — see plan.)
- **Patches (in Downloads, applied):** `patch_create_inline_insert_2026-06-28_v1.py`, `patch_sync_instrumented_2026-06-28_v2.py`, `patch_sync_recap_toast_2026-06-28_v1.py` (+ `_import_v2` for the LF file).

### 🔴 06-28 REAL PROBLEM FOUND — sync has no home; it runs loose + silent
- Sync is **auto-invoked off the map lifecycle** (`ConvoyMapViewerScreen:601`, on map load/viewport). The 06-28 log shows the SAME files processed 5-6× across SIX thread IDs with `=== sync start ===` appearing mid-stream → **multiple concurrent overlapping sync runs**. Fred pressed RESYNC twice (no feedback) which compounded it. Fred's read (likely correct): **the lack of a controlling screen means sync runs loose on the map lifecycle, so viewport resets re-launch it** — and processing may re-trigger it. It's idempotent (everything just skips) so it's WASTEFUL not destructive — but it's out of control and invisible.
- **The fix Fred specified = a SYNC CONTROL SCREEN** that OWNS the run (decoupled from the map lifecycle entirely), shows a ROLLING file-by-file status as it processes, and shows the RECAP at the end. This is the right architecture: contain sync in a screen that owns it, don't patch a loose background process.

### 📋 06-29 PLAN — get all three track processes under control + visible, then test
1. **Build the SYNC CONTROL SCREEN.** Dedicated screen: launch sync ONCE on a "Start" tap (owned by the screen, NOT the map lifecycle). Stream a rolling list ("Processing: <file> (N pts) → added/renamed/skipped"). Show recap at end (processed/added/renamed). **REMOVE or gate-to-truly-once the map auto-sync at `ConvoyMapViewerScreen:601`** so sync no longer fires on viewport/redraw. Add a single-flight guard so a sync can't run concurrently regardless. FIRST PULL: `sed -n '585,605p' ConvoyMapViewerScreen.kt` to see/cut the 601 trigger; find all `syncTracksFromFiles` callers; check whether `SpatialDbManager.init` or the insert path re-triggers sync (Fred: "processing a track launches another version").
2. **NEW TRACKS (create) — field-certify on Droid 2.** Record → save → confirm it displays, file is `<hash>.gpx`, DB row has typed name + hash. Apply the same "no silent process" rule to record-save (it should confirm the save + insert visibly).
3. **IMPORT revisions.** Make import (a) write `<hash>.gpx` (currently `$baseName.gpx`, ConvoyTrackOps.kt:547) with DB-write-FIRST then file (currently file-first at 547, insert at 569 — reorder), and (b) run under a visible control/progress surface (no silent multi-entity decomposition). KML→GPX preprocess stays a deferred import-only sub-step. PULL: `sed -n '540,575p' ConvoyTrackOps.kt` + its line ending.
4. **Test all three** under their visible surfaces: resync (control screen), new track (Droid 2), import (progress surface). Two-device split as before (Droid 1 resync, Droid 2 new track) so a failure in one still certifies the other.
5. **THEN: the route-planning popup / trails-data issue** (BUG A below — tapping near a trail during route build pops the layer popup; gate pointer-events on the trail/track LOAD path when `__routeMode` true, not just at route-mode entry). Gate the next AAB on track fixes + this popup fix.

### Design work still queued (from 06-28 deliberation — see TRACK MODEL section)
- **Paired add/delete contract:** every spatial track add should also add its `track_properties` extension record (distance/duration/speed from GPX, filename, shared=0); add fails on hash → skip extension too. Deletes must remove both (FK cascade can't be relied on — PRAGMA often off). Best: one `addTrack` / one `deleteTrack` that write/remove BOTH tables; route create/sync/import through them. **track_id vs geom_hash:** they're 1-to-1 (track_id=PK UUID, geom_hash=UNIQUE); track_id is the FK target for surveys/properties/aliases. Kept separate so the hash key can change without breaking links (Fred's call). NOT changing track_id now. **⛔ DELETE-AND-RESYNC IS OFF THE TABLE** — cascade-deletes user surveys/aliases (non-reproducible).

**THE LESSON OF 06-28 (burn it in):** NO SILENT PROCESSES (above). Also: INSTRUMENT FIRST — we wasted an hour reverse-engineering sync from GPX dumps; the moment we added per-file disk logging, the answer was immediate (logic was fine; it was running loose + concurrent). When behavior is murky, add logging at the decision points and rebuild BEFORE theorizing. And: DO THE BLAST-RADIUS/WHERE-USED CHECK *BEFORE* the build, and BATCH all related changes into ONE build (we burned a 43-min cold build by not folding import in).

**CARRY-FORWARD LESSONS:** 06-25 when X fails and Y succeeds on the SAME input, the bug is in X's path — use differential tests. 06-24 get the runtime fact early; in-app Settings build date is STALE (verify deploy via APK-grep). 06-21 believe the device; 06-22 look at the DATA before changing dependent code. Commit working features SAME-DAY.

**AFTER track work + popup: lead-cart [2.1] rebuild** (authority docs `GroupTrack_LeadTrackReplacement_Spec.docx` + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md` — DO NOT re-derive).

### ✅ DONE 06-24 (committed; V2.5 SHIPPED — AAB LIVE on Play Store)
Four commits banked; AAB cut and uploaded; Play Store release LIVE:
- **`c3e6ca33f`** — HUD heading fix (`ConvoyViewModel.kt:906`, 1 line). All 3 HUDs showed raw `28954000°`; `pos.ground_track` is 1e-5 degree units, was assigned raw to `ConvoyNode.heading_deg`. Fix: `(((pos.ground_track ?: 0).toFloat() / 100000f) % 360f + 360f) % 360f`. Confirmed on device (reads 188°). Phone-GPS path (`ConvoyViewModel:828`, `loc.bearing`) was already correct.
- **`2b1ac101c`** — route resume building re-arm nonce + per-point auto-checkpoint (`ConvoyMapViewerScreen.kt` + `ConvoyRouteToolbar.kt`, 18 ins). (1) A resumed in-progress route could place points but Undo/Save did nothing (only Discard worked): the toolbar `building` flag (default true) is set false ONLY at `ConvoyRouteToolbar.kt:179` (Discard handler) and persisted across save→resume. Fix: the screen bumps `routeEntryNonce` on every route-mode entry; the toolbar re-arms `building=true; addArmed=true` via `LaunchedEffect(routeEntryNonce)` (`:91`). (2) After every `addVertex` (~427) the draft is upserted via `RouteDraftStore` (auto-checkpoint).
- **`d31202fcb`** — route-build popup gate + by-ID resume rederive + delayed redraw (3 files). See the two OPEN route bugs below — both fixes are deployed but NOT yet working as written; shipped as cosmetic known-issues.
- **`c603bc3f0`** — version footer v2.4 → v2.5 (`ConvoySettingsScreen.kt:254`). The footer hardcodes the GroupTrack version literal; only `BUILD_STAMP` flows from the build. Bump it by hand each release.

### ✅ DONE 06-23 (committed)
- **`a658d7a00`** — full V2.5 user manual + release notes shipped as in-app assets (4-level drill-down, real device screenshots, both maps, every launch point), compressed 85MB→3.2MB. 4 files in `app/src/main/assets/`. `?`→Full Manual loads them.
- **`63ea797ab`** — area search seeds the viewport frame so all four artifact types draw (`UnifiedSearch.kt`, 11 ins). Confirmed on device (NH→Utah). *(Targeted band-aid; the durable two-role write was deferred — see DESIGN CONTEXT.)*

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
- **THREE INDEPENDENT TRACK-CAPTURE PROCESSES (keep separate — redundancy is the point):** (1) **add a new track** (record-save) does its OWN inline insert + hash + rename; (2) **sync all existing tracks** walks my_tracks and reconciles; (3) **import a track from a foreign source** decomposes a GPX. None calls another. They share only the `insertTrackToDb` primitive (the DB-controlled write). If one has an issue, the others still capture tracks — nothing is lost.
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

## 🟢 OPEN — REMOVE ROLLBACK ENTIRELY (Undo covers it) — decided 06-24, NOT yet done

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

## 🎯 TRACK SYNC — improvements (V2.6, lower priority; only the silent-failure gap is worth a quick fix)

> Track-sync code is functional (works on Fred's device). The tester scare was the DB delete-gate, not sync. But sync has real gaps surfaced 06-24.

`SpatialDbManager.syncTracksFromFiles` (~479) walks `my_tracks/*.gpx`, parses each with its own `parseGpxTrackPoints`, name-dedups (`SELECT name FROM tracks`; skip if present), generates a fresh UUID, `INSERT OR IGNORE`. It does NOT split grouped/onX files (only IMPORT splits), has NO update/reconcile (insert-only), and can fail SILENTLY (the tester got no toast).
- **(a) Quick win:** make sync ALWAYS emit a result toast on EVERY path (N synced / 0 files found / error). The "no feedback" was the real actionable gap.
- **(b)** route sync through the same import/split pipeline (or detect multi-track/onX files and split) so sync == import.
- **(c)** upsert + robust identity (geom_hash like trails' `findTrailIdByHash`, vs fragile name-dedup).

Track storage (confirmed 06-24): ONE store. `my_tracks` GPX files (`/sdcard/Documents/my_tracks`) = source of truth. Spatial `tracks` table (type='TRACK') = geometry rows; needs valid bbox to appear in filters/maps. Extension `grouptrack_data.db` has `track_properties` + `track_surveys` (by id), NO tracks table. IMPORT splits onX-grouped files → individual GPX + inserts via `insertTrackToDb`.

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
- **⭐ INCLUDE: per-artifact bbox download (maps follow the artifacts)** — the import/route-driven per-track-bbox tile download (see "MAPS FOLLOW THE ARTIFACTS" in OTHER OPEN) is an OPTION of this redesign. Placeholder for now, but the batch/queue redesign must accommodate spawning per-artifact bbox requests (each track/trail/route bbox + ½ mile → its own download request). Don't design the batch system without this consumer in mind.
- **Batch tile transfer** — Esri batch/bundle instead of per-tile `{z}/{x}/{y}`. Formats: **.tpkx** (Esri-native Map Tile Package) and **PMTiles** (open single-file, HTTP-range-readable). Collapse hundreds of per-tile requests into few bundle transfers → faster + stable (sidesteps concurrency crash) + threshold-economical. Needs an on-device unpacker into the Leaflet `{z}/{x}/{y}` cache; .tpkx readable via GDAL `esric` driver as reference.
- **AWS staged/hosted** — EC2 caches tiles by bbox (30-day TTL), pre-assembles a tile_manifest per area (`map_status=READY`), two-tier dedup (manifest − local_cache = delta), parallel delta pull (4 threads). First-occurrence-wins protects Esri threshold. EC2 also merges trail geometry into one GeoJSON by bound_hash.
- **Esri developer account / thresholds** — FREE Esri dev account, API-level access + monthly thresholds (~2M tiles/mo free tier referenced). Metering model open. **Account terms live only in Fred's recollection — capture as provided; do NOT reconstruct.**
- **Download-crash-past-3-concurrent** — root-cause in `ConvoyTileDownloader` concurrent path (likely resource exhaustion). Gates safe high concurrency for ANY approach.

---

## OTHER OPEN (backlog / 2.6)

- **⭐ MAPS FOLLOW THE ARTIFACTS — per-artifact bbox tile download (V2.6, Fred 06-28).** PRINCIPLE: whatever you bring to the field (imported tracks, trails, or a route planned online) brings its supporting map tiles with it — same bbox design for all three artifact types. Plan a route on wifi → the tiles to support it download with it. SPEC (keep simple, ship, then re-evaluate): for each artifact, take its bbox, expand ~½ mile each side, queue a tile-download request into the download queue. **19-track GPX → 19 separate per-track bbox requests** (NOT a union bbox). RATIONALE: Fred's field experience — riders download ~120GB of map data for terrain they rarely ride; a union/region box wastes enormous tile area in the gaps between thin trail corridors. Per-track tight boxes download only the corridors actually ridden. DOUBLE SAVING (Fred 06-28): it's not just empty gaps — OHV riding is backcountry (desert/forest/mountain), which is TILE-LIGHT (few structures/features). The DATA-DENSE tiles are cities/developed communities (building footprints, street grids) — exactly the terrain riders go AROUND, not through. A region bbox clips the edges of towns and sweeps up those heavy urban tiles (a few dense city tiles can outweigh hundreds of backcountry tiles). Per-track corridors thread BETWEEN the developed areas and stay in sparse terrain → less area AND lighter tiles. The 120GB is likely dominated by developed-area tiles the big download boxes incidentally swept up, not the trail miles themselves. APPROACH: ship the per-track-bbox version, then MEASURE actual GB pulled vs the 120GB baseline before adding complexity. Possible later refinement (only if still heavy): polyline-buffer corridor instead of bbox (drops empty bbox corners for diagonal/curvy tracks) — NOT now, don't overcomplicate. OPEN (defer until after first measurement): zoom-level range (biggest volume lever — likely part of the over-download problem); ½-mile expansion math (lat constant ~0.0072°, lon scales by cos(lat)); whether to spawn into the CURRENT per-tile queue or gate on the V2.6 batch-download redesign (crash-past-3-concurrent risk — see TILE DOWNLOAD SPEED). Must obey NO-SILENT-PROCESSES: show "queuing N tile downloads for imported tracks," visible in the queue panel. Likely opt-in toggle at import/route-save (don't auto-pull GB on cellular).

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
- **LINE ENDINGS:** TrailImporter.kt / ConvoyTrailSourceScreen.kt / SpatialDbManager.kt = LF; ConvoyMapViewerScreen.kt + ConvoyRouteToolbar.kt + ConvoySettingsScreen.kt + both HTMLs = CRLF; ConvoyViewModel.kt + RouteDraftStore.kt = LF. RouteManager.kt = check before patching. Commit only named files, never `git add .`.

## EOD DOCS — status

- This checklist → **06-28 EOD** (create + sync fixes BUILT & sync VERIFIED working via disk log; real problem = sync runs loose/silent off the map lifecycle; 06-29 plan = build a SYNC CONTROL SCREEN + visible import/create, test all three, then the route-planning popup. STANDING RULE: no silent processes.).
- Handoff → pairs with this checklist; the prior `GroupTrack_NEXT_SESSION_HANDOFF_2026-06-23.md` is superseded by the V2.5-shipped state captured here.
- Manual → `app/src/main/assets/grouptrack_manual.html` (committed `a658d7a00`). Masters (NOT committed, archive on Drive/G:): `grouptrack_manual_DRILLDOWN_2026-06-23.html` (~82MB), `grouptrack_manual_LIVE_2026-06-23.html` (~87MB).
- Release notes → `app/src/main/assets/grouptrack_release_notes.html` (committed). Install-as-update warning preserved.
- Lead-track authority → `GroupTrack_LeadTrackReplacement_Spec.docx` + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **V2.5 AAB cut, uploaded, and LIVE on Play Store. HEAD = `c603bc3f0`. Nothing left uncommitted except the experimental `patch_resume_diag_log` (not in tree).**
