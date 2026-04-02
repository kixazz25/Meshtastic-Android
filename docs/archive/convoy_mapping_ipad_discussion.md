# CONVOY — Mapping Strategy & iPad Feasibility
## Discussion Document

**Date:** March 14, 2026 | **Author:** Fred Kix | **Status:** Draft for Review

---

## 1. Current Mapping State (V2)

The current V2 convoy map runs on a WebView/Leaflet layer embedded in the Android app via `convoy_map.html`. Key characteristics:

- Leaflet.js renders the map in a WebView (`ConvoyMarkerRenderer`, `ConvoyViewModel.persistentWebView`)
- Tile sources are runtime-switchable: Satellite (ArcGIS), Hybrid (Google), Topo (OpenTopo), Road (OSM)
- MapLibre was identified as the production offline engine — not yet wired
- Map persists for the entire session via `persistentWebView` — never destroyed
- Node markers, convoy track segments, and the HUD overlay float above the map
- Track coloring rule: each segment colored by the last cart to pass it; black behind the tail

**Known gaps in current implementation:**
- No offline tile download or management
- MapLibre not yet substituted for Leaflet
- No map area selector for ride creation
- No KML boundary or route overlay
- Tile source switching works via JS bridge but is not persistent across sessions

---

## 2. ATAK/iTAK as Design Pattern (NOT Integration)

The roadmap explicitly states: **TAK Server excluded. ATAK/iTAK used as design pattern only.**

This is the right call. Here is what ATAK does well that we should replicate as design patterns:

### 2.1 What ATAK Gets Right

**Cursor on Target (CoT) data model**
ATAK transmits position, heading, speed, and status as structured CoT XML events. Our `ConvoyNode` model already captures equivalent fields (lat, lng, speed_mph, heading_deg, battery_pct, status). The pattern to adopt: treat every node position update as an event with a timestamp, not a live stream.

**Map-centric UI**
ATAK puts the map first — all controls float above it. We've adopted this with our transparent HUD + ConvoyButtonBar approach. The pattern is correct: map is always full screen, UI is always overlay.

**Track colors and trail rules**
ATAK uses distinct colors per unit with trails fading by age. Our rule (segment color = last cart to pass) is a cleaner and more informative variant of this. Keep our rule.

**Zoom-linked detail**
ATAK changes what's displayed based on zoom level — labels appear at certain zoom thresholds, icons change size. This is a V3 target for us: at GROUP zoom show all nodes with callsigns, at MY CART zoom show only adjacent nodes with full telemetry.

**Offline-first**
ATAK caches map tiles aggressively and operates entirely offline. This is our V3 must-have via MapLibre.

### 2.2 What ATAK Does That We Explicitly Don't Need

- TAK Server (centralized relay) — Meshtastic mesh replaces this
- CoT XML over multicast UDP — Meshtastic protobuf replaces this
- ATAK plugins and extensions — out of scope
- Military symbology (MIL-STD-2525) — not applicable
- Certificate-based authentication — not applicable

---

## 3. Open Source Mapping Approach for V3

### 3.1 Recommended Stack

**Map Engine: MapLibre GL (Android + iOS)**
MapLibre is the open-source fork of Mapbox GL JS/Native. It is already referenced in the Meshtastic codebase. Key advantages:
- True offline tile support with MBTiles
- Vector tile rendering (sharp at all zoom levels)
- Available for Android (MapLibre Native Android) and iOS (MapLibre Native iOS)
- No API key required, no usage fees
- Style specification compatible with Mapbox styles

**Tile Source for Offline: OpenMapTiles or PMTiles**
- Download region tiles in MBTiles format for offline use
- PMTiles is a newer single-file format that can be served from local storage
- OpenStreetMap-based, freely distributable

**Route/Boundary Files: GeoJSON + KML**
- KML for route import (riders may provide GPX/KML from trail apps)
- GeoJSON for internal representation
- Convert KML → GeoJSON on load

### 3.2 Implementation Plan for V3 Map

**Phase 1 — Replace Leaflet with MapLibre in convoy_map.html**
- Swap Leaflet tile layer for MapLibre GL JS
- Keep existing JS bridge interface unchanged (addMarker, setView, drawTrack, fitBounds)
- Test with online tile sources first

**Phase 2 — Offline Tile Download**
- Add map area selector to Create Ride screen
- On ride creation, trigger tile pre-fetch for selected area at zoom levels 14-18
- Store as MBTiles in app external storage
- MapLibre loads from local MBTiles when network unavailable

**Phase 3 — KML Route Overlay**
- Parse KML on Create Ride screen
- Render route polyline on map during ride
- Snap node positions to route for off-track detection

**Phase 4 — Zoom-Linked Display**
- GROUP button → fitBounds all nodes, zoom 12-14, show callsign labels
- MY CART button → zoom 16-18, show only ±2 adjacent nodes, full telemetry
- Node tap → zoom 17, center on node, show selected panel

### 3.3 Create Ride Map Selector

The Create Ride screen needs a map area selector before offline tile download can work:

**UI flow:**
1. Rider selects region on embedded MapLibre map
2. Draws bounding box or imports KML boundary
3. App shows estimated tile count and storage size
4. Rider confirms download
5. Tiles download in background, progress shown
6. Ride cannot be pushed to radios until tile download complete (or rider opts for online-only)

