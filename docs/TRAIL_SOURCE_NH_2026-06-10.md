# NH Trail Source — entry for trail_sources.json (DRAFT, 2 fields to verify)
_Created 2026-06-10. Fred relocating to NH for ~6 months; needs NH trails + trailheads importable via the existing 7-source catalog. NH GRANIT is NH's authoritative state GIS source (UNH-hosted, ArcGIS Hub) — the UGRC-equivalent. The in-app importer (TrailImporter.buildUrl) issues a STANDARD ArcGIS query (`?f=geojson&outSR=4326&outFields=*` + envelope bbox + resultOffset paging), so any ArcGIS FeatureServer/MapServer `/query` endpoint is drop-in. NH GRANIT is ArcGIS → drop-in, NO code change, just a new object in trail_sources.json._

## STATUS: structurally complete; 2 fields are PLACEHOLDER until verified from the NH GRANIT REST page.

## How buildUrl consumes a source (confirmed from TrailImporter.kt)
    val sb = StringBuilder(src.query_url)
    sb.append("?f=geojson&outSR=4326&outFields=*")
    // + &geometry=w,s,e,n &geometryType=esriGeometryEnvelope &spatialRel=Intersects &inSR=4326
    // + &resultOffset=N &resultRecordCount=2000
So query_url must be the layer `/query` endpoint (…/FeatureServer/0/query or …/MapServer/N/query). fields.name/type map to the service's real column names. motorized_where is a SQL clause on those columns (or "1=1" to import all).

## NH source object to ADD to the "sources" array in app/src/main/assets/trail_sources.json
    {
      "id": "nhgranit_trails",
      "name": "NH Recreational Trails",
      "agency": "NH GRANIT (UNH) / NH Geodata Portal",
      "scope": "state",
      "query_url": "VERIFY/FeatureServer/0/query",
      "max_records": 2000,
      "native_sr": "4326",
      "boundary": { "n": 45.31, "s": 42.70, "e": -70.61, "w": -72.56 },
      "fields": {
        "id": "OBJECTID",
        "name": "VERIFY_NAME_FIELD",
        "type": "VERIFY_TYPE_FIELD"
      },
      "motorized_where": "1=1",
      "format": "arcgis_geojson"
    }

## VERIFIED facts
- Source: NH GRANIT "NH Recreational Trails", ArcGIS service item id 7274322793b74f33a36fb3c2419886be.
- The dataset includes BOTH recreational/other trails AND trailhead/parking locations (Hub map 1de57c4fdac1464f940e543631140ab5 description). So trailheads are a layer in the same service — a second entry (or trailhead_asset) once the trailhead layer index is known.
- NH state bounding box (for "boundary"): N 45.31, S 42.70, E -70.61, W -72.56.
- Portal dataset page: https://www.nhgeodata.unh.edu/datasets/7274322793b74f33a36fb3c2419886be  (also nh-granit-nhgranit.hub.arcgis.com/datasets/NHGRANIT::nh-recreational-trails)
- License: planning-use approximation; trails subject to change/closure; landowner-consent caveat. Display this caveat to users.

## 2 THINGS TO VERIFY (one visit to the NH GRANIT REST page)
1. query_url — from the dataset page, "I want to use this" / "View API Resources" → the FeatureServer URL. The trails layer is almost certainly /FeatureServer/0; confirm the index. Append /query. Strip any params (buildUrl adds its own).
2. fields.name + fields.type — open <FeatureServerURL>/0?f=json (or the REST layer page) and read the field list. Find the trail-name column (likely TrailName / TRAIL_NAME / NAME) and a type/use column. Map:
     "name": "<that name column>",
     "type": "<that type column>"
   If there's a motorized/use column, optionally set motorized_where (e.g. "USE LIKE '%Motor%'"); otherwise leave "1=1" to import everything (fine for NH non-OHV use).

## TRAILHEADS (second entry, later)
Same service, the trailhead/parking layer (different layer index, geometry = points). Two ways depending on how you want it: (a) add as a second source object pointing at that layer's /query with point geometry, or (b) one-time export to a *_trailheads.json asset and reference via "trailhead_asset" like utah_trailheads.json. Decide once the trailhead layer index + fields are known.

## TEST PLAN once filled
1. Add the object to trail_sources.json (mind JSON commas).
2. Rebuild + install (assets change needs a build).
3. Work with Artifacts → Import Trails → "NH Recreational Trails" → import by area (your NH viewport so it's not the whole state).
4. Force-close/reopen; zoom in; confirm NH trails draw.
5. Then test route snap-2 against the imported NH trails (replaces the Utah-viewport workaround).

## NATIONAL FALLBACK (already in catalog)
For federal land in NH (White Mountain NF), the existing usfs_nfs_trails and nps_public_trails sources already cover it — no new entry needed for those.
