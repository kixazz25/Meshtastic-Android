# GroupTrack V2.5 — Entity Lifecycle Scenarios
## Maintenance Operations and Spatial Model Impact
## May 9, 2026 — Schema hardening prerequisite

---

## Purpose

Every operation on every spatial entity must be defined BEFORE triggers are written. Each scenario answers: what can the user do, what changes in the database, what cascades to other tables, what gets logged, and what business rules can block the operation.

This document defines trigger requirements. No application code until these scenarios are reviewed and confirmed.

---

## 1. TRACKS

Source of truth: GPX/KML file in Documents/my_tracks/
Database role: spatial index (metadata + bounding box) in grouptrack_user.spatialite

### 1.1 Track Created (GPS Recording)

| Item | Detail |
|------|--------|
| Trigger | GPS recording stops, file saved to my_tracks/ |
| Primary action | INSERT into tracks table: filename, name, bbox, recorded_at, distance, duration, max_speed, elevation_gain, source_format |
| Spatial impact | Calculate bbox from GPS points. Check which ride_areas this bbox intersects. Update has_spatial=1 on intersecting areas. |
| Log | data_log: action='track_created', track_count=1 |
| Shared flag | Default shared=0. If ride is active AND ride.private=false, prompt "Share this track?" If yes, shared=1. If ride.private=true, suppress prompt, shared=0. |
| Ride link | If recorded during an active ride, set ride_id on track record |
| Business rules | None — tracks can always be created |

### 1.2 Track Imported (from Downloads)

| Item | Detail |
|------|--------|
| Trigger | User imports GPX/KML via Import screen or file picker |
| Primary action | File copied to my_tracks/. INSERT into tracks table with metadata. |
| Spatial impact | Same as 1.1 — calculate bbox, update intersecting ride_areas |
| Log | data_log: action='track_imported', track_count=1 (or N if multi-track split) |
| Date rule | Set file mtime from earliest <time> element (existing ConvoyTrackOps behavior) |
| Duplicate check | If filename already exists in my_tracks/, skip with status. No overwrite. |
| Business rules | Source file stays in Downloads as backup. Never auto-delete source. |

### 1.3 Track Renamed

| Item | Detail |
|------|--------|
| Trigger | User renames track via action sheet |
| Primary action | File renamed in my_tracks/. UPDATE tracks SET filename=new, name=new WHERE track_id=? |
| Spatial impact | None — bbox unchanged, area coverage unchanged |
| Log | data_log: action='track_renamed', source=track_id |
| Cross-entity | If this track was converted to a route (routes.source_track_id = this track_id), does the route name update? DECISION NEEDED: No — route name is independent once created. The link is for provenance only. |
| Business rules | Cannot rename to a name that already exists (existing RenameResult.NameExists behavior) |

### 1.4 Track Deleted

| Item | Detail |
|------|--------|
| Trigger | User deletes track via action sheet with confirmation dialog |
| Primary action | File deleted from my_tracks/. DELETE FROM tracks WHERE track_id=? |
| Spatial impact | Recalculate has_spatial for affected ride_areas. If no tracks, waypoints, or routes remain in area, set has_spatial=0. |
| Log | data_log: action='track_deleted', track_count=1 |
| Cross-entity | If track was shared (shared=1), the shared copy in V3.0 collective is NOT affected — local delete doesn't remove from server. If track was converted to a route, the route remains — source_track_id becomes a dead reference (acceptable — route stands on its own). |
| Business rules | ALWAYS requires confirmation dialog with filename and size. Never auto-delete. Cannot delete if file is currently being recorded (active GPS recording). |

### 1.5 Track Shared

| Item | Detail |
|------|--------|
| Trigger | User taps "Share" on track action sheet, or responds "Yes" to post-recording prompt |
| Primary action | UPDATE tracks SET shared=1 WHERE track_id=? |
| Spatial impact | None — shared flag doesn't affect local spatial model |
| Log | data_log: action='track_shared', source=track_id |
| Business rules | BLOCKED if track is linked to a ride AND ride.private=true. Show message: "This track was recorded on a private ride and cannot be shared." |
| V3.0 behavior | When upload engine activates, shared=1 tracks queue for upload. Until then, flag only. |

### 1.6 Track Unshared

| Item | Detail |
|------|--------|
| Trigger | User taps "Unshare" on track action sheet |
| Primary action | UPDATE tracks SET shared=0 WHERE track_id=? |
| Spatial impact | None |
| Log | data_log: action='track_unshared', source=track_id |
| V3.0 impact | If already uploaded to collective, local unshare does NOT remove from server. Server removal is a separate action in V3.0. |
| Business rules | Always allowed |

