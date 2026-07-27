-- ============================================================
-- GroupTrack V2.5 — User Spatial Database Schema v2
-- File: grouptrack_user.spatialite
-- Location: /sdcard/Documents/GroupTrack/data/grouptrack_user.spatialite
-- Access: READ-WRITE. Survives app updates and uninstalls.
-- ============================================================
-- HARDENED SCHEMA — confirmed decisions May 10, 2026
-- All lifecycle triggers defined as placeholders
-- Do not modify schema without updating trigger plan
-- ============================================================

SELECT InitSpatialMetaData(1);

-- ============================================================
-- 1. TILE SOURCES — user-configurable map canvas
-- ============================================================

CREATE TABLE tile_sources (
    source_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    producer        TEXT NOT NULL,                   -- Google, Esri, USGS, CalTopo, onX, etc.
    map_type        TEXT NOT NULL,                   -- SAT | HYB | TOPO | STREET | TERRAIN | OUTDOOR | OVERLAY
    name            TEXT NOT NULL,                   -- Full display name
    short_label     TEXT NOT NULL,                   -- Abbreviated for map bar (max 8 chars)
    url_template    TEXT NOT NULL,                   -- URL with {x},{y},{z},{s},{key},{r}
    subdomains      TEXT,                            -- "0,1,2,3" or "a,b,c,d" or NULL
    requires_key    INTEGER NOT NULL DEFAULT 0,
    api_key         TEXT,                            -- user's key, local only, never uploaded
    api_key_param   TEXT,                            -- apikey | token | access_token | key
    api_registration_url TEXT,                       -- where user gets key
    api_cost_note   TEXT,                            -- "Free" | "Free tier: 150K/mo" | "$50/yr"
    attribution     TEXT,
    min_zoom        INTEGER NOT NULL DEFAULT 0,
    max_zoom        INTEGER NOT NULL DEFAULT 18,
    tile_size       INTEGER NOT NULL DEFAULT 256,
    tile_format     TEXT NOT NULL DEFAULT 'png',     -- png | jpg
    is_overlay      INTEGER NOT NULL DEFAULT 0,      -- 0=base, 1=transparent overlay
    is_default      INTEGER NOT NULL DEFAULT 0,      -- 1=shipped with app
    is_active       INTEGER NOT NULL DEFAULT 1,      -- 1=show in picker
    sort_order      INTEGER NOT NULL DEFAULT 0,
    notes           TEXT
);

-- ============================================================
-- 2. MAP SLOTS — three user-configurable map selections
-- ============================================================

CREATE TABLE map_slots (
    slot_number     INTEGER PRIMARY KEY CHECK(slot_number BETWEEN 1 AND 3),
    source_id       INTEGER NOT NULL REFERENCES tile_sources(source_id),
    label           TEXT NOT NULL,                   -- from tile_sources, shown on map bar
    short_label     TEXT NOT NULL                    -- abbreviated for narrow bar
);

-- ============================================================
-- 3. COMPOSITE LAYERS — multi-layer map sources
-- ============================================================
-- Some sources need base + overlay (e.g., Esri SAT + roads)
-- Each layer has its own URL for independent downloading

CREATE TABLE source_layers (
    layer_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id       INTEGER NOT NULL REFERENCES tile_sources(source_id),
    role            TEXT NOT NULL,                   -- 'base' | 'overlay'
    url_template    TEXT NOT NULL,
    tile_format     TEXT NOT NULL DEFAULT 'png',
    sort_order      INTEGER NOT NULL DEFAULT 0       -- rendering order
);

-- ============================================================
-- 4. WAYPOINTS — identified by coordinates, not names
-- ============================================================
-- Identity = lon/lat. Names are aliases in waypoint_aliases.
-- Creation includes proximity check: if waypoint exists
-- within 25m, add alias to existing instead of creating new.
-- NO MOVES — delete and recreate.
-- NO CONSOLIDATION — delete the extra.

