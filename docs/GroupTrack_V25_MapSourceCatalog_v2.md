# GroupTrack — Complete Map Source Catalog
## Every Known XYZ Tile Source by Map Type
## May 9, 2026

---

## MAP TYPES (6 categories)

| Code | Type | Description | Example Use |
|------|------|-------------|-------------|
| SAT | Satellite | Pure satellite/aerial imagery, no labels | Terrain reconnaissance |
| HYB | Hybrid | Satellite imagery + roads + labels | Primary riding map |
| TOPO | Topographic | Contour lines, elevation shading, terrain features | Route planning, elevation |
| STREET | Street/Road | Road networks, cities, POIs | Navigation to trailhead |
| TERRAIN | Terrain | Elevation shading with roads | Overview planning |
| OUTDOOR | Outdoor/Trail | Trail-optimized, marked paths, contours, POIs | Trail-specific navigation |

---

## COMPLETE SOURCE TABLE

### SATELLITE (SAT) — Pure Imagery

| # | Producer | Name | URL Template | Key | Key Cost | Signup URL | Max Zoom | Downloadable |
|---|----------|------|-------------|-----|----------|-----------|----------|-------------|
| 1 | Google | Google Satellite | `https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}` | No | Free | — | 20 | Yes |
| 2 | Esri | Esri World Imagery | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | No | Free | — | 19 | Yes |
| 3 | Esri | Esri Clarity Imagery | `https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | No | Free | — | 19 | Yes |
| 4 | MapTiler | MapTiler Satellite | `https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key={key}` | Yes | Free tier | maptiler.com/cloud | 20 | Yes |
| 5 | Mapbox | Mapbox Satellite | `https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.png?access_token={key}` | Yes | Free tier | mapbox.com/signup | 22 | Yes |

### HYBRID (HYB) — Satellite + Roads + Labels

| # | Producer | Name | URL Template | Key | Key Cost | Layers | Max Zoom |
|---|----------|------|-------------|-----|----------|--------|----------|
| 6 | Google | Google Hybrid | `https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` | No | Free | Single | 20 |
| 7 | Esri | Esri Sat+Roads | Base: `https://server.arcgisonline.com/.../World_Imagery/.../tile/{z}/{y}/{x}` + Overlay: `https://server.arcgisonline.com/.../World_Transportation/.../tile/{z}/{y}/{x}` | No | Free | 2 layers | 19 |
| 8 | Esri | Esri Sat+Labels | Base: World_Imagery + Overlay: World_Boundaries_and_Places | No | Free | 2 layers | 19 |
| 9 | USGS | USGS Imagery+Topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}` | No | Free | Single | 16 |

### TOPOGRAPHIC (TOPO) — Contours + Elevation

| # | Producer | Name | URL Template | Key | Key Cost | Signup URL | Max Zoom |
|---|----------|------|-------------|-----|----------|-----------|----------|
| 10 | OpenTopoMap | OpenTopoMap | `https://tile.opentopomap.org/{z}/{x}/{y}.png` | No | Free | — | 17 |
| 11 | USGS | USGS Topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}` | No | Free | — | 16 |
| 12 | Esri | Esri World Topo | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}` | No | Free | — | 19 |
| 13 | Esri | Esri NatGeo | `https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}` | No | Free | — | 16 |
| 14 | CalTopo | CalTopo USFS Topo | `https://caltopo.s3.amazonaws.com/topo/{z}/{x}/{y}.png` | No | Free | — | 16 |
| 15 | CalTopo | CalTopo MapBuilder Topo | Requires CalTopo Pro subscription tile URL | Yes | $50/yr | caltopo.com/buy | 16 |
| 16 | CalTopo | CalTopo Slope Angle | Requires CalTopo Pro subscription tile URL | Yes | $50/yr | caltopo.com/buy | 16 |
| 17 | MapTiler | MapTiler Topo | `https://api.maptiler.com/maps/topo/256/{z}/{x}/{y}.png?key={key}` | Yes | Free tier | maptiler.com/cloud | 22 |

### STREET / ROAD (STREET)

