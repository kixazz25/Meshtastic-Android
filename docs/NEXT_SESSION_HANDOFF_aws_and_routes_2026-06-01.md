# NEXT-SESSION HANDOFF — AWS DB Update + Route Planning (written 2026-06-01 EOD)

Read this + STATE_OF_PLAY_2026-06-01_EOD.md + v25_master_checklist.md (Sections E, K, L) to start oriented. This doc focuses the two big upcoming work items in detail. SEQUENCE DECISION made today: **route planning FIRST (2-day pass), then AWS mirror + cleanup.**

================================================================
# PART 1 — ROUTE PLANNING (do first; 2-day pass)
================================================================

## What it is (tester-chosen, locked — do NOT revert to freehand)
Point-to-point route line with **SNAP-2**. The user taps points on the map; each placed vertex SNAPS to the nearest point on a nearby trail or track within a radius. Testers were surveyed on freehand vs point-to-point+snap and chose snap-2. (Supersedes the May-29 freehand notes. Section E.)

## Snap mechanics (the core of the work)
- **Snap references = TRAILS and TRACKS.** A placed vertex snaps to the nearest point ON a trail/track line (nearest-point-on-line, not nearest vertex) within a tunable radius.
- **Snap priority + fallback:** when BOTH a trail and a track are in range, decide trail-first vs track-first. Define the tie-break. Nearest-point-on-line snap for the chosen reference.
- **Radius** is tune-by-testing; consider a hover/preview so the user sees where the vertex will land before committing.

## Build pipeline (the data path — this is where dedup ties in)
Build a WKT LINESTRING from the snapped vertices → compute bbox → call **insertRoute** → re-fire the viewport query → draw (gold dashed styling already wired). `insertRoute` already exists (4 xrefs) and after the P2 dedup work it routes through the shared add-core (computes geom_hash, applies the dupe/alias/insert decision).

## WHY routes interact with the dedup core (important)
A snap-2 route traces along trails, so the route's geometry can be IDENTICAL to a trail's geometry. That's expected and must coexist — handled by the composite identity key **(artifact_type, geom_hash)**: a route in the routes table and a trail in the trails table can share a geom_hash because they're different types/tables. `UNIQUE(geom_hash)` is per-table, so a route tracing a trail does NOT collide. THIS is the design reason routes are built after the dedup core is proven — route creation is what exercises that key decision under real use.

## UI wiring tasks
- **+ROUTE button** nav wiring on the artifacts panel (currently OPEN).
- **Parity across 3 map interfaces:** convoy line 494, convoy line 622, planning line 391. All are HTML/Leaflet. Build once, then diff across the three so behavior matches. (This 3-interface parity is a recurring theme — the snap-2 logic has to land in all three.)
- **Route maintenance screen** (rename/delete/detail) is OPEN.

## ROUTE-PLANNING-ADJACENT GAP to fix in this work
**Waypoint ADD works on the PLANNING map but NOT the convoy map.** Currently you can only drop waypoints on the planning map. Add waypoint-drop to the convoy map as part of route planning. (Related carried bug: long-press waypoint drop must fire only on empty map, not on node markers.)

## Define the minimum-win before starting (scope honesty)
Route planning is a feature, not a patch; two days is tight. Suggested minimum win = the core snap-2 create-route flow working on ONE interface (place points → snap to trail/track → build LINESTRING → insertRoute → draw), writing correctly to the routes table with a geom_hash. Polish (3-interface parity, maintenance screen, radius hover/preview, +ROUTE nav) can spill over.

================================================================
# PART 2 — AWS DATABASE UPDATE (the closing task; after routes)
================================================================

