# GroupTrack — Full-Filter-Persistence Model Change (DESIGN SPEC)
**Drafted:** 2026-06-16 · **Status:** design, not yet built · **Applies to:** convoy + planning (shared)

---

## THE ROOT CAUSE THIS FIXES
The current design persists the filter **only for SELECTED** state and **recreates ALL/OFF from a live bbox query** at panel-open / draw. The premise was "ALL/OFF are cheap to recreate." That premise is false in practice because the recreate depends on a viewport query whose bbox can be stale/wrong (GPS point, debounce lag, lastViewport drift). Every persistence bug today (empty select list, clobbered selection, 165-trail, missing tracks) traces to **recreating** display state via query instead of **restoring** it from saved data.

**Principle (Fred, locked):** One filter, three states. The filter holds **every row with its display state**, for ALL / OFF / SELECTED alike. The filter is the persisted source of truth and **drives the draw** — the query's only job is to CREATE rows the first time (when no saved filter exists for that type+frame), never to re-derive display state on restore.

**Restore, don't recreate.** The persisted JSON fully defines the display. Recreate nothing from a query at restore time.

---

## CURRENT MODEL (what exists, to change)
Two representations of display state, split:
1. `artifactList` + `selectedArtifactIds` (ConvoyScreen 216-217) — LIVE panel working set; only valid while a panel is open; transient.
2. `trailCheckedIds` / `trackCheckedIds` / ... (219-222) — PERSISTENT per-type checked-id SET, loaded from JSON via `MapStateStore.checkedIdsFor`.

JSON shape today: `{artifacts:{<Type>:{state:Int, rows:[{id,name,checked}]}}, bbox?, panel}`. state 0=OFF, 1=ALL, 2=SELECTED.

**Save (`rowsFor`, ConvoyScreen 227):** `if (activeListType != type) return emptyList()` → only the open type saves rows; rows come from `artifactList` + `selectedArtifactIds`. → CLOBBER: switching panels wipes other types' rows; non-open types save empty.

**Restore + Draw (ConvoyScreen ~765):** re-queries `queryTrailsByViewport(bbox)` then re-filters to `capTrailIds` for SELECTED; for ALL draws all query results; OFF draws nothing. → display state RE-DERIVED from query every time.

**onEditDisplay (1409):** queries by live bounds, then `selectedArtifactIds = when{ SELECTED→curChecked; ALL→all-in-query; else→empty }` → recreates ALL/OFF, restores only SELECTED.

---

## TARGET MODEL (full filter persistence)

### Data model
Persist, per type, the **complete filter**: the row set AND each row's display flag, for whatever state the type is in. The persisted filter is authoritative.

- A type's persisted record = `{ state, rows:[{id, name, display:Bool}] }` for ALL three states (not just SELECTED).
  - **ALL:** rows = all artifacts the user has in the filter, each `display=true`.
  - **OFF:** rows = same set, each `display=false` (OR state=OFF short-circuits draw; see open question Q2).
  - **SELECTED:** rows = the set, `display` per the user's individual choices.
- `MapStateStore.Row` already has `(id, name, checked)` — reuse `checked` as the per-row display flag. No schema change to Row needed; the change is WHICH rows get saved (all, not just active/selected) and that the draw READS them.

### Save (`saveState(mapKey)`)
- Remove the `activeListType` gate. Save rows for **every** type, every time.
- Rows source = the type's **persistent filter** (not the transient `artifactList`/`selectedArtifactIds` that's only valid for the open panel). This requires the persistent representation to hold the full row set per type (see Migration below), not just a checked-id set.
- Write bbox (the current frame) too — needed so restore can re-query ONLY when the filter is empty for a newly-entered frame.

### Restore + Draw (`recreateState(mapKey, webView)`)
- Read the JSON for `mapKey`. For each type:
  - If the type has a persisted filter (rows present) → **draw from the rows**: fetch geometry for the `display=true` row ids (query-BY-ID, not by-bbox), draw them. NO bbox query for display state.
  - If the type has NO persisted rows yet (first entry to a frame) → query by bbox ONCE to CREATE the rows, apply state default (ALL→all display, OFF→none, SELECTED→none until user picks), persist them.
