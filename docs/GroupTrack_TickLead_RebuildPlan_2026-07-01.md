# GroupTrack — Tick/Lead/Identity REBUILD PLAN (line-referenced, from real ConvoyViewModel.kt 1035L + ConvoyEngine.kt)
Baseline `d719fbc95`. This is the EXECUTABLE plan. Read after RedesignSpec. All line numbers from the 07-01 uploaded files (CartDiag instrumentation present — will be removed at the end).

## ✅ WHAT'S ALREADY CORRECT (do NOT rebuild — smaller job than feared)
- **`readLiveNodes` (835)** ALREADY builds the `!phone` device node when `nodeMap.isEmpty()` (843-859): nodeId="!phone", callsign=Build.MODEL, live phone GPS, ACTIVE, lastSeenMs=nowMs. "Device is always a node" is DONE. Keep.
- **`startTick()` (534-535)** ALREADY single-flights: `tickJob?.cancel()` before launch. Within ONE instance tick is not doubled. So the dual-tick is PURELY the two-instance problem.
- **`ConvoyEngine` (pure)** — status/heading/sortPositions/assignLeadTail/proximity/span all pure, no state. assignLeadTail tail logic (min-distance-accum + position fallback) is CORRECT — PRESERVE per parked-radio rationale.
- **Stale-packet rejection (readLiveNodes 866-880)** — rejects relay-rebroadcast packets older than rideStart, uses lastKnownPosition. Good, keep.

## 🔧 FIX 1 (STRUCTURAL, the real bug) — ONE ConvoyViewModel INSTANCE
ROOT: `ConvoyScreen.kt:128` and `ConvoyMapViewerScreen.kt:83` each call `hiltViewModel()` → two NavBackStackEntry scopes → two VMs → two init{} → two tickJobs → the paired CartDiag lines. ConvoyMapViewerScreen uses its VM for ONLY `downloadState` (line 278).
DECISION (Fred to pick — flagged, not chosen):
  1a. SHARE: both screens resolve to one nav-graph-scoped instance: `hiltViewModel(remember { navController.getBackStackEntry("<convoy_graph_route>") })` in BOTH ConvoyScreen:128 and ConvoyMapViewerScreen:83. One init, one tick.
  1b. DROP: ConvoyMapViewerScreen takes `downloadState` as a parameter (or a shared flow) and does NOT create a ConvoyViewModel at all. Smaller blast radius; map viewer never had convoy state anyway.
RECOMMEND 1b if downloadState is the ONLY use (it is, per grep) — the map viewer shouldn't own a convoy ViewModel. Confirm no other convoyViewModel.* use in that file first (grep). Either way: ONE tick after this. VERIFY: CartDiag identityHashCode single; pairs collapse to one line.

## 🔧 FIX 2 (IDENTITY, event-driven + record-lock) — small, hooks existing observer
CURRENT: `_myCartId` (291) defaults to HOTEL-10; only set at startGroupTrack (159). `resolveMyCartId` (292-296) returns `!%08x` if radio else `_myCartId.value` (→HOTEL-10 with no radio). The init observer (330-337) ALREADY collects myNodeInfo but does nothing with identity (comment only). HUD reads myCartId.value (ConvoyScreen:318) → shows HOTEL-10.
FIX — resolve identity IN the existing observer, guarded by record-lock:
  In init observer (330-337), replace the comment with:
    ```
    if (!_trackActive.value) {                      // RECORD-LOCK: don't reassign mid-recording
        _myCartId.value = if (num != null) "!%08x".format(num) else "!phone"
    }
    ```
  Also seed once at init (no-radio cold start): after startTick, if myNodeInfo null → _myCartId = "!phone".
  Change `resolveMyCartId()` (293-295) fallback: it can now simply `return _myCartId.value` (which the observer keeps correct) OR keep the `!%08x` compute — but the KEY is the else no longer yields HOTEL-10 because the observer set it to !phone. SAFEST: delete `ConvoySimulation.MY_CART_ID` default; init `_myCartId` to "!phone".
  At startGroupTrack (159): keep — this is now the explicit LOCK point (freezes current value for the session). The observer's `!_trackActive` guard means disconnect during recording can't change it.
  At stopGroupTrack: on stop, _trackActive=false → observer resumes event-driven idle resolution (no code needed beyond existing stop).