| # | Producer | Name | URL Template | Key | Key Cost | Max Zoom |
|---|----------|------|-------------|-----|----------|----------|
| 18 | OSM | OpenStreetMap | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | No | Free | 19 |
| 19 | Google | Google Roads | `https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}` | No | Free | 20 |
| 20 | Esri | Esri Streets | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}` | No | Free | 19 |
| 21 | CartoDB | CartoDB Voyager | `https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png` | No | Free | 20 |
| 22 | CartoDB | CartoDB Positron | `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png` | No | Free | 20 |
| 23 | CartoDB | CartoDB Dark Matter | `https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png` | No | Free | 20 |

### TERRAIN

| # | Producer | Name | URL Template | Key | Key Cost | Max Zoom |
|---|----------|------|-------------|-----|----------|----------|
| 24 | Google | Google Terrain | `https://mt{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}` | No | Free | 20 |
| 25 | Google | Google Terrain Only | `https://mt{s}.google.com/vt/lyrs=t&x={x}&y={y}&z={z}` | No | Free | 20 |
| 26 | Esri | Esri Shaded Relief | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Shaded_Relief/MapServer/tile/{z}/{y}/{x}` | No | Free | 13 |
| 27 | USGS | USGS Shaded Relief | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSShadedReliefOnly/MapServer/tile/{z}/{y}/{x}` | No | Free | 16 |
| 28 | Stadia | Stadia Stamen Terrain | `https://tiles.stadiamaps.com/tiles/stamen_terrain/{z}/{x}/{y}.png?api_key={key}` | Yes | Free tier | 18 |

### OUTDOOR / TRAIL

| # | Producer | Name | URL Template | Key | Key Cost | Signup URL | Max Zoom |
|---|----------|------|-------------|-----|----------|-----------|----------|
| 29 | Thunderforest | Outdoors | `https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}` | Yes | Free tier | thunderforest.com/docs/apikeys/ | 22 |
| 30 | Thunderforest | Landscape | `https://tile.thunderforest.com/landscape/{z}/{x}/{y}.png?apikey={key}` | Yes | Free tier | same | 22 |
| 31 | Thunderforest | Cycle | `https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey={key}` | Yes | Free tier | same | 22 |
| 32 | MapTiler | MapTiler Outdoor | `https://api.maptiler.com/maps/outdoor/256/{z}/{x}/{y}.png?key={key}` | Yes | Free tier | maptiler.com/cloud | 22 |
| 33 | Mapbox | Mapbox Outdoors | `https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/{z}/{x}/{y}?access_token={key}` | Yes | Free tier | mapbox.com/signup | 22 |
| 34 | Stadia | Stadia Outdoors | `https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}.png?api_key={key}` | Yes | Free tier | stadiamaps.com | 20 |
| 35 | onX | onX Offroad | User enters onX tile URL + subscription credentials | Yes | $30/yr | onxmaps.com | varies |
| 36 | onX | onX Hunt | User enters onX tile URL + subscription credentials | Yes | $30/yr | onxmaps.com | varies |
| 37 | Gaia GPS | Gaia GPS | User enters Gaia tile URL + subscription credentials | Yes | $40/yr | gaiagps.com | varies |

### OVERLAYS (transparent, layered on top of base)

| # | Producer | Name | URL Template | Key | Key Cost | Max Zoom |
|---|----------|------|-------------|-----|----------|----------|
| 38 | Google | Roads Overlay | `https://mt{s}.google.com/vt/lyrs=h&x={x}&y={y}&z={z}` | No | Free | 20 |
| 39 | Esri | Transportation | `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}` | No | Free | 19 |
| 40 | Esri | Boundaries+Places | `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}` | No | Free | 19 |

---

## SOURCE ATTRIBUTES (data model for tile_sources table)

Every source, regardless of cost model, has these attributes:

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
| source_id | INTEGER PK | Yes | Auto-increment |
| producer | TEXT | Yes | Who provides: Google, Esri, USGS, CalTopo, onX, etc. |
| map_type | TEXT | Yes | SAT, HYB, TOPO, STREET, TERRAIN, OUTDOOR, OVERLAY |
| name | TEXT | Yes | Full display name |
| short_label | TEXT | Yes | Abbreviated for map bar (max 8 chars) |
| url_template | TEXT | Yes | XYZ URL with {z},{x},{y},{s},{key},{r} placeholders |
| subdomains | TEXT | No | Comma-separated: "0,1,2,3" or "a,b,c,d" |
| requires_key | INTEGER | Yes | 0=no key needed, 1=key required |
| api_key | TEXT | No | User-entered key. Stored locally. Never uploaded. |
| api_key_param | TEXT | No | URL param name: apikey, token, access_token, key |
| api_registration_url | TEXT | No | Where user signs up or purchases access |
| api_cost_note | TEXT | No | "Free", "Free tier: 150K/mo", "$50/yr", "$30/yr" |
| attribution | TEXT | Yes | Required display text per provider TOS |
| min_zoom | INTEGER | Yes | Minimum zoom level |
| max_zoom | INTEGER | Yes | Maximum zoom level |
| tile_size | INTEGER | Yes | 256 or 512 |
| tile_format | TEXT | Yes | png or jpg |
| is_overlay | INTEGER | Yes | 0=base layer, 1=transparent overlay |
| is_default | INTEGER | Yes | 1=shipped with app, 0=user-added |
| is_active | INTEGER | Yes | 1=show in picker, 0=hidden |
| sort_order | INTEGER | Yes | Display order within type group |
| notes | TEXT | No | User's notes about this source |

