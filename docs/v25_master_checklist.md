# GroupTrack — V2.5 Master Checklist

_Rebuilt 2026-05-31, Section K+L finalized 2026-06-01. Living spine. Legend: DONE=confirmed · PARTIAL=partly wired · OPEN=not done · VERIFY=check against live source · FLAW=defect found in testing on a completed feature._

## A. V2.5 Map & Data Infrastructure (original placeholders)
- **[x] DONE** `SP10` · Tile Download System — ConvoyTileDownloader.downloadTiles(), background download w/ progress. _Fixed May 21 (Esri URL, slot filter, HTTP logging). 10 xrefs — wired._
- **[?] VERIFY** `SP11` · Planning Map Launch Integration — independent Map Manager route; state isolation from convoy; entry modes (planning/import/trailhead). _Planning map runs. State-isolation = May-31 map-independence work — likely satisfied. VERIFY entry modes wired._
- **[~] PARTIAL** `SP12` · Import Directory Scanner — scanImportDirectory(), GPX/KML via ACTION_OPEN_DOCUMENT, validation, metadata. _Tracks import works (6 xrefs). Waypoint/route GPX import still OPEN (see C)._
  - **↳ [FLAW]** Dupe-identification RULES undefined. May 30: 3 source imports (UGRC/BLM/USFS) tripled trails to 82,220. CANONICAL RULE: duplicate = same (name, geometry). NOT name alone (21K 'null'-named). NOT geometry alone (agency rounding). **→ RESOLVED by Section K.**
  - **↳ [FLAW]** No CLEAN PROCESS enforcing the rule after import. **→ RESOLVED by Section K (regenerate-not-migrate).**
  - **↳ [FLAW]** No DB-LEVEL guard. **→ RESOLVED by Section K (UNIQUE(name, geom_hash) + INSERT OR IGNORE in shipped v3 schema).**
  - **↳ [FLAW]** Add source_id (TrailArchitecture_v2 spec; actual table has none). **→ in Section K schema changes.**
  - **↳ [FLAW]** 'null' trail naming (21K rows). Fallback carto_code / 'Unnamed @ lat,lon'. **→ in Section K.**
- **[ ] OPEN** `SP13` · Route Planning Tools Integration — auto-set map_bounds; connect to ride-creation. PREMIUM. _Route CREATION is lead item (E); ride-creation wiring downstream._
- **[ ] OPEN** `SP14` · Trailhead Selection Integration — map picker modal; save to trailhead_waypoint_id. _0 xrefs → unwired. Ride-form auto-populate depends on this._

## B. Lazy-Load Display (viewport query → map)
- **[x] DONE** Trail / Track / Waypoint / Route lazy load — viewport query + display. _All four wired & verified. 0 routes until creation ships._

## C. GPX Import Expansion
- **[x] DONE** Import tracks (existing). _Verified working._
- **[ ] OPEN** Import waypoints from GPX. _Ties to waypoint import remap (foreign type → 12 canonical)._
- **[ ] OPEN** Import routes from GPX.
- **[?] VERIFY** Import sample test data for all 3 types.
- **[ ] OPEN** GPX/KML import handler: intent reaches activity but handler not executing.
- **[ ] OPEN** Large GPX >32MB: regex → string-loop (catastrophic backtracking).

## D. Work with Artifacts UI
- **[x] DONE** Trail/Track/Waypoint/Route toggle → viewport query → display. _ON/OFF/SELECTED both maps after May-31 filter fix._
- **[x] DONE** Per-type select/edit list. _ArtifactListPanel + queryArtifactList; SELECTED fixed May 31._
- **[?] VERIFY** Track maintenance screen (exists — verify on device).
- **[ ] OPEN** Trail maintenance screen (title click).
- **[ ] OPEN** Route maintenance screen.
- **[ ] OPEN** Waypoint maintenance screen. _rename/delete/changeType exist; dedicated screen may not be wired — VERIFY._
- **[ ] OPEN** Settings filter table (CartoCode / motorized / type filtering).
- **[ ] OPEN** Trail/Route DETAIL via SELECT/Edit list (not map-tap).

