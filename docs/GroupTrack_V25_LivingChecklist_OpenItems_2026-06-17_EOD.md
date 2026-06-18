# GroupTrack V2.5 — Living Checklist / Open Items
**Updated:** 2026-06-17 (~10:45 PM EOD)
**Branch:** feature/convoy-event-ride · **Committed HEAD:** `009b158aa`

---

## ⭐ TODAY'S OUTCOME (2026-06-17) — SEARCH→DETAIL SEPARATION SHIPPED; FIT POSITIONS BUT DOESN'T RETAIN SELECTION

Hard day. At noon everything was humming **except** retaining the FIT-selected trail. The afternoon did not land that retention fix, and the FIT trail-name in JSON is still blank. A diagnostic build broke all-artifact draw and was reverted — which briefly looked like a day's work was lost, but **git proved nothing real was lost** (only an 11-line `fitBounds` block was uncommitted; the whole-day shared-fn work was already committed in `fe8a0849a`). The day ended **safely committed** as `009b158aa`: maps position on FIT, all artifact types populate their lists, and manual filter-select works.

### ✅ COMMITTED TODAY — `009b158aa` (7 files, 381 insertions)
- **SEARCH→DETAIL SEPARATION.** Artifact name-search used to open the sel/edit list panel (`ArtifactListPanel`, gated by `activeListType`) only to reach the detail+FIT UI embedded inside it. That list's `onDismiss` recomputed state from `selectedArtifactIds` (empty on a search) and called `saveConvoyState` → **clobbered FIT's JSON.** Fix: extracted the detail UI into a new standalone **`ArtifactDetailPanel.kt`** (self-loads via `getArtifactDetail`, owns FIT, FIT auto-closes the popup, no list-state save). Search `onResultClick` now sets `pendingDetailId` + `pendingDetailType` only and mounts the standalone panel — the list never mounts on search, so the clobber is gone. Wired on both maps.
- **FIT `fitBounds` map-move RESTORED** in `drawPersistedState` (pad ~15% → `fitBounds([fS,fN],[fW,fE])` before the `Thread{processViewport}`). FIT now flies the convoy map to the artifact bbox on device.
- **FIT name + onDismiss reseed** — committed but **not the right fixes** (see open items). The name still writes `""`; the ungated reseed is a band-aid to be replaced by the real retention fix.

### 🐞 STILL OPEN — FIT-PRESELECT DOES NOT RETAIN THE SELECTION (job #1 next session)
FIT writes a correct JSON (Tracks `state:2`, fitted row `checked:true`, bbox Utah, map flies there) but the Work-with-Artifacts panel shows the track **off/unselected** and it doesn't draw. **Manually selecting the same track from the filter works.** See the detailed section below.

---

## 🐞 FIT-PRESELECT RETENTION BUG — DIAGNOSIS (fix first next session)

### Symptom
FIT a track → `convoy_panel.json` is correct: `Tracks state:2`, the fitted row `checked:true`, other types `0`, Utah bbox, and the map flies to Utah. **But** the panel shows the track unselected and it does not draw. Manual filter-select of the *same* track draws and selects perfectly. Same failure on both maps → shared root.

### The whole selection chain is correct (verified by reading)
- `readMap` parses `checked` with `optBoolean("checked", false)` — matches the JSON boolean `true`. ✓
- `checkedIdsFor` returns the checked ids only when `state == 2`. ✓
- `rowsFor` builds `Row(id, "", true)` from `checkedIds`. ✓
- Grid `isSelected = id in selectedIds` — id-based. ✓
- `processArtifact` SELECTED filter is `raw.filter { it[idField] in checkedIds }` — **id-based, NOT name**, so the blank name does not break display. ✓
- Net: a row draws iff **(row id ∈ checkedIds) AND (state != DS_OFF) AND (zoom ≥ minZoom)**. The query rows carry no checked/state field — display is decided entirely by the `checkedIds` set + the per-type state.

### Root cause
**FIT updates the SAVED side (JSON) but never updates the LIVE side (in-memory vars) that the screen actually draws from.** There are two draw paths, and **both are by design**:
- **(A) `drawPersistedState`** — JSON-sourced (saved/restore + `fitBounds`). FIT calls this.
- **(B) `ConvoyScreen` onViewportChanged path** (~786-800) — builds states/selectLists from the **in-memory live vars** (`trackState`, `trackCheckedIds`, …) so the user's live selections survive zoom/pan.

