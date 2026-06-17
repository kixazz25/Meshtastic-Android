# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-15 (~4:30 PM EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `0082b032f` · **Rollback anchor:** `168778c0a`

---

## ⛔⛔ TOP PRIORITY (2026-06-16 EOD) — BUILD THE SHARED DRAW FUNCTION (~4 hrs)
**Read the design spec `GroupTrack_FullFilterPersistence_ModelChange_DESIGN_2026-06-16.md` FIRST — it has the discovered draw contract + FIT spec + exact build steps.**

### What changed today (the simplification)
We examined the DRAW function end-to-end (ConvoyScreen 751-815) — the thing we should have studied on day 1. It was the keys to the kingdom. Every bug (empty list, clobber, 165-trail, missing tracks) was downstream of not understanding that one function's contract. The draw is currently the **body of `onViewportChanged`** (not a callable function), which is WHY restore depends on a viewport event firing with the right bbox.

### The discovered draw contract (4 identical per-type blocks)
```
INPUTS:  bbox(s,w,n,e) · zoom→limit(500/2000) · state(OFF/ALL/SELECTED) · selectList(checked ids)
LOGIC:   1. ELIGIBILITY  raw = if(state!=OFF) queryXByViewport(bbox,limit) else empty
         2. SELECT/OMIT  results = if(state==SELECTED) raw.filter{id in selectList} else raw
         3. RENDER       buildXGeoJson → updateX(json); if(state!=OFF) showX()
```
The draw takes (state, bbox, select-list) and executes. **Keep it exactly as-is — feed it from JSON instead of live state.** Do NOT reinvent the draw.

### The build (~4 hrs, Fred's estimate)
1. **`drawArtifacts(bbox, states, selectLists, webView)`** — extract 751-815 into a standalone parameterized function (the SHARED CORE; no JSON read, no viewport coupling).
2. **`drawPersistedState(mapKey, webView)`** — read `<mapKey>_panel.json` (state, ids, bbox) → call drawArtifacts. = RESTORE.
3. **`savePersistedState(mapKey)`** — write state + select-lists (ALL types — drop the activeListType clobber gate) + bbox (currently null — required add).
4. `onViewportChanged` calls drawArtifacts with live params. = LIVE nav (same core, two sources).
5. **FIT = write JSON + call drawPersistedState** — snaps in for free (compute artifact bbox, fitted type SELECTED with in-bbox list/only-fitted-checked, other types OFF).
6. **CONVOY-FIRST** (planning stays on committed `0082b032f`), prove, **then PLANNING** calls the same functions. Per-map copies deleted. This IS the shared-code consolidation.

### Two hand-coded query functions → one parameterized function
The whole point in one line: two complex hand-coded persistence/draw functions (convoy's + planning's) → ONE parameterized function reading a JSON and executing a persistent-state draw. Eliminates the duplication that caused 3 days of re-fixing.

### Notes
- **⛔ DO NOT run `convoy_saveState_mirror_planning_2026-06-16_v1.py`** — obsolete, superseded.
- **Milestone 1 (onEditDisplay live-bounds + three-way reselect)** is applied+built+tested working — a stopgap the model change supersedes; leave for now.
- **Q4 check first:** does SpatialDbManager have `queryXByIds`? Likely not needed — drawArtifacts reuses queryXByViewport fed the JSON bbox + filters by JSON ids, exactly as the current draw does.
- Diagnostic logs (JSONDIAG, TRAILDIAG) + clearTrails in current build — keep for testing, remove before commit.

### Lesson (Fred, 06-16)
Examine the central function's contract BEFORE building around it. The draw was studied on day 3; doing it on day 1 would have saved the detour. Also: the two back-to-back viewport queries (eligibility then a second identical query in some paths) are an artifact of organic growth, not a design — the consolidation removes the duplication.

---

