# GroupTrack — Tick + Lead/MyCart Tracking REDESIGN SPEC (2026-07-01)
Consolidates: `GroupTrack_TickEngine_Reference.md` (what it does) + `GroupTrack_LeadCart_TrackRevision_DemolitionRebuild_2026-06-18.md` [2.1] (the settled rebuild) + 07-01 CartDiag findings. Purpose: make the tick/lead redesign EXECUTABLE in a clean session WITHOUT damaging the spatial/artifact/recording model.

## ✅ WHY THIS IS SAFE TO REPLACE (the boundaries, xref-confirmed)
The live-convoy-track subsystem is CONTAINED behind clean seams:
- **Single map output:** `drawTrack(json)` — ONE WebView call at `ConvoyScreen.kt:390`, fed by `leadTrackSegments`. That is the ENTIRE live-track render surface.
- **Pure compute seam:** `ConvoyEngine.compute(nodes, myCartId, nowMs)` (`ConvoyViewModel.kt:589`) is PURE — inputs→state, no side effects → `_convoyState.value = state` (597). Safe to rebuild around.
- **State UI reads:** `_convoyState`, `leadTrackSegments`, `routeTrailSegments`. Bounded set.

## ⛔ OUT OF SCOPE — DO NOT TOUCH (protects the model)
These are SEPARATE code with their OWN JS bridge — the redesign must not modify them:
- **`SpatialDbManager.kt` (1705 lines), `SpatialDisplayManager.kt` (160 lines)** — displayed DB tracks (saved/imported), Work-with-Artifacts, via `jsShow/jsUpdate/fitBounds` (NOT `drawTrack`). Zero overlap.
- **Recording→GPX** (`ConvoyGpsService`) — CERTIFIED WORKING (walk-test 07-01). Writes GPX independently. Leave.
- **Spatial DB, artifact_aliases, track services layer** (`d719fbc95`) — untouched.
The demolition doc's boundary rule holds: this is LIVE CONVOY TRACK (lead-cart), NOT displayed DB tracks.

## 🔎 WHAT'S WRONG (07-01 findings, why the current mess fails)
1. **TWO ViewModel instances / TWO tick loops.** `ConvoyScreen.kt:128` and `ConvoyMapViewerScreen.kt:83` each call `hiltViewModel()` → separate NavBackStackEntry scopes (nav destinations at `ConvoyNavigation.kt:61` and `:208`) → each `init{}` launches its own `tickJob` (`ConvoyViewModel.kt:536`). CartDiag log (07-01 03:31–03:33) shows paired tick lines ~170ms apart, same PID: one instance correct (LEAD/green/draws), one orphan stuck (HOTEL-10/null-lead/never draws). Map reads whichever → intermittency. BOTH ticks always ran (map-viewer VM since `2cb99c404` V2.4; tick since `970ad212b` original).
2. **WHY IT BROKE RECENTLY ("why now"):** identity USED to be set every tick in `readLiveNodes` (~848) and the compute path (~332) — comments now read `// _myCartId no longer set here — set ONCE in startGroupTrack()`. So both instances self-identified every tick and were harmless. Moving identity to RECORD-only (`startGroupTrack` 156-158) left the orphan (never records) stuck at the `HOTEL-10` construction default. (Related: `eed497680` "identity locked at RECORD time"; lead assignment also REMOVED from tick — `ConvoyViewModel.kt:585` "Lead assignment REMOVED from tick — only through setLeadCart()".)
3. **`_myCartId` vs `resolveMyCartId()` SPLIT — USER-VISIBLE ON THE HUD (confirmed 07-01).** Display/compute uses `resolveMyCartId()` fresh each tick (`:591`) → map shows REAL cart, never HOTEL-10 (confirmed: HOTEL-10 is log-only, not on-screen). But lead-assign uses the stale `_myCartId.value` (`:165-166`) → HOTEL-10 on the orphan. Two identity sources.
4. **Self-heal rolled back.** TickEngine_Reference Stage 2 ("recording + 1 node + no lead → auto-assign lead") was removed (AllDocs 4033). Nothing recovers a solo cart to lead in-tick.
5. **`_trackLeadOnly=true`** → only the lead's segments draw. No consistent lead ⇒ no track (the visible symptom).
6. **HUD SHOWS HOTEL-10 (Fred confirmed 07-01, my-cart HUD).** `ConvoyScreen.kt:318` reads `viewModel.myCartId.value` (the STALE `_myCartId` StateFlow → HOTEL-10 until startGroupTrack). BUT `ConvoyScreen.kt:293` `isMine = node.isMyCart` comes from `computeProximity(resolveMyCartId())` (fresh, line 591). So the HUD LABEL uses the stale source while the map HIGHLIGHT uses the fresh one → HUD shows HOTEL-10 while the correct cart is highlighted. Exact surfacing of the split. The event-driven identity model fixes this: resolve `_myCartId` on connect/no-node so line 318 shows the real cart immediately, no HOTEL-10, no RECORD dependency.

