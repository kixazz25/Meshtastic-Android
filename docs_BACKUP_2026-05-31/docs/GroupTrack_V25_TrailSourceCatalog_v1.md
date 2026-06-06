# GroupTrack — Trail Source Catalog and Deduplication Method
## V2.5 Reference | May 9, 2026

---

## Part 1: Trail Source Catalog

### Source 1 — UGRC Utah Trails and Pathways (PROVEN)
- **Region:** Utah statewide
- **Agency:** Utah Geospatial Resource Center
- **Features:** 49,096 (full state), 46,953 after filtering
- **Download page:** https://opendata.gis.utah.gov/datasets/3080c0a2859a4d23a279e17e17c703c8
- **Feature service:** https://services1.arcgis.com/99lidPhWCzftIe9K/ArcGIS/rest/services/TrailsAndPathways/FeatureServer/0
- **Format:** GeoJSON export from open data portal
- **Filter fields:** MotorizedAllowed (Yes/null), DesignatedUses (Multiuse/Pedestrian), SurfaceType, HorseAllowed, CartoCode
- **OHV filter:** MotorizedAllowed = "Yes" → 18,230 trails
- **Key attributes:** PrimaryName, ID, Status, DesignatedUses, SurfaceType, MotorizedAllowed, HorseAllowed, OwnerSteward, County, RecreationArea, Unique_ID
- **Status:** PROVEN — 46,953 trails ingested and verified May 9, 2026
- **Notes:** Includes USFS trails curated by UGRC. Overlap with USFS national dataset likely. Last edited dates show active maintenance through 2025-2026.

### Source 2 — BLM National GTLF Motorized Trails
- **Region:** National (all BLM lands), filterable by state
- **Agency:** Bureau of Land Management
- **Download (GeoJSON):** https://gbp-blm-egis.hub.arcgis.com/datasets/BLM-EGIS::blm-natl-gtlf-public-motorized-trails.geojson
- **Hub page:** https://gbp-blm-egis.hub.arcgis.com/
- **Map service:** https://gis.blm.gov/arcgis/rest/services/transportation/BLM_Natl_GTLF_Public_Display/MapServer
- **Layers:** Layer 3 = motorized trails, Layer 4 = non-motorized, Layer 7 = all trails
- **Format:** GeoJSON, Shapefile, CSV, KML all available
- **Filter fields:** PLAN_OHV_ROUTE_DSGNTN (Open/Closed/Limited), OHV_ROUTE_DSGNTN_LIM (by vehicle/season/time), PLAN_ASSET_CLASS, BLM_ADMIN_ST (state filter)
- **OHV filter:** PLAN_OHV_ROUTE_DSGNTN = "OPEN" or "LIMITED"
- **Key attributes:** TRAIL_NAME, GTLF_ID, BLM_ADMIN_ST, PLAN_OHV_ROUTE_DSGNTN, PLAN_ASSET_CLASS, SURFACE_TYPE, ROUTE_SPCL_DSGNTN_TYPE
- **Status:** IDENTIFIED — needs download and schema mapping
- **Notes:** National dataset — very large. Filter by BLM_ADMIN_ST for state-specific subsets. This is the source for Parowan Gap and Arizona Strip trails missing from UGRC. MaxRecordCount = 2000, may need paginated download.

### Source 3 — BLM National GTLF Non-Motorized Trails
- **Region:** National
- **Agency:** Bureau of Land Management
- **Download (GeoJSON):** https://gbp-blm-egis.hub.arcgis.com/datasets/BLM-EGIS::blm-natl-gtlf-public-nonmotorized-trails.geojson
- **Status:** IDENTIFIED
- **Notes:** Same schema as motorized. Available for hikers/equestrian filter selections.