- The draw iterates persisted rows; it does not re-derive from a bbox query.

### Geometry: query-BY-ID for restore
- Add `SpatialDbManager.queryTrailsByIds(ids)` / tracks / waypoints / routes — `SELECT ... WHERE <id_col> IN (...)`. (Check if exists; earlier grep was pending.)
- Restore fetches geometry for the persisted display=true ids by id — immune to stale bbox. This is the "restore not recreate" mechanism.

### onEditDisplay
- Populate the panel from the **persisted rows** for that type (not a fresh bbox query) when they exist.
- Only query-by-bbox to ADD newly-in-frame artifacts the user could newly include (the "what else is here" case) — and merge per [3.1c] reconcile rules, never overwriting saved display flags.

---

## SHARED (consolidation — both maps)
- `saveState(mapKey)` and `recreateState(mapKey, webView)` become **shared functions keyed only by mapKey** (open `<mapKey>_panel.json`). The persisted JSON is the entire contract. ConvoyScreen and ConvoyMapViewerScreen each call the same two functions with their mapKey — no duplicated/drifted per-map copies.
- This is the documented "shared code / fix once fixes both" design, finally realized — by making the functions JSON-driven (mapKey in, JSON is the state) rather than closure-bound to composable locals.
- ⚠️ Planning persistence is COMMITTED + working (`0082b032f`). This change touches the shared store/draw that planning rides. Do it in small tested steps with planning revert anchor `0082b032f`. If planning destabilizes, revert.

---

## OPEN QUESTIONS (resolve before/while building)
- **Q1 — JSON size for ALL:** ALL-state persists every in-frame row (can be 500+ at wide zoom; query limit was 500). Acceptable? Options: (a) accept larger JSON; (b) for ALL, store state=ALL flag + the row set but cap; (c) ALL stays a flag that means "all currently-in-filter rows display." Decide.
- **Q2 — OFF representation:** OFF = rows with display=false, OR state=OFF short-circuits the draw and rows are retained for when you flip back ON? (Retaining rows lets OFF→ON restore the same set without re-query.)
- **Q3 — "filter" lifespan vs frame:** when the user pans to a NEW frame, is the filter for the old frame discarded, merged, or kept? Convoy = session-only; planning = persisted across launches. The frame/filter relationship differs per map — confirm the rule per [3.1c] (bbox change reconcile: NEW∩JSON keep, new→no, gone→DELETE).
- **Q4 — query-by-id existence:** confirm SpatialDbManager has or needs `queryXByIds`. (grep was pending.)
- **Q5 — migration:** the persistent representation changes from "checked-id SET per type" to "full row set per type." `checkedIdsFor` and the `*CheckedIds` vars get replaced/augmented by a full per-type row list. Plan the in-memory state change (likely a `Map<String, List<Row>>` per type or similar).

---

## ⭐⭐ DISCOVERED DRAW CONTRACT (from real code, ConvoyScreen 751-815) — THE BUILD TARGET
The draw is currently the BODY of `onViewportChanged` (the JS bridge callback), NOT a callable function — which is why restore depends on that event firing. Four identical per-type blocks (Trails/Tracks/Waypoints/Routes). Each block:

