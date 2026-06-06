# GroupTrack — Map Tile Source Reference
## Complete Analysis of Available Base Map Options
## May 9, 2026

---

## 1. HOW TILE URLS WORK

Every raster tile source uses the same XYZ pattern. A map tile is a 256x256 PNG image at a specific zoom level and grid position.

**URL template:** `https://server.com/tiles/{z}/{x}/{y}.png`
- `{z}` = zoom level (0-20, where 0 = whole world, 20 = building level)
- `{x}` = tile column (0 to 2^z - 1, left to right)
- `{y}` = tile row (0 to 2^z - 1, top to bottom)

**Leaflet integration:**
```javascript
L.tileLayer('https://server.com/tiles/{z}/{x}/{y}.png', {
    attribution: '© Provider',
    maxZoom: 19,
    subdomains: 'abc'  // optional, for load balancing
}).addTo(map);
```

**Switching sources** = changing the URL template. Leaflet clears the current tiles and loads from the new URL. In GroupTrack, the tile_sources table stores the URL template. User selects a source, app swaps the Leaflet tileLayer URL. Cached/downloaded tiles use the same URL pattern for offline serving via the interceptor.

**API key substitution:** sources requiring keys use `{key}` in the URL or append `?apikey={key}` as a query parameter. The app reads the key from tile_sources.api_key and substitutes before passing to Leaflet.

---

## 2. COMPLETE SOURCE CATALOG

### TIER 1 — Free, No API Key Required

These work by simply plugging the URL into Leaflet. No signup, no key, no account.

#### SATELLITE / IMAGERY

