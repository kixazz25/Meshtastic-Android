# GroupTrack V2.6a — Release Notes
**Date:** 2026-07-08 · **Milestone:** 2.6a (known-good fallback release) · **Status:** in progress — Pass 1 + WebP committed; remaining 2.6a items open

> 2.6a is the **fallback release** banked to the Play Store before the risky Esri transport rewrite (2.6b). It bundles the storage cutover, compression, and a set of map/UI fixes. Nothing in 2.6a touches the mesh radio (radio work is 2.6c).

---

## New in this build

### Smaller offline maps (~55% less storage)
Offline map tiles now use a compact single-database format per map type instead of thousands of loose image files, and satellite imagery is stored with efficient WebP compression. In field testing, satellite map storage dropped by roughly **55%** (about 21.5 KB per tile down to 9.7 KB) with no visible difference at normal viewing zoom. Smaller downloads, faster deletes, less space used on your device.

*(Draft user-facing line — trim/adjust for the store: "Offline maps now take about half the space, with faster downloads and cleanup.")*

### Offline map labels fixed
Road and place-name labels on the satellite map now stay visible after going offline and when switching map areas. Previously they could disappear on the first offline transition and not return.

### Download maps from the main map
You can now start a map-area download for a track directly from its detail popup on the main (convoy) map — it opens the same source-selection and replace-tiles panel available on the planning map.

---

## Known / by-design in this build
- **TOPO+ (USGS topo)** has no imagery above roughly zoom 15 — this is a limitation of the USGS source, not the app. (A future update will smoothly upscale the last available zoom instead of showing "map data not available.")

---

## Still to land in 2.6a (not in this build yet)
- Route+ state persistence (route screen stays open until you explicitly save or discard)
- Track rename to a human-readable name
- Aliases UI
- Minor cleanups

---

## Upgrade notice
After updating, previously downloaded offline maps use the old format. To get the storage savings, re-download the map areas you use (the old loose-file tiles can be cleared). Field-tester note: if a device is holding a very large old loose-tile set, clearing it before re-downloading frees the space immediately.

---
*Internal draft — 2026-07-08. Publish-ready copy to be finalized when the remaining 2.6a items land and the AAB is cut.*
