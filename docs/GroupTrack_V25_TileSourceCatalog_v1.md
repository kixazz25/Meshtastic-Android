# GroupTrack V2.5 — Tile Source Catalog

**Three free sources per map type. No API key required unless noted.**
**User manages their own keys. GroupTrack never embeds or distributes API keys.**

---

## SATELLITE (3 free, no key)

| # | Name | URL Template | Key | Max Zoom | Notes |
|---|------|-------------|-----|----------|-------|
| 1 | Google Hybrid | `https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` | No | 20 | Satellite + labels + roads. Current GroupTrack default (SAT). |
| 2 | Google Satellite | `https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}` | No | 20 | Pure satellite, no labels. |
| 3 | Esri World Imagery | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | No | 19 | High-res global imagery. Legacy service, still functioning. |

## TOPO (3 free, no key)

| # | Name | URL Template | Key | Max Zoom | Notes |
|---|------|-------------|-----|----------|-------|
| 1 | OpenTopoMap | `https://tile.opentopomap.org/{z}/{x}/{y}.png` | No | 17 | Contour lines, trails, elevation shading. Community maintained. |
| 2 | USGS Topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}` | No | 16 | Official USGS topographic maps. Current GroupTrack TOPO+. |
| 3 | Esri World Topo | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}` | No | 19 | Esri topo with roads, terrain, boundaries. Current GroupTrack TOPO. |

## ROAD / STREET (3 free, no key)

| # | Name | URL Template | Key | Max Zoom | Notes |
|---|------|-------------|-----|----------|-------|
| 1 | OpenStreetMap | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | No | 19 | Community-maintained street map. Standard OSM style. |
| 2 | Google Roads | `https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}` | No | 20 | Google Maps road view. |
| 3 | Google Terrain | `https://mt0.google.com/vt/lyrs=p&x={x}&y={y}&z={z}` | No | 20 | Google terrain with elevation shading + roads. |

## TRAIL / OUTDOOR (2 free + 1 free key)

| # | Name | URL Template | Key | Max Zoom | Notes |
|---|------|-------------|-----|----------|-------|
| 1 | CalTopo USFS | `https://caltopo.s3.amazonaws.com/topo/{z}/{x}/{y}.png` | No | 16 | USFS topo via CalTopo S3. Good trail detail for western US. |
| 2 | Esri NatGeo | `https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}` | No | 16 | National Geographic style with terrain + trails. |
| 3 | Thunderforest Outdoors | `https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}` | Free key | 22 | Detailed outdoor/trail map. Free plan: 150K tiles/month. Signup: thunderforest.com/docs/apikeys/ |

---

## User-Added Sources (examples — user enters URL + their key)

| Service | Map Type | Requires | Notes |
|---------|----------|----------|-------|
| CalTopo subscription | topo/trail | CalTopo API key | Higher zoom, more layers than free S3 |
| Gaia GPS tiles | trail | Gaia subscription | User's own Gaia account |
| onX Offroad | trail | onX subscription | User's own onX account |
| Mapbox | satellite/road | Mapbox access token | Free tier: 200K tiles/month |
| MapTiler | satellite/topo | MapTiler API key | Free tier: 100K tiles/month |

---

## Standing Rules

- GroupTrack ships the 12 default sources above. All are free, no-key (except Thunderforest which is free-key).
- Users add their own sources via settings: name, map_type, URL template, API key.
- API keys stored locally on device. Never uploaded to GroupTrack servers.
- If a source goes offline or changes pricing, the user deletes or updates the entry. GroupTrack does not manage tile source availability.
- Tile sources are canvas. GroupTrack's product is the spatial data layer on top.

---

*GroupTrack V2.5 | Tile Source Catalog v1 | May 9, 2026*