### 1.7 Track Converted to Route

| Item | Detail |
|------|--------|
| Trigger | User taps "Convert to Route" on track action sheet |
| Primary action | INSERT into routes: geometry copied from track GPX/KML, source_track_id=track_id, name=track_name + " (route)" |
| Spatial impact | New route has geometry → update ride_areas has_spatial for intersecting areas |
| Log | data_log: action='track_to_route', source=track_id |
| Cross-entity | Track remains unchanged. Route is a new independent entity linked by source_track_id. |
| Business rules | Always allowed. Does not modify or delete the original track. |

### 1.8 Track Smoothed (snap to trail)

| Item | Detail |
|------|--------|
| Trigger | User taps "Smooth Track" on track action sheet |
| Primary action | Read GPS points from file. For each point, snap to nearest trail in grouptrack_trails.db. Write smoothed geometry. User choice: save as new file (original preserved) or overwrite with confirmation. |
| Spatial impact | Bbox may change slightly. UPDATE tracks SET bbox=new_bbox. Recalculate area intersections. |
| Log | data_log: action='track_smoothed', source=track_id |
| Business rules | Original file preserved by default. Overwrite requires explicit confirmation. |

### 1.9 Track File Sync (orphan detection)

| Item | Detail |
|------|--------|
| Trigger | App launch scan of my_tracks/ directory |
| Scenario A | File exists, no DB record → INSERT record (same as 1.2 but auto-detected) |
| Scenario B | DB record exists, no file → DELETE record (orphan cleanup). Update area coverage. Log. |
| Scenario C | File renamed outside app → old DB record orphaned (Scenario B), new file detected (Scenario A). Net effect: record recreated with new filename. |
| Business rules | Scan is read-only on files. Never delete files during scan. Only reconcile DB records. |

### 1.10 Track Impact Summary — All Operations

| Operation | Spatial DB Impact | Area Coverage | Log Entry | Cross-Entity |
|-----------|------------------|---------------|-----------|--------------|
| ADD (record) | Insert record with bbox | has_spatial=1 on intersecting areas | track_created | None |
| ADD (import) | Copy file + insert record | has_spatial=1 on intersecting areas | track_imported | Source file preserved |
| RENAME | Update filename + name fields | None | track_renamed | Converted route name NOT updated |
| DELETE | Remove file + delete record | Recalculate has_spatial — may flip to 0 | track_deleted | Converted route remains (dead source_track_id). Shared copy unaffected. |
| SHARE | Set shared=1 | None | track_shared | BLOCKED if ride.private=true |
| UNSHARE | Set shared=0 | None | track_unshared | Server copy unaffected |
| CONVERT TO ROUTE | Insert new route record | Route adds to area coverage | track_to_route | Track unchanged. Route is independent. |
| SMOOTH | Update geometry in file, update bbox | Bbox may change — recalculate areas | track_smoothed | None |
| FILE SYNC | Reconcile DB with filesystem | Add/remove records as needed | track_synced | Orphan records cleaned |

---

## 2. TRAILS

Source of truth: grouptrack_trails.db (SQLite)
Population: ingested from agency GeoJSON sources
Access: read-only after ingestion

### 2.1 Trail Source Ingested (first time)

| Item | Detail |
|------|--------|
| Trigger | User selects source from catalog, applies filters, downloads and ingests |
| Primary action | Parse GeoJSON features. Apply user filters. For each matching feature: normalize properties via schema_map, calculate bbox and length, INSERT into trails table. |
| Spatial impact | Trails added. Viewport queries return new trails on next pan/zoom. |
| Source tracking | INSERT into source_ingestions: source_id, version_ingested, trail_count, filters_used |
| Dedup | For each trail, run dedup check against existing trails. CONFIRMED dupes auto-skip. PROBABLE/POSSIBLE dupes queued for review screen. |
| Log | data_log: action='source_ingested', source=source_id, trail_count=N |
| Business rules | User must confirm download before ingestion starts. Show estimated size and feature count from catalog. |

### 2.2 Trail Source Re-ingested (refresh/update)

