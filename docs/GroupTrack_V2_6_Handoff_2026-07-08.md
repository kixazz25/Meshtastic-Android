# GroupTrack V2.6 — Handoff / State of Play
**As of:** 2026-07-08 EOD · **Prev living-docs update:** 2026-07-07

> Single orientation doc for the current milestone. V2.5 shipped to Play Store 06-24 (`c603bc3f0`). Work is on **V2.6**.
>
> **Today (07-08): Pass 1 tile-storage migration (loose tree → MBTiles) + WebP compression BUILT, VERIFIED on Droid 1, COMMITTED. Storage ~55% smaller (measured). V2.6 now split into release milestones 2.6a / 2.6b / 2.6c. Tomorrow: 2.6b — Esri batch transport (tiles Pass 2).**
> **Yesterday (07-07): map-download standardization completed and certified for all three origins (AREA / TRACK / IMPORT) — see the section below, retained in full.**

---

## 07-08 — what got done (Pass 1 + WebP, committed & verified)

**Commits on `feature/convoy-event-ride`:** `55ce56239` (MBTiles cutover + offline-label fix) · `ccea5977e` (WebP q80 + overlay passthrough + convoy download-panel wiring + detail-close) · `dd52de720` (cleanup). *~110 commits ahead of origin — a `git push` for offsite backup is recommended.*

**Milestone split (07-08 decision):**
- **2.6a — known-good FALLBACK release** (bank to Play Store before the risky transport rewrite): Pass 1 MBTiles cutover + WebP + offline-label fix + convoy download-panel wiring + remaining UI fixes. *"No-radio" is NOT the defining trait of 2.6a — it just happens nothing here touches radio. The ONLY radio-dependent work in all of V2.6 is the lead-track rewrite (2.6c).*
- **2.6b — Esri batch transport (tiles Pass 2)** — the risky transport rewrite, built alongside + gated. **Tomorrow.**
- **2.6c — lead-track / tick / my-cart rewrite** — the only radio work; own milestone, last.

**Pass 1 MBTiles cutover — SHIPPED.** Loose files → one `.mbtiles` per type via new `MBTilesStore.kt`. Every write, read, helper, and coverage-highlight redirected. Verified Droid 1: SAT base + overlays render online AND offline. Storage = **raw z/x/y, NO TMS flip** (`scheme=xyz`; write & read agree; matches old loose paths; Esri `/tile/{z}/{y}/{x}` reordered by the intercepts). This is the "write landing / miracle-happens" step 07-07 pointed at — now built.

**WebP compression — VALIDATED (new `TileCodec.kt`).** Base = lossy WebP **q80**; overlays = **PNG passthrough** (re-encoding sparse-alpha label PNGs to WebP bloats them +167%; store as-is). Dispatch by `layer.role`. Measured: SAT base **9.7 KB/tile (WebP q80)** vs **21.5 KB/tile (Droid 2 loose JPEG, 344,061-tile real baseline)** = **~55% reduction**, clears the 35–40% goal. **q95 tested & REJECTED** — lossy-to-lossy re-encode of already-JPEG source inflates ~5× (19.5 KB/tile); comment left in `TileCodec` so nobody retries. q80 visually lossless at z19 on 11" field screen (Fred confirmed on-device). Learning: re-encoding already-compressed sources fights the source; "drop a zoom level beats raising quality."

**Convoy download-panel wiring — DONE (closes a 07-07 open item).** This is exactly the 07-07 carried-forward item *"ConvoyScreen artifact-detail host has no box 1 wired."* `ArtifactDetailPanel` is shared and its `onDownloadMaps` is a **caller-supplied callback**; ConvoyScreen was passing the OLD direct-queue value (`downloadMapsForTrackHash`) while the viewer passed the standard box-1 value. Fix: ConvoyScreen now passes the same value (get bbox via `getTrackBbox` → `downloadBbox` + `showDownloadConfirm=true` → `ConvoyDownloadConfirm`) AND closes the detail panel first (nulls `pendingDetailId`/`pendingDetailType`). Pure caller-side in `ConvoyScreen.kt` — shared components untouched. So the box-1 spine now covers BOTH artifact-detail hosts.

**TOPO+ — NOT a bug.** "TOPO+ won't display below z16" = USGS USA_Topo_Maps source coverage (no data above ~z15), confirmed identical online. The `+` round-trips fine through `convoy://tiles/TOPO+/...` and the `.mbtiles` filename.

### TWO DESIGN RULES set 07-08 (forward-looking; sibling to 07-07's DECOMPOSITION SCOPE RULE)
1. **No nullable-as-shortcut.** A nullable/optional param (`? = null`) must have its justification explained *before use*. `? = null` defers the "is every caller wiring this correctly?" analysis to silent field discovery — exactly how the convoy download callback silently ran the wrong behavior. Optional allowed only with a documented reason a caller legitimately omits it.
2. **Evaluate at point of contact, not retroactively.** Don't audit existing code for rule-1 violations; but whenever *touching* code with a nullable param, justify it or fix it then.

