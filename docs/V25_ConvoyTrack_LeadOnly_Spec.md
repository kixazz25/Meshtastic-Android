# GroupTrack V2.5 — Convoy Map Lead Track Fix Spec

**Date:** May 27, 2026  
**Status:** RESEARCH COMPLETE — pending code review before implementation  
**Priority:** Post-Planning-Map cleanup  

---

## Problem Statement

The convoy map should draw ONE track line representing the lead cart's path during a ride. Instead, 2–3 overlapping and sometimes divergent lines are drawn. The visual result is messy, confusing, and unusable for the primary use case: desert OHV group riding where only the lead cart's path matters.

---

## Root Cause Analysis

### The Pipeline (Current State)

The live track is rendered by a single JS call:

    ConvoyScreen.kt:302 → wv.evaluateJavascript("drawTrack(" + json + ")")

This call fires inside a `LaunchedEffect` whenever `trackSegments` changes. `trackSegments` is assembled at line 273 from THREE independent state flows:

| Flow | Source | Write Locations | What It Contains |
|------|--------|-----------------|------------------|
| `leadTrackSegments` | `colorSegmentsByNode()` result | ViewModel:142 (clear), ViewModel:707 (write) | Segments from ALL nodes, colored by node marker color |
| `gpsTrailSegments` | Phone GPS location callback | ViewModel:143 (clear), ViewModel:434 (append) | Phone device GPS trail, independent of radio |
| `routeTrailSegments` | Per-tick radio positions + batch accumulator | ViewModel:141 (clear), ViewModel:661 (append), ViewModel:686 (batch) | ALL carts' radio position changes |

### The Assembly (Line 273–275)

```kotlin
val trackSegments = remember(rawSegments, gpsTrail, routeTrail, trackLeadOnly) {
    val activeSegments = if (trackLeadOnly) rawSegments else (rawSegments + routeTrail)
    // ... gpsTrail may also be included below
}
```

When `trackLeadOnly = true`:
- `routeTrailSegments` is excluded (good)
- `leadTrackSegments` is kept — but it already contains ALL nodes from `colorSegmentsByNode` (bad)
- `gpsTrailSegments` is in the remember key and may still be included (bad)

### Why Multiple Lines Appear

1. **`colorSegmentsByNode` (ConvoyEngine.kt:160)** processes every node in `state.nodes`, creating segments colored by each node's marker color. Called from ViewModel:710 inside `tick()`. Even when `trackLeadOnly = true`, the output contains segments from all carts.

2. **Batch builder (ViewModel:667–686)** iterates `lastNodePositions` for ALL nodes. Every cart that moved gets a new segment added to `routeTrailSegments`.

3. **Phone GPS trail (ViewModel:434)** runs independently via the device location provider, producing a third line from a completely different position source than the radio broadcasts.

### Missed Broadcast Effect

When a radio broadcast is missed, the next received position creates a straight-line segment from the last known position to the new position. This causes:
- Squared turns and corner-cutting on the drawn track
- Different carts miss different broadcasts → each has a slightly different version of the track
- The visual result is overlapping lines that diverge at missed-broadcast points

---

## Current Settings

| Setting | Location | Purpose |
|---------|----------|---------|
| `trackLeadOnly` | ConvoyViewModel | When true: draw lead cart only. When false: draw all carts in pin colors |
| `trackActive` | ConvoyViewModel | Whether track recording is active |
| `showLeadTrack` | ConvoyViewModel | Visibility toggle for the track layer |

The "draw all carts in pin colors" mode (`trackLeadOnly = false`) has never been used in the field and is not needed for the desert OHV use case.

---

## Data Structure

```kotlin
// ConvoyEngine.kt:22
data class LeadTrackSegment(
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val endLat: Double = 0.0,
    val endLon: Double = 0.0,
    val color: String = "#000000",
    val nodeId: String = ""     // ← KEY FIELD for filtering
)
```

The `nodeId` field is already present on every segment. It identifies which cart generated the segment. This is the gate.

---

## Key Functions Involved

| Function | File:Line | Role |
|----------|-----------|------|
| `tick()` | ConvoyViewModel.kt:528 | Main loop, runs every TICK_MS |
| `ConvoyEngine.compute()` | ConvoyEngine.kt:31 | Produces ConvoyState with all node positions |
| `colorSegmentsByNode()` | ConvoyEngine.kt:160 | Creates colored segments for ALL nodes |
| `readLiveNodes()` | ConvoyViewModel.kt:745 | Reads radio positions for all nodes |
| `startGroupTrack()` | ConvoyViewModel.kt:140 | Clears all three flows on ride start |
| `drawTrack()` | convoy_map.html JS | Renders polyline segments on Leaflet map |

### Call Sites That Write Segment Data (7 total)

| # | Location | Flow Written | What |
|---|----------|-------------|------|
| 1 | ViewModel:141 | `_routeTrailSegments` | Clear on startGroupTrack |
| 2 | ViewModel:142 | `_leadTrackSegments` | Clear on startGroupTrack |
| 3 | ViewModel:143 | `_gpsTrailSegments` | Clear on startGroupTrack |
| 4 | ViewModel:434 | `_gpsTrailSegments` | Append from phone GPS callback |
| 5 | ViewModel:661 | `_routeTrailSegments` | Append per tick (lead position, when trackActive) |
| 6 | ViewModel:686 | `_routeTrailSegments` | Batch append from ALL nodes' distance accumulator |
| 7 | ViewModel:707–710 | `_leadTrackSegments` | Set from colorSegmentsByNode (ALL nodes) |