| Item | Detail |
|------|--------|
| Trigger | User taps refresh on a source with available update (version check shows newer) |
| Primary action | DELETE all trails WHERE source_id matches this source. Re-ingest from fresh download with same filters (preserved in source_ingestions.filters_used). |
| Spatial impact | Trails replaced. Some trail_ids change (new UUIDs generated). |
| Source tracking | UPDATE source_ingestions: version_ingested=new, ingested_at=now, trail_count=new |
| Cross-entity | User waypoints snapped to old trail positions remain valid — waypoints have independent coordinates, not references to trail geometry. |
| Log | data_log: action='source_refreshed', source=source_id, trail_count=N |
| Business rules | Warn user: "This will replace X trails from [source]. Your waypoints and tracks are not affected." Require confirmation. |

### 2.3 Trail Source Removed

| Item | Detail |
|------|--------|
| Trigger | User removes a trail source entirely |
| Primary action | DELETE FROM trails WHERE source_agency matches AND source ingestion record matches. DELETE FROM source_ingestions WHERE source_id=?. |
| Spatial impact | Trails removed from viewport queries. Map shows fewer trails. |
| Cross-entity | User waypoints remain — they have independent coordinates. User tracks remain — they reference files, not trails. Routes remain — they have independent geometry. |
| Log | data_log: action='source_removed', source=source_id, trail_count=N_deleted |
| Business rules | Confirmation: "Remove X trails from [source]? Your waypoints, tracks, and routes are not affected." |

### 2.4 Trail Hidden by User

| Item | Detail |
|------|--------|
| Trigger | User taps a trail and selects "Hide" (future feature) |
| Primary action | UPDATE trails SET is_hidden=1 WHERE trail_id=? (requires new column) |
| Spatial impact | Hidden trails excluded from viewport queries (WHERE is_hidden=0) |
| Business rules | Can unhide. Hidden state is local — re-ingestion resets hidden state. |
| DECISION NEEDED | Is this a V2.5 feature or deferred? Recommend deferred — low priority. |

### 2.5 Duplicate Detected During Ingestion

| Item | Detail |
|------|--------|
| Trigger | Dedup check during ingestion finds spatial overlap |
| CONFIRMED dupe | Auto-skip incoming trail. INSERT into dedup_log: resolution='auto_skip'. Increment source_ingestions.dupes_skipped. |
| PROBABLE dupe | Add to review queue. Present on review screen with mini-map comparison. User resolves: KEEP EXISTING, REPLACE, KEEP BOTH. |
| POSSIBLE dupe | Same as PROBABLE but with stronger warning that these may be different trails. |
| KEEP EXISTING | Skip incoming. Log resolution='user_keep_existing'. |
| REPLACE | DELETE existing trail. INSERT incoming. Log resolution='user_replace'. |
| KEEP BOTH | INSERT incoming alongside existing. Log resolution='user_keep_both'. |
| Business rules | First source ingested wins auto-resolve. User can always override. |

### 2.6 Trail Impact Summary — All Operations

| Operation | Spatial DB Impact | Area Coverage | Log Entry | Cross-Entity |
|-----------|------------------|---------------|-----------|--------------|
| SOURCE INGESTED | Batch insert trails | Viewport queries return new trails | source_ingested | Dedup against existing sources |
| SOURCE REFRESHED | Delete old + re-insert all | Trail_ids change (new UUIDs) | source_refreshed | User waypoints/tracks/routes UNAFFECTED |
| SOURCE REMOVED | Delete all trails from source | Fewer trails in viewport | source_removed | User data UNAFFECTED. Snap-to may have gaps. |
| DUPE AUTO-SKIP | No insert | None | dedup auto_skip | Dedup log entry |
| DUPE USER REPLACE | Delete existing + insert new | Trail replaced in viewport | dedup user_replace | Dedup log entry |

---

## 3. WAYPOINTS

Source of truth: grouptrack_user.spatialite waypoints table
All operations are user-initiated

### 3.1 Waypoint Created

| Item | Detail |
|------|--------|
| Trigger | Long-press on map → waypoint creation dialog |
| Primary action | INSERT into waypoints: waypoint_id (UUID), name, type (from picker), geometry (POINT from press location), description (optional), created_at, updated_at, shared=0 |
| Spatial impact | Find intersecting ride_areas. UPDATE has_spatial=1 on each. |
| Log | data_log: action='waypoint_created', waypoint_count=1 |
| Business rules | Name required. Type required (from waypoint_types table). Location from map press — no manual coordinate entry. |

### 3.2 Waypoint Renamed

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Edit → changes name |
| Primary action | UPDATE waypoints SET name=new, updated_at=now WHERE waypoint_id=? |
| Spatial impact | None — geometry unchanged |
| Log | data_log: action='waypoint_renamed', source=waypoint_id |
| Business rules | Name cannot be empty. |

