# GroupTrack V2.6 — Handoff / State of Play
**As of:** 2026-07-11 EOD · **Prev living-docs update:** 2026-07-09

> Single orientation doc for the current milestone. V2.5 shipped to Play Store 06-24 (`c603bc3f0`). Work is on **V2.6**, branch `feature/convoy-event-ride`.
>
> **⭐ TOMORROW'S FIRST TASK (new conversation): ROUTE+ PERSISTENCE.** Solve the route-add-disappearing issue by making the map and its exits INERT while route-add mode is active — the route panel must PERSIST and the map must NOT navigate away or close until route-add mode is explicitly cleared. Explicit close is the one exit, and it prompts save-or-discard. Root cause is already located and the fix spec is locked (see the ROUTE+ section below and `/areas/grouptrack-route-state.md`). Do the two-handler read FIRST (see that section), then build.
>
> **⭐ RELEASE DELAYED ~4 WEEKS (Fred's decision 07-11).** No Monday/partial release. Fred is on holiday ~1 week and does not want to rush. New target ~4 weeks out, scope EXPANDED to a full V2.6-complete release that cleans up ALL open issues AND includes the lead-cart re-engineer. See "⭐ THE ~4-WEEK PLAN" below.
>
> **Today (07-11): LOAD-BALANCING SEGMENTATION shipped & committed (`e38e43487`).** The priority build from the last two sessions is DONE — the drawn bbox now splits into balanced ≤50K-SAT segments so no single job strands a queue. Verified on Droid 1: "ran faster with two queues than three." Balance is now STRUCTURAL, not accidental. Also shipped in the same commit: 2-queue / 10-async concurrency config.
>
> **Yesterday context (07-09): tile download speed-up (2.6b) — concurrency + zoom-cap + backoff, ~5× faster. 07-08: Pass 1 MBTiles + WebP, ~55% smaller, committed.**

---

## ⭐ THE ~4-WEEK PLAN (release delayed — full V2.6 cleanup, calm pace)

Rough dependency order. This replaces the earlier "Monday release" framing entirely.

1. **⭐ Queue BOOT-LOOP IMMUNITY (top priority — the catastrophe-preventer).** `loadQueue()` on startup must survive ANY garbage in `download_queue.json` without crashing — tolerant parse, skip/quarantine bad entries, never throw during boot. WHY THIS IS #1: the map-source refresh once wrote poison queue entries that crashed the app **repeatedly on startup (a boot loop)** — every launch re-read the bad file and re-crashed. The ONLY recovery was Clear-All-Data, which also wiped the spatial+data DBs + tracks and revoked All-Files access = the whole 07-09→07-11 catastrophe. A poison queue file that boot-loops the app is the single most dangerous bug in the codebase because the only user recovery is destructive. Tolerant load turns the catastrophe into a shrug.
2. **`saveQueue` atomic-write** — the write side; stops a half-written/poison queue file from ever landing on disk.
3. **⭐ Route+ PERSISTENCE (tomorrow's first task, user-facing).** See dedicated section below.
4. **Rest of the hardening trio** — `insertTile` fail-loud (currently `db()`-null makes it a silent no-op while the loop counts "done" — worse now fetches are parallel); `deleteSource` handle-hygiene (close cached SQLite handles before delete or FUSE wedges, `SQLITE_IOERR_FSTAT`, reboot to clear).
5. **Three REGRESSIONS** — (a) map REFRESH "no longer works" (suspect: bounds-array order mismatch feeding `gridCells` → empty cells → silent no-op; the REFRESH path was NOT touched by segmentation); (b) `quickEstimate` source-awareness (currently ignores source, assumes z18 — also fixes the TOPO+ estimate gap); (c) SAT-overlay-on-TOPO render (overlays show on TOPO/TOPO+ always-on; render-side Leaflet layer-stack, download side is correct).
6. **Phase-4 FEATURES** — clear-area/delete-area (bbox-surgical tile delete, needs #4 handle-hygiene), THEN map-source-change tile-replace routed through the NEW `segmentCells` feeder (NOT its old ad-hoc path) and gated on the queue hardening (#1/#2). ⚠ This is the crash-trigger feature — do not rebuild it until #1/#2 are solid.
7. **⭐ LEAD-CART / LEAD-TRACK RE-ENGINEER (2.6c — the big one, needs RADIO).** Settled-not-started demolition/rebuild. Needs 2-cart field capture. Spec `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md` (authority `LeadTrackReplacement_Spec.docx`). Retire the 3 parallel live-track flows (`leadTrackSegments`/`gpsTrailSegments`/`routeTrailSegments` + `trackLeadOnly`), the lead-lock tick engine (`evaluateLeadLock`/`tick`/`assignLeadTail`/`lockedLeadNodeId`), and the projection/proxy substitution (the phantom-cart source). DO NOT touch displayed/imported DB tracks, node markers, route lines, route-builder snap-2. 4 weeks is deliberately enough to do this carefully (field capture + demolition), not jammed.
8. **Infra close-out** — git-push unblock (purge 3 large `.db` files from history via filter-repo/BFG; ~119 commits ahead of origin, no offsite backup until this clears), then push.

⚠ **HOLIDAY-WEEK RISK (check when back, not before leaving):** the boot-loop is still latent — the build currently on Droid 1 / any tester phone can still be bricked by a poison `download_queue.json`. If testers actively run the current build during the away week, that's the exposure. Item #1 closes it.

---

## ⭐ ROUTE+ PERSISTENCE (tomorrow's first build)

**The rule (Fred, 07-08 hard spec — supersedes any looser "every exit tears down" phrasing):** While Route+/route-add is ACTIVE, the route screen PERSISTS through ANY other selection (?, hamburger, search, another item, panel, tab — anything). The map and its exits are INERT against Route+ state. The ONLY way out is an EXPLICIT CLOSE, which PROMPTS SAVE-OR-DISCARD. This single rule subsumes the "route nav box disappears" bug — state that can never be implicitly abandoned can't be orphaned. Same prompt on BOTH exits (planning-map exit + convoy-map return).

**Root cause (LOCATED 07-06 from xref):** route mode is held in `var routeMode by remember { mutableStateOf(false) }` — ephemeral Compose state — in TWO copies: `ConvoyMapViewerScreen.kt:129` and `ConvoyScreen.kt:197`, both driving ONE shared `window.__routeMode` in `convoy_map.html`. Teardown = ~8 scattered hardcoded `setRouteMode(false)/clearBuildLine()` calls per screen. NO single funnel. Touching another item / closing the panel recomposes or orphans the `remember` boolean → Kotlin resets but JS gets no matching teardown → route disappears (a path fired clearBuildLine/redraw) OR stays-but-dead (Kotlin false so controls detach, JS still shows the line).

**PREREQUISITE before writing code:** read the actual `.kt` for the artifact-panel-close (`onDismiss`/`onClose`) handler + the `onTrackTap` bodies (`ConvoyMapViewerScreen:515`, `ConvoyScreen:645 & 895`) and CONFIRM they currently lack a teardown call (the diagnosis predicts they do). Under the new rule these must NOT tear down at all — they should be no-ops against Route+ state.

**Fix direction:** persist route state to the map JSON (per-map keyed, Planning vs Convoy — survives recomposition/nav; `remember` does not); intercept EVERY would-be-exit to no-op against Route+ state; only the explicit-close path runs teardown, through the save/discard prompt (one funnel). Touchpoints: `window.__routeMode` gate at `convoy_map.html:495` (BUG A popup suppression — keep mode-scoped); RouteManager build logic; JS `drawBuildLine`/`clearBuildLine`/`updateRoutes`/`showRoutes`. Full detail in `/areas/grouptrack-route-state.md`.

---

## ⭐ WHAT SHIPPED 07-11 — LOAD-BALANCING SEGMENTATION (committed `e38e43487`)

**Commit `e38e43487`** "V2.6 tile segmentation: SAT-sized load-balancing grid across all sources; 2x10 download concurrency" — 3 files, +92/-3 (`ConvoyDownloadQueue.kt`, `ConvoyTileCalculator.kt`, `ConvoyTileDownloader.kt`). ⚠ NOT build-verified after the final dry-run-scaffolding strip — the removal was mechanical (standalone function + its one call) so it should compile; it self-verifies at the first build tomorrow (Route+ requires a build).

**What it does:** `ConvoyTileDownloader.segmentCells(n,s,e,w, sizeTiles, segCeilingTiles=50_000, overlapTiles=3, zMax=DOWNLOAD_ZOOM)` REPLACES the fixed-12-mile `gridCells` as the bbox splitter for AREA downloads. Splits the bbox into `max(1, ceil(sizeTiles/50_000))` equal degree-slices of the LONGER axis; each cell is a full z10–z18 vertical slice (never zoom-banded); returns the same `List<DoubleArray>` `[N,S,E,W]` shape as `gridCells` so the enqueue loop (one QueueEntry per cell) is UNCHANGED. Logs each cell + a `SPLIT` summary under `"SEGMENT"` (`adb logcat -s SEGMENT`).

**Sizing (all sources ride the SAT grid):** `enqueueArea` computes `oneLayerTiles = quickEstimate(bbox).tileCount`, `satLayers = getDownloadSources().find{it.first=="SAT"}?.second?.size ?: slotLayers`, `sizeTiles = oneLayerTiles × satLayers`. Every slot (SAT/TOPO/TOPO+, called once each from `submitDownload`'s loop) sizes off SAT's 3-layer total → identical grid. Per-cell `QueueEntry.totalTiles` still uses the current slot's `slotLayers` (separate, correct).

**Overlap:** `pad = if(n>1) overlapTiles × (360/2^zMax) else 0.0` — 3 tile-widths (~0.004° at z18), seam-only, deduped by `hasTile()`. ⚠ HARD-LEARNED: overlap must be TILE-WIDTHS, never fixed degrees (⅛° = ~91 tiles/edge, ballooned a 24,716-tile area to 155,674 in the dry-run).

**Three bugs the dry-run + live logging caught before ship** (Fred insisted on visibility so nothing was a "miracle event"): (1) ⅛° overlap 6× blowup; (2) sizing under-count — `quickEstimate` returns ONE layer but SAT is 3 layers (~24.7K×3=~74K); (3) per-source grid mismatch — SAT split 2 but TOPO/TOPO+ split 1 until all sized off SAT. The dry-run scaffolding was stripped before commit; the `SEGMENT` logcat is the permanent validation tool.

**Concurrency config in the same commit:** `MAX_CONCURRENT=2` (line 85 `ConvoyDownloadQueue.kt`), `TILE_FETCH_CONCURRENCY=10` (line 38 `ConvoyTileDownloader.kt`, was 8). **Open follow-up (not yet run):** time 2×10 vs 2×8 on the balanced segments — if faster, probe 2×12; if flat, saturated at 8.

---

## Device / build quick-ref (durable)
- **Droid 1** `8624SBCEDF00001789` (field / real-GPS, primary) · **Droid 2** `24039703201775` (dev, no GPS). Droid 1 USB temperamental → prefer wireless ADB / X-plore.
- **BUILD:** `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm ~14 min, cold ~32–34 min). APK: `app/build/outputs/apk/google/release/app-google-release.apk`. **INSTALL: `adb -s 8624SBCEDF00001789 install -r -d <apk>` — use BOTH `-r -d` or tiles + user data are wiped.** ⚠ Play Store and local APK have DIFFERENT signing keys — uninstall one before installing the other. Cold-launch from icon.
- **One command at a time** (Fred executes + pastes). **Patches = dated Python scripts** downloaded to `/c/Users/kixaz/Downloads/`, run via `python ~/Downloads/<name>.py` (abs repo paths baked in; need NOT be in repo). UNIQUE filename per iteration, anchor-verified + abort-clean if not found, LF endings. NEVER heredoc Kotlin.
- **⚠ Do NOT `adb rm` `.mbtiles` while the app runs** — wedges scoped storage (`SQLITE_IOERR_FSTAT`) until reboot. Use X-plore + force-stop.
- **LOGCAT:** `adb -s 8624SBCEDF00001789 logcat -s SEGMENT` (segmentation) or `... | grep -E "TileDownloader|DownloadQueue"`. Enqueue lines fire at submission start.
- Repo: `C:\Users\kixaz\Meshtastic-Android`, branch `feature/convoy-event-ride`, package `com.geeksville.mesh` (path `com/geeksville/mesh/convoy/`; release pkg `com.grouptrack.android`). **~119 commits ahead of origin — push BLOCKED by 3 large `.db` files in history (need filter-repo/BFG purge); offsite backup blocked until cleared.**

## Milestone split (unchanged)
- **2.6a** — known-good FALLBACK release (Pass 1 MBTiles + WebP + UI fixes). SHIPPED/committed 07-08.
- **2.6b** — tile transport/SPEED. Concurrency + backoff + zoom-cap + **segmentation (shipped 07-11)** = the 2.6b speed work. (The original 2.6b "Esri batch transport / exportTiles / .tpkx" plan is DEAD — Location Platform key is per-tile only. Pivoted to client-side concurrency + segmentation. See recap + living master for the transport investigation and 4-phase Esri cost strategy.)
- **2.6c** — lead-track / tick / my-cart rewrite (the only radio work; last; now folded into the ~4-week plan).

## The two 07-08 design rules (still in force)
1. **No nullable-as-shortcut** — a nullable/optional param must have its justification explained before use.
2. **Evaluate at point of contact, not retroactively** — don't audit for violations, but fix/justify when touching such code.
And the **07-07 DECOMPOSITION SCOPE RULE** — when extracting an internal function into an external module, every scope var it read becomes an invisible dependency; carry data as an explicit payload param, never re-read from a scope that no longer exists at deferred-run time.