## PURPOSE (why this exists)
GroupTrack is a multi-device, multi-contributor system. Artifacts (trails, tracks, waypoints, routes) flow device→AWS, AWS→device, and device→device. The AWS store is the shared cloud backend (~100 contributors feed it). For the dedup/alias model to work ACROSS that whole system, the AWS database must enforce the SAME identity rules as the local SQLite DB. If the two stores enforce different uniqueness keys, the alias model breaks at sync time. So the AWS update is **correctness-critical, not cosmetic** (Section L, verification point #2).

## STATE OF THE OLD AWS DB (what's there now)
- AWS backend = **AWS RDS MySQL**, fronted by a PHP REST API, with Google Sign-In. Existing tables: users, rides, enrollments, invites (the ride/account layer). Rider taps invite link → downloads ride JSON via API → radio engine unchanged.
- The AWS **artifact** store (trails/tracks/waypoints/routes + aliases) is currently EMPTY / not yet built to match the new v3 model. It was waiting on the local schema being finalized first.
- The local schema has NOW been finalized and proven (v3 with geom_hash identity, per-type UNIQUE(geom_hash), pointer-model artifact_aliases with creation_date for track-alias dedup). So AWS can now be built to mirror a KNOWN-GOOD target.

## PLAN OF ATTACK (mirror the proven local v3 to AWS MySQL)
Gate (Fred's rule): **only build the AWS mirror AFTER the local model is finished + tested + committed** — mirror a fixed target, not a moving one. (Local is proven; commit P2 + recap first, THEN AWS.)

1. **Structural mirror.** Create the AWS MySQL tables to structurally match the proven local v3 schema — same tables, same columns, same identity semantics. MySQL type analogues:
   - geometry/WKT TEXT → **LONGTEXT** (call it geometry_json or geometry)
   - lat/lon REAL → **DECIMAL** (fixed precision)
   - geom_hash → CHAR/VARCHAR (SHA-256 = 64 hex chars → CHAR(64))
   - INTEGER/TEXT → INT/VARCHAR analogues
2. **Identity key MUST match.** Local uses composite (artifact_type, geom_hash), implemented per-type as UNIQUE(geom_hash) within each separate table. On AWS, if artifact types share tables, make the composite **explicit: UNIQUE(artifact_type, geom_hash)**. The UNIQUE KEY on AWS must mean the same thing as the local one or the alias model breaks at sync (Section L #2).
3. **Alias model must match.** artifact_aliases mirrors the pointer model: (artifact_type, artifact_id, alias) + geom_hash + creation_date. Track-alias dedup = UNIQUE(artifact_type, geom_hash, creation_date) — the same first-occurrence-per-day collapse that stops 20-rider group rides from flooding the cloud.
4. **First-occurrence semantics** (Section L): AWS is a FIRST-OCCURRENCE store, NOT a forced mirror of every device. When a row arrives: same name+geom → drop; new name+same geom → attach alias (existing primary stays primary); geom not present → insert as new primary. The invariant: the set {primary + all aliases} is identical across all DBs even though WHICH name is primary varies by arrival order per DB. No name ever lost.

## TWO CORRECTNESS POINTS TO PROVE (Section L)
1. **geom_hash normalization is load-bearing** — IF cross-agency/cross-device data ever arrives with WKT differing by coordinate precision, identical physical geometry would hash differently and the dedup would miss it. CURRENT DECISION: hash is RAW WKT, no normalization, because the real duplicate data we tested is byte-identical. Revisit normalization only if a multi-source import shows precision-variant dupes slipping through. (For AWS sync this matters: device and AWS must hash the SAME way or matches fail.)
2. **Local↔AWS structural identity** — a row must mean the same thing on both sides; the UNIQUE keys must be equivalent. This is the whole reason the AWS mirror is correctness-critical.

## OPEN QUESTION FOR THE AWS SESSION
The PHP REST API + sync logic (how rows actually move device↔AWS and where the dedup/alias decision fires on the AWS side) needs design — the local add-core makes the decision locally at insert; the AWS side needs the equivalent decision at its insert boundary (Section L: "decision made locally, at delivery, against that DB's current data"). Scope the API/sync layer, not just the schema.

================================================================
# CURRENT BUILD STATE AT SESSION END
================================================================
- P1 committed (3339839f4). P2 dedup core: built, applied, PROVEN on Droid 1 (zero collisions, aliases working incl. 'Equestrian Cg', 67 tracks with cross-file dupes collapsed). **NOT yet committed.**
- Import-recap patch: applied; first build FAILED (missing `var dropped/aliased` declarations); one-line fix patch (patch_v25_import_recap_fix_v1.py) written + self-tested; **rebuild in progress at session end.**
- PENDING: confirm recap rebuild succeeds → Droid 2 full import (v1→v3 on its own owned DB, normal clear) → watch trail recap report real breakdown → COMMIT P2 + recap.
- NON-BUGS (don't chase): gate delete-authority (test-rig artifact only — foreign-owned DB forced install); map-centering default (old permission until reboot).
- REAL follow-ups: tile storage media-scan (move tiles out of scanned path — caused the device bricking); track-import recap breakdown (insertTrackToDb report inserted-vs-collapsed); beginDedupSession load-only-needed-type optimization.