### 3.3 Waypoint Type Changed

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Edit → changes type |
| Primary action | UPDATE waypoints SET type=new_type, updated_at=now WHERE waypoint_id=? |
| Spatial impact | None — geometry unchanged. Map icon changes to match new type. |
| Log | data_log: action='waypoint_type_changed', source=waypoint_id |
| Business rules | Type must be valid type_code from waypoint_types table. |

### 3.4 Waypoint Description Edited

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Edit → changes description |
| Primary action | UPDATE waypoints SET description=new, updated_at=now WHERE waypoint_id=? |
| Spatial impact | None |
| Log | data_log: action='waypoint_edited', source=waypoint_id |
| Business rules | Description can be empty (cleared). |

### 3.5 Waypoint Moved

| Item | Detail |
|------|--------|
| Trigger | User long-press-drags waypoint to new location on map |
| Primary action | UPDATE waypoints SET geometry=new_point, updated_at=now WHERE waypoint_id=? |
| Spatial impact | Old and new ride_area intersections may differ. Recalculate: remove from old area coverage (if no other spatial data remains, set has_spatial=0), add to new area coverage (set has_spatial=1). |
| Log | data_log: action='waypoint_moved', source=waypoint_id |
| Business rules | Always allowed for user-created waypoints. BLOCKED for waypoints received from ride enrollment (V3.0) — those are organizer-placed. |
| DECISION NEEDED | Is drag-to-move a V2.5 feature or deferred? Recommend V2.5 — it's a natural interaction. |

### 3.6 Waypoint Deleted

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Delete → confirmation dialog |
| Primary action | DELETE FROM waypoints WHERE waypoint_id=? |
| Spatial impact | Recalculate has_spatial for affected ride_areas. If no waypoints, tracks, or routes remain in area, set has_spatial=0. |
| Log | data_log: action='waypoint_deleted', waypoint_count=1 |
| Cross-entity | If waypoint was shared (shared=1), local delete does NOT remove from V3.0 collective. Server copy persists independently. |
| Business rules | ALWAYS requires confirmation dialog showing waypoint name and type. Never auto-delete. |
| DECISION: Can a shared waypoint be deleted locally? | YES — local delete is independent of server copy. The rider is managing their local data. The shared copy lives on the server for other riders. |
| DECISION: Can a ride-linked waypoint be deleted? | V2.5: no ride-linked waypoints (V3.0 feature). When implemented: organizer-placed waypoints can only be deleted by organizer. Rider-placed waypoints on a ride can be deleted by the rider. |

### 3.7 Waypoint Shared

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Share |
| Primary action | UPDATE waypoints SET shared=1, updated_at=now WHERE waypoint_id=? |
| Spatial impact | None |
| Log | data_log: action='waypoint_shared', source=waypoint_id |
| Business rules | Always allowed for user-created waypoints. |

### 3.8 Waypoint Unshared

| Item | Detail |
|------|--------|
| Trigger | User taps waypoint → Unshare |
| Primary action | UPDATE waypoints SET shared=0, updated_at=now WHERE waypoint_id=? |
| Spatial impact | None |
| Log | data_log: action='waypoint_unshared', source=waypoint_id |
| V3.0 impact | Local unshare does NOT remove from server collective. |
| Business rules | Always allowed. |

### 3.9 Waypoints Consolidated / Replaced

This is the most complex waypoint operation. Two waypoints at or near the same location — user decides one replaces the other.

**Scenario:** "Gas Station" (waypoint A) and "Chevron Fuel Stop" (waypoint B) are both at the same intersection. User wants to keep B and retire A. All references to A must transfer to B.

| Item | Detail |
|------|--------|
| Trigger | User selects waypoint A → "Replace with..." → selects waypoint B from nearby waypoints list |
| Step 1 | Identify all references to waypoint A: ride associations (ride_id), area associations (area_id), shared status |
| Step 2 | Transfer references: if A was linked to a ride, link B to that ride. If A was shared, mark B as shared. If A had a description, append to B's description. |
| Step 3 | DELETE waypoint A |
| Step 4 | Log the consolidation with both IDs, names, and what was transferred |
| Primary action | DELETE FROM waypoints WHERE waypoint_id = A. UPDATE waypoint B with any transferred attributes. |
| Spatial impact | One waypoint removed. Recalculate has_spatial on affected ride_areas. B's coverage already counted — net change is A's removal. |
| Log | data_log: action='waypoint_consolidated', waypoint_count=-1 |
| Consolidation log | New table or extended data_log: old_waypoint_id, old_name, new_waypoint_id, new_name, attributes_transferred (JSON) |

