# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-16 (~8:30 PM EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `c0259d2ca`

---

## ⭐ TODAY'S OUTCOME (2026-06-16) — PERSISTENCE COMPLETE, FIT 95%

### ✅✅✅ PERSISTENCE FOUNDATION — COMPLETE & COMMITTED (3 commits)
The whole persistence foundation FIT rides on is done, device-proven, and banked.
- **`fe8a0849a`** — Planning fully on the shared draw fn (Stages 1-3). SpatialDisplayManager parameterized (JSON-fed `processViewport` + `processArtifact`); planning onViewportChanged → processViewport; `drawPersistedState` restore entry wired into planning onPageFinished. Device-proven "test perfect."
- **`cec3a3e4a`** — Convoy Fix 1: both convoy WebView paths unified on processViewport + save-clobber fixed + bbox saved. Device-proven via JSON pull (no clobber, real bbox). "works for the first time!!!"
- **`c0259d2ca`** — Convoy Fix 2: entry restore (seed lastViewport + fitBounds + drawPersistedState) + cold-launch deleteMap. Device-proven BOTH behaviors: in-session re-entry restores frame+trails; cold launch → GPS.

**The 3-day clobber/divergence bug is DEAD.** Architecture: revived the EXISTING shared draw fn (SpatialDisplayManager) that both maps had drifted away from; re-wired one map at a time, build+device-prove+commit each.

### ⏳ FIT — IMPLEMENTED + PANEL-OWNED, BUT NOT WORKING YET (resume here)
FIT is the GOAL ([2h.2]). It's implemented and builds clean, but the draw doesn't happen — **uncommitted, in working tree.** See the detailed bug section below.

---

## 🐞 FIT BUG — TRACKED (FIX FIRST NEXT SESSION)