| Source | URL Template | Max Zoom | Notes |
|--------|-------------|----------|-------|
| Google Satellite | `https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}` | 20 | Pure satellite imagery, no labels. Subdomains: 0,1,2,3 |
| Google Hybrid | `https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` | 20 | Satellite + roads + labels. Best overall hybrid. Subdomains: 0,1,2,3 |
| Esri World Imagery (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | 19 | High-res global imagery. Legacy "mature support" — working but Esri pushing migration. Note: {y}/{x} order, not {x}/{y} |
| Esri World Imagery Clarity (legacy) | `https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | 19 | Enhanced version of Esri imagery |

**Google tile URL parameter codes:**
- `lyrs=s` — satellite only
- `lyrs=y` — hybrid (satellite + labels + roads)
- `lyrs=m` — street map
- `lyrs=p` — terrain with roads
- `lyrs=t` — terrain only (no roads)
- `lyrs=h` — roads overlay only (transparent, for layering)

**Important note on Google tiles:** Google does not officially support direct tile access. These URLs are undocumented and could change. They work reliably and have for years, but there is no SLA. For a production app, this is acceptable as long as the user can switch sources — which our architecture supports.

#### TOPOGRAPHIC

| Source | URL Template | Max Zoom | Notes |
|--------|-------------|----------|-------|
| OpenTopoMap | `https://tile.opentopomap.org/{z}/{x}/{y}.png` | 17 | Contour lines, elevation shading, trails. Community maintained. Rate limited — respect usage policy. |
| USGS Topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}` | 16 | Official USGS topographic maps. US coverage only. Note: {y}/{x} order |
| USGS Imagery+Topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}` | 16 | USGS topo lines overlaid on satellite imagery |
| Esri World Topo (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}` | 19 | Esri topo with roads, terrain, boundaries. Note: {y}/{x} order |
| Esri NatGeo | `https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}` | 16 | National Geographic style |

#### ROAD / STREET

| Source | URL Template | Max Zoom | Notes |
|--------|-------------|----------|-------|
| OpenStreetMap | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | 19 | Community-maintained street map. Standard style. Rate limited — max 2 requests/sec, must display attribution. |
| Google Roads | `https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}` | 20 | Google Maps street view. Subdomains: 0,1,2,3 |
| Google Terrain | `https://mt{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}` | 20 | Terrain with elevation shading + roads |
| Esri Streets (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}` | 19 | Esri detailed street map |
| CartoDB Voyager | `https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png` | 20 | Clean modern style. Subdomains: a,b,c,d. {r}=@2x for retina |
| CartoDB Positron | `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png` | 20 | Light/minimal style |
| CartoDB Dark Matter | `https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png` | 20 | Dark theme |

#### TERRAIN / SHADED RELIEF

| Source | URL Template | Max Zoom | Notes |
|--------|-------------|----------|-------|
| Google Terrain Only | `https://mt{s}.google.com/vt/lyrs=t&x={x}&y={y}&z={z}` | 20 | Terrain shading only, no labels or roads |
| Esri Shaded Relief (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Shaded_Relief/MapServer/tile/{z}/{y}/{x}` | 13 | Hillshade only. Low max zoom. |
| USGS Shaded Relief | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSShadedReliefOnly/MapServer/tile/{z}/{y}/{x}` | 16 | US-only shaded relief |

#### OVERLAYS (transparent, layer on top of base map)

| Source | URL Template | Max Zoom | Notes |
|--------|-------------|----------|-------|
| Google Roads Overlay | `https://mt{s}.google.com/vt/lyrs=h&x={x}&y={y}&z={z}` | 20 | Roads + labels only, transparent background. Layer over satellite. |
| Esri Transportation (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}` | 19 | Road overlay. Degrading (legacy). |
| Esri Boundaries+Places (legacy) | `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}` | 19 | Labels overlay. Sparse/broken online, still works from cache. |

---

### TIER 2 — Free API Key Required (free tier available)

User signs up, gets a free API key, enters it in GroupTrack settings. Free tier has monthly limits.

| Source | URL Template | Key Signup | Free Tier | Max Zoom | Notes |
|--------|-------------|-----------|-----------|----------|-------|
| Thunderforest Outdoors | `https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}` | thunderforest.com/docs/apikeys/ | 150K tiles/month | 22 | Excellent trail/outdoor map. Good off-road detail. |
| Thunderforest Landscape | `https://tile.thunderforest.com/landscape/{z}/{x}/{y}.png?apikey={key}` | same | same | 22 | Terrain-focused style |
| Thunderforest Cycle | `https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey={key}` | same | same | 22 | OpenCycleMap style. Trails + contours. |
| MapTiler Outdoor | `https://api.maptiler.com/maps/outdoor/256/{z}/{x}/{y}.png?key={key}` | maptiler.com/cloud | 100K tiles/month | 22 | Modern outdoor map |
| MapTiler Satellite | `https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key={key}` | same | same | 20 | Satellite imagery alternative |
| MapTiler Topo | `https://api.maptiler.com/maps/topo/256/{z}/{x}/{y}.png?key={key}` | same | same | 22 | Topo map |
| Mapbox Satellite | `https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.png?access_token={key}` | mapbox.com/signup | 200K tiles/month | 22 | High quality satellite |
| Mapbox Outdoors | `https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/{z}/{x}/{y}?access_token={key}` | same | same | 22 | Outdoor activity map |
| Stadia Outdoors | `https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}{r}.png?api_key={key}` | stadiamaps.com | 200K tiles/month | 20 | Based on OpenMapTiles |
| Stadia Stamen Terrain | `https://tiles.stadiamaps.com/tiles/stamen_terrain/{z}/{x}/{y}{r}.png?api_key={key}` | same | same | 18 | Classic Stamen terrain style |
| Esri New Basemaps | `https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/arcgis/outdoor/static/tile/{z}/{y}/{x}?token={key}` | developers.arcgis.com | 2M tiles/month | 19 | New Esri service replacing legacy. Multiple styles available. |

---

### TIER 3 — Paid Subscription (user's own account)

User has existing subscription with these services. They enter their credentials in GroupTrack.

| Source | Notes | Who Uses It |
|--------|-------|-------------|
| CalTopo | User enters their CalTopo API token. Various map layers including USFS topo, slope angle, land management. | Backcountry users, SAR teams |
| Gaia GPS | User enters Gaia credentials. Gaia's proprietary map layers. | Hikers, hunters, overlanders |
| onX Offroad | User enters onX credentials. Private land boundaries, OHV-specific layers. | OHV riders, hunters |
| onX Hunt | Same as above, hunt-specific layers. | Hunters |
| Avenza Maps | PDF-based georeferenced maps. Different tile model. | Varies |

For Tier 3, GroupTrack provides a "custom source" entry where the user enters the URL template and their key. We don't pre-define these because the URL patterns may change with the provider's API and the user manages their own account.

---

## 3. RECOMMENDED DEFAULTS FOR GROUPTRACK

Ship these as pre-configured sources in the tile_sources table. User can add more.

