-- GroupTrack Spatial Schema v3
-- SQLite with WKT geometry (portable to iOS)
-- File: grouptrack_spatial.db
-- v3 = full current column set (v1 + inline-migration cols: tracks geometry/bbox/type, trails carto_code)
--      PLUS dedupe foundation: geom_hash + UNIQUE(name, geom_hash) on trails/routes, source_id on trails.
-- Built fresh on install after one-time delete (regenerate-not-migrate). Data repopulated by re-import + track-sync.

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL,
    applied_at  TEXT NOT NULL
);
INSERT INTO schema_version VALUES (3, datetime('now'));

-- 1.1 TRAILS  (+ carto_code [was v4 ALTER], + source_id, + geom_hash, + UNIQUE(name, geom_hash))
CREATE TABLE IF NOT EXISTS trails (
    trail_id    TEXT PRIMARY KEY,
    name        TEXT,
    geometry    TEXT,
    min_lat     REAL,
    max_lat     REAL,
    min_lon     REAL,
    max_lon     REAL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    carto_code  TEXT,
    source_id   TEXT,
    geom_hash   TEXT,
    UNIQUE(name, geom_hash)
);

-- 1.2 TRACKS  (+ geometry/min/max [was v2 ALTER], + type [was v3 ALTER], + geom_hash)
CREATE TABLE IF NOT EXISTS tracks (
    track_id    TEXT PRIMARY KEY,
    name        TEXT,
    bbox        TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    geometry    TEXT,
    min_lat     REAL,
    max_lat     REAL,
    min_lon     REAL,
    max_lon     REAL,
    type        TEXT NOT NULL DEFAULT 'TRACK',
    geom_hash   TEXT
);

-- 1.3 WAYPOINTS  (unchanged columns; geom_hash for dedupe parity)
CREATE TABLE IF NOT EXISTS waypoints (
    waypoint_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL,
    geometry    TEXT,
    min_lat     REAL,
    max_lat     REAL,
    min_lon     REAL,
    max_lon     REAL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    geom_hash   TEXT
);

-- 1.4 ROUTES  (+ geom_hash, + UNIQUE(name, geom_hash))
CREATE TABLE IF NOT EXISTS routes (
    route_id    TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    geometry    TEXT,
    min_lat     REAL,
    max_lat     REAL,
    min_lon     REAL,
    max_lon     REAL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    geom_hash   TEXT,
    UNIQUE(name, geom_hash)
);

-- Indexes (incl. bbox on tracks/waypoints/routes for viewport queries + geom_hash for dedupe lookups)
CREATE INDEX IF NOT EXISTS idx_trails_name      ON trails(name);
CREATE INDEX IF NOT EXISTS idx_trails_bbox      ON trails(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_trails_geomhash  ON trails(geom_hash);
CREATE INDEX IF NOT EXISTS idx_tracks_name      ON tracks(name);
CREATE INDEX IF NOT EXISTS idx_tracks_bbox      ON tracks(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_tracks_type      ON tracks(type);
CREATE INDEX IF NOT EXISTS idx_tracks_geomhash  ON tracks(geom_hash);
CREATE INDEX IF NOT EXISTS idx_waypoints_type   ON waypoints(type);
CREATE INDEX IF NOT EXISTS idx_waypoints_bbox   ON waypoints(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_routes_name      ON routes(name);
CREATE INDEX IF NOT EXISTS idx_routes_bbox      ON routes(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_routes_geomhash  ON routes(geom_hash);