**Attribute resolution during consolidation:**

| Attribute | Rule |
|-----------|------|
| Name | Keep replacement (B). A's name logged for audit. |
| Type | Keep replacement (B). If different types, user confirms. |
| Description | Concatenate: B's description + "\n[Consolidated from: A's name] " + A's description |
| Location | Keep replacement (B). If >50m apart, warn user — these may be different places. |
| Created_at | Keep earliest of A and B |
| Updated_at | Set to now |
| Shared | If EITHER was shared, result is shared=1 |
| Ride_id | If A had ride_id and B did not, transfer A's ride_id to B. If both have ride_id, user must choose which ride association to keep. |
| Area_id | If A had area_id and B did not, transfer. If both, keep B's. |

**Reverse scenario — user keeps A, retires B:**
Same process but A is the survivor. All B's references transfer to A.

**Multiple consolidation:**
User may consolidate 3+ waypoints at the same location into one. Process runs pairwise: consolidate C into B, then B into A. Each step logged independently.

### 3.10 Waypoint Impact Summary — All Operations

| Operation | Spatial DB Impact | Area Coverage | Log Entry | Cross-Entity |
|-----------|------------------|---------------|-----------|--------------|
| ADD | Insert record with geometry | has_spatial=1 on intersecting areas | waypoint_created | None |
| RENAME | Update name field | None | waypoint_renamed | None |
| TYPE CHANGE | Update type field | None (icon changes on map) | waypoint_type_changed | None |
| DESCRIPTION EDIT | Update description field | None | waypoint_edited | None |
| MOVE | Update geometry | Recalculate old+new area intersections | waypoint_moved | Blocked for ride-enrolled waypoints (V3.0) |
| DELETE | Remove record | Recalculate has_spatial — may flip to 0 | waypoint_deleted | Shared copy on server unaffected |
| SHARE | Set shared=1 | None | waypoint_shared | Queued for V3.0 upload |
| UNSHARE | Set shared=0 | None | waypoint_unshared | Server copy unaffected |
| CONSOLIDATE | Delete source, transfer refs to target | Recalculate has_spatial | waypoint_consolidated | Ride associations transferred, shared status merged |

---

## 4. ROUTES

Source of truth: grouptrack_user.spatialite routes table
Geometry stored in database (not file-based like tracks)

### 4.1 Route Created (drawn on map)

| Item | Detail |
|------|--------|
| Trigger | User draws route on Planning Map using snap-to-trail or freehand |
| Primary action | INSERT into routes: route_id (UUID), name (user-entered), geometry (LINESTRING from drawn points), distance_miles (calculated), source_format='drawn', created_at, updated_at, shared=0 |
| Spatial impact | Calculate bbox. Find intersecting ride_areas. Update has_spatial=1. |
| Log | data_log: action='route_created', route_count=1 |
| Business rules | Name required. At least 2 points required. |

### 4.2 Route Created (converted from track)

| Item | Detail |
|------|--------|
| Trigger | User selects "Convert to Route" on track action sheet |
| Primary action | INSERT into routes: geometry from track file, source_track_id=track_id, name=track_name + " (route)", source_format=track's format |
| Spatial impact | Same as 4.1 |
| Log | data_log: action='route_from_track', source=track_id |
| Cross-entity | Track unchanged. Route is independent entity with provenance link. |
| Business rules | Always allowed. Track is not modified. |

### 4.3 Route Created (imported from file)

| Item | Detail |
|------|--------|
| Trigger | User imports GPX/KML as a route (distinct from importing as a track) |
| Primary action | Parse geometry from file. INSERT into routes with geometry, name from file or user-entered. |
| Spatial impact | Same as 4.1 |
| Log | data_log: action='route_imported', route_count=1 |
| Business rules | File stays as source. Route geometry extracted and stored in DB. |

### 4.4 Route Renamed

| Item | Detail |
|------|--------|
| Trigger | User renames route |
| Primary action | UPDATE routes SET name=new, updated_at=now WHERE route_id=? |
| Spatial impact | None |
| Log | data_log: action='route_renamed', source=route_id |
| Cross-entity | Does NOT affect the source track name (if converted from track). Names are independent. |
| Business rules | Name cannot be empty. |

### 4.5 Route Geometry Edited