| # | Name | Type | URL Template | Key? | Why |
|---|------|------|-------------|------|-----|
| 1 | Google Hybrid | satellite | `https://mt0.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` | No | Best overall hybrid for off-road. Current default. |
| 2 | Google Satellite | satellite | `https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}` | No | Pure imagery, no labels. |
| 3 | Esri World Imagery | satellite | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | No | Alternative satellite source. |
| 4 | OpenTopoMap | topo | `https://tile.opentopomap.org/{z}/{x}/{y}.png` | No | Best free topo with contours. |
| 5 | USGS Topo | topo | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}` | No | Official USGS. US only. |
| 6 | Esri World Topo | topo | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}` | No | Good general topo worldwide. |
| 7 | OpenStreetMap | road | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | No | Standard road map. |
| 8 | Google Roads | road | `https://mt0.google.com/vt/lyrs=m&x={x}&y={y}&z={z}` | No | Google street view. |
| 9 | Google Terrain | topo | `https://mt0.google.com/vt/lyrs=p&x={x}&y={y}&z={z}` | No | Terrain with roads. |
| 10 | CartoDB Voyager | road | `https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png` | No | Clean modern style. |
| 11 | USGS Imagery+Topo | hybrid | `https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}` | No | Satellite with topo lines. |
| 12 | Google Terrain Only | terrain | `https://mt0.google.com/vt/lyrs=t&x={x}&y={y}&z={z}` | No | Raw terrain shading. |

---

## 4. TILE_SOURCES TABLE DESIGN

```sql
CREATE TABLE tile_sources (
    source_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,           -- Display name in picker
    map_type        TEXT NOT NULL,           -- satellite|topo|road|terrain|hybrid|overlay
    url_template    TEXT NOT NULL,           -- URL with {x},{y},{z},{s},{key},{r} placeholders
    subdomains      TEXT,                    -- comma-separated: "0,1,2,3" or "a,b,c,d" or NULL
    requires_key    INTEGER NOT NULL DEFAULT 0,
    api_key         TEXT,                    -- user's key, stored locally, never uploaded
    key_signup_url  TEXT,                    -- URL where user gets free key
    key_param_name  TEXT,                    -- "apikey" or "access_token" or "token" or "key"
    attribution     TEXT,                    -- required display text
    min_zoom        INTEGER NOT NULL DEFAULT 0,
    max_zoom        INTEGER NOT NULL DEFAULT 18,
    tile_size       INTEGER NOT NULL DEFAULT 256,  -- 256 or 512
    tile_format     TEXT NOT NULL DEFAULT 'png',   -- png or jpg
    is_overlay      INTEGER NOT NULL DEFAULT 0,    -- 0=base layer, 1=transparent overlay
    is_default      INTEGER NOT NULL DEFAULT 0,    -- 1=shipped with app
    is_active       INTEGER NOT NULL DEFAULT 1,    -- 1=show in picker
    sort_order      INTEGER NOT NULL DEFAULT 0,
    notes           TEXT                     -- user notes about this source
);
```

### What each field does:

**url_template** — the core field. Contains the tile URL with placeholders:
- `{x}`, `{y}`, `{z}` — tile coordinates (required)
- `{s}` — subdomain for load balancing (optional, uses subdomains field)
- `{key}` — API key substitution (optional, uses api_key field)
- `{r}` — retina suffix, replaced with `@2x` on high-DPI screens or empty string

**subdomains** — some servers use multiple subdomains for parallel downloads. Google uses `0,1,2,3`. CartoDB uses `a,b,c,d`. OSM uses `a,b,c`. If NULL, no subdomain substitution.

**key_param_name** — different providers use different parameter names:
- Thunderforest: `apikey`
- Mapbox: `access_token`
- Esri new: `token`
- MapTiler: `key`
The app uses this to know how to substitute the key in the URL or append as query parameter.

**is_overlay** — if 1, this layer is transparent and renders on top of a base layer. Used for road overlays on satellite imagery. Leaflet handles overlay layers differently (L.tileLayer with transparent PNG).

**tile_size** — most sources use 256px tiles. Esri new basemaps use 512px. Leaflet needs to know this for correct rendering.

---

## 5. LEAFLET INTEGRATION IN GROUPTRACK

### Current implementation (V2.4):
convoy_map.html has hardcoded tile layer URLs with three sources (SAT, TOPO, TOPO+) selected by ConvoyConfig.ACTIVE_TILE_SOURCE. The interceptor serves cached tiles from local storage when offline.

### V2.5 changes:
- Tile layer URL comes from tile_sources table, not hardcoded
- Switching source = update the tileLayer URL in JavaScript via bridge call
- Interceptor URL pattern matching updated to handle any URL template
- API key substitution happens in Kotlin before passing URL to Leaflet
- Overlay layers supported as additional transparent tileLayer instances

