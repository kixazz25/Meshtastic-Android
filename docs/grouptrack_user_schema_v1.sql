-- ============================================================
-- GroupTrack V2.5 — User Spatial Database
-- File: grouptrack_user.spatialite
-- Location: /sdcard/Documents/GroupTrack/data/grouptrack_user.spatialite
-- Access: READ-WRITE. Survives app updates and uninstalls.
-- ============================================================
--
-- WHAT'S IN HERE: waypoints, routes, tracks, ride areas,
-- tile sources, transfer queue. Everything the rider creates
-- or receives through ride enrollment.
--
-- WHAT'S NOT HERE: trails. Trails are a separate shipped
-- database (grouptrack_trails.spatialite) — public, free,
-- read-only, always available.
--
-- MAP OVERLAY: black diagonal stripes = waypoints, routes,
-- tracks present for this area. Blue fill = tiles (existing).
--
-- V2.5: local collection via rider activity.
-- V3.0: share/receive via ride engine (subscription).
--
-- ============================================================

SELECT InitSpatialMetaData(1);

-- ============================================================
-- 1. TILE SOURCES — user-configurable map canvas
-- ============================================================

CREATE TABLE tile_sources (
    source_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    map_type        TEXT NOT NULL,                   -- satellite | topo | road | trail
    url_template    TEXT NOT NULL,                   -- {x},{y},{z} placeholders
    requires_key    INTEGER NOT NULL DEFAULT 0,
    api_key         TEXT,                            -- local only, never uploaded
    key_signup_url  TEXT,
    attribution     TEXT,
    min_zoom        INTEGER NOT NULL DEFAULT 0,
    max_zoom        INTEGER NOT NULL DEFAULT 18,
    is_default      INTEGER NOT NULL DEFAULT 0,
    is_active       INTEGER NOT NULL DEFAULT 1,
    sort_order      INTEGER NOT NULL DEFAULT 0
);

-- ============================================================
-- 2. WAYPOINTS — rider-created points of interest
-- ============================================================

CREATE TABLE waypoints (
    waypoint_id     TEXT PRIMARY KEY,                -- UUID
    name            TEXT NOT NULL,
    type            TEXT NOT NULL,                   -- trailhead | fuel | gate | hazard | scenic
                                                     -- | water | camp | parking | rally | other
    description     TEXT,
    area_id         TEXT,                            -- FK ride_areas. Which area this belongs to.
    ride_id         TEXT,                            -- FK rides (V3.0). NULL if personal.
    created_at      TEXT NOT NULL,                   -- ISO 8601
    updated_at      TEXT NOT NULL,
    shared          INTEGER NOT NULL DEFAULT 0       -- 0=local, 1=flagged for upload
);

SELECT AddGeometryColumn('waypoints', 'geometry', 4326, 'POINT', 'XY');
SELECT CreateSpatialIndex('waypoints', 'geometry');

-- ============================================================
-- 3. ROUTES — planned ride routes (imported GPX/KML)
-- ============================================================

CREATE TABLE routes (
    route_id        TEXT PRIMARY KEY,                -- UUID
    name            TEXT NOT NULL,
    description     TEXT,
    distance_miles  REAL,
    elevation_gain_ft INTEGER,
    source_format   TEXT,                            -- gpx | kml
    area_id         TEXT,                            -- FK ride_areas
    ride_id         TEXT,                            -- FK rides (V3.0)
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    shared          INTEGER NOT NULL DEFAULT 0
);

SELECT AddGeometryColumn('routes', 'geometry', 4326, 'LINESTRING', 'XY');
SELECT CreateSpatialIndex('routes', 'geometry');

-- ============================================================
-- 4. TRACKS — rider's recorded GPS tracks
-- ============================================================
-- Metadata + bounding box for spatial queries.
-- Actual GPX/KML files stay in Documents/my_tracks/.
-- This table links file storage to the spatial database.

CREATE TABLE tracks (
    track_id        TEXT PRIMARY KEY,                -- UUID
    filename        TEXT NOT NULL UNIQUE,            -- file in my_tracks/
    name            TEXT,                            -- display name
    recorded_at     TEXT,                            -- earliest GPS time from file
    distance_miles  REAL,
    duration_minutes INTEGER,
    max_speed_mph   REAL,
    elevation_gain_ft INTEGER,
    source_format   TEXT,                            -- gpx | kml
    area_id         TEXT,                            -- FK ride_areas
    ride_id         TEXT,                            -- FK rides (V3.0)
    created_at      TEXT NOT NULL,
    shared          INTEGER NOT NULL DEFAULT 0
);

