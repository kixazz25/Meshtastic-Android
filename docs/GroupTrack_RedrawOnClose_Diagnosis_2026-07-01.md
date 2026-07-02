# GroupTrack — "Work-with-Artifacts doesn't redraw on close" — DIAGNOSIS (2026-07-01)
Bankable diagnosis. Baseline d719fbc95 (clean, has all 3 days of spatial work). This bug is a CHANGE TO ConvoyScreen.kt from the spatial/FIT work — NOT the Play Store version, NOT today's my-cart fix (that was reverted).

## SYMPTOM
Toggling/selecting artifacts in the Work-with-Artifacts panel, then CLOSING the panel, does not redraw the map to reflect the changes. State is correct on a full reload / other trigger, but panel-close alone doesn't refresh. ("Restricted when refreshes happen.")

## SEPARATION (Fred confirmed)
- Android track-RECORDING failure = OLD, pre-existing in the Play Store version. Separate bug. NOT this.
- Redraw-on-artifact-panel-close = RECENT, part of spatial implementation, a change to ConvoyScreen.kt. THIS bug.

## ROOT MECHANISM (found in ConvoyScreen.kt, d719fbc95)
1. **Reseed GATE, line ~794** (inside onViewportChanged JS interface):
   `if (MapStateStore.lastMapProcessed != "convoy") { ...re-read Trails/Tracks/Waypoints/Routes state from JSON... }`
   The convoy artifact state is re-read from JSON ONLY if lastMapProcessed != "convoy". If it's already "convoy", the panel's changes are NOT re-read on the next viewport event. (Gate added to avoid redundant reseed.)
2. **Panel dismiss, line ~1470:** `onDismiss = { showArtifactsPanel = false }` — closing the panel ONLY hides it. It does NOT trigger a reseed or redraw. So panel changes never get pushed to the map on close.
   Chain: change artifacts -> close panel -> onDismiss just hides -> gate at 794 is "convoy" so no re-read -> no redraw.

## ORIGIN COMMIT
`a43f80829` "feat: FIT recenters map to artifact bbox + 10% pad ... selection persists correctly. **Open: SEL/EDIT panel left open under FIT can override its state on close**"
- The FIT change reworked the redraw/reseed path and INTRODUCED this gate. Its own commit message FLAGS the panel-close-state issue as a KNOWN OPEN ITEM. This is the change Fred remembers as "restricted refreshes."

## FIX DIRECTION (do NOT implement tired / without MapStateStore.kt)
On artifact-panel onDismiss (1470), FORCE the reseed+redraw so panel changes appear:
  - Option A: reset the gate flag so the next viewport event re-reads: `MapStateStore.lastMapProcessed = ""` (or any value != "convoy") in onDismiss, so the gate at 795 fires.
  - Option B (cleaner if it exists): call the existing reseed+redraw path directly on dismiss, rather than poking a gate flag.
NEEDS before implementing: `MapStateStore.kt` — to see lastMapProcessed's type/writability and whether a dedicated redraw/reseed function exists (Option B preferred over flag-poking). Loosen the gate ONLY for the panel-close case; do NOT remove the gate globally (it prevents redundant reseed the FIT change needed).
VERIFY after fix: toggle artifact off/on/select -> close panel -> map redraws to match. AND confirm FIT recenter + selection-persist (the a43f80829 feature) still works — that's what the gate was protecting.

## STATE
On clean baseline d719fbc95. All spatial/sync/save/import/extension-DB work COMMITTED and safe. Today's my-cart fix was reverted (git checkout -- ConvoyViewModel.kt). Junk untracked: d1.db, legacy_sample.gpx (ignore).

---

## REFINED FINDING (deeper trace, 07-01 EOD) — toggle ALREADY persists+redraws; bug is in the CLOSE path
Corrected understanding from the real diffs + code:
- **Gate origin:** `e0182045a` "V2.5 map-independence: per-map MapStateStore ... lastMapProcessed gate". The gate (`if lastMapProcessed != "convoy"`) protects map INDEPENDENCE (convoy vs planning don't clobber). Line ~93 sets `lastMapProcessed = "convoy"` after each viewport process → subsequent viewport events on convoy SKIP reseed. This is the "tightening up" Fred was told about. KEEP the gate.
- **Redraw pattern (works):** FIT (`a43f80829`) after save fires `webViewRef...evaluateJavascript("...map.getBounds(); Android.onViewportChanged(...)")` — the round-trip that forces a redraw.
- **Panel TOGGLE already works (ConvoyScreen.kt ~1494-1500):** on toggle → sets trailState/trackState → `saveConvoyState()` (persists JSON) → grabs webViewRef and redraws. So toggling an artifact off/on DOES persist AND redraw at toggle time.
- **THE BUG is specifically the CLOSE path:** `onDismiss = { showArtifactsPanel = false }` (line ~1470) does NOT redraw. So whatever subset of panel changes doesn't redraw-at-change (candidates: SELECT/SELECTED state, EDIT operations, item check/uncheck at ~1790-1799) only reflects on close — and close does nothing.

## STILL TO PIN (next session, fresh — do NOT patch tired)
Determine WHICH panel changes fail to show:
- Toggle (off/on) → redraws at toggle (1499-1500). Works.
- SELECT/EDIT/item-check (~1790-1799): sets state + saveConvoyState() but check whether it fires the redraw round-trip like toggle does. If it saves but does NOT redraw, THAT's the bug — those changes only persist, never redraw, and close doesn't either.
Hypothesis: the SELECT/EDIT/item paths (1790-1799) call saveConvoyState() but MISS the getBounds→onViewportChanged redraw that the toggle path (1499-1500) has. Fix = add the same redraw round-trip to those paths AND/OR to onDismiss (1470).

## FIX CANDIDATE (verify persistence first)
On onDismiss (1470), fire the redraw round-trip (same as FIT/toggle), forcing gate reseed:
```
onDismiss = {
    showArtifactsPanel = false
    MapStateStore.lastMapProcessed = ""   // force gate to re-read saved panel changes
    webViewRef.value?.evaluateJavascript("try{var b=map.getBounds();Android.onViewportChanged(b.getNorth(),b.getSouth(),b.getEast(),b.getWest(),map.getZoom())}catch(e){}", null)
}
```
SAFE because: panel already persists via saveConvoyState() at change time; forcing reseed on close re-reads that saved state; gate stays intact for map-independence (only this convoy-panel close fires it). VERIFY: (1) confirm SELECT/EDIT changes are saved before close; (2) toggle/close redraws correct; (3) FIT + map-independence (planning vs convoy) still work.
NEEDS: MapStateStore.kt (confirm lastMapProcessed writable + type) and the ConvoyArtifactsPanel SELECT/EDIT callback code (~1780-1800) to confirm which paths miss the redraw.
