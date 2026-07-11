# GroupTrack V2.6 — Handoff / State of Play
**As of:** 2026-07-09 EOD · **Prev living-docs update:** 2026-07-08

> Single orientation doc for the current milestone. V2.5 shipped to Play Store 06-24 (`c603bc3f0`). Work is on **V2.6**, branch `feature/convoy-event-ride`.
>
> **⭐ TOP PRIORITY FOR TOMORROW (07-10): AREA SEGMENTATION FOR DOWNLOAD LOAD BALANCING.** This is THE issue. Everything else (concurrency tuning, the JSON tuning rig, async-task counts) is fine-tuning that only matters on top of a load-balanced baseline. See the dedicated section "⭐ LOAD BALANCING — THE PRIORITY BUILD" below. Read that section first.
>
> **Today (07-09): tile download SPEED-UP shipped & tested (2.6b) — tile-fetch concurrency + zoom-cap removal + 429/503/502 backoff. ~5× faster (80 min for 125K tiles vs a gut ~6–8 hr before). BUT the speed-up exposed that load balance is what actually governs total time, and current balance is ACCIDENTAL. The proven next build is even segmentation.**
>
> **Yesterday (07-08): Pass 1 tile-storage migration (loose tree → MBTiles) + WebP compression BUILT, VERIFIED on Droid 1, COMMITTED. Storage ~55% smaller. Milestones 2.6a / 2.6b / 2.6c.**

---

## ⭐ LOAD BALANCING — THE PRIORITY BUILD (start here 07-10)

