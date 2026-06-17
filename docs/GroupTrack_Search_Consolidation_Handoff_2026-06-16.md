# GroupTrack Search Consolidation — Handoff Doc
**2026-06-16 · for the unified search FAB ([2h.1], AFTER FIT)**

## WHY
Free up map real estate. Both maps carry permanent/standalone search chrome. Consolidate ALL search into ONE draggable **magnifying-glass FAB** (icon decided 06-16: magnifying glass, NOT binocular — universal "search" convention, legible at FAB size) present on BOTH maps. The FAB is the single new launch point; it routes to existing (kept) query logic.

---

## CURRENT STATE — what exists now (REMOVE the launch points, KEEP the engines)

### 1. AREA search — PLANNING map
- **File/launch point:** `ConvoyMapViewerScreen.kt` ~365–405 — inline search-text field, placeholder "Search area... (press Enter)" (line 401).
- **Executes:** `Geocoder(context).getFromLocationName(searchText, 5)` (line 371) → `setView(lat, lon, 13)` (line 376). Geocode a place name → recenter map.
- **REMOVE:** the text field + its geocode/Enter handler.
- **KEEP (reuse in FAB Area mode):** the `Geocoder...getFromLocationName → setView` logic.

### 2. LOCATION/AREA search — CONVOY map (separate from planning's!)
- **File/launch point:** `ConvoyScreen.kt` — `locationSearchQuery` / `locationSearchResults` / `locationSearchError` state (lines 267–269) + its UI.
- **Executes:** location/address lookup → setView (convoy's own area-search; convoy is NOT area-search-free as earlier assumed).
- **REMOVE:** this location-search UI + state.
- **NOTE:** This means BOTH maps currently have their OWN area search. The FAB unifies them into one Area mode.

### 3. ARTIFACT search — Work-with-Artifacts (BOTH maps)
- **File/launch point:** `ConvoyScreen.kt` 1355–1361 — `onSearch = { type, term -> ... }` → `SpatialDbManager.searchByName(type, term)` (1359) → `assignNameSequence(raw)` (1361). (Hosted via ArtifactListPanel's onSearch callback.)
- **Executes:** name search of an artifact type → numbered result rows.
- **REMOVE:** the standalone search box / onSearch UI entry inside Work-with-Artifacts.
- **KEEP (reuse in FAB artifact mode):**
  - `SpatialDbManager.searchByName(type, term, limit=200)` (SpatialDbManager:351) — the query engine. Committed `4f7abbbb7`, NON-SPATIAL (name match only).
  - `ArtifactSearch.kt` / `assignNameSequence()` — per-name stable sequence numbering (names aren't unique; rows keyed by geom_hash, shown as per-name sequence #). Pure, no DB/Compose. Rules R1–R4 in file header — DON'T break the `ORDER BY name COLLATE NOCASE, geom_hash` ↔ numbering agreement.

---

## KEPT / REUSED (the FAB plugs into these — do NOT remove)
- `SpatialDbManager.searchByName(type, term)` — artifact name query
- `ArtifactSearch.assignNameSequence()` — result numbering
- `Geocoder.getFromLocationName → setView` — area lookup
- **Detail panel** (ArtifactListPanel detail view) — the hub
- **FIT** — `ConvoyArtifactOps.fit(context, webView, type, id)` (implemented 06-16) — reached via detail panel's FIT button (onFit wired)

---

## NEW DESIGN — the magnifying-glass search FAB

### The FAB
- **Icon:** magnifying glass (decided).
- **Draggable** — repositionable on the map surface; parks out of the way. (Open Q: persist drag position per-map in panel JSON? or reset per session.)
- Present on **BOTH** maps (planning + convoy).
- Replaces: planning area-search (#1), convoy location-search (#2), artifact-search box (#3). Net: map surface reclaims that permanent chrome.

### Launch → unified search panel
- **Selector:** Area / Trail / Track / Route / Waypoint
- **Text box:** the query term
- **AREA mode** → geocode (reuse #1 logic) → `setView` the map. Available on BOTH maps now.
- **ARTIFACT mode** (Trail/Track/Route/Waypoint) → `searchByName(type, term)` → `assignNameSequence` → **results list** → tap a result → **detail panel** → **FIT** button (works now).

### Second entrance to detail (the other selection gesture)
- **Tappable map-popup descriptor** = "start here with THIS artifact." Tapping an artifact's on-map popup/callout opens the SAME detail panel.
- So detail panel = the convergence hub, reached TWO ways:
  - **FAB search** = "find by name" (deliberate lookup)
  - **map popup** = "this one I'm looking at" (spatial/visual pick)
- Both land on detail → FIT. One funnel, one FIT path, two entrances.
- **Wiring note:** the map-popup → detail requires the convoy/planning map HTML's popup to have a click handler that bridges back to Kotlin (via the Android JS interface) to open the detail panel. New JS↔Kotlin bridge call needed.

---

## OPEN QUESTIONS
- **Q-A: Drag-position persistence** — save FAB position per-map (panel JSON), or reset each session?
- **Q-B: Convoy scope** — does the FAB on convoy offer all 5 selector options, or restrict (convoy = live/proximity map; memory had "convoy Area-only" but convoy currently HAS artifact search too via onSearch — so full artifact search on convoy is already present and the FAB can keep it). Decide intended convoy scope.
- **Q-C: Map-popup → detail bridge** — new JS click handler on popups + Android interface method to open detail with (type, id). Both map HTMLs (convoy_map.html, grouptrack_map.html) — they drift, so two edits.
- **Q-D: Result-list → detail → FIT** on planning — FIT currently writes "convoy" JSON; for planning FIT it'd write "planning" + drawPersistedState("planning"). fit() may need a mapKey param when wired from planning. (For now fit() is convoy-hardcoded.)

---

## SEQUENCING
This is [2h.1], AFTER FIT (which lands 06-16). FAB's artifact mode → detail → FIT depends on FIT working. Build order when picked up: (1) the FAB + unified search panel (reusing searchByName + geocode), (2) remove the 3 old search launch points, (3) the map-popup→detail bridge. Test each on device.