## E. Route Creation — point-to-point, SNAP-2 (tester-chosen)
> Testers surveyed on BOTH; chose point-to-point + snap-2 over freehand. Supersedes May-29 freehand notes. Do NOT revert to freehand. **Rides on the Section K dedupe foundation (snap targets must be clean).**
- **[ ] OPEN** Point-to-point route line with SNAP-2. Snap references = TRAILS and TRACKS (snap placed vertex to nearest point on trail/track within radius). _Radius tune-by-testing; consider hover/preview._
- **[ ] OPEN** Snap priority + fallback: trail-first vs track-first when both in range; nearest-point-on-line snap.
- **[ ] OPEN** Build WKT LINESTRING from snapped vertices → bbox → insertRoute → re-fire → draw (gold dashed wired). _insertRoute exists (4 xrefs)._
- **[ ] OPEN** +ROUTE button nav wiring on artifacts panel.
- **[ ] OPEN** Parity across 3 interfaces (convoy 494, convoy 622, planning 391); both HTML; diff after.

## F. Cleanups & Carried Bugs
- **[ ] OPEN** QUEUES button (convoy) DEAD — PORT planning wiring to convoy, not build new. Lands in both convoy interfaces (494/622). Same row as +/- zoom + north; watch double-accordion; don't cover NET/LOCAL.
- **[ ] OPEN** trailSourceCount hardcoded 0 (area-import bug).
- **[ ] OPEN** Area trail import API fetch hangs — needs timeout + error handling.
- **[ ] OPEN** Trail-type filtering on ArcGIS queries (exclude non-trail features at import).
- **[ ] OPEN** Planning Map blank on return from trail source screen.
- **[ ] OPEN** z12 hide-features: min display zoom = 12 all four types.
- **[ ] OPEN** SpatialDisplayManager: wire both maps to one processArtifact, delete inline copies (Phase 1); inject shared JS (Phase 2). Align convoy onSetState clear-on-leave-SELECTED.
- **[ ] OPEN** Waypoint marker shape DECISION: triangle (orig) vs round pin (shipped).
- **[ ] OPEN** Long-press waypoint drop must fire only on empty map, not node markers.
- **[ ] OPEN** Bounding-box query-source restore (persist + restore before first query, both HTML). _SPEC, not enhancement._
- **[ ] OPEN** Remove GPX prompt Y/N after import; remove old 'Work with Tracks'; remove node persistence.
- **[ ] OPEN** Remove METHOD_SELECT / B1_DRAW_AREA remnants (verify dead via xref first).
- **[ ] OPEN** Track survey on stop (name + difficulty + share). _Unshipped 2.5 feature._
- **[ ] OPEN** Direction arrows on track/trail lines.
- **[ ] OPEN** Dead-code sweep: scanDownloadsForGpx (1 xref), showImportList orphans.
- **[ ] OPEN** Duplicate AlertDialog import ConvoyScreen.kt (34 & 85) — tidy.
- **[ ] OPEN** !!/safe-call warning tidy.
- **[ ] OPEN** Verify created waypoints survive force-stop/reopen.
- **[ ] OPEN** LEAD-TRACK RECORDING REPLACEMENT (spec'd; implement AFTER routes + planning cleanup). Replace 3-flow pipeline with one lead-position polyline + drawTrack, gated on lead nodeId. See lead_track_replacement.md.
  - **↳ DISCOVERY (open):** identify EXACTLY what old code is removed before cutting. Anchored sites in field_crossref. Trace each live + run TRACK-DBG 2-cart capture.

## G. Google Play / ANR (launch gate)
- **[ ] OPEN** ANR #2 osmdroid tile-cache scan: ~60GB on main thread at onCreate (20-43s freeze P10_T). Disable osmdroid cache trimming. MUST resolve before launch.
- **[ ] OPEN** ANR #1 MANAGE_EXTERNAL_STORAGE startup blocks main thread — defer file-dependent init until permission confirmed.
- **[?] VERIFY** ANR Type 2 Input Dispatching Timeout. May share osmdroid root cause.
- **[x] DONE** Package rename to com.grouptrack.android.
- **[ ] OPEN** About/Attribution screen (GPL/Leaflet/Esri); Play Console; AAB version > 29320573; lintVital ServiceKeepAlive tidy.

## H. Website (staged)
- **[ ] OPEN** Deploy V2.5: edited index.html + Release Notes + User Guide PDFs. Snapshot index.html first. Retire 'selections carry between maps' known-issue (fixed).

## I. Closing 2.5 → First 2.6 — Dead-Code Quarantine
> Run at 2.5 release. Standalone functions with ZERO live refs AND no AllDocs mention. Quarantine reversibly, never hard-delete; log source.
- **[ ] OPEN** Inventory orphans via xref.
- **[ ] OPEN** Quarantine reversibly; log source location.
- **[ ] OPEN** Candidates: scanDownloadsForGpx, showImportList orphans, SpatialDisplayManager dead bindings, METHOD_SELECT/B1_DRAW_AREA remnants.

## J. First-Launch Release-Notes Gate (NEW)
> Every launch, in-app PDF viewer of V2.5 Release Notes, checkbox 'I have read and acknowledge…' enables acknowledge button. No persisted flag.
- **[ ] OPEN** Build in-app PDF viewer screen (PdfRenderer or lib).
- **[ ] OPEN** Gate app entry every launch: checkbox → enable acknowledge → proceed.
- **[ ] OPEN** ANR-SAFETY: load/render PDF OFF main thread, show gate AFTER heavy init.
- **[ ] OPEN** Bundle current Release Notes PDF as asset; decide update path per release.

## K. DB REVISION — Dedupe Foundation (regenerate-not-migrate) — FINALIZED 2026-05-31
> Resolves the SP12 FLAW block. **goal-2 DB-revisions — the foundation route creation rides on.**

### Decided design (do NOT re-litigate)
- **Regenerate, do NOT migrate in place.** All DB data is derived/regenerable — trails + trailhead waypoints from trail-source re-import; tracks from track-sync (GPX on disk); aliases start empty; no routes (route code unshipped); no user waypoints (long-press creation committed to branch, PLANNING MAP ONLY, 2 dev devices, NOT distributed); no surveys (unshipped). **Nothing authored exists to lose.**
- **Schema ships in the APK** (`schema_spatial_*.sql` / `schema_extension_*.sql` assets, applied by `runSchemaFromAsset` in `SpatialDbManager.init()` when no DB present). Migration = delete old DB files → `init()` recreates fresh from shipped schema → repopulate.
- **v2 schema asset MUST be replaced by v3 asset.** Current shipping asset builds v2. v3 asset must replace it so post-delete recreation builds v3 (UNIQUE, geom_hash, dated aliases). If old asset left in place, recreation rebuilds v2 and gate loops forever. **Required edit.**
- **One-time delete** at first launch after update, triggered by stored SharedPreferences version marker (stored < 3 → delete both DB files, update marker). Folded into TOP of `SpatialDbManager.init()` (after `if (initialized) return`, before `dbDir()`): 21 lazy init() call sites, NO Application subclass, so init() is the only funnel. Delete = `File(dbDir(), SPATIAL_DB).delete()` + EXTENSION_DB, result logged.
- **REJECTED alternatives (do not reopen):** external .sql script — devices have no sqlite3. Shell-file-by-email — Android users have no shell. Install-conditioning — PackageManager has no data-inspection hook. App deleting its own file IS the in-app form of the `rm`.
- **DBs in PUBLIC shared storage** (/sdcard/Documents/GroupTrack/), NOT sandbox. System never auto-wipes (survive uninstall + clear-data). In-app delete = SOLE clearing mechanism, load-bearing. Bonus: `-r -d` reinstall preserves DB, so dev loop rehearses the real remote upgrade path. Verify `.delete()` returns true on Android 14/16.

### Schema changes (baked into v3 CREATE TABLE — no ALTER)
- trails + routes: add `geom_hash` (normalized WKT); `UNIQUE(name, geom_hash)` + INSERT OR IGNORE.
- trails: add `source_id` (spec'd, not implemented). null-name → carto_code / 'Unnamed @lat,lon' fallback.
- `artifact_aliases` (ext DB, already exists w/ is_preferred): add `alias_date` (UTC day) + `geom_hash`; `UNIQUE(artifact_type, geom_hash, alias_date)` = one alias/geom/UTC-day. PRESERVE views v_preferred_aliases / v_trail_display / v_track_display.
- `proximity_config` already seeded (waypoint 100m, trail 80%, track 70%, route 85%) — use these.

### Dedupe rule (unified outcome; per-type detection)
- Same name + same geom → REMOVE. New name + same geom → ALIAS (dated). "My data is mine; everyone else's is an alias."
- Detection: trails on (name, geom); tracks geometry→alias; waypoints proximity→alias; routes geometry→alias (local + AWS first-occurrence). All four defined.
- Enforcement = APP-LOGIC during repopulate (remove vs alias per type), UNIQUE as backstop. AliasManager Pass 2 holds per-type logic; ConvoyArtifactOps.addAlias wired through. Guards at insertWaypoint(WithId), insertRoute, insertTrackToDb.

### init() migration-mechanism conflict to resolve in patch
- init() runs inline ALTER migrations (v2 tracks, v3 type/wpt-bbox, v4 carto_code) but NEVER updates schema_version. applyMigrationIfNeeded (TrailImporter) reads schema_version, sees >=2, skips. Two mechanisms disagree. UNIFY into the regenerate path.

### Repopulate entry points (confirmed via grep)
- Tracks: `SpatialDbManager.syncTracksFromFiles(context)` (rebuilds from GPX). Also `ConvoyTrackImportScreen` RESYNC TRACKS button (219/237), `ConvoyMapViewerScreen` RESYNC button (982/997).
- Trails + trailheads: `TrailImporter.importTrailheads(context, …)` (459) + trail-source import.

### TEST HARNESS — golden v2 fixtures (PREREQ before patch; restore before each upgrade test)
> Once a device rebuilds to v3 it can't re-test v2→v3 (gate skips). Restore a known v2 DB before each run.
```
# SAVE golden v2 fixtures (BEFORE the patch changes anything):
mkdir -p /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden
cp /c/Users/kixaz/Downloads/grouptrack_spatial.db /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 exec-out "cat /sdcard/Documents/GroupTrack/grouptrack_data.db" > /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_data.db

# RESTORE before each upgrade test:
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 push /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_spatial.db /sdcard/Documents/GroupTrack/grouptrack_spatial.db
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 push /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_data.db /sdcard/Documents/GroupTrack/grouptrack_data.db
```
- Loop: restore golden v2 → install -r -d (NO uninstall, preserves DB) → launch → gate <v3 → delete → init recreates EMPTY v3 → repopulate → verify v3 schema + dedupe + empty-before-repopulate in logcat → restore + repeat. Many cycles on APK/AAB tester builds; bulletproof before Play Store AAB.

### CLOSING TASK: mirror final v3 spatial schema to the EMPTY AWS MySQL as structural equivalent (MySQL type analogues: geometry_json LONGTEXT, DECIMAL lat/lon; same tables/cols incl. new alias tables). Local SQLite and AWS models must match structurally.

## L. DB REVISION — Correctness Invariants (late-session framing 2026-05-31)
> The WHY behind the structure — do not lose.

### Insert-boundary trigger = PERMANENT gatekeeper (not one-time cleanup)
- Install-regenerate cleans existing contamination ONCE. The trigger/guard at the insert path keeps the model correct FOREVER, because data keeps arriving: device→AWS, AWS→device, device→device.
- Every delivery hits another DB with its OWN primary names + alias set, in its OWN arrival order. Same incoming track evaluated FRESH against the RECEIVING db, at delivery time, on the receiving device:
  - same name + same geom here → DROP (true dup)
  - new name + same geom here → ALIAS (attach incoming; existing primary stays primary)
  - geom not here → INSERT as new primary
- Decision can only be made locally, at delivery, against that device's current data. No central scrub — there is no central truth. Rule fires on each device, every delivery, every channel.
- Two-layer enforcement: UNIQUE(name, geom_hash) structurally guarantees DROP can never create a dup regardless of channel (device-to-device, AWS pull, GPX import all funnel through guarded inserts); app-logic makes the new-name→ALIAS judgment. No back door.

### First-occurrence / local-primary correctness invariant (100 contributors)
- ~100 users feed AWS in different arrival sequences. Same physical track can have a DIFFERENT primary name in each DB — each recorded whoever arrived first THERE as primary, others as alias. THIS IS CORRECT, not a bug.
- INVARIANT: the set {primary + all aliases} must be IDENTICAL across DBs even when which one is flagged primary differs. Completeness of the name-set is the correctness test; which name is primary is local + arrival-order-dependent.
- Aliasing accumulates INWARD at insert: whichever side pulls a row, incoming name attaches as alias, local/first name stays primary. AWS is a first-occurrence store, NOT a forced mirror. Correct = no name ever lost; NOT = everyone shows same primary.
- artifact_aliases already carries artifact_type + artifact_id — binds alias to parent across all four types in one table. is_preferred = local-primary flag. Structure already supports the invariant; this task adds alias_date + geom_hash so accumulation (one alias/geom/UTC-day) + cross-DB matching (same geom → same alias set) are enforced structurally.

### Two verification points to PROVE in testing
1. geom_hash normalization is load-bearing. Same physical trail arriving with WKT differing by coordinate precision/rounding → different hashes → constraint misses the dup. Normalize (round coords to fixed precision) BEFORE hashing; test against REAL duplicate agency data.
2. Local↔AWS structural identity. A row must mean the same thing both sides. If local UNIQUE key ≠ AWS UNIQUE key, the two stores enforce different rules and the alias model breaks at sync. Must be structurally identical (MySQL type analogues aside). This is why the AWS-mirror closing task is correctness-critical, not cosmetic.

## M. Doc & Tooling Workflow (housekeeping)
- **Authoritative doc source = repo `~/Meshtastic-Android/docs/`** (231 files, committed nightly by recommit11) + `docs_BACKUP_2026-05-31/`. Drive `GroupTrack_docs/` is the WORKING MIRROR Claude reads/writes — disposable, not canonical. Today's scare touched only the mirror; nothing authoritative was at risk.
- Doc-save mechanics: Claude can only CREATE (no edit/overwrite/delete via connector). `textContent` works; base64 errored. To update: user deletes old file in Drive, Claude recreates — OR keep it simple since the repo is the real version store.
- **recommit11 revisit (deferred):** simplify its role given repo is authoritative; decide dated-dir vs flat. Do NOT over-engineer Drive versioning — repo already versions.
- **TODO restore from repo:** CATALOG.md, INDEX.md, canonical handoff (handoff_2026-05-31 16KB) did not return from trash; copy current versions from `~/Meshtastic-Android/docs/`.
- **Future (after patch):** add `v2.5_user_manual_draft.md` + keep release-notes draft in daily sync to simplify the release process (publish current drafts vs. scramble at end).