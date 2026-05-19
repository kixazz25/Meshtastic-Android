-- GroupTrack V2.5 Spatial Schema Migration v1 -> v2
-- Adds bounding box columns for fast viewport queries

ALTER TABLE trails ADD COLUMN min_lat REAL;
ALTER TABLE trails ADD COLUMN max_lat REAL;
ALTER TABLE trails ADD COLUMN min_lon REAL;
ALTER TABLE trails ADD COLUMN max_lon REAL;

CREATE INDEX IF NOT EXISTS idx_trails_bbox ON trails(min_lat, max_lat, min_lon, max_lon);
