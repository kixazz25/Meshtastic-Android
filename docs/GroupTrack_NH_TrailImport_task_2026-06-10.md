# GroupTrack — NH Trail Import: Task + Discovery + Way Forward — 2026-06-10

_Note-to-future-Claude (and Fred as backstop). Fred is relocating to NH for ~6 months and needs NH trails + trailheads on the maps so route snap-2 has real geometry to snap to in NH. Today's Utah-viewport testing works because the Utah UGRC data is still in the spatial DB, but that's a stopgap — NH needs its own data._

## THE DECISION (Fred, end of day)
Use the **source-document path** — add NH GRANIT as the 8th entry in `app/src/main/assets/trail_sources.json` — NOT the manual DB-load. Fred: "I would rather do NH with a source document rather than manually move the data in." The manual loader is parked.

## WHY THIS IS (almost) DROP-IN — the standard mapping
The in-app importer already supports 8 sources, all on ONE standard pattern, so adding a 9th... er, the NH one as the next source is DATA, not code:
- `TrailImporter.buildUrl(src, s,w,n,e, offset)` issues a STANDARD ArcGIS query:
  `<query_url>?f=geojson&outSR=4326&outFields=*&geometry=w,s,e,n&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&inSR=4326&resultOffset=N&resultRecordCount=2000`
- The 8 existing sources in trail_sources.json: ugrc_utah_trails, azsp_trails, cotrex_colorado, blm_gtlf_all, usfs_nfs_trails, usgs_national_trails, nps_public_trails, usfs_mvum. Each is one JSON object with: id, name, agency, scope, query_url (the FeatureServer/MapServer `/N/query`), max_records, native_sr, boundary{n,s,e,w}, fields{id,name,type,use,...mapped to that source's real columns}, motorized_where, format:"arcgis_geojson".
- NH GRANIT is an ArcGIS Hub service → speaks exactly this protocol → ONE new JSON object, viewport-import (so Fred can import just his NH area, not the whole state).

## THE DISCOVERY / WHAT WENT WRONG (so we don't re-chase)
1. NH GRANIT "NH Recreational Trails" is the authoritative source (UNH-hosted, NH's UGRC equivalent). ArcGIS item id `7274322793b74f33a36fb3c2419886be`. Dataset includes BOTH trails AND trailhead/parking layers. ~19,877 trail features statewide.
2. **Could NOT resolve the exact FeatureServer `/query` URL from Fred's machine.** Two reasons: (a) the portal dataset page is a JS app, so the REST endpoint isn't in the page HTML; (b) Fred's Git-Bash `curl` is BLOCKED from arcgis.com (curl to services1.arcgis.com returned nothing; hitting the GRANIT server root returned only an unrelated marsh-migration MapServer — wrong server). So the two source-document fields that need the live service — `query_url` and `fields.name`/`fields.type` — are still PLACEHOLDERS.
3. Fred then downloaded the whole statewide GeoJSON directly from the Hub Download button: **Trails.geojson, 90,137,333 bytes, 19,877 LineString features, WGS84.** Its real field names: `trailname` (name), `trailsys` (system/type), `objectid` (id), plus per-use flags (atv, dirtbike, snowmbl, mtnbike, roadbike, horse, ped, xcski, paddle, paved, ada, wide, miles, community, maintorg, mapurl).
4. A byte-matched DB-loader (`load_nh_trails_to_db_2026-06-10_v1.py`) was written to load that file into a DB copy and push it — but Fred prefers the source-document path, so the loader is PARKED (kept in case the source path stalls).

## THE WAY FORWARD (next NH session)
**Verify two fields from a BROWSER (not curl), then add one JSON object.** Steps:
1. In a browser, open the NH GRANIT dataset: `https://nh-granit-nhgranit.hub.arcgis.com/datasets/NHGRANIT::nh-recreational-trails` (or nhgeodata.unh.edu/datasets/7274322793b74f33a36fb3c2419886be). Use "I want to use this" → "View API Resources" → copy the **FeatureServer URL**. The trails layer is index **2** (the Hub GeoJSON link is the item id + `_2`), so the query_url is likely `<FeatureServer>/2/query`. CONFIRM the index.
2. Open `<FeatureServer>/2?f=json` (or the REST layer page) to read the **field list**. From the downloaded file we ALREADY KNOW the columns: name = `trailname`, type = `trailsys`, id = `objectid`. So fields map: `{"id":"objectid","name":"trailname","type":"trailsys"}`. (The browser step is really just to confirm the layer index + that the live service exposes those same column names — the GeoJSON download already told us the schema.)
3. Add this object to the "sources" array in `app/src/main/assets/trail_sources.json` (draft also in TRAIL_SOURCE_NH_2026-06-10.md):
```json
{
  "id": "nhgranit_trails",
  "name": "NH Recreational Trails",
  "agency": "NH GRANIT (UNH) / NH Geodata Portal",
  "scope": "state",
  "query_url": "<VERIFY FeatureServer base>/2/query",
  "max_records": 2000,
  "native_sr": "4326",
  "boundary": { "n": 45.31, "s": 42.70, "e": -70.61, "w": -72.56 },
  "fields": { "id": "objectid", "name": "trailname", "type": "trailsys" },
  "motorized_where": "atv <> ' ' OR dirtbike <> ' ' OR snowmbl <> ' '",
  "format": "arcgis_geojson"
}
```
(NH bbox is verified. motorized_where above is a guess based on the file's use-flags — atv/dirtbike/snowmbl are " " when not applicable; safe to start with "1=1" if unsure.)
4. Rebuild (assets change needs a build), install, then in-app: Work with Artifacts → Import Trails → "NH Recreational Trails" → import by AREA (Fred's NH viewport, not statewide). Force-close/reopen, zoom in (>=8), confirm NH trails draw. Then build a route to test snap on NH trails.

## TRAILHEADS (second pass)
NH GRANIT's same service has a trailhead/parking layer (different layer index, point geometry). Either add a second source object pointing at that layer, or use the `trailhead_asset` mechanism the way ugrc_utah_trails references utah_trailheads.json. Decide once the trailhead layer index is known.

## FILES (in Fred's Downloads / outputs, to save)
- TRAIL_SOURCE_NH_2026-06-10.md — the source-entry draft (2 fields to verify).
- load_nh_trails_to_db_2026-06-10_v1.py — the PARKED manual DB-loader (byte-matched; only if the source path stalls).
- Trails.geojson — the 90MB statewide download (schema reference; also the loader's input if needed).

## NATIONAL FALLBACK (already in catalog)
For federal land in NH (White Mountain National Forest), the existing usfs_nfs_trails and nps_public_trails sources already cover it — no new entry needed for those.
