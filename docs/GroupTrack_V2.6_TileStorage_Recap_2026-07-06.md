# GroupTrack V2.6 — Tile-Storage Design Recap
**Date:** 2026-07-06 · **Author:** Fred (kixaz) + Claude · **Status:** design ~complete; one region left to verify

> **How to use this in a new conversation:** paste the "Orientation" block, or attach this file. The full authoritative design also lives in memory at `/areas/grouptrack-tile-storage.md` — read that first in the new session. This file is the human-readable mirror.

---

## Orientation (one paragraph)
V2.6 migrates tile storage from loose files to **MBTiles**, modeled as one **unified process** — a single spine `ORIGIN → NORMALIZE → EXPAND → CHOP → FETCH → RETURN POINT → WRITE → DESTINATION`, pluggable at both ends. Built in **two passes**: Pass 1 moves the current transport's *storage* to spatial DB (fetch unchanged); Pass 2 adds a *batch* transport capability (Esri first, gated). Core discipline: **reuse** existing components/functions; the DB has a **rules engine** — invoke it, don't reimplement. Everything upstream is defined; the **one undefined region** is the "miracle-happens" process at the destination end (how many DBs, how created, how overlays linked) — which **may already be a black box** in the code to find and verify.

---

## The unified process (one spine, many origins, many destinations)
`ORIGIN → NORMALIZE → EXPAND → CHOP → FETCH → [RETURN POINT] → WRITE → DESTINATION`

1. **ORIGIN → (bbox, source-set)** — the only place origins differ.
2. **NORMALIZE → (bbox, [sources])** — origins collapse to one canonical request; downstream is origin-blind.
3. **EXPAND → layers** — each source → its layers via the source def (SAT → base + 2 overlays). "One SAT request → 3 destinations" happens here.
4. **CHOP → queue items** — bbox → bounded cells; (layer, cell) = one queue item.
5. **FETCH → tiles** — the **one place the "miracle" varies**: HTML per-tile, or batch export+unbundle, chosen per-layer by whether it has a `batch` process.
6. **RETURN POINT → uniform `(type, z, x, y, bytes)` stream** — both transports emit this.
7. **WRITE → destination** — the write contract resolves type → its `.mbtiles` and inserts.

**Two fan points:** fan-**in** at front (many origins → one canonical request); fan-**out** at back (one request → EXPAND layers → CHOP cells → many tiles → many DBs).

**Three independent extension points:** add an ORIGIN = implement step 1 only; add a TRANSPORT = implement step 5's miracle only; add a DESTINATION (new layer/type) = free.

---

## Core design principle — REUSE
Don't build new — funnel into the existing proven component/function. Instances decided today:
- Origins reuse the **area submission** (not per-origin submitters).
- Track / route / artifacts reuse the **fit process** (not per-origin bbox derivation).
- Artifact-import reuses the **track path** (not an import downloader).
- Both transports reuse the **one write contract** `insertTile` (not per-transport writers).
- Batch reuses the **provider's own unpack** (port Vundler / compact-cache-bundle; not a bespoke unbundler).
- Batch reuses **getApiKey / api_keys.json** (not new key handling).
- The Esri handler reuses **one process across all Esri sources** (not per-source).

**The DB has a rules engine — reuse it, don't reimplement its logic in Kotlin.** Logic like type→file resolution, source→layer expansion, render_order/compositing, coverage/validation, source-set defaulting may already be rules in the engine → invoke it. **Scope it first:** which DB + which engine (SQLite `grouptrack_spatial.db` / `grouptrack_data.db`, or a separate layer? over the new per-type MBTiles too, or only GPX/track DBs?); what kinds of rules; is it callable where the submission/write code runs.

---

## The ONE undefined region — the "miracle-happens" process (destination end)
Everything upstream is defined. The only open design work is the RETURN POINT → WRITE internals of `MBTilesStore`:

- **It may already be a black box.** Before designing from scratch, **find the existing tile-landing component and verify its internals against memory** — reuse if it matches, design only the delta if diverged. Corpus is a lossy index; the code is truth.
- **(1) How many databases** — dynamic: one MBTiles per type (= per cache_dir); count = however many distinct types across active sources (5 today). *Open:* who computes the DB set + when (up front from source defs vs lazy)? **Rules-engine candidate.**
- **(2) How they're created** — lifecycle: lazy create-if-absent on first `insertTile`, or provision up front? Creation writes canonical `tiles` + `metadata` + the **mandatory** `tile_index`, with metadata (name, base/overlay, format, bounds) at creation.
- **(3) How overlays are linked** — the base↔overlay relationship does **not** live in the tile DBs (no type column, no cross-DB link; each DB standalone). It lives in the **source definition** (layers[] with role + render_order); MapView reads it at render to stack the right overlay DBs; each DB's metadata self-declares base-vs-overlay. *Open:* confirm nothing else needs the link (coverage? delete cascade?). **Rules-engine candidate.**
- **(4) The process ties 1–3 together** = `MBTilesStore` guts: receive `(type,z,x,y,bytes)` → resolve type→DB → open-or-create → encode → insert (y-flip). Write the right metadata so the reader can stack overlays later.