### JavaScript bridge for source switching:
```javascript
// Called from Kotlin when user selects a new tile source
function setTileSource(urlTemplate, attribution, minZoom, maxZoom) {
    if (baseLayer) map.removeLayer(baseLayer);
    baseLayer = L.tileLayer(urlTemplate, {
        attribution: attribution,
        minZoom: minZoom,
        maxZoom: maxZoom
    }).addTo(map);
}

// Called from Kotlin to add/remove overlay
function setOverlay(urlTemplate, attribution, opacity) {
    if (overlayLayer) map.removeLayer(overlayLayer);
    if (urlTemplate) {
        overlayLayer = L.tileLayer(urlTemplate, {
            attribution: attribution,
            opacity: opacity || 0.7
        }).addTo(map);
    }
}
```

### Kotlin side:
```kotlin
// Build the final URL from template + tile_sources fields
fun buildTileUrl(source: TileSource): String {
    var url = source.urlTemplate
    // Substitute subdomain (pick random from list for load balancing)
    if (source.subdomains != null) {
        val subs = source.subdomains.split(",")
        url = url.replace("{s}", subs.random())
    }
    // Substitute API key
    if (source.requiresKey && source.apiKey != null) {
        url = url.replace("{key}", source.apiKey)
    }
    // Substitute retina
    url = url.replace("{r}", if (isHighDpi) "@2x" else "")
    return url
}
```

---

## 6. TILE SOURCE SELECTION UI

### Planning Map settings → Map Source:

```
┌──────────────────────────────────────┐
│ MAP SOURCE                           │
│                                      │
│ ► SATELLITE                          │
│   ● Google Hybrid          [active]  │
│   ○ Google Satellite                 │
│   ○ Esri World Imagery              │
│                                      │
│ ► TOPOGRAPHIC                        │
│   ○ OpenTopoMap                      │
│   ○ USGS Topo                        │
│   ○ Esri World Topo                  │
│   ○ Google Terrain                   │
│                                      │
│ ► ROAD                               │
│   ○ OpenStreetMap                    │
│   ○ Google Roads                     │
│   ○ CartoDB Voyager                  │
│                                      │
│ ► CUSTOM SOURCES                     │
│   + Add custom source                │
│                                      │
│ ► OVERLAYS                           │
│   □ Google Roads Overlay             │
│   □ Esri Transportation             │
│                                      │
│ [MANAGE API KEYS]                    │
└──────────────────────────────────────┘
```

Radio buttons for base layers (one active at a time). Checkboxes for overlays (zero or more). Custom source entry: name, type, URL template, API key. Manage API Keys screen for sources requiring keys.

---

## 7. OFFLINE TILE CACHING

### How downloaded tiles work with any source:

When online: Leaflet requests tile at URL. Interceptor caches the response. Tile renders.

When offline: Leaflet requests same URL. Interceptor matches the URL pattern, serves from local cache. Tile renders.

The interceptor doesn't care which source the tile came from — it matches the URL and serves the cached file. This means ANY source the user selects works offline as long as tiles were cached while online or pre-downloaded.

### Tile download interaction with source switching:

If user downloads an area with Google Hybrid, those tiles are cached for the Google Hybrid URL pattern. If they switch to USGS Topo, the USGS tiles for that area are NOT cached — they need to download again with the new source. Each source's tiles are cached independently.

The ride_areas table tracks which source was used for tile downloads via the tile URL pattern. The "tiles downloaded" blue overlay is per-source — switching sources may show areas without coverage.

---

## 8. KEY OBSERVATIONS FOR GROUPTRACK

1. **Google tiles are the best free option for off-road** — high-res satellite, excellent hybrid with roads, terrain mode, and road overlay. All free, no key, zoom to 20. The risk is they're undocumented URLs.

2. **Esri legacy still works** — degrading slowly but cached tiles are fine indefinitely. The new Esri basemaps require a free API key (2M tiles/month free tier).

3. **USGS is authoritative for US topo** — free, no key, but only US coverage and max zoom 16.

4. **OpenTopoMap is the best free worldwide topo** — but rate limited and max zoom 17.

5. **Thunderforest Outdoors is the best trail-specific map** — requires free key, 150K tiles/month. Worth recommending to serious riders.

6. **The architecture handles everything** — any XYZ tile source works. User adds custom sources. Keys stored locally. Source switching is a URL swap. Offline caching works for any source.

7. **{y}/{x} vs {x}/{y} order matters** — Esri and USGS use `{z}/{y}/{x}`. Google and OSM use `{z}/{x}/{y}`. The tile_sources table stores the URL template exactly as the provider expects it. Leaflet handles both patterns.

---

*GroupTrack | Map Tile Source Reference v1 | May 9, 2026*
