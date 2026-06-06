# GroupTrack V2.5 — Master Planning Document
## Consolidated from May 9, 2026 Full Session
## All decisions, all criteria, development sequence TBD in AM session

---

## DOCUMENT INDEX — Files Produced This Session

| # | File | Purpose |
|---|------|---------|
| 1 | GroupTrack_V25_SpatialArchitecture_v1.docx | Architecture decisions — two databases, canvas independence, transfer queue |
| 2 | GroupTrack_V25_ActionPlan_v3.docx | Sequenced task plan — phases, hard cutoff, timeline |
| 3 | grouptrack_trails_schema_v1.sql | Trails database schema (public, free, read-only) |
| 4 | grouptrack_user_schema_v1.sql | User database schema (waypoints, routes, tracks, queue, source_ingestions) |
| 5 | GroupTrack_V25_TileSourceCatalog_v1.md | Original 12 default tile sources |
| 6 | GroupTrack_V25_MapSourceCatalog_v2.md | Complete 40-source catalog — all free, free-key, and paid sources |
| 7 | GroupTrack_V25_TrailSourceCatalog_v1.md | Trail data sources + dedup method + catalog JSON format |
| 8 | GroupTrack_V25_MapTileSourceReference_v1.md | URL mechanics, Leaflet integration, offline caching |
| 9 | GroupTrack_V25_SpatialImplementationGuide_v1.md | 17-section implementation guide with all processes |
| 10 | GroupTrack_V25_EntityLifecycle_v1.md | Complete lifecycle scenarios — ADD/CHANGE/DELETE/RENAME impacts |
| 11 | GroupTrack_V25_DataProtection_v1.md | Backup, change journal, rollback specification |
| 12 | build_trails_db_v1.py | Python ingestion proof-of-concept — 46,953 Utah trails proven |
| 13 | This document | Master consolidation — development sequence to be revised in AM |

---

## ARCHITECTURE SUMMARY

### Two databases on device:
- **grouptrack_trails.db** — public agency trail data. Free. Read-only after ingestion. Downloaded from agency sources on-demand per user selection. Viewport-queried for lazy loading to map.
- **grouptrack_user.spatialite** — rider's personal data. Waypoints, routes, tracks, ride areas, tile sources, transfer queue, change journal, backups. Read-write. Survives updates and uninstalls.

### Canvas independence:
- 40 cataloged tile sources (18 free/no-key, 12 free-key, 5+ paid)
- Three configurable map slots — user assigns any source+type combination
- Map bar headings derived from data, not hardcoded
- API keys entered by user, stored locally, never uploaded
- GroupTrack is out of the tile business entirely

### GroupTrack as awareness conduit:
- Not a data provider, not a curator, not a redistributor
- Catalog of public trail source URLs hosted at grouptrack.org/trails/catalog.json
- Nightly cron on EC2 checks agency sources for updates (HEAD requests only)
- Users download directly from agency sources, filter and ingest on-device
- Each device is unique to the rider's needs and selections

### Trail data model:
- User selects sources from catalog, applies filters (OHV, hiking, horse, etc.)
- Downloads full source GeoJSON from agency
- On-device ingestion: normalize, calculate bbox, dedup against existing data
- Lazy load to map via viewport queries — the viewport IS the subset
- Smart tile downloads guided by trail coverage — download tiles where trails exist

### Spatial data overlays:
- Blue fill = tiles downloaded (existing V2.4 behavior)
- Black diagonal stripes = waypoints, routes, tracks present
- Both visible on Planning Map and convoy map
- Management (create/remove) on Planning Map only

### Data protection — THREE layers:
- **Backup**: automatic on launch + before destructive ops. Last 5 retained. Manual backup anytime.
- **Change journal**: every INSERT/UPDATE/DELETE on user data. Before + after state as JSON. Geometry as WKT. 12 triggers across 4 tables. 30-day retention.
- **Rollback**: single-operation undo, transaction rollback, point-in-time rollback. Rollback creates backup first.
- Journal and recovery model scales to AWS when V3.0 collective activates

### Transfer queue:
- Every download and upload goes through queue
- Chunked into blocks based on viewport/zoom
- Background processing with progress visibility
- Drop-point resume on connectivity loss
- Priority-based (ride enrollment = 1, manual = 5)
- Prevents ANR on large area selections

---

## ENTITY RULES SUMMARY

### Tracks:
- Source of truth: GPX/KML file in my_tracks/
- DB record is spatial index (metadata + bbox), not a copy
- Operations: create, import, rename, delete (with confirmation), share, unshare, convert to route, smooth
- Share blocked if ride.private = true
- File sync on launch reconciles DB with filesystem

### Trails:
- Source of truth: grouptrack_trails.db
- Downloaded from agency sources, ingested on-device
- NEVER deleted individually — only removed by removing entire source/region
- Dedup on ingestion: bbox pre-filter → length filter → point proximity → classification
- Review screen for probable duplicates

### Waypoints:
- User-created via long-press on map
- 10 types: trailhead, fuel, gate, hazard, scenic, water, camp, parking, rally, other
- Operations: create, rename, type change, description edit, move (drag), delete (with confirmation), share, unshare, consolidate/replace
- Consolidation: retire one waypoint, transfer all references to replacement
- Map legend renders from waypoint_types table