---

## Two-pass build
- **Pass 1 — move current transport to spatial-DB storage.** HTML fetch **unchanged**; only the WRITE changes (loose files → MBTiles). Covers all origins + all readers. = the V2.6 tile work + the "simplify" goal.
- **Pass 2 — add a batch transport capability.** New FETCH miracle built **alongside**, gated off (`layer.batch?.process`), Esri first, provider-agnostic. Never touches Pass 1's storage — just a new way to feed the same write. Build-alongside-and-gate.

---

## Write-contract frame (the two "miracles")
Intercept at the **return point**; everything above it is untouched. Two producers emit `(type,z,x,y,bytes)` into **one shared write contract** (`MBTilesStore.insertTile` + TileCodec + type→file resolve + y-flip):
- **Miracle #1 (Pass 1):** per-tile HTML write, redirected `<cache_dir>/z/x/y.png` → `<cache_dir>.mbtiles`.
- **Miracle #2 (Pass 2):** batch `.tpkx` → unbundle → same stream → same DBs.

The contract is producer-blind — that's why Pass 2 reuses Pass 1's write. **Open:** single `insertTile` vs transaction-batched `insertTiles` (faster for batch bulk); Miracle #2 streaming per-bundle vs collect-whole-package.

---

## Schema — resolved 4 ways
**One MBTiles file per TYPE (= cache_dir = old directory name).** No type column; base/overlay declared in each file's `metadata`; compositing happens at the Leaflet layer, not in storage. Confirmed by: (1) the render stacks separate per-type layers (we're not rewriting MapView); (2) the MBTiles spec mandates one tileset per file; (3) Esri best practice = base+reference separation; (4) Esri delivers per-service.

Canonical: `tiles(zoom_level, tile_column, tile_row, tile_data)` + `metadata(name, value)` + **mandatory** `CREATE UNIQUE INDEX tile_index ON tiles(zoom_level, tile_column, tile_row)`. WebP yes; **never JPEG for overlays** (kills alpha). FLAT vs NORMALIZED per type (sparse overlays shrink with NORMALIZED). PMTiles rejected (immutable — GroupTrack patches incrementally). TMS y-flip to settle.

**DB naming = old directory names.** cache_dir = type = old folder = `convoy://tiles/<type>/...` URL segment = `.mbtiles` filename. Migration: `cache_dir` folder → `cache_dir`.mbtiles. ⚠ `TOPO+` has a `+` — verify it round-trips through the convoy:// URL and path open.

---

## Submission origins — all must go through the SAME area engine
The unified process only holds if every download origin funnels through the one area submission. Test per origin: does it end at the area submission with `(bbox, sources)`, or bypass it (a second engine)?

- **AREA** — user-drawn bbox + **selectable** sources = the canonical engine.
- **TRACK** — bbox derived by padding the track's lat/long extents **½ mile** + preference-sourced set → routes through area submission.
- **ROUTE** — "dupes track": same adapter shape (pad route extents → resolve sources from preference → area). Confirm it shares track's path.
- **IMPORTED ARTIFACTS** — an imported task **writes a new artifact**; the download panel has a **"yes" import-maps toggle**; if yes → the **fit process** requests a map download for this track, **right after track creation**. So it's **not a separate engine** — it triggers a track-style download via the fit process → converges through track.

**Prove the list exhaustive with a grep** — enumerate every caller of the queue submission + every path reaching `ConvoyTileDownloader` / `ConvoyDownloadWorker`; classify each as canonical / adapter / rogue-bypass. May be unnamed origins — from code, not memory.