## TODAY'S OUTCOME (2026-06-15) — historical, superseded by above
- ✅ **PLANNING persistence COMMITTED** = `0082b032f` ("Planning map persistence: bbox save/restore + selection (rowsFor) fix"). Device-proven. This is the recovery point.
- ✅ **CONVOY persistence SAVE side PROVEN** (via JSON pull) — but **REVERTED at EOD** (see below). The save logic was correct; the trail-DISPLAY had an unresolved bug and the working tree became cross-contaminated, so convoy was rolled back to `0082b032f` to protect a clean base.
- 🐞 **One unresolved bug carried to tomorrow:** on convoy restore, the map drew ~165 trails instead of the selected 2. **Measured fact: the spatial DB query for the saved bbox returns only 17 trails — so the 165 came from a SEPARATE draw call, not the DB viewport query.** Source of the 165 not yet identified.
- ⛔ **Convoy work REVERTED.** ConvoyScreen.kt + MapStateStore.kt are back at `0082b032f`. Convoy persistence must be rebuilt tomorrow from the clean base, one tested step at a time.
- 📄 Docs: this checklist updated. No user-manual or release-notes content changes (no shippable feature landed for testers today).

---

## ⛔ CONVOY PERSISTENCE — REBUILD FROM CLEAN BASE (TOMORROW)
Planning persistence is committed and works. Convoy must mirror it. The SAVE side was proven correct today (JSON pull was perfect) before the revert — so the approach is known, it just needs to be reapplied cleanly with a test after each step.

### What was proven working today (reapply these, test each):
1. `MapStateStore.deleteMap(mapKey)` + cold-start splash call → cold launch clears convoy frame (session-only design).
2. `saveConvoyState` passes `bbox = BBox(lastViewport*)`.
3. Restore block reads `cmSeed.bbox` → `fitBounds` on convoy entry.
4. convoy `rowsFor` + `onEditDisplay` + `onDismiss` MIRROR planning exactly (three-way `selectedArtifactIds = when{ curState==DS_SELECTED && curChecked!=null -> curChecked; DS_ON->all; else->empty }`).
5. `onEditDisplay` reads LIVE `map.getBounds()` via JS callback (not stale `lastViewport*`) — fixes reopen "no X in current view".
6. PANEL-MOVE: `ArtifactListPanel` out of the `.align(TopEnd)` Column to the root Box → full-screen overlay drawn on top. (Panel was rendering offscreen — that was the "select does nothing" bug.)

### Proof the save worked (JSON pull, before revert):
`convoy_panel.json` = `Trails:{state:2, rows:[2× checked:true]}, Tracks:{state:2, rows:[1]}, Waypoints/Routes:{state:0}, bbox:{REAL Utah 37.658/-112.099/37.801/-111.991}`. State, rows, bbox all correct. SEL list also restored correctly (2 trails showed checked on reopen).

---

## 🐞 THE OPEN BUG — ~165 TRAILS DRAWN ON RESTORE (root: a separate draw)

**Symptom:** convoy restore draws ~165 multicolor (cyan/green/pink) trails instead of the selected 2.

**Measured facts (not theory):**
- Spatial DB query for the SAVED bbox returns **17** trails (direct sqlite3 on the pulled DB): `SELECT COUNT(*) FROM trails WHERE max_lat>=37.658 AND min_lat<=37.801 AND max_lon>=-112.099 AND min_lon<=-111.991` = **17**. (Matches the ~18 in the panel; user selected 2.)
- The geojson ASSET is ruled out: `utah_trails_stgeorge.geojson` = 3270 bytes / 1 feature — cannot produce 165.
- Therefore the 165 are NOT from the DB viewport query for that bbox, and NOT from the asset. **A different draw call drew them.**

**Causal model (locked, user-stated):** the query populates the map; the map does NOT drive the filter; the **filter drives the display**. So "165 on the map" means a specific draw call handed 165 trails to the WebView.

**The multicolor clue:** DB-rendered trails are CYAN; tracks NEON GREEN. The 165 are multicolor (colored by cartoCode). So the rendering STYLE differs from the current DB path — pointing at a different/older trail-draw path still firing.

**NEXT-SESSION PLAN (measure, don't theorize — no "memory"/"caching" guesses without a log proving it):**
1. Grep EVERY trail-draw call across the whole codebase: every `updateTrails(`, `loadTrails(`, `showTrails(` in Kotlin; any other trail/geojson source.
2. Add a Log at EACH draw printing the count + bbox/source it used. Run once, read which one fires ~165.
3. Read the EXACT trail-rendering JS in the current build's `convoy_map.html` (it's compiled into the APK at build — not cached separately) to confirm whether multicolor rendering is current-but-wrong code (fixable line) vs an orphaned path.
4. ALSO instrument the bbox handoff (open question): log the bbox that populates the PANEL query vs the bbox sent to the DRAW query — confirm they're the same value. If the draw used a wider bbox, that alone explains a higher count.
5. Fix = make only the DB-filtered draw (cyan, 17→filtered-2) render; kill/clear the separate draw.

