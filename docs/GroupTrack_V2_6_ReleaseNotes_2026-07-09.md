# GroupTrack V2.6 — Release Notes
**Date:** 2026-07-09 · **Milestone:** 2.6a (fallback) committed; 2.6b (map transport/speed) in progress · **Status:** internal draft

> 2.6a is the fallback release (storage cutover + compression + map/UI fixes), committed 07-08. 2.6b adds map-download speed and transport work. Nothing here touches the mesh radio (radio work is 2.6c).

---

## New in this build

### Much faster map downloads (2.6b)
Downloading offline map areas is now **several times faster**. Map tiles are fetched in parallel instead of one at a time, so a large multi-source area (satellite + topo layers) that previously could take many hours now completes in roughly an hour and a half in field testing. The download is also more resilient: if a map server briefly rate-limits or returns a temporary error, the app now backs off and retries that tile instead of skipping it — so downloads finish more complete.

*(Draft user-facing line: "Map downloads are now several times faster, and more reliable when the map servers are busy.")*

*Note (internal): further download speed-up via even area-segmentation load balancing is the next 2.6b build — will make large downloads finish in the balanced/fast case every time rather than occasionally running long.*

### Full-detail topo downloads
The TOPO and TOPO+ map layers now download their full available zoom detail (a previous internal cap that limited them to a lower zoom has been removed). Note the USGS TOPO+ source itself still has no imagery above roughly zoom 15 — that's a source limitation, unchanged.

---

## Carried from 2.6a (already committed 07-08)

### Smaller offline maps (~55% less storage)
Offline map tiles now use a compact single-database format per map type instead of thousands of loose image files, with efficient WebP compression for satellite imagery. Field testing showed roughly **55%** less satellite-map storage (about 21.5 KB per tile down to 9.7 KB) with no visible difference at normal viewing zoom.

### Offline map labels fixed
Road and place-name labels on the satellite map now stay visible after going offline and when switching map areas.

### Download maps from the main map
You can start a map-area download for a track directly from its detail popup on the main (convoy) map.

---

## Known / by-design in this build
- **TOPO+ (USGS topo)** has no imagery above roughly zoom 15 — a limitation of the USGS source, not the app. (A future update will smoothly upscale the last available zoom instead of showing "map data not available.")
- **SAT map labels on topo layers** — the satellite road/place labels can currently appear over the TOPO/TOPO+ base maps. A fix to show them only on the satellite base is planned.

---

## Still to land in 2.6b / 2.6a
- Even area-segmentation load balancing for downloads (faster, consistent large downloads)
- Clear/delete a downloaded map area (surgical, per-area)
- SAT-overlay-on-topo display fix
- Route+ state persistence, track rename, aliases UI, minor cleanups

---

## Upgrade notice
After updating, previously downloaded offline maps use the old format. To get the storage savings and the new speed, re-download the map areas you use (the old loose-file tiles can be cleared). Field-tester note: if a device holds a very large old loose-tile set, clearing it before re-downloading frees the space immediately. **Do not delete map tiles with a file manager while GroupTrack is running** — close/force-stop the app first.

---
*Internal draft — 2026-07-09. Publish-ready copy to be finalized when 2.6b lands and the AAB is cut.*