```
INPUTS:  bbox(s,w,n,e) [currently onViewportChanged params] · zoom→limit(500/2000) · state(OFF/ALL/SELECTED) · selectList(capXIds)
LOGIC:   1. ELIGIBILITY: raw = if (state != OFF) queryXByViewport(s,w,n,e,limit) else emptyList()
         2. SELECT/OMIT: results = if (state == SELECTED) raw.filter { it["X_id"] in capXIds } else raw
         3. RENDER:      json = buildXGeoJson(results); MAIN-THREAD: updateX(json); if (state != OFF) showX()
                         (Trails also clearTrails() first — diagnostic add, can drop)
OUTPUT:  updateX(json) + showX()/implicit-hide via state
```
Per-type specifics: Trails limit 500/2000-by-zoom + `z>=8` gate; Tracks limit 200/50-by-z12; Waypoints/Routes limit. id keys: trail_id/track_id/waypoint_id/route_id. Builders: buildTrailGeoJson/buildTrackGeoJson/buildWaypointGeoJson/buildRouteGeoJson. JS: updateTrails/Tracks/Waypoints/Routes + showTrails/etc + clearTrails.

**ALSO tangled in the same callback (737-748): the RESEED GATE** — `if (lastMapProcessed != "convoy") { read JSON → set state + checkedIds }`. This is RESTORE logic mixed into the viewport callback. Separate it.

### THE EXTRACTION (the 4-hour build, Fred's estimate)
1. **`drawArtifacts(bbox, perTypeState, perTypeIds, webView)`** — extract 751-815 into a standalone parameterized function. The pure draw: takes bbox + per-type state + per-type select-lists → eligibility→select/omit→render, all 4 types. No JSON reading, no viewport coupling. THE SHARED CORE.
2. **`drawPersistedState(mapKey, webView)`** — read `<mapKey>_panel.json` (state, ids, bbox) → call `drawArtifacts` with them. = RESTORE. (the "recovery draw")
3. **`savePersistedState(mapKey)`** — write state + select-lists (ALL types, drop activeListType clobber gate) + bbox. (bbox currently null — REQUIRED add so drawPersistedState has a frame.)
4. **`onViewportChanged`** — calls `drawArtifacts` with live frame params + current state. = LIVE navigation. (same core, live source)
5. **FIT** = build the FIT JSON (artifact bbox; fitted type SELECTED with in-bbox list, only-fitted checked; other types OFF) → call `drawPersistedState`. Snaps in for free.
6. **Convoy-first, prove, then planning** calls the same `drawArtifacts`/`drawPersistedState`/`savePersistedState`. Both maps share the core; per-map copies deleted.

**Signature locked:** `drawArtifacts(bbox: BBox, states: Map<String,Int>, selectLists: Map<String,Set<String>?>, webView: WebView)`. Same draw, fed from two sources (live params vs JSON). Restore feeds from JSON; viewport feeds from live. FIT writes JSON then restores. Estimated ~4 hrs to build the common function + snap into existing restore.

---

## FIT (the GOAL) = WRITE A SPECIFIC JSON + CALL drawPersistedState
Once `drawPersistedState(mapKey, webView)` exists, FIT is just writing the right JSON and calling it. FIT's JSON content (Fred, hardened 06-16):
1. **Create a bounding box for the artifact to fit in** — compute bbox from the artifact's geometry (its min/max lat/lon spatial-row fields). This becomes the JSON bbox (the frame).
2. **Turn ALL OTHER artifact types OFF** — fit a trail → tracks/waypoints/routes all set to OFF state in the JSON.
3. **Create a select filter list for the fitted type in that bbox** — query that type within the new bbox → that's the select-list (all of that type in the artifact's frame).
4. **Select ONLY the working artifact to display** — in that select-list, only the fitted artifact is checked:true; all other in-bbox results checked:false (present, toggleable to COMBINE for route-building).
5. The fitted type's state = SELECTED.
Then **call `drawPersistedState`** → map frames to the artifact's bbox, displays only the fitted artifact, neighbors present-but-off and toggleable.

So FIT writes: `{ <fittedType>:{state:SELECTED, rows:[all-in-bbox, only-fitted checked:true]}, <otherTypes>:{state:OFF}, bbox:<artifact bbox> }` then calls the shared draw. No FIT-specific draw logic — FIT is a JSON write + the standard recovery draw. THIS is why the persistence model change makes FIT nearly free.

---

