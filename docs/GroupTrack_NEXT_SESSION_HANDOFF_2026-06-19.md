# GroupTrack — Next Session Handoff (resume 2026-06-20)
*Written 2026-06-19 EOD. Pairs with `GroupTrack_V25_LivingChecklist_OpenItems_2026-06-19.md` — see its "START HERE — TOMORROW" section for full task detail.*

## ⚠️ READ FIRST — the process rule that 06-19 violated
Before ANY UI code edit: re-pull current HEAD files, then build a **launcher → composable truth table** (which detail composable does each launcher actually render?) and SHOW Fred. **No patch until that table exists.** 06-19 burned 6 builds + 2 gradle cleans patching a panel the launchers don't call, because this 5-second check was skipped. We blamed applies / gradle / device versions and missed the obvious. Stay at the process level; verify before acting.

## WHERE WE ARE
HEAD = **`583b7b9df`** (49 commits ahead of origin).
- **Universal search COMPLETE + committed** — `42dc848ce` (convoy search FAB) + `583b7b9df` (planning search FAB + all 3 old searches removed). One shared `UnifiedSearch.kt`, both maps, FAB-only. Device-tested green.
- **Patches J / K / M applied + UNCOMMITTED** in `ArtifactDetailPanel.kt` (the Carto Type work). CORRECT — do NOT revert. They just need the launchers wired to this panel.

## THE PROBLEM (verified — root cause of the whole 06-19 grind)
**There are TWO detail panels, and the launchers don't reach the one we edited.**

1. **`ArtifactDetailPanel.kt`** — the standalone universal panel (has FIT; got all the Carto Type work). Rendered at ConvoyScreen ~1754 + ConvoyMapViewerScreen ~1336.
2. **`ArtifactListPanel.kt`** — the select/edit list, with its **OWN inline duplicate detail** baked inside (AlertDialog ~167-315 + `detailArtifactId` state + own `DetailActionButton` ~436). Rendered at ConvoyScreen ~1725 + ConvoyMapViewerScreen ~1203.

**Fred's framing (exact):** "Our goal was to make ONE detailed callable artifact panel and use it everywhere so we had one piece of code. We built a new detail panel, parametrized and callable as a function. We did all that work and NEVER REMOVED THE OLD FUNCTION and NEVER WIRED THE NEW FUNCTION IN PLACE."

**Verified history:** this was DIAGNOSED June 17 (chat 23127897) — `onResultClick` set both `pendingDetailId` AND `activeListType`, launching two surfaces; Fred's direction was "select -> detail panel directly, bypass `activeListType`." **That June 17 wiring fix was never finished.** 06-19 layered Carto Type onto the unwired panel, so nothing showed.

**Fred's EOD observation (from real use — ground truth):** BOTH launchers (the search FAB AND the select/edit list) end up at the WRONG detail panel. The wiring is wrong in more than one place. (Claude's late-session file was likely STALE — re-pull fresh and verify on real code.)

## THE FIX — ONE panel, do NOT change what works (panel content/FIT), only the wiring
**TASK 1 — REMOVE THE OLD.** In `ArtifactListPanel.kt`, delete the inline duplicate detail (AlertDialog ~167-315 + `detailArtifactId` state + private `DetailActionButton` if unused elsewhere). The list keeps its list/checkbox/select-deselect job; it loses its own detail rendering.

**TASK 2 — WIRE THE NEW.** Every artifact-select launcher invokes the ONE `ArtifactDetailPanel` (set `pendingDetailType/Id`; do NOT set `activeListType` on the detail path):
- search FAB result-tap (verify on fresh files)
- select/edit list row-tap -> add `onOpenDetail(type,id)` param to `ArtifactListPanel`, wired in parent (ConvoyScreen ~1725 + ConvoyMapViewerScreen ~1203) to `{ t,id -> pendingDetailType=t; pendingDetailId=id }`
- BOTH convoy + planning.

**RESULT:** one detail panel, called everywhere = the original goal. Patch M's Carto Type field then shows from every launcher. **Verify the Carto Type row appears from BOTH the search FAB AND the select/edit list, on BOTH maps, before committing.**

**Carto Type spec:** DETAILS-section row, value = TRANSLATED TEXT never the code — 4->"OHV / Road-Concurrent" (blue), 2->"Hiking & Biking" (orange), 1->"Hiking-Only" (yellow), 5->"Biking-Only" (purple), none->"Unspecified" (cyan). Cyan = unspecified ONLY; each real type its own color. **Only Utah trails carry carto_code** — elsewhere cyan-by-design reads "Unspecified" (correct). Test a Utah carto trail to see a real color.