### The problem, proven empirically today
Total download time is governed by **segment balance**, not by concurrency. Two runs of the SAME ~125K-tile area (both on today's build):
- **~80 minutes** when the work happened to finish balanced (all queues finishing together).
- **~113 minutes** when it didn't — the small jobs drained at ~45 min and the one big SAT segment ran **alone** for the long tail.

**The 80-min balanced run was balanced BY ACCIDENT** — the big SAT segment happened to have roughly equal tiles to *all the other jobs combined*, so a 2-queue split fell ~50/50 by luck. Add a 3rd queue and that coincidence breaks: the "everything else" half gets subdivided, drains early, and strands the big SAT job → ~113 min. So **current balance is accidental and fragile**; it holds only because big-segment ≈ sum-of-rest at exactly 2 queues, for that particular area. Change the area, tile mix, or concurrency and it breaks.

### Why this is the priority
The gap between 80 min (good) and 113 min (bad) is **entirely load balance** — nothing else. The concurrency work is done and shipped; the tuning levers are fine-tuning. **The one thing that turns "balanced by luck" into "balanced by guarantee" is even segmentation.** Build that, and every download reliably hits the ~80-min-class balanced case instead of occasionally stranding into 113 min. Do NOT touch concurrency tuning until this is solved — it's premature to tune a balanced system you don't yet have.

### THE SEGMENTATION METHOD (fully specced — this is the build)
**Divide the submission area's bounding box into EQUAL AREAS, sized so no area exceeds 50,000 SAT tiles.**

1. **SAT drives the grid sizing.** SAT is the densest source (goes to z18 — the deep zoom quadruples each level and dominates tile count). Estimate SAT's total tiles for the full bbox, then divide into `ceil(SAT_total / 50000)` equal areas. Because SAT is the worst case, every shallower source (TOPO, TOPO+ — lower max zoom, far fewer tiles) rides the SAME grid and is automatically well under the ceiling. **You only estimate SAT.**

2. **Each segment MUST be a COMPLETE VERTICAL SLICE OF THE ZOOM STACK (z-min .. z-max) over its geographic cell — NOT segmented by zoom.** This is the load-balance principle and the reason the method works. Segmenting by zoom would recreate the imbalance (a z18-only job = the ~45K heavy monster; a z10–16 job = trivial). By giving every geographic cell the full zoom stack, each segment carries the SAME SHAPE of work — its proportional share of heavy z18 tiles PLUS the light upper-zoom tiles. Equal-area, full-stack segments therefore do genuinely equal work and finish together in parallel slots. **That comparability IS the load balancing** — it makes the ~80-min balanced case guaranteed rather than lucky.

3. **The unit** = a vertical column (full zoom) over a horizontal cell (geographic), sized so SAT's full-stack count ≤ 50,000.
   - **Worked example:** 72,000 SAT tiles → `ceil(72000/50000)` = 2 segments of ~36,000 each (each a full-stack half-cell). That SAME grid applied to TOPO/TOPO+ yields their (smaller) per-cell counts. Elegantly simple: SAT sizes the grid once; everything else inherits it.

4. **Slight overlap between adjacent cells** (~a tile-width) prevents seam gaps where a tile straddles a cell boundary. Duplicate boundary tiles are negligible and `MBTilesStore.hasTile()` dedupes them.

5. **Overlays travel WITH SAT.** Confirmed from logcat: a single SAT job downloads base World_Imagery → then the two overlays (`SAT_LABELS_TRANSPORT` = Reference/World_Transportation, `SAT_LABELS_PLACES` = Reference/World_Boundaries_and_Places) sequentially into their own `.mbtiles`. So segmenting SAT automatically segments its overlays — no separate handling.

### Prerequisite the segmentation needs
**`ConvoyTileCalculator.quickEstimate` must become source-aware.** It currently ignores the source and assumes z18 for everything (this is also the root cause of the TOPO+ estimate-vs-actual gap — see below). The segmentation needs an accurate per-source SAT estimate to size the grid, so making `quickEstimate` respect the source's zoom range is the enabling change — and it fixes the TOPO+ estimate gap as the same stroke.

### Keep concurrency modest for now
`MAX_CONCURRENT` stays at **2** (raising it to 3 today REGRESSED time by ~33 min because it broke the accidental balance — see above). After segmentation makes balance guaranteed, run the OPEN EXPERIMENT below to decide if any concurrency change helps.

### OPEN EXPERIMENT (only AFTER segmentation — do not do before)
Once segments are balanced, the concurrency question becomes cleanly testable (today it was confounded by the imbalance). **The two multiplying levers:** total simultaneous requests ≈ `queues (MAX_CONCURRENT) × async-tiles (TILE_FETCH_CONCURRENCY)`. Currently 2 × 8 = 16.
- **Fred's hypothesis:** at the same total, **fewer queues × more async each may outperform more queues × fewer** — e.g. **2 queues × 12 async (=24) could beat 3 queues × 8 (=24)** — because same request rate (same collision exposure) but parallelism concentrated as async-WITHIN-fewer-jobs avoids stranding, feeds the critical-path segment directly, and wastes fewer slots on small early-draining jobs.
- **Test as a GRID:** {2, 3 queues} × {8, 12 async}, measuring BOTH total time AND collision/error counts (429/502/503), on balanced segments.
- **Decision:** if all configs ≈ equal → resource-SATURATED (shared throughput/network/serial-insert maxed), keep 2×8, and the JSON tuning rig has nothing to tune → skip it. If a config wins → that's the setting, and the rig earns its place. The rig should make BOTH knobs tunable.
- **Note on saturation:** the =3 regression today hinted the shared resource may already be saturated (few 429 errors ≠ spare throughput — you can be at 100% of the pipe with zero errors). The grid test settles it.

---

## 07-09 — what got done today (tile download speed-up, 2.6b)

**Built + installed to Droid 1 and Droid 2 (BUILD SUCCESSFUL). Three code changes to `ConvoyTileDownloader.kt` and `ConvoyTileCalculator.kt`, plus a job-concurrency test bump.**

1. **Tile-fetch CONCURRENCY** (the ~5× win). The sequential per-tile download loop in `ConvoyTileDownloader.downloadTiles` is now parallelized: fetch tiles in batches of `TILE_FETCH_CONCURRENCY = 8` concurrently (`coroutineScope { … async … }`), then INSERT them SERIALLY per batch. **Fetch-parallel / insert-serial is the required design** — `MBTilesStore` holds one cached SQLite handle per type, so concurrent inserts are unsafe; only the network fetch (OkHttp, thread-safe) is parallelized. Preserves resume-skip (`hasTile`), per-batch cancellation (`isActive`), the z18 debug log, counters + `onProgress`, `forceOverwrite`, `isOverlay`.
   - *First build failed:* the const wasn't declared inside the object, and `async`/`coroutineScope` were fully-qualified (`kotlinx.coroutines.async`) so `async` didn't bind the `coroutineScope` receiver. Fixed by declaring `TILE_FETCH_CONCURRENCY` in the object, importing `coroutineScope` + `async`, and calling them unqualified.

2. **Removed the stale download zoom cap** in `ConvoyTileCalculator.maxZoomForSource`. It hardcoded TOPO+/TRAIL → 17 (a leftover from the old hand-drawn TOPO+ source). Now returns full `ConvoyConfig.DOWNLOAD_ZOOM` for all sources — download requests the full zoom range; worst case is empty/404 tiles at high zoom (already the current behavior for sources that top out). The per-source `max_zoom` in `map_sources.json` is **retained as a DISPLAY clamp** to snap back to later — download and display ceilings are intentionally decoupled (Fred's call).

3. **429 / 503 / 502 backoff** in `fetchTileBytes`. Previously any non-2xx was a null-drop → the tile was silently LOST with no backoff (the 125K test dropped 2 tiles to 502s). Now 429/503/502 are **retryable**: back off (exponential, base `TILE_BACKOFF_BASE_MS = 400`, up to `TILE_MAX_RETRIES = 4`) and retry the SAME tile; honors `Retry-After`. Genuine 404/other returns null fast. `FetchOutcome` data class holds the outcome. (502 was added after the first 125K test dropped 2 tiles — patch `patch_2026-07-09_add_502_retry_v1`.)

**Test result (125K tiles, SAT + TOPO + TOPO+, Droid, MAX_CONCURRENT=2):** ~80 minutes, ~100% success, ZERO 429s, 2 transient 502s (dropped pre-502-fix). Old-build baseline unknown; Fred's gut ~6–8 hr previously → **~4.5–6× speed-up**. A tester is running a different-track 125K on the OLD build for a firmer baseline (different track, same tile count = best available comparison).

**The performance investigation (the important part) → see "⭐ LOAD BALANCING" above.** Summary: concurrency is NOT a further lever (job-level regressed; the bottleneck is a saturated shared resource); the ~5× came from the shipped tile parallelism; and total time is governed by segment balance, which is currently accidental. Even segmentation is the proven next build.

### New 2.6 backlog items surfaced today
- **⭐ Area segmentation for load balancing** — THE priority build (full spec above).
- **`quickEstimate` source-awareness** — prerequisite for segmentation AND fixes the TOPO+ estimate gap (below). Same change.
- **SAT overlay showing on TOPO / TOPO+ (display bug).** Both SAT overlays (labels + transportation) render on the TOPO and TOPO+ base maps, ALWAYS ON. Confirmed PURELY RENDER-SIDE: overlays download correctly UNDER SAT (gated right at download — verified in logcat), so the bug is only in the Leaflet layer-stack render logic adding overlays regardless of active base. **Fix: show SAT overlays only when base == SAT** — in the map HTML layer-stack / `MapSourceManager` base-overlay association. NOT a download/association problem.
- **Clear-area / delete-area for downloads — NEVER WIRED.** Left unwritten during the area-download build because the MBTiles cutover was pending (didn't want to write against loose-file storage about to be replaced). Now a real gap: no per-area tile clear for the "replace" flow or for test-clearing. Scope = bbox-surgical delete of tiles within the drawn area from the relevant `.mbtiles`, tied to the "replace" toggle in `ConvoyDownloadConfirm` (NOT whole-source drop); must close/reopen the cached SQLite handle cleanly (handle-hygiene — see the FUSE-wedge hardening item). Scope bbox-vs-whole-source to confirm.
- **Tile-download tuning rig** (MANUAL tuning, NOT an auto-throttle — Fred rejected an adaptive controller as overkill). Build-decision gated on the balanced grid experiment above. Pieces: (1) 502 retryable — DONE; (2) `MAX_CONCURRENT` settable from JSON, clamped ±1 per cycle, effective on the NEXT set of submits/releases (CANNOT change while jobs running — new value staged, applied when the queue is idle; "cycle" = a fresh submit/release set); (3) error counts broken out by code in the `Complete:` logcat line → `failed=Y [429=a 502=b 503=c]`; (4) a tuning guideline (error-rate thresholds + how to step). The guideline must account for CONTENTION — higher concurrency can regress total time when a saturated resource is split. Rig should make BOTH `MAX_CONCURRENT` and `TILE_FETCH_CONCURRENCY` tunable (the grid test needs both).
- **TOPO+ estimate-vs-actual gap — ROOT CAUSE FOUND.** "Map 3" TOPO+ = source `esri-usa-topo` (`server.arcgisonline.com/.../USA_Topo_Maps`), `max_zoom: 15` in `map_sources.json` — genuinely tops out ~z15. "Map 2" = a DIFFERENT source `usgs-imagery-topo` (`basemap.nationalmap.gov`, `max_zoom: 16`) that shares the "TOPO+" short_label — which is why switching to it yields tiles at higher zoom. Three mismatched zoom numbers existed: JSON says 15, `maxZoomForSource` hardcoded 17, `quickEstimate` ignored source (assumed 18). Fred removed the download cap (change #2 above) and keeps the JSON `max_zoom` for display. Making `quickEstimate` source-aware closes the estimate gap.
- **Queue-monitor display check.** The download queue IS persisted to JSON — `ConvoyDownloadQueue.kt` writes `download_queue.json` under `appCtx.filesDir/download_queue/` (`QUEUE_FILE` const line 86; `saveQueue()` ~line 420 writes a `JSONArray` of `entry.toJson()`, called after every queue change; `loadQueue()` reads on startup). This file backs the queue-monitor UI. It is NOT readable on the RELEASE build via `adb` (`run-as` fails on non-debuggable release; `cat /data/data/...` is denied). To inspect next session: X-plore into `Data/com.geeksville.mesh/files/download_queue/`, or add a debug mirror-copy of the file to `/sdcard/Documents/GroupTrack/` inside `saveQueue()`. Fred found the queue-monitor labels confusing ("SAT 2-4 / TOPO 1,2,3,4 / TOPO+ blended") — worth checking against `ConvoyDownloadQueuePanel.kt` render logic; the logcat ground truth is clean: 6 jobs, 2 cells per source, labeled `x/2`.

### Checked and CLOSED today
- **Convoy-map submission path parity** — Fred earlier saw a convoy-map submission preview look wrong (second cells appeared missing) and cancelled before submitting, then ran from the convoy menu (which correctly built 2 cells each). On re-test the job submitted correctly from convoy with no repeatable issue. Treated as a transient display/misread, not a confirmed bug. Thread closed.

---

## Device / build quick-ref (durable)
- **Droid 1** `8624SBCEDF00001789` (field / real-GPS, primary) · **Droid 2** `24039703201775` (dev, no GPS). Droid 1 USB temperamental → prefer wireless ADB / X-plore.
- **BUILD:** `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease 2>&1 | grep -E "^e:|BUILD|FAILED"` (warm ~15 min, cold ~32 min). APK: `app/build/outputs/apk/google/release/app-google-release.apk`. Install: `adb -s <serial> install -r <apk>`. Cold-launch from icon.
- **One command at a time** (Fred executes + pastes). **Patches = dated Python scripts** → `/c/Users/kixaz/Downloads/`, UNIQUE filename per iteration, marker-guarded + `.bak` backup, abort-clean if anchor not found verbatim, detect+preserve line endings (convoy files are LF).
- **⚠ Do NOT `adb rm` `.mbtiles` while the app runs** — wedges scoped storage (`SQLITE_IOERR_FSTAT`) until reboot. Use X-plore + force-stop the app.
- **LOGCAT:** `adb -s <serial> logcat -c` then `adb -s <serial> logcat | grep -E "TileDownloader|DownloadQueue"`. Enqueue lines fire at submission start (roll out of the circular buffer during long downloads — capture to a file if needed).
- Repo: `C:\Users\kixaz\Meshtastic-Android`, branch `feature/convoy-event-ride`, package `com.geeksville.mesh` (path `com/geeksville/mesh/convoy/`). **~110+ commits ahead of origin — a `git push` for offsite backup is recommended.** Today's speed-up work was BUILT and tested but is NOT yet noted as committed — confirm commit state at start of 07-10.

---

## Milestone split (unchanged)
- **2.6a** — known-good FALLBACK release (Pass 1 MBTiles + WebP + UI fixes). SHIPPED/committed 07-08.
- **2.6b** — tile transport/SPEED. Today's concurrency + backoff + zoom-cap = 2.6b speed work. **Segmentation for load balancing is the next 2.6b build.** (Note: the original 2.6b "Esri batch transport / exportTiles / .tpkx" plan is DEAD — the Location Platform key is per-tile only, `exportTiles` needs a different entitlement. Pivoted to client-side concurrency, which is what shipped. See the tile-storage recap + living master for the full transport investigation and the 4-phase Esri cost strategy.)
- **2.6c** — lead-track / tick / my-cart rewrite (the only radio work; last).

## Deferred / carried (no radio, next sessions)
- Route+ state persistence (refined spec in the living master + `/areas/grouptrack-route-state.md`).
- `insertTile`/`db()` silent-failure hardening (matters MORE now that fetches are parallel — do with/before further download work).
- `deleteSource` handle-hygiene (needed for delete-area).
- aliases UI, tracks rename→human-readable swap, dead `tilesDir` lines, `.nomedia` verify, compression-quality runtime setting.
- git push offsite backup.

---

## The two 07-08 design rules (still in force)
1. **No nullable-as-shortcut** — a nullable/optional param must have its justification explained before use.
2. **Evaluate at point of contact, not retroactively** — don't audit for violations, but fix/justify when touching such code.
And the **07-07 DECOMPOSITION SCOPE RULE** — when extracting an internal function into an external module, every scope var it read becomes an invisible dependency; carry data as an explicit payload param, never re-read from a scope that no longer exists at deferred-run time.