### Routes:
- NEVER independently shared — distributed only through ride enrollment
- NEVER deleted individually if assigned to active ride with enrollments
- Operations: create (draw/import/convert from track), rename, edit geometry, delete, assign to ride, unassign
- Created by: drawing on map, snap-to-trail, converting a track, importing GPX/KML

### Ride Areas:
- Defined by viewport zoom (V2.5) or polygon draw (later refinement)
- has_tiles and has_spatial flags drive map overlays
- Deletion is organizational — does not cascade delete spatial data (V2.5)

---

## DATA ACCESS MODEL

### Routes are never independently public
- Shared only through ride invitations
- Organizer's intellectual property

### Tracks are personal until shared
- "Share this track?" prompt on save
- Suppressed for private rides (ride.private toggle)

### Rides have a private toggle
- Private ride = track sharing blocked for all participants
- Publication pipeline: personal → submitted → public

### Published content is always recoverable
- Shared to collective (V3.0), removed locally, re-downloadable anytime

---

## STANDING RULES

1. Never auto-delete user data. All deletes require explicit confirmation.
2. Never embed API keys in APK. User manages their own keys.
3. Never upload user data without explicit user action. Share is opt-in.
4. Offline-first. Every feature works without connectivity.
5. Canvas independence. Map tiles are the user's choice.
6. Cumulative collection. Downloads add data. Only explicit removal deletes.
7. Routes never independently public. Only through ride invitations.
8. Private rides block track sharing.
9. Published content always recoverable from collective (V3.0).
10. Trails and routes NEVER removed individually. Source/region removal only.
11. First source ingested wins auto-resolve for duplicates.
12. Reusability: Ops files (logic) and Dialogs files (UI).
13. Every user data change is journaled. No exceptions.
14. Backup before every destructive operation. Automatic.
15. Journal stores before AND after state with geometry as WKT.
16. Schema must be hardened before application code.
17. Lifecycle triggers designed and tested before features built.
18. Patch scripts uniquely versioned Python files. Never reuse names.
19. Never commit V3_FEATURES_ENABLED or V25_SPATIAL_ENABLED = true.
20. Preserve full trail geometry precision — V3.0 snap-to depends on it.

---

## BUILD APPROACH — TO BE REVISED IN AM SESSION

### Current proposed sequence (subject to revision):

**Pre-Step: Schema hardening**
- Confirm 9 lifecycle decisions
- Write complete trigger SQL (spatial + journal = ~24 triggers)
- Test against edge cases
- Harden schema before any application code

**Step 1: Data-drive tile sources**
- Replace hardcoded SAT/TOPO/TOPO+ with tile_sources table
- Map bar headings from data
- Source picker for slot assignment
- Reclaim long-press for waypoints, add RIDERS button

**Step 2: Data-drive trails and tracks**
- Ingest full trail sources into grouptrack_trails.db
- Viewport queries lazy load to map
- Replace GeoJSON asset loader entirely
- Track metadata indexing with bbox

**Step 3: Add waypoints**
- Long-press creation, type picker, icons, legend
- Consolidate/replace workflow
- Share prompt

**Step 4: Area management (later refinement)**
- Viewport zoom defines download areas
- Block-based queued downloads
- Smart first pull guided by trail coverage
- Manual override for additional areas

### Standalone app approach:
- Copy Map Viewer as starting point
- Own launcher for isolated testing
- Prove every component in isolation
- Fold proven code back into GroupTrack

### V2.4 coexistence:
- V25_SPATIAL_ENABLED flag gates new code
- Phase 1: V2.4 hotfixes still possible
- Hard cutoff at Phase 2: V2.4 frozen
- Lead track smoothing on convoy needs 2-3 weeks field testing
- Cutover at end: port to convoy, remove old code, rename to Planning Map

---

## V3.0 AWARENESS

- Subscription: $3.00/month Google Play + Apple IAP
- Ride engine: upload/download via ride enrollment
- Ride repository: public discovery, browse by area/date
- Cloud sync: tracks/routes/waypoints to web account
- Package migration: com.geeksville.mesh → com.grouptrack.android
- Journal and recovery model extends to AWS collective — scales with demand
- Map Manager Phase C: auto-tile-download, PROTECTED/PURGEABLE, storage dashboard
- Full snap-to: trail network routing, lead track snapping, GPS track smoothing (map-matching)

---

## PROOF OF CONCEPT RESULTS — May 9, 2026

- Full Utah state trail ingestion: 49,096 features → 46,953 trails
- 18,230 OHV/motorized, 26,224 multi-use, 2,499 hiking
- 41 counties, 91 agencies
- 107 MB raw database
- St George viewport test: 535 trails returned
- Python ingestion script proven (build_trails_db_v1.py)
- Ready for Kotlin port and on-device ingestion

---

## AM SESSION AGENDA

1. Review this master document
2. Confirm 9 lifecycle decisions (from EntityLifecycle doc section 9)
3. Revise development sequence based on all new criteria
4. Determine: schema hardening first vs parallel with standalone app setup
5. Begin execution

---

*GroupTrack V2.5 | Master Planning Document | May 9, 2026*
*Recommit all documents via v9. Revise sequence in AM.*
*Do not re-litigate settled decisions.*