On FIT, the JSON says `state:2` but the in-memory `trackState` stays `OFF` (never updated — the existing reseed at ~771 convoy / ~611 planning is gated by `lastMapProcessed != "convoy"`, so it skips a same-map FIT). When path B redraws on the next viewport event, it uses `OFF` → `processArtifact("Tracks")` hits the `DS_OFF` early-return and never draws. (A diagnostic confirmed this: it logged zero `type=Tracks` lines, meaning Tracks bailed at the `DS_OFF`/minZoom early-return before the log point.)

### The fix (Fred's design — implement next session)
**The two paths stay. Do NOT collapse them, do NOT make path B read JSON, do NOT use a reseed band-aid** — path B exists on purpose to protect live selections during zoom/pan.

FIT must update the **live** in-memory selection the same way a **manual filter-select** does (manual select works). Concretely:
1. **Build the rows** — run the query (the same one the normal flow uses).
2. **Emulate the screen's row-selection from the JSON** — do to the fitted row exactly what a manual tap does to the live selection state, driven by the JSON `checked` id (not a tap).
3. **Run the normal draw.**

FIT stops being its own draw path and reuses the existing select-then-draw machinery, sourcing "which row" from JSON instead of a tap. **First task next session: find what a manual filter-select does to the live in-memory selection state, then make FIT do the same.**

### Also open
- **Blank trail-name in FIT's JSON row.** Id is correct (matches detail), the name is in the DB, but FIT writes `""`. Tried sourcing from the in-bbox viewport row and from `getArtifactDetail`; still blank. Cosmetic (match is id-based), but fix it. (Verify which name-version actually landed — a `grep inBbox.firstOrNull ConvoyArtifactOps.kt` returned 0.)

---

## ⚠️ INCIDENT LESSON (do not repeat)
A debug-log diagnostic patch in `SpatialDisplayManager.kt` produced a build where **nothing drew** (no artifacts of any type). It was reverted with `git checkout HEAD -- SpatialDisplayManager.kt`. The scare ("a day's work gone") was false — `git diff fe8a0849a -- SpatialDisplayManager.kt` showed the only uncommitted delta was the 11-line `fitBounds` block (since restored). **Lessons:** (1) before reverting an uncommitted file, run `git diff <last-commit> -- <file>` to KNOW the scope. (2) Don't stack debug-log builds; reason from ONE clean diagnostic. (3) A broken diagnostic build wastes a 20-40 min cycle and leaves the device on the broken apk until rebuild. (4) Commit working states promptly.

---

## 🎯 TOMORROW'S PLAN (locked order)
1. **COMPLETE FIT** — implement the build-rows → emulate-screen-select-from-JSON → normal-draw retention fix; fix the blank trail-name; device-prove (FIT a track → it stays selected + draws without manual re-select); **commit.**
2. **MAGNIFYING-GLASS UNIVERSAL SEARCH FAB** — one draggable magnifying-glass FAB on both maps replacing the three current searches (spec below / handoff doc).
3. **ARROWS → PIXEL-BASED ON TRACKS** — [3.9a]: change arrow decorations to pixel-based spacing + neon green, on tracks; two CRLF HTMLs (`convoy_map.html` + `grouptrack_map.html`) + `polylineDecorator`. Fred: "should be quick."

---

## ⛔ MAGNIFYING-GLASS UNIFIED SEARCH ([2h.1])
Full spec in `GroupTrack_Search_Consolidation_Handoff_2026-06-16.md`. Summary:
- **Icon DECIDED: magnifying glass** (not binocular). Frees map real estate.
- ONE draggable magnifying-glass FAB on BOTH maps replaces THREE current searches.
- **REMOVE launch points (KEEP engines):** (1) planning area search (ConvoyMapViewerScreen ~365-405, Geocoder→setView); (2) convoy location search (ConvoyScreen `locationSearchQuery/Results/Error` @267-269); (3) artifact search (ConvoyScreen `onSearch`→`searchByName` `4f7abbbb7` NON-SPATIAL→assignNameSequence).
- **KEEP/reuse:** `searchByName`, ArtifactSearch.kt numbering (ORDER BY name COLLATE NOCASE, geom_hash), Geocoder→setView, the standalone detail panel, FIT.
- FAB: selector (Area/Trail/Track/Route/Waypoint) + text box. Area→setView. Artifact→`searchByName`→results→**ArtifactDetailPanel**→FIT.
- Open Qs: drag-position persistence per-map? convoy scope? popup-vs-direct-detail?

## ⏳ MAP-POPUP / ARTIFACT-TAP → DETAIL PANEL
Touch an artifact on the map → route to the standalone `ArtifactDetailPanel` (which owns FIT). **Open design Q:** skip the popup banner and go straight to detail on tap (detail shows name + far more) vs popup-as-peek. Wiring: JS click handler on the artifact in BOTH map HTMLs (they drift) + Android `@JavascriptInterface` bridge to open detail with (type, id), passing mapKey + fitWebView.

