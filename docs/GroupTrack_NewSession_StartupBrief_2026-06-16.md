# GroupTrack — New Session Startup Brief
**2026-06-16 EOD → resume point for next session**

## READ FIRST, IN ORDER
1. **This brief** (the resume point)
2. `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-16_EOD.md` (full status + FIT bug detail)
3. `GroupTrack_Search_Consolidation_Handoff_2026-06-16.md` (the after-FIT work)
4. Design specs if needed: `GroupTrack_FullFilterPersistence_ModelChange_DESIGN_2026-06-16.md`, `Convoy_EntryRestore_Design_2026-06-16.md`

## WHERE WE ARE IN ONE PARAGRAPH
Persistence is COMPLETE and committed (3 commits today: `fe8a0849a`, `cec3a3e4a`, `c0259d2ca` — HEAD). FIT (the goal) is implemented and builds clean but does NOT work yet: the function fires (logcat proves it computes the right bbox and writes), but the map doesn't move or draw, and the JSON ends up with the GPS frame instead of the fit. FIT work is UNCOMMITTED in the working tree. **Job #1 next session: make FIT actually draw, then commit it.**

## THE FIT BUG (job #1)
**Symptom:** Tap FIT on an artifact's detail panel → logcat shows `ArtifactOps: FIT track <id> -> bbox=[37.6,-113.1,38.0,-112.9] rows=1` (correct Utah bbox, no "not yet wired"), BUT the map stays put (on NH/GPS) and convoy_panel.json shows `Tracks state:0 rows:[]` + the NH bbox — the fit write is gone/never effective.

**Leading diagnosis:** `drawPersistedState(mapKey, webView, context)` is a no-op because `fitWebView` (= `webViewRef.value`, passed from the convoy call site) is likely null/dead at FIT time → the map never moves → then `onViewportChanged` saves the NH frame, clobbering FIT's JSON (same clobber class as Fix 1).

**First moves next session:**
1. Read `drawPersistedState` body (SpatialDisplayManager.kt): does it fitBounds (move the map) + inject the JS draw? Does it null-check webView and silently return?
2. Check whether `ConvoyViewModel.persistentWebView` (defined :284, set ConvoyScreen:862) is the reliable live convoy WebView vs `webViewRef.value`. If so, switch FIT (convoy) to resolve the WebView from the ViewModel.
3. Confirm the clobber: pull JSON immediately after FIT before any pan; if the Utah shape is there then gets overwritten on the next viewport event → the real fix is to MOVE the map (fitBounds to the fit bbox via a live WebView), so the saved frame IS the fit (no clobber) and the artifact draws.

**Likely fix shape:** ensure drawPersistedState moves the convoy map (fitBounds to the snapshot bbox) using a LIVE WebView (persistentWebView), so the map shows the fit and the natural viewport-save records the Utah frame. Then verify: map flies to the artifact, only it draws, detail list shows it checked + neighbors toggleable. Then COMMIT FIT.

## FIT DESIGN (already implemented — for reference)
`ConvoyArtifactOps.fit(context, webView, mapKey, type, id)`:
- bbox = artifact's own min/max_lat/lon (`SpatialDbManager.bboxForArtifact`, reuses `spatialTableFor`; all 4 types have min/max columns)
- query that type in the bbox → rows = every in-bbox artifact, ONLY the fitted one `checked:true` (the toggle list)
- snapshot: fitted type SELECTED(2) w/ full rows, other 3 types OFF(0), bbox, fitArtifact
- saveMap(mapKey) → drawPersistedState(mapKey, webView, context)

**FIT is PANEL-OWNED:** the shared ArtifactListPanel's FIT button calls fit() directly (params mapKey + fitWebView; onFit lambda removed). Both call sites pass values (convoy "convoy"+webViewRef.value; planning "planning"+webViewRef). This means every entrance to the detail panel (Work-with-Artifacts, search, future map-tap) gets working FIT once the draw bug is fixed.

## FIT WORKING-TREE FILES (uncommitted — don't commit until proven)
`SpatialDbManager.kt`, `ConvoyArtifactOps.kt`, `ArtifactListPanel.kt`, `ConvoyScreen.kt`, `ConvoyMapViewerScreen.kt`.

## AFTER FIT (locked order)
1. **Fix + commit FIT** (job #1)
2. **EOD docs** if not done: release notes + manual (V2.5: shared-draw, persistence, FIT)
3. **Magnifying-glass unified search FAB** (handoff doc) — remove 3 search launch points, one draggable FAB both maps
4. **Map-tap → detail panel** (open Q: popup-as-peek vs tap-straight-to-detail)
5. **PLANNING DISCUSSION** — go through the living checklist, prioritize next tasks

## HARD RULES / METHODOLOGY
- NO "complete" without on-device proof. Fix fails → revert before next. MEASURE (logcat/JSON/DB pull), don't guess.
- Own behavior in the SHARED component; callers pass DATA not BEHAVIOR.
- Walk ONE command at a time (Fred runs each, pastes). Single-line python patch, count==1 guard, unique filenames, ending-agnostic where uncertain.
- LINE ENDINGS: .kt mixed CRLF/LF even within a file — verify with `od -c` on raw bytes, never sed/awk/cat-piped.
- Commit only named files. Never `git add .`. Parked files stay parked.

## DEVICE / BUILD
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~10-34 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 field/GPS; Droid 2 = 24039703201775 dev)
- JSON: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json`
- Logcat dump (non-blocking): `adb -s 8624SBCEDF00001789 logcat -d -s ArtifactOps ConvoyMap | tail -20`