### Where FIT stands
- **`fit()` is implemented** at `ConvoyArtifactOps.fit(context, webView, mapKey, artifactType, artifactId)`.
  - Design (Fred's toggle-list): bbox = artifact's own min/max_lat/lon (`SpatialDbManager.bboxForArtifact`, reuses `spatialTableFor`, all 4 types have min/max columns) → query that type in the bbox (the toggle list) → rows = every in-bbox artifact, ONLY the fitted one `checked:true` → snapshot (fitted type SELECTED state 2 w/ full rows; other 3 types OFF state 0; bbox; fitArtifact) → `saveMap(mapKey)` → `drawPersistedState(mapKey, webView, context)`.
- **FIT is now PANEL-OWNED** (not per-caller). The shared `ArtifactListPanel` (detail panel) owns the call: FIT button calls `ConvoyArtifactOps.fit(ctx, fitWebView, mapKey, singular, dId)` directly. Params `mapKey` + `fitWebView` added; `onFit` lambda dropped. Both call sites pass VALUES: convoy `mapKey="convoy", fitWebView=webViewRef.value`; planning `mapKey="planning", fitWebView=webViewRef`. WHY: detail panel is reached from multiple entrances (Work-with-Artifacts, search results, future map-popup) — owning the call means every entrance gets working FIT for free.
- **Build:** SUCCESSFUL (15m37s). All patches count==1 clean.

### The bug (device test)
**FIT fires but the map does NOT move or draw.**
- Logcat confirms `fit()` runs: `ArtifactOps: FIT track 8f54b838... -> bbox=[37.603,-113.163,38.037,-112.910] rows=1` — real Utah bbox computed, NO "FIT not yet wired" (panel-owned fix worked).
- **BUT the JSON on disk does NOT have the FIT write.** Pulled convoy_panel.json shows `Tracks: state:0, rows:[]` and `bbox: {NH GPS coords ~42.8,-71.2}` — i.e. the NH/GPS viewport state, NOT the Utah fit. The map stayed on NH (never moved to Utah).

### Probable causes (in priority order to check)
1. **`drawPersistedState` no-ops because the WebView is null/dead at FIT time.** `fitWebView = webViewRef.value` may be null when called from the detail panel context → drawPersistedState silently does nothing → map never moves. **Likely fix:** resolve the convoy WebView from `ConvoyViewModel.persistentWebView` (reliably set at ConvoyScreen:862, defined ConvoyViewModel:284) instead of `webViewRef.value`. (Planning's webView is a LOCAL var @82 — for planning, resolving differs; convoy can use the ViewModel.)
2. **FIT's saveMap is being CLOBBERED by the convoy map's own viewport save.** Because the map never moved (still NH), `onViewportChanged` → `saveConvoyState` fires and overwrites FIT's Utah JSON with the NH frame. This is the SAME class of clobber as Fix 1. If the map actually moved (drawPersistedState working), onViewportChanged would save the Utah frame, not clobber. So #1 (no draw) is the upstream cause; #2 is the consequence that wipes the JSON.
3. **drawPersistedState may not itself fitBounds + inject the JS draw** — confirm its body: does it move the map (fitBounds to the snapshot bbox) and call the JS draw, or does it expect the caller to trigger the redraw? If it only writes/processes but doesn't fire the map JS, FIT needs to also trigger the map move.

### Diagnosis plan (resume)
1. Read `drawPersistedState` body in SpatialDisplayManager.kt — does it fitBounds + inject JS draw, and does it null-check the webView (silent return)?
2. Confirm whether `webViewRef.value` is null at FIT time vs `ConvoyViewModel.persistentWebView` being live. If the ViewModel one is reliable, switch FIT to use it (or have drawPersistedState resolve by mapKey).
3. Verify the clobber theory: add the FIT write, immediately pull JSON BEFORE any viewport event, confirm FIT's Utah shape is there momentarily, then confirm onViewportChanged overwrites it. If so, FIT must either (a) move the map (so the saved frame IS the fit) or (b) suppress the viewport-save during a fit.
4. The clean fix is almost certainly: **make drawPersistedState actually move the convoy map (fitBounds to the fit bbox) using a live WebView** — then the map shows the fit AND the natural viewport-save records the fit frame (no clobber, because the frame IS Utah).

### Files in FIT working tree (uncommitted)
`SpatialDbManager.kt` (bboxForArtifact added), `ConvoyArtifactOps.kt` (fit() body, mapKey-aware), `ArtifactListPanel.kt` (panel-owned FIT: LocalContext, mapKey+fitWebView params, button calls fit directly), `ConvoyScreen.kt` (convoy call site mapKey+fitWebView), `ConvoyMapViewerScreen.kt` (planning call site mapKey+fitWebView). **Do NOT commit until FIT is device-proven.**

---

## ⛔ AFTER FIT — MAGNIFYING-GLASS UNIFIED SEARCH ([2h.1])
Full spec in `GroupTrack_Search_Consolidation_Handoff_2026-06-16.md`. Summary:
- **Icon DECIDED: magnifying glass** (not binocular).
- ONE draggable magnifying-glass FAB on BOTH maps replaces THREE current searches.
- **REMOVE launch points (KEEP engines):** (1) planning area search (ConvoyMapViewerScreen ~365-405, Geocoder→setView); (2) convoy location search (ConvoyScreen locationSearchQuery/Results/Error @267-269); (3) artifact search (ConvoyScreen 1355-1361, onSearch→searchByName→assignNameSequence).
- **KEEP/reuse:** searchByName (`4f7abbbb7`), ArtifactSearch.kt numbering, Geocoder→setView, detail panel, FIT.
- FAB: selector (Area/Trail/Track/Route/Waypoint) + text box. Area→setView. Artifact→searchByName→results→detail→FIT.

## ⏳ OPEN ITEM — MAP-POPUP / ARTIFACT-TAP → DETAIL PANEL
When an artifact is touched on the map, route to the SHARED detail panel (which now owns FIT, so FIT works automatically). **Open design Q (Fred, 06-16):** consider skipping the popup banner entirely and going straight to the detail panel on artifact-tap — the detail panel shows the name + far more info than the popup, and is the thing the user usually wants. Decide popup-as-peek vs tap-straight-to-detail.
- Wiring: JS click handler on artifact (popup or marker) in BOTH map HTMLs (convoy_map.html + grouptrack_map.html, they drift) + Android @JavascriptInterface bridge to open detail with (type, id). Pass mapKey + fitWebView so FIT works.

## ⭐ NEXT STRATEGIC CHECKPOINT (Fred's request)
After the search FAB is built: **PLANNING DISCUSSION** — go through this living checklist together, select + prioritize next tasks.

---

## OTHER OPEN (backlog)
convoy_map.html drawTrack/clearMarkers not defined; [6.2] geojson asset removal; [1.2] sliceLine whole-trail; [1.5] auto-save+terminate on map-switch; import [4.x]; queues [3.3]; ConvoyArtifactOps OTHER stubs (rename/delete/toRoute/toTrack/upload/download/changeType/editPoints/addAlias/setTH — Pass-1 log stubs); [3.1b] GPS-recenter button (planning); [3.9a] arrows pixel (neon green, pixel spacing, two CRLF HTMLs + polylineDecorator); docs [9.x]; BLE [10.1]. DEFERRED: GeoPackage national, V3.0, paywall.

---

## DESIGN CONTEXT (carried forward)
- **Map-purpose model:** Convoy = live/location (GPS, proximity, session-only). Planning = deliberate/identity (name search, fit, persisted frame across launches). Two selection methods share one golden-JSON merge: area-based + artifact/Fit.
- **FIT toggle-list:** fit writes the full bbox list with ONLY the fitted one checked=true; the SELECTED draw-filter (`checkedIdsFor` returns only checked=true) draws only the fitted one; unchecked rows are the quick toggle-on list. List + draw derive from the SAME bbox query → always consistent.
- **Winning approach (proven all session):** own behavior in the SHARED component (draw AND fit); callers pass DATA (mapKey+webView) not BEHAVIOR (lambda). Revive existing shared fn, re-wire ONE map at a time, BUILD+DEVICE-PROVE+COMMIT each step.

---

## TREE STATE
- **Committed HEAD `c0259d2ca`** (Convoy Fix 2). Persistence chain: `fe8a0849a` → `cec3a3e4a` → `c0259d2ca`.
- **FIT work UNCOMMITTED in working tree** (5 files listed above) — build green, NOT device-proven. Commit only after FIT works on device.
- Parked (never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db`, `D docs/.tmp.driveupload/10630`. Commit only named files. Never `git add .`.

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~10–34 min; MainActivity edits → ~23min wide recompile)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 = `8624SBCEDF00001789` field/real-GPS · Droid 2 = `24039703201775` dev)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json`
- Logcat (non-blocking dump): `adb -s 8624SBCEDF00001789 logcat -d -s ArtifactOps ConvoyMap | tail -20` (`-c` to clear first). LIVE logcat BLOCKS the terminal — use `-d` dump or a 2nd window; do NOT chain install behind a live logcat.
- **LINE ENDINGS:** .kt files are MIXED CRLF/LF, even within one file. Verify with `od -c` on RAW bytes (not sed/awk/cat-piped, which strip \r). Best: ending-agnostic patches (try CRLF then LF, match the anchor's). Single-line python patch, count==1 guard, unique filenames.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 /c/Users/kixaz/Downloads/<name>_v1.py`.
- Revert one file: `git checkout <hash> -- <file>`.

## EOD DOCS STILL PENDING (do after FIT commits, or next session)
Release notes `grouptrack_release_notes.html` + manual/cookbook `grouptrack_manual.html` — add V2.5 entries (shared-draw consolidation, convoy persistence, FIT) + document FIT/persistence behavior. Edit in place from prior baseline, output full updated files.
