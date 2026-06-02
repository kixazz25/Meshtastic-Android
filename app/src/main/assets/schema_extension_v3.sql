-- =====================================================================
-- ADD-RULES CONTRACT (see schema_spatial_v3.sql for the full contract)
-- =====================================================================
-- artifact_aliases is written ONLY by the shared add function in
-- SpatialDbManager (the same function that writes the artifact tables).
-- Aliases are POINTERS: (artifact_type, artifact_id, alias) + geom_hash +
-- creation_date. The hash lives on the ARTIFACT row (anchor); the alias only
-- points to it and carries a human-readable name.
--   UNIQUE(artifact_type, artifact_id, alias)        : one alias-name per artifact.
--   UNIQUE(artifact_type, geom_hash, creation_date)  : TRACK-ALIAS dedup -- collapses
--     same-day group rides (one community alias per geometry per ride-day, first wins).
-- creation_date is meaningful for TRACK ALIASES ONLY (generic name for future reuse).
-- Use INSERT OR IGNORE so a duplicate alias silently no-ops.
-- =====================================================================

-- GroupTrack Extension Schema v3 (revised: geom_hash identity + creation_date track-alias dedup)
-- File: grouptrack_data.db

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL,
    applied_at  TEXT NOT NULL
);
INSERT INTO schema_version VALUES (3, datetime('now'));

