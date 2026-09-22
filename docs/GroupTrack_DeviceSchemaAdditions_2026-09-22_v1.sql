-- =====================================================================
-- GroupTrack — DEVICE schema additions for grouptrack_data.db   2026-09-22 v1
-- MARKER: DEVSCHEMA-2026-09-22
-- =====================================================================
-- WHAT: the V3-shaped tables the device collects into (convergence doc v15).
--   Ownership comes from V3; these carry V3's column names with LOCAL keys.
-- HOW IT IS SAFE:
--   * ADDITIVE ONLY — every statement is CREATE ... IF NOT EXISTS; no existing
--     table is altered, no row is touched. Safe to run on every launch.
--   * The version marker (.db_schema_version / db_schema_marker) and the
--     schema_*_v3.sql assets are NOT touched. schema_version stays at 3.
--   * DORMANT in 2.6h: nothing reads or writes these tables EXCEPT the survey
--     captured at track save (ride_surveys), which is live.
-- CONVENTIONS:
--   * Keys are LOCAL (TEXT UUIDs). Server keys appear only when data moves,
--     recorded in sync_keys — never in these tables ("no movement, no keys").
--   * Times are ISO-8601 UTC text, as everywhere else on the device.
--   * Optional references are optional BY DESIGN (org on a leader or ride,
--     ride on a survey, config on an 'own'/'unique' mode) — CODE RULE 1 reason.
--   * No ride_status: a ride is CLOSED on this device when this rider's track
--     for it is saved (derived, never stored).
-- =====================================================================

