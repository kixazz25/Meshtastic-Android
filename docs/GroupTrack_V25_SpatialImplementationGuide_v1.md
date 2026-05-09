# GroupTrack V2.5 — Spatial Data Implementation Guide
## Complete Process Documentation | May 9, 2026
## If this conversation is lost, this document contains everything needed to continue.

---

## 1. GOAL

Build a spatial data system for GroupTrack that replaces the bundled 22MB Utah GeoJSON asset with a download-on-demand trail catalog, adds user-collected waypoints and tracks as spatial data, and establishes the foundation for V3.0 ride-based data sharing.

### What we are building:
- A trail download system where riders select sources, apply filters, and build a personalized local trails database on their device
- Waypoint capture (long-press on map) as the first user-created spatial data
- Track spatial indexing linking existing GPX/KML files to spatial queries
- A transfer queue that prevents ANR on large downloads
- Visual map overlays showing what data is downloaded where
- A standalone ingestion/testing app for curating trail sources

### What we are NOT building in V2.5:
- AWS spatial database or server-side spatial queries (V3.0)
- Upload/sharing infrastructure for the ride engine (V3.0, but schema is ready)
- Full snap-to routing along trail networks (V3.0, but simple snap-to is V2.5)
- Subscription paywall or premium features (V3.0)

### Strategic context:
- Free trails get riders in the door — competing with Gaia/onX at the entry point
- User spatial data (waypoints, routes, tracks) is GroupTrack's product value
- Canvas (map tiles) is the rider's choice — GroupTrack is out of the tile business
- The "share this track?" prompt in V2.5 seeds the V3.0 collective repository

---

## 2. ARCHITECTURE

### Two databases on device

**grouptrack_trails.db** (SQLite, NOT SpatiaLite)
- Location: /sdcard/Documents/GroupTrack/data/grouptrack_trails.db
- Content: official trail data from public agency sources (UGRC, BLM, USFS, state portals)
- Access: read-only after ingestion. Updated by re-ingesting sources.
- Built by: on-device ingestion from downloaded GeoJSON files
- Viewport queries: bounding box columns (min_lon, min_lat, max_lon, max_lat) with B-tree indexes
- FREE — this data is public domain, given away to all riders
- Schema file: grouptrack_trails_schema_v1.sql

**grouptrack_user.spatialite** (SpatiaLite)
- Location: /sdcard/Documents/GroupTrack/data/grouptrack_user.spatialite
- Content: waypoints, routes, tracks, ride areas, tile sources, transfer queue
- Access: read-write. Survives app updates and uninstalls (external storage).
- SUBSCRIPTION VALUE in V3.0 — sharing this data through the ride engine is premium
- Schema file: grouptrack_user_schema_v1.sql

### Why two databases:
- Trails are replaceable public data. User data is personal and precious.
- Trail database can be deleted and rebuilt from sources without losing user work.
- Different access patterns: trails = read-only viewport queries; user data = read-write with triggers.
- Clean separation of free (trails) and premium (user spatial data) value.

### Canvas independence:
- 12 default tile sources shipped (3 satellite, 3 topo, 3 road, 3 trail)
- Users add their own sources (CalTopo, Gaia, onX, Mapbox, MapTiler)
- API keys entered by user, stored locally, never uploaded
- If a tile source dies, user switches to another. GroupTrack unaffected.
- Tile source catalog in grouptrack_user.spatialite tile_sources table

### Map overlay visual patterns:
- Blue fill = tiles downloaded for this area (existing V2.4 behavior, unchanged)
- Black diagonal stripes = waypoints, routes, tracks present for this area (NEW in V2.5)
- Blue + black stripes = complete offline coverage
- One area outline serves both tile and spatial boundaries
- Both overlays visible on Planning Map AND convoy map (read-only on convoy)

---

## 3. TRAIL DATA SOURCES

### Verified sources:

**UGRC Utah Trails and Pathways** — PROVEN
- 49,096 features statewide, 46,953 after status filter
- Download: https://opendata.gis.utah.gov/datasets/3080c0a2859a4d23a279e17e17c703c8
- Feature service: https://services1.arcgis.com/99lidPhWCzftIe9K/ArcGIS/rest/services/TrailsAndPathways/FeatureServer/0
- OHV filter: MotorizedAllowed = "Yes" → 18,230 trails
- Key fields: PrimaryName, Unique_ID, MotorizedAllowed, DesignatedUses, SurfaceType, HorseAllowed, OwnerSteward, County
- TESTED: full state ingestion completed May 9, 2026. 46,953 trails, 41 counties, 91 agencies.