## TASK 3 — MAP ARTIFACT-TAP -> DETAIL (the original intended use; JS<->Kotlin)
Tapping an artifact on the map opens the OLD Leaflet popup (pure JS, never crosses to Kotlin). Wire it to the ONE `ArtifactDetailPanel`:
- Add `onArtifactTapped(type,id)` to the `@JavascriptInterface` object on both maps (alongside `onMapTap`/`onMarkerTapped`/etc.) -> sets `pendingDetailType/Id`.
- In the map JS (`convoy_map.html` + `grouptrack_map.html`): the feature click handler (currently `bindPopup`/`onEachFeature`) calls `AndroidBridge.onArtifactTapped(type,id)` — feature carries type+id in GeoJSON props.
- **GATE:** `onMapTap` is ALREADY route-building's vertex handler. Feature-click vs empty-map-click fire separately in Leaflet, so feature->detail and empty-map->route-vertex coexist. **Suppress artifact-detail-on-tap while route-build mode is active.** Paired edit, both HTMLs. (Confirm exact filenames: `ls app/src/main/assets/*.html`.)

## TASK 4 — WORK-WITH-ARTIFACTS -> FAB + accordion-close (removes the on-map WWA bar)
Convert the on-map "Work with Artifacts" BAR into a FAB in the right-edge icon column (search · artifacts · help).
- **FAB launches the panel ALREADY EXPANDED** (accordion open).
- **The accordion-collapse control becomes CLOSE** — dismisses the whole panel back to map + FAB (calls existing `onDismiss`, `ConvoyArtifactsPanel.kt:64`).
- **Removes the always-on WWA bar from the map.** Drag handle gone. Panel CONTENT (grid/rows/+ROUTE/import) unchanged.
- **SAME on BOTH convoy AND planning** — shared, not duplicated.

## ORDER FOR TOMORROW
1. Session start: recommit + upload xrefs + re-pull fresh files (ConvoyScreen, ConvoyMapViewerScreen, ArtifactListPanel, ArtifactDetailPanel, UnifiedSearch, both map HTMLs).
2. **Build the launcher->panel truth table. Show Fred. No code before this.**
3. TASK 1 + TASK 2 -> one build -> verify Carto Type from search FAB AND select/edit list on BOTH maps -> commit.
4. TASK 3 (map artifact-tap -> detail) -> build -> verify -> commit.
5. TASK 4 (WWA -> FAB + accordion-close) -> build -> verify -> commit.
6. Then: queue + survey + upload_queue · remove RouteCreate orphan · manual rewrite + captures BUNDLED · cut 2.5 AAB (banked fallback) · lead-track rewrite LAST.

## CARRIED ITEMS (full detail in the Living Checklist — do NOT re-derive)
- **Lead-cart tracking REBUILD [2.1]** — one lead / one track / snap-2 100yd / per-cart per-second GPS replacement. MUST-SHIP; attempt LAST after AAB banked. Demolition+rebuild planning doc to produce. Authority: `GroupTrack_LeadTrackReplacement_Spec.docx` (May 31) + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md`.
- **Tile downloads — batch formats for performance** — V2.5 interim = settable concurrency (default 4, max 6, throttle guidance); 2.6 = batch transfer (.tpkx / PMTiles) + AWS staged/hosted + Esri thresholds. App crashes past 3 concurrent -> root-cause is 2.6.
- **Track survey on STOP [7.5]** — V2.5 collect-now; schema finalized (extension db, enjoyment 1-5 + ride_again); feeds upload_queue; connected to queue-panel upload/download toggle — build together.
- **Documentation** — manual edit-in-place on `app/src/main/assets/grouptrack_manual.html` (pristine cookbook base); rewrite WWA section around icon navigation, add search FAB + one detail panel + Carto Type + map-tap; release notes realign; captures bundled in AAB. Done LAST, before the AAB cut.

## BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm-daemon incremental ~14-18 min; cold/clean ~40-55 min — a NEW gradle daemon ("busy daemon could not be reused") = cold = slow; let it finish to warm the daemon for the next build).
- **GREP-CONFIRM a patch is on disk before building** (`grep -n "<marker>" <file>`) — prevents building the wrong version (cost a 55-min build 06-19).
- **If incremental changes don't appear (same APK timestamp): `./gradlew clean`, confirm the APK timestamp moved before trusting a test.**
- Install: `adb -s 8624SBCEDF00001789 install -r -d app/build/outputs/apk/google/release/app-google-release.apk` (Droid 1).
- Line endings: ConvoyScreen.kt + ConvoyMapViewerScreen.kt CRLF; ArtifactDetailPanel.kt + ArtifactListPanel.kt + UnifiedSearch.kt + ConvoyArtifactsPanel.kt LF. Patches detect at runtime + count==1 guard.
- NO sqlite3 on device; `run-as` blocked on release (use debug build on Droid 2 to pull DB if needed).

## TREE — parked (never `git add .`)
`.bak_*` files, `ConvoyScreen.kt.bak_move`, `utah_trails_stgeorge.geojson`(+.bak), `grouptrack_manual.html`, `grouptrack_release_notes.html`, `grouptrack_spatial.db` (117MB), `docs/.tmp.driveupload/10630`. Tree-cleanup of the `.bak_*` files pending. Patches J/K/M leave `ArtifactDetailPanel.kt` modified (uncommitted, intentional).