---

## 🧭 IDENTITY MODEL — EVENT-DRIVEN, not per-tick (Fred, 07-01 — key architectural decision)
Identity (`myCartId`) must be resolved on ACTIVITY/STATE-CHANGE EVENTS, NOT recomputed every tick. Per-tick resolution is a lazy catch-all doing work 99% of the time for nothing, and is the source of the `_myCartId` (stale StateFlow) vs `resolveMyCartId()` (per-tick) SPLIT.

**Resolve identity ONCE on these events only:**
- **On node/radio CONNECT** → identity = `"!%08x".format(myNodeNum)` (the radio's id). Set once, stable until next event.
- **On NO NODE / DISCONNECT** → identity = `"!phone"`. Set once, stable.

**Tick just READS the already-resolved identity — never computes it.** No `resolveMyCartId()` call inside tick. No RECORD-time identity set. No `HOTEL-10` catch-all default (delete it).

**Implementation:** observe the radio-connection state flow (`myNodeInfo` / `nodeRepository` connection — already emits on change) and resolve identity in its collector (on connect / on disconnect). ONE identity value, event-set, read everywhere (display AND lead). This collapses `_myCartId` + `resolveMyCartId()` into one source and removes the per-tick recompute.

NEEDS from code: WHERE radio connection state is observed (the flow that emits connect/disconnect) — hook identity resolution there. (Confirm from ConvoyViewModel.kt: myNodeInfo/nodeRepository observation.)

## ⚠️ CRITICAL SAFETY REFINEMENT (Fred, 07-01) — IDENTITY LOCKS DURING RECORDING
Pure event-driven identity is UNSAFE alone: an INADVERTENT disconnect mid-ride (BLE dropout, T1000-E supervision timeout ~8s, radio glitch) would flip identity `!%08x`→`!phone` WHILE RECORDING, breaking the lockedLeadNodeId==myCartId match and corrupting the track. A momentary radio blip must NOT reassign who you are mid-track.

**THE CORRECT MODEL = HYBRID (event-driven when idle, LOCKED when recording):**
- **IDLE (before/outside recording):** identity is EVENT-DRIVEN — resolves on connect (`!%08x`) / no-node (`!phone`) for pre-record display. Free to change; nothing committed yet.
- **AT RECORD PRESS:** identity LOCKS to whatever it is at that moment. Frozen for the whole session. (This is the intent behind commit `eed497680` "identity locked at RECORD time" — correct goal, but it created the 2-instance side effect; the rebuild keeps the LOCK, fixes the instance issue.)
- **DURING RECORDING:** disconnect/connect events DO NOT change identity. Lock holds. An inadvertent BLE drop cannot reassign identity mid-track. (The GPX keeps recording from phone GPS regardless — recording is independent.)
- **ON STOP:** unlock → return to event-driven idle resolution.

Reconciles both concerns: event-driven avoids the per-tick catch-all + pre-record HOTEL-10 staleness; the RECORD-time LOCK avoids inadvertent-disconnect corruption mid-ride. Neither alone is correct.

NEEDS from code (to spec exactly): (a) the connection-state flow (connect/disconnect events); (b) the record lifecycle (start/stop) and where the lock is set/cleared; (c) how disconnect currently behaves during recording (does anything already guard it? autopause/BLE-reconnect logic — see FT-04/05 sleep/reconnect, commit 25fb4219e). Must ensure a mid-record disconnect does NOT touch identity.

## 🎯 THE NEW DESIGN (one-lead / one-track, per DemolitionRebuild [2.1])
Principles: ONE ViewModel, ONE tick, ONE identity source, lead resolvable WITHOUT a radio, "device is always a node" (StandaloneMode_Spec).
1. **ONE instance:** both screens resolve to a single shared ConvoyViewModel (nav-graph-scoped `hiltViewModel(sharedBackStackEntry)`), OR the map-viewer drops its VM (it only reads `downloadState` at `ConvoyMapViewerScreen.kt:278`). → one `init{}`, one `tickJob`.
2. **ONE identity source:** collapse `_myCartId` and `resolveMyCartId()`. Resolve the device identity ONCE per tick (radio `!%08x` or `!phone`) and use it for BOTH display AND lead. Kill the HOTEL-10 default path (prototype ghost — appearing = failure per Rule 4).
3. **Device is always a node:** `readLiveNodes` always includes the device (`!phone` when no radio) with live GPS + `isMyCart`, refreshed every tick (StandaloneMode_Spec, TickEngine_Reference).
4. **Lead resolution in-tick (self-heal restored, "work WITH tick"):** recording + exactly one node ⇒ that node is lead, every tick; multi-cart ⇒ locked/dialog selection. Lead is derived deterministically, not left null.
5. **Single draw path:** compute → `leadTrackSegments` → the one `drawTrack()` call. Unchanged output seam.

---

## 🎯 KEY DESIGN PILLAR (Fred, 07-01) — SNAP LEAD PATH TO TRACK/TRAIL WHENEVER POSSIBLE
The lead cart's DRAWN path should SNAP to a known track or trail whenever possible, to SMOOTH the lead path — instead of drawing the raw jittery GPS breadcrumb (broadcast delays, GPS scatter, ~5s radio gaps). When the lead is on/near a known trail, follow the trail geometry → clean path matching the real route.

**MECHANISM:** for each new lead position, if it's within a threshold of a known trail/track segment, PROJECT (snap) the drawn point onto that segment instead of using raw GPS. Produces a smooth on-route line.

**RELATION TO EXISTING CODE:** `8121b5a6f` "lead track proxy fix (actual GPS, snap on proxy)" + tick var `proxySnapped` — there is ALREADY a snap mechanism (snap to a proxy/trailing-cart position). This pillar EXTENDS/REDIRECTS it: snap to KNOWN TRAIL/TRACK GEOMETRY, not just proxy positions. Reconcile the two in the rebuild (proxy-snap vs trail-snap; trail-snap likely preferred when a trail is available).

**DESIGN QUESTIONS (Fred to decide during rebuild):**
1. Snap-target PRIORITY: loaded trail geometry first, or a previously-recorded track, or nearest-of-either?
2. Snap THRESHOLD: how far off-trail before snapping STOPS and raw GPS is drawn — so a genuine off-trail excursion isn't force-snapped back onto a trail you actually left. (Off-trail detection already exists — TickEngine stage 7 OFF_TRACK_MILES; reuse that threshold logic.)
3. DISPLAY-ONLY vs RECORDED: snap the DRAWN lead path only; keep the RECORDED GPX RAW (real GPS). Snapping the recording would FALSIFY the track data. (Strongly assume display-only — confirm.)

**⚠️ ARCHITECTURAL NOTE:** snap-to-trail CROSSES the boundary — the lead-path rebuild would need to READ trail/track geometry from `SpatialDbManager` (previously out-of-scope for the lead rebuild). This is a NEW, DELIBERATE dependency: lead-path compute reads spatial trail geometry (read-only) to snap. Keep it read-only; do not let the lead rebuild WRITE to spatial. This is a design pillar with its own data path, not just a smoothing filter — spec the spatial read seam explicitly.

NEEDS from code: the existing proxy-snap logic (proxySnapped, `8121b5a6f`), the off-track distance calc (stage 7), and the SpatialDbManager read API for trail/track geometry near a point (bbox/nearest-segment query).

## 🧨 DEMOLITION INVENTORY (remove, each mapped to replacement) — fill Part 1 of the 06-18 doc
| Remove | Where | Replaced by |
|---|---|---|
| 2nd `hiltViewModel()` instance | ConvoyMapViewerScreen.kt:83 | shared instance (or drop VM; pass downloadState) |
| duplicate `tickJob` (2nd instance) | ConvoyViewModel.kt:536 via 2nd init | single instance → single tick |
| `_myCartId` HOTEL-10 default + split | ConvoyViewModel.kt:289, 165-166 | one identity source (resolve once/tick) |
| identity-at-RECORD-only | startGroupTrack 156-158 | identity resolved every tick for all uses |
| lead-removed-from-tick | comment 585-586 | in-tick deterministic lead (self-heal) |
| HOTEL-10 constant | ConvoySimulation MY_CART_ID | delete once nothing defaults to it |
| ConvoyNavigation.kt.bak | nav dir | stale backup — delete |
KEEP: ConvoyEngine.compute (pure), drawTrack seam, leadTrackSegments/routeTrailSegments, ConvoyGpsService recording, all Spatial/artifact code.

## ANSWER TO "can we replace without irreparable damage?"
YES. The live-track subsystem sits behind two clean seams (`ConvoyEngine.compute` in, `drawTrack` out) and is fully separate from the spatial/artifact/recording model (different files, different JS bridge). Rebuild the tick/lead/identity internals against those seams; the model is untouched. Do it in a CLEAN session (not a time-boxed tail), Part-1-demolition-then-Part-2-rebuild per the 06-18 doc, each removal traceable to its replacement (table above).

## NOT TODAY
Do NOT begin the rebuild in a time-boxed remnant. This spec + the two source docs = a loaded, safe, executable rebuild for a fresh session. Today's surgical shared-instance fix, if attempted, is a STOPGAP on code slated for demolition — revert it before the rebuild.

---

## 🟢 TAIL CART — NO ACTION NEEDED (keep current method exactly)
Used for: centering track/group + inter-cart DISTANCE calcs. Fred: NOT mission-critical; jitter is OK.
CURRENT METHOD (verified from ConvoyEngine.kt assignLeadTail, lines 112-115 + comment line 97):
- **Primary:** tail = `tailNodeId` = the MINIMUM distance-accumulator node (cart that has travelled the LEAST distance) — `nodeDistanceAccum`, recomputed dynamic every tick.
- **Fallback (if tailNodeId null):** `active.maxByOrNull { convoyPosition }` = furthest-back by CURRENT-GPS projection (computeSortPositions projects current lat/lon onto the heading vector).
So it is a BLEND: distance-accumulator primary, current-GPS-projection fallback. Both mechanisms are correct and working.

**⭐ DESIGN RATIONALE (Fred — DO NOT LOSE THIS):** tail uses MINIMUM DISTANCE ACCUMULATOR (not pure GPS position) DELIBERATELY, so a cart that never actually drives the track — e.g. a SPARE RADIO left in a parked car at the trailhead — does NOT get counted as tail and artificially EXTEND the group span. A parked radio accumulates ~zero distance → correctly excluded from defining the tail; the real tail is the last cart actually MOVING along the route. Pure GPS-position projection would fall for the parked-radio problem (an idle radio could project as "furthest back"); the distance-accumulator primary is what prevents it. This is non-obvious — a rebuilder who "simplifies" tail to GPS-position would REINTRODUCE the parked-radio bug. PRESERVE the distance-accumulator method and this intent exactly.
DECISION: DO NOTHING — keep this exact method. No hysteresis, no smoothing (over-engineering a non-critical value; jitter acceptable).
REBUILD NOTE: `assignLeadTail` is a SHARED function (does BOTH lead and tail). When the rebuild changes the LEAD half, PRESERVE the tail half (lines 112-115 logic + the distance-accumulator feed) UNCHANGED. Do not disturb tailNodeId / nodeDistanceAccum / computeSortPositions when reworking lead.
