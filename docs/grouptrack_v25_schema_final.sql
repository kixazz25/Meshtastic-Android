-- ============================================================
-- GroupTrack V2.5 — Schema Design FINAL
-- Date: May 18, 2026
-- 
-- TWO LAYERS:
--   1. Spatial (SpatiaLite, pure OGC standard, minimal)
--   2. Extension (local SQLite, mirrors AWS for push/pull)
--
-- RULES:
--   - Spatial tables: geometry + identity + timestamps ONLY
--   - No FKs on spatial tables. No custom columns.
--   - Extensions reference spatial by ID, synced by Kotlin triggers
--   - Three separate queue tables (tiles, upload, download)
--   - Aliases travel with data. is_preferred is local only.
--   - All timestamps ISO 8601
-- ============================================================

-- ************************************************************
-- PART 1: SPATIAL MODEL (Pure OGC Standard)
-- File: grouptrack_spatial.spatialite
-- ************************************************************

SELECT InitSpatialMetaData(1);

-- 1.1 TRAILS
CREATE TABLE trails (
    trail_id        TEXT PRIMARY KEY,
    name            TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
SELECT AddGeometryColumn('trails', 'geometry', 4326, 'LINESTRING', 'XY');
SELECT CreateSpatialIndex('trails', 'geometry');

-- 1.2 TRACKS
CREATE TABLE tracks (
    track_id        TEXT PRIMARY KEY,
    name            TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
SELECT AddGeometryColumn('tracks', 'bbox', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('tracks', 'bbox');

-- 1.3 WAYPOINTS
CREATE TABLE waypoints (
    waypoint_id     TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    type            TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
SELECT AddGeometryColumn('waypoints', 'geometry', 4326, 'POINT', 'XY');
SELECT CreateSpatialIndex('waypoints', 'geometry');

-- 1.4 ROUTES
CREATE TABLE routes (
    route_id        TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
SELECT AddGeometryColumn('routes', 'geometry', 4326, 'LINESTRING', 'XY');
SELECT CreateSpatialIndex('routes', 'geometry');


-- ************************************************************
-- PART 2: EXTENSION MODEL (Local SQLite)
-- Relationships to spatial maintained by Kotlin triggers
-- Mirrors AWS for push/pull where noted
-- ************************************************************

-- ────────────────────────────────────────────
-- 2.1 TRAIL PROPERTIES [AWS mirror]
-- ────────────────────────────────────────────
CREATE TABLE trail_properties (
    trail_id            TEXT PRIMARY KEY,
    -- Source/provenance
    source_id           TEXT,
    source_unique_id    TEXT,
    -- Classification
    designated_uses     TEXT,
    motorized_allowed   TEXT,
    horse_allowed       TEXT,
    surface_type        TEXT,
    trail_class         TEXT,
    carto_code          TEXT,
    -- Difficulty
    hike_difficulty     TEXT,
    bike_difficulty     TEXT,
    ada_accessible      TEXT,
    -- Attribution
    owner_steward       TEXT,
    county              TEXT,
    recreation_area     TEXT,
    system_name         TEXT,
    trans_network       TEXT,
    -- Source metadata
    data_source         TEXT,
    agency_id           TEXT,
    status              TEXT,
    comments            TEXT,
    -- Measurements
    distance_miles      REAL,
    elevation_gain_ft   INTEGER,
    -- Flags
    shared              INTEGER NOT NULL DEFAULT 0,
    -- Agency timestamps
    source_created_at   TEXT,
    source_updated_at   TEXT,
    ingested_at         TEXT
);

-- ────────────────────────────────────────────
-- 2.2 TRACK PROPERTIES [AWS mirror]
-- ────────────────────────────────────────────
CREATE TABLE track_properties (
    track_id            TEXT PRIMARY KEY,
    filename            TEXT UNIQUE,
    source_format       TEXT,
    recorded_at         TEXT,
    distance_miles      REAL,
    duration_minutes    INTEGER,
    max_speed_mph       REAL,
    avg_speed_mph       REAL,
    elevation_gain_ft   INTEGER,
    point_count         INTEGER,
    shared              INTEGER NOT NULL DEFAULT 0,
    -- Ride association (V3.0)
    ride_id             TEXT,
    area_id             TEXT
);

-- ────────────────────────────────────────────
-- 2.3 TRACK SURVEYS [AWS mirror]
-- ────────────────────────────────────────────
-- Collected at end of GPS recording save
CREATE TABLE track_surveys (
    survey_id           TEXT PRIMARY KEY,
    track_id            TEXT NOT NULL,
    enjoyment           INTEGER,
    ride_again          INTEGER,
    submitted_at        TEXT NOT NULL
);

-- ────────────────────────────────────────────
-- 2.4 WAYPOINT PROPERTIES [AWS mirror]
-- ────────────────────────────────────────────
CREATE TABLE waypoint_properties (
    waypoint_id         TEXT PRIMARY KEY,
    description         TEXT,
    elevation_ft        INTEGER,
    source_format       TEXT,
    source_file         TEXT,
    shared              INTEGER NOT NULL DEFAULT 0,
    -- Ride association (V3.0)
    ride_id             TEXT,
    area_id             TEXT
);

-- ────────────────────────────────────────────
-- 2.5 ROUTE PROPERTIES [AWS mirror]
-- ────────────────────────────────────────────
-- trailhead_id: nullable during creation
-- RULE: required before ride release (V2.6)
-- RULE: rides not introduced until V3.0 paygate
CREATE TABLE route_properties (
    route_id            TEXT PRIMARY KEY,
    description         TEXT,
    distance_miles      REAL,
    elevation_gain_ft   INTEGER,
    creation_method     TEXT,
    source_track_id     TEXT,
    trailhead_id        TEXT,
    shared              INTEGER NOT NULL DEFAULT 0,
    -- Ride association (V3.0)
    ride_id             TEXT,
    area_id             TEXT
);

-- ────────────────────────────────────────────
-- 2.6 ARTIFACT ALIASES [AWS mirror partial]
-- ────────────────────────────────────────────
-- is_preferred is LOCAL ONLY, not in AWS
-- First name = source preferred. User can override.
-- Aliases travel with data on push/pull.
CREATE TABLE artifact_aliases (
    alias_id            TEXT PRIMARY KEY,
    artifact_type       TEXT NOT NULL,
    artifact_id         TEXT NOT NULL,
    alias               TEXT NOT NULL,
    is_preferred        INTEGER NOT NULL DEFAULT 0,
    source              TEXT,
    created_at          TEXT NOT NULL
);
CREATE INDEX idx_aliases_lookup ON artifact_aliases(artifact_type, artifact_id);
CREATE INDEX idx_aliases_pref ON artifact_aliases(artifact_type, artifact_id, is_preferred);

-- ────────────────────────────────────────────
-- 2.7 TRAIL SOURCES [local only]
-- ────────────────────────────────────────────
-- Vetted/approved catalog. Not end-user created.
CREATE TABLE trail_sources (
    source_id           TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    agency              TEXT,
    url                 TEXT NOT NULL,
    format              TEXT NOT NULL,
    trail_types         TEXT,
    coverage_state      TEXT,
    approved            INTEGER NOT NULL DEFAULT 1,
    last_checked_at     TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);
SELECT AddGeometryColumn('trail_sources', 'boundary', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('trail_sources', 'boundary');

-- ────────────────────────────────────────────
-- 2.8 SOURCE INGESTIONS [local only]
-- ────────────────────────────────────────────
CREATE TABLE source_ingestions (
    ingestion_id        TEXT PRIMARY KEY,
    source_id           TEXT NOT NULL,
    version_ingested    TEXT,
    ingested_at         TEXT NOT NULL,
    trail_count         INTEGER DEFAULT 0,
    dupes_skipped       INTEGER DEFAULT 0,
    filters_used        TEXT,
    bounds_json         TEXT
);

-- ────────────────────────────────────────────
-- 2.9 TILE QUEUE [local only]
-- ────────────────────────────────────────────
CREATE TABLE tile_queue (
    queue_id            TEXT PRIMARY KEY,
    job_type            TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'queued',
    priority            INTEGER NOT NULL DEFAULT 5,
    -- Tile specifics
    tile_source         TEXT,
    zoom_level          INTEGER,
    region_json         TEXT,
    -- Progress
    total_tiles         INTEGER DEFAULT 0,
    completed_tiles     INTEGER DEFAULT 0,
    progress_pct        REAL DEFAULT 0.0,
    -- Resume
    drop_point          TEXT,
    retry_count         INTEGER DEFAULT 0,
    max_retries         INTEGER DEFAULT 3,
    last_error          TEXT,
    -- Timestamps
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    completed_at        TEXT,
    updated_at          TEXT NOT NULL
);
CREATE INDEX idx_tile_q ON tile_queue(status, priority, created_at);

-- ────────────────────────────────────────────
-- 2.10 UPLOAD QUEUE [local only until V2.6]
-- ────────────────────────────────────────────
-- V2.5: collects items. No processing.
-- V2.6: processes items, pushes to AWS.
CREATE TABLE upload_queue (
    queue_id            TEXT PRIMARY KEY,
    artifact_type       TEXT NOT NULL,
    artifact_id         TEXT NOT NULL,
    artifact_name       TEXT,
    status              TEXT NOT NULL DEFAULT 'queued',
    priority            INTEGER NOT NULL DEFAULT 5,
    -- Progress (V2.6)
    total_bytes         INTEGER DEFAULT 0,
    transferred_bytes   INTEGER DEFAULT 0,
    progress_pct        REAL DEFAULT 0.0,
    -- Resume
    drop_point          TEXT,
    retry_count         INTEGER DEFAULT 0,
    max_retries         INTEGER DEFAULT 3,
    last_error          TEXT,
    -- Timestamps
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    completed_at        TEXT,
    updated_at          TEXT NOT NULL
);
CREATE INDEX idx_upload_q ON upload_queue(status, priority, created_at);

-- ────────────────────────────────────────────
-- 2.11 DOWNLOAD QUEUE [local only until V2.6]
-- ────────────────────────────────────────────
-- V2.5: stubs only. No processing.
-- V2.6: processes items, pulls from AWS.
CREATE TABLE download_queue (
    queue_id            TEXT PRIMARY KEY,
    artifact_type       TEXT NOT NULL,
    artifact_id         TEXT,
    artifact_name       TEXT,
    status              TEXT NOT NULL DEFAULT 'queued',
    priority            INTEGER NOT NULL DEFAULT 5,
    -- Area (for area-based discovery)
    area_json           TEXT,
    -- Progress (V2.6)
    total_items         INTEGER DEFAULT 0,
    completed_items     INTEGER DEFAULT 0,
    total_bytes         INTEGER DEFAULT 0,
    transferred_bytes   INTEGER DEFAULT 0,
    progress_pct        REAL DEFAULT 0.0,
    -- Resume
    drop_point          TEXT,
    retry_count         INTEGER DEFAULT 0,
    max_retries         INTEGER DEFAULT 3,
    last_error          TEXT,
    -- Server (V2.6)
    server_url          TEXT,
    -- Timestamps
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    completed_at        TEXT,
    updated_at          TEXT NOT NULL
);
CREATE INDEX idx_download_q ON download_queue(status, priority, created_at);

-- ────────────────────────────────────────────
-- 2.12 TRANSFER HISTORY [local only]
-- ────────────────────────────────────────────
CREATE TABLE transfer_history (
    history_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    queue_type          TEXT NOT NULL,
    queue_id            TEXT NOT NULL,
    artifact_type       TEXT,
    artifact_name       TEXT,
    total_items         INTEGER DEFAULT 0,
    total_bytes         INTEGER DEFAULT 0,
    result              TEXT NOT NULL,
    error_summary       TEXT,
    started_at          TEXT,
    completed_at        TEXT NOT NULL
);

-- ────────────────────────────────────────────
-- 2.13 DATA LOG / AUDIT JOURNAL [local only]
-- ────────────────────────────────────────────
-- Standing rule: every data change journaled
-- Before AND after state with geometry as WKT
CREATE TABLE data_log (
    log_id              INTEGER PRIMARY KEY AUTOINCREMENT,
    artifact_type       TEXT NOT NULL,
    artifact_id         TEXT NOT NULL,
    action              TEXT NOT NULL,
    before_state        TEXT,
    after_state         TEXT,
    geometry_wkt        TEXT,
    performed_at        TEXT NOT NULL,
    performed_by        TEXT
);
CREATE INDEX idx_log_artifact ON data_log(artifact_type, artifact_id);
CREATE INDEX idx_log_action ON data_log(action, performed_at);

-- ────────────────────────────────────────────
-- 2.14 TILE SOURCES [local only]
-- ────────────────────────────────────────────
CREATE TABLE tile_sources (
    source_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name                TEXT NOT NULL,
    map_type            TEXT NOT NULL,
    url_template        TEXT NOT NULL,
    requires_key        INTEGER NOT NULL DEFAULT 0,
    api_key             TEXT,
    key_signup_url      TEXT,
    attribution         TEXT,
    min_zoom            INTEGER NOT NULL DEFAULT 0,
    max_zoom            INTEGER NOT NULL DEFAULT 18,
    is_default          INTEGER NOT NULL DEFAULT 0,
    is_active           INTEGER NOT NULL DEFAULT 1,
    is_recommended      INTEGER NOT NULL DEFAULT 0,
    sort_order          INTEGER NOT NULL DEFAULT 0
);

-- ────────────────────────────────────────────
-- 2.15 RIDE AREAS [V3.0 — persistent for rides]
-- ────────────────────────────────────────────
-- Working areas are transient/memory only
CREATE TABLE ride_areas (
    area_id             TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    area_type           TEXT NOT NULL DEFAULT 'personal',
    ride_id             TEXT,
    has_tiles           INTEGER NOT NULL DEFAULT 0,
    has_spatial          INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);
SELECT AddGeometryColumn('ride_areas', 'geometry', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('ride_areas', 'geometry');


-- ************************************************************
-- PART 3: VIEWS
-- ************************************************************

CREATE VIEW v_preferred_aliases AS
SELECT artifact_type, artifact_id, alias
FROM artifact_aliases WHERE is_preferred = 1;

CREATE VIEW v_trail_display AS
SELECT t.trail_id,
       COALESCE(a.alias, t.name, tp.agency_id) as display_name,
       tp.motorized_allowed, tp.surface_type, tp.carto_code,
       tp.owner_steward, tp.status
FROM trails t
LEFT JOIN trail_properties tp ON t.trail_id = tp.trail_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type = 'trail'
    AND a.artifact_id = t.trail_id;

CREATE VIEW v_track_display AS
SELECT t.track_id,
       COALESCE(a.alias, t.name) as display_name,
       tp.distance_miles, tp.duration_minutes, tp.source_format, tp.shared
FROM tracks t
LEFT JOIN track_properties tp ON t.track_id = tp.track_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type = 'track'
    AND a.artifact_id = t.track_id;

CREATE VIEW v_shared_items AS
SELECT 'track' as artifact_type, tp.track_id as artifact_id,
       COALESCE(a.alias, t.name) as name, t.created_at
FROM track_properties tp
JOIN tracks t ON tp.track_id = t.track_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type = 'track'
    AND a.artifact_id = tp.track_id
WHERE tp.shared = 1
UNION ALL
SELECT 'waypoint', wp.waypoint_id,
       COALESCE(a.alias, w.name) as name, w.created_at
FROM waypoint_properties wp
JOIN waypoints w ON wp.waypoint_id = w.waypoint_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type = 'waypoint'
    AND a.artifact_id = wp.waypoint_id
WHERE wp.shared = 1
UNION ALL
SELECT 'route', rp.route_id,
       COALESCE(a.alias, r.name) as name, r.created_at
FROM route_properties rp
JOIN routes r ON rp.route_id = r.route_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type = 'route'
    AND a.artifact_id = rp.route_id
WHERE rp.shared = 1;

CREATE VIEW v_tile_queue_active AS
SELECT * FROM tile_queue
WHERE status IN ('queued', 'in_progress', 'failed')
ORDER BY priority, created_at;

CREATE VIEW v_upload_queue_active AS
SELECT * FROM upload_queue
WHERE status IN ('queued', 'in_progress', 'failed')
ORDER BY priority, created_at;

CREATE VIEW v_download_queue_active AS
SELECT * FROM download_queue
WHERE status IN ('queued', 'in_progress', 'failed')
ORDER BY priority, created_at;


-- ************************************************************
-- PART 4: SEED DATA — Default Tile Sources
-- ************************************************************

-- SATELLITE
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, max_zoom, is_default, is_active, is_recommended, sort_order) VALUES
('Google Hybrid',     'satellite', 'https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', 0, 'Map data Google', 20, 1, 1, 1, 1),
('Google Satellite',  'satellite', 'https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}', 0, 'Imagery Google',  20, 0, 1, 1, 2),
('Esri World Imagery','satellite', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', 0, 'Imagery Esri', 19, 0, 1, 1, 3);

-- TOPO
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, max_zoom, is_default, is_active, is_recommended, sort_order) VALUES
('OpenTopoMap',       'topo', 'https://tile.opentopomap.org/{z}/{x}/{y}.png', 0, 'OpenTopoMap CC-BY-SA', 17, 1, 1, 1, 4),
('USGS Topo',         'topo', 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}', 0, 'USGS', 16, 0, 1, 1, 5),
('Esri World Topo',   'topo', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}', 0, 'Esri', 19, 0, 1, 1, 6);

-- ROAD
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, max_zoom, is_default, is_active, sort_order) VALUES
('OpenStreetMap',     'road', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', 0, 'OSM contributors', 19, 1, 1, 7),
('Google Roads',      'road', 'https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', 0, 'Map data Google', 20, 0, 1, 8),
('Google Terrain',    'road', 'https://mt0.google.com/vt/lyrs=p&x={x}&y={y}&z={z}', 0, 'Map data Google', 20, 0, 1, 9);

-- TRAIL
INSERT INTO tile_sources (name, map_type, url_template, requires_key, key_signup_url, attribution, max_zoom, is_default, is_active, sort_order) VALUES
('CalTopo USFS',      'trail', 'https://caltopo.s3.amazonaws.com/topo/{z}/{x}/{y}.png', 0, NULL, 'CalTopo', 16, 1, 1, 10),
('Esri NatGeo',       'trail', 'https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}', 0, NULL, 'Esri / NatGeo', 16, 0, 1, 11),
('Thunderforest',     'trail', 'https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}', 1, 'https://www.thunderforest.com/docs/apikeys/', 'Thunderforest / OSM', 22, 0, 0, 12);


-- ************************************************************
-- PART 5: LIFECYCLE OWNERSHIP (Kotlin entities)
-- ************************************************************
/*
ENTITY                  OWNS                                    INTEGRITY
SpatialDbManager        DB creation, schema migration           Kotlin: create extension row on spatial insert
                                                                Kotlin: cascade delete extensions on spatial delete
TrailImporter           Trail import lifecycle                  Kotlin: insert spatial + properties + alias + log
                        Parse GeoJSON/Shapefile                 Kotlin: dedup check via source_unique_id
                        Map source fields to properties         Kotlin: tile queue LOW priority trigger
TrackManager            Track creation (GPS + import)           Kotlin: insert spatial + properties + alias + log
                        Save/name/share prompt                  Kotlin: tile queue HIGH priority trigger
                        Survey collection                       Kotlin: insert track_surveys
WaypointManager         Waypoint creation (long-press)          Kotlin: proximity check 100m before insert
                        Both maps                               Kotlin: insert spatial + properties + alias + log
RouteManager            Route creation (draw/snap/convert)      Kotlin: insert spatial + properties + alias + log
                        Trailhead validation                    Kotlin: tile queue HIGH priority trigger
                                                                Kotlin: block delete if ride assigned (check route_properties.ride_id)
AliasManager            All alias operations                    Kotlin: insert/update artifact_aliases
                        Set preferred                           Kotlin: ensure exactly one preferred per artifact
QueueManager            All three queues                        Kotlin: insert queue entries from triggers
                        Status transitions                      Kotlin: move completed to transfer_history
AuditLogger             data_log entries                        Kotlin: wrap every write operation
                        Before/after snapshots                  Kotlin: geometry as WKT
TileRequestTrigger      Observe spatial changes                 Kotlin: on spatial insert, create tile_queue entry
                        Priority assignment                     Kotlin: HIGH for tracks/routes, LOW for trails, MED for source refresh

TRACK CREATION FLOW:
  1. GPS stops or file selected
  2. TrackManager: prompt name
  3. TrackManager: Save or Skip
  4. TrackManager: insert spatial + properties
  5. TrackManager: prompt "available to all riders? Y/N" -> set shared flag
  6. TrackManager: survey (enjoyment 1-5, ride again Y/N) -> insert track_surveys
  7. AliasManager: insert alias (name as preferred)
  8. AuditLogger: insert data_log
  9. If shared: QueueManager inserts upload_queue entry
  10. TileRequestTrigger: insert tile_queue HIGH priority

ROUTE RULES:
  - trailhead_id nullable during creation
  - Required before ride release (V2.6 validation)
  - Rides not introduced until V3.0 paygate
  - Route cannot be assigned to ride without trailhead waypoint
  - Delete blocked if route_properties.ride_id is not null
*/


-- ************************************************************
-- PART 6: AWS MIRROR TABLES (MySQL syntax, reference only)
-- ************************************************************
/*
Tables that mirror to AWS for push/pull:
  trails              -> aws.trails (geometry as GeoJSON)
  trail_properties    -> aws.trail_properties
  tracks              -> aws.tracks (bbox as GeoJSON)
  track_properties    -> aws.track_properties
  track_surveys       -> aws.track_surveys
  waypoints           -> aws.waypoints (lat/lng columns)
  waypoint_properties -> aws.waypoint_properties
  routes              -> aws.routes (geometry as GeoJSON)
  route_properties    -> aws.route_properties
  artifact_aliases    -> aws.artifact_aliases (WITHOUT is_preferred)

Tables that are LOCAL ONLY:
  tile_sources
  trail_sources + boundary geometry
  source_ingestions
  tile_queue
  upload_queue
  download_queue
  transfer_history
  data_log
  ride_areas (until V3.0)
*/
