-- =====================================================================
-- GroupTrack — AWS mirror of the DEVICE data model          2026-09-22 v2
-- MARKER: SPATIALMIRROR-2026-09-22
-- v2: adds `contributions` (server-only) — who contributed each artifact and survey.
-- =====================================================================
-- WHY (Fred, 2026-09-22): the device model moved routes, tracks, waypoints
-- and trails into the spatial database; the AWS model was never updated for
-- that move. Ownership comes FROM V3 (orgs, leaders); spatial data and maps
-- come FROM THE DEVICE. These tables are that device model on the server,
-- created EMPTY now so 3.0 has a target that already matches the device.
--
-- SOURCES — mirrored exactly, table and column names unchanged:
--   app/src/main/assets/schema_spatial_v3.sql    -> database grouptrack_spatial
--   app/src/main/assets/schema_extension_v3.sql  -> database grouptrack_data
--   SpatialDbManager.kt : ALTER trails ADD status (:263), route_notes (:414)
--
-- TWO DATABASES, named like the device files, so every table keeps its device
-- name and nothing collides with convoy_tracker (which has its own `waypoints`).
-- MySQL joins across databases on the same instance when 3.0 links rides.
--
-- NOT MIRRORED — device-only operation, never shared:
--   tile_queue, upload_queue, download_queue, transfer_history, data_log,
--   proximity_config, area_downloads, the views, and the OSM working tables
--   (reference_points, subset_meta, osm_way_tags).
--
-- ⛔ HARD RULE (Fred 07-26): NO OSM-DERIVED DATA ON GROUPTRACK INFRASTRUCTURE.
--   ODbL share-alike. Rows imported from Geofabrik/OSM sources must NEVER be
--   uploaded to these tables. Enforced in the 3.0 upload path.
--
-- PRINCIPLE (Fred 09-22): COLLECT NOW, GATHER LATER. Data that gives the
--   community value at launch accumulates on riders' tablets in tables shaped
--   like the server's, so launch is gathering, not starting. Each piece is
--   switched on when it makes sense.
-- FIRST DIRECTION (Fred): riders' tracks and the trails grown from them are
--   PUSHED up, each track carrying its rider's SURVEY. Only rows the rider marked
--   `shared`. Collapse the GEOMETRY (the alias rule: 20 riders / same path / same
--   day -> one community alias) — NEVER the surveys: every rider's survey counts once.
-- SYNC: one direction only until 3.0 (Fred). Nothing writes here yet.
--   Surveys and ratings grow in 3.0 — advance schema_version on BOTH sides.
--
-- TYPE TRANSLATION (SQLite -> MySQL 8):
--   ids / keys / hashes TEXT -> VARCHAR (MySQL cannot index plain TEXT)
--   geometry (WKT) TEXT     -> LONGTEXT      REAL -> DOUBLE     INTEGER -> INT
--   timestamps TEXT         -> VARCHAR(32), ISO-8601 strings exactly as on the
--                              device — same representation both ends for now;
--                              revisit in the 3.0 review.
--   Views are not mirrored.
--
-- Safe to re-run: every statement is IF NOT EXISTS.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS grouptrack_spatial CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS grouptrack_data    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- =====================================================================
-- grouptrack_spatial   (device: grouptrack_spatial.db)
-- =====================================================================
USE grouptrack_spatial;

CREATE TABLE IF NOT EXISTS schema_version (
    version     INT          NOT NULL,
    applied_at  VARCHAR(32)  NOT NULL
);
INSERT INTO schema_version (version, applied_at)
SELECT 3, DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%d %H:%i:%s')
WHERE NOT EXISTS (SELECT 1 FROM schema_version WHERE version = 3);

CREATE TABLE IF NOT EXISTS trails (
    trail_id    VARCHAR(64)  PRIMARY KEY,
    name        TEXT,
    geometry    LONGTEXT,
    min_lat     DOUBLE, max_lat DOUBLE, min_lon DOUBLE, max_lon DOUBLE,
    created_at  VARCHAR(32)  NOT NULL,
    updated_at  VARCHAR(32)  NOT NULL,
    carto_code  VARCHAR(64),
    source_id   VARCHAR(128),
    geom_hash   VARCHAR(64)  NOT NULL,
    status      VARCHAR(64),                      -- device: ALTER, SpatialDbManager.kt:263
    UNIQUE KEY uq_trails_geomhash (geom_hash),
    KEY idx_trails_name (name(191)),
    KEY idx_trails_bbox (min_lat, max_lat, min_lon, max_lon)
);