## 🧹 DETAIL-EXTRACTION CLEANUP (holistic, backlog)
`ArtifactListPanel` still embeds its own detail for the sel/edit row-tap — route that row-tap to the shared `ArtifactDetailPanel` too. Implement the `ConvoyArtifactOps` Pass-1 log stubs (rename/delete/toRoute/toTrack/upload/download/changeType/editPoints/addAlias/setTrailhead). `fit()` is the only real op.

---

## OTHER OPEN (backlog)
convoy_map.html `drawTrack`/`clearMarkers` not defined; [6.2] geojson asset removal; [1.2] sliceLine whole-trail; [1.5] auto-save+terminate on map-switch; import [4.x]; queues [3.3]; [3.1b] GPS-recenter button (planning); docs [9.x]; BLE [10.1]. **Tree cleanup:** stray files to remove — `ConvoyScreen.kt.bak_move`, `utah_trails_stgeorge.geojson.bak`, `grouptrack_spatial.db` (117MB — never commit). DEFERRED: GeoPackage national, V3.0, paywall.

---

## DESIGN CONTEXT (carried forward)
- **Two draw paths are BY DESIGN.** (A) `drawPersistedState` = saved/restore from JSON (golden for saved state). (B) onViewportChanged path = in-memory live vars, preserves user selections across zoom/pan. New actions like FIT must update the LIVE side via the existing select mechanism, not bypass or collapse it.
- **Map-purpose model:** Convoy = live/location (GPS, proximity, session-only). Planning = deliberate/identity (name search, fit, persisted frame across launches).
- **FIT toggle-list:** fit writes the full bbox list with ONLY the fitted one `checked=true`; the SELECTED draw-filter draws only the fitted one; unchecked rows are the quick toggle-on list. List + draw derive from the SAME bbox query → consistent.
- **Winning approach:** own behavior in the SHARED component; callers pass DATA not BEHAVIOR. Revive existing shared fn, re-wire ONE map at a time, BUILD+DEVICE-PROVE+COMMIT each step. Holistic fixes, not per-map band-aids ("landmines") — Fred lives in this code.

---

## TREE STATE
- **Committed HEAD `009b158aa`** (search→detail separation + FIT name/state + fitBounds restore). Chain: `fe8a0849a` → `cec3a3e4a` → `c0259d2ca` → `009b158aa`.
- On-device proven at this commit: maps position on FIT, lists populate, manual select works. FIT-preselect retention still open.
- Parked (never git-add): `M utah_trails_stgeorge.geojson`, `?? grouptrack_manual.html`, `?? grouptrack_release_notes.html`, `?? *.geojson.bak`, `?? ConvoyScreen.kt.bak_move`, `?? grouptrack_spatial.db`, `D docs/.tmp.driveupload/10630`. Commit only named files. Never `git add .`.

## DEVICE / BUILD QUICK-REF
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (~11–42 min)
- APK: `app/build/outputs/apk/google/release/app-google-release.apk`
- Install: `adb -s 8624SBCEDF00001789 install -r -d <apk>` (Droid 1 = `8624SBCEDF00001789` field/real-GPS · Droid 2 = `24039703201775` dev)
- JSON pull: `MSYS_NO_PATHCONV=1 adb -s 8624SBCEDF00001789 shell cat /sdcard/Documents/GroupTrack/state/convoy_panel.json` (or `planning_panel.json`)
- Logcat (non-blocking dump): `adb -s 8624SBCEDF00001789 logcat -d -s ArtifactOps ConvoyMap | tail -20` (`-c` to clear). LIVE logcat BLOCKS — use `-d` dump or a 2nd window.
- **LINE ENDINGS:** .kt files MIXED CRLF/LF, even within one file. Verify raw bytes / Python byte-check (not sed/awk/cat-piped). Patches detect newline at runtime + count==1 guard.
- Patch flow: Claude files → present_files → Fred downloads to `/c/Users/kixaz/Downloads/` → `python3 /c/Users/kixaz/Downloads/<name>.py`.
- Revert one file: `git checkout <hash> -- <file>` — **run `git diff <hash> -- <file>` FIRST to know the scope.** NO sqlite3 on device.

## EOD DOCS STILL PENDING
Release notes `grouptrack_release_notes.html` + manual/cookbook `grouptrack_manual.html` — add V2.5 entries (shared-draw consolidation, convoy persistence, FIT, search→detail separation) + document behavior. Edit in place from prior baseline, output full updated files. (Untracked stubs already in tree.)
