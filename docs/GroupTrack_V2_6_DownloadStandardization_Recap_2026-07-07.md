# GroupTrack V2.6 — Map-Download Standardization Recap
**As of:** 2026-07-07 · Certified on Droid 2 · Committed to `feature/convoy-event-ride`

> Full design + rationale for the shared map-download pipeline. This is the prerequisite ("Step 0 — standardize all map-download submissions through the area-submission process") that the tile-storage plan depended on. Now complete for area, track, and import origins. Read this before touching any download/enqueue/box-1 code.

---

## Why this existed
The area map-download worked, but each origin (area, track/artifact-detail, import) had its **own** enqueue path — some headless/all-sources, some with a selection screen, some inline loops buried in a Compose handler. Goal: one screen + one process, every origin. Reuse, don't build per-origin. This also sets up the tile-storage migration: standardize the *submission* now, so redirecting the *write* (Pass 1) touches one spine, not four.

---

## The two black boxes

### BOX 1 — source selection (`ConvoyDownloadConfirm`)
- **Purpose:** present the map sources as checkboxes (+ "replace existing" toggle), return the user's selection. Rules only — which sources, replace or not.
- **Contract:**
  ```
  ConvoyDownloadConfirm(
      estimatedTiles: Int,          // OPTIONAL — shown only when > 0 (area knows it; batch passes 0)
      estimatedMB: Float,
      areaDesc: String,             // optional; hidden when empty
      bbox: DownloadBbox,           // travels THROUGH box 1 as payload (in, and back out)
      slots: List<SlotDisplayInfo>, // the sources, from MapSourceManager.getSlotSources()
      initialReplaceExisting: Boolean,
      onProceed: (bbox, selectedSlots: List<String>, replaceExisting: Boolean) -> Unit,
      onCancel, modifier)
  ```
- **Bbox-blind by design for batch:** box 1 does NOT own the bbox. Singular origins (area, track) pass a real bbox in; batch (import) passes a blank `DownloadBbox()` and the estimate lines hide (guarded on `estimatedTiles > 0`). The batch substitutes each item's own bbox downstream.
- **Estimate-zero guard (07-07):** the two estimate `Text`s hide/trim when `estimatedTiles == 0` so the batch popup shows just the source list, no misleading "0 tiles per source" / "~0 total tiles".

### BOX 2 — enqueue processor (`DownloadQueueManager.submitDownload`)
- **Purpose:** shared, screenless, model-layer. Takes a bbox + the chosen sources and enqueues one divided area-download per source. Callable from any working body, attended or unattended.
- **Contract:**
  ```
  fun submitDownload(context, north, south, east, west,
                     selectedSlots: List<String>, replaceExisting: Boolean = false) {
      for (slotName in selectedSlots)
          enqueueArea(context, slotName, north, south, east, west, replaceExisting)
  }
  ```
- Raw `n/s/e/w` Doubles, NOT `DownloadBbox` (a UI type) — keeps the model UI-free.
- `enqueueArea` handles large (grid divide via `gridCells`) AND small (single-job fallback when `cells.isEmpty()`) boxes — so it's safe for the small per-track/per-import boxes that batch feeds it unattended. (The historical reason the track path forked to plain `enqueue` — "gridCells empty → totalTiles=0" — no longer holds; the fallback exists.)

---

## The pipeline (one spine, all origins)
```
ORIGIN supplies bbox + shows BOX 1 (source selection)
        area:   drawn bbox
        track:  getTrackBbox(hash) from spatial DB row + ½-mile pad
        import: blank bbox at selection; per-track bbox substituted in the loop
   │
   ▼  onProceed(bbox, selectedSlots, replace)
BOX 2  submitDownload(n,s,e,w, selectedSlots, replace)
   │
   ▼
enqueueArea(slot, n,s,e,w)  →  gridCells → per-cell QueueEntry (totalTiles computed here)
   │                                        └ small box → single-job fallback
   ▼
launchWorker(entry)  →  Data payload (entry_id, n,s,e,w, label, refresh_mode, refresh_slot)
   │
   ▼
ConvoyDownloadWorker.doWork()  →  calculateTiles(n,s,e,w) × layers → downloadTiles → WRITE
        (normal mode = full download; refresh mode = existing tiles only, when replace+slot set)
```

---

## The three origins — how each is wired

### 1. AREA (`ConvoyMapViewerScreen`)
Download-confirm overlay block (~1396): `if (showDownloadConfirm && downloadBbox.isValid) { ConvoyDownloadConfirm(bbox = downloadBbox, ...) }`. `onProceed = { bbox, selectedSlots, replace -> ...; Thread { submitDownload(context, bbox.north, bbox.south, bbox.east, bbox.west, selectedSlots, replace) }.start() }`. **Bbox comes from the lambda payload, not a scope re-read.**

### 2. TRACK / artifact detail (`ConvoyMapViewerScreen:onDownloadMaps`)
`ArtifactDetailPanel` (one component, two hosts) emits the geometry hash via `onDownloadMaps`. Map-viewer host: `getTrackBbox(hash)` → if valid, **null `pendingDetailId`/`pendingDetailType` (dismiss detail panel so box 1 shows on top)** → `downloadBbox = bb; showDownloadConfirm = true`. Reuses the SAME box 1 + onProceed as area. `SpatialDbManager.getTrackBbox(context, hash): DownloadBbox?` = the bbox half extracted from `downloadMapsForTrackHash` (query `tracks WHERE geom_hash`, ½-mile pad: `padLat = 0.00724`; `padLon = 0.00724 / max(0.01, cos(midLat))`), returns padded `DownloadBbox` instead of enqueuing.

