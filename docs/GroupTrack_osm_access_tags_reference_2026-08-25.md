# OSM access tags — source reference for the PBF classification task

**Filed 2026-08-25.** Reference material for step 2 of the 2.6h sequence (PBF
classification / carto codes). Not yet validated against GroupTrack's own
imported data — see the caveats at the end, which matter more than the table.

---

## 1. The core trail types

Public trails are almost always one of three `highway` values:

| key | what it is |
|---|---|
| `highway=path` | multi-use trail (hiking, biking, etc.) |
| `highway=footway` | built primarily for walking |
| `highway=track` | rough unpaved road or dirt track — **the OHV/4x4 case** |

## 2. Segmenting by use

Look for `yes` or `designated`. Avoid `private` or `no`.

| use | trail types | access tags |
|---|---|---|
| Hiking | `path`, `footway`, `track` | `foot=yes` / `foot=designated` |
| Biking | `path`, `track` | `bicycle=yes` / `bicycle=designated` |
| Equestrian | `path`, `track` | `horse=yes` / `horse=designated` |
| OHV (ATV/quad) | `track`, `path` | `ohv=yes` / `ohv=designated` / `atv=yes` |
| Motorcycle | `track`, `path` | `motorcycle=yes` / `motorcycle=designated` |
| 4x4 | `track` | `4wd_only=yes` / `motor_vehicle=yes` |

## 3. ⚠ The implicit-default gotcha

OSM relies on **implicit defaults when an access tag is missing**:

- `highway=footway` is implicitly open to hikers even without `foot=yes`
- `highway=track` is often open to motor vehicles **unless** a restriction
  (`motor_vehicle=no`, a gate) says otherwise

**So absence of a tag is not absence of access.** A classifier that requires
an explicit `yes` will silently drop most of the network.

## 4. Motorized nuance worth keeping

`motor_vehicle=yes` technically admits cars, motorcycles and OHVs. But
`motor_vehicle=no` **combined with** `ohv=yes` means standard cars are banned
while off-highway vehicles are allowed. For GroupTrack's riders that
combination is a *positive* signal, not an exclusion — it is close to a
definition of the ground they want.

## 5. Reference SQL shape (PostGIS/JSONB)

Not GroupTrack's schema — kept for the CASE ordering, which is the useful part:
motorized checks first, then non-motorized, then a catch-all.

```sql
SELECT id, geom, tags->'name' AS trail_name,
  CASE
    WHEN tags->'ohv' IN ('yes','designated') OR tags->'atv' = 'yes' THEN 'OHV'
    WHEN tags->'4wd_only' = 'yes'                                   THEN '4x4'
    WHEN tags->'motorcycle' IN ('yes','designated')                 THEN 'Motorcycle'
    WHEN tags->'bicycle' IN ('yes','designated')                    THEN 'Biking'
    WHEN tags->'horse' IN ('yes','designated')                      THEN 'Equestrian'
    WHEN tags->'foot' IN ('yes','designated') OR highway = 'footway' THEN 'Hiking'
    ELSE 'Multi-Use / Unclassified Path'
  END AS primary_trail_use
FROM planet_osm_line
WHERE highway IN ('path','footway','track')
  AND (tags->'access' IS NULL OR tags->'access' NOT IN ('no','private'));
```

---

## ⛔ Before any of this is used

**Confirm the tags survived import.** This is the open question deferred on
2026-08-24 and it governs everything above. GroupTrack's `trails` table carries
`trail_id, name, geometry, min_lat, max_lat, min_lon, max_lon, created_at,
updated_at, carto_code, source_id, geom_hash` — **there is no tags column.**

So either the access tags were discarded at import, or they were collapsed into
`carto_code`. Until a `PRAGMA table_info` and a real sample say which, this
document describes what OSM *offers*, not what GroupTrack *has*.

The standing rule applies: **examine the actual data before categorising.**

## ⚠ Why this matters for the interim filter

`osm_layers.json` currently filters `trails` to seven `fclass` values —
`track`, `track_grade1`–`5`, `unclassified` — with `path` and `bridleway`
removed on 2026-08-24 (TRAILFILTER-K).

That removal is **an interim measure that contradicts the standing rule**:
classify at import, filter at use. Dropping classes at extract is irreversible
and costs a full re-download to undo, and `path` is exactly the judgement call
that has no right answer from `fclass` alone — correct for a Jeep, wrong for a
dirt bike.

**K is to be reversed as part of this task**: restore `path` and `bridleway` to
`filter_values` (back to nine), and let the classification above sort them at
*use* rather than at import. That is the whole point of the carto work.

## Open question for the sequence

Is `carto_code` already populated from these tags, partially populated, or
empty? The answer decides whether step 2 is a classification pass over data
already present, or a re-import.

---

# The import plan (Fred, 2026-08-25)

**Two downloads per region, not one.**

1. **Geofabrik `.osm.pbf`** — carries the full tag set, including every access
   tag in the table above.
