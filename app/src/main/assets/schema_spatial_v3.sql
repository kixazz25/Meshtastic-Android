-- =====================================================================
-- ADD-RULES CONTRACT  (read before touching any artifact insert path)
-- =====================================================================
-- ALL FOUR artifact types (trail, track, waypoint, route) MUST be written
-- through the ONE shared add function in SpatialDbManager. Do NOT write raw
-- INSERTs to these tables from any other code path. The add function is the
-- single place that enforces the identity + dedup rules below; bypassing it
-- silently breaks dedup.
--
-- The shared add function is responsible for, on every insert:
--   1. geom_hash = hash of the full WKT geometry string (computed in code, not
--      in SQL). This is the identity. geom_hash is nullable in-schema ON PURPOSE
--      -- the rule is enforced in the add function for maintainability, not by a
--      NOT NULL column. If the add function fails to set it, dedup will not fire.
--   2. name fallback: if name is null/empty, substitute the literal 'Not Named'.
--      Applied in the add function for ALL four artifacts.
--   3. Identity / dedup key = composite (artifact_type, geom_hash). Because each
--      artifact type is its OWN table, UNIQUE(geom_hash) per table expresses this
--      (type is implicit). Snap-2 routes may share a trail's geometry -> they
--      coexist safely because they live in different tables (different types).
--   4. Dedup decision per type:
--        trail / route / waypoint : geom match + same name = DROP (duplicate)
--                                   geom match + diff name = ALIAS (add alias row)
--                                   new geom               = INSERT
--        track  : entity key = (artifact_type, geom_hash), named by first creation.
--                 track ROWS are the user's own recordings -- NOT deduped.
--                 submission dedup = (name, geom_hash).
--                 ALIAS dedup = (artifact_type, geom_hash, creation_date) -- a match
--                 is a duplicate, NO new alias. (Collapses same-day group rides:
--                 20 riders / same path / same day -> 1 community alias, first wins.)
--   5. Aliases are POINTERS: artifact_aliases carries (artifact_type, artifact_id,
--      alias) + geom_hash + creation_date. The hash lives on the ARTIFACT row (the
--      anchor); the alias table only points to it and carries the name.
--   6. creation_date affects TRACK ALIASES ONLY (named generically for possible
--      future reuse by other types).
-- =====================================================================

-- GroupTrack Spatial Schema v3 (revised: geom_hash identity model)
-- SQLite with WKT geometry (portable to iOS)
-- File: grouptrack_spatial.db
-- IDENTITY MODEL: composite key (artifact_type, geom_hash). Per-type tables, so geom_hash
--   alone is unique within each table (type implicit). geom_hash = hash of full WKT, computed
--   at insert. Snap-2 routes legitimately share geometry with trails -> safe because they are
--   different tables/types. creation_date affects TRACK ALIASES ONLY (see extension schema).

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL,
    applied_at  TEXT NOT NULL
);
INSERT INTO schema_version VALUES (3, datetime('now'));

-- 1.1 TRAILS  (identity = geom_hash; name is descriptive/first-creation primary)
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
    geom_hash   TEXT NOT NULL,
    UNIQUE(geom_hash)
);

-- 1.2 TRACKS  (entity identity = geom_hash; track ROWS are the user's own recordings.
--   Alias-level creation_date dedup lives in extension artifact_aliases, NOT here.)
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
    geom_hash   TEXT NOT NULL,
    UNIQUE(geom_hash)
);

-- 1.3 WAYPOINTS  (identity = geom_hash)
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
    geom_hash   TEXT NOT NULL,
    UNIQUE(geom_hash)
);

-- 1.4 ROUTES  (identity = geom_hash; snap-2 routes may match a trail's geometry -> fine, different table)
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
    geom_hash   TEXT NOT NULL,
    UNIQUE(geom_hash)
);

CREATE INDEX IF NOT EXISTS idx_trails_name      ON trails(name);
CREATE INDEX IF NOT EXISTS idx_trails_bbox      ON trails(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_trails_geomhash  ON trails(geom_hash);
CREATE INDEX IF NOT EXISTS idx_tracks_name      ON tracks(name);
CREATE INDEX IF NOT EXISTS idx_tracks_bbox      ON tracks(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_tracks_type      ON tracks(type);
CREATE INDEX IF NOT EXISTS idx_tracks_geomhash  ON tracks(geom_hash);
CREATE INDEX IF NOT EXISTS idx_waypoints_type   ON waypoints(type);
CREATE INDEX IF NOT EXISTS idx_waypoints_bbox   ON waypoints(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_waypoints_geomhash ON waypoints(geom_hash);
CREATE INDEX IF NOT EXISTS idx_routes_name      ON routes(name);
CREATE INDEX IF NOT EXISTS idx_routes_bbox      ON routes(min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_routes_geomhash  ON routes(geom_hash);
