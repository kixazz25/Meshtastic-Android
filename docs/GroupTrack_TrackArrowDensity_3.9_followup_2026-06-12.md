# GroupTrack — Track direction-arrow DENSITY problem + fix approach ([3.9] follow-up)

_2026-06-12 · GroupTrack V2.5 · ON HOLD until after the [2h] artifact-detail card ships. This is a problem statement + approach, not a task to start now. recommit14: fold into the [3.9] notes / next doc set._

## STATUS
- **ON HOLD.** Do NOT start until the [2h] detail card (pieces 3+4) builds + device-tests + commits.
- This is a SECOND, separate issue from the already-known [3.9] arrow **redraw-timing** follow-up. Both live in the same code (showTracks / decorator config, two map HTMLs) and should be fixed in the **same arrow-pass** so we build once.

## THE PROBLEM (Fred, field reality)
Track/trail direction arrows are spaced as a **percentage of the whole line**, so on long trails they are far too sparse to read direction at any useful zoom.

- Current decorator config: arrowhead pattern `offset 8%`, `repeat 12%` → roughly **8 arrows across the entire trail** (100 ÷ 12 ≈ 8), regardless of trail length.
- GroupTrack trails are commonly **60–70 miles long**. 8 arrows across 70 miles = **~1 arrow every ~8 miles**.
- To see even one arrow you must have ~8 miles of trail in view — i.e. zoomed so far out the trail is a thread. **At normal riding zoom, the visible segment of a long trail often contains ZERO arrows.** That defeats the feature's purpose (reading which way a track runs at a glance).
- Root cause is the **percent-of-line spacing**: arrow COUNT is tied to trail LENGTH (always ~8), not to what's on screen or to real ground distance. Short trails get cramped arrows; 70-mile trails get almost none.

## THE FIX (approach — pixel-repeat)
Switch the decorator pattern from **percent-repeat** to **pixel-repeat**.

- `L.polylineDecorator` patterns accept `offset` / `repeat` in **pixels** (e.g. `repeat: 80`) instead of a percent string (`'12%'`).
- Pixel spacing places an arrow every N **screen pixels** along the portion of the line currently drawn. This makes arrow density **track the viewport, not the trail length**:
  - Zoom IN → the trail spans more pixels on screen → MORE arrows along the visible stretch (exactly what a rider needs to read direction).
  - Zoom OUT → fewer arrows, no clutter.
  - A 0.5-mile trail and a 70-mile trail get the **same comfortable on-screen spacing**.
- It's a **one-value change** in the pattern config (swap `repeat: '12%'` → `repeat: <px>`; tune the pixel value on device — start ~60–100 px, adjust for readability vs clutter). `offset` likewise to pixels or 0.

### Alternative considered (heavier, not preferred now)
Fixed **ground-distance** spacing (one arrow every N meters of real trail) gives consistent real-world density independent of zoom and length, but requires computing distance along each line — more work than the pixel switch. Pixel-repeat solves the stated complaint (too few arrows at riding zoom) with the smallest change; revisit ground-distance only if pixel-repeat proves insufficient in the field.

## DO IT IN THE SAME ARROW-PASS AS THE REDRAW FIX
When we return to arrows, bundle both known [3.9] follow-ups into one build:
1. **Density (this doc):** percent-repeat → pixel-repeat in the decorator pattern.
2. **Redraw timing (already logged):** decorator only redraws on `moveend`, so on first toggle arrows render late / only one shows until a pan nudges the map. Fix by forcing a redraw in `showTracks` after `trackArrows.addTo(map)` — e.g. `map.fire('moveend')` or call the decorator's redraw.
3. **Secondary suspect if a single arrow persists after both:** track geometry is MULTILINESTRING → `getLayers()` returns one multi-part layer; ties to [3.7] (the MULTILINESTRING parse bug in the GeoJSON builders).

## WHERE THE CODE IS
- Two map HTML assets (CRLF), in `app/src/main/assets/`: `convoy_map.html` and `grouptrack_map.html`. They differ in **script-tag style** (convoy inline `</script>`; planning split-line) — eyeball `<script>`/`</script>` balance before any long asset build.
- Decorator is **vendored locally** at `app/src/main/assets/leaflet.polylineDecorator.js` (CDN copy failed at runtime — `L.Symbol` undefined). Keep it vendored (offline field app).
- Arrows decorate the **displayed DB tracks** (`trackLayer` = `L.geoJSON`), created via `trackArrows = L.polylineDecorator(trackLayer.getLayers(), { patterns:[...] })` — MUST pass `getLayers()`, not the `L.geoJSON` group. Creation is GUARD-wrapped so a missing plugin can never break track display.
- Map HTML is CRLF → use **single-line** python anchors with a match-count guard; mirror the change in BOTH files.

## CHECKLIST WIRING
Extends [3.9] (track direction arrows — committed 2026-06-11, tracks reliable). The arrow REDRAW-timing follow-up was already noted; this adds the arrow-DENSITY problem (percent vs pixel spacing) to the same follow-up. Neither is a release gate; both are polish for when the arrow feature is revisited, after [2h].