CREATE TABLE IF NOT EXISTS trail_properties (
    trail_id TEXT PRIMARY KEY, source_id TEXT, source_unique_id TEXT, designated_uses TEXT,
    motorized_allowed TEXT, horse_allowed TEXT, surface_type TEXT, trail_class TEXT, carto_code TEXT,
    hike_difficulty TEXT, bike_difficulty TEXT, ada_accessible TEXT, owner_steward TEXT, county TEXT,
    recreation_area TEXT, system_name TEXT, trans_network TEXT, data_source TEXT, agency_id TEXT,
    status TEXT, comments TEXT, distance_miles REAL, elevation_gain_ft INTEGER,
    shared INTEGER NOT NULL DEFAULT 0, source_created_at TEXT, source_updated_at TEXT, ingested_at TEXT
);
CREATE TABLE IF NOT EXISTS track_properties (
    track_id TEXT PRIMARY KEY, filename TEXT UNIQUE, source_format TEXT, recorded_at TEXT,
    distance_miles REAL, duration_minutes INTEGER, max_speed_mph REAL, avg_speed_mph REAL,
    elevation_gain_ft INTEGER, point_count INTEGER, shared INTEGER NOT NULL DEFAULT 0, ride_id TEXT, area_id TEXT
);
CREATE TABLE IF NOT EXISTS track_surveys (
    survey_id TEXT PRIMARY KEY, track_id TEXT NOT NULL, enjoyment INTEGER, ride_again INTEGER, submitted_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS waypoint_properties (
    waypoint_id TEXT PRIMARY KEY, description TEXT, elevation_ft INTEGER, source_format TEXT,
    source_file TEXT, shared INTEGER NOT NULL DEFAULT 0, ride_id TEXT, area_id TEXT
);
CREATE TABLE IF NOT EXISTS route_properties (
    route_id TEXT PRIMARY KEY, description TEXT, distance_miles REAL, elevation_gain_ft INTEGER,
    creation_method TEXT, source_track_id TEXT, trailhead_id TEXT, shared INTEGER NOT NULL DEFAULT 0, ride_id TEXT, area_id TEXT
);

-- ARTIFACT ALIASES (revised: identity-pointer model + creation_date TRACK-ALIAS-ONLY dedup)
CREATE TABLE IF NOT EXISTS artifact_aliases (
    alias_id        TEXT PRIMARY KEY,
    artifact_type   TEXT NOT NULL,
    artifact_id     TEXT NOT NULL,
    alias           TEXT NOT NULL,
    is_preferred    INTEGER NOT NULL DEFAULT 0,
    source          TEXT,
    created_at      TEXT NOT NULL,
    creation_date   TEXT,
    geom_hash       TEXT,
    UNIQUE(artifact_type, artifact_id, alias),
    UNIQUE(artifact_type, geom_hash, creation_date)
);
CREATE INDEX IF NOT EXISTS idx_aliases_lookup ON artifact_aliases(artifact_type, artifact_id);
CREATE INDEX IF NOT EXISTS idx_aliases_pref ON artifact_aliases(artifact_type, artifact_id, is_preferred);
CREATE INDEX IF NOT EXISTS idx_aliases_geomhash ON artifact_aliases(artifact_type, geom_hash);

CREATE TABLE IF NOT EXISTS trail_sources (
    source_id TEXT PRIMARY KEY, name TEXT NOT NULL, agency TEXT, url TEXT NOT NULL, format TEXT NOT NULL,
    trail_types TEXT, coverage_state TEXT, boundary TEXT, approved INTEGER NOT NULL DEFAULT 1,
    last_checked_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS source_ingestions (
    ingestion_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, version_ingested TEXT, ingested_at TEXT NOT NULL,
    trail_count INTEGER DEFAULT 0, dupes_skipped INTEGER DEFAULT 0, filters_used TEXT, bounds_json TEXT
);
CREATE TABLE IF NOT EXISTS tile_queue (
    queue_id TEXT PRIMARY KEY, job_type TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued',
    priority INTEGER NOT NULL DEFAULT 5, tile_source TEXT, zoom_level INTEGER, region_json TEXT,
    total_tiles INTEGER DEFAULT 0, completed_tiles INTEGER DEFAULT 0, progress_pct REAL DEFAULT 0.0,
    drop_point TEXT, retry_count INTEGER DEFAULT 0, max_retries INTEGER DEFAULT 3, last_error TEXT,
    created_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tile_q ON tile_queue(status, priority, created_at);
CREATE TABLE IF NOT EXISTS upload_queue (
    queue_id TEXT PRIMARY KEY, artifact_type TEXT NOT NULL, artifact_id TEXT NOT NULL, artifact_name TEXT,
    status TEXT NOT NULL DEFAULT 'queued', priority INTEGER NOT NULL DEFAULT 5, total_bytes INTEGER DEFAULT 0,
    transferred_bytes INTEGER DEFAULT 0, progress_pct REAL DEFAULT 0.0, drop_point TEXT, retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3, last_error TEXT, created_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_upload_q ON upload_queue(status, priority, created_at);
CREATE TABLE IF NOT EXISTS download_queue (
    queue_id TEXT PRIMARY KEY, artifact_type TEXT NOT NULL, artifact_id TEXT, artifact_name TEXT,
    status TEXT NOT NULL DEFAULT 'queued', priority INTEGER NOT NULL DEFAULT 5, area_json TEXT,
    total_items INTEGER DEFAULT 0, completed_items INTEGER DEFAULT 0, total_bytes INTEGER DEFAULT 0,
    transferred_bytes INTEGER DEFAULT 0, progress_pct REAL DEFAULT 0.0, drop_point TEXT, retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3, last_error TEXT, server_url TEXT, created_at TEXT NOT NULL, started_at TEXT,
    completed_at TEXT, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_download_q ON download_queue(status, priority, created_at);
CREATE TABLE IF NOT EXISTS transfer_history (
    history_id INTEGER PRIMARY KEY AUTOINCREMENT, queue_type TEXT NOT NULL, queue_id TEXT NOT NULL,
    artifact_type TEXT, artifact_name TEXT, total_items INTEGER DEFAULT 0, total_bytes INTEGER DEFAULT 0,
    result TEXT NOT NULL, error_summary TEXT, started_at TEXT, completed_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS data_log (
    log_id INTEGER PRIMARY KEY AUTOINCREMENT, artifact_type TEXT NOT NULL, artifact_id TEXT NOT NULL,
    action TEXT NOT NULL, before_state TEXT, after_state TEXT, geometry_wkt TEXT, performed_at TEXT NOT NULL, performed_by TEXT
);
CREATE INDEX IF NOT EXISTS idx_log_artifact ON data_log(artifact_type, artifact_id);
CREATE INDEX IF NOT EXISTS idx_log_action ON data_log(action, performed_at);
CREATE TABLE IF NOT EXISTS proximity_config (
    artifact_type TEXT PRIMARY KEY, threshold REAL NOT NULL, unit TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1
);
INSERT OR IGNORE INTO proximity_config VALUES ('waypoint', 100.0, 'meters', 1);
INSERT OR IGNORE INTO proximity_config VALUES ('trail', 80.0, 'overlap_pct', 1);
INSERT OR IGNORE INTO proximity_config VALUES ('track', 70.0, 'overlap_pct', 1);
INSERT OR IGNORE INTO proximity_config VALUES ('route', 85.0, 'overlap_pct', 1);
CREATE TABLE IF NOT EXISTS ride_areas (
    area_id TEXT PRIMARY KEY, name TEXT NOT NULL, area_type TEXT NOT NULL DEFAULT 'personal', geometry TEXT,
    ride_id TEXT, has_tiles INTEGER NOT NULL DEFAULT 0, has_spatial INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS area_downloads (
    download_id TEXT PRIMARY KEY, artifact_type TEXT NOT NULL, source_id TEXT, direction TEXT NOT NULL DEFAULT 'download',
    bounds_json TEXT NOT NULL, item_count INTEGER DEFAULT 0, ride_id TEXT, created_at TEXT NOT NULL, completed_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_area_dl_type ON area_downloads(artifact_type, direction);
CREATE INDEX IF NOT EXISTS idx_area_dl_ride ON area_downloads(ride_id);

CREATE VIEW IF NOT EXISTS v_preferred_aliases AS
SELECT artifact_type, artifact_id, alias FROM artifact_aliases WHERE is_preferred = 1;
CREATE VIEW IF NOT EXISTS v_trail_display AS
SELECT t.trail_id, COALESCE(a.alias, t.name, tp.agency_id) as display_name, t.geometry,
       tp.motorized_allowed, tp.surface_type, tp.carto_code, tp.owner_steward, tp.status, tp.shared
FROM trails t LEFT JOIN trail_properties tp ON t.trail_id = tp.trail_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type='trail' AND a.artifact_id=t.trail_id;
CREATE VIEW IF NOT EXISTS v_track_display AS
SELECT t.track_id, COALESCE(a.alias, t.name) as display_name, t.bbox,
       tp.distance_miles, tp.duration_minutes, tp.source_format, tp.shared
FROM tracks t LEFT JOIN track_properties tp ON t.track_id = tp.track_id
LEFT JOIN v_preferred_aliases a ON a.artifact_type='track' AND a.artifact_id=t.track_id;
CREATE VIEW IF NOT EXISTS v_tile_queue_active AS
SELECT * FROM tile_queue WHERE status IN ('queued','in_progress','failed') ORDER BY priority, created_at;
CREATE VIEW IF NOT EXISTS v_upload_queue_active AS
SELECT * FROM upload_queue WHERE status IN ('queued','in_progress','failed') ORDER BY priority, created_at;
CREATE VIEW IF NOT EXISTS v_download_queue_active AS
SELECT * FROM download_queue WHERE status IN ('queued','in_progress','failed') ORDER BY priority, created_at;