| Item | Detail |
|------|--------|
| Trigger | User modifies route path on map (drag points, add points, remove points) |
| Primary action | UPDATE routes SET geometry=new, distance_miles=recalculated, updated_at=now WHERE route_id=? |
| Spatial impact | Bbox may change. Recalculate area intersections. |
| Log | data_log: action='route_edited', source=route_id |
| Business rules | BLOCKED if route is assigned to an active ride with enrolled riders — changing the route after enrollment could mislead riders. Require un-assignment from ride first, or confirm with warning. |
| DECISION NEEDED | Can a route be edited while assigned to a ride? Recommend: warn but allow — organizer may need to adjust for conditions. Log the change so enrolled riders can be notified (V3.0). |

### 4.6 Route Deleted

| Item | Detail |
|------|--------|
| Trigger | User deletes route via action sheet with confirmation |
| Primary action | DELETE FROM routes WHERE route_id=? |
| Spatial impact | Recalculate has_spatial for affected ride_areas. |
| Log | data_log: action='route_deleted', route_count=1 |
| Cross-entity | Source track (if converted) is NOT affected — track stands alone. Rides using this route: DECISION NEEDED below. |
| Business rules | BLOCKED if route is assigned to an active ride with enrolled riders. Message: "This route is assigned to [ride name] with [N] enrolled riders. Remove from ride first." ALLOWED if route is assigned to a personal (non-shared) ride or no ride. |
| Confirmation | Show route name, distance, and any ride assignments. |

### 4.7 Route Assigned to Ride

| Item | Detail |
|------|--------|
| Trigger | Organizer creates or edits a ride, selects this route |
| Primary action | UPDATE routes SET ride_id=ride_id WHERE route_id=? |
| Spatial impact | None — route geometry unchanged |
| Log | data_log: action='route_assigned', source=route_id |
| Business rules | A route can only be assigned to one ride at a time. To reuse, copy the route. |

### 4.8 Route Unassigned from Ride

| Item | Detail |
|------|--------|
| Trigger | Organizer removes route from ride |
| Primary action | UPDATE routes SET ride_id=NULL WHERE route_id=? |
| Spatial impact | None |
| Log | data_log: action='route_unassigned', source=route_id |
| Business rules | Always allowed by organizer. |

### 4.9 Route Sharing Rules

| Item | Detail |
|------|--------|
| Rule | Routes are NEVER independently shared to the collective. |
| Distribution | Routes are shared ONLY through ride enrollment. When a rider enrolls in a ride, they receive the ride's route. |
| Local copy | The enrolled rider gets a local copy. They cannot re-share it. |
| Schema enforcement | routes.shared column should NOT exist — or if it does, it's always 0. Routes have ride_id for distribution, not a shared flag. |
| DECISION | Remove shared column from routes table? Recommend: keep it but enforce shared=0 always via trigger. Reserve for future use case. |

### 4.10 Route Impact Summary — All Operations

| Operation | Spatial DB Impact | Area Coverage | Log Entry | Cross-Entity |
|-----------|------------------|---------------|-----------|--------------|
| ADD (drawn) | Insert record with geometry | has_spatial=1 on intersecting areas | route_created | None |
| ADD (from track) | Insert record, link source_track_id | has_spatial=1 on intersecting areas | route_from_track | Track unchanged |
| ADD (imported) | Insert record from file geometry | has_spatial=1 on intersecting areas | route_imported | None |
| RENAME | Update name field | None | route_renamed | Source track name NOT updated |
| GEOMETRY EDIT | Update geometry, recalculate distance | Bbox may change — recalculate areas | route_edited | WARN if assigned to active ride |
| DELETE | Remove record | Recalculate has_spatial — may flip to 0 | route_deleted | BLOCKED if assigned to active ride with enrollments. Source track unaffected. |
| ASSIGN TO RIDE | Set ride_id | None | route_assigned | Route locked to one ride at a time |
| UNASSIGN | Clear ride_id | None | route_unassigned | None |
| SHARE | BLOCKED — routes never shared independently | N/A | N/A | Distribution only through ride enrollment |

---

## 5. RIDE AREAS

Source of truth: grouptrack_user.spatialite ride_areas table

### 5.1 Ride Area Created

| Item | Detail |
|------|--------|
| Trigger | User defines area via viewport zoom (V2.5) or polygon draw (later) |
| Primary action | INSERT into ride_areas: area_id (UUID), name (user-entered or auto-generated from location), geometry (POLYGON from viewport bounds or drawn polygon), area_type='personal', has_tiles=0, has_spatial=0 |
| Spatial impact | Check if existing waypoints/tracks/routes fall within this area. If yes, set has_spatial=1. |
| Log | data_log: action='area_created' |
| Business rules | Name recommended but can be auto-generated ("St George Area", "Download 2026-05-09"). |

