-- ============================================================
-- GroupTrack V2.5 — Trails Database (shipped asset)
-- File: grouptrack_trails.spatialite
-- Location: app assets (compressed), decompressed to
--           /sdcard/Documents/GroupTrack/data/grouptrack_trails.spatialite
-- Access: READ-ONLY on device. Updated by app release.
-- ============================================================
-- Replaces the 22MB Utah GeoJSON asset.
-- Viewport-queried: Leaflet map boundary triggers spatial
-- query, loads only visible trails. Always available.
-- ============================================================

SELECT InitSpatialMetaData(1);

-- ============================================================
-- TRAILS — official agency data
-- ============================================================
-- Sources: UGRC, BLM, USFS, state agencies.
-- Built on Fred's PC via ingestion scripts per source.
-- Shipped compressed in APK. Decompressed on first launch.
-- New app release = new trails database = updated coverage.

CREATE TABLE trails (
    trail_id        TEXT PRIMARY KEY,                -- UUID
    name            TEXT,                            -- Trail name (normalized from source)
    source_agency   TEXT NOT NULL,                   -- UGRC, BLM, USFS, STATE_AZ, STATE_NV
    source_id       TEXT,                            -- Original ID from agency dataset
    surface_type    TEXT,                            -- dirt, gravel, paved, rock, sand, unknown
    difficulty      TEXT,                            -- easy, moderate, difficult, expert
    vehicle_type    TEXT,                            -- ohv, atv, utv, motorcycle, jeep, multi
    length_miles    REAL,                            -- Trail length
    elevation_gain_ft INTEGER,                       -- Total elevation gain
    region          TEXT,                            -- State: Utah, Arizona, Nevada
    county          TEXT,                            -- County name
    ingested_at     TEXT NOT NULL                    -- ISO 8601 when loaded
);

SELECT AddGeometryColumn('trails', 'geometry', 4326, 'LINESTRING', 'XY');
SELECT CreateSpatialIndex('trails', 'geometry');

-- ============================================================
-- METADATA — database version and coverage
-- ============================================================

CREATE TABLE db_metadata (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL
);

INSERT INTO db_metadata (key, value) VALUES
('version', '1'),
('built_at', '2026-05-09T00:00:00Z'),
('coverage', 'Utah'),
('trail_count', '0'),
('source_agencies', 'UGRC');

-- ============================================================
-- VIEWPORT QUERY (used by Android/Leaflet)
-- ============================================================
-- SELECT trail_id, name, source_agency, surface_type,
--        difficulty, vehicle_type, length_miles,
--        AsGeoJSON(geometry) as geojson
-- FROM trails
-- WHERE ROWID IN (
--     SELECT ROWID FROM SpatialIndex
--     WHERE f_table_name = 'trails'
--     AND search_frame = BuildMbr(west, south, east, north)
-- );
-- ============================================================
-- Returns GeoJSON per trail for Leaflet rendering.
-- Called on map pan/zoom. Fast via spatial index.
-- ============================================================
