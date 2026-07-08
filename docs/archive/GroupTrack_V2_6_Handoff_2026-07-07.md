# GroupTrack V2.6 — Handoff / State of Play
**As of:** 2026-07-07 EOD · **Prev living-docs update:** 2026-07-06

> Single orientation doc for the current milestone. V2.5 shipped to Play Store 06-24 (`c603bc3f0`). Work is on **V2.6**. **Today (07-07): map-download standardization completed and certified for all three one-up/batch origins. Tomorrow: begin tile-storage migration (loose tree → spatial/MBTiles model).**

---

## What got done today (07-07) — download standardization COMPLETE

**Goal:** take the working area-download model, modularize it, and route **all** map-download origins through the *same* screen + same process. Achieved and certified on Droid 2 for three origins.

### The shared spine (now used by every origin)
```
BOX 1 (source selection)  →  onProceed(bbox, selectedSlots, replace)
        ConvoyDownloadConfirm — presents map sources as checkboxes + replace toggle.
        bbox travels THROUGH box 1 as a payload param (in and back out) — never scope-read.
        Estimate (tiles/MB) is OPTIONAL: shown when known (area), hidden when 0 (batch).
                          │
BOX 2 (enqueue processor) →  DownloadQueueManager.submitDownload(context, n,s,e,w, selectedSlots, replace)
        Shared, screenless, model-layer. Loops slots → enqueueArea (grid divide, or
        single-job fallback for small boxes) → per-cell QueueEntry → launchWorker → doWork.
```

### The three certified origins
1. **AREA** — draw area → box 1 → `submitDownload`. (`ConvoyMapViewerScreen` onProceed handler.)
2. **TRACK (artifact detail)** — SAVE MAPS / download-maps on a track's `ArtifactDetailPanel` → derive bbox via new `SpatialDbManager.getTrackBbox(hash)` → **dismiss detail panel** → box 1 → `submitDownload`. (`ConvoyMapViewerScreen:onDownloadMaps`.)
3. **IMPORT (batch)** — track import: select GPX + check "maps" → tap Import → **box 1 popup** (bbox-blind, source selection only) → per-track on **INSERT**, `getTrackBbox` → `submitDownload` with the up-front-selected sources. (`ConvoyTrackImportScreen` + `ConvoyTrackOps.importGpxAllArtifacts`.)