**Source-set policy per origin:** area = selectable; track/route = preference-default; artifacts = via track (likely track's preference).

---

## Front-end standardization decision
Make each non-area origin a **pure origin adapter** — produce `(bbox, source-set)` and submit through the **same area submission**.

**Primary reason: inherit the area process's large-bbox splitting.** The area submission already chops a big bbox into bounded queue items (`gridCells` / `tileBoundsLatLon`) — real, tested. Track/route/artifacts on their own paths duplicate or lack it. Routing their bbox through the same submission → they inherit splitting for free. **Triple duty:** (i) splits big areas today; (ii) splits track/route/artifact downloads once standardized; (iii) sizes batch export jobs in Pass 2 (one split item = one Esri job under the 100k cap).

**Other reasons:** origins gain source control (fixed → selectable); convergence happens before the return point *by construction* → Pass 1's write-redirect is provably one seam.

**Confirm:** does the area submission cleanly take `(bbox, sources)`, or is it entangled with the drawing UI? If entangled, extract a pure `(bbox, sources) → submit` core first.

---

## Pass 2 — batch transport (Esri first)
- **Core:** the unit of variation is the **provider's process**, not just a key. One handler per provider protocol, reused across all its sources; the source def names the process + supplies parameters. One `EsriExportTilesProcess` serves all Esri sources.
- **Schema:** per-layer `batch` block — `{ "process":"esri_export_tiles", "export_endpoint":"<MapServer>/exportTiles", "package_format":"tpkx", "requires_key":true, "api_key_param":"token", "max_tiles_per_job":100000 }`. `process` = dispatch key. No block → HTML per-tile.
- **Request (async):** POST `exportTiles?f=json&tilePackage=true&exportBy=levelId&levels={min-max}&areaOfInterest={AOI wkid 4326}&token={key}` → jobId → poll `/jobs/{jobId}` until `esriJobSucceeded` → GET result → `.tpkx`.
- **Import (CompactV2 unpack — spec fully known):** `.tpkx` = ZIP → `root.json` + `iteminfo.json` + thumbnail + `tile/` of `.bundle` files. Each `.bundle` = 128×128 tile block: 64-byte header at offset 0 (LE) → tile index at byte 64 = 128×128 array of 8-byte records. `TileIndexOffset = 64 + 8*(128*(row%128) + (col%128))`. Each record: **offset = bits 0–39, size = bits 40–63** → seek + read `size` bytes = raw tile. Absolute (row,col) = bundle base (from `.bundle` filename e.g. `R0000C0000`) + in-bundle row/col. Skip size-0. **Port** from Esri `raster-tiles-compactcache` (Vundler.py) / `@syncpoint/compact-cache-bundle`; GDAL 3.8+ ESRIC driver = cross-check oracle. `.vtpk` shares the same bundle format (vector pbf) → a future vector handler reuses the walk.
- **SAT reassembly:** exportTiles is **per-service** → 3 SAT services = 3 separate `.tpkx`, same unpack loop, only the destination DB differs (SAT / SAT_LABELS_TRANSPORT / SAT_LABELS_PLACES). Separate, not combined.
- **API key:** reuse `api_keys.json` + `getApiKey`; inject `?token=<key>`; never in source JSON, never logged; decrypt at call-time. Batch uses Fred's Esri Location Platform account (needs a token — the public tile endpoint doesn't).
- **De-risk first:** curl the exportTiles POST→poll→download flow against a real endpoint from Git Bash **before** any Kotlin. Confirm exportTiles is enabled on the public arcgisonline.com services.

---

## Action plan (ordered — the pickup for the next session)
**Start:** read `/areas/grouptrack-tile-storage.md` (or this file). Fred uploads the 5 recommit xref docs + AllDocs at session start.

**Tomorrow's plan: standardize all map-download submissions through the area-submission process (= Step 0).**

