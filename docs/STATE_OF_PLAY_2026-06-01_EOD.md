# STATE OF PLAY — 2026-06-01 EOD (read first next session)

Living status. Surgical-append only (Working Agreement rule 5). Older STATE_OF_PLAY copies (05-31, 05-31_v2) are superseded by this file; retire them when convenient. Authoritative tracker remains v25_master_checklist.md (Section K/L = DB design).

## SEQUENCE DECISION (made 2026-06-01)
Two days left for a first PASS at ROUTE PLANNING. Remaining big items: (a) route planning, (b) AWS DB upgrade/mirror, (c) general cleanup.
**DECISION: do ROUTE PLANNING FIRST, then AWS mirror + cleanup.** Rationale: route planning is the uncertain/new-code work (snap-2) and needs the runway — front-load the unknowns. AWS mirror is lower-risk and well-defined (mirror the already-proven local v3 schema to MySQL analogues) and does NOT block routes (route creation writes to the LOCAL routes table, not AWS). Route planning also exercises the dedup core's composite (artifact_type, geom_hash) key under real use (snap-2 routes share geometry with trails) — best validated now while fresh. Scope honestly: two days = a PASS (core snap-2 create-route flow writing to routes table), not full polish. Define the minimum-win before starting day one.

## DB DEDUP — DONE THIS SESSION (P1 + P2 core)
- **P1 (committed 3339839f4):** regenerate-not-migrate, v3 schema, one-time delete-gate with sidecar sweep. Proven on-device earlier.
- **P2 dedup add-core — BUILT, APPLIED, BUILDS CLEAN, PROVEN ON-DEVICE (Droid 1). NOT YET COMMITTED.**
  - patch_v25_db_dedup_core_v1.py (supersedes patch_v25_db_schema_revision_v1.py — do NOT run that one). Touched both schema assets + SpatialDbManager.kt + TrailImporter.kt.
  - Identity model: geom_hash = SHA-256 of raw WKT (no normalization — real dupes are byte-identical). Key = composite (artifact_type, geom_hash); per-type tables use UNIQUE(geom_hash); geom_hash NOT NULL (bad-caller backstop). Aliases are POINTERS (artifact_type, artifact_id, alias) — hash lives on the artifact only. Track aliases dedup on (artifact_type, geom_hash, creation_date) to collapse same-day group rides. Name fallback 'Not Named' in the add function. All four artifacts funnel through ONE shared add-core in SpatialDbManager. Rules in code (not JSON) since a rebuild is needed regardless.
  - PROVEN on Droid 1: trails imported with every row hashed (null_hash=0), zero geom_hash collisions; 7 trail aliases created including 'Equestrian Cg' (the real same-geometry-different-name case). 67 tracks across 3 markup-file imports — cross-file duplicate tracks collapsed correctly (markup files legitimately contained dupes; that's why they were imported).

## IMPORT RECAP — DONE THIS SESSION (just built, build running at session pause)
- patch_v25_import_recap_v1.py — applied, self-tested, BUILD RUNNING.
- Makes the TRAIL import (importFromSource) report the real breakdown instead of burying drop/alias/skip in one "skipped" bucket. insertFeature now returns inserted/dropped/aliased distinctly (off the AddDecision enum); counters tally each; ImportResult + ImportProgress carry new fields; completion message = "N processed: X new, Y dupes dropped, Z aliased, S already-imported, R out-of-area, E errors". logIngestion/writeTrailAreaJson unchanged signatures (get dropped+aliased as combined dupe count).
- SCOPE NOTE: covers TRAIL imports (the two big batches). TRACK import collapses via INSERT OR IGNORE not the decision enum, so track recap breakdown is a FOLLOW-UP (would need insertTrackToDb to report inserted-vs-collapsed).

## NON-BUGS CONFIRMED (do not chase)
- **Gate delete-authority "bug" is a TEST-RIG ARTIFACT, not real.** It only appeared because we force-installed 3.0 over a FOREIGN-OWNED 1.0 DB the fixtures pushed into public storage. A real installed app owns its files; a real user never has a foreign-owned DB. Normal installs/upgrades clear their own DB fine. DROPPED from the bug list.
- **Map centering on Vegas/US default** was the OLD location permission in effect until reboot — self-resolves on reboot. Not a code bug. (convoy_map.html line 281 has the default; GPS centering exists at lines 193-202.)

## REAL FOLLOW-UPS (independent of dedup)
- **Track import recap breakdown** (above scope note) — make insertTrackToDb report inserted-vs-collapsed.
- **Tile storage / media-scan**: sat tiles in a media-scanned path → hundreds of thousands of files re-indexed every boot → bricks the device under storage stress. Fix: move tiles under a .nomedia dir or into app-private Android/data (not media-scanned). This is what caused all the device lockups this session.
- **beginDedupSession optimization**: track-only import still loads the 49K trail hash map it doesn't need (dedup is type-scoped). Load only the type(s) being imported. Not urgent; track imports are slow mainly due to SHA-256 over long track WKT.

## DEVICE / TOOLING LESSONS (this session)
- pm clear and adb uninstall both FAIL on the release build (DELETE_FAILED_INTERNAL_ERROR). The gate won't re-fire if db_schema_marker is already 3 (from a prior P1 test). Manual DB deletes over adb/MTP HANG due to the tile-index thrash. Reliable resets: reboot device + `adb kill-server`/`adb start-server` (or `taskkill //F //IM adb.exe`). A clean factory-reset device is the easiest dedup-proof rig.
- APK path: app/build/outputs/apk/google/release/app-google-release.apk (google/release as SEPARATE dirs). Install from repo root: `adb -s <serial> install -r -d <path>`.
- Kotlin-touching builds take ~32 min; asset-only ~quick. Pre-existing non-gating lintVital ServiceKeepAlive error is expected, not a failure.

## NEXT ACTIONS (in order)
1. (Build finishing) Confirm BUILD SUCCESSFUL for the recap patch.
2. Droid 2 (has its OWN 1.0 DB — normal clear works): capture v1 before-state, install new build, launch+grant+map (upgrades to revised-v3 on its own owned DB), run FULL import, watch the TRAIL recap report the real breakdown in one pass. (PENDING — RESULTS TO APPEND HERE.)
3. COMMIT P2 dedup core + the recap patch once Droid 2 confirms.
4. Then per the sequence decision: ROUTE PLANNING pass (2 days) → AWS mirror + cleanup.

## DROID 2 FULL-IMPORT RESULTS (append after run)
- (pending)