---

## [3.1c] FILTER-DISPLAY RULE (locked — spec any fix must satisfy)
JSON filter is the **golden** display state.
- **bbox unchanged** → no query; old JSON values carry over verbatim.
- **bbox changed** → query + reconcile by id:
  1. id in new query **and** in JSON → keep JSON's yes/no (golden; never flip an in-frame yes→no).
  2. id new in query (not in JSON) → add as **no**.
  3. id in JSON but gone from query → **delete** (returning item comes back **no**; no stale resurrection).
- Partial overlap = in-bbox (bbox-intersect query already returns partial overlaps; matters for long trails).
- A yes only becomes no by leaving the bbox.
- On entry re-query SELECTED only — do NOT re-query ALL/OFF (preserves their values).

**Acceptance cases (all must pass):**
1. bbox unchanged, 10 in filter / 2 selected → 2 display.
2. 2 selected, both in bbox → 2 display.
3. 15 in new bbox, 2 carried-forward → 2 display, 13 no.
4. 1 selected leaves bbox (6 in new bbox, 1 still in frame) → 1 displays.

---

## ⛔ BACKLOG — VIEWPORT (`lastViewport*`) USAGE AUDIT (convoy) — added 2026-06-16
**Problem definition:** Convoy queries the spatial DB against a CACHED viewport (`lastViewportSouth/West/North/East`, ConvoyScreen ~212) instead of the live current map bounds. The cache is written ONLY by `onViewportChanged` (ConvoyScreen 570 + 736 — the JS→Kotlin bridge from convoy_map.html's `moveend` handler @338, which is **debounced 400ms** @343). The cache can therefore be **stale** relative to what the map is actually displaying, because:
- The `moveend` handler updates it on a **400ms setTimeout** — a query firing within 400ms of a pan reads the OLD bbox.
- `zoomend` (convoy_map.html @329) does **NOT** call `onViewportChanged` at all — planning's grouptrack_map.html fires it from BOTH zoomend (@747) and moveend (@752); convoy fires only from moveend. So a pure zoom may not refresh the cache.
- On entry, GPS-centering (`setView(deviceGPS)` @647 one-time, and the autoPan block @322-335 when recording) moves the map to a GPS point; `onViewportChanged` then records GPS-centered (sometimes zero-area / `n==s,e==w`) bounds. `lastViewport*` faithfully records this — it is NOT directly overloaded with GPS (only written by onViewportChanged, confirmed by full-tree grep), but it ends up holding GPS-point bounds because the map was moved there.

**Observed failure (2026-06-16):** Map DRAW works (uses fresh `south/west/north/east` params from onViewportChanged directly, ConvoyScreen @765) → Utah trails display correctly. But the SELECT-LIST query (`queryArtifactList`, ConvoyScreen @1414) reads the stale CACHE (`lastViewport*`) → queries the wrong/NH-GPS frame → returns 0 rows → **SEL/EDIT filter list comes up empty even though the map shows the correct trails.** Proven by: select-ALL still yields an empty list (a zero-result query, not a filter problem); JSON has correct state=2 + checked rows; map displays correctly. Same view, two consumers, two different bboxes — DRAW=fresh (correct), LIST=cached (stale).

**Inconsistency to resolve (the audit):** The map DRAW uses fresh params (correct); the QUERIES use the cache (can be stale). Every `lastViewport*` READ in convoy must be evaluated:
- `542/543` trails/tracks query · `550/551` query pair · `702/703` query pair · `710/711` query pair · `1414` queryArtifactList (the select list — failing) · `1452` (`val sLat = lastViewportSouth...`).
- DRAW @765 already uses fresh params → correct → leave.

**Principle (user-stated 2026-06-16):** "I cannot think of an instance where the bbox queries should NOT use the current bbox." Every viewport query should use the frame actually on screen. The cache is a performance optimization that introduced a correctness bug.

**Resolution options (decide in the audit pass, fix one-at-a-time + test):**
- (A) Make the cache reliably current at its SOURCE — fire `onViewportChanged` from zoomend too (mirror planning), and reconsider the 400ms debounce — so every consumer can trust `lastViewport*`. One fix, all consumers benefit. Must PROVE it's current before trusting.
- (B) Replace cache reads with live `map.getBounds()` (async callback) at each query site — always correct, never stale, but more code per site and async.
- The immediate select-list fix (2026-06-16) uses (B) at onEditDisplay only to unblock persistence. **The general audit is deferred** (per user: fix this instance → complete persistence → FIT → unified search; THEN the audit). Until done, treat any `lastViewport*` read as potentially stale.

**Note:** field_crossref does NOT index `lastViewport*` (it's a UI composable local declared with `remember`, not a backend/bridge field) — confirmed empty in xref. Use single-file grep / live read for composable locals, not the xref.

---

## ⛔ ARCHITECTURAL DEBT — SHARED-CODE CONSOLIDATION OWED
Convoy & planning display-state handlers (save/restore/onEditDisplay/onDismiss) have **diverged into duplicate copies**, against the documented "shared code / no duplication" design (AllDocs line 14670: "both maps, same shared code — fix once fixes convoy+planning"; line 2964: single source of truth). Two map HTMLs (convoy_map.html vs grouptrack_map.html) can also drift. This duplication is the root cause of today's repeated hand-mirroring and drift.

**User's stated direction:** reengineer convoy methods into shared functions so a fix happens once and both maps inherit it. **Caution:** this refactor touches both maps and risks destabilizing the committed, working planning map. If done, do it in small tested+committed steps — extract a shared function, get BOTH maps passing on it, test each, commit — never a big-bang rewrite. If it destabilizes planning, revert to `0082b032f` (nothing committed is lost).

---

## ⭐ TOMORROW'S AGENDA (4 items, in order)
**1. Convoy map persistence** (rebuild from clean base) →
**2. FIT function** activated from the artifact detail card →
**3. UI changes** — unified area/artifact search function →
**4. Directional trail arrows** — pixel version, NEON GREEN (not black), pixel-based spacing.

---

### AGENDA ITEM 1 — CONVOY PERSISTENCE (rebuild)
1. **Decide approach:** rebuild convoy persistence as-was (mirror planning, fast) OR start the shared-function refactor (correct, riskier — see Architectural Debt). Either way: clean base `0082b032f`, one tested step at a time, no stacked patches.
2. **Find the 165-trail draw** (measure: grep all `updateTrails(`/`loadTrails(`/`showTrails(` + log which fires; read current convoy_map.html trail JS; instrument panel-vs-draw bbox). DB query for saved bbox = 17 (measured); 165 = a separate draw.
3. Reapply the 6 proven changes (see CONVOY PERSISTENCE section above), testing after each.
4. Device-prove with JSON pulls + visual: select 2 → state:2 + 2 checked:true; leave/return → only 2 display (NOT 165); pan within frame → 2 stay.
5. **Commit convoy** (named files only: ConvoyScreen.kt, MapStateStore.kt) onto `0082b032f`. Never `git add .`.

### AGENDA ITEM 2 — FIT WRITER (the goal)
See [2h.2] FIT WRITER section below for the full write spec. Activated from the artifact detail card's FIT button (currently a placeholder in ArtifactListPanel.kt). FIT writes the selected artifact as checked:true + its bbox into golden JSON, all other in-bbox results checked:false, reloads → displays the one artifact, neighbors off/toggleable. Convoy persistence (item 1) is the reader FIT writes into — must be committed first.

### AGENDA ITEM 3 — UI: UNIFIED AREA/ARTIFACT SEARCH ([2h.1])
- ONE always-visible search bar with a dropdown type selector: `Area` + `Trails` / `Tracks` / `Waypoints` / `Routes`.
- **Area** selected → repurpose the existing area-search geocoder → `setView` to the area (no artifact).
- **Artifact type** selected → `searchByName(type, term)` → result list → tap → DETAIL card → FIT.
- **Planning** gets the FULL dropdown (Area + all 4 artifact types). **Convoy** gets **Area-only** (per map-purpose model: convoy = proximity, no name search).
- Supersedes the old collapsible-search plan. `searchByName` already exists (committed `4f7abbbb7`): SELECT idCol,name,geom_hash WHERE name LIKE ? AND NOT NULL AND TRIM<>'' AND <>'Not Named' ORDER BY name COLLATE NOCASE LIMIT 200. Search is NON-SPATIAL; FIT is the only bridge to the map.
- This is UI rework on top of existing search backend — rehost the search surface, wire the dropdown, route Area vs artifact paths.

### AGENDA ITEM 4 — DIRECTIONAL TRAIL ARROWS, PIXEL VERSION ([3.9a])
- Convert arrow density from PERCENT to PIXEL spacing: `repeat:'12%'` → pixel value (~60–100px range; tune on device).
- **Color: NEON GREEN — NOT black.** (Correction 06-15: arrows render neon green to match track styling, not the black fallback.)
- Spacing is PIXEL-based (fixed on-screen spacing regardless of zoom), not percent-of-line.
- Redraw on zoom: in `showTracks`, after `addTo`, trigger a redraw (`map.fire('moveend')`) so pixel spacing recomputes when the view changes.
- TWO map HTMLs (convoy_map.html + grouptrack_map.html, both CRLF) + vendored polylineDecorator — apply to both, mirrored.
- Related open: [3.9] redraw; arrows use trackLayer=L.geoJSON; loadTracks convoy ~447 / planning ~290.

> Note: "quick" tasks are never quick here. Budget accordingly. Measure before theorizing.

---

## [2h.2] FIT WRITER (the GOAL — after convoy persistence committed)
FIT is the deliverable; persistence is the foundation it rides. FIT must:
- IDENTIFY the specific artifact (id + type + bbox from its spatial row).
- Write to golden JSON: `bbox` = artifact's bbox; that type `state` = SELECTED; that artifact `checked:true`; **all OTHER in-bbox query results `checked:false`** (present but off, toggleable to COMBINE for route-building).
- Reload → map frames to the artifact, displays only it, neighbors off and toggleable.
The persistence/restore machinery built (save + select/edit restore) is the READER FIT writes into.

---

## [3.1b] OPEN (deferred) — GPS-recenter button (planning)
Manual "center on current GPS" control — bull's-eye placeholder on the planning Sat/Topo/Topo+ layer nav bar (UI + comment; no function yet). Real GPS-center logic already exists (planning postDelayed setView block).

---

## DESIGN CONTEXT (carried forward — still valid)

### Map-purpose model
- **Convoy** = live / location-anchored: opens to GPS, finds artifacts by proximity. No search, no fit. Session-only persistence.
- **Planning** = deliberate / identity-anchored: finds by name (search), has fit, opens to persisted last-session frame (across launches).
- Asymmetries are intentional — one rule per function.
- **Two selection methods** share one golden-JSON merge: (1) area-based (query bbox, all/none/selected); (2) artifact-based / Fit (pick one, frame to its bbox, display alone; others off, toggleable).
- **[2h.1] consequence:** convoy gets Area-only search; planning gets the full search dropdown.

### Convoy persistence design (settled)
SESSION-ONLY. Cold launch = GPS fresh (splash deletes convoy_panel.json). In-session map-switch back = restore saved frame. planning_panel.json NEVER deleted. GPS positioning MANUAL only (My Cart/My Group); NOT auto on reopen. Query/save read LIVE map.getBounds() (single source of truth), not lastViewport*.

### Build order
Convoy persistence (rebuild + commit) → [2h.2] Fit writer → [2h.1] search-surface rehost → [3.9a] arrows.

---

## TREE STATE (06-15 EOD)
- Committed HEAD `0082b032f` (planning persistence). Convoy code reverted to this commit — **clean base, no uncommitted convoy work.**
- Backup retained: `ConvoyScreen.kt.bak_move` (the pre-revert convoy work, for reference).
- Parked files (leave; never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db` (pulled for diagnosis — delete or ignore), `D docs/.tmp.driveupload/10630`.
- Commit only named files. Never `git add .`.

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~10–34 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s <serial> install -r -d <apk>`
- Devices: Droid 2 = `24039703201775` (dev/test) · Droid 1 = `8624SBCEDF00001789` (field, real GPS)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json`
- **DB query (no build needed — laptop has sqlite3):** pull with `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 pull /sdcard/Documents/GroupTrack/grouptrack_spatial.db grouptrack_spatial.db` (bare relative dest — Git-Bash mangles `/c/...` paths), then `sqlite3 grouptrack_spatial.db "..."`. Tables: trails, tracks, waypoints, routes.
- Patch scripts: single-line `python3` with count==1 guard; verify by byte size; CRLF-safe anchors.