### 3. IMPORT / batch (`ConvoyTrackImportScreen` + `ConvoyTrackOps.importGpxAllArtifacts`)
- Import button: `if (mapsFor.isNotEmpty()) showSourcePopup = true else doImport()`.
- `if (showSourcePopup)` renders box 1 inside a `Dialog` with **blank bbox** (`DownloadBbox()`), `estimatedTiles = 0` (guarded). `onProceed = { _, sel, replace -> selectedSlots = sel; replaceExisting = replace; showSourcePopup = false; doImport() }` — ignores box 1's returned bbox (`_`); batch supplies per-track bbox.
- `doImport` loop passes `importGpxAllArtifacts(f, context, if (mapsFor.contains(f.name)) selectedSlots else emptyList(), replaceExisting) { ... }`.
- `importGpxAllArtifacts` signature: `downloadMapSlots: List<String> = emptyList(), replaceExisting: Boolean = false` (was `downloadMaps: Boolean`). On each track **INSERT**: `if (downloadMapSlots.isNotEmpty()) { gh = computeGeomHash(wkt); bb = getTrackBbox(context, gh); if (bb valid) submitDownload(context, bb.north,bb.south,bb.east,bb.west, downloadMapSlots, replaceExisting) }`.
- **Routes:** import currently processes **tracks only** — waypoint/route GPX import is a TEMP BYPASS (2026-06-02, untested). So import-maps is tracks-only until route-import is built; route artifacts will use the same submitDownload when they land.

---

## THE DECOMPOSITION SCOPE RULE (why this was hard — bit us twice)
When you extract an internal function (that shared a screen's scope) into a called external module, **every state variable it read from scope becomes an invisible dependency.** The extracted/deferred call runs at a *different time* than the original inline code — and shared mutable Compose state can be recomposed/reset underneath before the deferred read happens. The code looks identical (same statements) but the *timing of the read* changed.

- **Symptom this session:** box 1's `onProceed` flipped `showDownloadConfirm=false`/`showDownloadPanel=false`, then a `Thread{}` read `downloadBbox` — by thread-run time the value was empty → jobs got `(0,0,0,0)` → `gridCells` produced degenerate/zero → 0 tiles. The synchronous 280k estimate was valid; the deferred job read the box after it was gone.
- **The rule:** data must travel as an explicit **parameter / message payload carried into the call** — never re-read from a scope that may no longer exist. Snapshot at point-of-intent (box-1-render time, when valid), pass concrete values forward. For disparate/unattended processes (batch), the payload the consumer owns replaces any scope reference.
- **The practice:** do a **scope/state audit UP FRONT** when decomposing — enumerate every scope var the body touched, confirm each is now a payload param, before building. Not one crash at a time.

Payload continuity, confirmed end-to-end: `submitDownload(n,s,e,w)` → `enqueueArea(n,s,e,w)` → `QueueEntry(north=cell[0]...)` → `launchWorker.putDouble("north", entry.north)` → `doWork.getDouble("north", 0.0)`. Every hop carries concrete coordinates; the only failure was the *origin read time*, now fixed.

---

## Tile-count-at-enqueue fix (07-07)
`enqueueArea` now computes `totalTiles` at enqueue instead of the `0` placeholder, so the queue UI shows real counts (not red zeros) immediately:
```
val slotLayers = MapSourceManager.getDownloadSources().filter { it.first == slotName }.sumOf { it.second.size }
// small-area entry:  totalTiles = calculateTiles(north, south, east, west).size * slotLayers
// per-cell entry:    totalTiles = calculateTiles(cell[0], cell[1], cell[2], cell[3]).size * slotLayers
```
Matches the worker's own math (`tiles.size * totalLayers`). `ConvoyTileCalculator` + `MapSourceManager` are same-package (already used by the original `enqueue` at lines 127-129) — no imports needed.

---

## Files touched (all committed)
- `ConvoyDownloadQueue.kt` — added `submitDownload`; tile-count at enqueue.
- `ConvoyDownloadConfirm.kt` — `bbox` param in + returned via `onProceed`; estimate-zero guard.
- `ConvoyMapViewerScreen.kt` — area onProceed uses payload bbox; track `onDownloadMaps` feeds box 1 (dismiss detail panel first).
- `SpatialDbManager.kt` — added `getTrackBbox(context, hash): DownloadBbox?`.
- `ConvoyTrackOps.kt` — `importGpxAllArtifacts` takes `downloadMapSlots`+`replaceExisting`; INSERT path uses `getTrackBbox`+`submitDownload`.
- `ConvoyTrackImportScreen.kt` — state (`showSourcePopup`/`selectedSlots`/`replaceExisting`); button gate; box 1 popup; threading.
- `ConvoyDisplayPanel.kt` / `ConvoySettingsScreen.kt` — V2.6 badges.

## Deferred / open
- **INSERT-only gate:** import maps fire only on `AddOutcome.INSERT`. Re-importing an existing track with maps checked → nothing (DUPLICATE/ALIAS). Move the maps call out of the INSERT-only branch to fix.
- **ConvoyScreen artifact-detail host** not wired for box-1 track download (only the map-viewer host is). Wire it, or make box 1 a shared overlay both hosts embed, if that host's detail panel needs track-map download in 2.6.
- **Leftover debug log** `submitDownload IN:` — remove after storage work.

---
*Recap — 2026-07-07. The download submission is now one standardized spine; the tile-storage migration redirects its WRITE end (Pass 1) without touching the submission side.*