### Composite source handling (multi-layer maps):

Some sources require multiple layers (base + overlay) to create a complete map. The catalog JSON uses a `layers` array:

```json
{
  "id": "esri-hybrid",
  "producer": "Esri",
  "map_type": "HYB",
  "name": "Esri Satellite + Roads",
  "short_label": "ESRI HYB",
  "layers": [
    {"role": "base", "url_template": "...World_Imagery.../tile/{z}/{y}/{x}", "format": "jpg"},
    {"role": "overlay", "url_template": "...World_Transportation.../tile/{z}/{y}/{x}", "format": "png"}
  ]
}
```

Downloading this source downloads BOTH layers. Leaflet renders base first, overlay on top. The interceptor caches each layer's tiles independently.

For single-layer sources (most of them), the layers array has one entry.

---

## MAP SLOT CONFIGURATION

### Three slots, user-configurable:

```sql
CREATE TABLE map_slots (
    slot_number     INTEGER PRIMARY KEY CHECK(slot_number BETWEEN 1 AND 3),
    source_id       TEXT NOT NULL,       -- references catalog id
    label           TEXT NOT NULL,       -- from catalog, shown on map bar
    short_label     TEXT NOT NULL        -- abbreviated for narrow bar
);
```

### Default configuration (matches current V2.4):
| Slot | Source | Label | Short |
|------|--------|-------|-------|
| 1 | google-hybrid | Google Hybrid | HYB |
| 2 | esri-world-topo | Esri World Topo | TOPO |
| 3 | usgs-imagery-topo | USGS Imagery+Topo | TOPO+ |

### User changes a slot:
1. Long-press map bar heading (e.g., "TOPO")
2. Source picker opens, grouped by map type
3. Sources requiring unprovided API keys show lock icon
4. User selects new source
5. Slot label updates from catalog data
6. Map reloads with new tile URL
7. Previous slot's cached tiles remain (switchable back without re-download)

---

## PAID SOURCE SETUP PROCESS

Process is identical for free-key and paid sources:

1. User opens Map Source settings
2. Sees source with lock icon and note: "Requires API key — $50/yr at caltopo.com"
3. Taps the source → shown api_registration_url in browser
4. User creates account, pays if required, gets API key from provider
5. Returns to GroupTrack, enters key in the API Key field
6. Key stored locally in tile_sources.api_key — never uploaded
7. Source unlocked — available for slot assignment and tile downloading

GroupTrack does not validate keys. If the key is wrong, tiles fail to load — user sees blank tiles and knows to check their key. Simple, no server-side key validation needed.

---

## onX AND GAIA NOTE

onX and Gaia don't publish official XYZ tile API documentation for third-party apps. Their tile URLs can be discovered via browser developer tools when using their web viewers. These URLs may change without notice.

For the catalog, these are listed as "custom source" entries where the user enters the URL template themselves. GroupTrack provides:
- Producer name and logo
- Map type classification
- api_registration_url pointing to their subscription page
- A notes field with guidance: "Open onX web viewer, use browser dev tools to find tile URL pattern"

If onX or Gaia ever publish official tile APIs, update the catalog entry with the documented URL template.

---

## SUMMARY

| Category | Count | Key Required | Cost |
|----------|-------|-------------|------|
| Free, no key | 18 | No | $0 |
| Free tier, key required | 12 | Yes (free signup) | $0 |
| Paid subscription | 5+ | Yes (paid signup) | $30-50/yr |
| Custom user-entered | unlimited | varies | varies |
| **Total cataloged** | **40** | | |

All 40 sources use the same attributes, same table structure, same Leaflet integration, same download process, same interceptor caching. The only difference is whether `requires_key` is true and what `api_registration_url` points to.

---

*GroupTrack | Complete Map Source Catalog v2 | May 9, 2026*
