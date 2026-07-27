

## K. DB REVISION — Dedupe Foundation (regenerate-not-migrate) — FINALIZED 2026-05-31
> Resolves the SP12 FLAW block above. Approach decided after full framing this session. **This is goal-2 DB-revisions, the foundation route creation rides on.**

### Decided design (do NOT re-litigate)
- **Regenerate, do NOT migrate in place.** All DB data is derived/regenerable — trails + trailhead waypoints from trail-source re-import; tracks from track-sync (GPX on disk); aliases start empty; no routes (route code unshipped); no user waypoints (long-press creation committed to branch, PLANNING MAP ONLY, on the 2 dev devices, NOT distributed); no surveys (unshipped 2.5 feature). **Nothing authored exists to lose.**
- **Schema ships in the APK** (the `schema_spatial_*.sql` / `schema_extension_*.sql` assets, applied by `runSchemaFromAsset` in `SpatialDbManager.init()` when no DB present). The migration is: delete old DB files → `init()` recreates fresh from shipped schema → repopulate.
- **v2 schema asset MUST be replaced by v3 asset.** The asset currently shipping builds v2. v3 asset must replace it so post-delete recreation builds v3 (UNIQUE, geom_hash, dated aliases). If the old asset is left in place, recreation rebuilds v2 and the gate loops forever. **This is a required edit, not optional.**
- **One-time delete** at first launch after update, triggered by stored SharedPreferences version marker (stored < 3 → delete both DB files, update marker). Folded into TOP of `SpatialDbManager.init()` (after `if (initialized) return`, before `dbDir()`): 21 lazy init() call sites, NO Application subclass exists, so init() is the only single funnel. Delete = `File(dbDir(), SPATIAL_DB).delete()` + EXTENSION_DB, result logged.
- **Why not the alternatives (REJECTED, do not reopen):** external .sql script — devices have no sqlite3, only the app can run SQL. Shell-file-by-email — Android users have no shell; impossible for remote/Play Store users. Install-conditioning on schema version — PackageManager has no hook to inspect app data and refuse install. The app deleting its own file IS the in-app form of the `rm` you'd email.
- **DBs live in PUBLIC shared storage** (/sdcard/Documents/GroupTrack/), NOT app sandbox. System never auto-wipes them (survive uninstall + "clear data"). So the in-app delete is the SOLE clearing mechanism — load-bearing. Bonus: `-r -d` reinstall preserves the DB, so dev loop automatically rehearses the real remote upgrade path (can't accidentally test the easy clean-install path). Verify `.delete()` returns true on Android 14/16 (scoped-storage edge).

### Schema changes (baked into v3 CREATE TABLE — no ALTER)
- trails + routes: add `geom_hash` (hash of normalized WKT); `UNIQUE(name, geom_hash)` + INSERT OR IGNORE. (SQLite can't ALTER-add UNIQUE; rebuild-from-asset gives it free.)
- trails: add `source_id` (spec'd in TrailArchitecture_v2, never implemented). null-name → carto_code / 'Unnamed @lat,lon' fallback so unnamed trails dedupe on geometry.
- `artifact_aliases` (extension DB, already exists w/ is_preferred): add `alias_date` (UTC calendar day) + `geom_hash`; `UNIQUE(artifact_type, geom_hash, alias_date)` = one alias per geometry per UTC day. PRESERVE existing views v_preferred_aliases / v_trail_display / v_track_display.
- `proximity_config` already seeded (waypoint 100m, trail 80%, track 70%, route 85%) — detection thresholds are data-driven, use these.

### Dedupe rule (unified outcome; per-type detection)
- Same name + same geom → REMOVE (true dup). New name + same geom → ALIAS (dated). "My data is mine; everyone else's is an alias."
- Detection: trails collapse on (name, geom); tracks geometry→alias; waypoints proximity→alias; routes geometry→alias (local + AWS first-occurrence). All four defined.
- Enforcement = APP-LOGIC during repopulate (decides remove vs alias per type, then inserts), UNIQUE constraint as backstop. AliasManager Pass 2 holds the per-type logic; ConvoyArtifactOps.addAlias wired through. Guards at insertWaypoint(WithId), insertRoute, insertTrackToDb.

### init() migration-mechanism conflict to resolve in patch
- init() runs inline ALTER migrations (v2 tracks, v3 type/wpt-bbox, v4 carto_code) but NEVER updates schema_version. applyMigrationIfNeeded (TrailImporter) reads schema_version, sees >=2, skips. Two mechanisms disagree on what "v2" means. UNIFY into the single regenerate path; inline ALTERs become dormant safety net or fold in.

### TEST HARNESS — golden v2 fixtures (PREREQ before patch; restore before each upgrade test)
> Once a device rebuilds to v3 it can't re-test v2→v3 (gate skips). Must restore a known v2 DB before each run.
```
# SAVE golden v2 fixtures (do this BEFORE the patch changes anything):
mkdir -p /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden
cp /c/Users/kixaz/Downloads/grouptrack_spatial.db /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 exec-out "cat /sdcard/Documents/GroupTrack/grouptrack_data.db" > /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_data.db

# RESTORE before each upgrade test (push golden v2 back onto device):
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 push /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_spatial.db /sdcard/Documents/GroupTrack/grouptrack_spatial.db
MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 push /c/Users/kixaz/GroupTrack_test_fixtures/v2_golden/grouptrack_data.db /sdcard/Documents/GroupTrack/grouptrack_data.db
```
- Test loop: restore golden v2 → install -r -d (NO uninstall, preserves DB) → launch → gate sees <v3 → delete → init recreates EMPTY v3 → repopulate → verify v3 schema + dedupe + empty-before-repopulate in logcat → restore + repeat. Many cycles on APK/AAB tester builds as features complete; bulletproof before Play Store AAB release.

### CLOSING TASK (end of this work): mirror final v3 spatial schema to the EMPTY AWS MySQL as structural equivalent (MySQL type analogues: geometry_json LONGTEXT, DECIMAL lat/lon; same tables/cols incl. new alias tables). Local SQLite and AWS models must match structurally.