### Source 4 — USFS National Forest System Trails
- **Region:** National (all National Forests)
- **Agency:** US Forest Service
- **Hub page:** https://data-usfs.hub.arcgis.com/datasets/usfs::national-forest-system-trails-feature-layer/about
- **Download (GeoJSON):** https://data-usfs.hub.arcgis.com/datasets/usfs::national-forest-system-trails-feature-layer.geojson
- **Download (Shapefile):** https://data-usfs.hub.arcgis.com/api/download/v1/items/0969eb1cbb2f4a1d861ee58fff587cc2/shapefile?layers=0
- **Clearinghouse:** https://data.fs.usda.gov/geodata/edw/datasets.php?xmlKeyword=trails
- **Filter fields:** TERRA_MOTORIZED (Y/N), SNOW_MOTORIZED (Y/N), HIKER_PEDESTRIAN_MANAGED, PACK_SADDLE_MANAGED, BICYCLE_MANAGED
- **OHV filter:** TERRA_MOTORIZED = "Y"
- **Key attributes:** TRAIL_NAME, TRAIL_NO, TRAIL_TYPE, TRAIL_CLASS, SURFACE_TYPE, MANAGING_ORG, GIS_MILES
- **Status:** IDENTIFIED — needs download and schema mapping
- **Notes:** Significant overlap with UGRC in Utah (UGRC includes USFS trails). National dataset, very large. Filter by forest name or spatial clip for state-specific subsets. Active maintenance — data updated regularly.

### Source 5 — Arizona State Trails
- **Region:** Arizona
- **Agency:** Arizona State Land Department / Arizona State Parks
- **Portal:** https://azgeo.az.gov/ (Arizona Geographic Information Council)
- **Status:** NEEDS RESEARCH — verify trail dataset availability and download format
- **Notes:** Arizona Strip (BLM land south of Utah border) is covered by BLM GTLF Source 2. State trails on non-federal land need Arizona-specific source.

### Source 6 — Nevada State Trails
- **Region:** Nevada
- **Agency:** Nevada Division of State Lands
- **Portal:** http://www.nbmg.unr.edu/Maps&Data/VirtualClearinghouse.html (Nevada Bureau of Mines)
- **Status:** NEEDS RESEARCH — verify trail dataset availability
- **Notes:** Most Nevada OHV riding is on BLM land, well covered by Source 2.

### Source 7 — Colorado State Trails
- **Region:** Colorado
- **Agency:** Colorado Parks and Wildlife
- **Portal:** https://data-cdphe.opendata.arcgis.com/ (Colorado Open Data)
- **BLM Colorado:** https://catalog.data.gov/dataset/blm-colorado-roads-and-trails-177fa (GeoJSON available)
- **Status:** NEEDS RESEARCH
- **Notes:** Colorado has strong GIS portal. BLM Colorado trails available via GTLF standard.

### Source 8 — Idaho State Trails
- **Region:** Idaho
- **Agency:** Idaho Department of Parks and Recreation
- **Status:** NEEDS RESEARCH
- **Notes:** Idaho OHV riding primarily on USFS and BLM land, covered by Sources 2 and 4.

---

## Catalog Entry JSON Format