### 5.2 Ride Area Deleted

| Item | Detail |
|------|--------|
| Trigger | User deletes an area |
| Primary action | DELETE FROM ride_areas WHERE area_id=? |
| Spatial impact | DECISION NEEDED: does deleting an area delete the spatial data within it? |
| Option A — Area is just a boundary | Delete the area record only. Trails, waypoints, tracks, routes within it remain. They just lose their area association. has_spatial flag becomes meaningless for this area because the area no longer exists. |
| Option B — Area controls data lifecycle | Delete the area AND all downloaded trails/spatial data within it. User waypoints and tracks remain (never auto-delete user data). Only downloaded/ingested data removed. |
| Recommendation | Option A for V2.5. Areas are organizational, not lifecycle-controlling. Explicit "remove downloaded data" is a separate action from "delete area boundary." |
| Log | data_log: action='area_deleted' |

### 5.3 Ride Area Renamed

| Item | Detail |
|------|--------|
| Trigger | User renames area |
| Primary action | UPDATE ride_areas SET name=new, updated_at=now WHERE area_id=? |
| Spatial impact | None |
| Log | data_log: action='area_renamed' |

### 5.4 Tiles Downloaded for Area

| Item | Detail |
|------|--------|
| Trigger | Tile download completes for this area's bounds |
| Primary action | UPDATE ride_areas SET has_tiles=1, updated_at=now WHERE area_id=? |
| Spatial impact | Blue overlay appears on map for this area |
| Log | data_log: action='tiles_downloaded', source=area_id |

### 5.5 Tiles Removed for Area

| Item | Detail |
|------|--------|
| Trigger | User removes cached tiles for this area |
| Primary action | Delete tile files within area bounds. UPDATE ride_areas SET has_tiles=0, updated_at=now WHERE area_id=? |
| Spatial impact | Blue overlay disappears for this area |
| Log | data_log: action='tiles_removed', source=area_id |

---

## 6. CROSS-ENTITY IMPACT MATRIX

What happens to entity B when operation X is performed on entity A?

### When a TRACK is deleted:

| Affected entity | Impact |
|-----------------|--------|
| Routes (converted from this track) | Route remains. source_track_id becomes dead reference. Route is independent. |
| Ride areas | Recalculate has_spatial. May flip to 0 if no other spatial data in area. |
| Source files | GPX/KML file deleted from my_tracks/. |
| V3.0 collective | Shared copy (if any) unaffected. |

### When a TRAIL SOURCE is removed:

| Affected entity | Impact |
|-----------------|--------|
| User waypoints | UNAFFECTED. Waypoints have independent coordinates. |
| User tracks | UNAFFECTED. Tracks reference files, not trails. |
| User routes | UNAFFECTED. Routes have independent geometry. |
| Other trail sources | UNAFFECTED. Each source is independent. |
| Snap-to-trail | Fewer trails available for snapping. Lead track smoothing may have gaps. |
| Ride areas | Recalculate has_spatial based on remaining data. |

### When a TRAIL SOURCE is re-ingested:

| Affected entity | Impact |
|-----------------|--------|
| User waypoints | UNAFFECTED. |
| User tracks | UNAFFECTED. |
| Existing trails from same source | DELETED and re-inserted. Trail_ids change (new UUIDs). |
| Existing trails from other sources | UNAFFECTED. |
| Dedup log | New dedup entries for any overlaps with other sources. |

### When a WAYPOINT is deleted:

| Affected entity | Impact |
|-----------------|--------|
| Tracks | UNAFFECTED. |
| Routes | UNAFFECTED. |
| Trails | UNAFFECTED. |
| Ride areas | Recalculate has_spatial. |

### When a ROUTE is deleted:

| Affected entity | Impact |
|-----------------|--------|
| Source track | UNAFFECTED. Track stands alone. |
| Rides using this route | BLOCKED if active ride with enrollments. Must un-assign first. |
| Ride areas | Recalculate has_spatial. |

### When a RIDE AREA is deleted:

| Affected entity | Impact |
|-----------------|--------|
| Trails within area | UNAFFECTED (V2.5). Trails exist in DB regardless of areas. |
| Waypoints within area | UNAFFECTED. area_id reference on waypoint becomes dead but waypoint remains. |
| Tracks within area | UNAFFECTED. |
| Routes within area | UNAFFECTED. |
| Tiles within area | DECISION per 5.2 — recommend tiles unaffected in V2.5. |