**BLM National GTLF Motorized Trails** — IDENTIFIED
- National dataset, filterable by BLM_ADMIN_ST (state code)
- GeoJSON: https://gbp-blm-egis.hub.arcgis.com/datasets/BLM-EGIS::blm-natl-gtlf-public-motorized-trails.geojson
- Key fields: TRAIL_NAME, GTLF_ID, BLM_ADMIN_ST, PLAN_OHV_ROUTE_DSGNTN, SURFACE_TYPE
- This source fills gaps in UGRC data (Parowan Gap, Arizona Strip)

**BLM National GTLF Non-Motorized Trails** — IDENTIFIED
- GeoJSON: https://gbp-blm-egis.hub.arcgis.com/datasets/BLM-EGIS::blm-natl-gtlf-public-nonmotorized-trails.geojson
- Same schema as motorized. For hikers/equestrian.

**USFS National Forest System Trails** — IDENTIFIED
- GeoJSON: https://data-usfs.hub.arcgis.com/datasets/usfs::national-forest-system-trails-feature-layer.geojson
- Key fields: TRAIL_NAME, TRAIL_NO, TERRA_MOTORIZED, SURFACE_TYPE, GIS_MILES, MANAGING_ORG
- Overlap with UGRC in Utah (UGRC curates USFS trails into their dataset)

### Sources needing research:
- Arizona state trails (azgeo.az.gov)
- Nevada state trails (nbmg.unr.edu)
- Colorado state trails + BLM Colorado
- Idaho state trails

### Source catalog format:
Each source is a JSON entry with: id, name, agency, region, download URL, format, estimated features, filter fields with labels, default filter selections, and schema mapping to GroupTrack fields. The catalog ships with the app as a JSON file or fetches from grouptrack.org for updates without app releases. Full JSON format documented in GroupTrack_V25_TrailSourceCatalog_v1.md.

---

## 4. INGESTION PIPELINE

### Overview:
Source GeoJSON → download to device → apply user-selected filters → normalize to GroupTrack schema → check for duplicates against existing data → insert into grouptrack_trails.db → render on map

### Step-by-step process:

**Step 1 — Source selection:**
- User opens trail source settings on Planning Map (Map Viewer)
- App displays catalog entries for the user's region
- User selects a source (e.g., "UGRC Utah Trails")

