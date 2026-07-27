# TURNOVER — end of 2026-06-01 (read first next week)

Quick orientation for picking up next week. Full detail is in the other 06-01 docs (handoff, addendums, results, STATE_OF_PLAY). This is the short "where are we / what's committed / what's next" card.

## COMMITTED THIS SESSION (in git, branch feature/convoy-event-ride)
- **3339839f4** — P1 DB migration (regenerate-not-migrate, v3 schema, delete-gate). Earlier session.
- **eaf8508c1** — P2 dedup add-core + import recap. 4 files (SpatialDbManager.kt, TrailImporter.kt, schema_spatial_v3.sql, schema_extension_v3.sql), 319 ins / 305 del.
- Branch is ahead of origin by 2 commits — **NOT pushed yet** (push when ready: `git push`).

## WHAT'S DONE & PROVEN
- Dedup core: geom_hash (SHA-256 raw WKT) identity, composite (artifact_type, geom_hash) key, per-type UNIQUE(geom_hash), pointer-model aliases, track creation_date alias dedup, 'Not Named' fallback, one shared add-core all four artifacts funnel through. PROVEN on Droid 1 (clean install) AND Droid 2 (real v1->v3 upgrade): zero collisions, 7 aliases consistent across both devices, track cross-file dupes collapse correctly.
- Import recap: trail import now reports real buckets (inserted/dropped/aliased/skipped/rejected/errors). Droid 2 run: totals PERFECT, 7 aliases, 2 out-of-region. PROVEN.

## IMMEDIATE NEXT STEPS (next session)
1. **Push** the 2 local commits if not already (`git push`).
2. Per the SEQUENCE DECISION: **ROUTE PLANNING first (2-day pass)**, then AWS mirror + cleanup. (Routes = point-to-point snap-2 on trails+tracks — see NEXT_SESSION_HANDOFF_aws_and_routes.)

## OPEN ITEMS CARRIED (not blocking)
- **Track-import recap** = follow-up (track path collapses via INSERT OR IGNORE not the decision enum; insertTrackToDb would need to report inserted-vs-collapsed). Trail recap is done; covers the two big batches.
- **PORT tasks** (good build-window work, see ADDENDUM_convoy_waypoint_and_queues): convoy-map waypoint drop (port from planning map); QUEUES button on convoy (dead — port planning wiring to both convoy interfaces 494/622).
- **Esri tile store-and-forward** idea (see ADDENDUM_esri_tile...): pull-once-cache-on-AWS; gating Q = Esri ToS on re-hosting. AWS-session scope.
- **Tile storage / media-scan** REAL fix: sat tiles in a media-scanned path get re-indexed every boot and brick the device under storage stress (caused all the device lockups 06-01). Move tiles under .nomedia or app-private Android/data.
- **beginDedupSession** optimization: load only the type(s) being imported (type-scoped) so track-only imports don't load the 49K trail map. Track imports are slow (SHA-256 over long WKT). Not urgent.
- **recommit12** was being run to prune excess docs from the directory — glance before it prunes; docs_BACKUP exists. Untracked local files correctly NOT committed: docs_BACKUP_2026-05-31/, spatial_BACKUP.db, spatial_deduped.db, spatial_work.db.

## NON-BUGS (do not re-chase)
- Gate delete-authority "bug" = test-rig artifact only (force-installed over a foreign-owned DB). Confirmed: normal v1->v3 upgrade on Droid 2's own DB worked fine.
- Map-centering-on-default = old location permission until reboot; self-resolves on reboot.

## SESSION DOCS (all in GroupTrack_docs, dated 2026-06-01)
STATE_OF_PLAY_2026-06-01_EOD · NEXT_SESSION_HANDOFF_aws_and_routes · ADDENDUM_esri_tile_store_and_forward · ADDENDUM_convoy_waypoint_and_queues · RESULTS_2026-06-01_droid2_recap_SUCCESS · this TURNOVER. Authoritative tracker remains v25_master_checklist.md (Section K/L = DB design).
