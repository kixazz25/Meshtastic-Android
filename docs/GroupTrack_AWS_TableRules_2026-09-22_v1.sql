-- GroupTrack: rules stamped on the AWS mirror tables. 2026-09-22 v1. MARKER: TABLERULES-2026-09-22
-- Rule numbers = "Server rules" in GroupTrack_Nucleus_ImplementationTargets (v23+). Comments only; no data change.
USE grouptrack_spatial;
ALTER TABLE schema_version COMMENT='GT rules: advance with the device schema_version, same numbers both sides';
ALTER TABLE trails    COMMENT='GT rules: write ONLY via ingestion fn (R1) | geom_hash=SHA-256 of WKT (R2) | dedup drop/alias/insert (R4) | shared only | NO OSM rows (S2)';
ALTER TABLE tracks    COMMENT='GT rules: ingestion fn only (R1) | R2 | R4 | file = geom_hash.gpx (R6) | delete is compound (R7) | shared only (S1)';
ALTER TABLE waypoints COMMENT='GT rules: ingestion fn only (R1) | R2 | R3 Not Named | R4 | delete is compound (R7) | shared only (S1)';
ALTER TABLE routes    COMMENT='GT rules: ingestion fn only (R1) | R2 | R3 Not Named | R4 | delete is compound (R7) | shared only (S1)';
USE grouptrack_data;
ALTER TABLE schema_version      COMMENT='GT rules: advance with the device schema_version, same numbers both sides';
ALTER TABLE trail_properties    COMMENT='GT rules: pairs with a trails row | source+uid update in place (R9) | NO OSM rows (S2)';
ALTER TABLE track_properties    COMMENT='GT rules: filename must equal geom_hash.gpx (R6) | deleted with its track (R7) | shared only (S1)';
ALTER TABLE track_surveys       COMMENT='GT rules: collapse geometry, never surveys (S5) | one survey per rider per ride (S4)';
ALTER TABLE waypoint_properties COMMENT='GT rules: pairs with a waypoints row | deleted with it (R7) | shared only (S1)';
ALTER TABLE route_properties    COMMENT='GT rules: pairs with a routes row | deleted with it (R7) | shared only (S1)';
ALTER TABLE artifact_aliases    COMMENT='GT rules: pointers, hash lives on the artifact (R5) | INSERT IGNORE | one track alias per geometry per day (R4)';
ALTER TABLE route_notes         COMMENT='GT rules: the route narrative | deleted with its route (R7)';
ALTER TABLE ride_areas          COMMENT='GT rules: shared only (S1)';
ALTER TABLE trail_sources       COMMENT='GT rules: catalogue of approved sources | OSM sources never uploaded (S2)';
ALTER TABLE source_ingestions   COMMENT='GT rules: one row per source run (R9)';
ALTER TABLE contributions       COMMENT='GT rules: SERVER ONLY | one row per artifact per user (S3) | user_id = convoy_tracker.users';
SELECT table_schema, table_name, table_comment FROM information_schema.tables WHERE table_schema IN ('grouptrack_spatial','grouptrack_data') ORDER BY 1,2;
