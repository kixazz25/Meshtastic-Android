-- ============================================================
-- GroupTrack V3.0 Schema Update — ALTER TABLE + New Tables
-- Applies on top of: create_schema_v3_final.sql (13 tables)
-- Generated: April 1, 2026
-- Run: mysql -h [endpoint] -u convoy_admin -p convoy_tracker < schema_v3_update.sql
-- ============================================================
-- DESIGN DECISIONS:
-- ROLES: is_organizer set TRUE by API on first POST /rides.
--        Not a separate subscription tier. Everyone pays $3/mo.
--        Organizer Terms accepted before first ride creation — hard gate.
-- WAYPOINTS: Primary identity is (latitude, longitude).
--            Name is secondary unique key — human label for a place.
--            One record per unique coordinate pair — no duplicates.
--            Duplicate detection on insert: coords first, then name.
--            Organizer can rename a waypoint they own.
-- RIDES: trailhead_waypoint_id FK to waypoints — single source of truth.
--        No separate trailhead lat/lon/name columns on rides.
--        start_time added — ride date + time stored together.
-- RIDE SURVEYS: difficulty + recommend + track_donated added.
--              Aligns with s20 Stop Survey screen.
-- KML ROUTES: route_type column added — Route vs Track distinction
--             per s24 Import GPX/KML screen.
-- USERS: fcm_token, default_radius_miles, latitude, longitude added.
--        fcm_token for push notifications.
--        lat/lon + radius for explore rides area search.
-- ============================================================

-- ── 1. WAYPOINTS (new table) ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS waypoints (
    waypoint_id  CHAR(36)      PRIMARY KEY DEFAULT (UUID()),
    user_id      CHAR(36)      NOT NULL,
    name         VARCHAR(255)  NOT NULL,
    type         ENUM('trailhead','staging','hazard','poi') NOT NULL DEFAULT 'poi',
    latitude     DECIMAL(10,7) NOT NULL,
    longitude    DECIMAL(10,7) NOT NULL,
    notes        TEXT          NULL,
    is_public    BOOLEAN       DEFAULT FALSE,
    created_at   DATETIME      DEFAULT NOW(),
    updated_at   DATETIME      DEFAULT NOW() ON UPDATE NOW(),
    UNIQUE KEY uq_coords (latitude, longitude),
    UNIQUE KEY uq_name   (name),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- ── 2. ALTER users ────────────────────────────────────────────────────────
-- fcm_token: Firebase push notification token
-- latitude/longitude: home/default location for area search in Explore
-- default_radius_miles: search radius preference for Explore Rides + Track Library

ALTER TABLE users
    ADD COLUMN fcm_token            VARCHAR(255)   NULL         AFTER email_opt_in,
    ADD COLUMN latitude             DECIMAL(10,7)  NULL         AFTER fcm_token,
    ADD COLUMN longitude            DECIMAL(10,7)  NULL         AFTER latitude,
    ADD COLUMN default_radius_miles SMALLINT       DEFAULT 25   AFTER longitude;

-- ── 3. ALTER rides ────────────────────────────────────────────────────────
-- start_time: time of day for ride (date already stored in ride_date)
-- trailhead_waypoint_id: FK to waypoints — single source of truth for trailhead
--   Replaces any need for separate trailhead_lat/lon/name columns.
--   NULL until organizer sets trailhead via Set Trailhead screen (s28).
--   SAVE RIDE button disabled in app until this is set.

ALTER TABLE rides
    ADD COLUMN start_time            TIME          NULL         AFTER ride_date,
    ADD COLUMN trailhead_waypoint_id CHAR(36)      NULL         AFTER start_time,
    ADD CONSTRAINT fk_rides_trailhead
        FOREIGN KEY (trailhead_waypoint_id) REFERENCES waypoints(waypoint_id);

-- ── 4. ALTER kml_routes ───────────────────────────────────────────────────
-- route_type: distinguishes imported Route from donated Track
--   Route = planned path for a ride, used in map area calculation
--   Track = recorded GPS path, donated to community library
-- bounds: bounding box auto-calculated on import/creation
--   Used to auto-set map area on ride creation and drive tile download

ALTER TABLE kml_routes
    ADD COLUMN route_type    ENUM('route','track') NOT NULL DEFAULT 'route' AFTER route_name,
    ADD COLUMN bounds_north  DECIMAL(10,7)  NULL  AFTER file_hash,
    ADD COLUMN bounds_south  DECIMAL(10,7)  NULL  AFTER bounds_north,
    ADD COLUMN bounds_east   DECIMAL(10,7)  NULL  AFTER bounds_south,
    ADD COLUMN bounds_west   DECIMAL(10,7)  NULL  AFTER bounds_east;

-- ── 5. ALTER ride_surveys ─────────────────────────────────────────────────
-- difficulty: EASY / MODERATE / HARD — from s20 survey screen
-- recommend: YES/NO rider recommendation — drives recommend % in Track Library
-- track_donated: whether rider chose to donate their GPX track
-- track_route_id: FK to kml_routes if track was donated

ALTER TABLE ride_surveys
    ADD COLUMN difficulty    ENUM('easy','moderate','hard')  NULL  AFTER user_id,
    ADD COLUMN recommend     BOOLEAN                         NULL  AFTER difficulty,
    ADD COLUMN track_donated BOOLEAN                DEFAULT FALSE  AFTER recommend,
    ADD COLUMN track_route_id CHAR(36)               NULL         AFTER track_donated,
    ADD CONSTRAINT fk_survey_track
        FOREIGN KEY (track_route_id) REFERENCES kml_routes(route_id);

-- ── 6. New indexes ────────────────────────────────────────────────────────
CREATE INDEX idx_waypoints_user     ON waypoints(user_id);
CREATE INDEX idx_waypoints_type     ON waypoints(type);
CREATE INDEX idx_waypoints_public   ON waypoints(is_public);
CREATE INDEX idx_rides_trailhead    ON rides(trailhead_waypoint_id);
CREATE INDEX idx_rides_start        ON rides(ride_date, start_time);
CREATE INDEX idx_kml_type           ON kml_routes(route_type);
CREATE INDEX idx_kml_bounds         ON kml_routes(bounds_north, bounds_south, bounds_east, bounds_west);
CREATE INDEX idx_surveys_recommend  ON ride_surveys(recommend);
CREATE INDEX idx_users_location     ON users(latitude, longitude);
CREATE INDEX idx_users_fcm          ON users(fcm_token);

-- ── Verification queries ──────────────────────────────────────────────────
-- SHOW TABLES;
--   expect: 14 tables (13 original + waypoints)
--
-- DESCRIBE waypoints;
--   expect: waypoint_id, user_id, name, type, latitude, longitude, notes, is_public, created_at, updated_at
--
-- DESCRIBE rides;
--   expect: start_time and trailhead_waypoint_id present
--
-- DESCRIBE kml_routes;
--   expect: route_type, bounds_north/south/east/west present
--
-- DESCRIBE ride_surveys;
--   expect: difficulty, recommend, track_donated, track_route_id present
--
-- DESCRIBE users;
--   expect: fcm_token, latitude, longitude, default_radius_miles present
--
-- SHOW INDEX FROM waypoints;
--   expect: uq_coords and uq_name both Non_unique=0