- **Step 0 — first move:** verify the **area download submission** is the black box Fred remembers — it's load-bearing twice (the engine all origins funnel through + tangled with the return-point write). Check against memory: (i) does it cleanly accept `(bbox, sources)` as separable inputs, or is it wired into the drawing UI? (ii) does it own the large-bbox splitting? (iii) where does it hand off queue → worker → return-point write? In `ConvoyDownloadQueue`'s submission entry point.
- **Step 0 (audit + blast radius):** grep every caller of the queue submission + every path to `ConvoyTileDownloader`/`ConvoyDownloadWorker`; classify canonical / adapter / rogue; build the **blast-radius process inventory** (every process touching tile download/write + what it does) from the xref docs + code. Account for area / track / route / imported-artifacts; prove exhaustive. Scope the **DB rules engine**.
- **Step 0 (standardize):** make each non-area origin submit via the area submission with a source preference (default = today's behavior); pin down the **fit process**; reuse existing components.
- **Step 0.5 — resolve the miracle-happens design:** find the existing tile-landing **black box** + verify against memory. Settle DB-set enumeration, DB creation lifecycle, overlay linkage (try the rules engine for enumeration + linkage).
- **Step 1 (Pass 1 — read code bodies):** `ConvoyTileDownloader` + `ConvoyDownloadWorker` + `ConvoyDownloadQueue`; both `shouldInterceptRequest` (CS 720-748, MVS 578+); `setOverlayLayers` + tile JS in `convoy_map.html` / `grouptrack_map.html`; show-downloaded/show-queued + MVS:658; how the codebase opens SQLite (SpatialDbManager) + the DB rules engine.
- **Step 2 (Pass 1 — scope-lock):** grep `TILE_DIR` / `tilePath` / `File(...tiles` → prove the complete tile-reference set (zero `File(TILE_DIR,...)` survivors after Pass 1).
- **Step 3 (Pass 1 — write contract):** write `MBTilesStore` (type→file, canonical tiles + metadata + mandatory tile_index) + `TileCodec.toWebp`, reusing the rules engine + SpatialDbManager where they own logic. Settle: y-flip, WebP content-type, schema variant, insertTile vs txn insertTiles, `TOPO+` `+`.
- **Step 4 (Pass 1 — redirect + all readers):** swap the return-point write to `insertTile`; route every tile-reference site through `MBTilesStore`; retire loose-file cleanup. Verify on Droid 1 (pull `.mbtiles` → SQLite → rows + index; render composite + coverage). **Ship Pass 1.**
- **Step 5 (Pass 2 — gate first):** curl the Esri exportTiles flow vs a real endpoint from Git Bash, before any Kotlin.
- **Step 6 (Pass 2 — build alongside, gated):** add the `batch` block; write `EsriExportTilesProcess` (port Vundler/compact-cache-bundle); reuse `getApiKey`. Wire the `layer.batch?.process` dispatch, gate closed. Verify batch rows == HTML-path rows on Droid 1, then open the gate per-source.

---

## Confirmed real-code facts
- `ConvoyConfig.TILE_DIR = /sdcard/Documents/GroupTrack/maps/tiles/`. Droid 2: 5 type folders (= cache_dirs): SAT, SAT_LABELS_TRANSPORT, SAT_LABELS_PLACES, TOPO, TOPO+. Path `TILE_DIR/<type>/<z>/<x>/<y>.png`.
- **Write** = `ConvoyTileDownloader` (tilePath 57, downloadTile 64, downloadTiles 98; called only from `ConvoyDownloadWorker` 79, 134).
- **Queue** = `ConvoyDownloadQueue` (bbox → multiple queue items via gridCells/tileBoundsLatLon; own JSON @405/409/419-420).
- **Serving** = `convoy://tiles/` scheme (ConvoyConfig:26) → setTileUrl + setOverlayLayers → `shouldInterceptRequest` (MVS:578, CS:720). Source→overlays via `MapSourceManager.getOverlayJson`; render branches at ConvoyScreen 684-687 / 1305-1307. History: file:// (V2.4) → blocked Android 10+/14 → convoy:// now.
- ⚠ **WebView/JS not fully observable** → verify empirically on-device. `grouptrack_map.html` (Planning) doesn't route console.log to logcat → use a visible on-screen element.
- `MapSourceManager` separates ONLINE (getOnlineUrl) vs OFFLINE (getLocalUrl) → **online maps unaffected**.

### map_sources.json (source-connection document)
Path `app/src/main/assets/map_sources.json`. Each source: id, producer, map_type, name, short_label, layers[], requires_key, api_key_param, attribution, downloadable, validation. Each layer: role, url_template, tile_format, min_zoom, max_zoom, cache_dir, subdomains?, render_order.
- **default_slots (active 3) all Esri:** SAT = esri-imagery-overlays (World_Imagery base jpg + World_Transportation + World_Boundaries_and_Places overlays; cache_dirs SAT / SAT_LABELS_TRANSPORT / SAT_LABELS_PLACES); TOPO = esri-topo (World_Topo_Map); TOPO+ = esri-usa-topo (USA_Topo_Maps).
- Endpoints = cached-tile op `.../MapServer/tile/{z}/{y}/{x}`; exportTiles is a different op on the same MapServer. Only Thunderforest is keyed today; the rest (incl. current Esri via free public arcgisonline.com) are keyless.

### Devices / build
- **Droid 1** (`8624SBCEDF00001789`, field/real-GPS, flaky USB → wireless ADB) = primary MBTiles test device (maps/tiles cleared; ~6.4GB loose before).
- **Droid 2** (`24039703201775`, dev, connected 07-06) = has the loose tree (5 folders).
- Devices have **no sqlite3** — pull `.mbtiles` to PC to inspect.
- Esri Location Platform account done 07-05; API key created + rotated + saved locally. **Pending:** verify async Export Tiles is authorized under those privileges.
- Build: `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease`. V2.5 shipped to Play Store 06-24.

---
*Mirror of `/areas/grouptrack-tile-storage.md` as of 2026-07-06. Read the memory file in the new session for the authoritative, most-current version.*