### The bugs found and fixed on the way (all committed)
- **Bbox payload loss (scope-vs-parameter).** The original area handler read `downloadBbox` *inside* a deferred `Thread{}`, AFTER `showDownloadConfirm=false`/`showDownloadPanel=false` state flips → by thread-run time the value was stale/empty → jobs built with zero coordinates → `gridCells(0,0,0,0)` → 0 tiles, nothing downloaded (the "280k estimate but zero jobs" symptom). **Fix: bbox now travels THROUGH box 1 as a payload param** (added `bbox: DownloadBbox` in; `onProceed` returns it; handler uses the lambda's `bbox`, not a scope re-read). Captured at box-1-render time when valid.
- **Tile count = 0 at enqueue (red queue display).** `enqueueArea` built every `QueueEntry` with `totalTiles = 0` (placeholder); worker computed the real count but never wrote it back → queue showed red zeros even while downloading. **Fix: compute at enqueue** — `totalTiles = ConvoyTileCalculator.calculateTiles(cell).size * slotLayers`, where `slotLayers = getDownloadSources().filter{it.first==slotName}.sumOf{it.second.size}`. Matches the worker's own math.
- **Track download was headless/all-sources.** Old `downloadMapsForTrackHash` used plain `enqueue` (global `getDownloadSources()`, no box 1). Now split: `getTrackBbox` derives the padded bbox (same hash→row→½-mile pad math), and the UI feeds box 1 → `submitDownload`. (The old `downloadMapsForTrackHash` still exists; the INSERT path in import was repointed off it.)
- **Box 1 occluded by detail panel.** Track download set `showDownloadConfirm=true` but the `ArtifactDetailPanel` stayed on top. **Fix:** null `pendingDetailId`/`pendingDetailType` before showing box 1.

### THE DECOMPOSITION SCOPE RULE (durable — this bit us twice)
> **When extracting an internal function (that shared a screen's scope) into a called external module, every state variable it read from scope becomes an invisible dependency. The data must travel as an explicit PARAMETER / MESSAGE PAYLOAD carried into the call — never re-read from a scope that no longer exists when the deferred/threaded/detached job runs.** Do a scope/state audit UP FRONT when decomposing (enumerate every scope var the body touched; confirm each is now a payload param), not one crash at a time. The code always *looks* right — statements are identical — but the *timing* of the read changes, and shared mutable state can be reset/recomposed underneath. For genuinely disparate/unattended processes (batch), carry the data as a message/payload the consumer owns, not a scope reference.

---

## V2.6 version badges → 2.6 (done)
Three user-visible badges bumped V2.5→V2.6: `ConvoyDisplayPanel.kt:131` + `:140` (`versionTag`), `ConvoySettingsScreen.kt:254` (`GroupTrack v2.5`→`v2.6`). Play-store versionName/versionCode are **git-controlled** (computed in `build.gradle.kts` 65/71) — NOT hand-set.
**Left alone (logged for 3.0):** `ConvoySearchByAreaScreen.kt:58` (`"V2.5"`) + `:72` (`"Coming in V2.5"`) — this is a **3.0 ride-management placeholder**, unreachable from 2.6 map surfaces (verified: neither ConvoyScreen nor ConvoyMapViewerScreen launch it; reachable only via `GroupTrackTheme:294` ride menu = 3.0). 3.0 pass: repoint the menu to the real search-by-area (FAB Area mode, which IS implemented) or delete the stub.

---

## Open items carried forward
- **INSERT-only gate on import maps** (deferred bug). Import maps download fires only in the `AddOutcome.INSERT` branch of `importGpxAllArtifacts` → re-importing an **existing** track with "maps" checked downloads **nothing** (it's a DUPLICATE/ALIAS, not INSERT). "MAPS checkbox ignored when track already exists." Fix: move the maps call out of the INSERT-only branch so DUPLICATE/ALIAS-with-maps-checked also downloads. Not required for standardization; deferred.
- **Leftover debug log** `submitDownload IN: N=...` still in `submitDownload` — harmless, currently useful, remove after storage work settles.
- **ConvoyScreen artifact-detail host** — `ArtifactDetailPanel` is ONE component rendered by TWO hosts (`ConvoyMapViewerScreen:1226`, `ConvoyScreen:1824`). Only the **map-viewer** host has box 1 wired for track download. ConvoyScreen host has no box 1 state and wasn't wired this pass. If ConvoyScreen's detail panel is reachable in 2.6 and needs track-map download, either add box 1 there or make box 1 a shared overlay both hosts embed. (Deferred — the map-viewer host covers the certified flow.)
- **`.gitignore`** added for `ersi api key/`, `*.db`, `*.gpx`, `*.png` (keeps the Esri key + local artifacts out of commits). NOTE the `*.png` rule will ignore *new* image assets too — narrow it if real drawables need tracking.

---

## TOMORROW — tile storage: loose tree → spatial / MBTiles model

**Same transfer method, new storage.** The download pipeline (box 1 → submitDownload → divide → worker fetch) is now standardized and certified and does NOT change. What changes is the **write landing** — the "miracle happens" step at the destination end: instead of the worker writing loose tiles to `maps/tiles/<slot>/z/x/y`, tile bytes go into the **spatial/MBTiles model** (SQLite container, tiles table keyed z/x/y, blob).

**Fred's first move (agreed): stake a small area and inspect what the storage model creates.** Reasoning about it is guesswork until we see the artifacts. Today the loose-tree delivery process managed the whole directory/subdirectory tree (SAT, SAT_LABELS_TRANSPORT, SAT_LABELS_PLACES, TOPO, TOPO+ with z/x/y). The new storage's structure is the open question:
- One `.mbtiles` per TYPE (= cache_dir = old dir name), or one container with a source column?
- Where do they land? Does the delivery process still create per-source structure, or does the container replace it?
- Base + overlays each their own DB, composited at the Leaflet layer? (Prior design note.)
- The state-JSON / resolver that maps a request to storage.

**Method:** small-area download on **Droid 1** (the cleared MBTiles device) → pull the resulting `.mbtiles` to PC (Droid 1 has no sqlite3) → open, inspect schema + tile count → reason about the redesign from real artifacts, not memory.

**Baseline for comparison** (capture on Droid 2's loose tree before migrating):
`adb -s 24039703201775 shell "ls -R /sdcard/Documents/GroupTrack/maps/tiles/ | head -40"` — the current structure you're migrating FROM.

Full tile-storage design (MBTiles-only hard cutover, unified spine, Pass 1 = redirect the write / Pass 2 = Esri batch transport, schema, `.tpkx` unpack) is in `GroupTrack_V2_6_TileStorage_Recap` — unchanged by today's work; today just *finished* the "standardize all origins through the area submission" prerequisite that recap's Step 0 called for.

---

## Devices / environment
- **Droid 1** `8624SBCEDF00001789` — field/real-GPS; **USB temperamental** → prefer wireless ADB / Termux / X-plore. Primary MBTiles test device (tiles cleared). No sqlite3 → pull `.mbtiles` to PC.
- **Droid 2** `24039703201775` — dev; has the full loose tree (SAT, SAT_LABELS_TRANSPORT, SAT_LABELS_PLACES, TOPO, TOPO+). Today's standardization certified here.
- **Repo:** `C:\Users\kixaz\Meshtastic-Android`, branch `feature/convoy-event-ride`. **Package path `com/geeksville/mesh/convoy/`** (NOT `com/grouptrack/android/`). Package id `com.geeksville.mesh`.
- **Build:** `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`. Cold ~36min; **warm ~10-13min**. APK: `app/build/outputs/apk/google/release/app-google-release.apk`.
- **Install:** `adb -s <serial> install -r <apk>`.

## Working style (reinforced today)
- **One command at a time** — Fred executes each, pastes result, then next.
- **Patches: surgical `sed` for single lines; temp-file + short Python script for multi-line blocks.** Heredoc pastes with long multiline strings REPEATEDLY splice/corrupt in the terminal (the echo garbles mid-block) — but content usually lands on disk correctly; ALWAYS verify with `sed -n`/`cat` after any patch. Python needs Windows paths (`C:\Users\...`); `sed`/`grep` use `/c/...`.
- **Impact analysis first** — reference `field_crossref_raw.txt`, `where_used_raw.txt`, `function_universe_raw.txt`, `navigation_xref.txt` before changes. (function_universe indexes signatures/key-lines only — real file reads needed for logic.)
- **Certify each item on device before reuse/commit.** Commit per verified item; explicit `git add <files>` (never `-A` — the Esri key is untracked).

---
*Handoff — 2026-07-07 EOD. Download standardization (area + track + import) complete and certified. Tomorrow: stake a small area, inspect the spatial/MBTiles storage the delivery process builds.*

---

## Doc-set status note (manual clarification — 07-07)
THREE manual artifacts exist; don't confuse them:
- **`GroupTrack_V24_UserManual_Complete.pdf`** — the last COMPLETE published manual (V2.4, May 2026). Reference-style (feature-by-feature). Baseline.
- **`GroupTrack_User_Guide_V2_5.pdf`** (V2.5 Cookbook Draft) — the STRUCTURED current manual, built from `navigation_xref.txt` (40 screens) + V2.5 feature docs, cookbook style (how you get there / what you do / where it leads). Chapters 4-6 = current V2.5; Chapters 1-3 = 3.0 preview (banner'd). Marked DRAFT/VERIFY throughout (labels/nav need app authentication). **This is the manual today's download work lands in** — see below. Trail counts: ~49,000 trails / 591 trailheads.
- **`app/src/main/assets/grouptrack_manual.html`** — **THE TRUE, SHIPPED, CANONICAL MANUAL** (confirmed 07-07, pulled from app assets). Interactive HTML: collapsible L1 map &rarr; L2 launch-point &rarr; L3 sub-flow &rarr; L4 screen hierarchy, search box, reference images. Titled "GroupTrack V2.5 Manual". This is the FINISHED product the V2.5 Cookbook PDF was the rough draft FOR. **Source of truth** &mdash; always pull the manual from app assets (last release, no changes = asset is authoritative). The `grouptrack_manual.html` carried in the doc set is this asset copy.

**Manual revision pending (map onto the V2.5 Cookbook's chapters):**
1. **Uniform source-selection popup** (today's change) — track download (artifact detail, the "tap a name → detail panel" path in Ch. 4 Work with Artifacts) and import-with-maps (Ch. 4 IMPORT ARTIFACTS / Track Import) now present the SAME source-selection screen (box 1) as area download. Note the consistent source-select step in Ch. 4 + Ch. 5 (Offline Maps & Downloads). Minor.
2. **Tile-storage rewrite** (tomorrow's migration) — the loose `source/zoom/x/y.png` structure (V2.4 manual Ch. 11) becomes the MBTiles spatial-DB model (one .mbtiles per type, ~35-40% smaller). Material change; pairs with the release-notes upgrade box (install X-plore → delete maps → re-download). Revise + screenshot when Pass 1 ships, then re-point the release-notes manual link. In the Cookbook this touches Ch. 5 (Offline Maps & Downloads).
3. **Cookbook is still DRAFT/VERIFY** — before publishing, walk each screen in-app, confirm button labels (draft uses code labels), verify each 'Leads to', fill thin screens (Explore/Profile/MyOrganizers/Subscription), add per-screen screenshots. Regenerate nav_xref to keep current.

The V2.4 PDF's Chapter 11 + Chapter 12 (Tile Sources) + Appendix C (which is "Coming in V2.5") are the sections most out of date relative to shipped V2.5/V2.6 — worth a pass when the manual is revised.
