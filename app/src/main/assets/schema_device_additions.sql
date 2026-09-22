CREATE TABLE IF NOT EXISTS users (
    user_id         TEXT PRIMARY KEY,
    first_name      TEXT,
    last_name       TEXT,
    email           TEXT,
    cell            TEXT,
    zip_code        TEXT,
    callsign        TEXT,
    default_role    TEXT NOT NULL DEFAULT 'rider'
                    CHECK (default_role IN ('leader','rider','middle','tail_gunner')),
    vehicle_type    TEXT,
    team            TEXT,
    is_organizer    INTEGER NOT NULL DEFAULT 0,
    is_self         INTEGER NOT NULL DEFAULT 0,
    primary_org_id  TEXT,
    config_mode     TEXT NOT NULL DEFAULT 'own'
                    CHECK (config_mode IN ('own','inherit_org')),
    config_id       TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    CHECK (is_self = 0 OR email IS NOT NULL)
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
    config_id       TEXT,
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
CREATE TABLE IF NOT EXISTS rides (
    ride_id                 TEXT PRIMARY KEY,
    organizer_id            TEXT,
    organizer_name          TEXT NOT NULL,
    org_id                  TEXT,
    org_name                TEXT,
    route_id                TEXT,
    trailhead_waypoint_id   TEXT,
    ride_name               TEXT NOT NULL,
    ride_date               TEXT NOT NULL,
    start_time              TEXT,
    description             TEXT,
    zip_code                TEXT,
    is_public               INTEGER NOT NULL DEFAULT 0,
    config_mode             TEXT NOT NULL DEFAULT 'inherit_leader'
                            CHECK (config_mode IN ('inherit_org','inherit_leader','unique')),
    config_id               TEXT,
    expires_at              TEXT,
    created_at              TEXT NOT NULL,
    updated_at              TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rides_date ON rides(ride_date);
CREATE INDEX IF NOT EXISTS idx_rides_organizer ON rides(organizer_id);
CREATE TABLE IF NOT EXISTS enrollments (
    enrollment_id   TEXT PRIMARY KEY,
    ride_id         TEXT NOT NULL,
    user_id         TEXT,
    callsign        TEXT NOT NULL,
    display_name    TEXT,
    role            TEXT NOT NULL DEFAULT 'rider'
                    CHECK (role IN ('leader','rider','middle','tail_gunner')),
    team            TEXT,
    vehicle_type    TEXT,
    node_num        INTEGER,
    tak_uid         TEXT,
    device_callsign TEXT,
    created_by      TEXT NOT NULL CHECK (created_by IN ('login','packet')),
    enrolled_at     TEXT NOT NULL,
    first_seen      TEXT,
    last_seen       TEXT
);
CREATE INDEX IF NOT EXISTS idx_enroll_ride ON enrollments(ride_id);
CREATE INDEX IF NOT EXISTS idx_enroll_node ON enrollments(ride_id, node_num);
CREATE INDEX IF NOT EXISTS idx_enroll_tak ON enrollments(ride_id, tak_uid);
CREATE TABLE IF NOT EXISTS ride_surveys (
    survey_id       TEXT PRIMARY KEY,
    track_id        TEXT NOT NULL,
    ride_id         TEXT,
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
    network_ssid     TEXT,
    network_password TEXT,
    tak_sdk_version  TEXT,
    created_at       TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sync_keys (
    entity_type     TEXT NOT NULL,
    local_key       TEXT NOT NULL,
    server_key      TEXT NOT NULL,
    synced_at       TEXT NOT NULL,
    PRIMARY KEY (entity_type, local_key),
    UNIQUE (entity_type, server_key)
);
CREATE TABLE IF NOT EXISTS radio_snapshots (
    snapshot_id     TEXT PRIMARY KEY,
    node_num        INTEGER NOT NULL,
    config_id       TEXT,
    base_version    INTEGER,
    values_json     TEXT NOT NULL,
    taken_at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_snap_node ON radio_snapshots(node_num, taken_at);