SELECT AddGeometryColumn('tracks', 'bbox', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('tracks', 'bbox');

-- ============================================================
-- 5. RIDE AREAS — drawn boundaries on the map
-- ============================================================
-- Defines what the black stripe overlay shows.
-- Same outline as tile download area.

CREATE TABLE ride_areas (
    area_id         TEXT PRIMARY KEY,                -- UUID
    name            TEXT NOT NULL,                   -- 'St George home area'
    area_type       TEXT NOT NULL DEFAULT 'personal',-- personal | ride
    ride_id         TEXT,                            -- FK rides (V3.0). NULL for personal.
    has_tiles       INTEGER NOT NULL DEFAULT 0,      -- 1=tiles downloaded for this area
    has_spatial     INTEGER NOT NULL DEFAULT 0,      -- 1=waypoints/routes/tracks present
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

SELECT AddGeometryColumn('ride_areas', 'geometry', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('ride_areas', 'geometry');

-- ============================================================
-- 6. TRANSFER QUEUE — inbound/outbound data exchange
-- ============================================================
-- Every download and upload goes through this queue.
-- Chunked, background, resumable from drop point.
-- Prevents ANR on large area selections.
--
-- V2.5: inbound active (download waypoints/routes/tracks).
-- V3.0: outbound active (upload shared items via ride engine).

CREATE TABLE transfer_queue (
    queue_id        TEXT PRIMARY KEY,                -- UUID
    direction       TEXT NOT NULL,                   -- inbound | outbound
    data_type       TEXT NOT NULL,                   -- waypoints | routes | tracks | tiles
    status          TEXT NOT NULL DEFAULT 'queued',  -- queued | in_progress | completed
                                                     -- | failed | cancelled
    priority        INTEGER NOT NULL DEFAULT 5,      -- 1=ride enrollment, 5=manual

    -- AREA
    area_id         TEXT,                            -- FK ride_areas
    area_name       TEXT,                            -- denormalized for UI

    -- SINGLE ITEM (for share-one-track uploads)
    item_id         TEXT,                            -- UUID of track/waypoint/route
    item_name       TEXT,

    -- PROGRESS
    total_items     INTEGER DEFAULT 0,
    completed_items INTEGER DEFAULT 0,
    total_bytes     INTEGER DEFAULT 0,
    transferred_bytes INTEGER DEFAULT 0,
    progress_pct    REAL DEFAULT 0.0,                -- 0.0–100.0

    -- RESUME FROM DROP POINT
    drop_point      TEXT,                            -- JSON: offset/page/last_id for resume
    retry_count     INTEGER DEFAULT 0,
    max_retries     INTEGER DEFAULT 3,
    last_error      TEXT,

    -- SERVER (V3.0)
    server_url      TEXT,
    auth_token      TEXT,                            -- temporary, cleared on completion

    -- TIMESTAMPS
    created_at      TEXT NOT NULL,
    started_at      TEXT,
    completed_at    TEXT,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_queue_active ON transfer_queue(status, priority, created_at);

-- ============================================================
-- 7. TRANSFER HISTORY — completed transfers
-- ============================================================

CREATE TABLE transfer_history (
    history_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    queue_id        TEXT NOT NULL,
    direction       TEXT NOT NULL,
    data_type       TEXT NOT NULL,
    area_name       TEXT,
    item_name       TEXT,
    total_items     INTEGER DEFAULT 0,
    total_bytes     INTEGER DEFAULT 0,
    result          TEXT NOT NULL,                   -- completed | failed | cancelled
    error_summary   TEXT,
    started_at      TEXT,
    completed_at    TEXT NOT NULL
);

-- ============================================================
-- 8. DATA LOG — audit trail
-- ============================================================

CREATE TABLE data_log (
    log_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    action          TEXT NOT NULL,                   -- import | refresh | remove | share
    source          TEXT,                            -- area_id or description
    waypoint_count  INTEGER DEFAULT 0,
    route_count     INTEGER DEFAULT 0,
    track_count     INTEGER DEFAULT 0,
    performed_at    TEXT NOT NULL
);

-- ============================================================
-- 9. SOURCE INGESTIONS — tracks what trail sources are installed
-- ============================================================
-- Compared against catalog.json data_version to detect updates.
-- filters_used preserved for re-ingestion with same selections.

CREATE TABLE source_ingestions (
    source_id        TEXT PRIMARY KEY,                -- matches catalog entry id
    source_name      TEXT NOT NULL,                   -- display name from catalog
    version_ingested INTEGER NOT NULL,               -- data_version at time of ingestion
    ingested_at      TEXT NOT NULL,                   -- ISO 8601
    trail_count      INTEGER DEFAULT 0,              -- trails from this source
    dupes_skipped    INTEGER DEFAULT 0,              -- duplicates auto-skipped
    filters_used     TEXT,                            -- JSON of filter selections
    source_url       TEXT,                            -- agency URL used for download
    bounds_json      TEXT                             -- JSON bounding box of ingested data
);

-- ============================================================
-- 10. SEED DATA — Default Tile Sources
-- ============================================================

-- SATELLITE (3 free, no key)
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('Google Hybrid',    'satellite', 'https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', 0, 'Map data © Google', 0, 20, 1, 1, 1),
('Google Satellite', 'satellite', 'https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}', 0, 'Imagery © Google',  0, 20, 1, 0, 2),
('Esri World Imagery','satellite','https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', 0, 'Imagery © Esri', 0, 19, 1, 1, 3);

-- TOPO (3 free, no key)
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('OpenTopoMap',      'topo', 'https://tile.opentopomap.org/{z}/{x}/{y}.png', 0, '© OpenTopoMap (CC-BY-SA)', 0, 17, 1, 1, 4),
('USGS Topo',        'topo', 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}', 0, 'USGS The National Map', 0, 16, 1, 1, 5),
('Esri World Topo',  'topo', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}', 0, '© Esri', 0, 19, 1, 0, 6);

-- ROAD (3 free, no key)
INSERT INTO tile_sources (name, map_type, url_template, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('OpenStreetMap',    'road', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', 0, '© OpenStreetMap contributors', 0, 19, 1, 1, 7),
('Google Roads',     'road', 'https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', 0, 'Map data © Google', 0, 20, 1, 0, 8),
('Google Terrain',   'road', 'https://mt0.google.com/vt/lyrs=p&x={x}&y={y}&z={z}', 0, 'Map data © Google', 0, 20, 1, 0, 9);

-- TRAIL (2 free + 1 free key)
INSERT INTO tile_sources (name, map_type, url_template, requires_key, key_signup_url, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('CalTopo USFS',     'trail', 'https://caltopo.s3.amazonaws.com/topo/{z}/{x}/{y}.png', 0, NULL, '© CalTopo', 2, 16, 1, 1, 10),
('Esri NatGeo',      'trail', 'https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}', 0, NULL, '© Esri / National Geographic', 0, 16, 1, 0, 11),
('Thunderforest Outdoors','trail','https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}', 1, 'https://www.thunderforest.com/docs/apikeys/', '© Thunderforest / OSM contributors', 0, 22, 1, 0, 12);

-- ============================================================
-- VIEWS
-- ============================================================

CREATE VIEW v_active_tile_sources AS
SELECT source_id, name, map_type, url_template, requires_key, api_key, attribution, min_zoom, max_zoom
FROM tile_sources WHERE is_active = 1 ORDER BY sort_order;

CREATE VIEW v_shared_items AS
SELECT 'track' as item_type, track_id as item_id, name, created_at FROM tracks WHERE shared = 1
UNION ALL
SELECT 'waypoint', waypoint_id, name, created_at FROM waypoints WHERE shared = 1
UNION ALL
SELECT 'route', route_id, name, created_at FROM routes WHERE shared = 1;

CREATE VIEW v_active_queue AS
SELECT queue_id, direction, data_type, status, priority, area_name, item_name,
       total_items, completed_items, progress_pct, retry_count, last_error, created_at
FROM transfer_queue
WHERE status IN ('queued', 'in_progress', 'failed')
ORDER BY CASE status WHEN 'in_progress' THEN 0 WHEN 'failed' THEN 1 ELSE 2 END,
         priority, created_at;

CREATE VIEW v_retryable AS
SELECT queue_id, direction, data_type, area_name, item_name,
       retry_count, max_retries, last_error, drop_point
FROM transfer_queue
WHERE status = 'failed' AND retry_count < max_retries;

CREATE VIEW v_area_coverage AS
SELECT area_id, name, area_type, has_tiles, has_spatial,
       (SELECT COUNT(*) FROM waypoints w WHERE w.area_id = a.area_id) as waypoint_count,
       (SELECT COUNT(*) FROM routes r WHERE r.area_id = a.area_id) as route_count,
       (SELECT COUNT(*) FROM tracks t WHERE t.area_id = a.area_id) as track_count
FROM ride_areas a;