### HARDENING backlog (logged 07-08, not done)
- **`insertTile`/`db()` silent-failure** — when `db(type)` fails to open it returns null and every `insertTile` silently no-ops (loop still counts tiles "done"). Cost most of an afternoon (SAT "1140 tiles in 1 second" wrote nothing). Must **fail loudly** during download. Do before/with 2.6b (batch hammers inserts).
- **`deleteSource` handle hygiene** — deleting `.mbtiles` while the app holds cached open handles wedges scoped-storage → all writes fail `SQLITE_IOERR_FSTAT` until reboot. Close cached handle before delete + reopen clean. (Root cause of the 07-08 device chaos — a test artifact from repeated `adb rm`, but the app's replace/delete flow could hit it.)
- **map-source max-zoom self-healing** — per-source `maxNativeZoom` in `map_sources.json`, threaded from Kotlin through `setTileUrl`, drop the `isZ17`/label logic in the map HTML. Fixes TOPO+ "not available" above coverage, generally.
- **`.nomedia` / Play Store scan** — `SQLITE_IOERR` log showed `com.android.vending` touching tile DBs; verify `.nomedia` suppresses tile-DB scanning.
- **dead `tilesDir` lines** — 3 orphaned `File(TILE_DIR,"SAT/1x")` declarations (cosmetic, compile-warn).
- **map compression quality SETTING** (download panel / map controls) — a runtime dial for base WebP quality so it can be A/B'd on-device without a rebuild (Fred can already tune fast via replace-download of a small area). DEFERRED 07-08: build AFTER the WebP core is committed (depends on the codec being correct), then tune with the small-area loop. Quality is already a `BASE_QUALITY` constant in `TileCodec` → becomes a stored pref passed into `encode()`. Legit 2.6a-candidate feature. (q95 already proven bad → practical range ≤80.)
- **ARCHITECTURAL (own pass, "not today") — panel-owns-download-behavior.** The robust fix for the convoy download bug: move the standard download behavior INTO `ArtifactDetailPanel` (or make `onDownloadMaps` a required, non-null param) so callers CANNOT diverge. Today's caller-side alignment closes the symptom; this closes the class (a caller-supplied callback + nullable default is what let the behavior silently differ). Candidate for the 2.6b cycle or later — test without release pressure. This is the deeper form of the two 07-08 design rules.

### Remaining 2.6a items (next sessions, no radio)
Route+ state persistence (next-session pickup — refined spec below), aliases UI, tracks rename→human-readable swap, the cleanups above. **Dropped from scope:** detail-panel wiring (done — track map-popup), recorder fixes (no known issues).

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
- **ConvoyScreen artifact-detail host — ✅ RESOLVED 07-08.** (Was: only the map-viewer host had box 1 wired; ConvoyScreen host had none.) ConvoyScreen's `onDownloadMaps` now passes the standard box-1 value (get bbox → `downloadBbox` + `showDownloadConfirm` → `ConvoyDownloadConfirm`) + closes the detail panel first. Box-1 spine now covers BOTH hosts. Pure caller-side; shared `ArtifactDetailPanel` untouched.
- **`.gitignore`** added for `ersi api key/`, `*.db`, `*.gpx`, `*.png` (keeps the Esri key + local artifacts out of commits). NOTE the `*.png` rule will ignore *new* image assets too — narrow it if real drawables need tracking.

---

## Tile storage — Pass 1 DONE (07-08). Answers to 07-07's open questions.
The 07-07 open questions about the write-landing are now answered from real artifacts (built + inspected on Droid 1):
- **One `.mbtiles` per TYPE** (= cache_dir = old dir name), created **lazily** on first `insertTile`. NOT one container with a source column.
- Base + overlays **each their own DB** (SAT → SAT + SAT_LABELS_TRANSPORT + SAT_LABELS_PLACES), composited at the Leaflet layer. Overlay linkage lives ONLY in `map_sources.json` `layers[]` (role + render_order) — no cross-DB link, no type column.
- Schema: `tiles(zoom_level,tile_column,tile_row,tile_data)` + `metadata(name,value)` + mandatory unique `tile_index`. Raw z/x/y, `scheme=xyz`.
- The download pipeline (box 1 → submitDownload → divide → worker fetch) did NOT change — only the write landing did (loose `maps/tiles/<slot>/z/x/y` → `<slot>.mbtiles`), exactly as 07-07 predicted.

## TOMORROW — 2.6b Esri batch transport (tiles Pass 2)
**Per-tile HTTP fetch → Esri bulk `exportTiles` / `.tpkx` CompactV2 → unpack into the SAME `MBTilesStore`.** Pass 1's write path (codec q80 base / passthrough overlay, raw z/x/y) is DONE and stays; Pass 2 only changes *how tiles arrive*.
1. **De-risk first (no Kotlin):** curl the Esri `exportTiles` POST → poll `/jobs/{id}` → download `.tpkx` from Git Bash. Confirm async Export Tiles is authorized under the Location Platform key (pending gate from 07-05).
2. **Do the `insertTile`/`db()` silent-failure hardening first/alongside** — batch hammers inserts.
3. **Build `EsriExportTilesProcess` alongside, gated** (`layer.batch?.process`); CompactV2 128×128 bundle unpack (port Vundler.py / compact-cache-bundle) → emit `(type,z,x,y,bytes)` into the existing write contract.
4. **SAT = 3 separate `.tpkx`** (per-service), same loop, different destination DB.
5. Verify batch rows == HTML-path rows on Droid 1, open the gate per-source.

Full Pass 2 spec (request format, CompactV2 bit-layout, SAT reassembly, key reuse) is in `GroupTrack_V2_6_TileStorage_Recap` — authoritative and unchanged.

## Route+ state persistence — NEXT-SESSION pickup (2.6a, no radio)
**07-08 refined fix spec (Fred, hard rule):** while Route+ is active, the route screen **persists through ANY other selection** (?, hamburger, search, another item, panel — anything); the **ONLY** exit is an **explicit close**, which **prompts save-or-discard**. This subsumes the nav-box-disappears issue (state can't be implicitly abandoned → can't be orphaned; the save/discard prompt IS the teardown funnel). Same prompt on BOTH exits (planning-exit + convoy-return). Root cause (located 07-06): ephemeral Compose `remember` routeMode at two copies (`ConvoyMapViewerScreen.kt:129`, `ConvoyScreen.kt:197`) driving one `window.__routeMode`, ~8 scattered teardowns, no funnel. Design: persist to map JSON (per-map keyed) + one teardown funnel = the save/discard prompt. Full detail in memory `/areas/grouptrack-route-state`.

---

## Devices / environment
- **Droid 1** `8624SBCEDF00001789` — field/real-GPS; **USB temperamental** → prefer wireless ADB / Termux / X-plore. Primary MBTiles test device — now running the WebP build; SAT/TOPO/TOPO+ as `.mbtiles`. No sqlite3 → pull `.mbtiles` to PC (or Python's built-in sqlite3). ⚠ **Do NOT `adb rm` .mbtiles while the app runs** — wedges scoped-storage (`SQLITE_IOERR_FSTAT`), only a **reboot** clears it (cost most of 07-08 afternoon).
- **Droid 2** `24039703201775` — dev; still has the **old loose-file build + tree** = the uncompressed baseline oracle. SAT loose = **344,061 tiles / 7,410,581,847 bytes = 21,538 B/tile** (the 07-08 WebP baseline comparison). 07-07 standardization was certified here.
- **Repo:** `C:\Users\kixaz\Meshtastic-Android`, branch `feature/convoy-event-ride`. **Package path `com/geeksville/mesh/convoy/`** (NOT `com/grouptrack/android/`). Package id `com.geeksville.mesh`.
- **Build:** `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`. Cold ~36min; **warm ~10-13min**. APK: `app/build/outputs/apk/google/release/app-google-release.apk`.
- **Install:** `adb -s <serial> install -r <apk>`.

## Working style (reinforced today)
- **One command at a time** — Fred executes each, pastes result, then next.
- **Patches: surgical `sed` for single lines; temp-file + short Python script for multi-line blocks.** Heredoc pastes with long multiline strings REPEATEDLY splice/corrupt in the terminal (the echo garbles mid-block) — but content usually lands on disk correctly; ALWAYS verify with `sed -n`/`cat` after any patch. Python needs Windows paths (`C:\Users\...`); `sed`/`grep` use `/c/...`.
- **Impact analysis first** — reference `field_crossref_raw.txt`, `where_used_raw.txt`, `function_universe_raw.txt`, `navigation_xref.txt` before changes. (function_universe indexes signatures/key-lines only — real file reads needed for logic.)
- **Certify each item on device before reuse/commit.** Commit per verified item; explicit `git add <files>` (never `-A` — the Esri key is untracked).

---
*Handoff — 2026-07-08 EOD. Pass 1 (MBTiles cutover) + WebP (~55%) shipped & committed; convoy box-1 wiring closed the 07-07 host gap. Tomorrow: 2.6b Esri batch transport. Route+ state = next-session pickup. (07-07 download-standardization content retained above in full.)*

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