**Step 2 — Filter selection:**
- App shows available filter checkboxes from the catalog entry
- Default: OHV/motorized selected (GroupTrack's recommended default)
- User adjusts filters: hiking, horse, bike, multi-use, surface type
- Filters apply during ingestion, not at download time (download full file, filter on import)

**Step 3 — Download:**
- Download enters the transfer queue
- Queue handles: chunked download, progress display, drop-point resume on failure
- GeoJSON file saved to device temp storage
- Transfer queue status: queued → in_progress → completed/failed

**Step 4 — Ingestion (on-device):**
- Parse GeoJSON file feature by feature
- For each feature:
  - Apply user-selected filters. Skip non-matching features.
  - Normalize properties to GroupTrack schema using the source's schema_map
  - Calculate bounding box from coordinates (min_lon, min_lat, max_lon, max_lat)
  - Calculate trail length in miles using haversine distance
  - Generate UUID for trail_id
  - Store geometry as GeoJSON text in geometry_json column
  - Run dedup check against existing trails (see Section 5)
  - If not duplicate: INSERT into trails table
  - If duplicate: collect for review or auto-skip
- Update db_metadata with new trail count, timestamp, source info

**Step 5 — Dedup review (if applicable):**
- Present probable duplicates on review screen
- User resolves each: keep existing, replace, or keep both
- Confirmed duplicates auto-skip (no user action)
- Log all decisions to dedup_log table

**Step 6 — Display:**
- Viewport query loads trails for current map view
- Trails render on map as Leaflet polylines
- Color by source or vehicle type (configurable)

### Ingestion code location:
- Kotlin: `ConvoyTrailOps.kt` — pure logic, no UI, no Compose (mirrors ConvoyTrackOps pattern)
- Functions: ingestFromGeoJson(file, filters, schemaMap, onProgress, onDupeFound)
- Reusable across all sources — the schema_map handles source-specific field mapping

### Python proof-of-concept:
- build_trails_db_v1.py — runs on Fred's PC, ingests UGRC GeoJSON, outputs SQLite
- TESTED May 9, 2026: 46,953 trails from full Utah state dataset
- This script is the reference for the Kotlin port — same logic, different language

---

## 5. DEDUPLICATION

### Problem:
Same physical trail exists in multiple sources (UGRC + BLM + USFS) with different names, geometry, and attributes. Without dedup, map shows overlapping lines.

### Detection method — runs during ingestion Step 4:

**Pre-filter (fast):** find existing trails whose bounding box overlaps incoming trail
```sql
SELECT trail_id, name, source_agency, length_miles, geometry_json
FROM trails
WHERE max_lon >= :new_min_lon AND min_lon <= :new_max_lon
AND max_lat >= :new_min_lat AND min_lat <= :new_max_lat
```

**Length filter:** keep candidates within 20% length difference

**Point proximity:** sample 5 evenly-spaced points along incoming trail, find minimum distance to candidate trail geometry. Threshold: 50 meters.

**Classification:**
- 5/5 within 50m + length within 10% → CONFIRMED DUPLICATE → auto-skip
- 4/5 within 50m + length within 20% → PROBABLE DUPLICATE → review screen
- 3/5 within 50m → POSSIBLE DUPLICATE → review screen with warning
- 2 or fewer → NOT A DUPLICATE → insert normally

### Resolution:
- CONFIRMED: auto-skip, log to dedup_log
- PROBABLE/POSSIBLE: present on review screen with mini-map comparison
- User options: KEEP EXISTING, REPLACE WITH NEW, KEEP BOTH
- Bulk action: SKIP ALL REMAINING (auto-resolve rest by keeping existing)
- First source ingested wins auto-resolve by default

### Review screen:
Side-by-side mini-maps showing both trail geometries. Attribute comparison: name, source agency, field completeness count. Match details: how many points matched, length difference percentage. Three action buttons plus bulk skip.

### Schema additions for dedup:
- trails table: dupe_check_hash, dupe_of, dupe_status
- dedup_log table: incoming/existing details, match metrics, resolution, timestamp

---

## 6. DISPLAY REQUIREMENTS

### Viewport query pattern:
```sql
SELECT trail_id, name, source_agency, vehicle_type, surface_type, geometry_json
FROM trails
WHERE max_lon >= :west AND min_lon <= :east
AND max_lat >= :south AND min_lat <= :north
```
Returns GeoJSON per trail. Passed to Leaflet via JavaScript bridge for polyline rendering.

### Where trails display:
- Planning Map (Map Viewer): always visible, primary planning surface
- Convoy map: always visible, operational use
- Trails are the FREE planning layer — always available, no download step for display
- Trails lazy load on pan/zoom — only visible trails queried and rendered

### Where waypoints display:
- Both maps. Icons by type (flag, gas pump, barrier, warning, camera, droplet, tent, P, pin, circle)
- Map legend composable renders from waypoint_types table
- Waypoint creation: long-press on Planning Map (convoy map after cutover)

### Where tracks display:
- Planning Map: track bounding boxes or polylines from spatial query
- Convoy map: unchanged from V2.4 for now

### Area overlays:
- Blue fill: tiles downloaded (existing)
- Black diagonal stripes: waypoints, routes, tracks present
- Toggle buttons on both maps: "Tiles" and "Spatial" (labeled as waypoints, routes, tracks)
- Management (create/remove areas) only on Planning Map
- Display only on convoy map

### Lead track smoothing:
- Convoy map: when lead cart GPS arrives every 5 seconds, snap both the previous and new position to nearest trail in grouptrack_trails.db, draw along trail geometry instead of straight line
- Uses same snap-to query as route planning
- Fallback: if no trail within snap distance, draw straight line (rider is off-trail)
- This is the critical feature that needs 2-3 weeks field testing before V2.5 ships

---

## 7. CODE REQUIREMENTS

### New Kotlin files:

**ConvoyTrailOps.kt** — pure logic, no UI
- ingestFromGeoJson(inputFile, filters, schemaMap, progressCallback, dupeCallback)
- queryViewport(west, south, east, north) → List<TrailFeature>
- snapToNearestTrail(lat, lon, searchRadiusMeters) → SnappedPoint?
- snapBetweenPoints(lat1, lon1, lat2, lon2) → List<LatLng> (trail geometry between two points)
- calculateBbox(coordinates) → BoundingBox
- calculateLength(coordinates) → Double (miles)
- haversineDistance(lat1, lon1, lat2, lon2) → Double (meters)
- checkDuplicate(incoming, candidates) → DupeResult

**ConvoyTrailSourceScreen.kt** — source catalog UI
- Display available sources from catalog JSON
- Filter checkboxes per source
- Download button → enters transfer queue
- Accessible from Planning Map settings

**ConvoyTrailIngestionScreen.kt** — ingestion progress + dedup review
- Progress bar during ingestion
- Dedup review screen for probable/possible duplicates
- Mini-map comparison with side-by-side trail rendering
- Action buttons: keep existing, replace, keep both, skip all

**ConvoyWaypointOps.kt** — waypoint logic, no UI
- createWaypoint(lat, lon, name, type) → UUID
- queryViewport(west, south, east, north) → List<Waypoint>
- updateWaypoint(id, name, type, description)
- deleteWaypoint(id) — with confirmation in UI layer
- shareWaypoint(id) — sets shared=1

**ConvoyWaypointDialogs.kt** — waypoint UI
- CreateWaypointDialog(lat, lon, onSave)
- WaypointActionDialog(waypoint, onEdit, onDelete, onShare)
- WaypointLegend() — renders from waypoint_types table

### Modified Kotlin files:

**ConvoyMapViewerScreen.kt** (Planning Map)
- Add trail rendering from SQLite viewport queries (replaces GeoJSON asset loader)
- Add waypoint rendering from user database viewport queries
- Add long-press → waypoint creation
- Add trail source settings access
- Add area overlay rendering (blue + black stripes)
- Add simple snap-to for route planning

**ConvoyScreen.kt** (Convoy Map)
- Add trail rendering from SQLite viewport queries (at cutover, replaces GeoJSON)
- Add waypoint rendering (at cutover)
- Add lead track smoothing using snap-to-trail
- Remove download controls (at cutover)
- Remove long-press cart selection, add RIDERS button (at cutover)

**ConvoyConfig.kt**
- Add V25_SPATIAL_ENABLED flag
- Add trail database path constant
- Add user database path constant

**convoy_map.html / grouptrack_map.html**
- Add JavaScript bridge for receiving trail GeoJSON from Kotlin viewport queries
- Add waypoint marker rendering
- Add snap-to-trail visualization
- Replace hardcoded GeoJSON loading with bridge calls

### Database files:
- grouptrack_trails_schema_v1.sql — trails database schema
- grouptrack_user_schema_v1.sql — user database schema (waypoints, routes, tracks, tile sources, transfer queue)

---

## 8. USER WORKFLOW

### First launch (new user):
1. App creates grouptrack_user.spatialite with 12 default tile sources
2. No trail data yet — map shows tiles only
3. User goes to Planning Map → Settings → Trail Sources
4. Sees catalog: "Utah Trails (UGRC) — 49,096 trails"
5. Selects source, checks "Motorized / OHV" filter (default)
6. Taps Download → enters transfer queue
7. Progress bar while GeoJSON downloads and ingests
8. Trails appear on map as ingestion completes
9. User now has 18,230 OHV trails on their device

### Adding more sources:
1. User goes to Trail Sources again
2. Selects "BLM Motorized Trails" → filters by state "Utah"
3. Download + ingest runs with dedup against existing UGRC trails
4. Review screen shows probable duplicates
5. User resolves or skips all
6. New BLM trails added to map, duplicates skipped

### Pre-ride planning:
1. Open Planning Map, browse trails (always visible from local database)
2. See a trail you want to ride
3. Tap two points → route snaps to trail between them (simple snap-to)
4. Draw area around ride area → download tiles for offline
5. Long-press to add waypoints: trailhead, fuel stop, gate
6. Black stripes appear showing spatial data coverage
7. Everything ready for ride day

### During ride:
1. Open Convoy Map — trails visible, waypoints visible
2. Lead cart broadcasts GPS every 5 seconds
3. Lead track renders smoothed along trail geometry (not angular straight lines)
4. Blue overlay shows tile coverage — rider can see if they're about to leave downloaded area
5. No download controls on convoy map — clean operational HUD

### After ride:
1. Track saves to my_tracks/
2. "Share this track?" prompt (suppressed for private rides)
3. Track indexed in user database with bounding box
4. Track visible on Planning Map
5. Can convert track to route for future rides

---

## 9. TESTING — STANDALONE INGESTION APP

### Purpose:
Separate app (own launcher icon) for testing and debugging the ingestion pipeline. Used during development to:
- Visually verify trail rendering from database queries
- Test dedup detection accuracy
- Inspect ingested data quality
- Curate sources before integrating into GroupTrack

### Design:
- Leaflet map with tile source selector
- Draw bounding box to define area of interest
- Catalog of available trail sources
- Download + ingest with real-time progress
- Color-coded trail rendering by source
- Duplicate highlight (overlapping trails in red)
- Dedup review screen with mini-map comparison
- Export verified database for use in GroupTrack

### Implementation options:
- **Piece 1 (PC-side, build first):** HTML/Leaflet tool in Chrome. Load GeoJSON files, display color-coded by source, draw bounding boxes, visual overlap detection. Instant iteration, no Android build cycle.
- **Piece 2 (Android, build second):** Separate launcher activity in the same project. Uses production SQLite code path. Tests exactly what GroupTrack will use.

---

## 10. RELEASE STRATEGY

### V2.4 coexistence during V2.5 development:

**Period 1 — V2.4 safe (Phase 1, ~5-7 days):**
- All new spatial work on Planning Map (Map Viewer) only
- Convoy map untouched
- V2.4 tester builds still work (V25_SPATIAL_ENABLED = false)
- GeoJSON asset stays in repo
- V2.4 hotfixes still deployable

**HARD CUTOFF (after Phase 1):**
- V2.4 frozen, no more hotfixes
- V25_SPATIAL_ENABLED flipped to true
- Convoy map open for changes
- Lead track smoothing wired to convoy immediately for field testing

**Period 2 — Open field testing (Phases 2+3, ~8-12 days):**
- Waypoints on Planning Map + lead track smoothing on convoy map
- Track indexing, share prompt, track-to-route
- Lead track smoothing gets 2-3 weeks real-trail validation

**Cutover — Ship V2.5 (~5-7 days):**
- Port SpatiaLite loader to convoy map
- Remove GeoJSON asset
- Port waypoints to convoy map
- Add RIDERS button, remove long-press cart selection
- Remove download controls from convoy map
- Rename user-facing strings to "Planning Map"
- Set V25_SPATIAL_ENABLED default true, remove flag checks
- Final build, full test, tag v2.5

### Key insight — no branching:
Stay on one branch (feature/convoy-event-ride). Use V25_SPATIAL_ENABLED flag to gate new code. Same pattern as V3_FEATURES_ENABLED. No parallel branches, no merge conflicts, no reconciliation.

### The GeoJSON asset problem is solved:
The 22MB bundled GeoJSON made interim releases painful (35+ minute builds, bloated APK). The download-on-demand architecture eliminates this entirely. Trail data is decoupled from the release cycle. This change alone could ship as a V2.4.x patch — it's an improvement that makes everything easier, not a feature that risks stability.

---

## 11. DATA ACCESS MODEL

### Routes:
- NEVER independently public
- Shared only through ride invitations
- Organizer's intellectual property
- Creating a route: draw new, select previous, or convert track to route
- source_track_id links converted routes back to their source track

### Tracks:
- Personal until rider chooses to share
- "Share this track?" prompt on save
- Suppressed for private rides (ride.private toggle)
- Exist in two places: GPX/KML file (source of truth) + database record (spatial index)
- Database record has filename, bounding box, metadata — not a copy of the file

### Waypoints:
- Created by long-press on map
- 10 types: trailhead, fuel, gate, hazard, scenic, water, camp, parking, rally, other
- waypoint_types table with icon and color per type
- Map legend renders from this table
- shared flag for V3.0 upload

### Rides:
- Have a private toggle (already exists)
- Private ride → track sharing blocked for all participants
- Publication pipeline: personal → submitted (enrolled riders) → public (ride repository)
- Ride boundary defines area for data downloads

### Published content is always recoverable:
- If shared to the collective (V3.0) and removed locally for space, re-download anytime
- The rider's device is a viewport into their slice of the collective

---

## 12. TRANSFER QUEUE

### Purpose:
Every download and upload goes through the queue. This is ANR prevention — without it, riders overselect areas and the app freezes.

### Behavior:
- Chunked: large requests broken into manageable pieces
- Background: rider keeps using the app during transfers
- Progress: visible in queue screen (total items, completed, percentage)
- Drop-point resume: connectivity drops mid-transfer, queue saves offset, picks up later
- Retry: failed items retry up to max_retries (default 3)
- Priority: ride enrollment = 1 (highest), manual download = 5

### Schema:
- transfer_queue table in grouptrack_user.spatialite
- transfer_history table for completed transfers
- Views: v_active_queue, v_retryable

### V2.5 scope:
- Inbound (downloads) active
- Outbound (uploads) fully designed, activatable when ready

---

## 13. TILE SOURCE CATALOG

### Defaults (12 sources, 3 per type):

Satellite: Google Hybrid, Google Satellite, Esri World Imagery
Topo: OpenTopoMap, USGS Topo, Esri World Topo
Road: OpenStreetMap, Google Roads, Google Terrain
Trail: CalTopo USFS, Esri NatGeo, Thunderforest Outdoors (free key)

### User-added sources:
- Settings screen: add name, map type, URL template ({x},{y},{z}), API key
- Keys stored locally, never uploaded
- CalTopo, Gaia, onX subscribers enter their own credentials
- Full URL templates in grouptrack_user_schema_v1.sql seed data

---

## 14. STANDING RULES

1. Never auto-delete user data. All deletes require explicit user confirmation.
2. Never embed API keys in the APK. User manages their own tile source keys.
3. Never upload user data without explicit user action. Share is always opt-in.
4. Offline-first. Every feature must work without connectivity.
5. Canvas independence. Map tiles are the user's choice and responsibility.
6. Cumulative collection. Downloads add data. Only explicit removal deletes.
7. Routes are never independently public. They flow only through ride invitations.
8. Private rides block track sharing.
9. Published content is always recoverable from the collective (V3.0).
10. First source ingested wins auto-resolve for duplicates.
11. Reusability: track/trail/waypoint features go in Ops files (logic) and Dialogs files (UI).
12. One command at a time with echo-back. Batch all changes before building.
13. Never commit V3_FEATURES_ENABLED = true or V25_SPATIAL_ENABLED = true.
14. Patch scripts always uniquely versioned Python files. Never reuse names.
15. Preserve full trail geometry precision — V3.0 snap-to snaps GPS against this geometry.

---

## 15. FILES FROM THIS SESSION

| File | Purpose |
|------|---------|
| GroupTrack_V25_SpatialArchitecture_v1.docx | Architecture decisions document |
| GroupTrack_V25_ActionPlan_v3.docx | Sequenced task plan with timelines |
| grouptrack_trails_schema_v1.sql | Trails database schema (shipped free) |
| grouptrack_user_schema_v1.sql | User database schema (waypoints/routes/tracks/queue) |
| GroupTrack_V25_TileSourceCatalog_v1.md | 12 default tile sources + user-added |
| GroupTrack_V25_TrailSourceCatalog_v1.md | Trail sources + dedup method + catalog JSON format |
| build_trails_db_v1.py | Python ingestion proof-of-concept (PC-side) |
| This document | Complete implementation guide |

### Proof-of-concept results (May 9, 2026):
- Full Utah state: 49,096 features → 46,953 trails ingested
- 18,230 OHV/motorized, 26,224 multi-use, 2,499 hiking
- 41 counties, 91 agencies
- 107 MB raw database (too large to bundle — validates download-on-demand architecture)
- St George viewport test: 535 trails returned

---

## 16. NEXT SESSION TASKS

1. Build PC-side visual preview tool (HTML/Leaflet) for inspecting ingested trail data
2. Build standalone Android ingestion app with own launcher
3. Download BLM Utah motorized trails, test dedup against UGRC
4. Port Python ingestion logic to Kotlin (ConvoyTrailOps.kt)
5. Build trail source selector UI on Planning Map
6. Wire viewport queries to Leaflet on Planning Map
7. Begin waypoint capture implementation

---

*GroupTrack V2.5 | Spatial Data Implementation Guide | May 9, 2026*
*Do not re-litigate settled decisions documented here.*