---

## 7. BUSINESS RULE SUMMARY

### Delete rules:

| Entity | Can delete? | Blocked when? |
|--------|------------|---------------|
| Track | Yes, with confirmation | Currently being recorded |
| Trail | Only via source removal | Individual trail delete not supported — remove entire source |
| Waypoint | Yes, with confirmation | Never blocked in V2.5. V3.0: organizer-placed waypoints blocked for non-organizer. |
| Route | Yes, with confirmation | Assigned to active ride with enrolled riders |
| Ride area | Yes, with confirmation | Never blocked |

### Share rules:

| Entity | Can share independently? | Mechanism |
|--------|------------------------|-----------|
| Track | Yes — shared flag + V3.0 upload | Blocked if ride.private=true |
| Trail | N/A — trails are public agency data | N/A |
| Waypoint | Yes — shared flag + V3.0 upload | Always allowed |
| Route | NEVER independently | Only distributed through ride enrollment |

### Rename rules:

| Entity | Rename propagates to? |
|--------|----------------------|
| Track → converted Route | No. Names are independent after conversion. |
| Route → assigned Ride | No. Ride has its own name. |
| Waypoint → nothing | Waypoints are standalone. |

---

## 8. TRIGGER SPECIFICATION

Based on the scenarios above, these triggers are required:

### Trigger: after_track_insert
```
AFTER INSERT ON tracks
→ Update has_spatial on intersecting ride_areas
→ Insert data_log entry
```

### Trigger: after_track_delete
```
AFTER DELETE ON tracks
→ Recalculate has_spatial on previously-intersecting ride_areas
→ Insert data_log entry
```

### Trigger: after_track_update_shared
```
AFTER UPDATE OF shared ON tracks
→ Insert data_log entry
→ If setting shared=1: verify ride.private != true (or raise error)
```

### Trigger: after_waypoint_insert
```
AFTER INSERT ON waypoints
→ Update has_spatial on intersecting ride_areas
→ Insert data_log entry
```

### Trigger: after_waypoint_delete
```
AFTER DELETE ON waypoints
→ Recalculate has_spatial on previously-intersecting ride_areas
→ Insert data_log entry
```

### Trigger: after_waypoint_update
```
AFTER UPDATE ON waypoints
→ If geometry changed: recalculate area intersections
→ Insert data_log entry
```

### Trigger: after_route_insert
```
AFTER INSERT ON routes
→ Update has_spatial on intersecting ride_areas
→ Insert data_log entry
```

### Trigger: after_route_delete
```
BEFORE DELETE ON routes
→ Check: if ride_id is NOT NULL AND ride has enrollments → RAISE error 'Cannot delete route assigned to active ride'
AFTER DELETE ON routes
→ Recalculate has_spatial
→ Insert data_log entry
```

### Trigger: after_route_update_ride
```
AFTER UPDATE OF ride_id ON routes
→ Insert data_log entry (assigned or unassigned)
```

### Trigger: after_source_ingestion_complete
```
Not a DB trigger — application-level callback after batch insert
→ Update source_ingestions record
→ Insert data_log entry with counts
```

---

## 9. DECISIONS NEEDED BEFORE IMPLEMENTATION

| # | Question | Recommendation | Status |
|---|----------|---------------|--------|
| 1 | Does renaming a track update its converted route name? | No — names are independent | PROPOSED |
| 2 | Can a route be edited while assigned to a ride? | Warn but allow. Log change. | PROPOSED |
| 3 | Does deleting a ride area delete data within it? | No — areas are organizational only (V2.5) | PROPOSED |
| 4 | Is trail hiding (individual trail) a V2.5 feature? | Deferred | PROPOSED |
| 5 | Is waypoint drag-to-move a V2.5 feature? | Yes — natural interaction | PROPOSED |
| 6 | Is waypoint merge a V2.5 feature? | Deferred to V3.0 | PROPOSED |
| 7 | Should routes table have a shared column? | Keep but enforce shared=0 via trigger | PROPOSED |
| 8 | Can a shared waypoint be deleted locally? | Yes — local and server copies are independent | PROPOSED |
| 9 | Can a track be deleted if it was shared? | Yes — local delete doesn't affect server copy | PROPOSED |

---

*GroupTrack V2.5 | Entity Lifecycle Scenarios v1 | May 9, 2026*
*Review and confirm all PROPOSED decisions before writing triggers.*
*Do not write application code until triggers are hardened.*