-- Riders: this device's own rider + every rider it meets.
--   callsign lives HERE (beside is_organizer), the same on every device.
--   is_organizer = CAPABILITY (can lead); the role on a ride is on enrollments.
--   is_self = device-only: marks this tablet's own rider.
CREATE TABLE IF NOT EXISTS users (
    user_id         TEXT PRIMARY KEY,
    first_name      TEXT,
    last_name       TEXT,
    email           TEXT,
    callsign        TEXT,
    is_organizer    INTEGER NOT NULL DEFAULT 0,
    is_self         INTEGER NOT NULL DEFAULT 0,
    primary_org_id  TEXT,                                   -- optional by design
    config_mode     TEXT NOT NULL DEFAULT 'own'
                    CHECK (config_mode IN ('own','inherit_org')),
    config_id       TEXT,                                   -- set when config_mode = 'own'
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_callsign ON users(callsign);

CREATE TABLE IF NOT EXISTS organizations (
    org_id          TEXT PRIMARY KEY,
    org_name        TEXT NOT NULL,
    org_type        TEXT NOT NULL DEFAULT 'club'
                    CHECK (org_type IN ('club','outfitter','private')),
    contact_email   TEXT,
    website         TEXT,
    zip_code        TEXT,
    config_id       TEXT,                                   -- the org's standing config
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS org_members (
    membership_id   TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'member'
                    CHECK (role IN ('owner','admin','member')),
    status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended')),
    joined_at       TEXT NOT NULL,
    UNIQUE(org_id, user_id)
);

-- Rides: replaces the convoy_events/*.json store as the record (the .convoy
--   file stays the transport). organizer_id = the ride's OWNER — never changes.
--   The LEAD is an operational role on enrollments and can be handed off.
--   Config: inherit through the leader, or unique if the leader asked for one.
CREATE TABLE IF NOT EXISTS rides (
    ride_id                 TEXT PRIMARY KEY,
    organizer_id            TEXT NOT NULL,                  -- every ride has a leader
    org_id                  TEXT,                           -- optional by design
    route_id                TEXT,                           -- grouptrack_spatial.db routes
    trailhead_waypoint_id   TEXT,                           -- grouptrack_spatial.db waypoints
    ride_name               TEXT NOT NULL,
    ride_date               TEXT NOT NULL,
    start_time              TEXT,
    description             TEXT,
    zip_code                TEXT,
    is_public               INTEGER NOT NULL DEFAULT 0,
    config_mode             TEXT NOT NULL DEFAULT 'inherit_leader'
                            CHECK (config_mode IN ('inherit_leader','unique')),
    config_id               TEXT,                           -- set when config_mode = 'unique'
    expires_at              TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rides_date ON rides(ride_date);
CREATE INDEX IF NOT EXISTS idx_rides_organizer ON rides(organizer_id);

-- Enrollments: per-ride data, assembled or overridden per ride.
--   Created at NETWORK LOGIN (or first packet) if missing — never in advance.
--   Each tablet builds its own roster; after the ride it is the ride's history.
--   role is per rider per ride. Exactly one 'leader' at a time (hand-off =
--   the current lead is released, then the new one claims).
CREATE TABLE IF NOT EXISTS enrollments (
    enrollment_id   TEXT PRIMARY KEY,
    ride_id         TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'rider'
                    CHECK (role IN ('leader','rider','middle','tail_gunner')),
    team            TEXT,
    vehicle_type    TEXT,
    node_num        INTEGER,                                -- the radio carried on THIS ride
    tak_uid         TEXT,
    device_callsign TEXT,
    created_by      TEXT NOT NULL CHECK (created_by IN ('login','packet')),
    enrolled_at     TEXT NOT NULL,
    first_seen      TEXT,
    last_seen       TEXT,
    UNIQUE(ride_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_enroll_ride ON enrollments(ride_id);
CREATE INDEX IF NOT EXISTS idx_enroll_node ON enrollments(ride_id, node_num);

-- Surveys: V3's field set, captured AT TRACK SAVE (recorded tracks only).
--   Keyed by the TRACK; the ride is attached when there is one.
--   track_donated = the share choice. Replaces track_surveys at 3.0 (which
--   stays in place untouched until then).
CREATE TABLE IF NOT EXISTS ride_surveys (
    survey_id       TEXT PRIMARY KEY,
    track_id        TEXT NOT NULL,                          -- grouptrack_spatial.db tracks
    ride_id         TEXT,                                   -- optional by design
    user_id         TEXT NOT NULL,
    rating          INTEGER CHECK (rating BETWEEN 1 AND 5),
    difficulty      TEXT CHECK (difficulty IN ('easy','moderate','hard')),
    recommend       INTEGER,
    notes           TEXT,
    track_donated   INTEGER NOT NULL DEFAULT 0,
    submitted_at    TEXT NOT NULL,
    UNIQUE(track_id, user_id)
);

CREATE TABLE IF NOT EXISTS follows (
    follow_id       TEXT PRIMARY KEY,
    follower_id     TEXT NOT NULL,
    following_id    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','muted')),
    created_at      TEXT NOT NULL,
    UNIQUE(follower_id, following_id)
);

-- Configs: IMMUTABLE — rotation makes a new one. config_id = the GT id, which
--   is the channel name and network name, identical on every device (no mapping).
--   Owner = org | leader | ride. The out-of-the-box SETUP config is NOT here —
--   it ships as an asset and is outside the ride model.
CREATE TABLE IF NOT EXISTS network_configs (
    config_id        TEXT PRIMARY KEY,
    owner_type       TEXT NOT NULL CHECK (owner_type IN ('org','leader','ride')),
    owner_id         TEXT NOT NULL,
    display_name     TEXT NOT NULL,
    base_version     INTEGER NOT NULL,
    region           TEXT NOT NULL,
    modem_preset     TEXT NOT NULL,
    hop_limit        INTEGER NOT NULL,
    tx_power         INTEGER NOT NULL,
    frequency_slot   INTEGER NOT NULL DEFAULT 0,
    psk              TEXT NOT NULL,
    role             TEXT NOT NULL,
    network_ssid     TEXT,                                  -- only when a Nucleus is used
    network_password TEXT,                                  -- only when a Nucleus is used
    tak_sdk_version  TEXT,
    created_at       TEXT NOT NULL
);

-- Key mapping: a row exists ONLY once a record has moved. Server keys never
--   appear anywhere else on the device.
CREATE TABLE IF NOT EXISTS sync_keys (
    entity_type     TEXT NOT NULL,
    local_key       TEXT NOT NULL,
    server_key      TEXT NOT NULL,
    synced_at       TEXT NOT NULL,
    PRIMARY KEY (entity_type, local_key),
    UNIQUE (entity_type, server_key)
);

-- Radio snapshots (G7): DEVICE ONLY — this tablet's own radio(s), across rides.
--   Written before every apply; restore writes values_json back.
CREATE TABLE IF NOT EXISTS radio_snapshots (
    snapshot_id     TEXT PRIMARY KEY,
    node_num        INTEGER NOT NULL,
    config_id       TEXT,                                   -- the config being applied
    base_version    INTEGER,
    values_json     TEXT NOT NULL,
    taken_at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_snap_node ON radio_snapshots(node_num, taken_at);