**⚠️ IDENTITY DRIVES PROGRESS REPORTING, NOT JUST THE HUD (Fred, 07-01 — corrected severity):** `myCartId` → `isMyCart` (ConvoyEngine computeProximity 143) drives the ENTIRE my-cart progress model:
- `feetToNodeAhead`/`feetToNodeBehind` (144-147) — distance to carts ahead/behind you
- `milesToLead` (148) — YOUR progress up the convoy toward the lead
- `milesToTail` (150) — your distance from the tail
- ConvoyViewModel:596 `state.nodes.firstOrNull { it.isMyCart }` — feeds the RADIO-GPS position feed (`onRadioPosition` → recording for radio-GPS devices)
So a wrong `myCartId` (HOTEL-10 / stale / orphan-instance value) means: isMyCart matches no real node → milesToLead/milesToTail/feet-ahead-behind are WRONG or zero → PROGRESS REPORTING IS BROKEN (not just a mislabeled HUD); AND line 596 returns null → radio-GPS position feed doesn't fire → can affect radio-GPS recording. The HUD label was only the VISIBLE symptom. ⇒ FIX 2 is HIGH severity: correct pre-RECORD identity = correct proximity/progress the whole session.

RESULT: HUD (ConvoyScreen:318) shows real cart (!phone / !%08x) immediately, no HOTEL-10, no RECORD dependency; mid-ride disconnect can't reassign identity. DELETE HOTEL-10 default (291) → prevents the ghost entirely.
VERIFY: CartDiag myCart shows !phone/!%08x pre-RECORD (not HOTEL-10); HUD label matches map highlight.

## 🔧 FIX 3 (LEAD — deterministic, self-heal) — Fred decision pending
CURRENT: lead only via setLeadCart (195) — from startGroupTrack auto-assign (162-166) or dialog. `if (lockedLeadNodeId == null)` at 161 skips if already set (stale-lock risk if not cleared; stopGroupTrack DOES clear at 180). Tick does NOT assign lead (585-587 comment). ConvoyEngine.assignLeadTail returns lead=null if lockedLeadNodeId not in active list → tick trail block skipped → no draw.
OPTIONS (Fred):
  3a. SELF-HEAL (restore, "work WITH tick"): in tick, if recording + exactly 1 node + lockedLeadNodeId==null (or not-in-active) → setLeadCart(that node). Guarantees solo cart is lead. (This is the rolled-back Stage-2 from TickEngine_Reference.)
  3b. Keep RECORD-only assignment but ensure lockedLeadNodeId is reset null on each fresh startGroupTrack BEFORE the assign block (so a stale lock never skips) — startGroupTrack currently does NOT reset it (stopGroupTrack does at 180; but if a session starts without a clean stop, stale). Add `lockedLeadNodeId = null` (or setLeadCart(null)) at top of startGroupTrack before the assign.
RECOMMEND BOTH: 3b (clean reset on start — 1 line) AS BASELINE + 3a (self-heal in tick) for robustness. Low risk. VERIFY: solo cold record → green/LEAD/draws every time.

## 🔧 FIX 4 (SNAP-TO-TRAIL) — SUPERSEDED by the SNAP2 HEALING MODEL below (see centerpiece section)
Extends existing proxy-snap (`proxySnapped` var, leadActualLat/Lon, 8121b5a6f). NEW: read spatial trail/track geometry (SpatialDbManager, READ-ONLY) and project the DRAWN lead point onto nearest trail segment within threshold. DISPLAY-ONLY — recorded GPX stays raw. Reuse OFF_TRACK_MILES for the "too far → stop snapping" gate. This crosses into SpatialDbManager (read-only) — deliberate new dependency. SPEC separately; do AFTER 1-3 are shipped and stable. Do NOT bundle into the identity/instance fix.

## ⛔ PRESERVE UNTOUCHED
ConvoyGpsService recording (certified), SpatialDbManager/artifact code (except FIX4 read-only), assignLeadTail TAIL half (parked-radio rationale), stale-packet rejection, the drawTrack output seam, ConvoyEngine purity.

## ORDER + VERIFY (build-safe)
1. FIX 1 (one instance) → build → CartDiag: one tick line, one hash. 
2. FIX 2 (identity observer + delete HOTEL-10) → build → HUD shows real cart pre-REC.
3. FIX 3 (lead reset + self-heal) → build → solo record draws green every time.
4. Remove CartDiag instrumentation (`git checkout` or strip), final build, on-device verify.
5. FIX 4 (snap-to-trail) → separate session, separate spec.
Commit after EACH green build (named files only). Bank a marker before FIX 4.


