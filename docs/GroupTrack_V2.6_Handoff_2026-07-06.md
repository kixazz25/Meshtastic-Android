# GroupTrack V2.6 — Handoff / State of Play
**As of:** 2026-07-06 EOD · **Prev living-docs update:** 2026-07-02 (this set closes that gap)

> Single orientation doc for the current milestone. Points at the detailed living docs. V2.5 shipped to Play Store 06-24 (`c603bc3f0`); a 2.5/7-3 AAB was banked 07-03. Work is now on **V2.6**.

---

## V2.6 in one line
**Simplification/cleanup + faster maps.** Through-line = subtraction. Three item clusters:
- **A — MBTiles spatial storage (tiles Pass 1)** · no-radio · **first**
- **B — batch transport of tiles (tiles Pass 2, Esri)** · no-radio · after A
- **C — lead-track / tick / my-cart rewrite** · needs radio · **last (next week)**
- plus no-radio backlog: **route-state persistence** (this morning's issue), draw/connect-tracks-into-a-route, recorder fixes, detail-panel wiring, aliases UI.

---

## The detailed living docs (read these for full design + reasons)
| Topic | Doc | Status |
|---|---|---|
| Tile storage (Pass 1 + Pass 2) | `GroupTrack_V2.6_TileStorage_Recap_2026-07-06.md` | Design ~complete; one region to verify |
| Route-state persistence & teardown | `GroupTrack_V2.6_RouteState_2026-07-06.md` | Root cause located; fix not locked |
| Cross-platform / iPad / KMP | `GroupTrack_CrossPlatform_iPad_Strategy_2026-07-04.md` | Arc reconciled; decisions open |

---

## Tile work (A + B) — the headline design (07-06 convergence)
Full detail in the tile-storage recap. Summary:
- **MBTiles-only hard cutover** — dual-path loose+MBTiles dropped, no state-file mode switch, no read/write fork. The ~5 map-invested testers re-download once. Storage format changes; HTML fetch survives.
- **Unified process** — one spine `ORIGIN → NORMALIZE → EXPAND → CHOP → FETCH → RETURN POINT → WRITE → DESTINATION`, pluggable origins/transports/destinations.
- **Two passes:** Pass 1 = redirect the *write* at the return point (loose files → MBTiles, all origins + readers, MapView unchanged); Pass 2 = add a *batch* transport (Esri exportTiles, built alongside + gated).
- **All download origins go through the same area submission** — area / track / route(dupes track) / imported-artifacts(via the fit process on the "import maps" toggle, right after track creation). Reuse, don't build per-origin.
- **Schema:** one MBTiles per TYPE (= cache_dir = old dir name); base + overlays each their own DB; composite at the Leaflet layer. Mandatory `tile_index`; WebP not JPEG for overlays.
- **Batch unpack:** Esri `.tpkx` CompactV2 fully specified (128×128 bundles; port Vundler.py / compact-cache-bundle). SAT = 3 separate `.tpkx`, same loop, different destination DB.
- **Reuse discipline + DB rules engine** — invoke existing components; don't reimplement.

**One region still to design:** the "miracle-happens" process at the destination end (how many DBs, how created, how overlays linked) — which **may already be a black box** in the code to find and verify.

---

## Tomorrow's plan (Step 0 of the tile-storage action plan)
**Standardize all map-download submissions through the area-submission process.**
1. **First:** verify the **area download submission** is the black box Fred remembers (cleanly takes `(bbox, sources)` → chops → queues → submits; owns large-bbox splitting; where it hands off to the return-point write).
2. Origin audit + **blast-radius process inventory** (every process touching tile download/write + what it does) from the xref docs + code.
3. Scope the **DB rules engine**; pin down the **fit process**.
4. Standardize each non-area origin to submit via the area submission with a source preference (default = today's behavior).

Then Pass 1 build (read bodies → scope-lock grep → write MBTilesStore + TileCodec → redirect write + all readers → verify on Droid 1 → ship), then Pass 2 (curl-de-risk the Esri flow → build EsriExportTilesProcess alongside/gated → verify rows match → open the gate).

---

## Route-state persistence (this morning's issue) — no-radio
Root cause located 07-06. Route mode is held in **ephemeral Compose `remember` state** at **two independent copies** (`ConvoyMapViewerScreen.kt:129`, `ConvoyScreen.kt:197`) driving one shared `window.__routeMode`, with **~8 scattered manual teardowns** and **no single funnel**. Touching another item or closing the artifacts panel recomposes/orphans the boolean → JS gets no matching teardown → route either disappears or stays-drawn-but-dead.

**Fix intent (Fred's 3 reqs):** (1) persist route state in the **map JSON** (survives nav; `remember` doesn't); (2) **per-map** state (Planning vs Convoy — no collision); (3) **every exit ends it** via **one teardown funnel** (incl. panel-close + item-tap, which currently make no teardown call). Persist across nav, never past an exit.

**Before locking:** read the artifact-panel-close (`onDismiss`/`onClose`) handler + the `onTrackTap` bodies (`ConvoyMapViewerScreen:515`, `ConvoyScreen:645`/`895`) and confirm they lack a `setRouteMode(false)` teardown call.

---

## Cross-platform / iPad — post-V2.6
Convoy is a fork of Meshtastic's messaging, so iPad = a fork of **Meshtastic-Apple** (Swift). Presentation is ~90% shared because it's already web (Leaflet in a WebView) — **SwiftUI rejected** for presentation; iPad = the same web in a WKWebView behind a native Swift shell. The real cost driver = the **bridge/I/O surface** (enumerate every JavascriptInterface method + JS↔native crossing). Target end-state = a **service layer + logic-free panels**; **KMP** is the natural shared-logic path (incremental, since logic is already Kotlin).

**Sequencing (firm):** ship V2.6 → build known-good V2.6 on the Mac Air first → KMP preflight + entanglement inventory → incremental KMP → iPad. One unknown at a time.

**Open:** bridge-vs-fork for iPad mesh source; service-layer local vs network; shared-layer language; Meshtastic TAK/CoT reconciliation; restore-file format.

---

## Esri / auth status
ArcGIS Location Platform account done 07-05 (billing attached; API key created — Basemaps + Static maps privileges, native-app referrer blank, expiration set). **Follow-ups:** (1) rotate the key (first value was exposed) → store in a password manager; (2) **verify async Export Tiles is authorized** under those privileges — this is the Pass-2 "curl de-risk before Kotlin" gate.

---

## Devices / environment
- **Droid 1** `8624SBCEDF00001789` — field/real-GPS; **USB temperamental** (moving it drops adb) → prefer **wireless ADB**, Termux, X-plore. Primary MBTiles test device (tiles cleared; ~6.4GB loose before). No sqlite3 → pull `.mbtiles` to PC.
- **Droid 2** `24039703201775` — dev; has the loose tree (5 type folders: SAT, SAT_LABELS_TRANSPORT, SAT_LABELS_PLACES, TOPO, TOPO+).
- **Build:** `./gradlew assembleGoogleRelease -x uploadCrashlyticsMappingFileGoogleRelease` (warm ~10-15min). Commit per verified item; cut AAB at EOD from committed; keep prior AAB as fallback.
- **Working style:** one command at a time (Fred executes + pastes); patches as dated Python scripts to `/c/Users/kixaz/Downloads/`.

---

## Process note (why this doc set exists)
Living docs hadn't been regenerated as downloadable files since 07-02 — a month of design (tile storage, route-state, cross-platform) lived only in memory + chat, which doesn't reach AllDocs and can't be pulled for reference. **Going forward: generate downloadable docs at EOD, save to both doc homes (Drive GroupTrack_docs + G:), commit to repo `docs/`, and roll into AllDocs.** Memory is for the assistant; files are for Fred.

---
*Handoff — regenerated 2026-07-06 to close the 07-02 → 07-06 gap.*
