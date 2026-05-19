-- GroupTrack V2.5 Spatial Schema v1
-- SQLite with WKT geometry (portable to iOS)
-- File: grouptrack_spatial.db

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL,
    applied_at  TEXT NOT NULL
);
INSERT INTO schema_version VALUES (1, datetime('now'));

-- 1.1 TRAILS
CREATE TABLE IF NOT EXISTS trails (
    trail_id    TEXT PRIMARY KEY,
    name        TEXT,
    geometry    TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 1.2 TRACKS
CREATE TABLE IF NOT EXISTS tracks (
    track_id    TEXT PRIMARY KEY,
    name        TEXT,
    bbox        TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 1.3 WAYPOINTS
CREATE TABLE IF NOT EXISTS waypoints (
    waypoint_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL,
    geometry    TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- 1.4 ROUTES
CREATE TABLE IF NOT EXISTS routes (
    route_id    TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    geometry    TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

-- Indexes for text-search on geometry bounds
CREATE INDEX IF NOT EXISTS idx_trails_name ON trails(name);
CREATE INDEX IF NOT EXISTS idx_tracks_name ON tracks(name);
CREATE INDEX IF NOT EXISTS idx_waypoints_type ON waypoints(type);
CREATE INDEX IF NOT EXISTS idx_routes_name ON routes(name);