---

## 🎯 SNAP2 / LEAD-TRACK HEALING MODEL (Fred, 07-01 — the core lead-track design)
ONE continuous drawn lead track that HEALS from provisional to authoritative as THIS device drives it:
- **AHEAD (not yet driven):** provisional points from the LEAD cart's RADIO position, SNAPPED to trail/track geometry. Best-estimate route ahead.
- **BEHIND (already driven):** provisional points are DELETED and REPLACED by THIS DEVICE'S OWN recorded track (Android GPS or radio GPS — whatever it writes to the GPS recording), ALSO snapped to trail. Ground truth.
- As this device advances along the provisional lead track, each provisional point reached is REMOVED and replaced by the actual recorded (snapped) point → one continuous track: provisional ahead, authoritative behind. Both halves snap-to-trail.
- Source of replacement points = THIS DEVICE'S GPS RECORDING (my own track, not another cart's).

### Implementation consequences (shape the rebuild):
1. **Lead track becomes EDITABLE point-by-point** — NOT the current append-only `leadTrackSegments`. Need a structure supporting: delete-behind, keep-ahead, splice at the boundary each tick. (e.g. an ordered point list keyed by along-track position, with a "healed up to index N" pointer.)
2. **The heal boundary = this device's projected position on the lead track.** Each tick: project my recorded position onto the provisional line, replace everything behind that projection with my recorded+snapped points, leave ahead as provisional+snapped.
3. **Snap-to-trail (was Fix 4) is now CORE, not a late add-on.** Snap applies to BOTH the provisional lead points AND my recorded replacement points. So the snap-to-trail read-from-SpatialDbManager dependency is part of the lead-track engine, not a separate later feature. Reuse OFF_TRACK_MILES to gate snapping (off-trail → don't snap, draw raw).
4. **RECORDED TRACK IS THE SNAP2 (SNAPPED) TRACK (Fred, 07-01 — CONFIRMED).** The recording itself is smoothed — the written GPX is the snap-to-trail version, NOT raw GPS. Snap2 is applied to the RECORDED data to smooth recorded tracks (so saved/shared/reused tracks are clean, following trail geometry).
   **⚠️ OFF-TRAIL FALLBACK (required):** when NO trail/track is within snap threshold (or the device genuinely leaves the trail), the recording MUST fall back to RAW GPS — do NOT snap onto a trail you are not on. Gate with `OFF_TRACK_MILES`: on-trail (within threshold) → record SNAPPED; off-trail (beyond threshold) → record RAW. This gives smooth recordings on known routes without falsifying position off-route.
   RULE: record snap2 track when a trail/track is within snap threshold; record raw GPS when off-trail.

### Relation to existing code:
- Extends `proxySnapped`/`leadActualLat`/`leadActualLon` (the existing proxy-snap in startGroupTrack + tick). The current "snap on proxy" is a primitive version of this; the heal model replaces it with the provisional-ahead/recorded-behind design.
- `leadTrackSegments` (append-only StateFlow) → redesign to the editable healing structure.
- Ties to `nodeDistanceAccum` / lastKnownPosition already tracked per node.

### THIS IS THE REWRITE'S CENTERPIECE
The lead-track healing + snap2 is the actual NEW PROCESS being built (not a patch). It replaces the current append-only lead-track accumulation. The identity/instance/lead-assignment fixes (Fix 1-3) are PREREQUISITES that must be solid first (you can't heal a lead track if lead identity is wrong or two instances fight). Build order: stabilize identity+instance+lead (1-3), THEN build the heal/snap2 engine as the new lead-track process.
NEEDS to spec fully: SpatialDbManager read API (nearest trail segment to a point / bbox query) for snap; the recorded-track point stream (from ConvoyGpsService — the points as they're written); CONFIRMED: recorded track IS snapped (snap2), with off-trail raw fallback via OFF_TRACK_MILES.


---

## 🧨 DEMOLITION FINDING (Fred, 07-01) — LEGACY PROXY/SUBSTITUTION CONTAMINATES THE LEAD TRACK (REMOVE)
Fred's concern CONFIRMED in code: the current lead track can contain points that are NOT the lead's (or your) actual GPS — they are PROXY/SUBSTITUTED positions from other carts / resolved last-known values. Evidence in ConvoyViewModel.kt:
- **`proxySnapped` mechanism** (314, 689, 700, 731-734, 761, 770) — the `8121b5a6f` "snap on proxy": when the lead isn't reporting, the lead track is drawn from a PROXY (substituted) position, not the lead's real GPS.
- **`lastNodePositions` substitution** (632-634, 662) — other/resolved positions fed into segment building.
- The defensive comments themselves prove substitution happens: line 675 "Get lead's ACTUAL position from node (never use resolved/substituted)"; line 638 "OFF-TRACK carts keep their own last known position — never substitute". These guards exist BECAUSE substitution contaminates elsewhere.

**WHY IT MUST GO (snap2 model has no place for it):** the new model is — AHEAD = lead's OWN provisional radio position (snapped); BEHIND = THIS DEVICE'S OWN recorded track (snapped). Neither uses proxy/other-cart substitution. The heal replaces provisional with YOUR RECORDING, never with a proxy guess from another cart.

**DEMOLITION TARGETS (remove in the rewrite):**
- `proxySnapped` var + all proxy-snap logic (314, 689, 700, 731-734, 761).
- `lastNodePositions` substitution INTO the lead track (632-634, 662) — keep lastKnownPosition for stale-packet handling of DISPLAY nodes if needed, but NOT as a source of LEAD TRACK points.
- The append-only `_leadTrackSegments`/`_routeTrailSegments` accumulation (301-306, 771) — replaced by the editable heal structure.
- `_gpsTrailSegments` (303, 440-444) — audit: is it another legacy parallel track source? Confirm and likely remove.
KEEP: `nodeDistanceAccum` (tail's parked-radio rationale + lead 1/4-mile trigger), `leadActualLat/Lon` (the lead's REAL position — this is CORRECT, the non-substituted source the heal's provisional-ahead should use).

⇒ The rewrite's lead-track engine sources points from EXACTLY TWO clean places: (1) lead's real radio position (leadActualLat/Lon) for provisional-ahead, (2) this device's recorded GPS for authoritative-behind. Both snapped. NO proxy, NO other-cart substitution. Fred's contamination concern is the reason the append-only + proxy model must be demolished, not patched.


---

## 🎯 ROOT MECHANISM of the cross-cart contamination (Fred confirmed 07-01) — the "CART SUB" substitution
EXACT bug, ConvoyViewModel.kt tail→lead resolve loop (627-665):
- The loop walks tail→lead tracking `furthestLat/furthestLon` (furthest-along position seen so far).
- When a cart is SILENT (not reporting), lines 646-652 SUBSTITUTE the furthest trailing cart's position: `resolvedLat = furthestLat` with log "CART SUB: {callsign} silent — using trailing cart pos". (Legacy convoy-cohesion: assume a quiet cart is at least as far as the reporting cart behind it.)
- **THE CONTAMINATION (line 662):** `lastNodePositions[node.nodeId] = Pair(resolvedLat, resolvedLon)` — it WRITES THAT OTHER-CART position UNDER THIS node's id. So node X's "last known" now holds node Y's position.
- **NEXT TICK (632-634):** silent X reads `lastNodePositions[X]` → gets Y's position that was stored there. X has PERMANENTLY CLAIMED Y's last-known. → "last known position claiming another cart's last known" (Fred's exact words).
- Because resolvedPositions feed the drawn track, other carts' positions MIX INTO the lead/convoy track (Fred's other symptom). BOTH symptoms, ONE root: the CART SUB substitution writes cross-cart positions into per-node last-known storage.

**This is the legacy convoy-cohesion logic that corrupts the position model. REMOVE in the rewrite.** The snap2 model never substitutes cross-cart: provisional-ahead = lead's OWN real position (leadActualLat/Lon); authoritative-behind = THIS DEVICE'S OWN recording. A silent cart uses ITS OWN last-known ONLY (never another cart's), or is simply not drawn — never inherits a neighbor's position. 
DEMOLITION: remove the furthestLat/furthestLon substitution branch (646-652) and the cross-cart write at 662. If convoy-cohesion display is still wanted, it must NOT write another cart's position into a node's own last-known store — keep per-node last-known STRICTLY the node's own reported positions.