```json
{
  "sources": [
    {
      "id": "ugrc-utah",
      "name": "Utah Trails and Pathways",
      "agency": "Utah Geospatial Resource Center (UGRC)",
      "region": "Utah",
      "description": "Statewide trails including USFS, BLM, state, and municipal trails",
      "download_url": "https://opendata.gis.utah.gov/datasets/3080c0a2859a4d23a279e17e17c703c8",
      "format": "geojson",
      "estimated_features": 49096,
      "estimated_size_mb": 113,
      "last_verified": "2026-05-09",
      "status": "verified",
      "filters": {
        "motorized": {"field": "MotorizedAllowed", "value": "Yes", "label": "Motorized / OHV"},
        "hiking": {"field": "DesignatedUses", "contains": "Pedestrian", "label": "Hiking"},
        "horse": {"field": "HorseAllowed", "value": "Yes", "label": "Equestrian"},
        "multi_use": {"field": "DesignatedUses", "contains": "Multiuse", "label": "Multi-use"},
        "paved": {"field": "SurfaceType", "value": "Paved", "label": "Paved paths"},
        "unpaved": {"field": "SurfaceType", "value": "Unpaved", "label": "Unpaved trails"}
      },
      "default_filters": ["motorized", "unpaved"],
      "schema_map": {
        "name": "PrimaryName",
        "source_id": "Unique_ID",
        "trail_code": "ID",
        "surface_type": "SurfaceType",
        "county": "County",
        "source_agency": "OwnerSteward"
      }
    },
    {
      "id": "blm-motorized",
      "name": "BLM Motorized Trails",
      "agency": "Bureau of Land Management",
      "region": "National (filter by state)",
      "description": "OHV and motorized trails on BLM-managed federal lands",
      "download_url": "https://gbp-blm-egis.hub.arcgis.com/datasets/BLM-EGIS::blm-natl-gtlf-public-motorized-trails.geojson",
      "format": "geojson",
      "estimated_features": "unknown — national dataset",
      "last_verified": "2026-05-09",
      "status": "identified",
      "filters": {
        "state_ut": {"field": "BLM_ADMIN_ST", "value": "UT", "label": "Utah"},
        "state_az": {"field": "BLM_ADMIN_ST", "value": "AZ", "label": "Arizona"},
        "state_nv": {"field": "BLM_ADMIN_ST", "value": "NV", "label": "Nevada"},
        "state_co": {"field": "BLM_ADMIN_ST", "value": "CO", "label": "Colorado"},
        "state_id": {"field": "BLM_ADMIN_ST", "value": "ID", "label": "Idaho"},
        "open": {"field": "PLAN_OHV_ROUTE_DSGNTN", "value": "OPEN", "label": "OHV Open"},
        "limited": {"field": "PLAN_OHV_ROUTE_DSGNTN", "value": "LIMITED", "label": "OHV Limited"}
      },
      "default_filters": ["open", "limited"],
      "schema_map": {
        "name": "TRAIL_NAME",
        "source_id": "GTLF_ID",
        "surface_type": "SURFACE_TYPE",
        "source_agency": "constant:BLM"
      }
    },
    {
      "id": "usfs-national",
      "name": "USFS National Forest Trails",
      "agency": "US Forest Service",
      "region": "National (filter by forest)",
      "description": "Trails in National Forests and Grasslands",
      "download_url": "https://data-usfs.hub.arcgis.com/datasets/usfs::national-forest-system-trails-feature-layer.geojson",
      "format": "geojson",
      "estimated_features": "unknown — national dataset",
      "last_verified": "2026-05-09",
      "status": "identified",
      "filters": {
        "motorized": {"field": "TERRA_MOTORIZED", "value": "Y", "label": "Motorized"},
        "hiking": {"field": "HIKER_PEDESTRIAN_MANAGED", "not_null": true, "label": "Hiking"},
        "horse": {"field": "PACK_SADDLE_MANAGED", "not_null": true, "label": "Equestrian"},
        "bike": {"field": "BICYCLE_MANAGED", "not_null": true, "label": "Mountain Bike"}
      },
      "default_filters": ["motorized"],
      "schema_map": {
        "name": "TRAIL_NAME",
        "source_id": "TRAIL_NO",
        "trail_code": "TRAIL_NO",
        "surface_type": "SURFACE_TYPE",
        "length_miles": "GIS_MILES",
        "source_agency": "MANAGING_ORG"
      }
    }
  ]
}
```

---

## Part 2: Deduplication Method

### The Problem

A rider downloads Utah UGRC trails (source 1) then adds BLM Utah trails (source 2). Many trails exist in both datasets — same physical trail, different names, slightly different geometry, different attribute schemas. Without dedup, the map shows two overlapping lines for the same trail.

Known overlaps:
- UGRC includes USFS trails curated by Utah → overlaps with USFS national dataset
- UGRC includes some BLM trails → overlaps with BLM GTLF dataset
- BLM and USFS manage adjacent/overlapping areas → potential overlaps between sources 2 and 4

### Detection Method — Spatial Proximity on Ingestion

When inserting a new trail during ingestion, check against existing trails in the database:

**Step 1 — Bounding box pre-filter:**
Find existing trails whose bounding box overlaps the incoming trail's bounding box. This is fast using the existing bbox indexes.
```sql
SELECT trail_id, name, source_agency, length_miles, geometry_json,
       min_lon, min_lat, max_lon, max_lat
FROM trails
WHERE max_lon >= :new_min_lon AND min_lon <= :new_max_lon
AND max_lat >= :new_min_lat AND min_lat <= :new_max_lat
```

**Step 2 — Length filter:**
From the bbox matches, keep only trails where length is within 20% of the incoming trail.
```
abs(existing.length_miles - incoming.length_miles) / incoming.length_miles < 0.20
```

**Step 3 — Point proximity sampling:**
Sample 5 evenly-spaced points along the incoming trail. For each point, find the minimum distance to any point on the candidate existing trail. If 4 of 5 sampled points are within 50 meters of the existing trail, flag as PROBABLE DUPLICATE.

