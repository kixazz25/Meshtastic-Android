# ADDENDUM — Esri tile store-and-forward via AWS (idea captured 2026-06-01)

Append to the NEXT_SESSION_HANDOFF. This is an AWS-related architecture idea, also tied to the existing V3 Map Manager cloud-tile-sync work.

## The idea
Esri (ArcGIS) offers a FREE API tier of ~2 million tiles/month — enough headroom to TEST a new tile-download method at no cost.

The real design question (not just "download faster"): **should GroupTrack pull tiles from Esri ONCE, store them on AWS, and then store-and-forward to devices FROM AWS** — so the first user who pulls a given area populates the AWS tile cache, and every subsequent user/device for that same area reads from AWS instead of hitting Esri again. A caching-proxy / first-pull-populates-the-cloud model for TILES.

## Why it fits
- It's the SAME shape as the artifact first-occurrence model ("first arrival populates the shared store; others read the cached copy") — just applied to map tiles instead of trails/tracks/waypoints/routes.
- **Quota protection:** the Esri free tier (2M tiles/mo) burns fast if every device independently pulls tiles for the same popular areas (e.g. the St George / southern Utah corridor everyone rides). Pull-once-serve-many from AWS minimizes redundant Esri pulls and keeps usage under the free cap.
- **Ties into existing roadmap:** V3 Map Manager Phase C already specifies "AWS tile cloud sync per org," PROTECTED/PURGEABLE tile states, a 2GB cap, and auto-download on ride enrollment. This Esri store-and-forward is the upstream source side of that same tile-cloud system — worth designing them together.

## Open questions for when this is worked
- Esri free-tier license terms: confirm caching/re-serving tiles on AWS is permitted under the free API license (some tile providers prohibit re-hosting — check before building the cache). This is the gating legal/ToS question.
- Tile storage format on AWS: individual tiles vs packaged (.mbtiles) blobs; how devices request a region.
- Cache key + freshness: how AWS decides it already has an area (tile x/y/z coords) and whether/when tiles expire.
- Relationship to the existing osmdroid tile cache and the tile-storage media-scan fix (tiles should live OUT of media-scanned storage — see STATE_OF_PLAY follow-ups).

## Status
IDEA / to-investigate. Not started. Belongs in the AWS-session scope (store-and-forward is an AWS feature) and overlaps V3 Map Manager Phase C.
