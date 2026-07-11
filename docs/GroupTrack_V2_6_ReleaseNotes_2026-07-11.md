# GroupTrack V2.6 — Release Notes
**Date:** 2026-07-11 · **Milestone:** 2.6a (fallback) committed; 2.6b (map transport/speed) in progress · **Status:** internal draft — RELEASE DELAYED (see note)

> **Release timing (2026-07-11): the V2.6 Play Store release has been DELAYED ~4 weeks** to clean up all open issues without rushing, and to fold in the lead-cart tracking re-engineer. No partial/Monday release. This doc continues to track shipped work; publish-ready copy is finalized when the AAB is cut.

> 2.6a is the fallback release (storage cutover + compression + map/UI fixes), committed 07-08. 2.6b adds map-download speed and transport work. Nothing here touches the mesh radio (radio work is 2.6c).

---

## New in this build

### Even faster, consistently-balanced map downloads (2.6b) — NEW 07-11
Large offline map downloads now split the drawn area into **balanced segments** so no single piece can run long by itself. Previously a big satellite area could occasionally strand one download slot on an oversized chunk while the others sat idle — making some downloads take much longer than others for no obvious reason. Now the area is divided so every piece carries a comparable amount of work, and the download reliably finishes in the fast/balanced case every time rather than occasionally running long. In testing, this made a large multi-layer download run faster with two active download slots than it previously did with three.

*(Draft user-facing line: "Large map downloads are now more consistent — they reliably finish in the fast case instead of occasionally running long.")*

### Much faster map downloads (2.6b)
Downloading offline map areas is now **several times faster**. Map tiles are fetched in parallel instead of one at a time, so a large multi-source area (satellite + topo layers) that previously could take many hours now completes in roughly an hour and a half in field testing. The download is also more resilient: if a map server briefly rate-limits or returns a temporary error, the app now backs off and retries that tile instead of skipping it — so downloads finish more complete.

*(Draft user-facing line: "Map downloads are now several times faster, and more reliable when the map servers are busy.")*

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

## Still to land before the V2.6 release (~4-week plan)
- **Queue boot-loop immunity** (top priority — app must never be bricked by a bad download-queue file on startup)
- Route+ state persistence (route-add mode stays put until explicitly closed)
- Download-queue write hardening (atomic write + tolerant load)
- Clear/delete a downloaded map area (surgical, per-area)
- Map REFRESH fix; SAT-overlay-on-topo display fix; per-source download estimate accuracy
- Map-source-change tile-replace (routed through the new balanced-download path)
- Lead-cart / lead-track tracking re-engineer (2.6c, needs radio)
- Track rename, aliases UI, minor cleanups

---

## Upgrade notice
After updating, previously downloaded offline maps use the old format. To get the storage savings and the new speed, re-download the map areas you use (the old loose-file tiles can be cleared). Field-tester note: if a device holds a very large old loose-tile set, clearing it before re-downloading frees the space immediately. **Do not delete map tiles with a file manager while GroupTrack is running** — close/force-stop the app first.

---
*Internal draft — 2026-07-11. Release DELAYED ~4 weeks; publish-ready copy to be finalized when the full V2.6 lands and the AAB is cut.*
