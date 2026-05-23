-- V2 Migration: Add geometry and bbox columns to tracks
ALTER TABLE tracks ADD COLUMN geometry TEXT;
ALTER TABLE tracks ADD COLUMN min_lat REAL;
ALTER TABLE tracks ADD COLUMN max_lat REAL;
ALTER TABLE tracks ADD COLUMN min_lon REAL;
ALTER TABLE tracks ADD COLUMN max_lon REAL;
CREATE INDEX IF NOT EXISTS idx_tracks_bbox ON tracks(min_lat, max_lat, min_lon, max_lon);