CREATE TABLE IF NOT EXISTS tracks (
    track_id    VARCHAR(64)  PRIMARY KEY,
    name        TEXT,
    bbox        TEXT,
    created_at  VARCHAR(32)  NOT NULL,
    updated_at  VARCHAR(32)  NOT NULL,
    geometry    LONGTEXT,
    min_lat     DOUBLE, max_lat DOUBLE, min_lon DOUBLE, max_lon DOUBLE,
    type        VARCHAR(32)  NOT NULL DEFAULT 'TRACK',
    geom_hash   VARCHAR(64)  NOT NULL,
    UNIQUE KEY uq_tracks_geomhash (geom_hash),
    KEY idx_tracks_name (name(191)),
    KEY idx_tracks_bbox (min_lat, max_lat, min_lon, max_lon),
    KEY idx_tracks_type (type)
);

CREATE TABLE IF NOT EXISTS waypoints (
    waypoint_id VARCHAR(64)  PRIMARY KEY,
    name        TEXT         NOT NULL,
    type        VARCHAR(64)  NOT NULL,
    geometry    LONGTEXT,
    min_lat     DOUBLE, max_lat DOUBLE, min_lon DOUBLE, max_lon DOUBLE,
    created_at  VARCHAR(32)  NOT NULL,
    updated_at  VARCHAR(32)  NOT NULL,
    geom_hash   VARCHAR(64)  NOT NULL,
    UNIQUE KEY uq_waypoints_geomhash (geom_hash),
    KEY idx_waypoints_type (type),
    KEY idx_waypoints_bbox (min_lat, max_lat, min_lon, max_lon)
);

CREATE TABLE IF NOT EXISTS routes (
    route_id    VARCHAR(64)  PRIMARY KEY,
    name        TEXT         NOT NULL,
    geometry    LONGTEXT,
    min_lat     DOUBLE, max_lat DOUBLE, min_lon DOUBLE, max_lon DOUBLE,
    created_at  VARCHAR(32)  NOT NULL,
    updated_at  VARCHAR(32)  NOT NULL,
    geom_hash   VARCHAR(64)  NOT NULL,
    UNIQUE KEY uq_routes_geomhash (geom_hash),
    KEY idx_routes_name (name(191)),
    KEY idx_routes_bbox (min_lat, max_lat, min_lon, max_lon)
);

-- =====================================================================
-- grouptrack_data   (device: grouptrack_data.db)
-- =====================================================================
USE grouptrack_data;

CREATE TABLE IF NOT EXISTS schema_version (
    version     INT          NOT NULL,
    applied_at  VARCHAR(32)  NOT NULL
);
INSERT INTO schema_version (version, applied_at)
SELECT 3, DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%d %H:%i:%s')
WHERE NOT EXISTS (SELECT 1 FROM schema_version WHERE version = 3);

CREATE TABLE IF NOT EXISTS trail_properties (
    trail_id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(128), source_unique_id VARCHAR(255), designated_uses TEXT,
    motorized_allowed TEXT, horse_allowed TEXT, surface_type TEXT, trail_class TEXT, carto_code VARCHAR(64),
    hike_difficulty TEXT, bike_difficulty TEXT, ada_accessible TEXT, owner_steward TEXT, county TEXT,
    recreation_area TEXT, system_name TEXT, trans_network TEXT, data_source TEXT, agency_id TEXT,
    status TEXT, comments TEXT, distance_miles DOUBLE, elevation_gain_ft INT,
    shared TINYINT(1) NOT NULL DEFAULT 0,
    source_created_at VARCHAR(32), source_updated_at VARCHAR(32), ingested_at VARCHAR(32),
    ref_code TEXT, operator TEXT, width_raw TEXT, maxwidth_raw TEXT,
    incline TEXT, sac_scale TEXT, mtb_scale TEXT, trail_visibility TEXT,
    other_restrictions TEXT
);

CREATE TABLE IF NOT EXISTS track_properties (
    track_id VARCHAR(64) PRIMARY KEY,
    filename VARCHAR(512), source_format VARCHAR(32), recorded_at VARCHAR(32),
    distance_miles DOUBLE, duration_minutes INT, max_speed_mph DOUBLE, avg_speed_mph DOUBLE,
    elevation_gain_ft INT, point_count INT,
    shared TINYINT(1) NOT NULL DEFAULT 0,
    ride_id VARCHAR(64), area_id VARCHAR(64),
    UNIQUE KEY uq_track_props_filename (filename)
);

