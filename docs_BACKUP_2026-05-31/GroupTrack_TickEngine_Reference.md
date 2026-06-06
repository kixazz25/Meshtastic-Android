# GroupTrack Tick Engine Reference
## ConvoyViewModel.kt tick() + ConvoyEngine.kt

---

## TICK CYCLE OVERVIEW (every TICK_MS milliseconds)

```
tick()
  │
  ├── 1. GET NODES
  │     └── Simulation mode: ConvoySimulation.tick(nowMs)
  │     └── Live mode: readLiveNodes(nowMs)
  │
  ├── 2. SELF-HEAL LEAD ASSIGNMENT
  │     └── If recording + 1 node + no lead → auto-assign that node as lead
  │
  ├── 3. CONVOY ENGINE COMPUTE ──────────────────────────────
  │     │
  │     ├── computeStatus(node, nowMs)
  │     │     └── age = nowMs - node.lastSeenMs
  │     │     └── age >= LOST_MINUTES    → LOST (removed from active)
  │     │     └── age >= SIGNAL_DROP_MIN → SIGNAL_DROP (still in active)
  │     │     └── else                   → ACTIVE
  │     │
  │     ├── computeHeading(nodes)
  │     │     └── Median heading of ACTIVE nodes moving > 3mph
  │     │     └── Returns convoy direction of travel
  │     │
  │     ├── computeSortPositions(nodes, heading)
  │     │     └── Projects each node's lat/lon onto the heading vector
  │     │     └── Sorts DESCENDING by projection (front → back)
  │     │     └── Assigns convoyPosition: 1 = front, N = back
  │     │     └── LOST nodes appended at end
  │     │
  │     ├── assignLeadTail(sorted, lockedLeadNodeId, tailNodeId)
  │     │     └── Lead: LOCKED by nodeId (set via dialog, never changes)
  │     │     └── Lead is NULL if locked node is not in active list
  │     │     └── Tail: by tailNodeId or fallback to max convoyPosition
  │     │
  │     ├── computeSpan(lead, tail)
  │     │     └── Haversine miles between lead and tail
  │     │
  │     └── computeProximity(nodes, myCartId)
  │           └── For each node: feetToNodeAhead, feetToNodeBehind
  │           └── milesToLead, milesToTail
  │           └── Sets isMyCart flag
  │
  ├── 4. FEED RADIO GPS (if device has no GPS hardware)
  │     └── useRadioGps == true → feed myCart position to GPS service
  │     └── Only for devices WITHOUT GPS hardware
  │
  ├── 5. DEBUG LOGGING
  │     └── Logs: lead callsign, trackFrom nodeId, locked flag, distance accum
  │
  ├── 6. TRAIL ACCUMULATION ─────────────────────────────────
  │     │
  │     ├── LEAD-ONLY MODE (_trackLeadOnly == true)
  │     │     └── Gets leadNode from state.lead
  │     │     └── *** IF LEAD IS NULL → ENTIRE BLOCK SKIPPED ***
  │     │     └── Compares to lastLeadLat/lastLeadLon
  │     │     └── If position changed → create LeadTrackSegment
  │     │     └── Adds segment to _routeTrailSegments
  │     │     └── Updates lastLeadLat/lastLeadLon
  │     │
  │     └── MULTI-CART MODE (_trackLeadOnly == false)
  │           └── Iterates ALL state.nodes
  │           └── Skips nodes with lat=0, lon=0
  │           └── Compares to lastNodePositions[nodeId]
  │           └── If position changed → create segment
  │           └── *** 0.25mi JUMP FILTER *** (still present on multi path)
  │           └── Updates lastNodePositions[nodeId]
  │
  ├── 7. OFF-TRACK DETECTION
  │     └── For each node, compute min distance to any trail segment
  │     └── If > OFF_TRACK_MILES threshold → mark as off-track
  │
  ├── 8. COLOR SEGMENTS
  │     └── Lead-only: all segments black
  │     └── Multi-track: each node's segments get node's markerColor
  │
  └── 9. UPDATE SELECTED NODE HUD
        └── Refresh selected node data if HUD is showing node detail
```

---

## KEY DATA STRUCTURES

| Variable | Type | Purpose |
|---|---|---|
| `lastLeadLat/Lon` | Double? | Lead cart's last position THIS SESSION |
| `lastNodePositions` | Map<String, Pair<Double,Double>> | All carts' last positions THIS SESSION |
| `currentLeadNodeId` | String? | Currently tracked lead node ID |
| `lockedLeadNodeId` | String? | Permanently locked lead (never changes) |
| `_routeTrailSegments` | List<LeadTrackSegment> | Accumulated trail segments |
| `_leadTrackSegments` | List<LeadTrackSegment> | Colored segments for display |
| `nodeDistanceAccum` | Map<String, Double> | Per-node distance traveled |

---

## CONVOY ORDER DETERMINATION

Convoy order is computed EVERY TICK by `computeSortPositions`:

1. Calculate convoy heading from median of active moving nodes
2. Project each node position onto the heading vector: `lat * cos(heading) + lon * sin(heading)`
3. Sort descending — highest projection = furthest along route = position 1 (LEAD)
4. LOST nodes are appended at end (not sorted)

**This means convoy order is DYNAMIC** — it changes as carts move and pass each other.

---

## THE LEAD DROPOUT PROBLEM (Task 2)

### Current behavior:
```
state.lead is populated by assignLeadTail()
  └── Finds node matching lockedLeadNodeId
  └── If that node is LOST → not in active list → state.lead = NULL
  └── tick() line 577: if (leadNode != null) { ... }
  └── leadNode is null → trail block SKIPPED → track display freezes
```

### Required behavior:
```
For EVERY cart on EVERY tick:
  1. Cart is ACTIVE with valid position → use its reported position
  2. Cart is SIGNAL_DROP or LOST → use lastKnownPosition
  3. Check cart behind (next convoyPosition) → if behind cart is
     FURTHER ALONG than this cart's lastKnownPosition → substitute
     behind cart's position as this cart's new lastKnownPosition
  4. Draw segment from previous position to new position — ALWAYS
  5. Track line NEVER goes backwards
```

### "Further along" test:
Use the same heading projection as computeSortPositions:
```
projection = lat * cos(heading) + lon * sin(heading)
```
If trailing cart's projection > this cart's lastKnown projection → trailing cart is ahead → substitute.

---

## FILES

| File | Lines | Purpose |
|---|---|---|
| ConvoyViewModel.kt | 877 | tick(), trail accumulation, state management |
| ConvoyEngine.kt | 178 | Pure computation: status, heading, sorting, proximity |
| ConvoyGpsService.kt | ~480 | GPS recording, GPX/KML writing |
| ConvoyScreen.kt | ~2000 | UI, map display, drawTrack(), show downloads |
| ConvoyConfig.kt | ~60 | Constants: tile paths, thresholds, feature flags |