CREATE TABLE waypoints (
    waypoint_id     TEXT PRIMARY KEY,                -- UUID
    type            TEXT NOT NULL,                   -- FK waypoint_types.type_code
    created_by      TEXT,                            -- user_id of creator. NULL = local user.
    is_owned        INTEGER NOT NULL DEFAULT 1,      -- 1=I created. 0=downloaded from collective.
    area_id         TEXT,                            -- FK ride_areas
    shared          INTEGER NOT NULL DEFAULT 0,      -- 0=local, 1=shared to collective
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

SELECT AddGeometryColumn('waypoints', 'geometry', 4326, 'POINT', 'XY');
SELECT CreateSpatialIndex('waypoints', 'geometry');

-- ============================================================
-- 5. WAYPOINT ALIASES — "known as" names per owner
-- ============================================================
-- Each waypoint can have multiple names from different users.
-- Display priority:
--   In ride context → organizer's alias
--   Personal context → my alias if exists, else primary
--   Tap waypoint → show ALL aliases
-- Rename creates alias, does not modify primary.

CREATE TABLE waypoint_aliases (
    alias_id        TEXT PRIMARY KEY,                -- UUID
    waypoint_id     TEXT NOT NULL REFERENCES waypoints(waypoint_id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT,
    owner_id        TEXT,                            -- user who created this alias. NULL = local user.
    is_primary      INTEGER NOT NULL DEFAULT 0,      -- 1 = original creator's name
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_alias_waypoint ON waypoint_aliases(waypoint_id);
CREATE INDEX idx_alias_owner ON waypoint_aliases(owner_id);

-- ============================================================
-- 6. WAYPOINT TYPES — icon and color definitions
-- ============================================================

CREATE TABLE waypoint_types (
    type_code       TEXT PRIMARY KEY,
    display_name    TEXT NOT NULL,
    icon_name       TEXT NOT NULL,
    color           TEXT NOT NULL,
    sort_order      INTEGER NOT NULL
);

INSERT INTO waypoint_types VALUES ('trailhead', 'Trailhead', 'flag', '#2E86C1', 1);
INSERT INTO waypoint_types VALUES ('fuel', 'Fuel', 'gas_pump', '#E67E22', 2);
INSERT INTO waypoint_types VALUES ('gate', 'Gate', 'barrier', '#C0392B', 3);
INSERT INTO waypoint_types VALUES ('hazard', 'Hazard', 'warning', '#F39C12', 4);
INSERT INTO waypoint_types VALUES ('scenic', 'Scenic', 'camera', '#27AE60', 5);
INSERT INTO waypoint_types VALUES ('water', 'Water', 'droplet', '#3498DB', 6);
INSERT INTO waypoint_types VALUES ('camp', 'Camp', 'tent', '#8E44AD', 7);
INSERT INTO waypoint_types VALUES ('parking', 'Parking', 'parking', '#7F8C8D', 8);
INSERT INTO waypoint_types VALUES ('rally', 'Rally Point', 'pin', '#E74C3C', 9);
INSERT INTO waypoint_types VALUES ('other', 'Other', 'circle', '#95A5A6', 10);

-- ============================================================
-- 7. ROUTES — never independently shared (configurable)
-- ============================================================
-- Sharing controlled by configurable setting.
-- Track rename cascades to route name via trigger.

CREATE TABLE routes (
    route_id        TEXT PRIMARY KEY,                -- UUID
    name            TEXT NOT NULL,
    description     TEXT,
    distance_miles  REAL,
    elevation_gain_ft INTEGER,
    source_format   TEXT,                            -- gpx | kml | drawn
    source_track_id TEXT,                            -- FK tracks. NULL if drawn/imported.
    created_by      TEXT,                            -- user_id of creator
    is_owned        INTEGER NOT NULL DEFAULT 1,
    area_id         TEXT,
    ride_id         TEXT,                            -- V3.0 ride assignment
    shared          INTEGER NOT NULL DEFAULT 0,      -- controlled by configurable setting
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

SELECT AddGeometryColumn('routes', 'geometry', 4326, 'LINESTRING', 'XY');
SELECT CreateSpatialIndex('routes', 'geometry');

-- ============================================================
-- 8. TRACKS — file is source of truth, DB is spatial index
-- ============================================================
-- GPX/KML files in Documents/my_tracks/
-- DB record = metadata + bounding box for spatial queries
-- Track rename = create alias (same model as waypoints)
-- Track "delete" = delete user's alias. Track deleted when no aliases remain.
-- Track rename cascades to route name (source_track_id link)

CREATE TABLE tracks (
    track_id        TEXT PRIMARY KEY,                -- UUID
    filename        TEXT NOT NULL UNIQUE,            -- file in my_tracks/
    recorded_at     TEXT,                            -- earliest GPS time from file
    distance_miles  REAL,
    duration_minutes INTEGER,
    max_speed_mph   REAL,
    elevation_gain_ft INTEGER,
    source_format   TEXT,                            -- gpx | kml
    created_by      TEXT,                            -- user_id
    is_owned        INTEGER NOT NULL DEFAULT 1,
    area_id         TEXT,
    ride_id         TEXT,                            -- V3.0 ride link
    shared          INTEGER NOT NULL DEFAULT 0,      -- 0=local, 1=shared
    created_at      TEXT NOT NULL
);

SELECT AddGeometryColumn('tracks', 'bbox', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('tracks', 'bbox');

-- ============================================================
-- 8a. TRACK ALIASES — "known as" names per owner
-- ============================================================
-- Same model as waypoint_aliases.
-- Track identity = the GPX/KML file. Names are aliases.
-- Rename creates alias. Delete removes alias.
-- Track deleted when no aliases remain.
-- Display: ride context = organizer's alias. Personal = mine. Tap = all.

CREATE TABLE track_aliases (
    alias_id        TEXT PRIMARY KEY,                -- UUID
    track_id        TEXT NOT NULL REFERENCES tracks(track_id) ON DELETE CASCADE,
    name            TEXT NOT NULL,                   -- this user's name for the track
    description     TEXT,                            -- this user's notes
    owner_id        TEXT,                            -- user who created this alias
    is_primary      INTEGER NOT NULL DEFAULT 0,      -- 1 = original creator's name
    created_at      TEXT NOT NULL
);

CREATE INDEX idx_track_alias_track ON track_aliases(track_id);
CREATE INDEX idx_track_alias_owner ON track_aliases(owner_id);

-- ============================================================
-- 9. RIDE AREAS — boundaries for tile management
-- ============================================================
-- Area delete = removes TILES only. Spatial data stays.
-- has_tiles: managed by tile download/delete operations
-- has_spatial: managed by triggers on waypoints/routes/tracks

CREATE TABLE ride_areas (
    area_id         TEXT PRIMARY KEY,                -- UUID
    name            TEXT NOT NULL,
    area_type       TEXT NOT NULL DEFAULT 'personal',-- personal | ride
    ride_id         TEXT,                            -- V3.0
    has_tiles       INTEGER NOT NULL DEFAULT 0,
    has_spatial     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

SELECT AddGeometryColumn('ride_areas', 'geometry', 4326, 'POLYGON', 'XY');
SELECT CreateSpatialIndex('ride_areas', 'geometry');

-- ============================================================
-- 10. SOURCE INGESTIONS — what trail sources are installed
-- ============================================================

CREATE TABLE source_ingestions (
    source_id       TEXT PRIMARY KEY,
    source_name     TEXT NOT NULL,
    version_ingested INTEGER NOT NULL,
    ingested_at     TEXT NOT NULL,
    trail_count     INTEGER DEFAULT 0,
    dupes_skipped   INTEGER DEFAULT 0,
    filters_used    TEXT,                            -- JSON of filter selections
    source_url      TEXT,
    bounds_json     TEXT                             -- JSON bounding box
);

-- ============================================================
-- 11. TRANSFER QUEUE — inbound/outbound data exchange
-- ============================================================

CREATE TABLE transfer_queue (
    queue_id        TEXT PRIMARY KEY,
    direction       TEXT NOT NULL,                   -- inbound | outbound
    data_type       TEXT NOT NULL,                   -- waypoints | routes | tracks | tiles
    status          TEXT NOT NULL DEFAULT 'queued',
    priority        INTEGER NOT NULL DEFAULT 5,
    area_id         TEXT,
    area_name       TEXT,
    item_id         TEXT,
    item_name       TEXT,
    total_items     INTEGER DEFAULT 0,
    completed_items INTEGER DEFAULT 0,
    total_bytes     INTEGER DEFAULT 0,
    transferred_bytes INTEGER DEFAULT 0,
    progress_pct    REAL DEFAULT 0.0,
    drop_point      TEXT,                            -- JSON for resume
    retry_count     INTEGER DEFAULT 0,
    max_retries     INTEGER DEFAULT 3,
    last_error      TEXT,
    server_url      TEXT,
    auth_token      TEXT,
    created_at      TEXT NOT NULL,
    started_at      TEXT,
    completed_at    TEXT,
    updated_at      TEXT NOT NULL
);

CREATE INDEX idx_queue_active ON transfer_queue(status, priority, created_at);

-- ============================================================
-- 12. TRANSFER HISTORY
-- ============================================================

CREATE TABLE transfer_history (
    history_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    queue_id        TEXT NOT NULL,
    direction       TEXT NOT NULL,
    data_type       TEXT NOT NULL,
    area_name       TEXT,
    item_name       TEXT,
    total_items     INTEGER DEFAULT 0,
    total_bytes     INTEGER DEFAULT 0,
    result          TEXT NOT NULL,
    error_summary   TEXT,
    started_at      TEXT,
    completed_at    TEXT NOT NULL
);

-- ============================================================
-- 13. CHANGE JOURNAL — every user data change logged
-- ============================================================
-- Before + after state as JSON. Geometry as WKT.
-- Enables single-operation undo, transaction rollback,
-- and point-in-time rollback.

CREATE TABLE change_journal (
    journal_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id  TEXT NOT NULL,
    sequence        INTEGER NOT NULL,
    timestamp       TEXT NOT NULL,
    table_name      TEXT NOT NULL,
    operation       TEXT NOT NULL,                   -- INSERT | UPDATE | DELETE
    record_id       TEXT NOT NULL,
    before_state    TEXT,                            -- JSON (NULL for INSERT)
    after_state     TEXT,                            -- JSON (NULL for DELETE)
    user_action     TEXT NOT NULL,                   -- human-readable description
    rolled_back     INTEGER DEFAULT 0
);

CREATE INDEX idx_journal_transaction ON change_journal(transaction_id);
CREATE INDEX idx_journal_timestamp ON change_journal(timestamp);
CREATE INDEX idx_journal_table ON change_journal(table_name, record_id);

-- ============================================================
-- 14. BACKUP LOG
-- ============================================================

CREATE TABLE backup_log (
    backup_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    filename        TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    trigger_reason  TEXT NOT NULL,
    description     TEXT,
    size_bytes      INTEGER NOT NULL,
    waypoint_count  INTEGER DEFAULT 0,
    route_count     INTEGER DEFAULT 0,
    track_count     INTEGER DEFAULT 0,
    area_count      INTEGER DEFAULT 0
);

-- ============================================================
-- 15. DATA LOG — audit trail
-- ============================================================

CREATE TABLE data_log (
    log_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    action          TEXT NOT NULL,
    source          TEXT,
    waypoint_count  INTEGER DEFAULT 0,
    route_count     INTEGER DEFAULT 0,
    track_count     INTEGER DEFAULT 0,
    performed_at    TEXT NOT NULL
);

-- ============================================================
-- 16. DEDUP LOG — trail duplicate resolution history
-- ============================================================

CREATE TABLE dedup_log (
    log_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    incoming_name   TEXT,
    incoming_source TEXT,
    existing_id     TEXT,
    existing_name   TEXT,
    existing_source TEXT,
    match_points    INTEGER,
    length_diff_pct REAL,
    resolution      TEXT,                            -- auto_skip | user_keep_existing | user_replace | user_keep_both
    resolved_at     TEXT
);

-- ============================================================
-- 17. APP SETTINGS — configurable rules
-- ============================================================

CREATE TABLE app_settings (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL,
    description     TEXT
);

INSERT INTO app_settings VALUES ('route_sharing_enabled', 'false', 'Allow users to share routes publicly. Configurable per user demand.');
INSERT INTO app_settings VALUES ('waypoint_proximity_meters', '25', 'Distance threshold for detecting existing waypoint at same location.');
INSERT INTO app_settings VALUES ('journal_retention_days', '30', 'Days to retain change journal entries before cleanup.');
INSERT INTO app_settings VALUES ('max_backups', '5', 'Maximum number of database backups to retain.');
INSERT INTO app_settings VALUES ('schema_version', '2', 'Current schema version. Used for migration checks.');

-- ============================================================
-- 18. LIFECYCLE TRIGGERS — COMPLETE USE CASE RULES
-- ============================================================
-- Every business rule from May 9-10 lifecycle discussions.
-- Implementation TBD but rules are FINAL. Do not re-litigate.
-- Trigger naming: trg_{table}_{timing}_{operation}
-- ============================================================

-- ===== WAYPOINT TRIGGERS =====

-- trg_waypoint_before_insert
-- RULE: WAYPOINT IDENTITY = COORDINATES
-- Before INSERT: query existing waypoints within proximity threshold
--   (app_settings.waypoint_proximity_meters, default 25m)
-- If match found within threshold:
--   RAISE ABORT with message 'EXISTING_WAYPOINT:{waypoint_id}'
--   Application code catches this, creates alias on existing waypoint instead
-- If no match: allow INSERT to proceed
-- RULE SOURCE: "waypoints are known by lon and lat"

-- trg_waypoint_after_insert
-- RULE: AREA COVERAGE — set has_spatial=1 on intersecting ride_areas
--   Query: SELECT area_id FROM ride_areas WHERE Intersects(geometry, NEW.geometry)
--   UPDATE ride_areas SET has_spatial=1 WHERE area_id IN (results)
-- RULE: JOURNAL — INSERT change_journal with:
--   operation='INSERT', after_state=JSON with geometry as AsText(NEW.geometry)
--   user_action='Created waypoint type [NEW.type]'
-- RULE: DATA LOG — INSERT data_log action='waypoint_created'

-- trg_waypoint_after_delete
-- NOTE: Users NEVER directly delete waypoints. They delete their ALIAS.
-- Waypoint deletion only happens via orphan cleanup when last alias removed.
-- This trigger handles the rare case of programmatic waypoint deletion.
-- RULE: OWNERSHIP CASCADE
--   If OLD.is_owned=1 AND OLD.shared=1:
--     Flag for collective removal in V3.0
--     "local deletes do not impact shared data unless I am removing something I created"
--   If OLD.is_owned=0:
--     Local delete only. Collective copy unaffected.
-- RULE: AREA COVERAGE RECALCULATE
--   Check: SELECT COUNT(*) FROM waypoints WHERE area_id=OLD.area_id
--          + SELECT COUNT(*) FROM routes WHERE area_id=OLD.area_id
--          + SELECT COUNT(*) FROM tracks WHERE area_id=OLD.area_id
--   If total = 0: UPDATE ride_areas SET has_spatial=0 WHERE area_id=OLD.area_id
-- RULE: JOURNAL — INSERT change_journal with:
--   operation='DELETE', before_state=JSON with geometry as AsText(OLD.geometry)
--   user_action='Deleted waypoint [primary alias name]'
-- RULE: DATA LOG — INSERT data_log action='waypoint_deleted'

-- trg_waypoint_after_update
-- RULE: NO MOVES — geometry column should NEVER change on UPDATE
--   If OLD.geometry != NEW.geometry: RAISE error 'Waypoint moves not allowed. Delete and recreate.'
--   "no moves of waypoints. delete and recreate"
-- RULE: JOURNAL — INSERT change_journal with before/after state
-- RULE: DATA LOG — appropriate action description
-- NOTE: Rename does NOT update waypoint record — it creates an alias.
--   "renames on waypoints creates an alias"

-- ===== WAYPOINT ALIAS TRIGGERS =====

-- trg_alias_after_insert
-- RULE: RENAME = CREATE ALIAS
--   When user "renames" a waypoint, application creates new alias row
--   with owner_id=current_user, is_primary=0
--   Primary alias (is_primary=1) is NEVER modified by rename
--   "renames on waypoints creates an alias. waypoints are known by lon and lat
--    and have a known-as extension owned by creators"
-- RULE: JOURNAL — log alias creation

-- trg_alias_before_delete
-- RULE: USER "DELETE" = DELETE MY ALIAS
--   "delete of waypoints removes my alias not the waypoint.
--    waypoint remains unless no alias exists, then waypoint is deleted"
--   User taps "delete" on waypoint → application deletes THIS USER'S alias only
--   If is_primary=1: only the waypoint creator can delete the primary alias
--   If is_primary=0: the alias owner can delete their own alias
--   Non-owners CANNOT delete other users' aliases
-- RULE: JOURNAL — log alias removal

-- trg_alias_after_delete
-- RULE: ORPHAN WAYPOINT CLEANUP
--   "waypoint remains unless no alias exists, then waypoint is deleted"
--   SELECT COUNT(*) FROM waypoint_aliases WHERE waypoint_id=OLD.waypoint_id
--   If count=0:
--     DELETE FROM waypoints WHERE waypoint_id=OLD.waypoint_id
--     (No names left = no purpose. Triggers waypoint_after_delete cascade.)
--   If count>0:
--     Waypoint stays. Other users still have names for this location.
-- RULE: DISPLAY CONTEXT — if user deleted their alias but others remain:
--   User now sees primary alias (or another user's alias) instead of their own
--   "clicking on waypoints should display aliases"

-- ===== TRACK TRIGGERS =====

-- trg_track_after_insert
-- RULE: AREA COVERAGE — set has_spatial=1 on ride_areas intersecting track bbox
-- RULE: JOURNAL — INSERT change_journal
--   user_action='Track created [filename]' or 'Track imported [filename]'
-- RULE: DATA LOG — action='track_created' or 'track_imported'

-- trg_track_after_delete
-- NOTE: Users NEVER directly delete tracks. They delete their ALIAS.
-- Track deletion only happens via orphan cleanup when last alias removed.
-- This trigger handles the orphan-triggered deletion.
-- RULE: OWNERSHIP CASCADE
--   If OLD.is_owned=1 AND OLD.shared=1:
--     Flag for collective removal in V3.0
--     "local deletes do not impact shared data unless I am removing something I created"
--   If OLD.is_owned=0:
--     Local delete only
-- RULE: ROUTE LINK — routes with source_track_id=OLD.track_id:
--   Route REMAINS. source_track_id becomes dead reference.
--   Route is independent entity. "track unchanged. route is independent."
-- RULE: FILE CLEANUP — delete GPX/KML file from my_tracks/ if is_owned=1
--   If is_owned=0 (downloaded track): no file to delete
-- RULE: AREA COVERAGE RECALCULATE — same pattern as waypoint delete
-- RULE: JOURNAL — log with before_state
-- RULE: DATA LOG — action='track_deleted'

-- trg_track_after_update_shared
-- RULE: PRIVATE RIDE CHECK
--   On UPDATE of shared from 0 to 1:
--   If NEW.ride_id IS NOT NULL:
--     Check ride.private flag (V3.0 — placeholder for now)
--     If ride.private=true: RAISE error 'Cannot share track from private ride'
--   "share blocked if ride.private=true"
-- RULE: JOURNAL — log share/unshare action

-- ===== TRACK ALIAS TRIGGERS =====

-- trg_track_alias_after_insert
-- RULE: RENAME = CREATE ALIAS (same as waypoints)
--   "same rules for tracks. tracks can have many aliases"
--   When user "renames" a track, application creates new alias row
--   with owner_id=current_user, is_primary=0
--   Primary alias is NEVER modified by rename
-- RULE: JOURNAL — log alias creation

-- trg_track_alias_before_delete
-- RULE: USER "DELETE" = DELETE MY ALIAS
--   "track remains, user deletes their alias. no aliases left track deletes"
--   User taps "delete" on track → application deletes THIS USER'S alias only
--   If is_primary=1: only the track creator can delete the primary alias
--   Non-owners CANNOT delete other users' aliases
-- RULE: JOURNAL — log alias removal

-- trg_track_alias_after_delete
-- RULE: ORPHAN TRACK CLEANUP
--   "no aliases left track deletes"
--   SELECT COUNT(*) FROM track_aliases WHERE track_id=OLD.track_id
--   If count=0:
--     DELETE FROM tracks WHERE track_id=OLD.track_id
--     (No names left = no purpose. Triggers track_after_delete cascade.)
--   If count>0:
--     Track stays. Other users still have names for this track.

-- trg_track_alias_after_update_name
-- RULE: ROUTE NAME CASCADE VIA PRIMARY ALIAS
--   "those names need to match"
--   On UPDATE of name WHERE is_primary=1:
--     UPDATE routes SET name=NEW.name, updated_at=datetime('now')
--       WHERE source_track_id = (SELECT track_id FROM track_aliases WHERE alias_id=NEW.alias_id)
--   Only PRIMARY alias name changes cascade to routes.
--   Non-primary alias renames do NOT cascade.
-- RULE: JOURNAL — log name change

-- ===== ROUTE TRIGGERS =====

-- trg_route_after_insert
-- RULE: AREA COVERAGE — set has_spatial=1 on intersecting ride_areas
-- RULE: JOURNAL — log creation
-- RULE: DATA LOG — action='route_created' or 'route_from_track' or 'route_imported'

-- trg_route_before_delete
-- RULE: RIDE ASSIGNMENT CHECK (V3.0 PLACEHOLDER)
--   If OLD.ride_id IS NOT NULL:
--     V3.0: check if ride has enrollments. If yes, RAISE error.
--     V2.5: no ride logic, allow delete.
--   "we are not building rides yet. only map functionality"
-- RULE: Allow delete in V2.5 — ride checks are V3.0

-- trg_route_after_delete
-- RULE: AREA COVERAGE RECALCULATE
-- RULE: JOURNAL — log with before_state including geometry as WKT
-- RULE: DATA LOG — action='route_deleted'

-- trg_route_after_update
-- RULE: SHARE SETTING CHECK
--   On UPDATE of shared to 1:
--   Check app_settings WHERE key='route_sharing_enabled'
--   If value='false': RAISE error 'Route sharing not enabled'
--   "let's see if there is demand. build with variable setting controlling that rule"
-- RULE: JOURNAL — log changes
-- RULE: DATA LOG

-- ===== RIDE AREA TRIGGERS =====

-- trg_area_after_insert
-- RULE: EXISTING DATA CHECK
--   On new area creation, check if existing waypoints/routes/tracks
--   fall within the new area geometry
--   If any found: set has_spatial=1
-- RULE: JOURNAL — log area creation
-- RULE: DATA LOG

-- trg_area_before_delete
-- RULE: TILES ONLY DELETION
--   Area delete removes TILES for this area only.
--   "deleting a ride area leaves details within it. it only deletes tiles.
--    biggest storage requirement"
--   Spatial data (waypoints, routes, tracks) stays untouched.
--   Clear area_id references on orphaned spatial records but DO NOT delete them.
--   Delete tile files within area bounds.
-- RULE: BACKUP BEFORE DELETE
--   Create automatic backup before any area deletion
--   backup_log.trigger_reason='pre_destructive'

-- trg_area_after_delete
-- RULE: JOURNAL — log area deletion
-- RULE: DATA LOG — action='area_deleted'

-- trg_area_after_update
-- RULE: JOURNAL — log changes (rename, etc.)
-- RULE: DATA LOG

-- ===== TILE SOURCE TRIGGERS =====

-- trg_tilesource_before_delete
-- RULE: DEFAULT PROTECTION
--   If OLD.is_default=1: RAISE error 'Cannot delete default tile source. Deactivate instead.'
--   Only user-added sources (is_default=0) can be deleted.

-- trg_tilesource_after_update
-- RULE: JOURNAL — log configuration changes (key added, source activated/deactivated)

-- ===== MAP SLOT TRIGGERS =====

-- trg_mapslot_after_update
-- RULE: JOURNAL — log slot assignment changes
--   user_action='Changed slot [N] from [old source] to [new source]'

-- ===== TRAIL RULES (NOT TRIGGERS — enforced in application code) =====
-- Trails are in grouptrack_trails.db (separate database), not in this schema.
-- These rules are enforced in ConvoyTrailOps.kt, not via SQLite triggers.
--
-- RULE: TRAILS NEVER DELETED INDIVIDUALLY
--   "never remove unless an area is removed. they are never removed individually.
--    a full region is removed"
--   Only source-level removal: DELETE FROM trails WHERE source matching
--   No single-trail delete action exists in UI
--
-- RULE: TRAIL DISPLAY IS A TOGGLE, NOT DELETION
--   "individual trail display hiding. trails display shows trails available
--    on current boundaries you can select or deselect trails in list.
--    function already exists"
--   Hiding a trail = display filter, data stays in database
--
-- RULE: TRAIL DEDUP ON INGESTION
--   Proximity check: bbox pre-filter → length filter → point sampling
--   CONFIRMED dupes auto-skip, PROBABLE/POSSIBLE go to review screen
--   First source ingested wins auto-resolve

-- ============================================================
-- END TRIGGER DEFINITIONS — 24 triggers across 6 tables
-- Every rule from May 9-10 lifecycle discussions captured.
-- Do not implement triggers without these rules.
-- Do not modify rules without updating this section.
-- ============================================================

-- ============================================================
-- 19. SEED DATA — Default Tile Sources (12 free, no key)
-- ============================================================

-- SATELLITE
INSERT INTO tile_sources (producer, map_type, name, short_label, url_template, subdomains, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('Google', 'HYB', 'Google Hybrid', 'HYB', 'https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', '0,1,2,3', 0, 'Map data © Google', 0, 20, 1, 1, 1),
('Google', 'SAT', 'Google Satellite', 'SAT', 'https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}', '0,1,2,3', 0, 'Imagery © Google', 0, 20, 1, 0, 2),
('Esri', 'SAT', 'Esri World Imagery', 'ESRI', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', NULL, 0, '© Esri', 0, 19, 1, 1, 3);

-- TOPO
INSERT INTO tile_sources (producer, map_type, name, short_label, url_template, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('OpenTopoMap', 'TOPO', 'OpenTopoMap', 'OTM', 'https://tile.opentopomap.org/{z}/{x}/{y}.png', 0, '© OpenTopoMap (CC-BY-SA)', 0, 17, 1, 1, 4),
('USGS', 'TOPO', 'USGS Topo', 'USGS', 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}', 0, 'USGS The National Map', 0, 16, 1, 1, 5),
('Esri', 'TOPO', 'Esri World Topo', 'TOPO', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}', 0, '© Esri', 0, 19, 1, 0, 6);

-- ROAD
INSERT INTO tile_sources (producer, map_type, name, short_label, url_template, subdomains, requires_key, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('OSM', 'STREET', 'OpenStreetMap', 'OSM', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', NULL, 0, '© OpenStreetMap contributors', 0, 19, 1, 1, 7),
('Google', 'STREET', 'Google Roads', 'ROADS', 'https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', '0,1,2,3', 0, 'Map data © Google', 0, 20, 1, 0, 8),
('Google', 'TERRAIN', 'Google Terrain', 'TERR', 'https://mt{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}', '0,1,2,3', 0, 'Map data © Google', 0, 20, 1, 0, 9);

-- TRAIL / OUTDOOR
INSERT INTO tile_sources (producer, map_type, name, short_label, url_template, requires_key, key_signup_url, attribution, min_zoom, max_zoom, is_default, is_active, sort_order) VALUES
('CalTopo', 'OUTDOOR', 'CalTopo USFS', 'CALT', 'https://caltopo.s3.amazonaws.com/topo/{z}/{x}/{y}.png', 0, NULL, '© CalTopo', 2, 16, 1, 1, 10),
('Esri', 'OUTDOOR', 'Esri NatGeo', 'NATG', 'https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}', 0, NULL, '© Esri / National Geographic', 0, 16, 1, 0, 11),
('Thunderforest', 'OUTDOOR', 'Thunderforest Outdoors', 'TF', 'https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}', 1, 'https://www.thunderforest.com/docs/apikeys/', '© Thunderforest / OSM', 0, 22, 1, 0, 12);

-- DEFAULT MAP SLOTS (matches current V2.4 config)
INSERT INTO map_slots VALUES (1, 1, 'Google Hybrid', 'HYB');
INSERT INTO map_slots VALUES (2, 5, 'USGS Topo', 'USGS');
INSERT INTO map_slots VALUES (3, 6, 'Esri World Topo', 'TOPO');

-- ============================================================
-- 20. VIEWS
-- ============================================================

CREATE VIEW v_active_tile_sources AS
SELECT source_id, producer, map_type, name, short_label, url_template,
       subdomains, requires_key, api_key, api_key_param, attribution, 
       min_zoom, max_zoom, tile_format, is_overlay
FROM tile_sources WHERE is_active = 1 ORDER BY sort_order;

CREATE VIEW v_map_slot_config AS
SELECT ms.slot_number, ms.label, ms.short_label,
       ts.url_template, ts.subdomains, ts.api_key, ts.attribution,
       ts.min_zoom, ts.max_zoom, ts.tile_format, ts.is_overlay,
       ts.producer, ts.map_type
FROM map_slots ms
JOIN tile_sources ts ON ms.source_id = ts.source_id
ORDER BY ms.slot_number;

CREATE VIEW v_shared_items AS
SELECT 'track' as item_type, track_id as item_id,
       (SELECT ta.name FROM track_aliases ta WHERE ta.track_id = t.track_id AND ta.is_primary = 1) as name,
       created_at, is_owned FROM tracks t WHERE shared = 1
UNION ALL
SELECT 'waypoint', waypoint_id, 
       (SELECT wa.name FROM waypoint_aliases wa WHERE wa.waypoint_id = w.waypoint_id AND wa.is_primary = 1),
       created_at, is_owned FROM waypoints w WHERE shared = 1
UNION ALL
SELECT 'route', route_id, name, created_at, is_owned FROM routes WHERE shared = 1;

CREATE VIEW v_active_queue AS
SELECT queue_id, direction, data_type, status, priority, area_name, item_name,
       total_items, completed_items, progress_pct, retry_count, last_error, created_at
FROM transfer_queue
WHERE status IN ('queued', 'in_progress', 'failed')
ORDER BY CASE status WHEN 'in_progress' THEN 0 WHEN 'failed' THEN 1 ELSE 2 END,
         priority, created_at;

CREATE VIEW v_area_coverage AS
SELECT a.area_id, a.name, a.area_type, a.has_tiles, a.has_spatial,
       (SELECT COUNT(*) FROM waypoints w WHERE w.area_id = a.area_id) as waypoint_count,
       (SELECT COUNT(*) FROM routes r WHERE r.area_id = a.area_id) as route_count,
       (SELECT COUNT(*) FROM tracks t WHERE t.area_id = a.area_id) as track_count
FROM ride_areas a;

CREATE VIEW v_waypoint_display AS
SELECT w.waypoint_id, w.type, wt.display_name as type_name, wt.icon_name, wt.color,
       wa.name, wa.description, wa.is_primary, wa.owner_id,
       w.shared, w.is_owned, w.created_at
FROM waypoints w
JOIN waypoint_types wt ON w.type = wt.type_code
JOIN waypoint_aliases wa ON w.waypoint_id = wa.waypoint_id;

CREATE VIEW v_track_display AS
SELECT t.track_id, t.filename, t.recorded_at, t.distance_miles,
       t.duration_minutes, t.max_speed_mph, t.elevation_gain_ft,
       t.source_format, t.shared, t.is_owned, t.created_at,
       ta.name, ta.description, ta.is_primary, ta.owner_id
FROM tracks t
JOIN track_aliases ta ON t.track_id = ta.track_id;

CREATE VIEW v_change_history AS
SELECT journal_id, transaction_id, timestamp, table_name, operation,
       record_id, user_action, rolled_back
FROM change_journal
WHERE rolled_back = 0
ORDER BY timestamp DESC
LIMIT 100;

-- ============================================================
-- SCHEMA COMPLETE — 20 tables/views, ~24 trigger placeholders
-- Triggers must be implemented before application code.
-- ============================================================