**Step 4 — Classification:**
- 5/5 points within 50m + length within 10% → CONFIRMED DUPLICATE
- 4/5 points within 50m + length within 20% → PROBABLE DUPLICATE
- 3/5 points within 50m → POSSIBLE DUPLICATE (review needed)
- 2 or fewer → NOT A DUPLICATE

### Resolution — Ingestion Review Screen

During ingestion, duplicates are collected and presented for review:

**Auto-resolved (no user action needed):**
- CONFIRMED DUPLICATE: skip incoming trail. Keep existing. Log skipped trail with reason.

**Requires review (presented on screen):**
- PROBABLE DUPLICATE: show both trails on a mini-map side by side. Display name, source agency, attribute completeness (count of non-null fields). Recommend keeping the one with richer attributes. User taps KEEP EXISTING, REPLACE WITH NEW, or KEEP BOTH.
- POSSIBLE DUPLICATE: same review screen but with a stronger visual warning that these may be different trails that happen to be nearby.

### Review Screen Design

```
┌─────────────────────────────────────────────┐
│ DUPLICATE REVIEW — 23 of 340 probable dupes │
│                                             │
│  ┌──────────────┐  ┌──────────────┐         │
│  │ [mini map]   │  │ [mini map]   │         │
│  │ existing     │  │ incoming     │         │
│  └──────────────┘  └──────────────┘         │
│                                             │
│  EXISTING              INCOMING             │
│  Name: Trail Canyon    Name: Trail Canyon   │
│  Source: UGRC/USFS     Source: BLM           │
│  Length: 3.2 mi        Length: 3.4 mi        │
│  Fields: 12/18         Fields: 8/18         │
│  Match: 4/5 points within 50m               │
│                                             │
│  [KEEP EXISTING]  [REPLACE]  [KEEP BOTH]    │
│                                             │
│  [ SKIP ALL REMAINING ] (auto-resolve rest) │
└─────────────────────────────────────────────┘
```

**Bulk actions:**
- SKIP ALL REMAINING — auto-resolve all remaining probable dupes by keeping existing
- REPLACE ALL — replace all existing with incoming source (when you trust the new source more)
- After review: summary screen showing kept/replaced/skipped counts

### Schema Impact

Add to trails table:
```sql
-- Dedup tracking
dupe_check_hash  TEXT,  -- hash of sampled point coordinates for fast re-check
dupe_of          TEXT,  -- trail_id of the trail this was identified as duplicate of (NULL if unique)
dupe_status      TEXT,  -- 'unique' | 'kept' | 'skipped' | 'replaced'
```

Add dedup log table:
```sql
CREATE TABLE dedup_log (
    log_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    incoming_name   TEXT,
    incoming_source TEXT,
    existing_id     TEXT,
    existing_name   TEXT,
    existing_source TEXT,
    match_points    INTEGER,  -- how many of 5 points matched
    length_diff_pct REAL,
    resolution      TEXT,     -- 'auto_skip' | 'user_keep_existing' | 'user_replace' | 'user_keep_both'
    resolved_at     TEXT
);
```

### Standing Rules

- First source ingested always wins the auto-resolve. If UGRC was imported first and BLM has a duplicate, UGRC stays by default.
- User can override any auto-resolution via the review screen.
- KEEP BOTH is always an option — some "duplicates" are actually different trails in close proximity (parallel trails, trail and access road).
- The dedup log preserves all decisions. If a user later finds a bad resolution, they can re-import the source and make a different choice.
- Dedup runs per-ingestion, not globally. Adding BLM trails checks against existing UGRC trails. Adding USFS trails checks against both existing UGRC and BLM trails.

---

## Part 3: Next Steps

1. Download BLM motorized trails GeoJSON for Utah (filter BLM_ADMIN_ST = "UT")
2. Map BLM schema to GroupTrack schema (schema_map in catalog entry)
3. Run ingestion against Utah BLM with dedup against existing UGRC trails
4. Review duplicates on ingestion screen — verify detection accuracy
5. Refine proximity thresholds if needed (50m, 20% length, 4/5 points)
6. Repeat for USFS national (filter to Utah forests)
7. Document findings — how many dupes per source pair, accuracy of auto-resolve

---

*GroupTrack V2.5 | Trail Source Catalog and Dedup Method v1 | May 9, 2026*