2. **The GeoPackage / shapefile extract** — carries clean geometry and is what
   the importer already knows how to read.

**Mine the PBF for the required attributes only** — vehicle type and access
tags — never import it wholesale. Interpret those tags into a use
classification, **write the result onto the skinny GeoPackage before import**,
and import once.

⭐ The PBF is a *lookup*, not a second dataset. Nothing about the existing
import path changes except that the features arrive already classified.

**Then choose a staging attribute** on the GeoPackage to carry the answer,
which is deployed/mapped to `carto_code` at import.

## ⭐ The staging attribute may already be designed

The V2 National Trail Data Architecture already specifies, on `trails`:

    vehicle_class TEXT   -- atv, utv, motorcycle, full-size, mixed

That is the staging attribute this plan is looking for, already named, already
in the schema design, and already in the rider's vocabulary rather than OSM's.
Worth adopting rather than inventing a new one.

⚠ It is **not** in the live table. The current `trails` schema is `trail_id,
name, geometry, min_lat, max_lat, min_lon, max_lon, created_at, updated_at,
carto_code, source_id, geom_hash` — so `vehicle_class` was designed and never
built, and `carto_code` arrived instead.

## ⛔ The question that governs the whole plan: is there a join key?

Mining the PBF only works if a PBF way can be matched to a GeoPackage feature.
That needs a stable OSM id on both sides.

`reference_points` has one — `source_uid` holds values like `83189618`, which
is an OSM id. **`trails` appears to have no equivalent**: `source_id` names the
*source* (`osm`), not the feature.

If there is no per-feature OSM id on `trails`, the tags cannot be joined back
and the plan needs either a spatial match (fragile) or an id added at extract
time (clean, but changes the extract).

**Check first, before anything else in this task:**

```bash
python -c "
import sqlite3
c=sqlite3.connect(r'D:\nav_test\spatial_after.db')
for r in c.execute('select trail_id,name,carto_code,source_id from trails limit 5'): print(r)
print('distinct source_id:', [r[0] for r in c.execute('select distinct source_id from trails limit 10')])
print('carto_code values:', [r for r in c.execute('select carto_code,count(*) from trails group by 1 order by 2 desc limit 12')])
"
```

That answers three things at once: whether `trail_id` is an OSM id or a
generated UUID, what `source_id` actually contains, and whether `carto_code` is
populated, partially populated, or empty.

⭐ The last one decides the shape of the task: a classification pass over data
already present, or a re-import.

---

# 2026-08-25 — what the day's debugging adds to this task

## ⛔ The node-density finding changes what classification is for

Instrumentation on 08-25 measured rider pins snapping **up to 6,001 feet** to the
nearest graph node, with ten pins collapsing onto four nodes. The cause is that
**nodes exist only where trail segments end or cross.** A trail running two miles
between junctions has no node along it.

That matters here because the PBF pipeline decides where segments break:

- **OSM ways are already split at every tag change.** A trail whose surface,
  access or name changes mid-run arrives as several ways sharing endpoint nodes.
  Those shared endpoints are exactly the node density the router needs.
- **The GeoPackage/shapefile extract is a derived product** — Geofabrik merges
  and re-splits, and the OSM node ids do not survive. Whatever node structure it
  produces is an artefact of that conversion, not of the source.

⭐ **So mining the PBF is not only about access tags. It is about topology.**
The `nodeRefs` list on each way is the connectivity information the routing graph
should be built from, and it is discarded today.

## ⭐ Relation stitching matters more than first recorded

The reference pipeline suggests stitching ways into master trails via
`type=route` relations. For GroupTrack that is not cosmetic:

- A named trail system currently arrives as dozens of unrelated fragments
- Fragments are why `onFragment` appears in `assess()` output
- Stitching by relation, then by shared endpoint node, is what makes a trail one
  artifact instead of forty

## ⚠ Adjust the filter for OHV, not hiking

The reference pipeline targets `highway=path`/`footway` and
`route=foot`/`hiking`. GroupTrack's riders want:

- `highway=track` first, then `path`
- `ohv`, `atv`, `motorcycle`, `4wd_only`, `motor_vehicle`
- `route=mtb` and unnamed relations, not just `route=hiking`

Same pipeline, different tag test.

## ⭐ Confirmed on 08-25: unnamed trails route fine

`NONAMES` does not exclude trails from the graph — routes were observed running
entirely over unnamed trails. So name is not a filter criterion and the
classification work does not need to solve naming.

## The measurement to take first, before any pipeline work

Against `D:\nav_test\spatial_after.db`, at Panguitch:

1. How many **nodes** does the corridor graph hold, and what is the median
   distance between adjacent nodes along a trail? That number is the ceiling on
   how accurately any tapped point can be placed without edge splitting.
2. How many trails carry **no junction at all** within the corridor — i.e. arrive
   as a single long segment?

⚠ If node density is low everywhere, edge splitting is required regardless of
what the PBF work produces, and it should be built first.