**Estimated storage:** At zoom 16-18 for a 50km² area ≈ 200-500MB. This must be communicated clearly to the rider before download.

---

## 4. iPad / iOS Feasibility Analysis

### 4.1 Meshtastic iOS Current State

Meshtastic has an official iOS app: **Meshtastic Apple** (github.com/meshtastic/Meshtastic-Apple). Key facts:
- Written in Swift + SwiftUI
- Supports iPhone and iPad (universal app)
- Uses MapKit for the map (Apple Maps, not Leaflet or MapLibre)
- BLE connectivity to Meshtastic radios
- Active development, regularly updated
- Open source, Apache 2.0 license

The Meshtastic Apple app already displays node positions on a map, shows mesh topology, and supports basic group messaging. It is NOT a fork of the Android app — it is a completely separate codebase in Swift.

### 4.2 Feasibility of Convoy Fork on iPad

**Option A — Fork Meshtastic Apple (Swift/SwiftUI)**

Feasibility: **HIGH** — but requires Swift development skills.

The Meshtastic Apple repo is well-structured and the map, BLE, and node data layers are all present. A convoy fork would:
- Add ConvoyScreen equivalent in SwiftUI
- Add the HUD overlay (GROUP/MY CART panels) above MapKit
- Add ConvoyNode model mirroring the Android version
- Add ConvoyButtonBar at the bottom
- Add convoy-specific channel/config management

Advantages: Native iPad UI, MapKit offline (Apple Maps caches tiles), AirDrop for config transfer, iPad screen real estate is ideal for the GROUP map view.

Challenges: Separate codebase means double maintenance. Swift/SwiftUI required. MapKit is not MapLibre — offline tiles work differently.

**Option B — React Native or Flutter Cross-Platform**

Feasibility: **LOW** — explicitly excluded from roadmap. React Native and Flutter are excluded per V2 technical decisions. Do not pursue.

**Option C — WebView-Based Hybrid on iPad**

Feasibility: **MEDIUM** — use the existing `convoy_map.html` WebView approach inside a Swift WKWebView container.

A minimal Swift app could:
- Wrap `convoy_map.html` in a WKWebView
- Use the same Leaflet/MapLibre JS
- Connect to Meshtastic radio via BLE using Meshtastic Apple's BLE layer
- Display the convoy HUD via the existing HTML/CSS/JS

Advantages: Reuses all existing convoy_map.html work. Minimal Swift code required. Could ship faster.

Challenges: WKWebView on iOS has stricter security than Android WebView. Local file access requires special handling. BLE integration still requires Swift code.

**Option D — Use Meshtastic Apple As-Is, Add Convoy Channel Support**

Feasibility: **HIGH for tracking display, LOW for full convoy features**

The existing Meshtastic Apple app already shows all nodes on a map. If convoy nodes use standard Meshtastic position broadcasting, riders with iPhones/iPads running the unmodified Meshtastic app can see all convoy nodes on the map already.

What's missing: convoy-specific HUD (GROUP/MY CART panels), convoy channel auto-config, the button bar, telemetry overlay. But for a viewer-only iPad display showing all convoy positions — it works today with zero code changes.

### 4.3 Recommended iPad Strategy

**Short term (V2/V3):** Use Option D — standard Meshtastic Apple as a display-only viewer for convoy positions. Zero development cost.

**Medium term (V3):** Pursue Option C — WKWebView hybrid. Reuse convoy_map.html, add minimal Swift BLE bridge. Estimated effort: 2-3 weeks for a functional iPad display app.

**Long term (V4):** Pursue Option A — full Swift fork of Meshtastic Apple with complete convoy HUD. Requires dedicated iOS developer or MacStadium build environment.

### 4.4 Build Environment for iOS

Per roadmap, iOS build is planned via MacStadium or MacInCloud. Requirements:
- Mac with Xcode 15+
- Apple Developer account ($99/year) for device deployment
- MacStadium cloud Mac: ~$99/month for M1 Mac Mini
- MacInCloud: pay-per-hour alternative
- TestFlight for beta distribution

---

## 5. Priority Recommendations

### Immediate (V2 — in progress)
1. Fix MapLibre tile URL switching persistence
2. Add map area selector to Create Ride screen (bounding box only, no KML yet)
3. Wire offline tile download trigger on ride creation

### V3 Planning
1. Replace Leaflet with MapLibre GL in convoy_map.html
2. Implement MBTiles offline storage and loading
3. Implement KML route import and overlay
4. Implement zoom-linked display behavior
5. Begin iPad Option C (WKWebView hybrid) prototype

### V4 Planning
1. Full Swift fork of Meshtastic Apple
2. Native SwiftUI convoy HUD
3. Cross-platform config sync (Android ↔ iPad)

---

## 6. Open Questions for Discussion

1. What is the target offline area size for a typical ride? (determines storage requirements)
2. Should tile download happen at ride creation (organizer) or at app install (all riders)?
3. Is iPad primarily for organizer use (command view) or all riders?
4. What KML sources will riders use? (Gaia GPS, Trailforks, custom?)
5. Should the iPad app be a separate app or the same app ID as Android?
6. What is the acceptable minimum iOS version? (affects BLE API availability)

---

*Document prepared for discussion. Review and annotate with corrections, additions, and priority decisions.*