CREATE TABLE IF NOT EXISTS track_surveys (
    survey_id VARCHAR(64) PRIMARY KEY,
    track_id VARCHAR(64) NOT NULL,
    enjoyment INT, ride_again INT,
    submitted_at VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS waypoint_properties (
    waypoint_id VARCHAR(64) PRIMARY KEY,
    description TEXT, elevation_ft INT, source_format VARCHAR(32), source_file TEXT,
    shared TINYINT(1) NOT NULL DEFAULT 0,
    ride_id VARCHAR(64), area_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS route_properties (
    route_id VARCHAR(64) PRIMARY KEY,
    description TEXT, distance_miles DOUBLE, elevation_gain_ft INT,
    creation_method VARCHAR(64), source_track_id VARCHAR(64), trailhead_id VARCHAR(64),
    shared TINYINT(1) NOT NULL DEFAULT 0,
    ride_id VARCHAR(64), area_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS artifact_aliases (
    alias_id        VARCHAR(64)  PRIMARY KEY,
    artifact_type   VARCHAR(32)  NOT NULL,
    artifact_id     VARCHAR(64)  NOT NULL,
    alias           VARCHAR(255) NOT NULL,
    is_preferred    TINYINT(1)   NOT NULL DEFAULT 0,
    source          VARCHAR(128),
    created_at      VARCHAR(32)  NOT NULL,
    creation_date   VARCHAR(32),
    geom_hash       VARCHAR(64),
    UNIQUE KEY uq_alias_name (artifact_type, artifact_id, alias),
    UNIQUE KEY uq_alias_trackday (artifact_type, geom_hash, creation_date),
    KEY idx_aliases_lookup (artifact_type, artifact_id),
    KEY idx_aliases_pref (artifact_type, artifact_id, is_preferred),
    KEY idx_aliases_geomhash (artifact_type, geom_hash)
);

CREATE TABLE IF NOT EXISTS route_notes (          -- device: SpatialDbManager.kt:414 — the route NARRATIVE
    note_id     VARCHAR(64) PRIMARY KEY,
    route_id    VARCHAR(64) NOT NULL,
    geom_hash   VARCHAR(64),
    seq         INT DEFAULT 0,
    kind        VARCHAR(64), at_mile DOUBLE, lat DOUBLE, lon DOUBLE,
    title TEXT, body TEXT, payload TEXT,
    author      VARCHAR(64) DEFAULT 'generated',
    created_at  VARCHAR(32) NOT NULL,
    KEY idx_route_notes_route (route_id, seq),
    KEY idx_route_notes_hash (geom_hash)
);

CREATE TABLE IF NOT EXISTS ride_areas (
    area_id     VARCHAR(64)  PRIMARY KEY,
    name        TEXT         NOT NULL,
    area_type   VARCHAR(32)  NOT NULL DEFAULT 'personal',
    geometry    LONGTEXT,
    ride_id     VARCHAR(64),
    has_tiles   TINYINT(1)   NOT NULL DEFAULT 0,
    has_spatial TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  VARCHAR(32)  NOT NULL,
    updated_at  VARCHAR(32)  NOT NULL
);

CREATE TABLE IF NOT EXISTS trail_sources (
    source_id VARCHAR(128) PRIMARY KEY,
    name TEXT NOT NULL, agency TEXT, url TEXT NOT NULL, format VARCHAR(64) NOT NULL,
    trail_types TEXT, coverage_state VARCHAR(64), boundary TEXT,
    approved TINYINT(1) NOT NULL DEFAULT 1,
    last_checked_at VARCHAR(32), created_at VARCHAR(32) NOT NULL, updated_at VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS source_ingestions (
    ingestion_id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(128) NOT NULL, version_ingested TEXT, ingested_at VARCHAR(32) NOT NULL,
    trail_count INT DEFAULT 0, dupes_skipped INT DEFAULT 0, filters_used TEXT, bounds_json TEXT
);


-- =====================================================================
-- SERVER-ONLY (not on the device — a tablet has one owner)
-- contributions: who contributed each artifact or survey. Keeps the mirrored
-- tables identical to the device while ownership lives beside them. One row per
-- (artifact, contributor): a rider counts once and can update their own survey.
-- user_id = convoy_tracker.users.user_id.
-- =====================================================================
CREATE TABLE IF NOT EXISTS contributions (
    contribution_id VARCHAR(64)  PRIMARY KEY,
    artifact_type   VARCHAR(32)  NOT NULL,     -- track | trail | route | waypoint | survey
    artifact_id     VARCHAR(64)  NOT NULL,
    geom_hash       VARCHAR(64),
    user_id         CHAR(36)     NOT NULL,
    ride_id         VARCHAR(64),
    contributed_at  VARCHAR(32)  NOT NULL,
    UNIQUE KEY uq_contribution (artifact_type, artifact_id, user_id),
    KEY idx_contrib_user (user_id),
    KEY idx_contrib_geomhash (artifact_type, geom_hash)
);

-- =====================================================================
-- Check: SHOW TABLES FROM grouptrack_spatial; SHOW TABLES FROM grouptrack_data;
-- Expect 5 and 12 tables (each includes schema_version), all empty.
-- =====================================================================