### JS Functions on convoy_map.html

| Function | Line | Purpose |
|----------|------|---------|
| `drawTrack(segments)` | ~470/495 | Renders lead track polyline segments |
| `loadTrackFile(name, json, color)` | ~442/445 | Loads saved track file overlay |
| `showTracks()` | ~489/288 | Shows saved track layers |
| `hideTracks()` | ~497/291 | Hides saved track layers |
| `removeTrackFile(name)` | ~451/454 | Removes a saved track overlay |

### Separate System: Pin Movement (NOT affected)

Cart pins are updated independently at ConvoyScreen.kt:207:
```
wv.evaluateJavascript("addMarker('${node.nodeId}', ${lat}, ${lon}, '$color', '$label', ...)")
```
This runs for ALL nodes every tick and is completely separate from the track pipeline. Simplifying the track pipeline does not affect pin movement.

---

## Recommended Fix

### Approach: Add nodeId gate to existing pipeline

**Rationale:** Fred prefers a targeted fix over a full pipeline rewrite. Adding the lead cart's nodeId to existing requests lets us filter out non-lead segments without removing code paths we don't fully understand yet.

### Changes Required

#### 1. ConvoyViewModel.kt — Filter `colorSegmentsByNode` output (Line ~707)

BEFORE: `_leadTrackSegments.value` receives ALL nodes' segments from `colorSegmentsByNode`.

AFTER: Filter the result to only segments matching `currentLeadNodeId`:

```kotlin
val allSegments = ConvoyEngine.colorSegmentsByNode(...)
_leadTrackSegments.value = allSegments.filter { it.nodeId == currentLeadNodeId }
```

#### 2. ConvoyViewModel.kt — Filter batch builder (Lines ~667–686)

BEFORE: Iterates ALL `lastNodePositions`, creates segments for every node that moved.

AFTER: Skip non-lead nodes:

```kotlin
for ((nodeId, pos) in currentNodePositions) {
    if (nodeId != currentLeadNodeId) continue  // ← ADD THIS
    val prev = lastNodePositions[nodeId]
    // ... rest unchanged
}
```

#### 3. ConvoyViewModel.kt — Remove or gate GPS trail (Line ~434)

The phone GPS trail is redundant when radio positions are available. Either:
- Remove the GPS trail writes entirely, OR
- Gate with: `if (myNodeId == currentLeadNodeId)` so only the lead device's phone GPS contributes

#### 4. ConvoyScreen.kt — Simplify assembly (Line ~273)

With all three flows now filtered to lead-only, the assembly simplifies:

```kotlin
val trackSegments = remember(rawSegments) {
    rawSegments  // Already lead-only after filter
}
```

### What Gets Removed (After Validation)

After confirming the fix works:
- `trackLeadOnly` setting and all UI for it
- `routeTrailSegments` flow (merged into filtered `leadTrackSegments`)
- `gpsTrailSegments` flow (redundant with radio-based lead track)
- Multi-cart color logic in `colorSegmentsByNode` (optional — function still works, output just gets filtered)
- Related UI toggles if any

### What Stays

- `colorSegmentsByNode` function (still useful, just filtered)
- `LeadTrackSegment` data class (still used)
- `drawTrack` JS function (still renders the segments)
- Pin movement via `addMarker` (completely separate, untouched)
- Track recording via `ConvoyGpsService` (file recording, separate from display)

---

## Diagnostic Step (Before Implementation)

Add `TRACK-DBG` logging at all 4 data-write locations to confirm the diagnosis on a live device with 2+ carts:

```
Log.w("TRACK-DBG", "GPS-TRAIL nodeId=${seg.nodeId} start=${seg.startLat},${seg.startLon}")
Log.w("TRACK-DBG", "ROUTE-TICK nodeId=${seg.nodeId} start=${seg.startLat},${seg.startLon}")
Log.w("TRACK-DBG", "ROUTE-BATCH nodeId=${s.nodeId} ...")
Log.w("TRACK-DBG", "LEAD-WRITE distinctNodes=${ids.size} nodeIds=$ids")
```

Watch with: `adb logcat -s TRACK-DBG`

Expected result: multiple distinct nodeIds appearing in ROUTE-BATCH and LEAD-WRITE logs, confirming that non-lead carts are generating track segments.

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Filter removes segments that look like lead but have wrong nodeId due to timing | Log confirms actual nodeIds before filtering |
| Lead cart changes mid-ride (new lead assignment) | Filter uses `currentLeadNodeId` which updates dynamically |
| GPS trail provides smoother path than radio-only | Test with/without GPS trail; can re-enable if radio track is too choppy |
| `colorSegmentsByNode` has side effects beyond segment generation | Review function thoroughly before removing — spec recommends filter-first |
| Missed broadcasts cause gaps regardless of fix | Separate issue — interpolation or increased broadcast rate are future improvements |

---

## Implementation Sequence

1. Deploy TRACK-DBG logging patch
2. Test with 2+ carts, capture log output
3. Confirm multi-nodeId diagnosis
4. Apply nodeId filter at write locations 5, 6, 7
5. Gate or remove GPS trail writes (location 4)
6. Simplify assembly at ConvoyScreen.kt
7. Test: one line, lead cart only, corners acceptable
8. Remove dead settings and unused flows
9. Commit

**Do not proceed to step 4 without step 2–3 confirmation.**
