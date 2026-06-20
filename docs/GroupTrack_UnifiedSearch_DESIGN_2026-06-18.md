# GroupTrack — Unified Search (FAB) Design Document
**2026-06-18 · checklist item [2h.1] · build target: next session**
Supersedes the design portion of `GroupTrack_Search_Consolidation_Handoff_2026-06-16.md`
(keeps that doc's engine inventory; corrects the convoy area-search assumption — see §3).

---

## 1. Goal

Replace all standalone search chrome on **both** maps with ONE draggable
magnifying-glass search beacon (FAB). Reclaim map real estate. The FAB is the
single new launch point; it routes to **existing** query engines — this is
wiring + deletion, NOT new search logic.

Net effect:
- **Planning:** beacon replaces its area-search field AND its artifact-search box.
- **Convoy:** beacon replaces its artifact-search box AND **adds** an area-search
  positioner convoy never had (the thing that made field-testing UT trails from
  NH impossible — you could not recenter the convoy map to Utah).

---

## 2. Architecture decision — self-contained shared component (CONFIRMED)

Search lives in **one new file**, `UnifiedSearch.kt`, NOT inlined into
`ConvoyScreen.kt` or `ConvoyMapViewerScreen.kt`. Both screens mount it.

**Why (not busywork):** every bug fought on 2026-06-18 was the same disease —
behavior duplicated across convoy/planning and drifting (`saveConvoyState` vs
`savePlanningState`; two `onDismiss` blocks patched twice; two map HTMLs with
duplicate `setView`/`showSearchCenter`). Search is a large feature wanted on BOTH
maps; duplicating it guarantees the worst case of that drift. One shared component
= one fix-site, drift impossible.

**The key primitive:** a `mapContext` parameter. Same component on both maps;
behavior is routed by which map context is passed in. The component owns what is
genuinely identical (FAB, search bar, the 5 chips, results rendering, engine
calls); it parameterizes only what legitimately differs (mapContext, webView,
the detail-open callback).

**Guardrail:** do NOT over-abstract. If per-map special-case flags start
multiplying, the abstraction is wrong. Today the two maps want the IDENTICAL
search — so the shared component is clean. Keep it that way.

### Proposed signature

```
@Composable
fun UnifiedSearch(
    mapContext: String,            // "convoy" | "planning" — routes behavior
    webView: WebView?,             // the map this instance controls
    context: Context,              // for Geocoder + SpatialDbManager.init
    onOpenDetail: (type: String, id: String) -> Unit  // artifact-select action,
                                                       // supplied per screen
)
```

The component internally owns: FAB state + drag, the search bar (5 chips + text),
Enter-to-execute, the results list, and per-mode routing.

### Reuse note — lift, don't duplicate
`ConvoyArtifactsPanel.kt` already contains the two pieces we want to share, as
private composables:
- `SearchBlock` (the type-select + term UI) — ConvoyArtifactsPanel.kt:192
- `ResultsList` (renders `ArtifactResult` rows; tap → onResultClick) — :284
- model `ArtifactResult` (id, type, geomHash, name)

Plan: **lift `ResultsList` + `ArtifactResult` (and optionally `SearchBlock`)
out of ConvoyArtifactsPanel.kt into the shared search file** (or a shared
ui-components file both import). ConvoyArtifactsPanel then drops its search
(see §4 removals). This is the "self-contained, shareable" structure.

---

## 3. The flow (approved via mockup 2026-06-18)

1. Tap beacon (magnifying glass; draggable; **drag position resets per session**
   — no JSON persistence).
2. Search bar opens: **5 type chips on ONE line**, order:
   **Area · Track · Route · Trail · Waypoint**, plus a text field.
3. Keyboard **Return executes the search AND closes the search bar.**
4. Results render in the shared results list (same display + selector for all
   modes). Both Area and artifact searches may return MULTIPLE matches → list
   selection required for both.
5. **Selecting a row closes the list**, then:
   - **Area row** → `setView(lat,lon,zoom)` + `showSearchCenter(lat,lon)` —
     recenter map. Done.
   - **Artifact row** → open **ArtifactDetailPanel** (the hub; FIT + other
     artifact actions live there).

Duplicate artifact names get per-name sequence numbering (#1, #2 …) via the
existing `assignNameSequence` (names aren't unique; rows keyed by geom_hash).

---

## 4. Engines to KEEP (reuse) · Launch points to REMOVE

### KEEP — engines (already exist, callable; the FAB calls these)
- **Artifact name search:** `SpatialDbManager.searchByName(type, term)` →
  `assignNameSequence(raw)` → `List` of results.
  (convoy host ConvoyScreen.kt:1356–1362; planning ConvoyMapViewerScreen.kt:870–874)
- **Area geocode:** `Geocoder(context).getFromLocationName(term, 5)` →
  `setView(lat,lon,13)` + `showSearchCenter(lat,lon)`.
  (planning has the full pattern: ConvoyMapViewerScreen.kt:365–388)
- **Detail panel:** `ArtifactDetailPanel` — the artifact-select destination.
- Both map HTMLs already define `setView` AND `showSearchCenter`
  (convoy_map.html:138/154, grouptrack_map.html:625/643) — **no HTML change
  needed for search.** (They ARE duplicated → see §5 deferred JS dedupe.)

### REMOVE — launch points (replaced by the FAB)
1. **Convoy dead area-search vars** — ConvoyScreen.kt:268–270
   (`locationSearchQuery/Results/Error`). Grep shows ONLY declarations, no
   UI/reads/writes → dead code. Delete the 3 lines.
2. **Planning area-search field** — ConvoyMapViewerScreen.kt: `searchText` (195)
   + the `BasicTextField` + its row/▲ button + geocode handler (~345–405+) +
   "Search area... (press Enter)" placeholder (401–402). Remove the UI; the
   geocode logic relocates INTO the FAB's Area mode.
3. **Artifact-search box (both maps)** — `SearchBlock` + `ResultsList` mounts in
   ConvoyArtifactsPanel.kt:138–139, and params `onSearch`/`onResultClick`/
   `searchResults` (61–63). Remove the search box from the panel; KEEP the
   panel's other jobs (type toggles via `onSetState`, `onCreateRoute`, import).
   `ResultsList`/`ArtifactResult` get lifted to the shared file (§2), not deleted.

---

## 5. Explicitly DEFERRED — separate checklist items (DO NOT COMBINE)

- **Map-popup → detail bridge** (the "second entrance"): tapping an on-map
  artifact opens ArtifactDetailPanel. Needs a JS click handler on map popups +
  an Android JS-interface method to open detail with (type, id), in BOTH map
  HTMLs. Its own item.
- **Shared-JS consolidation:** dedupe `setView`/`showSearchCenter` (and other
  shared map fns) out of the two HTMLs into a shared local JS file. Precedent
  exists — both HTMLs already `<script src="leaflet.polylineDecorator.js">`.
  Its own item. NOT a blocker for search (both HTMLs already have the fns).

---

## 6. Build sequence (incremental — test between steps)

Per the day's lesson (don't build many changes + test only at the end):

1. **Create `UnifiedSearch.kt`** (FAB + bar + results + routing; lift
   ResultsList/ArtifactResult into it). Mount on **convoy only**, leaving the
   old convoy search in place. Build → test all 5 modes + routing on convoy.
2. **Mount on planning.** Build → test on planning.
3. **Remove** the three old launch points (§4). Build → test both maps still
   search via the FAB and the old chrome is gone.

Commit after each green step.

---

## 7. Open questions resolved

- Convoy scope → **all 5 modes**, Area included (B). Area on convoy is NEW
  capability (place-positioner it lacked).
- FAB drag persistence → **reset per session.**
- Default mode → **Area** (first chip).
- Chip order → **Area · Track · Route · Trail · Waypoint** (one line).
- On Return → **close the search bar.** On row select → **close the list.**
- Results display → **shared list, common to all modes**; only the on-select
  action differs (Area recenters / artifact opens detail).

---

## 8. Risk notes

- Lifting `ResultsList`/`ArtifactResult` out of ConvoyArtifactsPanel.kt: make
  sure nothing else references them privately; update ConvoyArtifactsPanel's
  imports.
- `searchByName` requires `SpatialDbManager.init(context)` before the query
  (see convoy 1358) — the shared component must init on the IO dispatcher too.
- Area geocode is network/IO + may return 0 results — keep the "not found, try
  adding state (e.g. Zion UT)" toast behavior from planning 386–388.
- mapContext must reach the correct webView; convoy passes `webViewRef.value`,
  planning passes `webViewRef` (different types — convoy wraps in MutableState).
  The component takes a plain `WebView?`; each screen unwraps before passing.