## ⭐ CORE FRAMING (Fred, locked 06-16) — THE WHOLE POINT IN ONE LINE
Today there are TWO complex hand-coded persistence/draw functions (convoy's and planning's). **Replace both with ONE parameterized function that reads a JSON and executes a persistent-state draw.** That single function, keyed by mapKey, eliminates the duplication and IS the common framework. Everything below is implementation detail of that one idea.

- `savePersistedState(mapKey)` — write the JSON (state + rows + selected ids + bbox, all types, all states).
- `drawPersistedState(mapKey, webView)` — read `<mapKey>_panel.json`, execute the persistent-state draw from it.
- Both maps call these two functions. The hand-coded per-map copies are DELETED. One implementation, fix-once-fixes-both.

Note: this is a SEPARATE operation from the live viewport draw (which fires on pan/zoom and stays per-map, unchanged). `drawPersistedState` is the RESTORE/RECOVERY draw — deterministic, JSON-driven, triggered on entry/map-switch, NOT dependent on an onViewportChanged event firing. Internals (query JSON-bbox + filter by JSON-ids, vs fetch-by-id) are an implementation choice settled at build time; simplest is to reuse the existing query+filter fed from the JSON's bbox + ids.

---

## APPROACH (REVISED 2026-06-16 — PLANNING-FIRST) — SHARED FUNCTION, BUILT FROM THE CLEAN MAP
**SEQUENCING CHANGED to PLANNING-FIRST (Fred's reasoning, supersedes the earlier convoy-first note below):** Build the shared function INTO planning (the simpler, working codebase) FIRST. Reason: extract from clean, known-good behavior so the FUNCTION ITSELF is proven correct before it ever meets convoy's quirks (persistent WebView, GPS centering, the clobber). 

Why planning-first beats convoy-first: if we build into convoy first and it misbehaves, we can't tell if the new function is wrong OR convoy's pre-existing quirks are interfering (two unknowns). Build into planning first (clean) — if it still works, the function is PROVEN. Then when convoy adopts it and something breaks, we KNOW it's a convoy-specific quirk to handle (one unknown, isolated). Cleaner cause-isolation + better diagnostics.

**Recovery (NOT a fallback to desire):** none of this is in production — `0082b032f` is a dev checkpoint, not a shipped feature, and it IS the disparate-two-copies code we are LEAVING. So reverting there is not a goal; it just restores the problem. The DIRECTION is always FORWARD to the shared function. `git history` / `git checkout 0082b032f -- <file>` is a recovery tool if we tangle badly mid-extraction, but the endpoint is always both maps on ONE shared function — never back to two copies. **Planning-working is the LAUNCH PAD** (it must work so convoy inherits a proven function), not a production asset to protect. If extraction breaks planning, FIX FORWARD on the shared function.

**Phase 1:** build `drawArtifacts` + `drawPersistedState` + `savePersistedState` into PLANNING, wire planning's call sites to them, prove planning WORKS ON THE SHARED FUNCTION (not its old copy). **Phase 2:** convoy adopts the SAME shared functions; per-map copies deleted. Convoy-specific quirks surface here, isolated. Endpoint: both maps on one function.

### JSON READ/WRITE INVENTORY (audited 06-16 — every state-save accounted for)
Every save already funnels through ONE wrapper per map → replacing the wrapper covers every write. No scattered saveMap calls to hunt.
- **PLANNING (ConvoyMapViewerScreen.kt):** readMap 214 (entry seed), 610 (reseed gate in onViewportChanged); savePlanningState DEF 230 → saveMap 252; SAVE TRIGGERS 256 (debounced viewport-settle — convoy LACKS this), 943 (onEditDisplay/onSetState), 1271 (onDismiss).
- **CONVOY (ConvoyScreen.kt):** readMap 186 (entry seed), 241 (diagnostic, remove), 739 (reseed gate); saveConvoyState DEF 226 → saveMap 244; SAVE TRIGGERS 1400 (onSetState), 1695.
- **MapStateStore.kt:** saveMap 78, readMap 120 (shared low-level I/O).
- **NO deleteMap on current base** — cold-launch clear (session-only) must be added separately.
- **ASYMMETRY:** planning has the debounced viewport-settle save (256) that persists bbox on every pan/zoom settle; convoy LACKS it → part of why convoy's bbox was never saved. The shared design must give convoy this (or equivalent).
- `savePersistedState(mapKey)` REPLACES both wrappers; all 5 triggers call it. `drawPersistedState` + seed-read replace the readMap points.

---

## APPROACH (ORIGINAL 2026-06-16 — convoy-first; SUPERSEDED by planning-first above)
Build ONE shared pair of functions — `saveState(mapKey)` + `recreateState(mapKey, webView)` — keyed only on mapKey (open `<mapKey>_panel.json`). The JSON is the entire contract: **write a JSON, read a JSON, redraw the map.** Restore ALL values (every row + display state, all 3 states) BEFORE the draw, then re-execute the draw from the restored rows.

**CRITICAL SEQUENCING — convoy-first, planning-second (protects committed planning):**
- **Phase 1 (build + prove on CONVOY only):** wire the shared functions to ConvoyScreen ONLY. Planning stays on its committed working code (`0082b032f`) untouched. Convoy is already broken → no regression risk. Prove the shared function works on convoy (JSON pull + on-device: select/all/off persist & restore for all types, survive pan/exit/return, no clobber, no 165).
- **Phase 2 (adopt on PLANNING):** only AFTER convoy proves it, wire ConvoyMapViewerScreen to the SAME shared functions, retire planning's duplicate copies. Now both maps share one impl. Planning revert anchor `0082b032f` if it destabilizes.
- Rationale: the elegance (one function, both maps) is also the risk — a flaw breaks BOTH at once. Convoy-first means a flaw can't take down the working committed map.

## BUILD SEQUENCE (small tested steps, planning anchor 0082b032f)
1. **Answer Q1-Q5 first** (below) — they shape the function signature; building before answering = wrong function + rework.
2. Confirm/add `queryXByIds` in SpatialDbManager (Q4).
3. New shared `saveState(mapKey)`: saves all types, full rows + display, from a per-type row representation (drop activeListType gate) + bbox. Convoy-first. Test JSON pull: all types' rows persist, no clobber.
4. New shared `recreateState(mapKey, webView)`: draw from persisted rows via query-by-id; query-by-bbox only when no rows exist for a fresh frame. Convoy-first. Test display matches JSON, survives pan/exit/return.
5. Prove convoy end-to-end. Then Phase 2: wire planning to the shared functions, test BOTH.
6. Commit (named files only). Then FIT rides this as the reader.

## STATUS OF TODAY'S PATCHES vs THIS MODEL CHANGE
- **Milestone 1 (onEditDisplay live-bounds + three-way reselect):** APPLIED + BUILT + TESTED working today. The live-bounds part is a stopgap that this model change SUPERSEDES (restore-by-id removes the bbox query entirely). Leave it in for now; the model change replaces it.
- **Milestone 2 (rowsFor mirror + save-bbox):** patch was WRITTEN (`convoy_saveState_mirror_planning_2026-06-16_v1.py`) but **NOT run** — and it is now SUPERSEDED by this model change. **DO NOT run the Milestone 2 patch tomorrow** — it's obsolete. Build the model change instead.
- Diagnostic logs (JSONDIAG @242, READ @740, TRAILDIAG @768) + clearTrails @772 are in the current build — keep for testing the model change, remove before commit.

---

## WHY THIS IS THE RIGHT FIX (not another patch)
Every bug today was a symptom of **re-deriving known display state through a query that can't be trusted.** Persisting the full filter and restoring by id removes the query from the restore path entirely for existing filters. One root change dissolves the whole bug class (empty list, clobber, stale bbox, 165) instead of patching each. And making it a shared mapKey-keyed function ends the convoy/planning drift that caused the repeated re-fixing.
